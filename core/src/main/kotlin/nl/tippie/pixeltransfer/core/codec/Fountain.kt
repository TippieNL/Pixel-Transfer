package nl.tippie.pixeltransfer.core.codec

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Parameters shared by the fountain encoder and decoder. Everything here is carried in the
 * frame header, so a receiver that catches a single frame can reconstruct the code.
 */
data class FountainParams(
    /** Number of source blocks. */
    val k: Int,
    /** Bytes per source block (and per encoded symbol). */
    val symbolSize: Int,
    /** Random per-transfer id; also salts the symbol PRNG. */
    val streamId: Int,
    /** Robust soliton shape parameter. Not transmitted: both ends use the same default. */
    val solitonC: Double = DEFAULT_SOLITON_C,
    /** Robust soliton failure-probability parameter. Not transmitted. */
    val solitonDelta: Double = DEFAULT_SOLITON_DELTA,
) {
    init {
        require(k > 0) { "k must be positive" }
        require(symbolSize > 0) { "symbolSize must be positive" }
    }

    companion object {
        const val DEFAULT_SOLITON_C = 0.05
        const val DEFAULT_SOLITON_DELTA = 0.01
    }
}

/**
 * Robust soliton degree distribution (Luby). Precomputes a CDF so sampling is a single binary
 * search per symbol.
 */
internal class RobustSoliton(private val k: Int, c: Double, delta: Double) {

    private val cdf: DoubleArray

    init {
        val kd = k.toDouble()
        val r = max(1.0, c * ln(kd / delta) * sqrt(kd))
        val pivot = min(k, max(1, ceil(kd / r).toInt()))

        val p = DoubleArray(k + 1)
        // Ideal soliton.
        p[1] = 1.0 / kd
        for (i in 2..k) p[i] = 1.0 / (i.toDouble() * (i - 1).toDouble())
        // Robust spike.
        for (i in 1 until pivot) p[i] += r / (i.toDouble() * kd)
        if (pivot <= k) p[pivot] += r * ln(r / delta) / kd

        val beta = p.sum()
        cdf = DoubleArray(k + 1)
        var acc = 0.0
        for (i in 1..k) {
            acc += p[i] / beta
            cdf[i] = acc
        }
        cdf[k] = 1.0
    }

    fun sample(rng: Prng): Int {
        val u = rng.nextDouble()
        var lo = 1
        var hi = k
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cdf[mid] < u) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Maps an encoded symbol id to the set of source blocks it XORs together.
 *
 * ESIs below `k` are *systematic*: symbol i is source block i verbatim. Above that the neighbour
 * set is drawn from the robust soliton distribution using a PRNG seeded by (streamId, esi), so
 * the sequence of distinct symbols is unbounded and the receiver can reconstruct any of them
 * from the ESI alone.
 */
class SymbolPlan(private val params: FountainParams) {

    private val soliton = RobustSoliton(params.k, params.solitonC, params.solitonDelta)

    fun neighbors(esi: Int): IntArray {
        require(esi >= 0) { "esi must be non-negative" }
        val k = params.k
        if (esi < k) return intArrayOf(esi)

        val rng = Prng(Prng.seedFor(params.streamId, esi))
        val degree = min(k, soliton.sample(rng))

        // Partial Fisher-Yates: draws `degree` distinct indices from 0 until k in O(degree).
        val swapped = HashMap<Int, Int>(degree * 2)
        val out = IntArray(degree)
        for (i in 0 until degree) {
            val j = i + rng.nextInt(k - i)
            val vj = swapped[j] ?: j
            val vi = swapped[i] ?: i
            out[i] = vj
            swapped[j] = vi
        }
        return out
    }
}

/**
 * Produces an unbounded stream of encoded symbols from a fixed source object.
 *
 * The source object is zero-padded to `k * symbolSize`; the true length travels in the frame
 * header so the receiver can trim it.
 */
class FountainEncoder(val params: FountainParams, source: ByteArray) {

    private val plan = SymbolPlan(params)
    private val blocks: Array<ByteArray> = Array(params.k) { i ->
        val start = i * params.symbolSize
        val block = ByteArray(params.symbolSize)
        if (start < source.size) {
            val len = min(params.symbolSize, source.size - start)
            source.copyInto(block, 0, start, start + len)
        }
        block
    }

    fun symbol(esi: Int): ByteArray {
        val neighbors = plan.neighbors(esi)
        val out = blocks[neighbors[0]].copyOf()
        for (n in 1 until neighbors.size) {
            val b = blocks[neighbors[n]]
            for (i in out.indices) out[i] = (out[i].toInt() xor b[i].toInt()).toByte()
        }
        return out
    }

