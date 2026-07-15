package io.github.auxen.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
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
import io.github.auxen.dsp.AutoEqController
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
import kotlinx.coroutines.flow.collectLatest
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
 * The audiophile part is in [EqRenderersFactory]: the full seven-stage DSP
 * chain is installed directly into the player's audio sink, so it runs
 * before the audio ever leaves the app. Chain order (`ReplayGain -> AutoEq
 * -> ParametricEq (graphic) -> BassBoost -> Balance -> Limiter ->
 * EncodingRestorer`) is LAW — see [ParametricEqProcessor]'s KDoc for why.
 * AutoEq (headphone correction) and the graphic EQ are independent
 * [ParametricEqProcessor] instances, attached to [AutoEqController] and
 * [EqController] respectively (AutoEq split, Task 1). The sink still
 * requests float output (so Hi-Res sources aren't truncated at the
 * AudioTrack), and every stage but the last runs unclamped float —
 * chain-level headroom, not per-processor clamping. [EncodingRestorerProcessor]
 * alone converts back to 16-bit at the tail, because DefaultAudioSink's
 * built-in trailing processors are 16-bit-only and float mid-chain would
 * break sink configuration.
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
 *
 * [SleepTimerController] is unrelated to any of the above -- it just watches
 * a [kotlinx.coroutines.flow.StateFlow] and, at expiry, either pauses
 * immediately or (if `finishTrack`) arms `pendingSleepTimerPause`, consumed
 * at the next `onMediaItemTransition` (DSP-b Task 2).
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
    //
    // @Volatile: written from onAudioSessionIdChanged (main thread, via
    // rebuildSessionEffects), but read inside applyReverb/applyVirtualizer --
    // which AudioFxController's attachReverb/attachVirtualizer can invoke
    // from ITS OWN IO-dispatched scope during DataStore hydration
    // (FxSlot.restore(), called from initialize()), not just from the main
    // thread. A hydration-time read racing a session rebuild without this
    // could observe a torn/stale reference (final review round, Important #4).
    @Volatile
    private var reverb: PresetReverb? = null
    @Volatile
    private var virtualizer: Virtualizer? = null

    // Diagnostic snapshot for the "AuxenFx" log line (platform effects fix,
    // user-confirmed device report, 2026-07-13) -- audibility is un-CI-
    // testable, so this is the shipped on-device fallback: `adb logcat -s
    // AuxenFx` during a toggle pinpoints whichever hypothesis is still live.
    // Written alongside reverb/virtualizer (same rebuild/apply call sites),
    // read from logFxDiagnostics -- same cross-thread shape as reverb/
    // virtualizer themselves, so @Volatile for the same reason.
    @Volatile
    private var reverbSetEnabledStatus: Int? = null
    @Volatile
    private var reverbAuxRouteSet: Boolean = false
    @Volatile
    private var virtualizerSetStrengthStatus: Int? = null
    @Volatile
    private var virtualizerSetEnabledStatus: Int? = null
    @Volatile
    private var virtualizerForceModeApplied: Boolean = false

    /**
     * One-shot flag set by the sleep-timer watcher when
     * [SleepTimerState.finishTrack] is armed and the timer expires -- the
     * CURRENT track is allowed to finish, and the pause happens at the next
     * [onMediaItemTransition] instead of immediately. Consumed (reset to
     * `false`) the moment it's acted on, hence "one-shot".
     *
     * If the armed track is the LAST one in a non-repeating queue, playback
     * reaches [Player.STATE_ENDED] instead of firing another
     * [onMediaItemTransition] -- with nothing to consume the flag, it would
     * otherwise stay stuck `true` on this still-live service and silently
     * pause the user's NEXT, unrelated queue on its first transition. Two
     * consumers guard against that: `onPlaybackStateChanged(STATE_ENDED)`
     * and the `onTimelineChanged` playlist-replaced hook (fix round, review
     * of commit 60c7699, finding 1).
     *
     * `@Volatile`: the first genuinely cross-thread field in this class --
     * written from the watcher coroutine (`Dispatchers.IO`, via
     * `serviceScope`), read from `Player.Listener` callbacks (the main
     * thread) -- matching the `@Volatile` convention already enforced
     * everywhere else across the DSP work for exactly this IO-write/Main-read
     * shape (finding 2).
     */
    @Volatile
    private var pendingSleepTimerPause = false

    override fun onCreate() {
        super.onCreate()

        val eqProcessor = ParametricEqProcessor()
        EqController.attachProcessor(eqProcessor)

        // Headphone correction (AutoEq) is its own stage now, independent of
        // the graphic EQ above -- a second ParametricEqProcessor instance,
        // attached to AutoEqController instead (AutoEq split, Task 1).
        val autoEqProcessor = ParametricEqProcessor()
        AutoEqController.attachProcessor(autoEqProcessor)

        val replayGainProcessor = ReplayGainProcessor()
        val bassBoostProcessor = BassBoostProcessor()
        val balanceProcessor = BalanceProcessor()
        val limiterProcessor = LimiterProcessor()
        AudioFxController.attachReplayGain { state -> replayGainProcessor.updateState(state) }
        AudioFxController.attachBassBoost { state -> bassBoostProcessor.updateState(state) }
        AudioFxController.attachBalance { state -> balanceProcessor.updateState(state) }
        AudioFxController.attachLimiter { state -> limiterProcessor.updateState(state) }
        val rgGainRouter = RgGainRouter(serviceScope, replayGainProcessor)

        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            TidalUriResolver(Graph.resolver),
        )

        val renderersFactory = EqRenderersFactory(
            this,
            replayGainProcessor,
            autoEqProcessor,
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

        // Platform effects (below the in-process chain): attach here (after
        // `player` exists, since reverb's route needs player.setAuxEffectInfo)
        // so a settings change reaches whatever reverb/virtualizer instance
        // currently exists, null-safely no-op'ing before the first one is
        // built by rebuildSessionEffects (right after this).
        AudioFxController.attachReverb { state -> applyReverb(state, player) }
        AudioFxController.attachVirtualizer { state -> applyVirtualizer(state) }

        // Sleep timer: collectLatest re-launches (cancelling any in-flight
        // delay) every time SleepTimerController's state changes, so a
        // re-arm or cancel() naturally supersedes whatever this coroutine
        // was previously waiting on -- no manual job bookkeeping needed,
        // unlike RgGainRouter (which reacts to discrete method calls, not a
        // continuously-collected Flow).
        serviceScope.launch {
            SleepTimerController.state.collectLatest { timerState ->
                val remaining = timerState.remainingMillis()
                if (remaining == null) {
                    // Unarmed OR in the pendingTrackEnd phase -- remainingMillis()
                    // returns null for both (see its KDoc), so pendingTrackEnd
                    // is the only thing distinguishing them here. Clear the
                    // flag ONLY for the true-unarmed case: a user-initiated
                    // Cancel during pendingTrackEnd calls
                    // SleepTimerController.cancel(), which resets to full
                    // defaults and lands HERE -- without this, Cancel would
                    // stop the sheet from showing anything pending while the
                    // pause still silently fired at the next transition
                    // (final review round, Important #3). The pendingTrackEnd
                    // transition itself (markPendingTrackEnd(), below) ALSO
                    // re-enters this same branch on its own emission --
                    // pendingTrackEnd=true there correctly skips the clear,
                    // since the flag it would be clearing is the one this
                    // very coroutine just set two lines below.
                    if (!timerState.pendingTrackEnd) pendingSleepTimerPause = false
                    return@collectLatest
                }
                // A fresh arm -- including a re-arm while a PREVIOUS timer's
                // finishTrack pause is still pending on the current track --
                // supersedes any leftover pendingSleepTimerPause from that
                // previous timer. Without this, arming timer B while timer
                // A's finish-track pause is still waiting for the current
                // track to end would have B's countdown start while A's
                // stale flag fires early at the very next transition,
                // pausing playback the user just told to keep going (micro
                // round, review of commit a252273/85c106d, finding 2).
                pendingSleepTimerPause = false
                if (remaining > 0) delay(remaining)
                if (timerState.finishTrack) {
                    pendingSleepTimerPause = true
                    // Waits for the current track to end instead of
                    // cancelling outright, so the sheet keeps showing an
                    // active, cancelable state (final review round,
                    // Important #3) -- consumed at the next
                    // onMediaItemTransition (checkSleepTimerPause) or at
                    // STATE_ENDED, both of which call cancel() themselves.
                    SleepTimerController.markPendingTrackEnd()
                } else {
                    withContext(Dispatchers.Main) { mediaSession?.player?.pause() }
                    SleepTimerController.cancel()
                }
            }
        }

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
                checkSleepTimerPause(player)
                // Belt-and-suspenders: the AudioTrack (and therefore the
                // platform effects attached to its session) can be recreated
                // between tracks or on a route change without necessarily
                // firing onAudioSessionIdChanged -- re-apply the CURRENT
                // AudioFxController states on every transition rather than
                // relying solely on the session-id-change path (platform
                // effects fix, user-confirmed device report, 2026-07-13).
                reapplySessionEffects(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    scheduleQueueSave(player)
                    // Belt-and-suspenders against pendingSleepTimerPause
                    // surviving into an unrelated new queue -- see that
                    // field's KDoc (fix round, review of commit 60c7699,
                    // finding 1).
                    if (pendingSleepTimerPause) {
                        pendingSleepTimerPause = false
                        // The queue this pause was waiting on is gone --
                        // also disarm the controller so the sheet doesn't
                        // keep showing "Pausing after this track" for a
                        // pause that will now never happen (same belt-and-
                        // suspenders reasoning as onPlaybackStateChanged's
                        // STATE_ENDED handler below, extended to cover the
                        // pendingTrackEnd phase's own UI staleness).
                        SleepTimerController.cancel()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The queue-exhausted exit path: STATE_ENDED fires instead
                // of another onMediaItemTransition, so it's the only place
                // left to consume a flag armed on the queue's last track --
                // pausing is moot (playback already stopped on its own), but
                // the flag must still clear, and so must the controller's
                // own (already-expired) timer state, so the sheet's UI
                // doesn't keep showing a stale armed timer (fix round,
                // review of commit 60c7699, finding 1).
                if (playbackState == Player.STATE_ENDED && pendingSleepTimerPause) {
                    pendingSleepTimerPause = false
                    SleepTimerController.cancel()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                rebuildSessionEffects(audioSessionId, player)
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
                    // On-device diagnostic (platform effects fix,
                    // user-confirmed device report, 2026-07-13): playback
                    // actually starting is the moment the user would notice
                    // reverb/virtualizer being silent, so this is the second
                    // of the two required log points (`adb logcat -s
                    // AuxenFx`), alongside every session rebuild.
                    logFxDiagnostics(player.audioSessionId)
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

        // Fix round (review of commit 6426194), Critical: do NOT read
        // player.audioSessionId and build platform effects against it
        // directly -- that id is generated in ExoPlayerImpl's own
        // constructor and pushed toward the renderers via an internal
        // PlayerMessage, but delivery to the audio renderer/DefaultAudioSink
        // is NOT guaranteed to have completed by the time the constructor
        // returns (PlayerMessage.send() queues onto the internal playback
        // thread's own message loop, asynchronous to the caller). If the
        // real DefaultAudioSink never actually adopts that id before it
        // builds its AudioTrack (built lazily, once real playback starts),
        // externalAudioSessionIdProvided stays false and the sink falls
        // back to whatever session the platform's AudioTrack.create()
        // auto-generates instead -- a DIFFERENT id than the one this
        // service just attached PresetReverb/Virtualizer to. The effects
        // would construct successfully (no crash) and simply process
        // silence forever: invisible to both Robolectric (no real
        // AudioTrack) and CI smoke (crash-gating only, not audio-content
        // verification).
        //
        // Explicitly calling setAudioSessionId(UNSET) here, AFTER the
        // listener above is registered, is the fix: verified directly
        // against the real ExoPlayerImpl.setAudioSessionId(int) source --
        // the id it holds right now (the constructor-generated value) is
        // never UNSET, so the method's `if (this.audioSessionId ==
        // audioSessionId) return` guard does NOT short-circuit; it takes
        // the `audioSessionId == C.AUDIO_SESSION_ID_UNSET` branch, mints a
        // FRESH id via the same Util.generateAudioSessionIdV21 the
        // constructor used, assigns it to its own field, sends the renderer
        // message again, and -- critically, and unconditionally, as part of
        // this same synchronous call, not gated on that message's async
        // delivery -- fires onAudioSessionIdChanged(id) with that fresh,
        // non-UNSET id to every currently-registered Player.Listener. Our
        // listener (registered above) receives it and calls
        // rebuildSessionEffects with a value we now KNOW this call pushed
        // toward the sink moments ago, on the same call stack that
        // generated it -- not a value merely read from a field that may or
        // may not have made it to the sink yet.
        //
        // Do not "simplify" this back to reading player.audioSessionId
        // directly: that was the original bug.
        player.setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)

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
    private fun currentTracks(player: Player): List<Track> = Graph.tracksFrom(player)

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
     * Consumes [pendingSleepTimerPause], if armed, by pausing right here at
     * the transition boundary -- the NORMAL finishTrack completion path
     * (track ends, the next one starts, THIS pauses it immediately).
     *
     * Also disarms [SleepTimerController] itself, mirroring the
     * `onPlaybackStateChanged(STATE_ENDED)` branch below: without this, the
     * controller's `pendingTrackEnd` phase survived every successful
     * finishTrack completion via this common path -- the sheet kept
     * showing "Pausing after this track" with a dead Cancel button, and the
     * Now Playing icon stayed tinted, forever, after the pause had already
     * happened. STATE_ENDED (the rarer, queue-exhausted exit) already
     * called cancel() correctly; this far more common path did not (final
     * review round #2, the one real bug).
     */
    private fun checkSleepTimerPause(player: Player) {
        if (!pendingSleepTimerPause) return
        pendingSleepTimerPause = false
        player.pause()
        SleepTimerController.cancel()
    }

    /**
     * Tears down and rebuilds [reverb]/[virtualizer] for [sessionId]. Only
     * ever called from [Player.Listener.onAudioSessionIdChanged] -- once for
     * the `setAudioSessionId(UNSET)` call [onCreate] makes right after
     * registering that listener (see the long comment at that call site for
     * why a direct call with `player.audioSessionId` is wrong), and again
     * for any later legitimate session change.
     *
     * `runCatching` around construction: emulators and some devices lack
     * effect implementations entirely (`PresetReverb`/`Virtualizer`'s
     * constructors can throw), and an effect is never allowed to break
     * playback -- a `null` instance just means [applyReverb]/[applyVirtualizer]
     * no-op until the next successful rebuild (DSP-b Task 1).
     *
     * `PresetReverb(1, sessionId)`, not priority 0: matches the empirically-
     * proven recipe from four independent working Media3 players (RiMusic,
     * ViTune, Kreate, RiPlay) that this fix was researched against -- the
     * platform-doc-cited "session 0" alternative was tried and rejected (see
     * the plan's RECONCILIATION note; `setAudioSessionId(UNSET)` in
     * [onCreate] is what actually guarantees the sink adopts this exact
     * per-session id, independent of the reverb priority question).
     */
    private fun rebuildSessionEffects(sessionId: Int, player: ExoPlayer) {
        runCatching { player.clearAuxEffectInfo() }
        runCatching { reverb?.release() }
        runCatching { virtualizer?.release() }
        reverb = null
        virtualizer = null
        reverbSetEnabledStatus = null
        reverbAuxRouteSet = false
        virtualizerSetStrengthStatus = null
        virtualizerSetEnabledStatus = null
        virtualizerForceModeApplied = false
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        reverb = runCatching { PresetReverb(1, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        applyReverb(AudioFxController.reverbState.value, player)
        applyVirtualizer(AudioFxController.virtualizerState.value)
        logFxDiagnostics(sessionId)
    }

    /**
     * Re-runs [applyReverb]/[applyVirtualizer] for the CURRENT
     * [AudioFxController] states without tearing down/rebuilding the
     * platform effect objects themselves -- belt-and-suspenders against the
     * AudioTrack being recreated (between tracks, or on a route change)
     * without necessarily firing `onAudioSessionIdChanged` (platform
     * effects fix, user-confirmed device report, 2026-07-13). Called from
     * `onMediaItemTransition`.
     */
    private fun reapplySessionEffects(player: ExoPlayer) {
        applyReverb(AudioFxController.reverbState.value, player)
        applyVirtualizer(AudioFxController.virtualizerState.value)
    }

    /**
     * Null-safe: no-ops if [reverb] hasn't been built yet (or failed to
     * build). `PresetReverb` is an AUXILIARY (send) effect -- `enabled =
     * true` alone is inaudible; it must additionally be routed into the
     * sink via [ExoPlayer.setAuxEffectInfo] (-> `AudioTrack.attachAuxEffect`
     * + `setAuxEffectSendLevel`), which is the actual root cause this fix
     * addresses. `DefaultAudioSink` re-applies `AuxEffectInfo` across track
     * transitions on its own; [reapplySessionEffects] is the additional
     * belt-and-suspenders path for a recreated `AudioTrack`.
     *
     * Uses the `setEnabled`/method form (not the `r.enabled = ...` property
     * assignment this used to use) specifically to capture the `Int` status
     * code `AudioEffect.setEnabled` returns -- silently discarded by
     * property-assignment syntax, but the whole point of
     * [reverbSetEnabledStatus] existing for the `AuxenFx` diagnostic log.
     */
    private fun applyReverb(state: ReverbState, player: ExoPlayer) {
        val r = reverb ?: return
        runCatching {
            r.preset = clampReverbPreset(state.preset)
            reverbSetEnabledStatus = r.setEnabled(state.enabled)
            if (state.enabled) {
                player.setAuxEffectInfo(AuxEffectInfo(r.id, 1f))
                reverbAuxRouteSet = true
            } else {
                player.clearAuxEffectInfo()
                reverbAuxRouteSet = false
            }
        }
    }

    /**
     * Null-safe: no-ops if [virtualizer] hasn't been built yet (or failed to
     * build). An INSERT effect (unlike reverb) -- no aux routing needed --
     * but Android 13/14 has a known platform bug that leaves it silent
     * unless [Virtualizer.forceVirtualizationMode] is called with
     * [Virtualizer.VIRTUALIZATION_MODE_BINAURAL] roughly 50ms AFTER
     * `enabled = true` settles (sourced from the Wavelet author's
     * documented workaround) -- also what makes it audible over the
     * device speaker, since binaural virtualization is otherwise
     * headphones-only by spec. Captures the current [virtualizer] instance
     * before the delayed call, since a session rebuild could otherwise
     * swap it out from under a still-pending delay.
     */
    private fun applyVirtualizer(state: VirtualizerState) {
        val v = virtualizer ?: return
        runCatching {
            if (v.strengthSupported) {
                // Virtualizer.setStrength returns void (throws on error), unlike
                // AudioEffect.setEnabled which returns a status int -- capture
                // success/failure explicitly so the AuxenFx diagnostic still has
                // a status field for it.
                virtualizerSetStrengthStatus = runCatching {
                    v.setStrength(clampVirtualizerStrength(state.strength))
                    AudioEffect.SUCCESS
                }.getOrDefault(AudioEffect.ERROR)
            }
            virtualizerSetEnabledStatus = v.setEnabled(state.enabled)
        }
        if (state.enabled) {
            val captured = v
            serviceScope.launch {
                delay(50)
                runCatching { captured.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL) }
                    .onSuccess { virtualizerForceModeApplied = true }
            }
        }
    }

    /**
     * Emits the single `AuxenFx`-tagged diagnostic line the device
     * checklist depends on (platform effects fix, user-confirmed device
     * report, 2026-07-13) -- called on every session rebuild and from
     * `onIsPlayingChanged(true)`. [formatFxDiagnosticLog] is the pure,
     * directly-testable half; this method's only job is assembling the
     * live snapshot from [reverb]/[virtualizer] and this service's own
     * status fields.
     */
    private fun logFxDiagnostics(sessionId: Int) {
        val r = reverb
        val v = virtualizer
        val message = formatFxDiagnosticLog(
            sessionId = sessionId,
            reverb = ReverbDiagnostics(
                created = r != null,
                id = r?.id,
                hasControl = runCatching { r?.hasControl() }.getOrNull(),
                setEnabledStatus = reverbSetEnabledStatus,
                auxRouteSet = reverbAuxRouteSet,
            ),
            virtualizer = VirtualizerDiagnostics(
                created = v != null,
                strengthSupported = runCatching { v?.strengthSupported }.getOrNull(),
                setStrengthStatus = virtualizerSetStrengthStatus,
                setEnabledStatus = virtualizerSetEnabledStatus,
                forceModeApplied = virtualizerForceModeApplied,
            ),
        )
        Log.i("AuxenFx", message)
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
            // MediaSession.player is typed as the base Player interface, not
            // ExoPlayer -- clearAuxEffectInfo() is ExoPlayer-specific, so a
            // safe cast, not a straight call (the actual runtime instance is
            // always the ExoPlayer onCreate built; a safe cast just avoids
            // any crash risk if that ever stops holding).
            runCatching { (player as? ExoPlayer)?.clearAuxEffectInfo() }
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

/**
 * Clamps to `PresetReverb`'s valid `PRESET_*` range (0..6) -- apply-site
 * validation, since a persisted [ReverbState] could hold anything (fix
 * round, review of commit 6426194, Minor). Extracted as a top-level
 * `internal` function so it's directly unit-testable without touching
 * [PlaybackService] or any real platform-effect object.
 */
internal fun clampReverbPreset(preset: Int): Short = preset.coerceIn(0, 6).toShort()

/**
 * Clamps to `Virtualizer`'s valid strength range (0..1000) -- same
 * apply-site-validation reasoning as [clampReverbPreset].
 */
internal fun clampVirtualizerStrength(strength: Int): Short = strength.coerceIn(0, 1000).toShort()

/**
 * Plain snapshot of a `PresetReverb`'s live state for the `AuxenFx`
 * diagnostic line -- nullable where a getter can be absent (never built,
 * or threw). Kept as a value type so [formatFxDiagnosticLog] is a pure,
 * directly-testable function with no platform-effect object involved.
 */
internal data class ReverbDiagnostics(
    val created: Boolean,
    val id: Int?,
    val hasControl: Boolean?,
    val setEnabledStatus: Int?,
    val auxRouteSet: Boolean,
)

/** Plain snapshot of a `Virtualizer`'s live state for the `AuxenFx` line. */
internal data class VirtualizerDiagnostics(
    val created: Boolean,
    val strengthSupported: Boolean?,
    val setStrengthStatus: Int?,
    val setEnabledStatus: Int?,
    val forceModeApplied: Boolean,
)

/**
 * Pure formatter for the single `AuxenFx`-tagged diagnostic line the device
 * checklist relies on (`adb logcat -s AuxenFx`). Field set + wording are
 * pinned by [FxDiagnosticLogTest]; audibility itself is un-CI-testable, so
 * this structured line is the shipped fallback for disambiguating the
 * remaining device-only hypotheses.
 */
internal fun formatFxDiagnosticLog(
    sessionId: Int,
    reverb: ReverbDiagnostics,
    virtualizer: VirtualizerDiagnostics,
): String =
    "sessionId=$sessionId " +
        "reverb[created=${reverb.created} id=${reverb.id} hasControl=${reverb.hasControl} " +
        "setEnabledStatus=${reverb.setEnabledStatus} auxRouteSet=${reverb.auxRouteSet}] " +
        "virtualizer[created=${virtualizer.created} strengthSupported=${virtualizer.strengthSupported} " +
        "setStrengthStatus=${virtualizer.setStrengthStatus} setEnabledStatus=${virtualizer.setEnabledStatus} " +
        "forceModeApplied=${virtualizer.forceModeApplied}]"

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
 * Builds the seven-stage DSP processor array in chain order. Extracted out
 * of [EqRenderersFactory.buildAudioSink] so a production regression test
 * ([io.github.auxen.playback.ProcessorChainOrderTest]) can call the exact
 * function the real audio sink uses, instead of asserting against a
 * hand-rolled list that could silently drift out of sync if this order ever
 * changed here without the test noticing (fix round, review of commit
 * dd1bd55, Important #2). Order is LAW -- see [ParametricEqProcessor]'s KDoc.
 *
 * [autoEqProcessor] and [eqProcessor] are both [ParametricEqProcessor]
 * instances -- AutoEq split, Task 1: headphone correction and the 10-band
 * graphic EQ are now two independent stages (AutoEq first, right after
 * ReplayGain, before the user's graphic taste; both LTI, but each carries
 * its own preamp so headroom composes predictably).
 */
@UnstableApi
internal fun buildDspProcessorChain(
    replayGainProcessor: ReplayGainProcessor,
    autoEqProcessor: ParametricEqProcessor,
    eqProcessor: ParametricEqProcessor,
    bassBoostProcessor: BassBoostProcessor,
    balanceProcessor: BalanceProcessor,
    limiterProcessor: LimiterProcessor,
    encodingRestorerProcessor: EncodingRestorerProcessor,
): Array<AudioProcessor> = arrayOf(
    replayGainProcessor,
    autoEqProcessor,
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
    private val autoEqProcessor: ParametricEqProcessor,
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
        // MUST stay false. In Media3 1.5.1, DefaultAudioSink inserts the app
        // AudioProcessor chain ONLY on its integer path -- the float-output path
        // skips setAudioProcessors entirely, silently bypassing the WHOLE DSP
        // chain (EQ, AutoEq, bass, balance, limiter, ReplayGain) for any source
        // that decodes to float. That was the "DSP does nothing" bug (dead on all
        // routes for float/hi-res sources; ParametricEqProcessor's KDoc already
        // noted it). The integer path reduces hi-res to 16-bit before the chain;
        // each processor accepts 16-bit input (see their onConfigure) and
        // upconverts to float internally for headroom, and EncodingRestorer-
        // Processor converts back to 16-bit at the tail -- so this design already
        // outputs 16-bit and loses nothing by running integer. (True float-through
        // WITH DSP would require a custom/forked AudioSink -- a tracked follow-up.)
        // Chain order is LAW (see ParametricEqProcessor's KDoc) -- ReplayGain
        // first so a quiet track's boost has the same downstream headroom as
        // everything else, Limiter last-but-one so it's the one stage
        // allowed to clamp, catching whatever upstream boosts produced.
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(
            buildDspProcessorChain(
                replayGainProcessor,
                autoEqProcessor,
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
