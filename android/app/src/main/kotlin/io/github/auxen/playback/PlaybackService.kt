package io.github.auxen.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.auxen.Graph
import io.github.auxen.R
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.AutoEqController
import io.github.auxen.dsp.BalanceProcessor
import io.github.auxen.dsp.BassBoostProcessor
import io.github.auxen.dsp.BitPerfectController
import io.github.auxen.dsp.EncodingRestorerProcessor
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.LimiterProcessor
import io.github.auxen.dsp.ParametricEqProcessor
import io.github.auxen.dsp.ReplayGainProcessor
import io.github.auxen.dsp.ReverbState
import io.github.auxen.dsp.VirtualizerState
import io.github.auxen.model.Track
import io.github.auxen.playback.browse.AuxenBrowseIds
import io.github.auxen.playback.browse.AuxenBrowseTree
import io.github.auxen.playback.browse.BrowseFetch
import io.github.auxen.playback.browse.BrowseQueueResolver
import io.github.auxen.playback.browse.BrowseTarget
import io.github.auxen.playback.browse.TidalBrowseFetcher
import io.github.auxen.playback.cache.AutoResumeController
import io.github.auxen.playback.cache.AutoResumeEvent
import io.github.auxen.playback.cache.CacheAheadScheduler
import io.github.auxen.playback.cache.CacheKind
import io.github.auxen.playback.cache.CacheQueueSnapshot
import io.github.auxen.playback.cache.CacheRecoveryGate
import io.github.auxen.playback.cache.OfflineAction
import io.github.auxen.playback.cache.OfflineSkipper
import io.github.auxen.playback.cache.PlayerQueueAdapter
import io.github.auxen.playback.cache.QueueChangeTracker
import io.github.auxen.playback.cache.TrackDownloader
import io.github.auxen.playback.cache.cacheCandidates
import io.github.auxen.playback.cache.isExternalOutput
import io.github.auxen.playback.cache.isPlayingResolvedCopy
import io.github.auxen.playback.cache.nextIndexForCache
import io.github.auxen.playback.cache.playOrder
import io.github.auxen.playback.cache.queueItemUpdate
import io.github.auxen.playback.cache.queueMediaIds
import io.github.auxen.playback.cache.reconcileQueueWithCache
import io.github.auxen.playback.cache.replacePlayingItem
import io.github.auxen.playback.cache.resumeOfflinePause
import io.github.auxen.playback.cache.swapDashCopies
import io.github.auxen.playback.cache.tidalTrackIdOf
import io.github.auxen.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
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
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val browseTree = AuxenBrowseTree()
    private val browseQueue = BrowseQueueResolver()
    private val browseFetcher by lazy { TidalBrowseFetcher(Graph.tidal) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = MainScope()
    private var queueSaveJob: Job? = null

    /**
     * The ReplayGain router for the CURRENTLY-live player. Recreated by
     * [buildPlayer] (it wraps that player's own [ReplayGainProcessor] instance)
     * and read by the main [Player.Listener] that [configurePlayer] installs.
     * A field, not a local, so a Bit-Perfect rebuild's fresh router reaches the
     * new player's listener.
     */
    private var rgGainRouter: RgGainRouter? = null

    /**
     * The Bit-Perfect mode the live player was built with. Seeded in [onCreate]
     * from [BitPerfectController.enabled] and updated on every rebuild, so the
     * observer in [observeBitPerfect] only rebuilds when the mode actually flips
     * (never on the initial replayed value).
     */
    private var currentBitPerfect = false

    /** Last expiry-recovery attempt (elapsedRealtime), bounding the 4xx retry path. */
    private var lastExpiryRecoveryAtMillis = 0L

    /** Bounds missing-cache-file recovery to one attempt per Tidal id per cooldown. Main thread only. */
    private val cacheRecoveryGate = CacheRecoveryGate(RETRY_COOLDOWN_MILLIS, SystemClock::elapsedRealtime)

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

    /**
     * Spots a real queue change at PLAYLIST_CHANGED, which cache and DASH swaps fire too. Every
     * queue set through the session, the startup restore included, reaches it through that
     * callback; the Bit-Perfect rebuild seeds it directly. Main thread only.
     */
    private val queueChanges = QueueChangeTracker()

    /** Downloads the songs coming up so they play from disk. Built in [onCreate]. */
    private var cacheAhead: CacheAheadScheduler? = null

    /** Keeps playback going from the cache with no network. Built in [onCreate]. */
    private lateinit var offlineSkipper: OfflineSkipper

    /** The live session player, as the offline helpers read and drive it. */
    private val offlineQueue = PlayerQueueAdapter { mediaSession?.player }

    /** Plays again when the network comes back soon after a "nothing cached ahead" pause. Main thread only. */
    private val autoResume = AutoResumeController { index, mediaId -> resumeOfflinePause(offlineQueue, index, mediaId) }

    /**
     * Cancels the auto-resume when the car or headphones go away. The player is already paused
     * while waiting, so the noisy-audio pause never fires, and a resume would play on the speaker.
     */
    private val outputLoss = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink && isExternalOutput(it.type) }) {
                autoResume.on(AutoResumeEvent.OutputLost)
            }
        }
    }

    /**
     * Queue tracks decoded on the last cache-window walk, by mediaId, so the replans every
     * player event sets off skip the JSON decode. Rebuilt on each walk, so it never outgrows
     * the queue. Main thread only.
     */
    private var cacheTrackMemo: Map<String, Track> = emptyMap()

    override fun onCreate() {
        super.onCreate()

        // Build the initial player for whatever Bit-Perfect mode has been
        // restored so far. BitPerfectController's DataStore restore is async, so
        // this may still read the default (false) even when the persisted value
        // is true; observeBitPerfect() below rebuilds if the settled value ends
        // up differing -- exactly the same "restore lands after the fact" shape
        // the effect controllers already have (they push restored state into
        // their attached appliers once DataStore settles).
        val bitPerfect = BitPerfectController.enabled.value
        currentBitPerfect = bitPerfect
        val player = buildPlayer(bitPerfect)

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
                // A timer that runs out during an offline pause beats the auto-resume, finish-track
                // or not: the outage already stopped the track it would have finished.
                withContext(Dispatchers.Main) { autoResume.on(AutoResumeEvent.SleepTimerFired) }
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

        // Built before the listener below so its first event has somewhere to go. The
        // downloader reuses the app's plain OkHttp client, which logs nothing, so stream
        // URLs and tokens stay out of logcat.
        val downloader = TrackDownloader(Graph.httpClient) { id, fresh ->
            if (fresh) Graph.resolver.invalidate(id)
            Graph.resolver.resolve(id)
        }
        cacheAhead = CacheAheadScheduler(
            scope = serviceScope,
            index = Graph.trackCache,
            download = downloader::download,
            settings = Graph.cacheSettings.settings,
            network = Graph.network.state,
            freeDeviceBytes = Graph.trackCache::freeDeviceBytes,
        )
        offlineSkipper = OfflineSkipper(
            offlineQueue,
            Graph.trackCache::cachedPlayback,
            { Graph.network.state.value },
            Graph.offlineStatus,
        )

        // Attach the platform effects, the main Player.Listener, and the
        // audio-session-id kick -- all re-runnable per player, so a Bit-Perfect
        // rebuild can re-wire a fresh player identically (see configurePlayer).
        configurePlayer(player)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, AuxenSessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_stat_auxen)
            },
        )

        // Rebuild the player live whenever the Bit-Perfect mode flips (and to
        // pick up the DataStore-restored value if it settles to something other
        // than what the initial build used above).
        observeBitPerfect()

        // Commits, evictions, clear and logout all change the snapshot. The first value needs no
        // pass: whatever queue is set gets one from its own PLAYLIST_CHANGED.
        mainScope.launch {
            Graph.trackCache.snapshot.drop(1).collect { mediaSession?.player?.let(::syncQueueWithCache) }
        }

        // The validated network can drop after a network error already came in. A reconnect
        // clears the "nothing cached ahead" notice whether or not it resumes, so a stale one
        // never outlives the outage. The clear runs before the resume, so a resume that pauses
        // again with nothing cached keeps the notice it just showed.
        mainScope.launch {
            Graph.network.state.collect { state ->
                autoResume.onOfflineAction(offlineSkipper.onNetworkChanged(state))
                autoResume.onNetwork(state.online) { Graph.offlineStatus.clear() }
            }
        }
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(outputLoss, Handler(Looper.getMainLooper()))

        // Restore the persisted queue paused: the user decides when to hit
        // play, and no Tidal stream resolution happens until they do.
        mainScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { Graph.queueStore.load() }.getOrNull() }
                ?: return@launch
            // Reference the LIVE session player, not the initial local, so a
            // Bit-Perfect rebuild that swapped the player in the meantime can't
            // leave us writing the queue into a released instance.
            val p = mediaSession?.player ?: return@launch
            if (p.mediaItemCount > 0) return@launch // a controller (or a rebuild) beat us to it
            p.setMediaItems(saved.tracks.map(Graph::mediaItemFor), saved.index, saved.positionMs)
            // No prepare(): stream resolution stays lazy until the user plays.
        }
    }

    /**
     * Builds a fully-wired [ExoPlayer] for the given [bitPerfect] mode: a fresh
     * DSP processor set, every effect controller (re-)attached to those fresh
     * instances, the ReplayGain router, and an [EqRenderersFactory] configured
     * for [bitPerfect]. Does NOT install the main [Player.Listener], platform
     * effects, or session id -- that is [configurePlayer]'s job, run right after
     * this on both the initial build and every rebuild.
     *
     * Every controller `attachX`/`attachProcessor` REPLACES its previous
     * applier/processor (single-applier design -- see [AudioFxController]/
     * [EqController] KDoc), so a rebuild's fresh processors cleanly orphan the
     * released player's processors instead of both being driven at once.
     */
    private fun buildPlayer(bitPerfect: Boolean): ExoPlayer {
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
        rgGainRouter = RgGainRouter(serviceScope, replayGainProcessor)

        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            TidalUriResolver(Graph.resolver, Graph.trackCache::cachedPlayback),
        )

        val renderersFactory = EqRenderersFactory(
            this,
            replayGainProcessor,
            autoEqProcessor,
            eqProcessor,
            bassBoostProcessor,
            balanceProcessor,
            limiterProcessor,
            bitPerfect,
        )
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(AuxenLoadErrorPolicy(Graph.trackCache.rootUri)),
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
    }

    /**
     * Wires everything that must be attached AFTER an [ExoPlayer] is built --
     * identical for the initial player and every Bit-Perfect rebuild: the
     * platform reverb/virtualizer appliers (routed to THIS player), the main
     * [Player.Listener], and the `setAudioSessionId(UNSET)` kick that fires
     * `onAudioSessionIdChanged` -> [rebuildSessionEffects]. Each `attachX`
     * REPLACES the previous player's applier (single-applier design), so
     * re-running this for a new player cleanly re-points the platform effects
     * and the RG router at it.
     */
    private fun configurePlayer(player: ExoPlayer) {
        // The RG router built alongside THIS player in buildPlayer (captured as
        // a local so the listener below closes over a stable, non-null value).
        val router = rgGainRouter

        // Platform effects (below the in-process chain): attach here (after
        // `player` exists, since reverb's route needs player.setAuxEffectInfo)
        // so a settings change reaches whatever reverb/virtualizer instance
        // currently exists, null-safely no-op'ing before the first one is
        // built by rebuildSessionEffects (right after this).
        AudioFxController.attachReverb { state -> applyReverb(state, player) }
        AudioFxController.attachVirtualizer { state -> applyVirtualizer(state) }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // An armed "finish this track" pause wins, so the song it stops on is never
                // skipped. Otherwise, offline, an uncached song is skipped before anything counts
                // it as playing. The skip's own transition comes right after this one and does
                // the work below. A different song cancels the auto-resume, fed first so a pause on
                // this same song starts a fresh wait.
                autoResume.on(AutoResumeEvent.ItemChanged(mediaItem?.mediaId.orEmpty()))
                val sleepPauseDue = sleepPauseFiresOn(reason, pendingSleepTimerPause)
                val offline = if (sleepPauseDue) OfflineAction.None else offlineSkipper.onItemStarting()
                autoResume.onOfflineAction(offline)
                if (offline is OfflineAction.Skipped) return
                pendingPlayMediaId = mediaItem?.mediaId
                // Records immediately for mid-playback transitions (isPlaying
                // still true); defers to onIsPlayingChanged for cold starts and
                // paused queue edits, so restores never count as plays.
                maybeRecordPendingPlay(player)
                scheduleQueueSave(player)
                preResolveUpcoming(player)
                router?.route(mediaItem?.mediaId, player.playWhenReady)
                // A swap of the playing item isn't the next track starting. A real queue change
                // already disarmed the pause in onTimelineChanged, which fires first.
                if (sleepPauseDue) checkSleepTimerPause(player)
                // Belt-and-suspenders: the AudioTrack (and therefore the
                // platform effects attached to its session) can be recreated
                // between tracks or on a route change without necessarily
                // firing onAudioSessionIdChanged -- re-apply the CURRENT
                // AudioFxController states on every transition rather than
                // relying solely on the session-id-change path (platform
                // effects fix, user-confirmed device report, 2026-07-13).
                reapplySessionEffects(player)
                markCachedPlay(mediaItem)
                updateCacheWindow(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    scheduleQueueSave(player)
                    // Songs added to the queue later pick up their cached copies.
                    syncQueueWithCache(player)
                    // Built from mediaIds, so a cache swap leaves it unchanged.
                    updateCacheWindow(player)
                    val queueChanged = queueChanges.changed(queueMediaIds(player))
                    // A new queue may have cached songs, so the offline message is stale, and
                    // the song an offline pause was waiting on may be gone.
                    if (queueChanged) {
                        Graph.offlineStatus.clear()
                        autoResume.on(AutoResumeEvent.QueueChanged)
                    }
                    // Belt-and-suspenders against pendingSleepTimerPause
                    // surviving into an unrelated new queue -- see that
                    // field's KDoc (fix round, review of commit 60c7699,
                    // finding 1). A cache swap isn't a new queue.
                    if (queueChanged && pendingSleepTimerPause) {
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
                    autoResume.on(AutoResumeEvent.SleepTimerFired)
                }
                // Buffering toward play counts as active for caching.
                updateCacheWindow(player)
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
                    router?.route(player.currentMediaItem?.mediaId, player.playWhenReady)
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
                updateCacheWindow(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Any play supersedes the auto-resume; the play a resume starts finds it already
                // Idle. A pause changes nothing: while waiting, the player is already paused.
                if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    // Clear first, so a play that still finds nothing cached shows the message again
                    // and starts a fresh wait.
                    Graph.offlineStatus.clear()
                    autoResume.onUserPlay { offlineSkipper.onItemStarting() }
                } else if (playWhenReady) {
                    autoResume.on(AutoResumeEvent.UserPlay)
                }
                if (playWhenReady) {
                    preResolveUpcoming(player)
                    router?.route(player.currentMediaItem?.mediaId, playWhenReady)
                }
                updateCacheWindow(player)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateCacheWindow(player)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateCacheWindow(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentPlayer = mediaSession?.player ?: return

                if (recoverMissingCacheFile(currentPlayer, error)) return

                // A song cached as a DASH folder, or one Tidal serves as a DASH manifest.
                val dash = findCause<CachedDashStreamException>(error)?.let { it.trackId to it.manifestUri }
                    ?: findCause<TidalDashStreamException>(error)?.let { it.trackId to it.streamInfo.uri }
                if (dash != null) {
                    val swapped = swapDashCopies(currentPlayer, dash.first, dash.second)
                    // preResolveUpcoming can swap the playing copy between the error and this callback.
                    if (!swapped && !isPlayingResolvedCopy(currentPlayer, dash.first)) return
                    currentPlayer.prepare()
                    currentPlayer.play()
                    return
                }

                // No network: resume from the cache, skip to a song that plays offline, or pause.
                val offline = offlineSkipper.onError(error)
                autoResume.onOfflineAction(offline)
                if (offline != OfflineAction.None) return

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
                        // A cached song's local URI never expires.
                        if (queued.localConfiguration?.uri?.toString()?.startsWith(Graph.trackCache.rootUri) == true) continue
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
    }

    /**
     * Observes [BitPerfectController.enabled] and rebuilds the player whenever
     * the mode flips. Collected on [mainScope] (the main thread) because
     * ExoPlayer must only be touched from the thread that built it; the initial
     * replayed value equals [currentBitPerfect], so it never triggers a
     * spurious first rebuild.
     */
    private fun observeBitPerfect() {
        mainScope.launch {
            BitPerfectController.enabled.collect { desired ->
                if (desired != currentBitPerfect) {
                    currentBitPerfect = desired
                    rebuildPlayerForBitPerfect(desired)
                }
            }
        }
    }

    /**
     * Rebuilds the ExoPlayer for a new [bitPerfect] mode. `setEnableFloatOutput`
     * is fixed at sink construction, so switching modes requires a fresh player.
     * A brief audible gap here is acceptable -- this is a deliberate settings
     * action, not a hot path.
     *
     * Captures the live queue/position/flags, builds a new player with the new
     * mode, swaps it into the (surviving) [MediaSession] -- so notification,
     * lockscreen controls, and connected [androidx.media3.session.MediaController]s
     * all keep working across the swap (Media3 supports `MediaSession.setPlayer`)
     * -- re-wires it via [configurePlayer], then releases the old player.
     */
    private fun rebuildPlayerForBitPerfect(bitPerfect: Boolean) {
        val session = mediaSession ?: return
        val old = session.player as? ExoPlayer ?: return

        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val startIndex = old.currentMediaItemIndex.coerceAtLeast(0)
        val startPositionMs = old.currentPosition.coerceAtLeast(0)
        val playWhenReady = old.playWhenReady
        val repeatMode = old.repeatMode
        val shuffle = old.shuffleModeEnabled
        // Preserve the lazy-resolution contract (see the queue-restore comment
        // in onCreate): a paused, never-prepared restored queue (STATE_IDLE and
        // not intending to play) must NOT be prepared here, or merely toggling
        // Bit-Perfect would kick off Tidal stream resolution the user never
        // asked for. Prepare only if the old player was already past IDLE or
        // playback is actually intended.
        val shouldPrepare = old.playbackState != Player.STATE_IDLE || playWhenReady

        val newPlayer = buildPlayer(bitPerfect)
        if (items.isNotEmpty()) {
            newPlayer.setMediaItems(items, startIndex, startPositionMs)
            if (shouldPrepare) newPlayer.prepare()
        }
        newPlayer.playWhenReady = playWhenReady
        newPlayer.repeatMode = repeatMode
        newPlayer.shuffleModeEnabled = shuffle

        // Swap first so connected controllers (the UI) re-bind to the new
        // player before the old one is torn down; the MediaSession itself (and
        // thus the notification/controls) survives.
        session.player = newPlayer
        configurePlayer(newPlayer)
        // setMediaItems ran before the listener was attached, so seed this here.
        queueChanges.seed(queueMediaIds(newPlayer))
        updateCacheWindow(newPlayer)

        // The old player's platform effects were already released inside
        // configurePlayer(newPlayer)'s rebuildSessionEffects (it releases the
        // reverb/virtualizer fields before building the new session's). Clear
        // its aux route defensively, then release it.
        runCatching { old.clearAuxEffectInfo() }
        old.release()
    }

    /**
     * Tells the cache-ahead scheduler what plays next, in the order the player will reach it
     * (shuffle and repeat-all included). An empty queue still sends a snapshot, so a download
     * for a song no longer queued gets cancelled.
     */
    private fun updateCacheWindow(player: Player) {
        val scheduler = cacheAhead ?: return
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            cacheTrackMemo = emptyMap()
            scheduler.update(CacheQueueSnapshot(emptyList(), playingId = null, active = false))
            return
        }
        val order = playOrder(player.currentMediaItemIndex) { i -> nextIndexForCache(player, i) }
        val memo = HashMap<String, Track>()
        val candidates = cacheCandidates(order, { player.getMediaItemAt(it).mediaId }) { i ->
            val item = player.getMediaItemAt(i)
            (memo[item.mediaId] ?: cacheTrackMemo[item.mediaId] ?: Graph.trackFor(item))
                ?.also { memo[item.mediaId] = it }
        }
        cacheTrackMemo = memo
        val active = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        scheduler.update(CacheQueueSnapshot(candidates, player.currentMediaItem?.mediaId?.let(::tidalTrackIdOf), active))
    }

    /** A cached song starting to play ranks as recently played, so eviction keeps it longer. */
    private fun markCachedPlay(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId?.let(::tidalTrackIdOf) ?: return
        // cachedPlayback stats the file, so it runs off the main thread too.
        serviceScope.launch {
            if (Graph.trackCache.cachedPlayback(id) != null) Graph.trackCache.markPlayed(id)
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
        autoResume.on(AutoResumeEvent.SleepTimerFired)
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

    /** Points [player]'s queued Tidal items at their cached copies, or back at the network. */
    private fun syncQueueWithCache(player: Player) {
        reconcileQueueWithCache(player, Graph.trackCache::cachedPlayback, Graph.trackCache.rootUri)
    }

    /**
     * Handles a cached file that vanished between resolve and open, or a DASH folder missing a
     * segment: drops the entry, points the item back at the network and plays on from the same
     * spot. Once per track per cooldown, so a file that keeps failing can't loop. False when it
     * didn't take the error.
     */
    private fun recoverMissingCacheFile(player: Player, error: PlaybackException): Boolean {
        val mediaId = player.currentMediaItem?.mediaId
        if (!isRecoverableCacheError(error.errorCode, mediaId) || mediaId == null) return false
        val trackId = mediaId.removePrefix("TIDAL:")
        if (!cacheRecoveryGate.tryAcquire(trackId)) return false
        mainScope.launch {
            try {
                Graph.trackCache.evict(listOf(trackId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PlaybackService", "Couldn't evict cached track $trackId after a missing file", e)
            }
            val p = mediaSession?.player ?: return@launch
            val current = p.currentMediaItem ?: return@launch
            // The user may have moved on while the evict ran.
            if (current.mediaId != mediaId) return@launch
            queueItemUpdate(current, null, Graph.trackCache.rootUri)?.let { replacePlayingItem(p, it) }
            p.prepare()
            p.play()
        }
        return true
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
                // A cached progressive song plays from disk through its auxen URI. A cached DASH
                // song swaps onto its local manifest here, so it doesn't bounce through onPlayerError.
                val cached = Graph.trackCache.cachedPlayback(id)
                if (cached?.kind == CacheKind.PROGRESSIVE) continue
                val mpdUri = cached?.uri
                    ?: runCatching { Graph.resolver.resolve(id) }.getOrNull()?.uri?.takeIf { it.startsWith("data:") }
                    ?: continue
                withContext(Dispatchers.Main) {
                    mediaSession?.player?.let { p -> swapDashCopies(p, id, mpdUri) }
                }
            }
        }
    }

    private inner class AuxenSessionCallback : MediaLibrarySession.Callback {
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

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = browseTree.item(mediaId) ?: browseQueue.resolve(mediaId)
            return if (item != null) Futures.immediateFuture(LibraryResult.ofItem(item, null))
            else Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = mainScope.future {
            val children: List<MediaItem> = withContext(Dispatchers.IO) {
                try {
                    childrenFor(parentId)
                } catch (t: Throwable) {
                    listOf(browseTree.errorNode())
                }
            }
            // Cache the playable leaves of this folder so a tapped item can be
            // reconstructed and expanded into its sibling queue. Exclude the
            // "Play all" nodes so they never end up cached as queue tracks.
            val playable = children.filter {
                it.mediaMetadata.isPlayable == true &&
                    AuxenBrowseIds.parse(it.mediaId).let { t ->
                        t !is BrowseTarget.PlayAllAlbum && t !is BrowseTarget.PlayAllArtist
                    }
            }
            if (playable.isNotEmpty()) browseQueue.remember(parentId, playable)
            val from = (page.toLong() * pageSize.toLong()).coerceIn(0L, children.size.toLong()).toInt()
            val pageSlice = children.drop(from).take(pageSize.coerceAtLeast(0))
            LibraryResult.ofItemList(ImmutableList.copyOf(pageSlice), params)
        }

        // Android Auto sends back items carrying only a mediaId. Rebuild each into a
        // full playable MediaItem (URI + extras) from the browse-queue cache; drop any
        // that cannot be resolved.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) item else browseQueue.resolve(item.mediaId)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        // When a browse leaf (or "Play all") is chosen, expand to its sibling list so
        // playback continues from the tapped item to the end of the shown list.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val id = mediaItems.single().mediaId
                // "Play all" -> the parent folder's full list from the top.
                val playAllExpansion = when (val t = AuxenBrowseIds.parse(id)) {
                    is BrowseTarget.PlayAllAlbum ->
                        browseQueue.expandParent(AuxenBrowseIds.album(t.tidalId))
                    is BrowseTarget.PlayAllArtist ->
                        browseQueue.expandParent(AuxenBrowseIds.artist(t.tidalId))
                    else -> null
                }
                val expansion = playAllExpansion ?: browseQueue.expand(id)
                if (expansion != null) return Futures.immediateFuture(expansion)
            }
            // Fallback: resolve each id to a full item; keep the requested start.
            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) item else browseQueue.resolve(item.mediaId)
            }
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(
                    resolved,
                    startIndex.coerceAtMost(maxOf(0, resolved.size - 1)),
                    startPositionMs,
                ),
            )
        }
    }

    // The cached badge is read when Auto opens a folder. A song that finishes caching
    // while its folder is open shows its badge the next time the folder opens.
    private suspend fun childrenFor(parentId: String): List<MediaItem> {
        val cached = Graph.trackCache.snapshot.value.ids
        return when (val target = AuxenBrowseIds.parse(parentId)) {
            BrowseTarget.Root -> browseTree.rootChildren()
            BrowseTarget.Recent -> browseTree.trackList(Graph.library.recentlyPlayed(limit = 50), cached)
            BrowseTarget.Favorites -> browseTree.trackList(Graph.library.favorites().first(), cached)
            BrowseTarget.Playlists -> browseTree.playlistCategory(Graph.library.playlists().first())
            BrowseTarget.Albums -> browseTree.albumCategory(Graph.library.favoriteAlbums().first())
            BrowseTarget.Artists -> browseTree.artistCategory(Graph.library.favoriteArtists().first())
            is BrowseTarget.Playlist -> browseTree.trackList(playlistTracksFor(target.id), cached)
            is BrowseTarget.Album -> when (val f = browseFetcher.albumTracks(target.tidalId)) {
                is BrowseFetch.Ok -> browseTree.albumDetail(target.tidalId, f.tracks, cached)
                BrowseFetch.Failed -> listOf(browseTree.errorNode())
            }
            is BrowseTarget.Artist -> when (val f = browseFetcher.artistTracks(target.tidalId)) {
                is BrowseFetch.Ok -> browseTree.artistDetail(target.tidalId, f.tracks, cached)
                BrowseFetch.Failed -> listOf(browseTree.errorNode())
            }
            else -> listOf(browseTree.errorNode())
        }
    }

    /** Local playlist tracks, importing a Tidal playlist's items on first open
     *  (mirrors PlayerViewModel.loadPlaylist). */
    private suspend fun playlistTracksFor(playlistId: Long): List<Track> {
        val local = runCatching { Graph.library.playlistTracks(playlistId) }.getOrDefault(emptyList())
        if (local.isNotEmpty()) return local
        val uuid = Graph.library.playlists().first().firstOrNull { it.id == playlistId }?.tidalPlaylistId
            ?: return local
        runCatching { Graph.tidalCollectionSync.importPlaylistTracks(playlistId, uuid) }
        return runCatching { Graph.library.playlistTracks(playlistId) }.getOrDefault(emptyList())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // An offline pause that may still resume on its own counts as playing.
        if (autoResume.isWaiting) return
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Before any teardown, so no download starts while the player and scopes go away.
        cacheAhead?.stop()
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(outputLoss)
        // With no service nothing is paused offline, so the notice mustn't outlive it.
        Graph.offlineStatus.clear()
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

/**
 * The single Bit-Perfect -> float-output mapping, extracted as a pure function
 * so the invariant can be unit-tested without constructing a real audio sink
 * ([io.github.auxen.playback.BitPerfectFloatOutputTest]).
 *
 * - `false` (default, Bit-Perfect OFF): request INTEGER output. In Media3
 *   1.5.1, `DefaultAudioSink` inserts the app AudioProcessor chain ONLY on its
 *   integer path, so the WHOLE DSP chain (EQ, AutoEq, bass, balance, limiter,
 *   ReplayGain) runs and output is 16-bit. This is the shipping behaviour and
 *   MUST remain the default -- float-on silently skips `setAudioProcessors`
 *   entirely, which was the "DSP does nothing" bug (dead on all routes for
 *   float/hi-res sources). The integer path reduces hi-res to 16-bit before the
 *   chain; each processor accepts 16-bit input (see their onConfigure) and
 *   upconverts to float internally for headroom, and EncodingRestorerProcessor
 *   converts back to 16-bit at the tail -- so this design already outputs
 *   16-bit and loses nothing by running integer.
 * - `true` (Bit-Perfect / Direct ON): request FLOAT output. The float path
 *   delivers the untouched hi-res stream straight to the AudioTrack AND, by the
 *   very same Media3 quirk above, skips the processor chain -- so ALL DSP is
 *   auto-bypassed. Exactly a purist "direct" mode: full resolution, nothing
 *   applied. (True float-through WITH DSP would need a custom/forked AudioSink
 *   -- a tracked follow-up.)
 */
internal fun bitPerfectEnablesFloatOutput(bitPerfect: Boolean): Boolean = bitPerfect

/**
 * Renderers factory that injects the full DSP chain into the audio sink. When
 * [bitPerfect] is false (default) the sink runs its integer path and the chain
 * is active; when true it requests float output, which delivers untouched
 * hi-res to the AudioTrack and (in Media3 1.5.1) auto-bypasses the chain. See
 * [bitPerfectEnablesFloatOutput].
 */
@UnstableApi
private class EqRenderersFactory(
    context: Context,
    private val replayGainProcessor: ReplayGainProcessor,
    private val autoEqProcessor: ParametricEqProcessor,
    private val eqProcessor: ParametricEqProcessor,
    private val bassBoostProcessor: BassBoostProcessor,
    private val balanceProcessor: BalanceProcessor,
    private val limiterProcessor: LimiterProcessor,
    private val bitPerfect: Boolean,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // false (default) = integer path, DSP chain ACTIVE, 16-bit out (the
        // shipping behaviour -- do NOT regress it; float-on skips the chain,
        // which was the "DSP does nothing" bug). true = Bit-Perfect / Direct:
        // float passthrough, ALL DSP auto-bypassed by Media3. Chain order is LAW
        // (see ParametricEqProcessor's KDoc) -- ReplayGain first so a quiet
        // track's boost has the same downstream headroom as everything else,
        // Limiter last-but-one so it's the one stage allowed to clamp, catching
        // whatever upstream boosts produced. The chain below is still installed
        // in both modes; Media3 simply ignores it on the float path.
        .setEnableFloatOutput(bitPerfectEnablesFloatOutput(bitPerfect))
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
