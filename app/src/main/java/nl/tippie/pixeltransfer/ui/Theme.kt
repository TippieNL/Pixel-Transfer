package nl.tippie.pixeltransfer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B0B0F)
private val Surface = Color(0xFF16161D)
private val SurfaceHigh = Color(0xFF20202B)
private val Accent = Color(0xFF6FD3FF)
private val AccentDim = Color(0xFF0E3D52)
private val Warning = Color(0xFFFFC24D)
private val Danger = Color(0xFFFF6B6B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00202E),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFCBEEFF),
    secondary = Warning,
    onSecondary = Color(0xFF2A1E00),
    background = Ink,
    onBackground = Color(0xFFE7E7EE),
    surface = Surface,
    onSurface = Color(0xFFE7E7EE),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFB6B6C4),
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00627F),
    secondary = Color(0xFF7A5900),
    error = Danger,
)

/**
 * The app is dark by default whatever the system says: both screens are used in the dark, and the
 * sender's playback screen has to be pure black around the code area so the camera's exposure is
 * driven by the pattern rather than by a bright chrome.
 */
@Composable
fun PixelTransferTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (forceDark || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
