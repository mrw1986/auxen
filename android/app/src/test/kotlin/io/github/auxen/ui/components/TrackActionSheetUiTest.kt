package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun track(
    title: String = "Everlong",
    artist: String = "Foo Fighters",
    album: String? = "The Colour and the Shape",
    source: Source = Source.LOCAL,
    sourceId: String = "x",
) = Track(title = title, artist = artist, album = album, source = source, sourceId = sourceId)

/**
 * Behavior tests for [TrackActionSheet] — the long-press action sheet shared
 * across track lists (mobile counterpart of the desktop TrackContextMenu).
 *
 * ## Robolectric concession 1 — clicking inside the [ModalBottomSheet]
 * [TrackActionSheet]'s root actions live inside a Material 3
 * [androidx.compose.material3.ModalBottomSheet]. Under Robolectric,
 * [androidx.compose.ui.test.performClick] (real coordinate-based touch
 * injection) does not reach the sheet's list items: a debugging pass dumped
 * every node with a click action and found a single node spanning the
 * *entire* root (0,0)-(1078,2399) that also exposes a click action —
 * evidently part of the sheet's own scaffolding — which silently swallows
 * touches aimed at rows nested inside it (`performClick()` returns
 * successfully but the row's `onClick` lambda is never invoked; verified by
 * a recording-lambda counter staying at `0`). Dispatching the click via the
 * *semantics* action instead —
 * `node.performSemanticsAction(SemanticsActions.OnClick)` — invokes the
 * exact same `onClick` lambda that `Modifier.clickable` registers (i.e. the
 * real, production `SheetAction` callback), without depending on Robolectric's
 * simulated touch hit-testing. All sheet-row taps in this file use that
 * form for this reason. `assertExists()` is used instead of
 * `assertIsDisplayed()` for the same root cause — the sheet's animated
 * entrance does not settle into fully-unclipped bounds under Robolectric.
 *
 * ## Robolectric concession 2 — the "New playlist…" dialog is unreachable
 * Tapping "New playlist…" opens an
 * [androidx.compose.material3.AlertDialog] containing an
 * [androidx.compose.material3.OutlinedTextField]. Isolated repro tests (an
 * `AlertDialog` + `OutlinedTextField` alone, with no `TrackActionSheet` or
 * `ModalBottomSheet` involved) showed that composing *both together* makes
 * Robolectric's Compose idling resource never settle:
 * `ComposeContentTestRule.waitForIdle()` — and every node query, which
 * synchronizes internally before running — throws
 * `AppNotIdleException: Compose did not get idle after ... in 60 SECONDS`.
 * This reproduced even with `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and
 * with `compose.mainClock.autoAdvance = false` plus manual frame stepping,
 * and even happens inside `setContent`'s own initial synchronization before
 * any test code runs — so it is not something a differently-written
 * assertion can route around. `AlertDialog` alone and `OutlinedTextField`
 * alone both idle fine in isolation; it is specifically the combination.
 * Per the task brief, [newPlaylistDialogCreates] therefore verifies only
 * the reachable part of the flow (the playlist page and its "New
 * playlist…" action exist and are wired to a click handler, and tapping it
 * does not throw) and does **not** assert `onCreatePlaylist` is invoked —
 * verifying the typed-name → Create → callback path is not possible under
 * this Robolectric/Compose combination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class TrackActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val playlists = listOf(
        PlaylistEntity(id = 1, name = "Road Trip", color = "#3498db"),
        PlaylistEntity(id = 2, name = "Chill", color = "#2ecc71"),
    )

    @Test
    fun playNextActionInvokesAndDismisses() {
        var playedNext = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onPlayNext = { playedNext++ },
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play next").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, playedNext)
        assertEquals(1, dismissed)
    }

    @Test
    fun addToPlaylistShowsPlaylistPage() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Add to playlist").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithText("Road Trip").assertExists()
        compose.onNodeWithText("Chill").assertExists()
        compose.onNodeWithText("Play next").assertDoesNotExist()
    }

    @Test
    fun newPlaylistDialogCreates() {
        var createdName: String? = null
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = { createdName = it },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Add to playlist").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // Verify the action is really present and wired to a click handler
        // before triggering it (see Robolectric concession 2 in the class
        // KDoc for why we can't go further and assert on the dialog it
        // opens).
        compose.onNodeWithText("New playlist…").assertExists().assertHasClickAction()

        // Triggering the click is safe as the *last* interaction of the test
        // (no query runs afterwards to hang on the now-mounted dialog), and
        // still exercises the real onClick lambda wired by SheetAction.
        compose.onNodeWithText("New playlist…").performSemanticsAction(SemanticsActions.OnClick)

        // Cannot assert `createdName` here — see class KDoc. `createdName`
        // is expected to still be null since the create flow itself
        // (typing + tapping "Create") is unreachable under Robolectric.
        assertEquals(null, createdName)
    }

    /**
     * The "Track info" action is gated on a non-null `onShowInfo` alone (D1: no
     * longer Tidal-gated — the info sheet carries a Details section read off the
     * Track, so it is useful for a local track too). The three tests below pin the
     * happy path, the local-track case, and the null-handler gate.
     */
    @Test
    fun trackInfoActionShownAndInvokesHandler() {
        var infoShown = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(source = Source.TIDAL),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onShowInfo = { infoShown++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Track info").performSemanticsAction(SemanticsActions.OnClick)

        // The sheet must dismiss as it opens the info sheet — two stacked
        // ModalBottomSheets would otherwise fight for the same surface.
        assertEquals(1, infoShown)
        assertEquals(1, dismissed)
    }

    @Test
    fun trackInfoActionShownForLocalTrackWithHandler() {
        // D1: the Details section makes the info sheet useful for local tracks.
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(source = Source.LOCAL),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onShowInfo = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Track info").assertExists()
    }

    @Test
    fun trackInfoActionAbsentWhenHostProvidesNoHandler() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(source = Source.TIDAL),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        // No handler -> no dead row; also what keeps the action-sheet golden stable.
        compose.onNodeWithText("Track info").assertDoesNotExist()
    }

    @Test
    fun goToAlbumAndArtistShownWithHandlersAndInvokeThem() {
        var album = 0
        var artist = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onGoToAlbum = { album++ },
                    onGoToArtist = { artist++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Go to album").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, album)
        assertEquals(1, dismissed)

        compose.onNodeWithText("Go to artist").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, artist)
        assertEquals(2, dismissed)
    }

    @Test
    fun goToAlbumAbsentForTrackWithNoAlbum() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track().copy(album = null),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onGoToAlbum = {},
                    onGoToArtist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Go to album").assertDoesNotExist()
        compose.onNodeWithText("Go to artist").assertExists()
    }

    @Test
    fun favoriteLabelReflectsState() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
                    isFavorite = true,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Remove from favorites").assertExists()
    }
}
