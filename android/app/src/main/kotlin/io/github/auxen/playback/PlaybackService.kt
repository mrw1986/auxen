package io.github.auxen.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.auxen.Graph
import io.github.auxen.R
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.BalanceProcessor
import io.github.auxen.dsp.BassBoostProcessor
import io.github.auxen.dsp.EncodingRestorerProcessor
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.LimiterProcessor
import io.github.auxen.dsp.ParametricEqProcessor
import io.github.auxen.dsp.ReplayGainProcessor
import io.github.auxen.dsp.ReverbState
import io.github.auxen.dsp.VirtualizerState
import io.github.auxen.model.Track
import io.github.auxen.provider.StreamInfo
import io.github.auxen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground media playback service.
 *
 * Media3's [MediaSessionService] gives us everything MPRIS gave the desktop
 * app and more: lockscreen/notification controls, Bluetooth AVRCP, output
 * switching, and (later) Android Auto — all driven by the one MediaSession.
 *
 * The audiophile part is in [EqRenderersFactory]: the full six-stage DSP
 * chain is installed directly into the player's audio sink, so it runs
 * before the audio ever leaves the app. Chain order (`ReplayGain ->
 * ParametricEq -> BassBoost -> Balance -> Limiter -> EncodingRestorer`) is
 * LAW — see [ParametricEqProcessor]'s KDoc for why. The sink still requests
 * float output (so Hi-Res sources aren't truncated at the AudioTrack), and
 * every stage but the last runs unclamped float — chain-level headroom, not
 * per-processor clamping. [EncodingRestorerProcessor] alone converts back to
 * 16-bit at the tail, because DefaultAudioSink's built-in trailing
 * processors are 16-bit-only and float mid-chain would break sink
 * configuration.
 *
 * Each effect stage (bass boost, balance, limiter, ReplayGain) is
 * individually toggleable — [AudioFxController] owns each effect's state
 * independently and this service `attachX`es a processor to each, so an
 * enable/disable flip from the UI reaches the live audio chain without a
 * service restart. [RgGainRouter] pushes ReplayGain's per-track tag values
 * into its processor on every media-item transition.
 *
 * Reverb and virtualizer are different: platform effects
 * (`android.media.audiofx`), not in-process [AudioProcessor]s, so they
 * attach to the sink's real audio session rather than the chain above. See
 * `rebuildSessionEffects` (DSP-b Task 1).
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = MainScope()
    private var queueSaveJob: Job? = null

    /** Last expiry-recovery attempt (elapsedRealtime), bounding the 4xx retry path. */
    private var lastExpiryRecoveryAtMillis = 0L

    /** MediaId awaiting its first actual playback before a play is recorded. */
    private var pendingPlayMediaId: String? = null

    private var preResolveJob: Job? = null

    // Platform effects (android.media.audiofx), applied to the sink's real
    // audio session -- NOT part of the in-process DSP chain the six
    // processors above belong to (see ParametricEqProcessor's KDoc). Torn
    // down and rebuilt whenever the session id changes; see
    // rebuildSessionEffects.
    private var reverb: PresetReverb? = null
    private var virtualizer: Virtualizer? = null

    override fun onCreate() {
        super.onCreate()

        val eqProcessor = ParametricEqProcessor()
        EqController.attachProcessor(eqProcessor)

        val replayGainProcessor = ReplayGainProcessor()
        val bassBoostProcessor = BassBoostProcessor()
        val balanceProcessor = BalanceProcessor()
        val limiterProcessor = LimiterProcessor()
        AudioFxController.attachReplayGain { state -> replayGainProcessor.updateState(state) }
        AudioFxController.attachBassBoost { state -> bassBoostProcessor.updateState(state) }
        AudioFxController.attachBalance { state -> balanceProcessor.updateState(state) }
        AudioFxController.attachLimiter { state -> limiterProcessor.updateState(state) }
        // Platform effects (below the in-process chain): attach here so a
        // settings change reaches whatever reverb/virtualizer instance
        // currently exists, null-safely no-op'ing before the first one is
        // built by rebuildSessionEffects (right after the player itself).
        AudioFxController.attachReverb { state -> applyReverb(state) }
        AudioFxController.attachVirtualizer { state -> applyVirtualizer(state) }
        val rgGainRouter = RgGainRouter(serviceScope, replayGainProcessor)

        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            TidalUriResolver(Graph.resolver),
        )

        val renderersFactory = EqRenderersFactory(
            this,
            replayGainProcessor,
            eqProcessor,
            bassBoostProcessor,
            balanceProcessor,
            limiterProcessor,
        )
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(AuxenLoadErrorPolicy()),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // ExoPlayer assigns its audio session id synchronously in its own
        // constructor (verified against the real 1.5.1 source:
        // ExoPlayerImpl's constructor calls Util.generateAudioSessionIdV21
        // and pushes it to the renderer directly, with NO Player.Listener
        // dispatch at that point -- onAudioSessionIdChanged only fires from
        // the explicit setAudioSessionId(int) setter, which this app never
        // calls). So the id is already valid right here and platform
        // effects can be built immediately, rather than waiting on a
        // listener callback that would never fire in this app's flow. The
        // onAudioSessionIdChanged handler below still covers any LATER
        // legitimate change.
        rebuildSessionEffects(player.audioSessionId)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                pendingPlayMediaId = mediaItem?.mediaId
                // Records immediately for mid-playback transitions (isPlaying
                // still true); defers to onIsPlayingChanged for cold starts and
                // paused queue edits, so restores never count as plays.
                maybeRecordPendingPlay(player)
                scheduleQueueSave(player)
                preResolveUpcoming(player)
                rgGainRouter.route(mediaItem?.mediaId, player.playWhenReady)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleQueueSave(player)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                rebuildSessionEffects(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    maybeRecordPendingPlay(player)
                    // Re-route: a Tidal track's RG resolve may have been
                    // skipped in onMediaItemTransition while the queue was
                    // still paused (see RgGainRouter's "Lazy resolution"
                    // KDoc) -- this is the belt half of the belt-and-
                    // suspenders pair with onPlayWhenReadyChanged below.
                    rgGainRouter.route(player.currentMediaItem?.mediaId, player.playWhenReady)
                } else {
                    scheduleQueueSave(player)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    preResolveUpcoming(player)
                    rgGainRouter.route(player.currentMediaItem?.mediaId, playWhenReady)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentPlayer = mediaSession?.player ?: return

                val dash = findCause<TidalDashStreamException>(error)
                if (dash != null) {
                    if (!swapDashCopies(currentPlayer, dash.trackId, dash.streamInfo)) return
                    currentPlayer.prepare()
                    currentPlayer.play()
                    return
                }

                val http = findCause<HttpDataSource.InvalidResponseCodeException>(error)
                val expired = http != null && http.responseCode in intArrayOf(401, 403, 410)
                if (expired) {
                    val anyTidal = (0 until currentPlayer.mediaItemCount)
                        .any { currentPlayer.getMediaItemAt(it).mediaId.startsWith("TIDAL:") }
                    if (!anyTidal) return
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastExpiryRecoveryAtMillis < RETRY_COOLDOWN_MILLIS) return
                    lastExpiryRecoveryAtMillis = now
                    // The expired URL may belong to any queued Tidal item
                    // (current or pre-buffering): drop every cached stream URL
                    // and restore the stable auxen:// URI on previously
                    // DASH-swapped items so re-prepare can re-resolve them.
                    Graph.resolver.invalidateAll()
                    for (i in 0 until currentPlayer.mediaItemCount) {
                        val queued = currentPlayer.getMediaItemAt(i)
                        if (!queued.mediaId.startsWith("TIDAL:")) continue
                        if (queued.localConfiguration?.uri?.scheme == "auxen") continue
                        val restored = queued.buildUpon()
                            .setUri("auxen://tidal/${queued.mediaId.substringAfter(':')}")
                            .setMimeType(null)
                            .build()
                        currentPlayer.replaceMediaItem(i, restored)
                    }
                    currentPlayer.prepare()
                    currentPlayer.play()
                }
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(AuxenSessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_stat_auxen)
            },
        )

        // Restore the persisted queue paused: the user decides when to hit
        // play, and no Tidal stream resolution happens until they do.
        mainScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { Graph.queueStore.load() }.getOrNull() }
                ?: return@launch
            if (player.mediaItemCount > 0) return@launch // a controller beat us to it
            player.setMediaItems(saved.tracks.map(Graph::mediaItemFor), saved.index, saved.positionMs)
            // No prepare(): stream resolution stays lazy until the user plays.
        }
    }

    /** Snapshot the queue's Tracks from each item's metadata extras. */
    private fun currentTracks(player: Player): List<Track> =
        (0 until player.mediaItemCount).mapNotNull { i ->
            player.getMediaItemAt(i).mediaMetadata.extras
                ?.getString(Graph.TRACK_EXTRA_KEY)
                ?.let { encoded -> runCatching { Graph.json.decodeFromString<Track>(encoded) }.getOrNull() }
        }

    /** Debounced queue persist; snapshots on the main thread, writes on IO. */
    private fun scheduleQueueSave(player: Player) {
        val tracks = currentTracks(player)
        val index = player.currentMediaItemIndex
        val positionMs = player.currentPosition.coerceAtLeast(0)
        queueSaveJob?.cancel()
        queueSaveJob = serviceScope.launch {
            delay(500)
            runCatching { Graph.queueStore.save(tracks, index, positionMs) }
        }
    }

    /** Record the pending item's play once the player is actually playing. */
    private fun maybeRecordPendingPlay(player: Player) {
        val mediaId = pendingPlayMediaId ?: return
        if (!player.isPlaying) return
        pendingPlayMediaId = null
        serviceScope.launch { runCatching { Graph.library.recordPlay(mediaId) } }
    }

    /**
     * Tears down and rebuilds [reverb]/[virtualizer] for [sessionId]. Called
     * once right after the player is built (its session id is already valid
     * at that point -- see [onCreate]'s comment) and again from
     * [Player.Listener.onAudioSessionIdChanged] for any later legitimate
     * change.
     *
     * `runCatching` around construction: emulators and some devices lack
     * effect implementations entirely (`PresetReverb`/`Virtualizer`'s
     * constructors can throw), and an effect is never allowed to break
     * playback -- a `null` instance just means [applyReverb]/[applyVirtualizer]
     * no-op until the next successful rebuild (DSP-b Task 1).
     */
    private fun rebuildSessionEffects(sessionId: Int) {
        runCatching { reverb?.release() }
        runCatching { virtualizer?.release() }
        reverb = null
        virtualizer = null
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        reverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        applyReverb(AudioFxController.reverbState.value)
        applyVirtualizer(AudioFxController.virtualizerState.value)
    }

    /** Null-safe: no-ops if [reverb] hasn't been built yet (or failed to build). */
    private fun applyReverb(state: ReverbState) {
        val r = reverb ?: return
        runCatching {
            r.enabled = state.enabled
            r.preset = state.preset.toShort()
        }
    }

    /** Null-safe: no-ops if [virtualizer] hasn't been built yet (or failed to build). */
    private fun applyVirtualizer(state: VirtualizerState) {
        val v = virtualizer ?: return
        runCatching {
            v.enabled = state.enabled
            v.setStrength(state.strength.toShort())
        }
    }

    /**
     * Swap every still-unresolved (auxen-scheme) copy of the given Tidal track
     * for its resolved DASH manifest. Returns true if anything was swapped.
     */
    private fun swapDashCopies(player: Player, trackId: String, streamInfo: StreamInfo): Boolean {
        val targetMediaId = "TIDAL:$trackId"
        var swapped = false
        for (i in 0 until player.mediaItemCount) {
            val queued = player.getMediaItemAt(i)
            if (queued.mediaId != targetMediaId) continue
            if (queued.localConfiguration?.uri?.scheme != "auxen") continue
            player.replaceMediaItem(
                i,
                queued.buildUpon()
                    .setUri(streamInfo.uri)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build(),
            )
            swapped = true
        }
        return swapped
    }

    /**
     * Resolve the current and next Tidal items ahead of playback and swap
     * DASH copies proactively, so Hi-Res tracks play without bouncing
     * through onPlayerError. Only runs when playback is intended
     * (playWhenReady) — a paused restored queue stays unresolved.
     */
    private fun preResolveUpcoming(player: Player) {
        if (!player.playWhenReady) return
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        val ids = listOf(current, current + 1)
            .filter { it < player.mediaItemCount }
            .mapNotNull { i ->
                val uri = player.getMediaItemAt(i).localConfiguration?.uri
                if (uri?.scheme == "auxen") uri.lastPathSegment else null
            }
        if (ids.isEmpty()) return
        preResolveJob?.cancel()
        preResolveJob = serviceScope.launch {
            for (id in ids) {
                val info = runCatching { Graph.resolver.resolve(id) }.getOrNull() ?: continue
                if (!info.uri.startsWith("data:")) continue
                withContext(Dispatchers.Main) {
                    mediaSession?.player?.let { p -> swapDashCopies(p, id, info) }
                }
            }
        }
    }

    private inner class AuxenSessionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaItemsWithStartPosition>()
            serviceScope.launch {
                val saved = runCatching { Graph.queueStore.load() }.getOrNull()
                if (saved == null) {
                    future.setException(UnsupportedOperationException("No saved queue"))
                } else {
                    future.set(
                        MediaItemsWithStartPosition(
                            saved.tracks.map(Graph::mediaItemFor),
                            saved.index,
                            saved.positionMs,
                        ),
                    )
                }
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Flush a final save while the player is still alive (main thread),
        // then tear the main scope down before releasing the player.
        mediaSession?.player?.let { p ->
            val tracks = currentTracks(p)
            val index = p.currentMediaItemIndex
            val positionMs = p.currentPosition.coerceAtLeast(0)
            queueSaveJob?.cancel()
            runBlocking {
                runCatching { Graph.queueStore.save(tracks, index, positionMs) }
            }
        }
        mainScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        runCatching { reverb?.release() }
        runCatching { virtualizer?.release() }
        reverb = null
        virtualizer = null
        preResolveJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

private const val RETRY_COOLDOWN_MILLIS = 60_000L

private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is T) return cause
        cause = cause.cause
    }
    return null
}

