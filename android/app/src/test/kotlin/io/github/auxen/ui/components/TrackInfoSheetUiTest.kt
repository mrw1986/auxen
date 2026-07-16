package io.github.auxen.ui.components

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.auxen.provider.tidal.TidalTrackDetail
import io.github.auxen.provider.tidal.TrackCredit
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for [TrackInfoSheetContent] — the stateless body of
 * [TrackInfoSheet] (credits + lyrics for a Tidal track).
 *
 * ## Why the content composable, not [TrackInfoSheet]
 * Same Robolectric concession the `action-sheet-*` goldens and
 * `TrackActionSheetUiTest` already document: a Material 3
 * [androidx.compose.material3.ModalBottomSheet] hosts its content in a separate
 * window whose animated entrance never settles under Robolectric. The stateless
 * `Column` renders in the main composition and asserts reproducibly.
 *
 * ## The `loading` branch IS covered — see [showLoading]
 * `loading && detail == null` renders [LoadingState], whose
 * `CircularProgressIndicator` is an INDEFINITE animation, so the auto-advancing
 * default clock never idles and `waitForIdle()` (which every node query runs
 * internally) throws `AppNotIdleException`. Stopping the clock with
 * `mainClock.autoAdvance = false` before `setContent` renders it deterministically
 * at frame 0 — see [showLoading]'s KDoc for why the repo's two standing
 * `autoAdvance` cautions do not apply to this shape.
 *
 * What still closes on-device (and cannot be pinned here) is the branch's *live*
 * behaviour: that a real failed fetch flips `loading` false so the sheet settles
 * on the notice. The static frames on both sides of that flip are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class TrackInfoSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun detail(
        credits: List<TrackCredit> = listOf(
            TrackCredit("Producer", listOf("Alice", "Bob")),
            TrackCredit("Mixer", listOf("Carol")),
        ),
        lyricsText: String? = "Never gonna give you up",
        lyricsSynced: String? = null,
    ) = TidalTrackDetail(
        id = "t1",
        title = "Nightcall",
        credits = credits,
        lyricsText = lyricsText,
        lyricsSynced = lyricsSynced,
        similarTracks = emptyList(),
    )

    private fun show(detail: TidalTrackDetail?, loading: Boolean = false) {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(detail = detail, loading = loading)
            }
        }
        compose.waitForIdle()
    }

    /**
     * The `loading && detail == null` branch, which [show] cannot render.
     *
     * [LoadingState]'s `CircularProgressIndicator` is an INDEFINITE animation:
     * it always leaves an awaiter on the clock, so the auto-advancing default
     * never reaches idle and `waitForIdle()` — which every node query runs
     * internally — throws `AppNotIdleException`. Pinning `autoAdvance = false`
     * BEFORE `setContent` stops the clock driving that animation, so composition
     * and layout settle and the queries resolve against a spinner frozen at
     * frame 0.
     *
     * Note the two `autoAdvance = false` cautions elsewhere in this repo do not
     * apply here, and are worth distinguishing rather than pattern-matching:
     * `TrackActionSheetUiTest`'s AlertDialog+OutlinedTextField never idles for a
     * different root cause (it fails inside `setContent` itself, before any
     * clock manipulation can help), and `FxSectionsDebounceFlushTest` needs
     * `waitForIdle()` to process a disposal that a stopped clock would withhold.
     * This test asserts on a static frame and awaits nothing.
     */
    private fun showLoading() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(detail = null, loading = true)
            }
        }
    }

    @Test
    fun creditsRenderAsRoleHeaderWithJoinedNames() {
        show(detail())

        compose.onNodeWithText("Nightcall").assertExists()
        compose.onNodeWithText("Credits").assertExists()
        // TrackCredit already groups by role (trackDetailFrom does the grouping);
        // the sheet's job is to print one role header + its comma-joined names.
        compose.onNodeWithText("Producer").assertExists()
        compose.onNodeWithText("Alice, Bob").assertExists()
        compose.onNodeWithText("Mixer").assertExists()
        compose.onNodeWithText("Carol").assertExists()
    }

    @Test
    fun lyricsTextRenders() {
        show(detail())

        compose.onNodeWithText("Lyrics").assertExists()
        compose.onNodeWithText("Never gonna give you up").assertExists()
    }

    @Test
    fun syncedOnlyLyricsShowTheSyncedNote() {
        show(detail(lyricsText = null, lyricsSynced = "[00:12.00]Never gonna give you up"))

        compose.onNodeWithText("Synced lyrics only.").assertExists()
    }

    @Test
    fun absentLyricsShowThePlaceholder() {
        show(detail(lyricsText = null, lyricsSynced = null))

        compose.onNodeWithText("No lyrics available.").assertExists()
    }

    @Test
    fun creditsSectionIsHiddenWhenThereAreNone() {
        show(detail(credits = emptyList()))

        // No header, no placeholder — same "hide the section" treatment the
        // artist screen gives a null biography.
        compose.onNodeWithText("Credits").assertDoesNotExist()
        compose.onNodeWithText("Lyrics").assertExists()
    }

    @Test
    fun settledWithNoDetailShowsTheFallbackNotice() {
        show(null, loading = false)

        compose.onNodeWithText("Track info").assertExists()
        compose.onNodeWithText("No details available.").assertExists()
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
    }

    @Test
    fun blankTitleFallsBackToThePlaceholderHeader() {
        // `title` is projected with `.orEmpty()`, so an absent title attribute
        // arrives as "" on a perfectly resolved document — a bare elvis would
        // render an empty header. Pins the `?.takeIf { it.isNotBlank() }`.
        show(detail().copy(title = ""))

        compose.onNodeWithText("Track info").assertExists()
    }

    @Test
    fun loadingWithNoDetailShowsTheSpinnerAndNotTheFallbackNotice() {
        // The branch the on-device checkpoint would otherwise be the ONLY
        // evidence for. Two things are pinned:
        //  1. loading && detail == null renders the spinner and NOT
        //     "No details available." — i.e. a failure settling on the notice
        //     (settledWithNoDetailShowsTheFallbackNotice) is a real transition,
        //     not a state the sheet was already in.
        //  2. LoadingState's `fillMaxSize()` sits inside a `verticalScroll`ed
        //     Column here — an UNBOUNDED-height context, unlike the bounded
        //     `fillMaxSize()` Columns it was written for. `fillMaxHeight` no-ops
        //     when `!constraints.hasBoundedHeight`, so the Box wraps its content
        //     instead of blowing the scroll content up to Constraints.Infinity.
        //     The height assert is what would catch that regressing.
        showLoading()

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        ).assertExists()
        compose.onNodeWithText("Track info").assertExists()
        compose.onNodeWithText("No details available.").assertDoesNotExist()
        // Title + divider + a wrapped 40dp spinner in 32dp padding is ~150dp.
        // An unbounded-height leak would measure orders of magnitude past this.
        // (Compose 1.7.6's bounds API has assertHeightIsAtLeast/EqualTo but no
        // AtMost, hence the explicit bounds read.)
        val height = compose.onRoot().getUnclippedBoundsInRoot().height
        assertTrue("spinner frame measured $height — fillMaxSize leaked", height < 600.dp)
    }
}
