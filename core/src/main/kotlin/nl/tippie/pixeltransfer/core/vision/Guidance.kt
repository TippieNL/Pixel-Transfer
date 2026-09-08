package nl.tippie.pixeltransfer.core.vision

/** A single piece of live advice for the person holding the receiving phone. */
data class Hint(val message: String, val severity: Severity) {
    enum class Severity { INFO, WARN, ERROR }
}

/**
 * Turns per-frame diagnostics into the short instructions shown over the camera preview.
 *
 * Hints are ranked so only the most useful one or two are shown; a wall of advice while the user
 * is trying to hold a phone steady is worse than none.
 */
object Guidance {

    /** Cells smaller than this in the capture cannot be classified reliably. */
    private const val MIN_CELL_PIXELS = 2.2

    /** Beyond this the cell grid is being oversampled and the user could back off. */
    private const val COMFORTABLE_CELL_PIXELS = 3.0

    fun hints(
        status: ReadStatus,
        diagnostics: FrameDiagnostics,
        recentGoodFrames: Int,
        recentAttempts: Int,
    ): List<Hint> {
        val out = ArrayList<Hint>(3)

        when (status) {
            ReadStatus.NO_FINDERS -> out.add(
                Hint("Point the camera at the sending screen", Hint.Severity.INFO),
            )
            ReadStatus.NO_GRID -> out.add(
                Hint("Fit the whole pattern in view", Hint.Severity.WARN),
            )
            ReadStatus.NO_CALIBRATION -> out.add(
                Hint("Colours unreadable - reduce glare or move closer", Hint.Severity.WARN),
            )
            ReadStatus.TORN -> out.add(
                Hint("Hold steady - frames are tearing", Hint.Severity.WARN),
            )
            ReadStatus.HEADER_FAILED, ReadStatus.NO_SYMBOLS -> out.add(
                Hint("Too noisy to decode - hold steady and move closer", Hint.Severity.WARN),
            )
            ReadStatus.OK -> Unit
        }

        if (status != ReadStatus.NO_FINDERS) {
            if (diagnostics.cellPixelSize in 0.01..MIN_CELL_PIXELS) {
                out.add(Hint("Move closer", Hint.Severity.WARN))
            } else if (diagnostics.cellPixelSize > 12.0) {
                out.add(Hint("Move back a little", Hint.Severity.INFO))
            }
            if (diagnostics.glareFraction > 0.08) {
                out.add(Hint("Glare detected - change the angle slightly", Hint.Severity.WARN))
            }
            if (diagnostics.contrast in 0.01..45.0) {
                out.add(Hint("Too dark - raise the sender's brightness", Hint.Severity.WARN))
            }
            if (diagnostics.offAxisDegrees > 35.0) {
                out.add(Hint("Reduce angle - hold the phones more parallel", Hint.Severity.WARN))
            }
            if (diagnostics.sharpness in 0.01..0.25 && diagnostics.cellPixelSize > COMFORTABLE_CELL_PIXELS) {
                out.add(Hint("Blurred - hold steady while focus settles", Hint.Severity.WARN))
            }
            if (diagnostics.rsBlocksTotal > 0 &&
                diagnostics.rsBlocksFailed > diagnostics.rsBlocksTotal / 3
            ) {
                out.add(Hint("Heavy errors - try Robust mode or move closer", Hint.Severity.WARN))
            }
        }

        if (recentAttempts >= 30 && recentGoodFrames == 0) {
            out.add(
                Hint(
                    "No frames decoded yet - check that the sender is playing and at full brightness",
                    Hint.Severity.ERROR,
                ),
            )
        }

        return out.distinctBy { it.message }.take(2)
    }
}
