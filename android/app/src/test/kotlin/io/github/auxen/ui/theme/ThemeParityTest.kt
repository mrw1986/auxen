package io.github.auxen.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the AuxenTheme color-scheme slots that Material 3's scheme builders
 * otherwise default to their baseline purple/lavender palette when left
 * unset: selected-state containers (nav indicator, chips, segmented
 * buttons), error containers, and — light only — onPrimary and the
 * surfaceContainer* ramp. Each slot is checked against both the intended
 * Auxen value and the real M3 default (computed live via a bare
 * darkColorScheme()/lightColorScheme() call, not a hardcoded guess), so a
 * regression back to baseline purple fails loudly.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeParityTest {
    @get:Rule val compose = createComposeRule()

    private fun captureColorScheme(darkTheme: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        compose.setContent {
            AuxenTheme(darkTheme = darkTheme) {
                scheme = MaterialTheme.colorScheme
            }
        }
        return scheme
    }

    @Test fun darkSchemeReplacesPurpleWithAmberContainers() {
        val materialDefault = darkColorScheme()
        val scheme = captureColorScheme(darkTheme = true)

        assertEquals(Color(0xFF31281D), scheme.secondaryContainer)
        assertNotEquals(materialDefault.secondaryContainer, scheme.secondaryContainer)

        assertEquals(AuxenColors.AmberPrimary, scheme.onSecondaryContainer)
        assertNotEquals(materialDefault.onSecondaryContainer, scheme.onSecondaryContainer)

        assertEquals(Color(0xFF2D1A1C), scheme.errorContainer)
        assertNotEquals(materialDefault.errorContainer, scheme.errorContainer)

        assertEquals(Color(0xFFE74C3C), scheme.onErrorContainer)
        assertNotEquals(materialDefault.onErrorContainer, scheme.onErrorContainer)
    }

    @Test fun lightSchemeReplacesPurpleWithWarmContainers() {
        val materialDefault = lightColorScheme()
        val scheme = captureColorScheme(darkTheme = false)

        assertEquals(AuxenColors.BgDeep, scheme.onPrimary)
        assertNotEquals(materialDefault.onPrimary, scheme.onPrimary)

        assertEquals(Color(0xFFF5E6C4), scheme.secondaryContainer)
        assertNotEquals(materialDefault.secondaryContainer, scheme.secondaryContainer)

        assertEquals(Color(0xFF3D2E00), scheme.onSecondaryContainer)
        assertNotEquals(materialDefault.onSecondaryContainer, scheme.onSecondaryContainer)

        assertEquals(Color(0xFFF8E3DC), scheme.errorContainer)
        assertNotEquals(materialDefault.errorContainer, scheme.errorContainer)

        assertEquals(Color(0xFFC0392B), scheme.onErrorContainer)
        assertNotEquals(materialDefault.onErrorContainer, scheme.onErrorContainer)

        // surfaceContainerLowest is white in both the Auxen target and the M3
        // baseline (the lightest tone in Material's own tonal ramp), so this
        // slot was never actually leaking violet — no notEquals check here.
        assertEquals(Color(0xFFFFFFFF), scheme.surfaceContainerLowest)

        assertEquals(Color(0xFFF7F4EC), scheme.surfaceContainerLow)
        assertNotEquals(materialDefault.surfaceContainerLow, scheme.surfaceContainerLow)

        assertEquals(Color(0xFFF1EDE3), scheme.surfaceContainer)
        assertNotEquals(materialDefault.surfaceContainer, scheme.surfaceContainer)

        assertEquals(Color(0xFFEBE6D9), scheme.surfaceContainerHigh)
        assertNotEquals(materialDefault.surfaceContainerHigh, scheme.surfaceContainerHigh)

        assertEquals(Color(0xFFE5DFD0), scheme.surfaceContainerHighest)
        assertNotEquals(materialDefault.surfaceContainerHighest, scheme.surfaceContainerHighest)
    }
}
