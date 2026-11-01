package io.github.auxen.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Skips load retries for errors a retry can't fix: a DASH swap signal, which needs a media-item
 * swap, and a missing file under the track cache at [cacheRootUri], which needs the service's
 * missing-file recovery. Media3's default only skips a bare FileNotFoundException, so without
 * this a missing cached segment stalls for about 3 s of retries first.
 */
@UnstableApi
internal class AuxenLoadErrorPolicy(private val cacheRootUri: String) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (isRecoverableDashError(error)) return C.TIME_UNSET
        val load = loadErrorInfo.loadEventInfo
        if (listOf(load.uri, load.dataSpec.uri).any { isMissingCacheFile(error, it.toString(), cacheRootUri) }) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
