package io.github.auxen.playback.cache

import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
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

/**
 * A body that reports every read to [onRead] (bytes served so far, and whether this read hit
 * EOF) and can claim a [contentLength] it doesn't have, as an HTTP/2 stream cut short does.
 */
private class FakeBody(
    private val bytes: ByteArray,
    private val type: MediaType,
    private val claimedLength: Long,
    private val onRead: (served: Long, eof: Boolean) -> Unit,
) : ResponseBody() {
    private val source: BufferedSource by lazy {
        object : Source {
            private var served = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, (bytes.size - served).toLong()).toInt()
                if (count == 0) return (-1L).also { onRead(served.toLong(), true) }
                sink.write(bytes, served, count)
                served += count
                onRead(served.toLong(), false)
                return count.toLong()
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    override fun contentType(): MediaType = type
    override fun contentLength(): Long = claimedLength
    override fun source(): BufferedSource = source
}

/** Serves canned bodies by URL in place of the network, and records every request in order. */
private class FakeCdn : Interceptor {
    val requests = CopyOnWriteArrayList<String>()
    var contentType = "application/octet-stream"
    var onRequest: (String) -> Unit = {}
    var onBodyRead: (url: String, served: Long, eof: Boolean) -> Unit = { _, _, _ -> }
    private val bodies = mutableMapOf<String, ByteArray>()
    private val failures = mutableMapOf<String, MutableList<Int>>()
    private val claimedLengths = mutableMapOf<String, Long>()

    fun serve(url: String, body: ByteArray) {
        bodies[url] = body
    }

    /** Answers [url] with [codes] in turn, then with its body. */
    fun fail(url: String, vararg codes: Int) {
        failures[url] = codes.toMutableList()
    }

    /** Makes [url]'s body claim [length] bytes, whatever it holds. */
    fun claimLength(url: String, length: Long) {
        claimedLengths[url] = length
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        requests += url
        onRequest(url)
        val code = failures[url]?.removeFirstOrNull() ?: if (url in bodies) 200 else 404
        val bytes = if (code == 200) bodies.getValue(url) else ByteArray(0)
        val length = claimedLengths[url] ?: bytes.size.toLong()
        val body = FakeBody(bytes, contentType.toMediaType(), length) { served, eof -> onBodyRead(url, served, eof) }
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake $code")
            .header("Content-Type", contentType)
            .body(body)
            .build()
    }
}

/** Hands out [first] for a normal resolve and [fresh] for a forced one, recording each call. */
private class FakeResolver(private val first: StreamInfo, private val fresh: StreamInfo = first) {
    val calls = CopyOnWriteArrayList<Pair<String, Boolean>>()
    var onResolve: (fresh: Boolean) -> Unit = {}

    fun resolve(trackId: String, fresh: Boolean): StreamInfo {
        calls += trackId to fresh
        onResolve(fresh)
        return if (fresh) this.fresh else first
    }
}

/** Records the URL of every call OkHttp reports as cancelled. */
private fun cancelRecorder(canceled: MutableList<String>) = object : EventListener() {
    override fun canceled(call: Call) {
        canceled += call.request().url.toString()
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

    private suspend fun assertCancelled(download: Deferred<DownloadResult>) {
        val outcome = runCatching { download.await() }.exceptionOrNull()
        assertTrue("expected a plain cancel, got $outcome", outcome is CancellationException)
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

        assertCancelled(download)
        assertTrue("the in-flight call was not cancelled", sawCallCancel.get())
        assertEquals(listOf(initUrl(), segmentUrl(1), segmentUrl(2)), cdn.requests)
    }

    @Test
    fun `a cancel as a segment finishes makes no further request`() = runBlocking<Unit> {
        lateinit var download: Deferred<DownloadResult>
        serveDash("x")
        cdn.onBodyRead = { url, _, eof -> if (eof && url == segmentUrl(2)) download.cancel() }
        download = async { downloader(FakeResolver(dashInfo())).download(TRACK_ID, into) }

        assertCancelled(download)
        assertEquals(listOf(initUrl(), segmentUrl(1), segmentUrl(2)), cdn.requests)
    }

    @Test
    fun `a cancel during a re-resolve makes no further request`() = runBlocking<Unit> {
        lateinit var download: Deferred<DownloadResult>
        serveDash("x")
        serveDash("y")
        cdn.fail(segmentUrl(5), 403)
        val resolver = FakeResolver(dashInfo(), dashInfo(freshMpd()))
        // Lands between two parts, outside any call, so only the check before each part catches it.
        resolver.onResolve = { fresh -> if (fresh) download.cancel() }
        download = async { downloader(resolver).download(TRACK_ID, into) }

        assertCancelled(download)
        assertEquals(listOf(initUrl()) + (1..5).map { segmentUrl(it) }, cdn.requests)
    }

    @Test
    fun `a cancel mid-file stops reading the body`() = runBlocking<Unit> {
        lateinit var download: Deferred<DownloadResult>
        val body = Random(5).nextBytes(1_000_000)
        val served = AtomicLong()
        cdn.serve(BTS_URL, body)
        cdn.onBodyRead = { _, total, _ ->
            served.set(total)
            if (total >= 64 * 1024) download.cancel()
        }
        download = async { downloader(FakeResolver(StreamInfo(uri = BTS_URL))).download(TRACK_ID, into) }

        assertCancelled(download)
        assertTrue("read ${served.get()} of ${body.size} bytes", served.get() < body.size)
    }

    @Test
    fun `a finished download cancels no calls`() = runBlocking<Unit> {
        val canceled = CopyOnWriteArrayList<String>()
        serveDash("x")

        downloader(FakeResolver(dashInfo()), client(cancelRecorder(canceled))).download(TRACK_ID, into)

        assertEquals(emptyList<String>(), canceled)
    }

    @Test
    fun `a failed part cancels no calls`() = runBlocking<Unit> {
        val canceled = CopyOnWriteArrayList<String>()
        serveDash("x")
        serveDash("y")
        cdn.fail(segmentUrl(5), 403)
        val resolver = FakeResolver(dashInfo(), dashInfo(freshMpd()))

        downloader(resolver, client(cancelRecorder(canceled))).download(TRACK_ID, into)

        assertEquals(emptyList<String>(), canceled)
    }

    @Test
    fun `an empty segment fails the download`() = runBlocking<Unit> {
        serveDash("x")
        cdn.serve(segmentUrl(5), ByteArray(0))

        val error = expectIoFailure { downloader(FakeResolver(dashInfo())).download(TRACK_ID, into) }

        assertEquals("Empty body for segment 5", error.message)
        assertEquals(segmentUrl(5), cdn.requests.last())
    }

    @Test
    fun `an empty BTS body fails the download`() = runBlocking<Unit> {
        cdn.serve(BTS_URL, ByteArray(0))

        val error = expectIoFailure { downloader(FakeResolver(StreamInfo(uri = BTS_URL))).download(TRACK_ID, into) }

        assertEquals("Empty body for audio", error.message)
    }

    @Test
    fun `a body shorter than its Content-Length fails the download`() = runBlocking<Unit> {
        serveDash("x")
        cdn.claimLength(segmentUrl(5), segmentBody(5, "x").size + 100L)

        val error = expectIoFailure { downloader(FakeResolver(dashInfo())).download(TRACK_ID, into) }

        assertEquals("Short body for segment 5", error.message)
        assertEquals(segmentUrl(5), cdn.requests.last())
    }

    @Test
    fun `a 401 mid-DASH re-resolves once and resumes`() = runBlocking<Unit> {
        serveDash("x")
        serveDash("y")
        cdn.fail(segmentUrl(10), 401)
        val resolver = FakeResolver(dashInfo(), dashInfo(freshMpd()))

        downloader(resolver).download(TRACK_ID, into)

        assertEquals(listOf(TRACK_ID to false, TRACK_ID to true), resolver.calls)
        val expected = listOf(initUrl()) + (1..10).map { segmentUrl(it) } + (10..SEGMENTS).map { segmentUrl(it, "y") }
        assertEquals(expected, cdn.requests)
    }

    @Test
    fun `a 403 on init re-resolves once and resumes`() = runBlocking<Unit> {
        serveDash("x")
        serveDash("y")
        cdn.fail(initUrl(), 403)
        val resolver = FakeResolver(dashInfo(), dashInfo(freshMpd()))

        downloader(resolver).download(TRACK_ID, into)

        assertEquals(listOf(TRACK_ID to false, TRACK_ID to true), resolver.calls)
        assertEquals(listOf(initUrl(), initUrl("y")) + (1..SEGMENTS).map { segmentUrl(it, "y") }, cdn.requests)
        assertArrayEquals(initBody("y"), File(into, "init.mp4").readBytes())
    }

    @Test
    fun `a 410 on a BTS download re-resolves once and retries the file`() = runBlocking<Unit> {
        val body = Random(4).nextBytes(10_000)
        cdn.fail(BTS_URL, 410)
        cdn.serve(FRESH_BTS_URL, body)
        val resolver = FakeResolver(StreamInfo(uri = BTS_URL), StreamInfo(uri = FRESH_BTS_URL))

        downloader(resolver).download(TRACK_ID, into)

        assertEquals(listOf(TRACK_ID to false, TRACK_ID to true), resolver.calls)
        assertEquals(listOf(BTS_URL, FRESH_BTS_URL), cdn.requests)
        assertArrayEquals(body, File(into, "audio").readBytes())
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

    private suspend fun expectTooLarge(block: suspend () -> Unit): SongTooLargeException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a SongTooLargeException, got $error", error is SongTooLargeException)
        return error as SongTooLargeException
    }

    @Test
    fun `a Content-Length over budget stops before reading the body`() = runBlocking<Unit> {
        cdn.serve(BTS_URL, Random(5).nextBytes(200_000))
        val reads = AtomicLong()
        cdn.onBodyRead = { _, _, _ -> reads.incrementAndGet() }

        val error = expectTooLarge {
            downloader(FakeResolver(StreamInfo(uri = BTS_URL))).download(TRACK_ID, into, maxBytes = 199_999)
        }

        assertEquals(TRACK_ID, error.trackId)
        assertEquals(200_000L, error.sizeBytes)
        assertEquals(0L, reads.get())
        assertTrue(into.list()!!.isEmpty())
        assertFalse(error.message.orEmpty(), "cdn.example" in error.message.orEmpty())
    }

    @Test
    fun `a body that runs past its budget stops at the budget`() = runBlocking<Unit> {
        val body = Random(6).nextBytes(500_000)
        cdn.serve(BTS_URL, body)
        // No Content-Length, as a chunked response has none.
        cdn.claimLength(BTS_URL, -1)
        val served = AtomicLong()
        cdn.onBodyRead = { _, bytes, _ -> served.set(bytes) }

        val error = expectTooLarge {
            downloader(FakeResolver(StreamInfo(uri = BTS_URL))).download(TRACK_ID, into, maxBytes = 100_000)
        }

        assertTrue("${error.sizeBytes}", error.sizeBytes > 100_000)
        assertTrue(File(into, "audio").length() <= 100_000)
        assertTrue("read ${served.get()}", served.get() < body.size)
    }

    @Test
    fun `a DASH stream whose real format is over budget stops before the first segment`() = runBlocking<Unit> {
        serveDash("x")
        val seconds = 315.037
        val expected = estimateBytes(seconds, bitDepth = 24, sampleRateHz = 96_000)

        val error = expectTooLarge {
            downloader(FakeResolver(dashInfo())).download(TRACK_ID, into, expected - 1, seconds)
        }

        assertEquals(expected, error.sizeBytes)
        assertTrue(cdn.requests.isEmpty())
        assertTrue(into.list()!!.isEmpty())
    }

    @Test
    fun `a DASH download that runs past its budget stops and scales up its size`() = runBlocking<Unit> {
        serveDash("x")
        val total = initBody("x").size + (1..SEGMENTS).sumOf { segmentBody(it, "x").size.toLong() }
        val budget = total / 2

        val error = expectTooLarge { downloader(FakeResolver(dashInfo())).download(TRACK_ID, into, budget) }

        // Scaled from the segments done, so it's past the budget though only part was fetched.
        assertTrue("${error.sizeBytes}", error.sizeBytes > budget)
        assertTrue(cdn.requests.size < 1 + SEGMENTS)
        assertTrue(into.listFiles()!!.sumOf { it.length() } <= budget)
    }

    @Test
    fun `a download that fits its budget exactly works as before`() = runBlocking<Unit> {
        val body = Random(8).nextBytes(150_000)
        cdn.serve(BTS_URL, body)

        val result = downloader(FakeResolver(StreamInfo(uri = BTS_URL))).download(TRACK_ID, into, maxBytes = 150_000)

        assertEquals(150_000L, result.sizeBytes)
        assertArrayEquals(body, File(into, "audio").readBytes())
    }

    @Test
    fun `a DASH download counts its local manifest against the budget`() = runBlocking<Unit> {
        serveDash("x")
        val full = downloader(FakeResolver(dashInfo())).download(TRACK_ID, tmp.newFolder("full")).sizeBytes

        val fits = downloader(FakeResolver(dashInfo())).download(TRACK_ID, into, maxBytes = full)
        assertEquals(full, fits.sizeBytes)

        val tight = tmp.newFolder("tight")
        val error = expectTooLarge { downloader(FakeResolver(dashInfo())).download(TRACK_ID, tight, full - 1) }
        assertEquals(full, error.sizeBytes)
        assertFalse(File(tight, "manifest.mpd").exists())
    }
}
