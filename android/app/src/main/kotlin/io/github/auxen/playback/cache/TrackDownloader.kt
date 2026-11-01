package io.github.auxen.playback.cache

import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.transform.TransformerException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The single file a progressive (BTS) track is cached as. */
const val PROGRESSIVE_FILE = "audio"

private const val CHUNK_BYTES = 64 * 1024

/** Statuses a CDN answers once a signed URL has expired. */
private val EXPIRED_CODES = setOf(401, 403, 410)

/**
 * Downloads one Tidal track whole into a staging folder: a progressive stream as one file, a
 * DASH stream as its init, every media segment and a manifest that plays them from disk.
 *
 * [resolve] maps a track id to its stream; `fresh = true` asks for newly signed URLs. A signed
 * URL can expire mid-track, so the first 401/403/410 re-resolves once and carries on.
 */
class TrackDownloader(
    private val http: OkHttpClient,
    private val resolve: suspend (trackId: String, fresh: Boolean) -> StreamInfo,
) {

    /**
     * Fills [into] (an existing, empty folder) with the track.
     *
     * @throws IOException on any network, HTTP or manifest failure. The messages this class
     *   writes name the part ("segment 5", "init", "audio"), never its URL. Whatever [resolve]
     *   throws passes through as is.
     */
    suspend fun download(trackId: String, into: File): DownloadResult = withContext(Dispatchers.IO) {
        var plan = plan(resolve(trackId, false))
        var refreshed = false
        var contentType: String? = null
        var next = 0
        while (next < plan.parts.size) {
            ensureActive()
            try {
                contentType = fetch(plan.parts[next], into)
                next++
            } catch (e: HttpStatusException) {
                if (e.code !in EXPIRED_CODES || refreshed) throw e
                refreshed = true
                // Resume at the part that failed. Everything before it is already on disk.
                plan = refreshedPlan(trackId, plan)
            }
        }
        result(plan, into, contentType)
    }

    private suspend fun refreshedPlan(trackId: String, stale: Plan): Plan {
        val fresh = plan(resolve(trackId, true))
        // Segments from two encodes can't be spliced, so the fresh stream must be the same one.
        val sameParts = fresh.parts.map { it.file } == stale.parts.map { it.file }
        val sameEncode = stale.manifest == null ||
            (fresh.info.sampleRateHz == stale.info.sampleRateHz && fresh.info.bitDepth == stale.info.bitDepth)
        if (!sameParts || !sameEncode) throw IOException("The refreshed stream doesn't match the first one")
        return fresh
    }

    /** GETs [part] into its file and returns the response's media type. */
    private suspend fun fetch(part: Part, into: File): String? {
        val url = part.url.toHttpUrlOrNull() ?: throw IOException("Bad URL for ${part.label}")
        val call = http.newCall(Request.Builder().url(url).build())
        return runCall(call) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, part.label)
            val body = response.body ?: throw IOException("No body for ${part.label}")
            // Truncates, so a retried part starts again from byte 0.
            val written = File(into, part.file).outputStream().use { copyChunks(body.byteStream(), it) }
            // OkHttp checks the length only on HTTP/1.1. An HTTP/2 stream that ends early reads as
            // a clean EOF, and a cut-short part would otherwise be cached as a whole song.
            if (written == 0L) throw IOException("Empty body for ${part.label}")
            val expected = body.contentLength()
            if (expected >= 0 && written != expected) throw IOException("Short body for ${part.label}")
            body.contentType()?.let { "${it.type}/${it.subtype}" }
        }
    }

    /**
     * Runs [call] and hands its response to [block]. Cancelling the coroutine cancels the call,
     * which also unblocks a read stalled on the socket.
     */
    private suspend fun <T> runCall(call: Call, block: suspend (Response) -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // Also reached once the call is over, whether it worked or failed. Only a cancel
                // that lands while the call is still running should cancel it.
                if (!finished.get()) call.cancel()
            }
        }
        try {
            call.execute().use { block(it) }
        } catch (e: IOException) {
            // OkHttp reports a cancelled call as IOException("Canceled"). Surface the cancel itself.
            ensureActive()
            throw e
        } finally {
            finished.set(true)
            canceller.cancel()
        }
    }

    /** Copies [input] to [output] and returns the byte count. */
    private suspend fun copyChunks(input: InputStream, output: OutputStream): Long {
        val chunk = ByteArray(CHUNK_BYTES)
        var written = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(chunk)
            if (read < 0) return written
            output.write(chunk, 0, read)
            written += read
        }
    }
}

/** One file of a download: [label] names it in errors, since [url] carries a token. */
private class Part(val label: String, val file: String, val url: String)

/**
 * What to fetch for one resolved stream. [manifest] is the DASH manifest rewritten to play from
 * disk, null for progressive.
 */
private class Plan(val info: StreamInfo, val manifest: String?, val parts: List<Part>)

private class HttpStatusException(val code: Int, label: String) : IOException("HTTP $code for $label")

private fun plan(info: StreamInfo): Plan {
    if (!info.uri.startsWith("data:")) {
        return Plan(info, null, listOf(Part("audio", PROGRESSIVE_FILE, info.uri)))
    }
    val mpd = decodeDataUriMpd(info.uri)
    val segments = asIoFailure { parseSegmentList(mpd) }
    // Localized now, so an MPD that can't be mirrored fails before anything downloads.
    val manifest = localManifest(mpd)
    val media = segments.media.map { (n, url) -> Part("segment $n", segmentFileName(n), url) }
    return Plan(info, manifest, listOf(Part("init", INIT_FILE, segments.initUrl)) + media)
}

private fun localManifest(mpd: String): String =
    try {
        asIoFailure { localizeMpd(mpd) }
    } catch (e: TransformerException) {
        // No cause: a transformer message can quote the document.
        throw IOException("Couldn't write the local manifest")
    }

private fun result(plan: Plan, into: File, contentType: String?): DownloadResult {
    val manifest = plan.manifest
    if (manifest == null) {
        val size = File(into, PROGRESSIVE_FILE).length()
        val mimeType = plan.info.mimeType ?: contentType
        return DownloadResult(CacheKind.PROGRESSIVE, PROGRESSIVE_FILE, size, mimeType, plan.info)
    }
    File(into, MANIFEST_FILE).writeText(manifest)
    val size = (plan.parts.map { it.file } + MANIFEST_FILE).sumOf { File(into, it).length() }
    return DownloadResult(CacheKind.DASH, MANIFEST_FILE, size, plan.info.mimeType, plan.info)
}

// The DASH parser throws IllegalArgumentException with URL-free messages. Callers handle one failure type.
private inline fun <T> asIoFailure(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    }
