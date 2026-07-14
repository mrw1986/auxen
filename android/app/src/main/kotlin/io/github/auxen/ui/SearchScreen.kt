package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.theme.AuxenColors
import kotlinx.coroutines.delay

private val TYPE_FILTERS = listOf("All", "Local", "Tidal")

// Mirrors PlayerViewModel.onSearchQueryChange's debounce (delay(300)). While a
// keystroke is still within this window the VM has not started search() yet, so
// searchInFlight is false and results are empty — suppressing "No results" here
// keeps the field from flashing a failure state during normal typing.
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Search — desktop SearchView: debounced input, type filter chips,
 * DB-backed search history shown before typing.
 */
@UnstableApi
@Composable
fun SearchScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val history by viewModel.searchHistoryItems.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var typeFilter by rememberSaveable { mutableStateOf("All") }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
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
                            selectedContainerColor = AuxenColors.AmberPrimary,
                            selectedLabelColor = AuxenColors.BgDeep,
                        ),
                    )
                }
            }
            if (viewModel.searchInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
            }
            val filtered = when (typeFilter) {
                "Local" -> results.filter { it.source == Source.LOCAL }
                "Tidal" -> results.filter { it.source == Source.TIDAL }
                else -> results
            }
            if (filtered.isEmpty() && !viewModel.searchInFlight && !searchPending) {
                // Empty results with nothing in flight AND nothing pending in the
                // debounce is a genuine "no results", distinct from a still-loading
                // search (spinner above) or a query that hasn't been searched yet —
                // never a blank void that reads like a swallowed error.
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
                            onPlay = { viewModel.play(track) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onLongPress = { sheetTrack = track },
                            trailing = {
                                IconButton(onClick = { viewModel.enqueue(track) }) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to queue")
                                }
                            },
                        )
                    }
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
        )
    }
}
