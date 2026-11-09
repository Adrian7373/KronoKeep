package com.kronokeep.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kronokeep.app.R

/* Fonts are bundled (SIL OFL, licences in assets/licenses/) so the app looks the same offline and on first launch. */

private fun schibsted(weight: Int) = Font(
    resId = R.font.schibsted_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Variable font: one file, one instance per weight the type scale uses. */
val SchibstedGrotesk = FontFamily(schibsted(400), schibsted(500), schibsted(600), schibsted(700), schibsted(800))

/** Only for serial numbers and content hashes, where each character matters. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

val SerialTextStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.04.em)

private val base = Typography()

private fun TextStyle.grotesk(weight: FontWeight, tracking: Float? = null) = copy(
    fontFamily = SchibstedGrotesk,
    fontWeight = weight,
    letterSpacing = tracking?.em ?: letterSpacing,
)

/** Display and headlines are heavy and tightly tracked; body stays regular; labels are semibold. */
val KronoTypography = Typography(
    displayLarge = base.displayLarge.grotesk(FontWeight.ExtraBold, -0.02f),
    displayMedium = base.displayMedium.grotesk(FontWeight.ExtraBold, -0.02f),
    displaySmall = base.displaySmall.grotesk(FontWeight.ExtraBold, -0.02f),
    headlineLarge = base.headlineLarge.grotesk(FontWeight.ExtraBold, -0.02f),
    headlineMedium = base.headlineMedium.grotesk(FontWeight.ExtraBold, -0.02f),
    headlineSmall = base.headlineSmall.grotesk(FontWeight.Bold, -0.01f),
    titleLarge = base.titleLarge.grotesk(FontWeight.Bold, -0.01f),
    titleMedium = base.titleMedium.grotesk(FontWeight.Bold),
    titleSmall = base.titleSmall.grotesk(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.grotesk(FontWeight.Normal),
    bodyMedium = base.bodyMedium.grotesk(FontWeight.Normal),
    bodySmall = base.bodySmall.grotesk(FontWeight.Normal),
    labelLarge = base.labelLarge.grotesk(FontWeight.SemiBold),
    labelMedium = base.labelMedium.grotesk(FontWeight.SemiBold),
    labelSmall = base.labelSmall.grotesk(FontWeight.SemiBold),
)