/** Skips load retries for errors that can only be fixed by a media-item swap. */
@UnstableApi
private class AuxenLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (findCause<TidalDashStreamException>(loadErrorInfo.exception) != null) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/**
 * Builds the six-stage DSP processor array in chain order. Extracted out of
 * [EqRenderersFactory.buildAudioSink] so a production regression test
 * ([io.github.auxen.playback.ProcessorChainOrderTest]) can call the exact
 * function the real audio sink uses, instead of asserting against a
 * hand-rolled list that could silently drift out of sync if this order ever
 * changed here without the test noticing (fix round, review of commit
 * dd1bd55, Important #2). Order is LAW -- see [ParametricEqProcessor]'s KDoc.
 */
@UnstableApi
internal fun buildDspProcessorChain(
    replayGainProcessor: ReplayGainProcessor,
    eqProcessor: ParametricEqProcessor,
    bassBoostProcessor: BassBoostProcessor,
    balanceProcessor: BalanceProcessor,
    limiterProcessor: LimiterProcessor,
    encodingRestorerProcessor: EncodingRestorerProcessor,
): Array<AudioProcessor> = arrayOf(
    replayGainProcessor,
    eqProcessor,
    bassBoostProcessor,
    balanceProcessor,
    limiterProcessor,
    encodingRestorerProcessor,
)

