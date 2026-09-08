package nl.tippie.pixeltransfer.core.frame

import nl.tippie.pixeltransfer.core.codec.ByteIo
import nl.tippie.pixeltransfer.core.codec.Crc32
import nl.tippie.pixeltransfer.core.codec.RsCodec

/** One fountain symbol as it travels inside a frame. */
data class SymbolPacket(val esi: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SymbolPacket && esi == other.esi && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * esi + data.contentHashCode()
}

/**
 * Capacity plan for one frame: how many cells go to the header, how many bytes of payload
 * survive Reed-Solomon, and how many fountain symbols therefore fit.
 *
 * The header always uses the Robust palette (3 bits/cell) because it has to be readable before
 * the receiver knows which palette the payload uses.
 */
class FramePlan(
    val layout: FrameLayout,
    val paletteMode: PaletteMode,
    val eccLevel: EccLevel,
    val blockSize: Int,
) {

    val headerCellCount: Int =
        Bits.symbolsNeeded(FrameHeader.ENCODED_SIZE, PaletteMode.ROBUST.bitsPerCell)

    val payloadCellCount: Int = layout.dataCells.size - headerCellCount

    /** Bytes of Reed-Solomon-encoded payload the cells can carry. */
    val payloadEncodedCapacity: Int = (payloadCellCount * paletteMode.bitsPerCell) / 8

    private val rs = RsCodec(eccLevel.nsym)

    /** Payload bytes before Reed-Solomon; fixed per configuration so both ends agree. */
    val payloadDataSize: Int = run {
        var size = 0
        val dataPerBlock = 255 - eccLevel.nsym
        // Start from an optimistic estimate then walk down/up to the exact fit.
        var guess = (payloadEncodedCapacity.toLong() * dataPerBlock / 255).toInt()
        while (guess > 0 && rs.encodedSize(guess) > payloadEncodedCapacity) guess--
        while (rs.encodedSize(guess + 1) <= payloadEncodedCapacity) guess++
        size = guess.coerceAtLeast(0)
        size
    }

    val packetSize: Int = ESI_SIZE + blockSize + CRC_SIZE

    val symbolsPerFrame: Int = ((payloadDataSize - FOOTER_SIZE) / packetSize).coerceAtLeast(0)

    /** Payload bytes per frame once symbol and footer overhead is removed. */
    val payloadBytesPerFrame: Int get() = symbolsPerFrame * blockSize

    /** Payload bytes actually used by symbols plus footer; the rest is zero padding. */
    val usedPayloadBytes: Int get() = symbolsPerFrame * packetSize + FOOTER_SIZE

    val isUsable: Boolean get() = symbolsPerFrame >= 1

    init {
        require(headerCellCount <= layout.dataCells.size) { "grid too small for a frame header" }
    }

    companion object {
        const val ESI_SIZE = 4
        const val CRC_SIZE = 4
        const val FOOTER_SIZE = 8
        val END_MARKER = byteArrayOf(0xA5.toByte(), 0x5A, 0xC3.toByte(), 0x3C)
    }
}

/**
 * Turns a frame header plus a handful of fountain symbols into a grid of ARGB cell colours, and
 * back again. Everything above this class is bytes; everything below it is pixels.
 */
class FrameCodec(val plan: FramePlan) {

    private val layout = plan.layout
    private val rs = RsCodec(plan.eccLevel.nsym)
    private val headerRs = RsCodec(FrameHeader.RS_PARITY)

    /** Renders one frame as ARGB per cell, in row-major grid order. */
    fun render(header: FrameHeader, symbols: List<SymbolPacket>): IntArray {
        require(symbols.size <= plan.symbolsPerFrame) {
            "frame holds ${plan.symbolsPerFrame} symbols, got ${symbols.size}"
        }
        val cells = IntArray(layout.grid * layout.grid)

        renderStructure(cells, header.nonce)

        // Header: 32 bytes + its own RS parity, always in the Robust palette.
        val headerBytes = headerRs.encode(header.encode())
        val headerSymbols = Bits.bytesToSymbols(
            headerBytes, PaletteMode.ROBUST.bitsPerCell, plan.headerCellCount,
        )
        for (i in 0 until plan.headerCellCount) {
            cells[layout.dataCells[i]] = Palette.colorOf(PaletteMode.ROBUST, headerSymbols[i])
        }

        // Payload.
        val payload = buildPayload(symbols)
        val encoded = rs.encode(payload)
        val payloadSymbols = Bits.bytesToSymbols(
            encoded, plan.paletteMode.bitsPerCell, plan.payloadCellCount,
        )
        for (i in 0 until plan.payloadCellCount) {
            cells[layout.dataCells[plan.headerCellCount + i]] =
                Palette.colorOf(plan.paletteMode, payloadSymbols[i])
        }
        return cells
    }

