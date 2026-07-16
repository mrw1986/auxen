package io.github.auxen.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.provider.tidal.TidalTrackDetail
import io.github.auxen.provider.tidal.TrackCredit
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
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
 * ## Why the `loading` branch is not covered here
 * `loading && detail == null` renders [LoadingState], whose
 * `CircularProgressIndicator` is an INDEFINITE animation — it never leaves the
 * clock without awaiters, so `waitForIdle()` (which every node query runs
 * internally) would never settle and would throw `AppNotIdleException`. No test
 * in this repo renders a spinner for exactly that reason. That branch closes on
 * the task's on-device checkpoint, per the plan's Global Constraints.
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
}
