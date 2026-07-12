package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
    source: Source = Source.LOCAL,
    sourceId: String = "x",
    album: String? = "The Colour and the Shape",
    durationSeconds: Double? = null,
    format: String? = null,
    bitDepth: Int? = null,
    sampleRateHz: Int? = null,
    explicit: Boolean = false,
) = Track(
    title = title,
    artist = artist,
    source = source,
    sourceId = sourceId,
    album = album,
    durationSeconds = durationSeconds,
    format = format,
    bitDepth = bitDepth,
    sampleRateHz = sampleRateHz,
    explicit = explicit,
)

/**
 * Behavior tests for [AuxenTrackRow] — the canonical row shared across Library,
 * Search, Queue, and other track lists. Verifies interaction callbacks and the
 * conditional badges (explicit, quality) render from real [Track] data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class AuxenTrackRowUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapRowInvokesOnPlay() {
        var played = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = track(title = "Everlong"),
                    isFavorite = false,
                    onPlay = { played++ },
                    onToggleFavorite = {},
                )
            }
        }

        // The row's Text children merge into the clickable Row's semantics
        // node, so a click found via the title text lands on the row itself.
        compose.onNodeWithText("Everlong").performClick()

        assertEquals(1, played)
    }

    @Test
    fun longPressInvokesOnLongPress() {
        var longPressed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = track(title = "My Hero"),
                    isFavorite = false,
                    onPlay = {},
                    onToggleFavorite = {},
                    onLongPress = { longPressed++ },
                )
            }
        }

        compose.onNodeWithText("My Hero").performTouchInput { longClick() }

        assertEquals(1, longPressed)
    }

    @Test
    fun heartTogglesDescription() {
        var isFavorite by mutableStateOf(false)
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = track(),
                    isFavorite = isFavorite,
                    onPlay = {},
                    onToggleFavorite = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Add to favorites").assertExists()

        isFavorite = true
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Remove from favorites").assertExists()
    }

    @Test
    fun explicitBadgeOnlyWhenExplicit() {
        var trackState by mutableStateOf(track(title = "Clean Song", explicit = false))
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = trackState,
                    isFavorite = false,
                    onPlay = {},
                    onToggleFavorite = {},
                )
            }
        }

        compose.onNodeWithText("E").assertDoesNotExist()

        trackState = trackState.copy(explicit = true)
        compose.waitForIdle()

        compose.onNodeWithText("E").assertExists()
    }

    @Test
    fun tidalRowShowsQualityBadge_localDoesNot() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                Column {
                    AuxenTrackRow(
                        track = track(title = "Tidal Track", source = Source.TIDAL, format = "FLAC"),
                        isFavorite = false,
                        onPlay = {},
                        onToggleFavorite = {},
                    )
                    AuxenTrackRow(
                        track = track(title = "Local Track", source = Source.LOCAL, format = "FLAC"),
                        isFavorite = false,
                        onPlay = {},
                        onToggleFavorite = {},
                    )
                }
            }
        }

        // Only the TIDAL row renders a quality pill; LOCAL never shows one
        // (AuxenTrackRow gates QualityBadge on source == Source.TIDAL).
        compose.onAllNodesWithText("FLAC").assertCountEquals(1)
    }

    @Test
    fun durationFormatted() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = track(title = "Timed", durationSeconds = 227.0),
                    isFavorite = false,
                    onPlay = {},
                    onToggleFavorite = {},
                )
            }
        }

        compose.onNodeWithText("3:47").assertExists()
    }
}
