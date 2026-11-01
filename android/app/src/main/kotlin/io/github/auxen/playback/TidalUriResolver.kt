package io.github.auxen.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.auxen.playback.cache.CacheKind
import io.github.auxen.playback.cache.CachedPlayback
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Rewrites `auxen://tidal/<id>` DataSpecs to fresh Tidal stream URLs just
 * before the data source opens — the milestone-2 fix for short-lived URLs.
 * Runs on Media3's loader thread, so blocking on the network call is fine.
 *
 * [cachedPlayback] is checked first, so a song cached on disk never needs
 * the network: a progressive file plays straight from its `file://` URI.
 */
@UnstableApi
class TidalUriResolver(
    private val resolver: TrackResolver,
    private val cachedPlayback: (trackId: String) -> CachedPlayback? = { null },
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "auxen") return dataSpec
        val trackId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Malformed auxen URI: ${dataSpec.uri}")
        cachedPlayback(trackId)?.let { return cachedSpec(dataSpec, trackId, it) }
        val info = runBlocking { resolver.resolve(trackId) }
        // A song committed during the resolve comes back as its local file, which for
        // a DASH folder is a manifest that can't be read as a progressive stream.
        cachedPlayback(trackId)?.let { return cachedSpec(dataSpec, trackId, it) }
        if (info.uri.startsWith("data:")) {
            // A DASH manifest can't flow through a progressive DataSpec;
            // PlaybackService catches this and swaps in a DASH media item.
            throw TidalDashStreamException(trackId, info)
        }
        return dataSpec.withUri(Uri.parse(info.uri))
    }

    private fun cachedSpec(dataSpec: DataSpec, trackId: String, cached: CachedPlayback): DataSpec =
        when (cached.kind) {
            CacheKind.PROGRESSIVE -> dataSpec.withUri(Uri.parse(cached.uri))
            // Same as a network manifest: PlaybackService swaps in a DASH media item.
            CacheKind.DASH -> throw CachedDashStreamException(trackId, cached.uri)
        }
}

/** Signals that a Tidal track resolved to a DASH manifest, not a direct URL. */
class TidalDashStreamException(
    val trackId: String,
    val streamInfo: StreamInfo,
) : IOException("Tidal track $trackId resolved to a DASH manifest")

/** Signals that a Tidal track is cached as a local DASH folder whose manifest is at [manifestUri]. */
class CachedDashStreamException(
    val trackId: String,
    val manifestUri: String,
) : IOException("Tidal track $trackId is cached as a local DASH manifest")