    companion object {
        /** Number of source blocks needed for an object of [size] bytes at [symbolSize]. */
        fun blockCount(size: Int, symbolSize: Int): Int =
            max(1, (size + symbolSize - 1) / symbolSize)
    }
}

/**
 * Incremental fountain decoder.
 *
 * Symbols may arrive in any order, with any gaps, and duplicates are cheap to reject. The main
 * engine is belief propagation ("peeling"); when peeling stalls with only a small residual set
 * of unknown blocks, [tryFinish] runs Gauss-Jordan elimination over GF(2) on the residual, which
 * is what keeps the reception overhead near the 5% target instead of the 10-20% plain peeling
 * would need.
 */
class FountainDecoder(
    val params: FountainParams,
    /** Upper bound on the residual system size the elimination fallback will attempt. */
    private val maxEliminationUnknowns: Int = 4096,
) {

    private class Equation(var neighbors: MutableSet<Int>, val value: ByteArray) {
        var alive = true
    }

    private val plan = SymbolPlan(params)
    private val blocks = arrayOfNulls<ByteArray>(params.k)
    private val equations = ArrayList<Equation>()
    private val refs = Array(params.k) { ArrayList<Equation>() }
    private val seenEsi = HashSet<Int>()

    var recoveredBlocks: Int = 0
        private set

    /** Symbols accepted (distinct ESIs), including ones that turned out to be redundant. */
    var symbolsAccepted: Int = 0
        private set

    var symbolsRedundant: Int = 0
        private set

    private var symbolsSinceElimination = 0

    val isComplete: Boolean get() = recoveredBlocks == params.k

    /** Fraction of source blocks recovered, 0..1. */
    val progress: Float get() = recoveredBlocks.toFloat() / params.k

    fun hasSeen(esi: Int): Boolean = seenEsi.contains(esi)

    fun block(index: Int): ByteArray? = blocks[index]

    /**
     * Feeds one encoded symbol. Returns true when the symbol was new (regardless of whether it
     * immediately unlocked anything).
     */
    fun offer(esi: Int, data: ByteArray): Boolean {
        require(data.size == params.symbolSize) {
            "symbol size mismatch: expected ${params.symbolSize}, got ${data.size}"
        }
        if (isComplete) return false
        if (!seenEsi.add(esi)) return false
        symbolsAccepted++
        symbolsSinceElimination++

        val value = data.copyOf()
        val remaining = HashSet<Int>()
        for (n in plan.neighbors(esi)) {
            val known = blocks[n]
            if (known != null) {
                xorInto(value, known)
            } else if (!remaining.add(n)) {
                // A block XORed with itself cancels; the plan never repeats, but stay safe.
                remaining.remove(n)
            }
        }

        when (remaining.size) {
            0 -> {
                symbolsRedundant++
                return true
            }
            1 -> {
                val idx = remaining.first()
                setBlock(idx, value)
                cascade(idx)
            }
            else -> {
                val eq = Equation(remaining, value)
                equations.add(eq)
                for (n in remaining) refs[n].add(eq)
            }
        }
        return true
    }

    private fun setBlock(index: Int, value: ByteArray) {
        if (blocks[index] != null) return
        blocks[index] = value
        recoveredBlocks++
    }

    /** Substitutes a freshly decoded block into every equation that references it. */
    private fun cascade(startIndex: Int) {
        val queue = ArrayDeque<Int>()
        queue.add(startIndex)
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val known = blocks[idx] ?: continue
            val referencing = refs[idx]
            for (eq in referencing) {
                if (!eq.alive) continue
                if (!eq.neighbors.remove(idx)) continue
                xorInto(eq.value, known)
                when (eq.neighbors.size) {
                    0 -> eq.alive = false
                    1 -> {
                        val next = eq.neighbors.first()
                        eq.alive = false
                        if (blocks[next] == null) {
                            setBlock(next, eq.value)
                            queue.add(next)
                        }
                    }
                }
            }
            referencing.clear()
        }
    }