    private fun buildPayload(symbols: List<SymbolPacket>): ByteArray {
        val out = ByteArray(plan.payloadDataSize)
        var offset = 0
        for (packet in symbols) {
            require(packet.data.size == plan.blockSize) {
                "symbol size ${packet.data.size} != ${plan.blockSize}"
            }
            ByteIo.putInt(out, offset, packet.esi)
            packet.data.copyInto(out, offset + FramePlan.ESI_SIZE)
            val crc = Crc32.of(out, offset, FramePlan.ESI_SIZE + plan.blockSize)
            ByteIo.putInt(out, offset + FramePlan.ESI_SIZE + plan.blockSize, crc)
            offset += plan.packetSize
        }
        val footerAt = plan.payloadDataSize - FramePlan.FOOTER_SIZE
        FramePlan.END_MARKER.copyInto(out, footerAt)
        ByteIo.putInt(out, footerAt + 4, Crc32.of(out, 0, footerAt))
        return out
    }

    /** Fills in every non-payload cell: finders, timing, corner ids, calibration, tear stripe. */
    fun renderStructure(cells: IntArray, nonce: Int) {
        for (y in 0 until layout.grid) {
            for (x in 0 until layout.grid) {
                val i = layout.index(x, y)
                cells[i] = when (layout.roles[i]) {
                    CellRole.FINDER ->
                        if (layout.finderIsDark(x, y)) Palette.DARK else Palette.LIGHT

                    CellRole.TIMING ->
                        if (layout.timingIsDark(x, y)) Palette.DARK else Palette.LIGHT

                    CellRole.CORNER_ID -> {
                        val corner = layout.cornerOfIdCell(x, y)
                        val origin = layout.cornerIdOrigins()[corner]
                        val light = layout.cornerIdIsLight(corner, x - origin[0], y - origin[1])
                        if (light) Palette.LIGHT else Palette.DARK
                    }

                    CellRole.CALIBRATION ->
                        CalibrationRamp.colorOf(layout.calibrationPatchOf(x, y))

                    CellRole.NONCE_STRIPE -> {
                        val column = x - layout.nonceCols.first
                        if (FrameHeader.stripeBit(nonce, y, column)) Palette.LIGHT else Palette.DARK
                    }

                    CellRole.DATA -> cells[i]
                }
            }
        }
    }

    /** Result of pulling the payload back out of a captured frame. */
    class PayloadResult(
        val symbols: List<SymbolPacket>,
        val footerValid: Boolean,
        val rsBlocksTotal: Int,
        val rsBlocksRepaired: Int,
        val rsBlocksFailed: Int,
        val byteErrorsCorrected: Int,
    )

    /**
     * Decodes the payload region from classified cell symbols.
     *
     * Symbols are accepted individually on their own CRC32, so a frame whose footer check fails
     * - one Reed-Solomon block beyond repair, say - still contributes every packet that came
     * through intact instead of being thrown away wholesale.
     */
    fun decodePayload(payloadSymbols: IntArray): PayloadResult {
        val encoded = Bits.symbolsToBytes(
            payloadSymbols, plan.paletteMode.bitsPerCell, rs.encodedSize(plan.payloadDataSize),
        )
        val decoded = rs.decode(encoded, plan.payloadDataSize)
        val data = decoded.data

        val footerAt = plan.payloadDataSize - FramePlan.FOOTER_SIZE
        var footerValid = true
        for (i in FramePlan.END_MARKER.indices) {
            if (data[footerAt + i] != FramePlan.END_MARKER[i]) { footerValid = false; break }
        }
        if (footerValid && Crc32.of(data, 0, footerAt) != ByteIo.getInt(data, footerAt + 4)) {
            footerValid = false
        }

        val out = ArrayList<SymbolPacket>(plan.symbolsPerFrame)
        var offset = 0
        for (i in 0 until plan.symbolsPerFrame) {
            val bodyLen = FramePlan.ESI_SIZE + plan.blockSize
            val expected = ByteIo.getInt(data, offset + bodyLen)
            if (Crc32.of(data, offset, bodyLen) == expected) {
                val esi = ByteIo.getInt(data, offset)
                if (esi >= 0) {
                    out.add(SymbolPacket(esi, data.copyOfRange(offset + FramePlan.ESI_SIZE, offset + bodyLen)))
                }
            }
            offset += plan.packetSize
        }

        val failed = decoded.blockOk.count { !it }
        return PayloadResult(
            symbols = out,
            footerValid = footerValid,
            rsBlocksTotal = decoded.blockOk.size,
            rsBlocksRepaired = decoded.blockOk.size - failed,
            rsBlocksFailed = failed,
            byteErrorsCorrected = decoded.errorsCorrected,
        )
    }

    companion object {
        /** Decodes the frame header from the Robust-palette header cells. */
        fun decodeHeader(layout: FrameLayout, headerSymbols: IntArray): FrameHeader? {
            val bytes = Bits.symbolsToBytes(
                headerSymbols, PaletteMode.ROBUST.bitsPerCell, FrameHeader.ENCODED_SIZE,
            )
            val result = RsCodec(FrameHeader.RS_PARITY).decode(bytes, FrameHeader.SIZE)
            if (!result.allOk) return null
            return FrameHeader.decode(result.data)
        }

        fun headerCellCount(): Int =
            Bits.symbolsNeeded(FrameHeader.ENCODED_SIZE, PaletteMode.ROBUST.bitsPerCell)
    }
}
