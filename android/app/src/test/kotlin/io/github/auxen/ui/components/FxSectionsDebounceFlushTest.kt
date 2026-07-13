package io.github.auxen.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.BassBoostState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `rememberDebouncedSlider`'s 50ms write-through debounce (see its KDoc in
 * `FxSections.kt`) races composable disposal: if the section hosting the
 * slider leaves composition (collapsed, screen navigated away) before the
 * debounce settles, the pending `AudioFxController.updateX` call was simply
 * lost -- `rememberCoroutineScope()`'s scope is cancelled as part of the
 * same teardown, taking the in-flight `delay(50)` coroutine with it.
 * Reviewer finding from the DSP-b Task 3/4 review: reliably lost with zero
 * settling frames between the drag and disposal, reliably survived with
 * one -- inconclusive by luck, not by correctness.
 *
 * No explicit clock manipulation is needed to reproduce "zero settling
 * frames" deterministically: under `createComposeRule()`, `waitForIdle()`
 * settles pending recomposition but does NOT itself advance the coroutine
 * test clock far enough for an already-in-flight `delay(50)` to resume
 * (confirmed by direct investigation — a plain `performSemanticsAction` +
 * `waitForIdle()`, with no disposal at all, leaves the debounced commit
 * un-fired). That's exactly the "no settling time passed" case, so simply
 * disposing immediately after the drag and calling `waitForIdle()`
 * reproduces it every run, not just "sometimes." (An earlier draft of this
 * test set `compose.mainClock.autoAdvance = false` to try to force this —
 * don't: that also prevents `waitForIdle()` from processing the disposal
 * itself, which made the test fail for the WRONG reason.)
 *
 * `SemanticsActions.SetProgress` sets the Slider's value directly (the
 * standard accessibility action every Material3 `Slider` exposes) rather
 * than simulating a physical drag gesture — simpler, and exactly how a
 * TalkBack user would operate this control anyway.
 */
@RunWith(RobolectricTestRunner::class)
class FxSectionsDebounceFlushTest {
    @get:Rule val compose = createComposeRule()

    // BassBoostSection's onCommit now merges against the LIVE
    // AudioFxController.bassBoostState.value (final review round, Important
    // #2) -- reset it so a prior test's leftover state can't affect these.
    @Before
    fun setUp() {
        AudioFxController.resetForTest()
    }

    @After
    fun tearDown() {
        AudioFxController.resetForTest()
    }

    @Test
    fun sliderDoesNotCommitBeforeTheDebounceWindowElapses() {
        // Pins the window itself, not just "eventually commits" -- final
        // review round, Minor #7: removing the delay(debounceMillis) call
        // entirely would still pass sliderCommitsNormallyAfterTheDebounceSettles
        // (advanceTimeBy is a no-op if there's nothing to wait for), so
        // that test alone doesn't prove the debounce is actually gating
        // anything. This one advances well short of the 50ms window and
        // requires the commit to still be un-fired.
        var committed: BassBoostState? = null
        compose.setContent {
            AuxenTheme {
                BassBoostSection(
                    state = BassBoostState(),
                    onStateChange = { committed = it },
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Frequency")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        compose.mainClock.advanceTimeBy(20L)

        assertEquals(null, committed)
    }

    @Test
    fun sliderCommitsNormallyAfterTheDebounceSettles() {
        var committed: BassBoostState? = null
        compose.setContent {
            AuxenTheme {
                BassBoostSection(
                    state = BassBoostState(),
                    onStateChange = { committed = it },
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Frequency")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        // Past the 50ms debounce window -- the normal (non-disposal) path.
        compose.mainClock.advanceTimeBy(60L)

        assertEquals(120.0, committed?.freqHz)
    }

    @Test
    fun sliderFlushesUncommittedDragOnImmediateDispose() {
        var committed: BassBoostState? = null
        val showSection = mutableStateOf(true)
        compose.setContent {
            AuxenTheme {
                if (showSection.value) {
                    BassBoostSection(
                        state = BassBoostState(),
                        onStateChange = { committed = it },
                        expanded = true,
                        onExpandedChange = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Frequency")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }

        // Remove the composable immediately -- no time advance, so the
        // debounce's delay(50) coroutine has not resumed yet.
        showSection.value = false
        compose.waitForIdle()

        assertEquals(120.0, committed?.freqHz)
    }

    @Test
    fun sliderDoesNotFlushWhenNothingWasDragged() {
        var commitCount = 0
        val showSection = mutableStateOf(true)
        compose.setContent {
            AuxenTheme {
                if (showSection.value) {
                    BassBoostSection(
                        state = BassBoostState(),
                        onStateChange = { commitCount++ },
                        expanded = true,
                        onExpandedChange = {},
                    )
                }
            }
        }

        // Dispose with no prior interaction -- must not spuriously commit
        // the untouched default state.
        showSection.value = false
        compose.waitForIdle()

        assertEquals(0, commitCount)
    }
}
