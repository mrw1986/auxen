package io.github.auxen.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure logic for the Settings screen's theme-mode picker (Desktop-Parity
 * Screens, sub-batch A, Task 1) — no Robolectric/Compose needed, matching
 * [io.github.auxen.ui.ScreensLogicTest]'s "pure logic, no VM/Compose
 * runtime" convention.
 */
class ThemeModeTest {

    @Test
    fun `settingValue is the lowercase color_scheme string`() {
        assertEquals("system", ThemeMode.SYSTEM.settingValue)
        assertEquals("dark", ThemeMode.DARK.settingValue)
        assertEquals("light", ThemeMode.LIGHT.settingValue)
    }

    @Test
    fun `fromSetting round-trips every settingValue`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting("system"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromSetting("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromSetting("light"))
    }

    @Test
    fun `fromSetting defaults to SYSTEM for null, blank, or unrecognized values`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting("sepia")) // pre-parity/corrupt value
    }

    @Test
    fun `resolveDarkTheme follows the system flag only for SYSTEM`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `resolveDarkTheme ignores the system flag for DARK and LIGHT`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = false))
    }
}
