@file:OptIn(ExperimentalTextApi::class)

package io.github.auxen.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.auxen.R

private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** DM Sans — body/UI font, per the desktop design. */
val DmSans = FontFamily(
    variable(R.font.dm_sans, FontWeight.Normal),
    variable(R.font.dm_sans, FontWeight.Medium),
    variable(R.font.dm_sans, FontWeight.SemiBold),
    variable(R.font.dm_sans, FontWeight.Bold),
    variable(R.font.dm_sans, FontWeight.ExtraBold),
)

/** Fraunces — display/brand serif, per the desktop design. */
val Fraunces = FontFamily(
    variable(R.font.fraunces, FontWeight.Light),
    variable(R.font.fraunces, FontWeight.Medium),
    variable(R.font.fraunces, FontWeight.SemiBold),
    variable(R.font.fraunces, FontWeight.Bold),
)

/** Josefin Sans — brand wordmark face, per desktop .sidebar-brand-title/.splash-title. */
val JosefinSans = FontFamily(
    variable(R.font.josefin_sans, FontWeight.Bold),
)

/**
 * Material type scale mapped to the desktop mockup's sizes: Fraunces for
 * greeting/brand/section titles, DM Sans for rows, labels, and badges.
 */
val AuxenTypography = Typography(
    displaySmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.5.sp),
)