/** Renderers factory that injects the full DSP chain into a float-output audio sink. */
@UnstableApi
private class EqRenderersFactory(
    context: Context,
    private val replayGainProcessor: ReplayGainProcessor,
    private val eqProcessor: ParametricEqProcessor,
    private val bassBoostProcessor: BassBoostProcessor,
    private val balanceProcessor: BalanceProcessor,
    private val limiterProcessor: LimiterProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // Request float output so Hi-Res sources aren't truncated to 16-bit at
        // the AudioTrack; devices without float support fall back automatically.
        // Every processor but the last runs unclamped float (chain headroom);
        // EncodingRestorerProcessor converts back to 16-bit last, since
        // DefaultAudioSink's built-in trailing processors are 16-bit-only.
        // Chain order is LAW (see ParametricEqProcessor's KDoc) -- ReplayGain
        // first so a quiet track's boost has the same downstream headroom as
        // everything else, Limiter last-but-one so it's the one stage
        // allowed to clamp, catching whatever upstream boosts produced.
        .setEnableFloatOutput(true)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(
            buildDspProcessorChain(
                replayGainProcessor,
                eqProcessor,
                bassBoostProcessor,
                balanceProcessor,
                limiterProcessor,
                EncodingRestorerProcessor(),
            ),
        )
        .build()
}

