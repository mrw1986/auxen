package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.ArtistRow
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.components.TrackInfoSheet
import io.github.auxen.ui.components.albumTargetFor
import kotlinx.coroutines.delay

private val TYPE_FILTERS = listOf("All", "Local", "Tidal")

// The result-type tabs (user decision 2026-07-17 — type tabs over section rails).
// Mirrors CollectionScreen's tab pattern; the index-based `tab` state feeds
// [searchTabContent].
private val SEARCH_TABS = listOf("Tracks", "Albums", "Artists")

/**
 * What a Search tab renders once the source filter is taken into account.
 *
 * The type-tab layout ships Tracks / Albums / Artists. Albums and Artists are
 * Tidal-only — the local DB has no album/artist entities yet (Slice 2) — so the
 * "Local" source filter on those two tabs has no data to show and resolves to
 * [TIDAL_ONLY_UNAVAILABLE] (an explanatory empty state), never a misleading
 * blank. The Tracks tab always renders tracks; the source filter then narrows
 * that list (see [searchTabContent]'s caller and
 * `ScreensLogicTest.searchTypeFilterSemantics`).
 */
internal enum class SearchTabContent { TRACKS, ALBUMS, ARTISTS, TIDAL_ONLY_UNAVAILABLE }

/**
 * Pure decision: which content a tab shows given the active source filter.
 * Mirrored by `ScreensLogicTest.searchTabContentSemantics`; keep the two in sync.
 */
internal fun searchTabContent(tab: Int, sourceFilter: String): SearchTabContent = when (tab) {
    1 -> if (sourceFilter == "Local") SearchTabContent.TIDAL_ONLY_UNAVAILABLE else SearchTabContent.ALBUMS
    2 -> if (sourceFilter == "Local") SearchTabContent.TIDAL_ONLY_UNAVAILABLE else SearchTabContent.ARTISTS
    else -> SearchTabContent.TRACKS
}

// Mirrors PlayerViewModel.onSearchQueryChange's debounce (delay(300)). While a
// keystroke is still within this window the VM has not started search() yet, so
// searchInFlight is false and results are empty — suppressing "No results" here
// keeps the field from flashing a failure state during normal typing.
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Search — desktop SearchView: debounced input, Tracks/Albums/Artists type tabs,
 * an All/Local/Tidal source filter, and DB-backed search history shown before
 * typing. Artist and album results are tappable and navigate straight to their
 * detail pages ([onOpenArtist]/[onOpenAlbum]) — the whole point of multi-type
 * search: the rebuilt artist page is finally reachable.
 */
