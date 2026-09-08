package nl.tippie.pixeltransfer.sender

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.tippie.pixeltransfer.core.frame.EccLevel
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.ui.Banner
import nl.tippie.pixeltransfer.ui.BannerTone
import nl.tippie.pixeltransfer.ui.ChipRow
import nl.tippie.pixeltransfer.ui.LabelledSlider
import nl.tippie.pixeltransfer.ui.SectionCard
import nl.tippie.pixeltransfer.ui.StatRow
import nl.tippie.pixeltransfer.util.formatBytes
import nl.tippie.pixeltransfer.util.formatDuration

/** Offered file-size caps. The default is 2 MB; above that transfers become tedious. */
private val FILE_SIZE_LIMITS = listOf(
    512 * 1024,
    1024 * 1024,
    2 * 1024 * 1024,
    4 * 1024 * 1024,
    8 * 1024 * 1024,
)

@Composable
fun SenderSetupScreen(
    viewModel: SenderViewModel,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::pick) }

    var exportFormat by remember { mutableStateOf(ExportFormat.PNG_SEQUENCE) }
    // One contract instance for every format: the concrete type comes from the file name we
    // suggest, and re-creating the contract when the chip selection changes would invalidate the
    // launcher mid-flow.
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { viewModel.export(it, exportFormat) } }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Send", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        state.error?.let { Banner(it, BannerTone.ERROR) }
        state.notice?.let { Banner(it, BannerTone.INFO) }

        SectionCard("File") {
            val file = state.file
            if (file == null) {
                Text(
                    "Pick a file, or share one to PixelTransfer from any other app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatRow("Name", file.displayName)
                StatRow("Type", file.mimeType)
                StatRow("Size", formatBytes(file.bytes.size.toLong()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Choose file") }
                if (file != null) {
                    OutlinedButton(onClick = viewModel::clear) { Text("Clear") }
                }
            }
        }

        val transfer = state.transfer
        if (transfer != null) {
            SectionCard("Encoded stream") {
                StatRow(
                    "Compressed",
                    "${formatBytes(transfer.metadata.compressedSize.toLong())} " +
                        "(${"%.0f".format(transfer.metadata.compressionRatio * 100)}% of original)",
                )
                StatRow("Compression", transfer.metadata.compression.name)
                StatRow("Source blocks (K)", "${transfer.sourceBlocks} x ${transfer.fountainParams.symbolSize} B")
                StatRow("Symbols needed", "${transfer.symbolsNeeded}")
                StatRow("Symbols per frame", "${transfer.symbolsPerFrame}")
                StatRow("Payload per frame", formatBytes(transfer.bytesPerFrame.toLong()))
                StatRow("Frames per loop", "${transfer.framesPerLoop}")
                StatRow(
                    "Estimated transfer time",
                    formatDuration(transfer.estimatedTransferSeconds()),
                    emphasis = true,
                )
                Text(
                    "Best case ${formatDuration(transfer.idealTransferSeconds())} if the receiver " +
                        "decodes every displayed frame. There is no back channel, so how long it " +
                        "actually takes depends on how well the camera sees the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.slowTransferWarning) {
                Banner(
                    "This file is over 512 KB. Expect the transfer to take a while and the phones " +
                        "to need holding steady throughout.",
                    BannerTone.WARNING,
                )
            }
            if (state.blockSizeReduced) {
                Banner(
                    "Source blocks were reduced to ${transfer.fountainParams.symbolSize} bytes: a " +
                        "${state.config.blockSize}-byte symbol does not fit in one frame in " +
                        "${state.config.paletteMode.label}.",
                    BannerTone.INFO,
                )
            }
        }

        SectionCard("Limits") {
            Text("Maximum file size", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = FILE_SIZE_LIMITS,
                selected = state.config.maxFileSizeBytes,
                label = { formatBytes(it.toLong()) },
                onSelect = viewModel::setMaxFileSize,
            )
            Text(
                "The whole file is held in memory while it is encoded, and a bigger file means a " +
                    "proportionally longer transfer - there is no way to speed it up beyond the " +
                    "palette and frame rate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Modulation") {
            Text("Palette", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = PaletteMode.entries.toList(),
                selected = state.config.paletteMode,
                label = { it.label },
                onSelect = viewModel::setPaletteMode,
            )
            Text(
                state.config.paletteMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Text("Error correction inside each frame", style = MaterialTheme.typography.bodyMedium)
            ChipRow(
                options = EccLevel.entries.toList(),
                selected = state.config.eccLevel,
                label = { it.label },
                onSelect = viewModel::setEccLevel,
            )

            LabelledSlider(
                label = "Cell size",
                value = state.config.cellSizePx,
                range = SenderConfig.MIN_CELL_SIZE..SenderConfig.MAX_CELL_SIZE,
                onChange = viewModel::setCellSize,
                suffix = " px",
            )
            Text(
                "Each logical cell is drawn as an N x N block of screen pixels. One screen pixel " +
                    "per cell is not recoverable by any phone camera and is not offered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LabelledSlider(
                label = "Frame rate",
                value = state.config.fps,
                range = SenderConfig.MIN_FPS..SenderConfig.MAX_FPS,
                onChange = viewModel::setFps,
                suffix = " fps",
            )
            Text(
                "Above 20 fps a 30 fps camera can no longer see each frame for a full exposure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (transfer != null) {
            SectionCard("Export the stream") {
                val formats = ExportFormat.entries.filter {
                    it != ExportFormat.SINGLE_FRAME || transfer.fitsInOneFrame
                }
                // The selection can fall out of range when the file grows past one frame. Resolve
                // it for display rather than writing state back during composition.
                val activeFormat = exportFormat.takeIf { it in formats } ?: ExportFormat.PNG_SEQUENCE
                ChipRow(
                    options = formats,
                    selected = activeFormat,
                    label = { it.label },
                    onSelect = { exportFormat = it },
                )
                if (transfer.fitsInOneFrame) {
                    Text(
                        "This file is small enough that one frame carries all of it, so a single " +
                            "still image is a complete transfer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!StreamExporter.isSupported(activeFormat, state.config.paletteMode)) {
                    Text(
                        "Video compression destroys ${state.config.paletteMode.label}: adjacent " +
                            "levels are too close together to survive chroma subsampling. Use a " +
                            "lossless image sequence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                Button(
                    onClick = {
                        exportFormat = activeFormat
                        val base = state.file?.displayName?.substringBeforeLast('.') ?: "pixeltransfer"
                        exportFile.launch("$base-stream.${activeFormat.extension}")
                    },
                    enabled = state.exportProgress == null &&
                        StreamExporter.isSupported(activeFormat, state.config.paletteMode),
                ) {
                    Text(
                        if (activeFormat == ExportFormat.SINGLE_FRAME) {
                            "Export one frame"
                        } else {
                            "Export ${transfer.framesPerLoop} frames"
                        },
                    )
                }
            }
        }

        Button(
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth(),
            enabled = transfer != null && !state.busy,
        ) {
            Text("Start sending", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}
