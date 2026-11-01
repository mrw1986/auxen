package io.github.auxen.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.auxen.playback.cache.CacheNetworkMode
import io.github.auxen.playback.cache.CacheSettings
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class OfflineCacheSectionUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val modes = mutableListOf<CacheNetworkMode>()
    private val limits = mutableListOf<Long>()
    private var clears = 0

    private fun show(settings: CacheSettings = CacheSettings(), readout: String = "0 MB used · 0 songs") {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                OfflineCacheSection(
                    settings = settings,
                    readout = readout,
                    onModeChange = { modes += it },
                    onLimitChange = { limits += it },
                    onClear = { clears++ },
                )
            }
        }
    }

    @Test
    fun `shows the readout`() {
        show(readout = "1.2 GB used · 14 songs")
        compose.onNodeWithText("1.2 GB used · 14 songs").assertIsDisplayed()
    }

    @Test
    fun `selecting Wi-Fi only calls onModeChange`() {
        show()
        compose.onNodeWithText("Wi-Fi only").performClick()
        assertEquals(listOf(CacheNetworkMode.WIFI_ONLY), modes)
    }

    @Test
    fun `selecting 5 GB calls onLimitChange`() {
        show()
        compose.onNodeWithText("5 GB").performClick()
        assertEquals(listOf(5_000_000_000L), limits)
    }

    @Test
    fun `the current mode and limit are selected`() {
        show(settings = CacheSettings(mode = CacheNetworkMode.OFF, limitBytes = 1_000_000_000L))
        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("1 GB").assertIsSelected()
        // The defaults must not also read as selected.
        compose.onNodeWithText("Wi-Fi and mobile data").assertIsNotSelected()
        compose.onNodeWithText("2 GB").assertIsNotSelected()
    }

    @Test
    fun `Clear cache asks first and clears on confirm`() {
        show()
        compose.onNodeWithText("Clear cache").performClick()
        compose.onNodeWithText("Clear cache?").assertIsDisplayed()
        compose.onNodeWithText("Cached songs will need service to play again.").assertIsDisplayed()
        assertEquals(0, clears)

        compose.onNodeWithText("Clear").performClick()
        assertEquals(1, clears)
        compose.onNodeWithText("Clear cache?").assertDoesNotExist()
    }

    @Test
    fun `cancelling the confirm does not clear`() {
        show()
        compose.onNodeWithText("Clear cache").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, clears)
        compose.onNodeWithText("Clear cache?").assertDoesNotExist()
    }
}