    /**
     * Attempts to close out the decode with Gaussian elimination over GF(2) when peeling has
     * stalled. Cheap to call repeatedly: a rank pre-check on the bit matrix alone (no payload
     * XORs) decides whether the far more expensive value elimination is worth running, and
     * attempts are throttled in proportion to the size of the residual system.
     *
     * @return true if the decoder is complete after this call.
     */
    fun tryFinish(force: Boolean = false): Boolean {
        if (isComplete) return true
        val unknowns = params.k - recoveredBlocks
        if (unknowns > maxEliminationUnknowns) return false

        pruneDeadEquations()
        val live = equations.size
        if (live < unknowns) return false

        val minGap = if (force) 1 else max(1, unknowns / 64)
        if (symbolsSinceElimination < minGap) return false
        symbolsSinceElimination = 0

        // Compact the unknown block indices into a dense column space.
        val columnOf = HashMap<Int, Int>(unknowns * 2)
        val blockOf = IntArray(unknowns)
        var col = 0
        for (i in 0 until params.k) {
            if (blocks[i] == null) {
                columnOf[i] = col
                blockOf[col] = i
                col++
            }
        }

        val words = (unknowns + 63) / 64
        val rows = ArrayList<LongArray>(live)
        val sources = ArrayList<Equation>(live)
        for (eq in equations) {
            val row = LongArray(words)
            for (n in eq.neighbors) {
                val c = columnOf[n] ?: continue
                row[c ushr 6] = row[c ushr 6] xor (1L shl (c and 63))
            }
            rows.add(row)
            sources.add(eq)
        }
        if (rows.size < unknowns) return false

        // Cheap pass: is the system solvable at all? Only the bit matrix is touched here.
        if (!hasFullRank(rows, unknowns)) return false

        // Expensive pass: the same elimination, this time carrying the payloads along.
        // hasFullRank worked on copies, so `rows` is still the untouched bit matrix.
        val matrix = rows
        val values = ArrayList<ByteArray>(rows.size)
        for (eq in sources) values.add(eq.value.copyOf())

        val pivotRow = IntArray(unknowns) { -1 }
        var r = 0
        for (c in 0 until unknowns) {
            var sel = -1
            for (i in r until matrix.size) {
                if (matrix[i][c ushr 6] and (1L shl (c and 63)) != 0L) { sel = i; break }
            }
            if (sel < 0) continue
            swap(matrix, values, r, sel)
            for (i in r + 1 until matrix.size) {
                if (matrix[i][c ushr 6] and (1L shl (c and 63)) != 0L) {
                    xorRow(matrix[i], matrix[r])
                    xorInto(values[i], values[r])
                }
            }
            pivotRow[c] = r
            r++
            if (r == matrix.size) break
        }
        if (pivotRow.any { it < 0 }) return false

        // Back substitution over the upper-triangular remainder.
        for (c in unknowns - 1 downTo 0) {
            val pr = pivotRow[c]
            val value = values[pr]
            val row = matrix[pr]
            for (c2 in c + 1 until unknowns) {
                if (row[c2 ushr 6] and (1L shl (c2 and 63)) != 0L) {
                    xorInto(value, values[pivotRow[c2]])
                    row[c2 ushr 6] = row[c2 ushr 6] xor (1L shl (c2 and 63))
                }
            }
            setBlock(blockOf[c], value)
        }

        for (eq in equations) eq.alive = false
        equations.clear()
        for (list in refs) list.clear()
        return isComplete
    }

    /** Gauss elimination on the bit matrix only, used as a solvability probe. */
    private fun hasFullRank(rows: List<LongArray>, unknowns: Int): Boolean {
        val words = (unknowns + 63) / 64
        val work = ArrayList<LongArray>(rows.size)
        for (row in rows) work.add(row.copyOf())
        var r = 0
        for (c in 0 until unknowns) {
            var sel = -1
            for (i in r until work.size) {
                if (work[i][c ushr 6] and (1L shl (c and 63)) != 0L) { sel = i; break }
            }
            if (sel < 0) return false
            val tmp = work[r]; work[r] = work[sel]; work[sel] = tmp
            val pivot = work[r]
            for (i in r + 1 until work.size) {
                val row = work[i]
                if (row[c ushr 6] and (1L shl (c and 63)) != 0L) {
                    for (w in (c ushr 6) until words) row[w] = row[w] xor pivot[w]
                }
            }
            r++
            if (r == work.size && c < unknowns - 1) return false
        }
        return true
    }

    private fun pruneDeadEquations() {
        if (equations.none { !it.alive }) return
        equations.retainAll { it.alive }
    }

    /** Concatenates the recovered blocks and trims to [totalSize]. Requires [isComplete]. */
    fun assemble(totalSize: Int): ByteArray {
        check(isComplete) { "decode is not complete" }
        val out = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until params.k) {
            if (offset >= totalSize) break
            val block = blocks[i]!!
            val len = min(params.symbolSize, totalSize - offset)
            block.copyInto(out, offset, 0, len)
            offset += len
        }
        return out
    }

    /**
     * Returns the leading run of contiguous recovered bytes, which is how the receiver can show
     * the filename before the whole file has arrived.
     */
    fun recoveredPrefix(): ByteArray {
        var n = 0
        while (n < params.k && blocks[n] != null) n++
        if (n == 0) return ByteArray(0)
        val out = ByteArray(n * params.symbolSize)
        for (i in 0 until n) blocks[i]!!.copyInto(out, i * params.symbolSize)
        return out
    }

    private fun swap(rows: MutableList<LongArray>, values: MutableList<ByteArray>, a: Int, b: Int) {
        if (a == b) return
        val tr = rows[a]; rows[a] = rows[b]; rows[b] = tr
        val tv = values[a]; values[a] = values[b]; values[b] = tv
    }

    private fun xorRow(dst: LongArray, src: LongArray) {
        for (i in dst.indices) dst[i] = dst[i] xor src[i]
    }

    private fun xorInto(dst: ByteArray, src: ByteArray) {
        for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
    }
}
