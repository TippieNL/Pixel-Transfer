package nl.tippie.pixeltransfer.sender

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nl.tippie.pixeltransfer.core.frame.EccLevel
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.ui.Banner
import nl.tippie.pixeltransfer.ui.BannerTone
import nl.tippie.pixeltransfer.ui.ChipRow
import nl.tippie.pixeltransfer.ui.LabelledSlider
import nl.tippie.pixeltransfer.ui.StatRow
import nl.tippie.pixeltransfer.util.DisplayControl
import nl.tippie.pixeltransfer.util.DisplayState

/**
 * Fullscreen playback of the pixel stream.
 *
 * The pattern is drawn on pure black with no system UI, at forced maximum brightness and a pinned
 * refresh rate. Tapping anywhere reveals the controls; they sit over the stream rather than
 * beside it so the code area stays as large as the screen allows.
 */
@Composable
fun SenderPlaybackScreen(viewModel: SenderViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val transfer = state.transfer
    val context = LocalContext.current
    val activity = context as? Activity

    var display by remember { mutableStateOf<DisplayState?>(null) }
    DisposableEffect(activity) {
        if (activity != null) display = DisplayControl.apply(activity)
        onDispose { if (activity != null) DisplayControl.release(activity) }
    }

    if (transfer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing prepared to send.")
        }
        return
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var restartToken by remember { mutableIntStateOf(0) }
    var frameSequence by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    // Held outside the playback effect: the frame on screen must not go back into the pool when
    // the effect restarts (changing the frame rate does exactly that), or the producer would draw
    // over the bitmap the UI is still showing.
    val displayed = remember { arrayOfNulls<RenderedFrame>(1) }

    val scope = rememberCoroutineScope()

    // Rebuild the pipeline whenever the coding or the rendering changes. fps is deliberately not
    // a key: changing the frame rate must not restart the stream.
    val renderer = remember(transfer, state.config.cellSizePx, state.config.gridCells) {
        FrameBitmapRenderer(StreamEncoder(transfer), state.config)
    }
    val producer = remember(renderer) { FrameProducer(renderer, scope) }

    DisposableEffect(producer) {
        onDispose { producer.stop() }
    }

    LaunchedEffect(producer, restartToken) {
        frameSequence = 0
        producer.start(0)
    }

    // Playback clock. withFrameNanos ties each advance to the display's own vsync, so a frame
    // is held for a whole number of refreshes rather than drifting against them - uneven frame
    // durations are what a rolling shutter turns into torn captures.
    LaunchedEffect(producer, paused, state.config.fps, restartToken) {
        if (paused) return@LaunchedEffect
        val periodNanos = 1_000_000_000L / state.config.fps
        var nextDue = -1L
        while (true) {
            val frame = producer.next()
            while (true) {
                val now = withFrameNanos { it }
                if (nextDue < 0L) {
                    nextDue = now
                    break
                }
                if (now >= nextDue) break
            }
            image = frame.bitmap.asImageBitmap()
            frameSequence = frame.sequence
            displayed[0]?.let { producer.recycle(it) }
            displayed[0] = frame
            nextDue += periodNanos
            // If playback fell far behind - the app was backgrounded, say - resynchronise instead
            // of sprinting through the backlog at the wrong rate.
            val now = System.nanoTime()
            if (now - nextDue > periodNanos * 4) nextDue = now + periodNanos
        }
    }

    val loop = if (transfer.framesPerLoop > 0) frameSequence / transfer.framesPerLoop + 1 else 1

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        image?.let { bitmap ->
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val available = minOf(constraints.maxWidth, constraints.maxHeight)
                val native = renderer.imageSize
                // Draw one screen pixel per rendered pixel whenever it fits. Any resampling here
                // makes cell edges uneven, and uneven cells are exactly what the receiver has to
                // fight through. Only when the pattern is larger than the screen does it fall
                // back to scaled drawing.
                val modifier = if (native <= available) {
                    Modifier.size(with(density) { native.toDp() })
                } else {
                    Modifier.fillMaxSize()
                }
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = "Pixel stream",
                    modifier = modifier,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            Text(
                "loop $loop  ·  frame $frameSequence  ·  ${state.config.fps} fps",
                color = Color(0xFF6FD3FF),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (paused) {
                Text("PAUSED", color = Color(0xFFFFC24D), style = MaterialTheme.typography.labelMedium)
            }
        }

        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6101018))
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                display?.let { info ->
                    if (info.nightModeSuspected) {
                        Banner(
                            "A night light or blue-light filter looks active. It shifts every " +
                                "colour on screen - turn it off before sending.",
                            BannerTone.WARNING,
                        )
                    }
                    if (info.variableRefreshRate && !info.refreshRateLocked) {
                        Banner(
                            "This display changes refresh rate on its own and the rate could not " +
                                "be pinned. Frames may be shown for uneven times; lower the fps " +
                                "if the receiver reports tearing.",
                            BannerTone.WARNING,
                        )
                    }
                    StatRow(
                        "Display",
                        "${"%.0f".format(info.refreshRateHz)} Hz" +
                            if (info.refreshRateLocked) " (pinned)" else " (not pinned)",
                    )
                }

                StatRow("Symbols per frame", "${transfer.symbolsPerFrame}")
                StatRow("Frames per loop", "${transfer.framesPerLoop}")

                LabelledSlider(
                    label = "Frame rate",
                    value = state.config.fps,
                    range = SenderConfig.MIN_FPS..SenderConfig.MAX_FPS,
                    onChange = viewModel::setFps,
                    suffix = " fps",
                )
                LabelledSlider(
                    label = "Cell size",
                    value = state.config.cellSizePx,
                    range = SenderConfig.MIN_CELL_SIZE..SenderConfig.MAX_CELL_SIZE,
                    onChange = viewModel::setCellSize,
                    suffix = " px",
                )

                Text("Palette", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = PaletteMode.entries.toList(),
                    selected = state.config.paletteMode,
                    label = { it.label },
                    onSelect = viewModel::setPaletteMode,
                )
                Text("Error correction", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = EccLevel.entries.toList(),
                    selected = state.config.eccLevel,
                    label = { it.label },
                    onSelect = viewModel::setEccLevel,
                )
                Text(
                    "Changing the palette or error correction re-encodes the stream with a new " +
                        "stream id; a receiver part-way through will discard what it had.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
                    OutlinedButton(onClick = { restartToken++ }) { Text("Restart") }
                    OutlinedButton(onClick = onExit) { Text("Done") }
                }
            }
        }
    }
}
