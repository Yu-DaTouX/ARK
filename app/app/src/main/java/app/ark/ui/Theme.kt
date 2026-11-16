/*
 * Theme.kt —— 浅色 / 深色配色方案。设置里 theme：0 跟随系统、1 深色、2 浅色（见 AppViewModel.Settings）。
 */
package app.ark.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F6A73), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8EB), onPrimaryContainer = Color(0xFF00363D),
    secondary = Color(0xFF7A5A2A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E3C8), onSecondaryContainer = Color(0xFF2B1D05),
    tertiary = Color(0xFFB3261E),
    background = Color(0xFFFAF8F3), onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFAF8F3), onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE6E3DA), onSurfaceVariant = Color(0xFF4A4A44),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F2EC),
    surfaceContainer = Color(0xFFEFECE5), surfaceContainerHigh = Color(0xFFE9E6DF),
    surfaceContainerHighest = Color(0xFFE3E0D9), outlineVariant = Color(0xFFD3D0C6),
)

// 深色用纯黑背景：OLED 屏幕黑色像素不发光，夜里、停电时更省电
private val Dark = darkColorScheme(
    primary = Color(0xFF8FD1D9), onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF1F4F55), onPrimaryContainer = Color(0xFFCDE8EB),
    secondary = Color(0xFFE6C38E), onSecondary = Color(0xFF3F2C0A),
    secondaryContainer = Color(0xFF4A3618), onSecondaryContainer = Color(0xFFF3E3C8),
    tertiary = Color(0xFFFFB4AB),
    background = Color.Black, onBackground = Color(0xFFE4E2DC),
    surface = Color.Black, onSurface = Color(0xFFE4E2DC),
    surfaceVariant = Color(0xFF1E2424), onSurfaceVariant = Color(0xFFBFC6C5),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0E0E),
    surfaceContainer = Color(0xFF111515), surfaceContainerHigh = Color(0xFF181D1D),
    surfaceContainerHighest = Color(0xFF202626), outlineVariant = Color(0xFF2E3636),
)

/** @param mode 0 跟随系统 / 1 强制深色 / 2 强制浅色 */
@Composable
fun ArkTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
