package io.github.auxen.ui.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.dsp.AutoEqProfile
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.AutoEqPickerResults
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.BrandBlock
import io.github.auxen.ui.components.QualityBadge
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.TrackActionSheetContent
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.testutil.auxenScreenshotName
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden screenshots for the core reusable components, in both the
 * light and dark [AuxenTheme] variants (7 surfaces × 2 themes = 14 goldens).
 *
 * ## How goldens are produced / checked
 * `./gradlew :app:recordRoborazziDebug` writes the PNGs under
 * `app/src/test/screenshots/` (via [auxenScreenshotName]); they are committed.
 * `:app:verifyRoborazziDebug` re-renders and pixel-compares against them.
 *
 * ## Determinism
 *  - `@GraphicsMode(GraphicsMode.Mode.NATIVE)` renders real pixels (the default
 *    no-op canvas would capture blank images) — the first such usage in the repo.
 *  - `@Config(qualifiers = TEST_DEVICE, sdk = [35])` pins a fixed Pixel-7 profile
 *    and API level so goldens don't drift across machines / Robolectric SDKs.
 *  - Sample [Track]s use `albumArtUrl = null`, so `AsyncImage` renders its
 *    (empty) placeholder deterministically instead of hitting the network.
 *
 * ## Action-sheet surface — content composable, not the ModalBottomSheet
 * The `action-sheet-*` goldens capture [TrackActionSheetContent] (the sheet's
 * stateless action-list body) directly rather than the full [io.github.auxen.ui.components.TrackActionSheet].
 * The Material 3 `ModalBottomSheet` hosts its content in a separate window whose
 * animated entrance never settles under Robolectric (see the behavior-test
 * concessions in `TrackActionSheetUiTest`), so `onRoot().captureRoboImage(...)`
 * cannot capture the sheet reproducibly. The content composable renders in the
 * main composition and is pixel-stable, showing the same root action page a user
 * sees when the sheet opens.
 *
 * ## AutoEq picker surface — fake results, not the live 8,850-profile asset
 * The `autoeq-picker-*` goldens capture [AutoEqPickerResults] (extracted
 * from `EqualizerScreen` for the same reason as [TrackActionSheetContent]:
 * it is a stateless slice of a screen that otherwise depends on a live
 * repository / `Graph` singleton). It is rendered with a hardcoded 3-entry
 * fake result list rather than a real search over the bundled AutoEq
 * database, so the golden can never drift when the database asset changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TEST_DEVICE, sdk = [35])
class ComponentScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val tidalTrack = Track(
        title = "Nightcall",
        artist = "Kavinsky",
        source = Source.TIDAL,
        sourceId = "t1",
        album = "OutRun",
        durationSeconds = 258.0,
        format = "FLAC",
        bitDepth = 24,
        sampleRateHz = 96_000,
        albumArtUrl = null,
        explicit = false,
    )

    private val localTrack = Track(
        title = "Everlong",
        artist = "Foo Fighters",
        source = Source.LOCAL,
        sourceId = "l1",
        album = "The Colour and the Shape",
        durationSeconds = 250.0,
        format = "MP3",
        bitrateKbps = 320,
        albumArtUrl = null,
        explicit = true,
    )

    private val playlists = listOf(
        PlaylistEntity(id = 1, name = "Road Trip", color = "#3498db"),
        PlaylistEntity(id = 2, name = "Chill", color = "#2ecc71"),
    )

    // Fake AutoEq search results — a golden must never depend on the bundled
    // 8,850-profile database, so this is a fixed 3-entry stand-in.
    private val autoEqResults = listOf(
        AutoEqProfile(index = 0, name = "Sennheiser HD 650", source = "AutoEq", rig = "Averaged"),
        AutoEqProfile(index = 1, name = "Sony WH-1000XM4", source = "AutoEq", rig = "Harman over-ear 2018"),
        AutoEqProfile(index = 2, name = "Sennheiser HD 800S", source = "Crinacle", rig = "5128"),
    )

    /**
     * Renders [content] inside `AuxenTheme(darkTheme) { Surface { … } }` and
     * writes the root's pixels to the golden named [name].
     */
    private fun captureComponent(
        name: String,
        darkTheme: Boolean,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            AuxenTheme(darkTheme = darkTheme) {
                Surface {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage(auxenScreenshotName(name))
    }

    // --- AuxenTrackRow (Tidal, favorited, Hi-Res quality badge) ---

    @Test
    fun trackRowTidalFavorite_light() = captureComponent("track-row-tidal-favorite-light", darkTheme = false) {
        AuxenTrackRow(track = tidalTrack, isFavorite = true, onPlay = {}, onToggleFavorite = {})
    }

    @Test
    fun trackRowTidalFavorite_dark() = captureComponent("track-row-tidal-favorite-dark", darkTheme = true) {
        AuxenTrackRow(track = tidalTrack, isFavorite = true, onPlay = {}, onToggleFavorite = {})
    }

    // --- AuxenTrackRow (Local, explicit badge, no quality pill) ---

    @Test
    fun trackRowLocalExplicit_light() = captureComponent("track-row-local-explicit-light", darkTheme = false) {
        AuxenTrackRow(track = localTrack, isFavorite = false, onPlay = {}, onToggleFavorite = {})
    }

    @Test
    fun trackRowLocalExplicit_dark() = captureComponent("track-row-local-explicit-dark", darkTheme = true) {
        AuxenTrackRow(track = localTrack, isFavorite = false, onPlay = {}, onToggleFavorite = {})
    }

    // --- AlbumCard ---

    @Test
    fun albumCard_light() = captureComponent("album-card-light", darkTheme = false) {
        AlbumCard(title = "OutRun", artist = "Kavinsky", artUrl = null, source = Source.TIDAL, onClick = {}, onPlay = {})
    }

    @Test
    fun albumCard_dark() = captureComponent("album-card-dark", darkTheme = true) {
        AlbumCard(title = "OutRun", artist = "Kavinsky", artUrl = null, source = Source.TIDAL, onClick = {}, onPlay = {})
    }

    // --- SourceBadge + QualityBadge row ---

    @Test
    fun badgesRow_light() = captureComponent("badges-row-light", darkTheme = false) { BadgesRow() }

    @Test
    fun badgesRow_dark() = captureComponent("badges-row-dark", darkTheme = true) { BadgesRow() }

    @Composable
    private fun BadgesRow() {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SourceBadge(Source.TIDAL)
            SourceBadge(Source.LOCAL)
            QualityBadge("Hi-Res")
            QualityBadge("FLAC")
            QualityBadge("MP3")
        }
    }

    // --- SectionHeader with action link ---

    @Test
    fun sectionHeader_light() = captureComponent("section-header-light", darkTheme = false) {
        SectionHeader(title = "Recently Played", actionLabel = "See all", onAction = {})
    }

    @Test
    fun sectionHeader_dark() = captureComponent("section-header-dark", darkTheme = true) {
        SectionHeader(title = "Recently Played", actionLabel = "See all", onAction = {})
    }

    // --- TrackActionSheet root page (content composable — see class KDoc) ---

    @Test
    fun actionSheet_light() = captureComponent("action-sheet-light", darkTheme = false) { ActionSheetRootPage() }

    @Test
    fun actionSheet_dark() = captureComponent("action-sheet-dark", darkTheme = true) { ActionSheetRootPage() }

    @Composable
    private fun ActionSheetRootPage() {
        TrackActionSheetContent(
            track = tidalTrack,
            isFavorite = true,
            playlists = playlists,
            showPlaylists = false,
            onPlay = {},
            onPlayNext = {},
            onEnqueue = {},
            onToggleFavorite = {},
            onAddToPlaylist = {},
            onShowPlaylists = {},
            onNewPlaylist = {},
        )
    }

    // --- BrandBlock (full + compact) ---

    @Test
    fun brandBlockFull_light() = captureComponent("brand-block-full-light", darkTheme = false) {
        BrandBlock()
    }

    @Test
    fun brandBlockFull_dark() = captureComponent("brand-block-full-dark", darkTheme = true) {
        BrandBlock()
    }

    @Test
    fun brandBlockCompact_light() = captureComponent("brand-block-compact-light", darkTheme = false) {
        BrandBlock(compact = true)
    }

    @Test
    fun brandBlockCompact_dark() = captureComponent("brand-block-compact-dark", darkTheme = true) {
        BrandBlock(compact = true)
    }

    // --- AutoEq picker (active-profile row + fake search results) ---

    @Test
    fun autoEqPicker_light() = captureComponent("autoeq-picker-light", darkTheme = false) { AutoEqPickerPreview() }

    @Test
    fun autoEqPicker_dark() = captureComponent("autoeq-picker-dark", darkTheme = true) { AutoEqPickerPreview() }

    @Composable
    private fun AutoEqPickerPreview() {
        AutoEqPickerResults(
            activeProfile = "Sennheiser HD 650",
            results = autoEqResults,
            noMatches = false,
            onSelectProfile = {},
            onClearActive = {},
        )
    }
}
