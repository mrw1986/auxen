package io.github.auxen.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.auxen.R
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.dsp.AutoEqProfile
import io.github.auxen.dsp.BalanceState
import io.github.auxen.dsp.BassBoostState
import io.github.auxen.dsp.EqState
import io.github.auxen.dsp.LimiterState
import io.github.auxen.dsp.ReplayGainState
import io.github.auxen.dsp.ReverbState
import io.github.auxen.dsp.VirtualizerState
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.AutoEqPickerResults
import io.github.auxen.ui.BandSlider
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.BalanceSection
import io.github.auxen.ui.components.BassBoostSection
import io.github.auxen.ui.components.BrandBlock
import io.github.auxen.ui.components.FxSectionCard
import io.github.auxen.ui.components.LimiterSection
import io.github.auxen.ui.components.QualityBadge
import io.github.auxen.ui.components.ReverbSection
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.TrackActionSheetContent
import io.github.auxen.ui.components.VirtualizerSection
import io.github.auxen.ui.components.VolumeNormalizationSection
import io.github.auxen.ui.components.formatDuration
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.testutil.auxenScreenshotName
import io.github.auxen.ui.theme.AuxenColors
import io.github.auxen.ui.theme.AuxenTheme
import io.github.auxen.ui.theme.Fraunces
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden screenshots for the core reusable components, in both the
 * light and dark [AuxenTheme] variants — every surface documented below is
 * captured as a light/dark pair. Not stating a total count here on purpose:
 * it goes stale every time a surface is added (final-review fix round,
 * Minor #4) — count the `@Test` methods below if you need the current total.
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
 * `autoeq-section-*` (AutoEq split, Task 2) wraps that same picker in the
 * real `FxSectionCard` chrome it now lives inside as its own "Tune for your
 * headphones" section, with a search field and the import button/credit
 * line reproduced alongside it — same "reproduce the widgets with
 * placeholder state" pattern as `mini-player-controls-*`/`search-field-*`
 * below, since this section (like those) has no further stateless
 * extraction beyond the picker itself (it owns an `ActivityResultLauncher`
 * and `Graph.autoEq`/`Graph.library` I/O).
 *
 * ## Top-bar-brand surface — composed context, not BrandBlock in isolation
 * The `top-bar-brand-*` goldens capture [BrandBlock] the way `MainActivity`
 * actually uses it: as a `CenterAlignedTopAppBar` title alongside the real
 * equalizer/account action icons, rather than standalone. This pins the
 * actual on-screen treatment (sizing/centering next to the actions), which
 * the standalone `brand-block-*` goldens above don't exercise.
 * `top-bar-brand-suppressed-*` pins the account-route variant, where
 * `MainActivity` renders an empty title lambda (icon-only bar) instead.
 *
 * ## Selection-states surface — the theme-parity fix's own regression pin
 * The `selection-states-*` goldens capture a `NavigationBar`, a selected +
 * unselected `FilterChip` pair, a `SingleChoiceSegmentedButtonRow`, and a
 * plain default-colored `Button` in one column — the surfaces the theme-
 * parity color-scheme fix targeted (Material 3's baseline `secondaryContainer`
 * is a purple/lavender absent from the Auxen palette) plus `onPrimary`, which
 * every default `Button` consumes implicitly (see e.g. `Screens.kt`'s Tidal
 * login buttons, `EqualizerScreen.kt`'s import button). Every selected state
 * and the button's content color here must read amber/warm; a regression
 * back to Material's baseline purple or white-on-amber should be visible at
 * a glance and fail the pixel diff.
 *
 * ## Mini-player-controls / search-field surfaces — faithful previews, not the real screens
 * `MiniPlayerBar` takes a live `PlayerViewModel` and `SearchScreen`'s field is
 * wired to one directly; neither has a stateless content composable to
 * extract (unlike `TrackActionSheetContent`/`AutoEqPickerResults`, which this
 * repo's authors already split out for testability). Rather than refactor
 * production code beyond this task's stated file list, `mini-player-controls-*`
 * and `search-field-*` reproduce the exact same widgets/parameters as the
 * real `MiniPlayerBar.kt`/`SearchScreen.kt` call sites (art radius, title
 * weight, play-button colors, field shape/colors) with placeholder state
 * instead of a wired ViewModel — same pattern as `TopBarBrandPreview`.
 *
 * ## Typography-details surface — pins facts no other golden exercises
 * The theme-parity typography fixes have no other coverage: nothing in the
 * app currently requests `FontWeight.Bold` in a `Fraunces` context (the
 * registration guards a latent bug, not a visible one), and there is no
 * `NowPlayingScreen` golden to catch its title's Fraunces-to-DM-Sans swap.
 * `typography-details-*` renders, in one column: the NowPlayingScreen title
 * style verbatim (`titleMedium.copy(fontSize = 22.sp)`, DM Sans, sans-serif
 * — was Fraunces `headlineSmall`), Fraunces SemiBold next to Fraunces Bold,
 * and a monospace duration string (proves DM Sans's lack of a `tnum`
 * feature is worked around by the font swap, not just documented).
 *
 * NOT proof that `Fraunces` Bold renders heavier than SemiBold: this golden
 * is only reliable to the extent Robolectric's Typeface cache for the
 * `fraunces.ttf` resource is clean when these two tests happen to run. Under
 * the full `testDebugUnitTest` suite (one shared JVM, no fork-per-class),
 * an earlier test's variable-font weight request can leave a stale cached
 * instance that a later, unrelated `FontWeight` request incorrectly reuses
 * — confirmed via `VariableFontWeightProbeTest` (`ui/theme/`): the exact
 * same composable, run in isolation, correctly shows Bold heavier; run as
 * part of the full suite, it does not, with the "wrong" measurement
 * matching an unrelated test's cached value bit-for-bit. The `Fraunces`
 * Bold registration itself is correct (see that test's class KDoc for the
 * full investigation) — this golden just cannot reliably prove it under
 * Robolectric's shared-JVM execution model.
 *
 * ## Per-effect FX section surfaces (DSP-b Task 4)
 * The `fx-section-card-*`, `fx-bass-boost-*`, `fx-balance-*`, `fx-limiter-*`,
 * `fx-reverb-*`, `fx-virtualizer-*`, and `fx-volume-normalization-*` goldens
 * capture the real production composables from `io.github.auxen.ui.components`
 * (`FxSections.kt`) directly — these already ARE stateless content
 * composables taking state/callbacks as explicit parameters, same shape as
 * [AutoEqPickerResults]/[TrackActionSheetContent], so unlike those two
 * surfaces no extraction was needed to make them goldenable. `fx-section-card-*`
 * pins the shared expandable-card primitive itself (collapsed + expanded,
 * placeholder title/subtitle/content) rather than any one effect, since
 * every section's header chrome — title, subtitle, switch, chevron — comes
 * from that one component.
 *
 * `fx-equalizer-*` (AutoEq split, Task 2) is the graphic EQ's first-ever
 * standalone golden — before the split it was always captured collapsed
 * only, as part of `equalizer-all-sections-collapsed-*` below, since its
 * content (10-band sliders + AutoEq picker together) lived directly inside
 * `EqualizerScreen` with no extraction. Splitting the picker out into its
 * own section left the graphic EQ's remaining content — preset button + the
 * 10 `BandSlider`s — genuinely stateless, so `BandSlider` was made
 * `internal` (same reasoning as [AutoEqPickerResults]) and this golden
 * reproduces the section with an asymmetric, non-flat gain curve (same
 * "distinguishable slider positions" reasoning as `fx-balance-*`'s
 * panned-right state).
 *
 * **Action-sheet drift trap, checked:** none of these twenty new surfaces
 * render a `ModalBottomSheet`, an open `DropdownMenu`, or any other
 * animated-entrance overlay that could fail to settle under Robolectric (see
 * the action-sheet surface note above). `FxSectionCard`'s expand/collapse is
 * a plain `if (expanded) { … }` conditional — no `AnimatedVisibility`, no
 * transition to wait out — and `fx-reverb-*`/`fx-equalizer-*` capture their
 * preset dropdown/menu in its default CLOSED state (just the trigger
 * button), never opened. All twenty render fully synchronously on first
 * composition, same as every other non-action-sheet surface in this file.
 *
 * ## "All sections collapsed" EqualizerScreen composition (DSP-b Task 4; AutoEq split, Task 2)
 * `equalizer-all-sections-collapsed-*` assembles the real per-section
 * composables (`BassBoostSection`, `BalanceSection`, etc., each with its own
 * default state, plus two manual [FxSectionCard] calls for the "Tune for
 * your headphones" and Equalizer entries, neither of which has an extracted
 * whole-section content composable of its own — see the `autoeq-section-*`
 * and `fx-equalizer-*` notes above for what each one's content actually
 * looks like expanded) with `expanded = false` for all eight. This is fully
 * faithful to what `EqualizerScreen` itself renders collapsed: a collapsed
 * `FxSectionCard` never composes its `content` lambda regardless of what's
 * inside it, so an empty lambda for the two manual entries is exactly as
 * faithful here as their real inline content would be. The full live
 * `EqualizerScreen()` composable itself is deliberately NOT captured — it
 * depends on process-wide singletons (`EqController.state`,
 * `AutoEqController.state`, `Graph.autoEq`, `Graph.library`) and an
 * `ActivityResultLauncher` for file import, none of which have a stateless
 * extraction the way `AutoEqPickerResults`/`TrackActionSheetContent` do.
 * Wiring or faking all of that for one golden would risk exactly the
 * shared-JVM test-order pollution this file's own typography-details
 * section documents above, for a golden that — being collapsed — wouldn't
 * exercise any of that live state anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // --- SourceBadge (solid + tinted) + QualityBadge row — theme-parity badge pins ---

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
            SourceBadge(Source.TIDAL, tinted = true)
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

    // --- Top bar with BrandBlock title (composed context — see class KDoc) ---

    @Test
    fun topBarBrand_light() = captureComponent("top-bar-brand-light", darkTheme = false) { TopBarBrandPreview() }

    @Test
    fun topBarBrand_dark() = captureComponent("top-bar-brand-dark", darkTheme = true) { TopBarBrandPreview() }

    @Test
    fun topBarBrandSuppressed_light() = captureComponent("top-bar-brand-suppressed-light", darkTheme = false) {
        TopBarBrandPreview(showTitle = false)
    }

    @Test
    fun topBarBrandSuppressed_dark() = captureComponent("top-bar-brand-suppressed-dark", darkTheme = true) {
        TopBarBrandPreview(showTitle = false)
    }

    @Composable
    private fun TopBarBrandPreview(showTitle: Boolean = true) {
        CenterAlignedTopAppBar(
            title = { if (showTitle) BrandBlock(compact = true) },
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Equalizer, contentDescription = "Equalizer")
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Person, contentDescription = "Account")
                }
            },
        )
    }

    // --- Selection states: nav bar + filter chips + segmented buttons (see class KDoc) ---

    @Test
    fun selectionStates_light() = captureComponent("selection-states-light", darkTheme = false) {
        SelectionStatesPreview()
    }

    @Test
    fun selectionStates_dark() = captureComponent("selection-states-dark", darkTheme = true) {
        SelectionStatesPreview()
    }

    @Composable
    private fun SelectionStatesPreview() {
        Column {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                    label = { Text("Collection") },
                )
            }
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("All" to true, "Tidal" to false).forEach { (label, selected) ->
                    FilterChip(
                        selected = selected,
                        onClick = {},
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AuxenColors.AmberPrimary,
                            selectedLabelColor = AuxenColors.BgDeep,
                        ),
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                listOf("Tracks", "Albums").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == 0,
                        onClick = {},
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) { Text(label) }
                }
            }
            // Plain default-colored Button — pins onPrimary content color (see
            // Screens.kt's Tidal login buttons, EqualizerScreen.kt's import button).
            Button(onClick = {}, modifier = Modifier.padding(16.dp)) {
                Text("Log in to Tidal")
            }
        }
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

    // --- Mini-player controls (see class KDoc) ---

    @Test
    fun miniPlayerControls_light() = captureComponent("mini-player-controls-light", darkTheme = false) {
        MiniPlayerControlsPreview()
    }

    @Test
    fun miniPlayerControls_dark() = captureComponent("mini-player-controls-dark", darkTheme = true) {
        MiniPlayerControlsPreview()
    }

    @Composable
    private fun MiniPlayerControlsPreview() {
        // Matches MiniPlayerBar.kt's own Surface(tonalElevation = 4.dp) wrapper
        // (final-review fix round, Minor #3) -- without it this preview was
        // rendering on a flat surface the real bar never uses.
        Surface(tonalElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    // A null model + Coil's empty placeholder renders fully
                    // transparent (confirmed against the existing track-row
                    // goldens: the art area is solid background-color, invisible
                    // regardless of clip shape) — a visible fill here is required
                    // for the 6dp radius this golden exists to pin to actually
                    // show up in the pixel diff.
                    model = null,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF808080)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nightcall", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "Kavinsky",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = {},
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = AuxenColors.AmberPrimary,
                        contentColor = AuxenColors.BgDeep,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
        }
    }

    // --- Search field (see class KDoc) ---

    @Test
    fun searchField_light() = captureComponent("search-field-light", darkTheme = false) { SearchFieldPreview() }

    @Test
    fun searchField_dark() = captureComponent("search-field-dark", darkTheme = true) { SearchFieldPreview() }

    @Composable
    private fun SearchFieldPreview() {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search local + Tidal") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }

    // --- Typography details (see class KDoc) ---

    @Test
    fun typographyDetails_light() = captureComponent("typography-details-light", darkTheme = false) {
        TypographyDetailsPreview()
    }

    @Test
    fun typographyDetails_dark() = captureComponent("typography-details-dark", darkTheme = true) {
        TypographyDetailsPreview()
    }

    @Composable
    private fun TypographyDetailsPreview() {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // NowPlayingScreen.kt's title style, verbatim.
            Text("Nightcall", style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp))
            Text("Fraunces SemiBold", fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text("Fraunces Bold", fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(formatDuration(258.0), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }

    // --- FxSectionCard primitive (collapsed + expanded — see class KDoc) ---

    @Test
    fun fxSectionCardCollapsed_light() = captureComponent("fx-section-card-collapsed-light", darkTheme = false) {
        FxSectionCardPreview(expanded = false)
    }

    @Test
    fun fxSectionCardCollapsed_dark() = captureComponent("fx-section-card-collapsed-dark", darkTheme = true) {
        FxSectionCardPreview(expanded = false)
    }

    @Test
    fun fxSectionCardExpanded_light() = captureComponent("fx-section-card-expanded-light", darkTheme = false) {
        FxSectionCardPreview(expanded = true)
    }

    @Test
    fun fxSectionCardExpanded_dark() = captureComponent("fx-section-card-expanded-dark", darkTheme = true) {
        FxSectionCardPreview(expanded = true)
    }

    @Composable
    private fun FxSectionCardPreview(expanded: Boolean) {
        FxSectionCard(
            title = "Sample section",
            subtitle = "Placeholder description text.",
            enabled = true,
            onEnabledChange = {},
            expanded = expanded,
            onExpandedChange = {},
        ) {
            Text("Sample content", style = MaterialTheme.typography.bodyMedium)
        }
    }

    // --- Bass boost section ---

    @Test
    fun fxBassBoost_light() = captureComponent("fx-bass-boost-light", darkTheme = false) { BassBoostPreview() }

    @Test
    fun fxBassBoost_dark() = captureComponent("fx-bass-boost-dark", darkTheme = true) { BassBoostPreview() }

    @Composable
    private fun BassBoostPreview() {
        BassBoostSection(
            state = BassBoostState(enabled = true),
            onStateChange = {},
            expanded = true,
            onExpandedChange = {},
        )
    }

    // --- Balance section (panned right, so the L/R asymmetry is pixel-visible) ---

    @Test
    fun fxBalance_light() = captureComponent("fx-balance-light", darkTheme = false) { BalancePreview() }

    @Test
    fun fxBalance_dark() = captureComponent("fx-balance-dark", darkTheme = true) { BalancePreview() }

    @Composable
    private fun BalancePreview() {
        BalanceSection(
            state = BalanceState(enabled = true, balance = 0.3f),
            onStateChange = {},
            expanded = true,
            onExpandedChange = {},
        )
    }

    // --- Limiter section (defaults — pins the corrected subtitle copy) ---

    @Test
    fun fxLimiter_light() = captureComponent("fx-limiter-light", darkTheme = false) { LimiterPreview() }

    @Test
    fun fxLimiter_dark() = captureComponent("fx-limiter-dark", darkTheme = true) { LimiterPreview() }

    @Composable
    private fun LimiterPreview() {
        LimiterSection(
            state = LimiterState(),
            onStateChange = {},
            expanded = true,
            onExpandedChange = {},
        )
    }

    // --- Reverb section (non-None preset selected, dropdown closed) ---

    @Test
    fun fxReverb_light() = captureComponent("fx-reverb-light", darkTheme = false) { ReverbPreview() }

    @Test
    fun fxReverb_dark() = captureComponent("fx-reverb-dark", darkTheme = true) { ReverbPreview() }

    @Composable
    private fun ReverbPreview() {
        ReverbSection(
            state = ReverbState(enabled = true, preset = 4), // Medium hall
            onStateChange = {},
            expanded = true,
            onExpandedChange = {},
        )
    }

    // --- Virtualizer section ---

    @Test
    fun fxVirtualizer_light() = captureComponent("fx-virtualizer-light", darkTheme = false) { VirtualizerPreview() }

    @Test
    fun fxVirtualizer_dark() = captureComponent("fx-virtualizer-dark", darkTheme = true) { VirtualizerPreview() }

    @Composable
    private fun VirtualizerPreview() {
        VirtualizerSection(
            state = VirtualizerState(enabled = true),
            onStateChange = {},
            expanded = true,
            onExpandedChange = {},
        )
    }

    // --- Volume normalization section (Album mode, non-zero preamp/fallback) ---

    @Test
    fun fxVolumeNormalization_light() = captureComponent("fx-volume-normalization-light", darkTheme = false) {
        VolumeNormalizationPreview()
    }

    @Test
    fun fxVolumeNormalization_dark() = captureComponent("fx-volume-normalization-dark", darkTheme = true) {
        VolumeNormalizationPreview()
    }

    @Composable
    private fun VolumeNormalizationPreview() {
        VolumeNormalizationSection(
            state = ReplayGainState(enabled = true, albumMode = true, preampDb = 3.0, fallbackDb = -6.0),
            onStateChange = {},
            expanded = true,
            onExpandedChange = {},
        )
    }

    // --- AutoEq section, expanded (AutoEq split, Task 2 — see class KDoc) ---

    @Test
    fun autoEqSection_light() = captureComponent("autoeq-section-light", darkTheme = false) { AutoEqSectionPreview() }

    @Test
    fun autoEqSection_dark() = captureComponent("autoeq-section-dark", darkTheme = true) { AutoEqSectionPreview() }

    @Composable
    private fun AutoEqSectionPreview() {
        FxSectionCard(
            title = stringResource(R.string.autoeq_section_title),
            subtitle = stringResource(R.string.autoeq_section_subtitle),
            enabled = true,
            onEnabledChange = {},
            expanded = true,
            onExpandedChange = {},
        ) {
            Text(
                "Corrections for 8,850 headphones, tuned to a neutral reference.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = "",
                onValueChange = {},
                label = { Text("Find your headphone model") },
                singleLine = true,
            )
            AutoEqPickerResults(
                activeProfile = "Sennheiser HD 650",
                results = autoEqResults,
                noMatches = false,
                onSelectProfile = {},
                onClearActive = {},
            )
            Button(onClick = {}) { Text("Import custom profile…") }
            Text(
                "Powered by AutoEq (MIT) — github.com/jaakkopasanen/AutoEq",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // --- Equalizer section, expanded (graphic EQ only, picker moved out to
    // its own "Tune for your headphones" section above — AutoEq split, Task
    // 2). No golden existed for this section pre-split (it was always
    // captured collapsed, as part of equalizer-all-sections-collapsed-* below)
    // — this is the first standalone golden for it. ---

    @Test
    fun fxEqualizer_light() = captureComponent("fx-equalizer-light", darkTheme = false) { EqualizerPreview() }

    @Test
    fun fxEqualizer_dark() = captureComponent("fx-equalizer-dark", darkTheme = true) { EqualizerPreview() }

    @Composable
    private fun EqualizerPreview() {
        // A non-flat, asymmetric curve (same reasoning as BalancePreview's
        // panned-right state) so the ten independent slider positions are
        // pixel-distinguishable rather than all sitting at the same height.
        val gains = listOf(6.0, 4.0, 2.0, 0.0, -2.0, -4.0, -2.0, 0.0, 3.0, 6.0)
        FxSectionCard(
            title = stringResource(R.string.fx_equalizer_title),
            subtitle = null,
            enabled = true,
            onEnabledChange = {},
            expanded = true,
            onExpandedChange = {},
        ) {
            Text("Active profile: Rock", style = MaterialTheme.typography.bodyMedium)
            Text("Preamp: -6.0 dB", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {}) { Text("Presets") }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                gains.forEachIndexed { index, gain ->
                    BandSlider(label = EqState.BAND_LABELS[index], gainDb = gain, onChange = {})
                }
            }
        }
    }

    // --- Equalizer screen, all eight sections collapsed (see class KDoc) ---
    // Eight, not seven: AutoEq split, Task 2 added a standalone "Tune for
    // your headphones" FxSectionCard alongside the pre-existing Equalizer one.

    @Test
    fun equalizerAllSectionsCollapsed_light() = captureComponent(
        "equalizer-all-sections-collapsed-light",
        darkTheme = false,
    ) { EqualizerAllSectionsCollapsedPreview() }

    @Test
    fun equalizerAllSectionsCollapsed_dark() = captureComponent(
        "equalizer-all-sections-collapsed-dark",
        darkTheme = true,
    ) { EqualizerAllSectionsCollapsedPreview() }

    @Composable
    private fun EqualizerAllSectionsCollapsedPreview() {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FxSectionCard(
                title = stringResource(R.string.autoeq_section_title),
                subtitle = stringResource(R.string.autoeq_section_subtitle),
                enabled = false,
                onEnabledChange = {},
                expanded = false,
                onExpandedChange = {},
            ) {}
            FxSectionCard(
                title = stringResource(R.string.fx_equalizer_title),
                subtitle = null,
                enabled = false,
                onEnabledChange = {},
                expanded = false,
                onExpandedChange = {},
            ) {}
            BassBoostSection(state = BassBoostState(), onStateChange = {}, expanded = false, onExpandedChange = {})
            BalanceSection(state = BalanceState(), onStateChange = {}, expanded = false, onExpandedChange = {})
            LimiterSection(state = LimiterState(), onStateChange = {}, expanded = false, onExpandedChange = {})
            ReverbSection(state = ReverbState(), onStateChange = {}, expanded = false, onExpandedChange = {})
            VirtualizerSection(state = VirtualizerState(), onStateChange = {}, expanded = false, onExpandedChange = {})
            VolumeNormalizationSection(state = ReplayGainState(), onStateChange = {}, expanded = false, onExpandedChange = {})
        }
    }
}
