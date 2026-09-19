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


    fun hints(
        status: ReadStatus,
        diagnostics: FrameDiagnostics,
        recentGoodFrames: Int,
        recentAttempts: Int,
    ): List<Hint> {
        val out = ArrayList<Hint>(3)

        when (status) {
            ReadStatus.NO_FINDERS -> out.add(
                if (recentAttempts > 15 && recentGoodFrames == 0) {
                    // The user is plainly already pointing at it, so say something useful.
                    Hint("Move closer so the pattern fills the frame", Hint.Severity.WARN)
                } else {
                    Hint("Point the camera at the sending screen", Hint.Severity.INFO)
                },
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

        if (diagnostics.clippedFraction > 0.10 && diagnostics.glareImbalance < 0.08) {
            // Clipping spread evenly over the frame is the camera over-exposing an emissive
            // display, which collapses the top palette levels into each other.
            out.add(Hint("Too bright - the camera is clipping", Hint.Severity.WARN))
        } else if (diagnostics.glareImbalance > 0.08) {
            out.add(Hint("Glare - tilt slightly to move the reflection", Hint.Severity.WARN))
        }

        if (status != ReadStatus.NO_FINDERS) {
            if (diagnostics.cellPixelSize in 0.01..MIN_CELL_PIXELS) {
                out.add(Hint("Move closer", Hint.Severity.WARN))
            } else if (diagnostics.cellPixelSize > 16.0) {
                out.add(Hint("Move back a little", Hint.Severity.INFO))
            }
            if (diagnostics.sharpness in 0.01..0.45) {
                out.add(Hint("Out of focus - hold steady, or tap to refocus", Hint.Severity.WARN))
            }
            if (diagnostics.contrast in 0.01..45.0) {
                out.add(Hint("Too dark - raise the sender's brightness", Hint.Severity.WARN))
            }
            if (diagnostics.offAxisDegrees > 35.0) {
                out.add(Hint("Reduce angle - hold the phones more parallel", Hint.Severity.WARN))
            }
            if (diagnostics.tornRowFraction > 0.0) {
                out.add(Hint("Hold steady - frames are tearing", Hint.Severity.WARN))
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
