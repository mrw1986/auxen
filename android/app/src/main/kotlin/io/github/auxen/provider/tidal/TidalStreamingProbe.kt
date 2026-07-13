package io.github.auxen.provider.tidal

/**
 * One resolved outcome from the go/no-go streaming spike (Tidal
 * official-API migration, Task 1) -- the decisive signal for the whole
 * migration decision. `manifestPresentation`/`filePresentation` "FULL"
 * means the official API served the complete track for that path;
 * "PREVIEW" (with a `previewReason`) means it didn't. [playbackConfirmed]
 * is the OPTIONAL further step: null until a throwaway Media3 play is
 * attempted against whichever path came back FULL.
 */
data class TidalStreamingProbeResult(
    val trackId: String,
    val manifestPresentation: String? = null,
    val manifestPreviewReason: String? = null,
    val manifestFormats: List<String> = emptyList(),
    val manifestUri: String? = null,
    /** Set instead of the fields above when the `/trackManifests/{id}` request itself failed. */
    val manifestError: String? = null,
    val filePresentation: String? = null,
    val fileFormat: String? = null,
    val fileUrl: String? = null,
    /** Set instead of the fields above when the `/trackFiles/{id}` request itself failed -- expected under THIRD_PARTY tier, see [formatTidalStreamingProbeResult]. */
    val fileError: String? = null,
    val playbackConfirmed: Boolean? = null,
)

/**
 * Runs both halves of the spike against a real, logged-in [TidalOfficialClient]
 * for [trackId]: `/trackManifests/{id}` (THIRD_PARTY tier -- the one likely
 * to actually work for this app's registration) and `/trackFiles/{id}`
 * (PARTNER tier -- likely to 403 regardless of subscription; see
 * `TidalOfficialEndpoints.trackFileRequest`'s KDoc). Not unit tested itself
 * (two live network calls); [formatTidalStreamingProbeResult] is.
 */
suspend fun probeStreaming(api: TidalOfficialClient, accessToken: String, trackId: String): TidalStreamingProbeResult {
    val formats = listOf("FLAC", "FLAC_HIRES")
    val manifest = api.getTrackManifest(trackId, accessToken, formats)
    val file = api.getTrackFile(trackId, accessToken, formats)
    return TidalStreamingProbeResult(
        trackId = trackId,
        manifestPresentation = manifest.getOrNull()?.data?.attributes?.trackPresentation,
        manifestPreviewReason = manifest.getOrNull()?.data?.attributes?.previewReason,
        manifestFormats = manifest.getOrNull()?.data?.attributes?.formats.orEmpty(),
        manifestUri = manifest.getOrNull()?.data?.attributes?.uri,
        manifestError = manifest.exceptionOrNull()?.message,
        filePresentation = file.getOrNull()?.data?.attributes?.trackPresentation,
        fileFormat = file.getOrNull()?.data?.attributes?.format,
        fileUrl = file.getOrNull()?.data?.attributes?.url,
        fileError = file.exceptionOrNull()?.message,
    )
}

/**
 * The visible status readout (Settings "Try official Tidal login (beta)"
 * card) and the `AuxenTidalOfficial` diagnostic log line -- so the user can
 * report FULL vs PREVIEW on-device, the thing this whole spike exists to
 * answer. Pure and fully deterministic given a [result].
 */
fun formatTidalStreamingProbeResult(result: TidalStreamingProbeResult): String = buildString {
    appendLine("Tidal official-API streaming probe — track ${result.trackId}")
    append("Manifest (trackManifests, THIRD_PARTY tier): ")
    appendLine(
        when {
            result.manifestError != null -> "ERROR — ${result.manifestError}"
            result.manifestPresentation == "FULL" ->
                "FULL — ${result.manifestFormats.joinToString().ifEmpty { "format unknown" }}"
            result.manifestPresentation == "PREVIEW" ->
                "PREVIEW — ${result.manifestPreviewReason ?: "no reason given"}"
            else -> "no response"
        },
    )
    append("File (trackFiles, PARTNER tier): ")
    appendLine(
        when {
            result.fileError != null -> "ERROR — ${result.fileError} (expected: this endpoint requires PARTNER access tier)"
            result.filePresentation == "FULL" -> "FULL — ${result.fileFormat ?: "format unknown"}"
            result.filePresentation == "PREVIEW" -> "PREVIEW"
            else -> "no response"
        },
    )
    append("Playback: ")
    appendLine(
        when (result.playbackConfirmed) {
            true -> "CONFIRMED — audio played through a throwaway Media3 player"
            false -> "FAILED — see Logcat for the Media3 error"
            null -> "not attempted"
        },
    )
}
