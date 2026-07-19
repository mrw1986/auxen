package io.github.auxen.playback.browse

import io.github.auxen.model.Track
import io.github.auxen.provider.tidal.TidalProvider

/**
 * The only network piece of the Android Auto browse tree: fetches album/artist
 * track lists on demand. Kept out of AuxenBrowseTree so that stays pure. Any
 * failure (not logged in, network error, empty body) maps to [BrowseFetch.Failed]
 * so the caller renders the error node instead of a misleading empty list.
 */
class TidalBrowseFetcher(private val tidal: TidalProvider) {

    suspend fun albumTracks(tidalId: String): BrowseFetch =
        runCatching { tidal.albumDetail(tidalId)?.tracks }
            .getOrNull()
            ?.let { BrowseFetch.Ok(it) }
            ?: BrowseFetch.Failed

    suspend fun artistTracks(tidalId: String): BrowseFetch =
        runCatching { tidal.artistTopTracks(tidalId) }
            .getOrNull()
            ?.let { BrowseFetch.Ok(it) }
            ?: BrowseFetch.Failed
}

sealed interface BrowseFetch {
    data class Ok(val tracks: List<Track>) : BrowseFetch
    data object Failed : BrowseFetch
}
