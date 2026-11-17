package com.localai.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.localai.app.data.settings.ThemeMode

/** Tokens mirror docs/UI_UX_SPECIFICATION.md §2 and prototype/css/styles.css. */
@Immutable
data class LaColors(
    val bg: Color, val surface1: Color, val surface2: Color, val surface3: Color, val surface4: Color,
    val line: Color, val lineStrong: Color, val text: Color, val text2: Color, val text3: Color,
    val accent: Color, val accentStrong: Color, val onAccent: Color, val accentSoft: Color,
    val model: Color, val agent: Color, val knowledge: Color,
    val ok: Color, val warn: Color, val bad: Color, val pending: Color,
)

val DarkLa = LaColors(
    bg = Color(0xFF0B0E13), surface1 = Color(0xFF11151C), surface2 = Color(0xFF171C25), surface3 = Color(0xFF1F2530), surface4 = Color(0xFF282F3C),
    line = Color(0xFF2A3140), lineStrong = Color(0xFF3A4253), text = Color(0xFFE8EBF1), text2 = Color(0xFFA7AEBB), text3 = Color(0xFF707A8A),
    accent = Color(0xFF8EA8FF), accentStrong = Color(0xFFB3C4FF), onAccent = Color(0xFF0D1430), accentSoft = Color(0x248EA8FF),
    model = Color(0xFF7CC7FF), agent = Color(0xFFC39BFF), knowledge = Color(0xFF6FD3B0),
    ok = Color(0xFF5FD08E), warn = Color(0xFFF2B45A), bad = Color(0xFFF27A6E), pending = Color(0xFF8A93A3),
)

val LightLa = LaColors(
    bg = Color(0xFFF3F4F8), surface1 = Color(0xFFFFFFFF), surface2 = Color(0xFFF6F7FB), surface3 = Color(0xFFECEEF4), surface4 = Color(0xFFE1E4EC),
    line = Color(0xFFDDE1EA), lineStrong = Color(0xFFC7CCD8), text = Color(0xFF141821), text2 = Color(0xFF4D5565), text3 = Color(0xFF7C8494),
    accent = Color(0xFF3E5BD8), accentStrong = Color(0xFF2A45BD), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0x1A3E5BD8),
    model = Color(0xFF1A73B8), agent = Color(0xFF7A45D6), knowledge = Color(0xFF13866A),
    ok = Color(0xFF1B8A4B), warn = Color(0xFFA8650A), bad = Color(0xFFC2382C), pending = Color(0xFF6B7383),
)

val LocalLa = staticCompositionLocalOf { DarkLa }

object La {
    val colors: LaColors @Composable get() = LocalLa.current
    val mono = FontFamily.Monospace
}

@Composable
fun LocalAiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val c = if (dark) DarkLa else LightLa
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentStrong,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
        surfaceContainer = c.surface1, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3, surfaceContainerLow = c.surface1,
        outline = c.lineStrong, outlineVariant = c.line, error = c.bad, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accentStrong,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentStrong,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
        surfaceContainer = c.surface1, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3, surfaceContainerLow = c.surface1,
        outline = c.lineStrong, outlineVariant = c.line, error = c.bad, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accentStrong,
    )
    val base = Typography()
    val display = FontFamily.SansSerif
    val typography = base.copy(
        headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
    CompositionLocalProvider(LocalLa provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
