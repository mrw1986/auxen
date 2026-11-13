package io.github.auxen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuxenDarkColors = darkColorScheme(
    primary = AuxenColors.AmberPrimary,
    onPrimary = AuxenColors.BgDeep,
    primaryContainer = AuxenColors.BgActive,
    onPrimaryContainer = AuxenColors.TextPrimary,
    secondary = AuxenColors.TidalBlue,
    onSecondary = AuxenColors.BgDeep,
    tertiary = AuxenColors.LocalGreen,
    onTertiary = AuxenColors.BgDeep,
    background = AuxenColors.BgDeep,
    onBackground = AuxenColors.TextPrimary,
    surface = AuxenColors.BgSurface,
    onSurface = AuxenColors.TextPrimary,
    surfaceVariant = AuxenColors.BgElevated,
    onSurfaceVariant = AuxenColors.TextSecondary,
    surfaceContainer = AuxenColors.BgElevated,
    surfaceContainerHigh = AuxenColors.BgHover,
    surfaceContainerHighest = AuxenColors.BgActive,
    error = Color(0xFFE74C3C),
    onError = Color.White,
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0x0FFFFFFF),
    // Desktop nav-selection glow: rgba(212,160,57,0.15) over BgSurface (style.css:184-195)
    secondaryContainer = Color(0xFF31281D),
    onSecondaryContainer = AuxenColors.AmberPrimary,
    // Desktop soft-red error treatment: rgba(231,76,60,0.12) over BgSurface (style.css:776-777)
    errorContainer = Color(0xFF2D1A1C),
    onErrorContainer = Color(0xFFE74C3C),
)

private val AuxenLightColors = lightColorScheme(
    primary = AuxenColors.Amber600,
    // was Color.White — desktop accent_fg_color #0c0b0f is scheme-invariant (style.css:8-11),
    // and every hard-coded amber button already uses BgDeep content in both themes
    onPrimary = AuxenColors.BgDeep,
    primaryContainer = Color(0xFFF5E6C4),
    onPrimaryContainer = Color(0xFF3D2E00),
    secondary = Color(0xFF00747A),
    onSecondary = Color.White,
    tertiary = Color(0xFF4C7A4A),
    onTertiary = Color.White,
    background = Color(0xFFFAF8F2),
    onBackground = Color(0xFF1C1B16),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B16),
    surfaceVariant = Color(0xFFF1EDE3),
    onSurfaceVariant = Color(0xFF6B6860),
    error = Color(0xFFC0392B),
    onError = Color.White,
    outline = Color(0x1F000000),
    outlineVariant = Color(0x14000000),
    // Reuses primaryContainer/onPrimaryContainer rather than compositing the
    // CSS nav-selection rgba literally: amber text (#D4A039) on pale cream
    // reads poorly, so light mode keeps the app's established dark-brown-on-
    // cream amber-container convention instead.
    secondaryContainer = Color(0xFFF5E6C4),
    onSecondaryContainer = Color(0xFF3D2E00),
    errorContainer = Color(0xFFF8E3DC),   // rgba(231,76,60,0.12) over #FAF8F2
    onErrorContainer = Color(0xFFC0392B),
    // Warm-neutral container ramp — the dark scheme already overrides these, light must too,
    // or NavigationBar/ModalBottomSheet/AlertDialog/slider tracks render M3 baseline lavender
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4EC),
    surfaceContainer = Color(0xFFF1EDE3),
    surfaceContainerHigh = Color(0xFFEBE6D9),
    surfaceContainerHighest = Color(0xFFE5DFD0),
)

/**
 * Auxen brand theme — amber accent on warm near-black (dark) or warm
 * off-white (light). Deliberately NOT Material You dynamic color: brand
 * identity matches the desktop app.
 */
@Composable
fun AuxenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AuxenDarkColors else AuxenLightColors,
        typography = AuxenTypography,
        content = content,
    )
}

/**
 * The Settings screen's appearance choice, persisted via
 * `LibraryRepository.getSetting/setSetting` under the `color_scheme` key
 * (Desktop-Parity Screens, sub-batch A, Task 1 -- mirrors the desktop
 * settings dialog's Theme combo row). [SYSTEM] follows the device's
 * day/night setting; [DARK]/[LIGHT] pin one regardless of it.
 */
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
    ;

    /** The `color_scheme` setting value this mode persists as. */
    val settingValue: String get() = name.lowercase()

    companion object {
        /**
         * Parses a stored `color_scheme` value back into a [ThemeMode].
         * Defaults to [SYSTEM] for null/blank/unrecognized input -- covers
         * "never set" (fresh install) and a corrupt/pre-parity value the
         * same way, rather than crashing or silently picking a fixed theme.
         */
        fun fromSetting(value: String?): ThemeMode = entries.firstOrNull { it.settingValue == value } ?: SYSTEM
    }
}

/**
 * Resolves a [ThemeMode] to the concrete `darkTheme` boolean [AuxenTheme]
 * needs, given the device's current day/night setting ([systemDark] --
 * typically `isSystemInDarkTheme()`, resolved by the caller so this stays a
 * pure function). Only [ThemeMode.SYSTEM] actually consults it.
 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}