/**
 * Pushes ReplayGain tag values for the current track into [processor] on
 * every media-item transition.
 *
 * LOCAL tracks: reads tags directly via [io.github.auxen.provider.local.LocalProvider.replayGainFor]
 * (no stream resolution needed — the tags live in the file itself). TIDAL
 * tracks: reuses [TrackResolver]'s cache via [Graph.resolver] — playback
 * just resolved this id moments ago (either this transition's own prepare,
 * or [PlaybackService.preResolveUpcoming]), so this is a cache hit in the
 * common case, not an extra network round trip. Either branch is wrapped in
 * `runCatching`; any failure or absent tag falls through to
 * `setTrackGains(null, null)`, which [ReplayGainProcessor] treats as "use
 * the state's fallbackDb" — never a crash, never stale gains left over from
 * the previous track.
 *
 * ### Cancellation, not just failure
 * `runCatching` also catches `CancellationException` like any other
 * `Throwable` -- without the `isActive` check below, a job cancelled
 * mid-suspend (a fast skip past a slow-resolving Tidal track, say) would
 * "complete" with the failure `Result` anyway and race the replacement
 * job's `setTrackGains` call with no ordering guarantee, silently resetting
 * the still-playing track to fallback loudness (fix round, review of commit
 * dd1bd55, Important #1). Checking `isActive` immediately after
 * `runCatching` -- before applying its result -- closes that window:
 * cancellation is tracked on the coroutine's `Job` independently of the
 * caught exception, so this check still reflects the real cancellation
 * state even though the exception itself was swallowed.
 *
 * ### Lazy resolution
 * TIDAL resolution triggers a real network call (or, in the common case, a
 * [TrackResolver] cache hit -- still not free). [PlaybackService.onCreate]'s
 * queue-restore comment already establishes the contract: "no Tidal stream
 * resolution happens until the user plays." [route] previously violated that
 * for a paused, cold-start-restored Tidal queue -- `onMediaItemTransition`
 * fires from `setMediaItems` alone, with no `prepare()`/`play()` involved.
 * The `playWhenReady` parameter gates the TIDAL branch specifically: LOCAL
 * tag reads are a cheap local file read with no such contract, so they
 * always proceed. [PlaybackService.onCreate]'s `onPlayWhenReadyChanged`/
 * `onIsPlayingChanged(true)` handlers re-call [route] once play is actually
 * intended, so gains still land at or before first audio for a Tidal track
 * that was skipped here (final-review fix round, Important #4).
 */
