package io.github.auxen.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.model.Source
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proof-of-life for the Compose UI test infrastructure: exercises
 * [createComposeRule] under Robolectric on the JVM against real Auxen
 * composables. Later tasks build screenshot goldens on top of this wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class BadgesUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sourceBadgeRendersSourceName() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                SourceBadge(Source.TIDAL)
            }
        }
        compose.onNodeWithText("TIDAL").assertIsDisplayed()
    }

    @Test
    fun qualityBadgeHidesUnknown() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                QualityBadge("Unknown")
            }
        }
        compose.onNodeWithText("UNKNOWN").assertDoesNotExist()
    }
}
