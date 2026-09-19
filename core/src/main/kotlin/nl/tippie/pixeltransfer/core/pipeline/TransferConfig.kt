package nl.tippie.pixeltransfer.core.pipeline

import nl.tippie.pixeltransfer.core.codec.CompressionType
import nl.tippie.pixeltransfer.core.frame.EccLevel
import nl.tippie.pixeltransfer.core.frame.FrameLayout
import nl.tippie.pixeltransfer.core.frame.PaletteMode

/**
 * Everything the sender can tune. Defaults are the reference configuration from the spec:
 * 96x96 cells at 8x8 pixels (a 768x768 display area), Balanced palette, ~20% Reed-Solomon,
 * 1 KiB source blocks, 12 fps.
 */
data class SenderConfig(
    val paletteMode: PaletteMode = PaletteMode.BALANCED,
    val eccLevel: EccLevel = EccLevel.MEDIUM,
    val gridCells: Int = DEFAULT_GRID,
    val cellSizePx: Int = DEFAULT_CELL_SIZE,
    val blockSize: Int = DEFAULT_BLOCK_SIZE,
    val fps: Int = DEFAULT_FPS,
    /** null means "decide by measuring". */
    val compression: CompressionType? = CompressionType.DEFLATE,
    val maxFileSizeBytes: Int = DEFAULT_MAX_FILE_SIZE,
    /** Bytes per independently coded segment. */
    val segmentSizeBytes: Int = SegmentPlan.DEFAULT_SEGMENT_SIZE,
    /**
     * Frames spent on each segment relative to the theoretical minimum.
     *
     * With no back channel the sender cannot know when a segment has landed, so it shows each one
     * for longer than strictly necessary and then moves on. Too little margin and most receivers
     * need a second pass over the whole file; too much and every receiver waits for frames it
     * already has.
     */
    val segmentRedundancy: Double = DEFAULT_SEGMENT_REDUNDANCY,
) {
    init {
        require(cellSizePx in MIN_CELL_SIZE..MAX_CELL_SIZE) {
            "cell size must be $MIN_CELL_SIZE..$MAX_CELL_SIZE px, was $cellSizePx"
        }
        require(fps in MIN_FPS..MAX_FPS) { "fps must be $MIN_FPS..$MAX_FPS, was $fps" }
        require(gridCells >= FrameLayout.MIN_GRID && gridCells % 2 == 0) {
            "grid must be even and at least ${FrameLayout.MIN_GRID}"
        }
        require(blockSize in 64..8192) { "block size must be 64..8192 bytes" }
        require(segmentSizeBytes >= 64 * 1024) { "segment size must be at least 64 KiB" }
        require(segmentRedundancy in 1.0..4.0) { "segment redundancy must be 1.0..4.0" }
    }

    /** Pixel width/height of the rendered code including the quiet border. */
    val imageSizePx: Int get() = (gridCells + 2 * QUIET_CELLS) * cellSizePx

    companion object {
        const val DEFAULT_GRID = 96
        const val DEFAULT_CELL_SIZE = 8
        const val DEFAULT_BLOCK_SIZE = 1024
        const val DEFAULT_FPS = 12
        const val DEFAULT_MAX_FILE_SIZE = 150 * 1024 * 1024

        const val DEFAULT_SEGMENT_REDUNDANCY = 1.5

        /**
         * A 1:1 mapping onto physical screen pixels is not recoverable by any phone camera, so
         * four is the hard floor.
         */
        const val MIN_CELL_SIZE = 4
        const val MAX_CELL_SIZE = 16

        const val MIN_FPS = 5

        /**
         * Above 20 fps a 30 fps camera can no longer guarantee that each displayed frame is
         * visible for a full exposure, so frames start tearing faster than the code can absorb.
         */
        const val MAX_FPS = 20

        /** Quiet border, in cells, drawn light around the code area. */
        const val QUIET_CELLS = 2

        /** Warn about transfer time above this size. */
        const val SLOW_TRANSFER_WARNING_BYTES = 512 * 1024

        /** Above this, warn that the transfer runs for tens of minutes and must not be disturbed. */
        const val VERY_SLOW_TRANSFER_BYTES = 20 * 1024 * 1024
    }
}