@UnstableApi
private class RgGainRouter(
    private val scope: CoroutineScope,
    private val processor: ReplayGainProcessor,
) {
    private var job: Job? = null

    fun route(mediaId: String?, playWhenReady: Boolean) {
        job?.cancel()
        val sourceId = mediaId?.substringAfter(':', missingDelimiterValue = "")
        if (mediaId == null || sourceId.isNullOrEmpty()) {
            processor.setTrackGains(null, null)
            return
        }
        if (mediaId.startsWith("TIDAL:") && !playWhenReady) {
            // Skip the resolve entirely -- see "Lazy resolution" above. Once
            // playback is actually intended, onPlayWhenReadyChanged/
            // onIsPlayingChanged(true) re-call route() with playWhenReady=true.
            return
        }
        job = scope.launch {
            val result = runCatching {
                when {
                    mediaId.startsWith("LOCAL:") -> {
                        val info = Graph.local.replayGainFor(sourceId)
                        info?.trackGainDb to info?.albumGainDb
                    }
                    mediaId.startsWith("TIDAL:") -> {
                        val info = Graph.resolver.resolve(sourceId)
                        info.trackGainDb to info.albumGainDb
                    }
                    else -> null to null
                }
            }
            if (!isActive) return@launch
            val (trackGainDb, albumGainDb) = result.getOrDefault(null to null)
            processor.setTrackGains(trackGainDb, albumGainDb)
        }
    }
}
