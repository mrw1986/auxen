package io.github.auxen.playback.browse

import io.github.auxen.model.Track
import io.github.auxen.provider.tidal.TidalProvider

/**
 * The only network piece of the Android Auto browse tree: fetches album/artist
 * track lists on demand. Kept out of AuxenBrowseTree so that stays pure.
 *
 * Fail-safe contract: TidalProvider.albumDetail/artistTopTracks already collapse
 * "not logged in" and some mid-fetch errors into a NON-null EMPTY result (by
 * design, for their silent-fallback callers), so this wrapper cannot rely on a
 * null/exception to detect failure. It therefore (a) short-circuits to
 * [BrowseFetch.Failed] when not logged in, and (b) treats an empty track list as
 * [BrowseFetch.Failed] too — a followed artist / saved album always has tracks,
 * so empty here means a failed or partial fetch, not a real "0 tracks". This keeps
 * the car from ever showing a misleading empty list in place of the error node.
 */
class TidalBrowseFetcher(private val tidal: TidalProvider) {

    suspend fun albumTracks(tidalId: String): BrowseFetch {
        if (!tidal.isLoggedIn) return BrowseFetch.Failed
        val tracks = runCatching { tidal.albumDetail(tidalId)?.tracks }.getOrNull()
        return if (tracks.isNullOrEmpty()) BrowseFetch.Failed else BrowseFetch.Ok(tracks)
    }

    suspend fun artistTracks(tidalId: String): BrowseFetch {
        if (!tidal.isLoggedIn) return BrowseFetch.Failed
        val tracks = runCatching { tidal.artistTopTracks(tidalId) }.getOrNull()
        return if (tracks.isNullOrEmpty()) BrowseFetch.Failed else BrowseFetch.Ok(tracks)
    }
}

sealed interface BrowseFetch {
    data class Ok(val tracks: List<Track>) : BrowseFetch
    data object Failed : BrowseFetch
}
