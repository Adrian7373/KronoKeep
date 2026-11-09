package com.kronokeep.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * "Vault" palette: deep teal steel + brass. Fixed brand colours, no dynamic colour on purpose.
 * Use MaterialTheme.colorScheme / KronoTheme.status in UI code, never raw hex.
 */

private val Teal10 = Color(0xFF062328)
private val Teal30 = Color(0xFF0E3B43)
private val Teal40 = Color(0xFF155561)
private val Teal80 = Color(0xFF8FD0DB)
private val Teal90 = Color(0xFFC4ECF2)
private val Brass40 = Color(0xFF8A6417)
private val Brass80 = Color(0xFFE3B04B)
private val Brass90 = Color(0xFFFFDEA0)
private val Paper = Color(0xFFF6F4EE)
private val Ink = Color(0xFF1A1C1C)
private val Night = Color(0xFF0F1517)
private val NightSurface = Color(0xFF172024)

private val LightColors = lightColorScheme(
    primary = Teal30,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Brass40,
    onSecondary = Color.White,
    secondaryContainer = Brass90,
    onSecondaryContainer = Color(0xFF2B1D00),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE2E6E4),
    onSurfaceVariant = Color(0xFF424948),
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Teal40,
    onPrimaryContainer = Teal90,
    secondary = Brass80,
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Brass40,
    onSecondaryContainer = Brass90,
    background = Night,
    onBackground = Color(0xFFDEE3E3),
    surface = Night,
    onSurface = Color(0xFFDEE3E3),
    surfaceVariant = NightSurface,
    onSurfaceVariant = Color(0xFFBFC8C7),
)

/** Maintenance status colours (OK / due soon / overdue), outside Material's scheme. */
@Immutable
data class StatusColors(val ok: Color, val dueSoon: Color, val overdue: Color)

private val LightStatus = StatusColors(ok = Color(0xFF2E7D4F), dueSoon = Color(0xFFB26A00), overdue = Color(0xFFB3261E))
private val DarkStatus = StatusColors(ok = Color(0xFF7FD4A0), dueSoon = Color(0xFFFFB960), overdue = Color(0xFFF2B8B5))

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object KronoTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}

@Composable
fun KronoKeepTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography(),
            content = content,
        )
    }
}
