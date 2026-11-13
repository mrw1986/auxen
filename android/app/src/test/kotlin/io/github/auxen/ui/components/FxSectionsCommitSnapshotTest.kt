package io.github.auxen.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.LimiterState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DSP-b final review, Important #2: a section's commit lambdas must merge
 * against the CURRENT controller state at the moment they actually fire, not
 * a `state` snapshot closed over whenever the composable last recomposed --
 * otherwise a flush (dispose-time or debounce) that fires after some OTHER
 * field changed in the same section (e.g. the enabled switch, or another
 * slider) reverts that other change back to its stale value.
 */
@RunWith(RobolectricTestRunner::class)
class FxSectionsCommitSnapshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        AudioFxController.resetForTest()
    }

    @After
    fun tearDown() {
        AudioFxController.resetForTest()
    }

    @Test
    fun flushAfterAnExternalStateChangeCommitsTheMergedResultNotAStaleRevert() {
        val showSection = mutableStateOf(true)
        compose.setContent {
            AuxenTheme {
                if (showSection.value) {
                    LimiterSection(
                        // Deliberately a constant, non-reactive `state` -- this
                        // composable is never recomposed with fresher values,
                        // simulating a recomposition that hasn't caught up yet
                        // (the exact window the reviewer's finding describes).
                        state = LimiterState(),
                        onStateChange = { AudioFxController.updateLimiter(it) },
                        expanded = true,
                        onExpandedChange = {},
                    )
                }
            }
        }

        // Drag Release -- uncommitted, still inside the 50ms debounce window.
        compose.onNodeWithContentDescription("Release")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }

        // An external change lands directly on the controller (bypassing this
        // composable's own `state` parameter entirely, so it never
        // recomposes) -- e.g. the enabled switch, or another slider,
        // committing independently in the same narrow window.
        AudioFxController.updateLimiter(LimiterState(enabled = false, thresholdDb = -5.0))

        // Collapse the section -- the pending Release drag must flush
        // against the LATEST controller state, not the LimiterState()
        // default this composable was constructed with.
        showSection.value = false
        compose.waitForIdle()

        val final = AudioFxController.limiterState.value
        assertEquals(300.0, final.releaseMs, 0.001)
        assertEquals(false, final.enabled)
        assertEquals(-5.0, final.thresholdDb, 0.001)
    }
}