@UnstableApi
@Composable
fun SearchScreen(
    viewModel: PlayerViewModel,
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String?) -> Unit,
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val albums by viewModel.searchAlbums.collectAsState()
    val artists by viewModel.searchArtists.collectAsState()
    val history by viewModel.searchHistoryItems.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val cachedIds by viewModel.cachedIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var typeFilter by rememberSaveable { mutableStateOf("All") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    // Local mirror of the VM's debounce: true while a keystroke is still waiting
    // out the debounce and search() has not run. Used to hold back the
    // "No results" state until the search has actually settled.
    var searchPending by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        searchPending = query.isNotBlank()
        if (query.isNotBlank()) {
            delay(SEARCH_DEBOUNCE_MS)
            searchPending = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.onSearchQueryChange(it) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search local + Tidal") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.commitSearch()
                    keyboard?.hide()
                },
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        if (query.isBlank()) {
            if (history.isNotEmpty()) {
                SectionHeader(
                    "Recent Searches",
                    actionLabel = "Clear all",
                    onAction = { viewModel.clearSearchHistory() },
                )
                LazyColumn {
                    items(history, key = { it }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onSearchQueryChange(item)
                                    viewModel.commitSearch()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                item,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            IconButton(onClick = { viewModel.deleteSearchHistory(item) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $item")
                            }
                        }
                    }
                }
            } else {
                // No query and no history: an inviting prompt, not a blank void.
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.empty_search_prompt_title),
                    subtitle = stringResource(R.string.empty_search_prompt_subtitle),
                )
            }
        } else {
            // Result-type tabs. Icon dropped for the same reason CollectionScreen
            // drops it: the ~26dp selection check eats the active tab's label width
            // and clips it at narrow widths.
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SEARCH_TABS.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = tab == index,
                        onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SEARCH_TABS.size),
                        icon = {},
                    ) { Text(label) }
                }
            }
            // Source filter — orthogonal to the type tabs (source = where a TRACK
            // came from). It narrows the Tracks tab and, on the Tidal-only
            // Albums/Artists tabs, its "Local" value selects the empty state.
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TYPE_FILTERS.forEach { filter ->
                    FilterChip(
                        selected = typeFilter == filter,
                        onClick = { typeFilter = filter },
                        label = { Text(filter) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            if (viewModel.searchInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
            }
            // Empty results with nothing in flight AND nothing pending in the
            // debounce is a genuine "no results", distinct from a still-loading
            // search (spinner above) or a query that hasn't been searched yet.
            val settled = !viewModel.searchInFlight && !searchPending
            when (searchTabContent(tab, typeFilter)) {
                SearchTabContent.TRACKS -> {
                    // Source chip filters the tracks list. Mirrored by
                    // ScreensLogicTest.searchTypeFilterSemantics; keep in sync.
                    val filtered = when (typeFilter) {
                        "Local" -> results.filter { it.source == Source.LOCAL }
                        "Tidal" -> results.filter { it.source == Source.TIDAL }
                        else -> results
                    }
                    if (filtered.isEmpty() && settled) {
                        EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = stringResource(R.string.empty_search_no_results_title),
                            subtitle = stringResource(R.string.empty_search_no_results_subtitle),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filtered, key = { "${it.source}:${it.sourceId}" }) { track ->
                                AuxenTrackRow(
                                    track = track,
                                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                                    isCached = track.isTidal && track.sourceId in cachedIds,
                                    onPlay = { viewModel.play(track) },
                                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                                    onLongPress = { sheetTrack = track },
                                    trailing = {
                                        IconButton(onClick = { viewModel.enqueue(track) }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                                contentDescription = "Add to queue",
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                SearchTabContent.ALBUMS -> {
                    if (albums.isEmpty() && settled) {
                        EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = stringResource(R.string.empty_search_no_results_title),
                            subtitle = stringResource(R.string.empty_search_no_results_subtitle),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                        ) {
                            // Deliberately UNKEYED (default index key): a short,
                            // static per-query list, never reordered, from an
                            // undocumented API — keying on id risks a hard crash if
                            // it ever repeats one.
                            items(albums) { result ->
                                AlbumCard(
                                    title = result.summary.title,
                                    artist = result.artistName,
                                    artUrl = result.summary.artworkUrl,
                                    source = Source.TIDAL,
                                    // artistName is the real album artist
                                    // (artists[0].name); AlbumDetailScreen also
                                    // re-fetches by tidalAlbumId, so even an empty
                                    // name self-corrects there.
                                    onClick = {
                                        onOpenAlbum(result.summary.title, result.artistName ?: "", result.summary.id)
                                    },
                                    // No local tracks to enqueue from a search card —
                                    // a tap opens the album to play.
                                    onPlay = null,
                                )
                            }
                        }
                    }
                }
                SearchTabContent.ARTISTS -> {
                    if (artists.isEmpty() && settled) {
                        EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = stringResource(R.string.empty_search_no_results_title),
                            subtitle = stringResource(R.string.empty_search_no_results_subtitle),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            // Unkeyed for the same reason as the albums grid.
                            items(artists) { link ->
                                ArtistRow(
                                    name = link.name,
                                    artUrl = link.artworkUrl,
                                    // The headline fix: tap navigates STRAIGHT to
                                    // the rebuilt artist page via tidalArtistId.
                                    onClick = { onOpenArtist(link.name, link.id) },
                                )
                            }
                        }
                    }
                }
                SearchTabContent.TIDAL_ONLY_UNAVAILABLE -> {
                    // The "Local" source filter on a Tidal-only tab: explain why
                    // there is nothing, rather than a blank that reads like a bug.
                    val isAlbums = tab == 1
                    EmptyState(
                        icon = if (isAlbums) Icons.Filled.Album else Icons.Filled.Person,
                        title = stringResource(
                            if (isAlbums) {
                                R.string.empty_search_albums_tidal_only_title
                            } else {
                                R.string.empty_search_artists_tidal_only_title
                            },
                        ),
                        subtitle = stringResource(
                            if (isAlbums) {
                                R.string.empty_search_albums_tidal_only_subtitle
                            } else {
                                R.string.empty_search_artists_tidal_only_subtitle
                            },
                        ),
                    )
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
            onShowInfo = { infoTrack = track },
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
            onGoToArtist = { onOpenArtist(track.artist, null) },
        )
    }

    infoTrack?.let { track ->
        val detail by viewModel.trackDetail.collectAsState()
        val detailLoading by viewModel.trackDetailLoading.collectAsState()
        val enriched by viewModel.enrichedInfoTrack.collectAsState()
        LaunchedEffect(track) { viewModel.loadTrackDetail(track) }
        TrackInfoSheet(
            // The enriched copy for THIS track (guarded against a stale value
            // from a previous open); falls back to the un-enriched track until
            // MediaMetadataRetriever lands.
            track = enriched?.takeIf { it.source == track.source && it.sourceId == track.sourceId } ?: track,
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
    }
}
