package com.kronokeep.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Direction C, "Asset Tag": teal steel + brass, light and dark.
 * Dark is a tonal version of the same palette (teal lifts to sea-glass, brass to old gold), not an inversion.
 * Brass marks things of record (export, locked logs, verified report); teal is for everyday actions.
 * Use MaterialTheme.colorScheme / KronoTheme.status in UI code, never raw hex.
 * Mirrors design/previews/c-asset-tag.html.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E3B43),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E6EA),
    onPrimaryContainer = Color(0xFF062328),
    inversePrimary = Color(0xFF8FD0DB),
    secondary = Color(0xFF8F6A1B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4E2B8),
    onSecondaryContainer = Color(0xFF5C4100),
    tertiary = Color(0xFF3E5C76),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4F5),
    onTertiaryContainer = Color(0xFF0F2A40),
    background = Color(0xFFEDF1F0),
    onBackground = Color(0xFF152022),
    surface = Color(0xFFEDF1F0),
    onSurface = Color(0xFF152022),
    surfaceVariant = Color(0xFFDDE5E4),
    onSurfaceVariant = Color(0xFF566769),
    surfaceTint = Color(0xFF0E3B43),
    surfaceBright = Color(0xFFF6F8F8),
    surfaceDim = Color(0xFFD9E0DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8F8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6ECEB),
    surfaceContainerHighest = Color(0xFFDDE5E4),
    inverseSurface = Color(0xFF253133),
    inverseOnSurface = Color(0xFFEAF1F0),
    outline = Color(0xFF7D8E8F),
    outlineVariant = Color(0xFFD3DCDB),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDB),
    onErrorContainer = Color(0xFF8C1D18),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD0DB),
    onPrimary = Color(0xFF06262C),
    primaryContainer = Color(0xFF174E58),
    onPrimaryContainer = Color(0xFFC9EEF3),
    inversePrimary = Color(0xFF0E3B43),
    secondary = Color(0xFFE3B04B),
    onSecondary = Color(0xFF3A2800),
    secondaryContainer = Color(0xFF4D3A10),
    onSecondaryContainer = Color(0xFFFFDFA0),
    tertiary = Color(0xFFA9C8E4),
    onTertiary = Color(0xFF0F2A40),
    tertiaryContainer = Color(0xFF284660),
    onTertiaryContainer = Color(0xFFD3E4F5),
    background = Color(0xFF0E1A1C),
    onBackground = Color(0xFFE0EAE9),
    surface = Color(0xFF0E1A1C),
    onSurface = Color(0xFFE0EAE9),
    surfaceVariant = Color(0xFF243A3E),
    onSurfaceVariant = Color(0xFF93A6A7),
    surfaceTint = Color(0xFF8FD0DB),
    surfaceBright = Color(0xFF2A3F43),
    surfaceDim = Color(0xFF0E1A1C),
    surfaceContainerLowest = Color(0xFF091315),
    surfaceContainerLow = Color(0xFF122123),
    surfaceContainer = Color(0xFF162527),
    surfaceContainerHigh = Color(0xFF1D3034),
    surfaceContainerHighest = Color(0xFF243A3E),
    inverseSurface = Color(0xFFE0EAE9),
    inverseOnSurface = Color(0xFF1B2A2D),
    outline = Color(0xFF6B8083),
    outlineVariant = Color(0xFF2A3E42),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF4A1F1C),
    onErrorContainer = Color(0xFFF7C4C0),
)

/**
 * Maintenance status colours (OK / due soon / overdue), outside Material's scheme.
 * The plain colour is for icons and text on a surface; the container pair is for pills and due-window bands.
 */
@Immutable
data class StatusColors(
    val ok: Color,
    val dueSoon: Color,
    val overdue: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    val dueSoonContainer: Color,
    val onDueSoonContainer: Color,
    val overdueContainer: Color,
    val onOverdueContainer: Color,
)

private val LightStatus = StatusColors(
    ok = Color(0xFF2E7D4F), dueSoon = Color(0xFFB26A00), overdue = Color(0xFFB3261E),
    okContainer = Color(0xFFD5EEDD), onOkContainer = Color(0xFF1E5C38),
    dueSoonContainer = Color(0xFFFBE7C6), onDueSoonContainer = Color(0xFF7A4900),
    overdueContainer = Color(0xFFF9DEDB), onOverdueContainer = Color(0xFFB3261E),
)
private val DarkStatus = StatusColors(
    ok = Color(0xFF7FD4A0), dueSoon = Color(0xFFFFB960), overdue = Color(0xFFF2B8B5),
    okContainer = Color(0xFF183D29), onOkContainer = Color(0xFF9BE0B6),
    dueSoonContainer = Color(0xFF46300F), onDueSoonContainer = Color(0xFFFFCF8A),
    overdueContainer = Color(0xFF4A1F1C), onOverdueContainer = Color(0xFFF7C4C0),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val KronoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object KronoTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}

@Composable
fun KronoKeepTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = KronoTypography,
            shapes = KronoShapes,
            content = content,
        )
    }
}
