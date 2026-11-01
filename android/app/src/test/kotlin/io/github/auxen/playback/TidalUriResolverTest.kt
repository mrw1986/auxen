package io.github.auxen.playback

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import io.github.auxen.playback.cache.CacheKind
import io.github.auxen.playback.cache.CachedPlayback
import io.github.auxen.provider.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: AuxenApp would open the Room database at startup.
@Config(application = Application::class)
class TidalUriResolverTest {

    private var fetchCount = 0
    private var streamUri = "https://cdn.example/42"
    private var cached: CachedPlayback? = null

    private val resolver = TidalUriResolver(
        TrackResolver(fetch = { fetchCount++; StreamInfo(uri = streamUri) }),
        cachedPlayback = { id -> cached?.takeIf { id == "42" } },
    )

    private fun spec(uri: String, position: Long = 0) = DataSpec.Builder().setUri(Uri.parse(uri)).setPosition(position).build()

    @Test
    fun `non-auxen specs pass through`() {
        cached = CachedPlayback(CacheKind.PROGRESSIVE, "file:///cache/42/audio")
        // A segment of a local manifest, relative to its file:// folder.
        val segment = spec("file:///cache/42/seg_3.mp4")

        assertSame(segment, resolver.resolveDataSpec(segment))
        assertEquals(0, fetchCount)
    }

    @Test
    fun `a cached progressive track resolves to its file without fetching`() {
        cached = CachedPlayback(CacheKind.PROGRESSIVE, "file:///cache/42/audio")

        val resolved = resolver.resolveDataSpec(spec("auxen://tidal/42", position = 4096))

        assertEquals("file:///cache/42/audio", resolved.uri.toString())
        assertEquals(4096, resolved.position)
        assertEquals(0, fetchCount)
    }

    @Test
    fun `a cached DASH track throws CachedDashStreamException with the manifest uri`() {
        cached = CachedPlayback(CacheKind.DASH, "file:///cache/42/manifest.mpd")

        val e = assertThrows(CachedDashStreamException::class.java) {
            resolver.resolveDataSpec(spec("auxen://tidal/42"))
        }

        assertEquals("42", e.trackId)
        assertEquals("file:///cache/42/manifest.mpd", e.manifestUri)
        assertEquals(0, fetchCount)
    }

    @Test
    fun `a track cached as DASH during the resolve still plays as DASH`() {
        // The first lookup misses, then the resolve reads the new entry's local info.
        val manifest = "file:///cache/42/manifest.mpd"
        var lookups = 0
        val racing = TidalUriResolver(
            TrackResolver(fetch = { fetchCount++; StreamInfo(uri = streamUri) }, cachedInfo = { StreamInfo(uri = manifest) }),
            cachedPlayback = { if (lookups++ == 0) null else CachedPlayback(CacheKind.DASH, manifest) },
        )

        val e = assertThrows(CachedDashStreamException::class.java) {
            racing.resolveDataSpec(spec("auxen://tidal/42"))
        }

        assertEquals(manifest, e.manifestUri)
        assertEquals(0, fetchCount)
    }

    @Test
    fun `an uncached direct stream resolves as before`() {
        val resolved = resolver.resolveDataSpec(spec("auxen://tidal/42"))

        assertEquals(streamUri, resolved.uri.toString())
        assertEquals(1, fetchCount)
    }

    @Test
    fun `an uncached DASH stream throws TidalDashStreamException as before`() {
        streamUri = "data:application/dash+xml;base64,PE1QRD4="

        val e = assertThrows(TidalDashStreamException::class.java) {
            resolver.resolveDataSpec(spec("auxen://tidal/42"))
        }

        assertEquals("42", e.trackId)
        assertEquals(streamUri, e.streamInfo.uri)
        assertEquals(1, fetchCount)
    }
}
