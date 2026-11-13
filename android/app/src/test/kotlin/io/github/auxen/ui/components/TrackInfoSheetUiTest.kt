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
import io.github.auxen.model.Source
import io.github.auxen.model.Track
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

    private val tidalTrack = Track(
        title = "Nightcall",
        artist = "Kavinsky",
        source = Source.TIDAL,
        sourceId = "t1",
        format = "FLAC",
        bitrateKbps = 1411,
    )

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

    private fun show(detail: TidalTrackDetail?, loading: Boolean = false, track: Track = tidalTrack) {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(track = track, detail = detail, loading = loading)
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
                TrackInfoSheetContent(track = tidalTrack, detail = null, loading = true)
            }
        }
    }

    @Test
    fun detailsSectionRendersLocalTechnicalMetadata() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(
                    track = Track(
                        title = "My Hero",
                        artist = "Foo Fighters",
                        source = Source.LOCAL,
                        sourceId = "1",
                        format = "FLAC",
                        bitrateKbps = 1000,
                    ),
                    detail = null,
                    loading = false,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("FLAC").assertExists()
        compose.onNodeWithText("Local").assertExists()
        // A local track has no catalog-keyed credits/lyrics block.
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
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

        // The credits/lyrics block settles on its notice; Details still renders.
        compose.onNodeWithText("No credits or lyrics available.").assertExists()
        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
    }

    @Test
    fun blankTitleFallsBackToThePlaceholderHeader() {
        // The header now comes from the Track's own title; a blank one falls back.
        show(detail(), track = tidalTrack.copy(title = ""))

        compose.onNodeWithText("Track info").assertExists()
    }

    @Test
    fun loadingWithNoDetailShowsTheSpinnerAndNotTheFallbackNotice() {
        // NOT unchanged: the header now comes from `track.title` (D1), and the
        // settled-notice text changed to "No credits or lyrics available." — both
        // assertions this test used to make had to move. showLoading() renders the
        // tidalTrack (title "Nightcall") with detail == null and loading == true,
        // so the spinner shows and the settled notice does not.
        showLoading()

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        ).assertExists()
        // Header is the Track's own title now, not the detail's — pins that the
        // body composed while the spinner is up.
        compose.onNodeWithText("Nightcall").assertExists()
        // The settled notice must NOT show while loading (the spinner is up), and
        // its text changed with the Details reshaping.
        compose.onNodeWithText("No credits or lyrics available.").assertDoesNotExist()
        // Title + divider + a wrapped 40dp spinner + the small Details block stays
        // well bounded; an unbounded-height fillMaxSize leak would measure orders
        // of magnitude past this. (Compose 1.7.6's bounds API has
        // assertHeightIsAtLeast/EqualTo but no AtMost, hence the explicit read.)
        val height = compose.onRoot().getUnclippedBoundsInRoot().height
        assertTrue("spinner frame measured $height — fillMaxSize leaked", height < 600.dp)
    }
}
