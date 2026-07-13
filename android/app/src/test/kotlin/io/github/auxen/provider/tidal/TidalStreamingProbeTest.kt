package io.github.auxen.provider.tidal

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [formatTidalStreamingProbeResult] is the pure formatter behind the go/
 * no-go spike's visible status readout + `AuxenFx`-style diagnostic log
 * (Tidal official-API migration, Task 1) -- same "separate the pure
 * formatting function from the untestable device/network call" pattern
 * already used for `formatFxDiagnosticLog` (the reverb/virtualizer fix).
 */
class TidalStreamingProbeTest {

    @Test
    fun `formats a FULL manifest as the clear go signal`() {
        val result = TidalStreamingProbeResult(
            trackId = "75413016",
            manifestPresentation = "FULL",
            manifestFormats = listOf("FLAC_HIRES"),
            manifestUri = "https://example.tidal.com/manifest.mpd",
        )
        val text = formatTidalStreamingProbeResult(result)
        assertTrue(text.contains("75413016"))
        assertTrue(text.contains("FULL"))
        assertTrue(text.contains("FLAC_HIRES"))
    }

    @Test
    fun `formats a PREVIEW manifest with its reason, not a bare FULL-or-not verdict`() {
        val result = TidalStreamingProbeResult(
            trackId = "75413016",
            manifestPresentation = "PREVIEW",
            manifestPreviewReason = "FULL_REQUIRES_HIGHER_ACCESS_TIER",
        )
        val text = formatTidalStreamingProbeResult(result)
        assertTrue(text.contains("PREVIEW"))
        assertTrue(text.contains("FULL_REQUIRES_HIGHER_ACCESS_TIER"))
    }

    @Test
    fun `formats a trackFiles 403 distinctly from a genuine failure -- expected under THIRD_PARTY tier`() {
        val result = TidalStreamingProbeResult(
            trackId = "75413016",
            manifestPresentation = "FULL",
            fileError = "CLIENT_NOT_ENTITLED: Cannot fulfill this request because required prerequisites are missing",
        )
        val text = formatTidalStreamingProbeResult(result)
        assertTrue(text.contains("CLIENT_NOT_ENTITLED"))
        assertTrue("a trackFiles 403 should read as expected-under-this-tier, not an unexplained bug", text.contains("PARTNER"))
    }

    @Test
    fun `formats playbackConfirmed states distinctly -- null, true, and false must not read the same`() {
        val base = TidalStreamingProbeResult(trackId = "1", manifestPresentation = "FULL")
        val notAttempted = formatTidalStreamingProbeResult(base.copy(playbackConfirmed = null))
        val confirmed = formatTidalStreamingProbeResult(base.copy(playbackConfirmed = true))
        val failed = formatTidalStreamingProbeResult(base.copy(playbackConfirmed = false))
        assertTrue(notAttempted != confirmed)
        assertTrue(confirmed != failed)
        assertTrue(notAttempted != failed)
    }
}
