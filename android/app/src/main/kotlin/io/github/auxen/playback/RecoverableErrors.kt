package io.github.auxen.playback

/**
 * True when [error]'s cause chain contains a [TidalDashStreamException] — the
 * signal a Tidal track resolved to a DASH manifest instead of a direct stream.
 *
 * [PlaybackService.onPlayerError] AUTO-RECOVERS this by swapping in the resolved
 * DASH media item and re-preparing, so the track goes on to play. The same
 * [androidx.media3.common.PlaybackException] also reaches the UI's controller
 * listener, which must NOT surface it as a failure — otherwise a Hi-Res track
 * that plays fine still flashes a scary "ERROR_CODE_IO_UNSPECIFIED" banner during
 * the swap. Pure and framework-free so it is unit-testable; walks `cause` links
 * (the DASH exception is nested under Media3's ExoPlaybackException/source error).
 *
 * A GENUINE DASH-playback failure (e.g. the swapped MPD source itself 401s) throws
 * a DIFFERENT exception, not [TidalDashStreamException], so real errors still
 * surface — only the recoverable resolve-time signal is filtered here.
 */
fun isRecoverableDashError(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.any { it is TidalDashStreamException }
