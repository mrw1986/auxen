package io.github.auxen.playback.cache

import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TRACK_ID = "123"
private const val ASSET = "https://cdn.example/mediatracks/ASSET"
private const val SEGMENTS = 80
private const val BTS_URL = "https://cdn.example/bts/123.flac?token=a"
private const val FRESH_BTS_URL = "https://cdn.example/bts/123.flac?token=b"

private fun initUrl(token: String = "x") = "$ASSET/0.mp4?token=$token"
private fun segmentUrl(n: Int, token: String = "x") = "$ASSET/$n.mp4?token=$token"
private fun initBody(token: String) = "init $token".toByteArray()
private fun segmentBody(n: Int, token: String) = "segment $n $token ".repeat(n).toByteArray()

private fun dataUri(mpd: String) =
    "data:application/dash+xml;base64," + Base64.getEncoder().encodeToString(mpd.toByteArray())

/** Serves canned bodies by URL in place of the network, and records every request in order. */
private class FakeCdn : Interceptor {
    val requests = CopyOnWriteArrayList<String>()
    var contentType = "application/octet-stream"
    var onRequest: (String) -> Unit = {}
    private val bodies = mutableMapOf<String, ByteArray>()
    private val failures = mutableMapOf<String, MutableList<Int>>()

    fun serve(url: String, body: ByteArray) {
        bodies[url] = body
    }

    /** Answers [url] with [codes] in turn, then with its body. */
    fun fail(url: String, vararg codes: Int) {
        failures[url] = codes.toMutableList()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        requests += url
        onRequest(url)
        val code = failures[url]?.removeFirstOrNull() ?: if (url in bodies) 200 else 404
        val body = if (code == 200) bodies.getValue(url) else ByteArray(0)
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake $code")
            .header("Content-Type", contentType)
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }
}

/** Hands out [first] for a normal resolve and [fresh] for a forced one, recording each call. */
private class FakeResolver(private val first: StreamInfo, private val fresh: StreamInfo = first) {
    val calls = CopyOnWriteArrayList<Pair<String, Boolean>>()

    fun resolve(trackId: String, fresh: Boolean): StreamInfo {
        calls += trackId to fresh
        return if (fresh) this.fresh else first
    }
}

class TrackDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cdn = FakeCdn()
    private lateinit var into: File

    private val fixture: String by lazy {
        requireNotNull(javaClass.classLoader!!.getResource("dash/tidal_hires_manifest.mpd")).readText()
    }

    @Before
    fun setUp() {
        into = tmp.newFolder("staging")
    }

    private fun client(listener: EventListener = EventListener.NONE): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(cdn).eventListener(listener).build()

    private fun downloader(resolver: FakeResolver, http: OkHttpClient = client()) =
        TrackDownloader(http) { trackId, fresh -> resolver.resolve(trackId, fresh) }

    private fun dashInfo(mpd: String = fixture, sampleRateHz: Int = 96_000, bitDepth: Int = 24) =
        StreamInfo(dataUri(mpd), "application/dash+xml", sampleRateHz, bitDepth)

    // Same encode, newly signed segment URLs.
    private fun freshMpd() = fixture.replace("token=x", "token=y")

    private fun serveDash(token: String) {
        cdn.serve(initUrl(token), initBody(token))
        for (n in 1..SEGMENTS) cdn.serve(segmentUrl(n, token), segmentBody(n, token))
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit): IOException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected an IOException, got $error", error is IOException)
        return error as IOException
    }

    private suspend fun assertRefreshRejected(fresh: StreamInfo) {
        serveDash("x")
        serveDash("y")
        cdn.fail(segmentUrl(5), 403)

        expectIoFailure { downloader(FakeResolver(dashInfo(), fresh)).download(TRACK_ID, into) }

        // Nothing came from the fresh stream, so no segments from two streams were mixed.
        assertEquals(segmentUrl(5), cdn.requests.last())
    }

    @Test
    fun `BTS download writes one file and reports its size`() = runBlocking<Unit> {
        val body = Random(7).nextBytes(200_000) // several 64 KB chunks
        cdn.serve(BTS_URL, body)
        val info = StreamInfo(uri = BTS_URL, mimeType = "audio/flac")

        val result = downloader(FakeResolver(info)).download(TRACK_ID, into)

        assertEquals(CacheKind.PROGRESSIVE, result.kind)
        assertEquals("audio", result.entryFile)
        assertEquals(body.size.toLong(), result.sizeBytes)
        assertArrayEquals(body, File(into, "audio").readBytes())
        assertEquals(listOf("audio"), into.list()!!.toList())
        assertEquals("audio/flac", result.mimeType)
        assertEquals(info, result.streamInfo)
    }

    @Test
    fun `BTS mime type falls back to Content-Type`() = runBlocking<Unit> {
        cdn.serve(BTS_URL, ByteArray(10))
        cdn.contentType = "audio/mp4"

        val result = downloader(FakeResolver(StreamInfo(uri = BTS_URL))).download(TRACK_ID, into)

        assertEquals("audio/mp4", result.mimeType)
    }

    @Test
    fun `DASH download writes init, every segment and a local manifest`() = runBlocking<Unit> {
        serveDash("x")

        val result = downloader(FakeResolver(dashInfo())).download(TRACK_ID, into)

        assertEquals(CacheKind.DASH, result.kind)
        assertEquals("manifest.mpd", result.entryFile)
        assertEquals("application/dash+xml", result.mimeType)
        assertArrayEquals(initBody("x"), File(into, "init.mp4").readBytes())
        for (n in 1..SEGMENTS) assertArrayEquals(segmentBody(n, "x"), File(into, "seg_$n.mp4").readBytes())
        assertEquals(localizeMpd(fixture), File(into, "manifest.mpd").readText())
        val expectedFiles = setOf("init.mp4", "manifest.mpd") + (1..SEGMENTS).map { "seg_$it.mp4" }
        assertEquals(expectedFiles, into.list()!!.toSet())
        assertEquals(into.listFiles()!!.sumOf { it.length() }, result.sizeBytes)
    }

    @Test
    fun `DASH requests segments in order`() = runBlocking<Unit> {
        serveDash("x")

        downloader(FakeResolver(dashInfo())).download(TRACK_ID, into)

        assertEquals(listOf(initUrl()) + (1..SEGMENTS).map { segmentUrl(it) }, cdn.requests)
    }

    @Test
    fun `a 403 mid-DASH re-resolves once and resumes at the failing segment`() = runBlocking<Unit> {
        serveDash("x")
        serveDash("y")
        cdn.fail(segmentUrl(5), 403)
        val fresh = dashInfo(freshMpd())
        val resolver = FakeResolver(dashInfo(), fresh)

        val result = downloader(resolver).download(TRACK_ID, into)

        assertEquals(listOf(TRACK_ID to false, TRACK_ID to true), resolver.calls)
        // Only segment 5 goes out twice: once stale, once freshly signed.
        val expected = listOf(initUrl()) + (1..5).map { segmentUrl(it) } + (5..SEGMENTS).map { segmentUrl(it, "y") }
        assertEquals(expected, cdn.requests)
        assertArrayEquals(segmentBody(4, "x"), File(into, "seg_4.mp4").readBytes())
        assertArrayEquals(segmentBody(5, "y"), File(into, "seg_5.mp4").readBytes())
        assertEquals(fresh, result.streamInfo)
    }

    @Test
    fun `a second 403 fails the download`() = runBlocking<Unit> {
        serveDash("x")
        serveDash("y")
        cdn.fail(segmentUrl(5), 403)
        cdn.fail(segmentUrl(5, "y"), 403)
        val resolver = FakeResolver(dashInfo(), dashInfo(freshMpd()))

        expectIoFailure { downloader(resolver).download(TRACK_ID, into) }

        assertEquals(listOf(TRACK_ID to false, TRACK_ID to true), resolver.calls)
        assertEquals(segmentUrl(5, "y"), cdn.requests.last())
    }

    @Test
    fun `a 500 fails the download`() = runBlocking<Unit> {
        serveDash("x")
        cdn.fail(segmentUrl(5), 500)
        val resolver = FakeResolver(dashInfo())

        val error = expectIoFailure { downloader(resolver).download(TRACK_ID, into) }

        val message = error.message.orEmpty()
        assertTrue(message, "500" in message && "segment 5" in message)
        assertFalse(message, "http" in message)
        assertFalse(message, "token" in message)
        assertEquals(listOf(TRACK_ID to false), resolver.calls)
        assertEquals(segmentUrl(5), cdn.requests.last())
    }

    @Test
    fun `cancelling stops further requests`() = runBlocking<Unit> {
        val callCanceled = CountDownLatch(1)
        val sawCallCancel = AtomicBoolean(false)
        lateinit var download: Deferred<DownloadResult>
        serveDash("x")
        cdn.onRequest = { url ->
            if (url == segmentUrl(2)) {
                download.cancel()
                // Holds segment 2's response until the downloader cancels the OkHttp call in flight.
                sawCallCancel.set(callCanceled.await(5, TimeUnit.SECONDS))
            }
        }
        val listener = object : EventListener() {
            override fun canceled(call: Call) {
                if (call.request().url.toString() == segmentUrl(2)) callCanceled.countDown()
            }
        }
        download = async { downloader(FakeResolver(dashInfo()), client(listener)).download(TRACK_ID, into) }

        val outcome = runCatching { download.await() }.exceptionOrNull()

        assertTrue("expected a plain cancel, got $outcome", outcome is CancellationException)
        assertTrue("the in-flight call was not cancelled", sawCallCancel.get())
        assertEquals(listOf(initUrl(), segmentUrl(1), segmentUrl(2)), cdn.requests)
    }

    @Test
    fun `a finished download cancels no calls`() = runBlocking<Unit> {
        val canceled = CopyOnWriteArrayList<String>()
        val listener = object : EventListener() {
            override fun canceled(call: Call) {
                canceled += call.request().url.toString()
            }
        }
        serveDash("x")

        downloader(FakeResolver(dashInfo()), client(listener)).download(TRACK_ID, into)

        assertEquals(emptyList<String>(), canceled)
    }

    @Test
    fun `a malformed MPD fails with IOException`() = runBlocking<Unit> {
        expectIoFailure { downloader(FakeResolver(dashInfo("<MPD><Period>"))).download(TRACK_ID, into) }

        assertTrue(cdn.requests.isEmpty())
    }

    @Test
    fun `a segment URL that isn't absolute fails with IOException`() = runBlocking<Unit> {
        val relative = fixture.replace("media=\"$ASSET/", "media=\"")
        serveDash("x")

        val error = expectIoFailure { downloader(FakeResolver(dashInfo(relative))).download(TRACK_ID, into) }

        assertEquals(listOf(initUrl()), cdn.requests)
        assertFalse(error.message.orEmpty(), "token" in error.message.orEmpty())
    }

    @Test
    fun `a 403 on a BTS download re-resolves once and retries the file`() = runBlocking<Unit> {
        val body = Random(3).nextBytes(100_000)
        cdn.fail(BTS_URL, 403)
        cdn.serve(FRESH_BTS_URL, body)
        val fresh = StreamInfo(uri = FRESH_BTS_URL, mimeType = "audio/flac")
        val resolver = FakeResolver(StreamInfo(uri = BTS_URL, mimeType = "audio/flac"), fresh)

        val result = downloader(resolver).download(TRACK_ID, into)

        assertEquals(listOf(TRACK_ID to false, TRACK_ID to true), resolver.calls)
        assertEquals(listOf(BTS_URL, FRESH_BTS_URL), cdn.requests)
        assertArrayEquals(body, File(into, "audio").readBytes())
        assertEquals(body.size.toLong(), result.sizeBytes)
        assertEquals(fresh, result.streamInfo)
    }

    @Test
    fun `a re-resolve to another encode fails the download`() = runBlocking<Unit> {
        assertRefreshRejected(dashInfo(freshMpd(), sampleRateHz = 44_100, bitDepth = 16))
    }

    @Test
    fun `a re-resolve to other segment numbers fails the download`() = runBlocking<Unit> {
        assertRefreshRejected(dashInfo(freshMpd().replace("r=\"78\"", "r=\"77\"")))
    }

    @Test
    fun `a re-resolve to a progressive stream fails the download`() = runBlocking<Unit> {
        assertRefreshRejected(StreamInfo(uri = FRESH_BTS_URL, mimeType = "audio/flac"))
    }
}
