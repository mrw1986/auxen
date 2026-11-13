package io.github.auxen.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.ui.components.BrandBlock
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrandBlockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullBrandBlockShowsNameAndTagline() {
        compose.setContent { AuxenTheme { BrandBlock() } }
        compose.onNodeWithText("AUXEN").assertIsDisplayed()
        compose.onNodeWithText("UNORTHODOX AUDIO").assertIsDisplayed()
    }

    @Test fun compactBrandBlockOmitsTagline() {
        compose.setContent { AuxenTheme { BrandBlock(compact = true) } }
        compose.onNodeWithText("AUXEN").assertIsDisplayed()
        compose.onNodeWithText("UNORTHODOX AUDIO").assertDoesNotExist()
    }
}
