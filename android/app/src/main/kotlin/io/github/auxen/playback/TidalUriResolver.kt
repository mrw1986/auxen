package io.github.auxen.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Rewrites `auxen://tidal/<id>` DataSpecs to fresh Tidal stream URLs just
 * before the data source opens — the milestone-2 fix for short-lived URLs.
 * Runs on Media3's loader thread, so blocking on the network call is fine.
 */
@UnstableApi
class TidalUriResolver(private val resolver: TrackResolver) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "auxen") return dataSpec
        val trackId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Malformed auxen URI: ${dataSpec.uri}")
        val info = runBlocking { resolver.resolve(trackId) }
        if (info.uri.startsWith("data:")) {
            // A DASH manifest can't flow through a progressive DataSpec;
            // PlaybackService catches this and swaps in a DASH media item.
            throw TidalDashStreamException(trackId, info)
        }
        return dataSpec.withUri(Uri.parse(info.uri))
    }
}

/** Signals that a Tidal track resolved to a DASH manifest, not a direct URL. */
class TidalDashStreamException(
    val trackId: String,
    val streamInfo: StreamInfo,
) : IOException("Tidal track $trackId resolved to a DASH manifest")
