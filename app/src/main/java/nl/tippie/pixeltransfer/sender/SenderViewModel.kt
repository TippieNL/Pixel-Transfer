package nl.tippie.pixeltransfer.sender

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.pixeltransfer.core.frame.EccLevel
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.PreparedTransfer
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import nl.tippie.pixeltransfer.util.FileIo
import nl.tippie.pixeltransfer.util.PickedFile
import nl.tippie.pixeltransfer.util.formatBytes

data class SenderUiState(
    val file: PickedFile? = null,
    val transfer: PreparedTransfer? = null,
    val config: SenderConfig = SenderConfig(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val exportProgress: Float? = null,
) {
    /** True when the transfer is large enough that the user should be told it will take a while. */
    val slowTransferWarning: Boolean
        get() = (file?.bytes?.size ?: 0) > SenderConfig.SLOW_TRANSFER_WARNING_BYTES

    /** Set when the requested source block size had to be reduced to fit a frame. */
    val blockSizeReduced: Boolean
        get() = transfer != null && transfer.fountainParams.symbolSize != config.blockSize
}

class SenderViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SenderUiState())
    val state: StateFlow<SenderUiState> = _state.asStateFlow()

    fun pick(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val limit = _state.value.config.maxFileSizeBytes
                val file = withContext(Dispatchers.IO) {
                    FileIo.read(getApplication(), uri, limit)
                }
                _state.update { it.copy(file = file) }
                prepare()
            } catch (e: FileIo.TooLarge) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "That file is ${formatBytes(e.size)}. The limit is " +
                            "${formatBytes(e.limit.toLong())} - raise it in the settings below if " +
                            "you are willing to wait.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Could not read that file: ${e.message}") }
            }
        }
    }

    fun clear() {
        _state.value = SenderUiState(config = _state.value.config)
    }

    fun setPaletteMode(mode: PaletteMode) = reconfigure { it.copy(paletteMode = mode) }

    fun setEccLevel(level: EccLevel) = reconfigure { it.copy(eccLevel = level) }

    fun setCellSize(px: Int) = reconfigure { it.copy(cellSizePx = px) }

    fun setGrid(cells: Int) = reconfigure { it.copy(gridCells = cells) }

    fun setMaxFileSize(bytes: Int) = reconfigure { it.copy(maxFileSizeBytes = bytes) }

    /** Frame rate is the one setting that can change without re-encoding the stream. */
    fun setFps(fps: Int) {
        _state.update { it.copy(config = it.config.copy(fps = fps.coerceIn(SenderConfig.MIN_FPS, SenderConfig.MAX_FPS))) }
    }

    fun dismissMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    private fun reconfigure(transform: (SenderConfig) -> SenderConfig) {
        val updated = try {
            transform(_state.value.config)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
            return
        }
        _state.update { it.copy(config = updated) }
        if (_state.value.file != null) prepare()
    }

    /**
     * Compresses, hashes and splits the file for the current configuration.
     *
     * Re-run whenever a setting that changes the coding is touched: a different palette or ECC
     * level changes how much fits in a frame, which can change the source block size and
     * therefore the whole stream. A fresh stream id comes with it, so a receiver mid-transfer
     * correctly rejects the old frames instead of mixing the two.
     */
    private fun prepare() {
        val file = _state.value.file ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val config = _state.value.config
                val transfer = withContext(Dispatchers.Default) {
                    TransferPreparation.prepare(file.displayName, file.mimeType, file.bytes, config)
                }
                _state.update { it.copy(transfer = transfer, busy = false) }
            } catch (e: TransferPreparation.TooSmallGrid) {
                _state.update { it.copy(busy = false, transfer = null, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, transfer = null, error = "Could not prepare the transfer: ${e.message}") }
            }
        }
    }

    fun export(target: Uri, format: ExportFormat, loops: Int = 1) {
        val transfer = _state.value.transfer ?: return
        viewModelScope.launch {
            _state.update { it.copy(exportProgress = 0f, error = null, notice = null) }
            try {
                val frames = (transfer.framesPerLoop * loops).coerceAtLeast(1)
                val result = StreamExporter.export(
                    getApplication(), target, transfer, format, frames,
                ) { done, total ->
                    _state.update { it.copy(exportProgress = done.toFloat() / total) }
                }
                _state.update {
                    it.copy(
                        exportProgress = null,
                        notice = "Exported ${result.frames} frames, ${formatBytes(result.bytes)}.",
                    )
                }
            } catch (e: StreamExporter.NotASingleFrame) {
                _state.update {
                    it.copy(
                        exportProgress = null,
                        error = "This file needs more than one frame, so a still image cannot " +
                            "carry it. Export a sequence or a video instead.",
                    )
                }
            } catch (e: StreamExporter.UnsupportedForPalette) {
                _state.update {
                    it.copy(
                        exportProgress = null,
                        error = "${e.mode.label} cannot survive video compression - export a " +
                            "lossless image sequence instead.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(exportProgress = null, error = "Export failed: ${e.message}") }
            }
        }
    }
}
