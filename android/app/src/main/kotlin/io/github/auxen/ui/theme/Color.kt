package io.github.auxen.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Auxen design tokens — ported from the desktop app's ui-mockup.html /
 * data/style.css palette (amber brand on near-black warm surfaces).
 */
object AuxenColors {
    val AmberPrimary = Color(0xFFD4A039)
    val Amber400 = Color(0xFFFFCA28)
    val Amber600 = Color(0xFFB8860B)

    val BgDeep = Color(0xFF0C0B0F)
    val BgSurface = Color(0xFF141318)
    val BgElevated = Color(0xFF1C1B22)
    val BgHover = Color(0xFF25242D)
    val BgActive = Color(0xFF2E2D38)

    val TextPrimary = Color(0xFFF0ECE4)
    val TextSecondary = Color(0xFF9E9A91)
    val TextTertiary = Color(0xFF6B6860)

    val TidalBlue = Color(0xFF00C4CC)
    val LocalGreen = Color(0xFF7CB87A)
    val FavoriteRed = Color(0xFFC75C5C)

    // Brand block — desktop .sidebar-brand-title/.sidebar-brand-subtitle colors
    val BrandTitleGold = Color(0xFFF0C560)
    val BrandGold = Color(0xFFE0B868)
    val BrandTitleOnLight = Color(0xFF8A6010)
    val BrandSubtitleOnLight = Color(0xFF7A5820)
}
