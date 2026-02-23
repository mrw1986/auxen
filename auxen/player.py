"""GStreamer playbin3 wrapper for the Auxen music player."""

from __future__ import annotations

import logging
import threading
from typing import Callable, Optional

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, GObject, Gst  # noqa: E402

from auxen.models import Source, Track  # noqa: E402
from auxen.queue import PlayQueue  # noqa: E402

if False:  # TYPE_CHECKING
    from auxen.crossfade import CrossfadeService

logger = logging.getLogger(__name__)


class PlayerState:
    """Playback state constants."""

    STOPPED = "stopped"
    PLAYING = "playing"
    PAUSED = "paused"


class Player(GObject.Object):
    """GStreamer playbin3 player with gapless playback and play queue.

    Signals
    -------
    state-changed(str)
        Emitted when the player state changes.
    track-changed(object)
        Emitted when the current track changes.
    position-updated(float, float)
        Emitted periodically with (position_seconds, duration_seconds).
    eos()
        Emitted when the end of stream is reached.
    spectrum-data(object)
        Emitted with a list of normalised float magnitudes (0.0--1.0)
        from the GStreamer spectrum element.
    """

    __gsignals__ = {
        "state-changed": (
            GObject.SignalFlags.RUN_LAST,
            None,
            (str,),
        ),
        "track-changed": (
            GObject.SignalFlags.RUN_LAST,
            None,
            (object,),
        ),
        "position-updated": (
            GObject.SignalFlags.RUN_LAST,
            None,
            (float, float),
        ),
        "eos": (
            GObject.SignalFlags.RUN_LAST,
            None,
            (),
        ),
        "spectrum-data": (
            GObject.SignalFlags.RUN_LAST,
            None,
            (object,),
        ),
    }

    def __init__(self) -> None:
        super().__init__()
        Gst.init(None)

        self._pipeline = Gst.ElementFactory.make("playbin3", "auxen-player")
        if self._pipeline is None:
            raise RuntimeError(
                "Could not create playbin3 element. "
                "Is gstreamer1-plugins-base installed?"
            )

        self.queue = PlayQueue()
        self._state: str = PlayerState.STOPPED
        self._uri_resolver: Optional[Callable[[Track], str]] = None
        self._position_poll_id: Optional[int] = None
        self._crossfade: Optional["CrossfadeService"] = None
        self._target_volume: float = 0.7
        self._play_generation: int = 0  # guards against stale resolvers
        self._uri_cache: dict[str, str] = {}  # source_id -> stream URI
        self._cache_lock = threading.Lock()  # protects _uri_cache

        # --- ReplayGain + Equalizer + Spectrum audio chain (optional) ---
        self._rgvolume_element: Optional[Gst.Element] = None
        self._rglimiter_element: Optional[Gst.Element] = None
        self._equalizer_element: Optional[Gst.Element] = None
        self._spectrum_element: Optional[Gst.Element] = None
        self._spectrum_bands: int = 16
        self._spectrum_threshold: int = -60
        self._replaygain_enabled: bool = True
        self._replaygain_mode: str = "album"
        try:
            rgvolume = Gst.ElementFactory.make("rgvolume", "rgvolume")
            rglimiter = Gst.ElementFactory.make("rglimiter", "rglimiter")
            eq = Gst.ElementFactory.make("equalizer-10bands", "eq")
            spectrum = Gst.ElementFactory.make("spectrum", "spectrum")
            audio_sink = Gst.ElementFactory.make(
                "autoaudiosink", "audio-out"
            )

            # Configure spectrum element if available
            if spectrum is not None:
                spectrum.set_property("bands", self._spectrum_bands)
                spectrum.set_property("threshold", self._spectrum_threshold)
                # ~66ms interval (in nanoseconds) for ~15 Hz updates
                spectrum.set_property("interval", 66666666)
                spectrum.set_property("post-messages", True)
                spectrum.set_property("message-magnitude", True)
                self._spectrum_element = spectrum
                logger.info("GStreamer spectrum element loaded")
            else:
                logger.warning(
                    "spectrum element not available; "
                    "visualizer will show flat bars"
                )

            if eq is not None and audio_sink is not None:
                audio_bin = Gst.Bin.new("audio-bin")

                if rgvolume is not None and rglimiter is not None:
                    # Full chain: rgvolume -> rglimiter -> eq
                    #             [-> spectrum] -> sink
                    audio_bin.add(rgvolume)
                    audio_bin.add(rglimiter)
                    audio_bin.add(eq)

                    if spectrum is not None:
                        audio_bin.add(spectrum)

                    audio_bin.add(audio_sink)
                    rgvolume.link(rglimiter)
                    rglimiter.link(eq)

                    if spectrum is not None:
                        eq.link(spectrum)
                        spectrum.link(audio_sink)
                    else:
                        eq.link(audio_sink)

                    # Ghost pad from rgvolume (head of chain)
                    pad = rgvolume.get_static_pad("sink")
                    ghost = Gst.GhostPad.new("sink", pad)
                    audio_bin.add_pad(ghost)
                    self._rgvolume_element = rgvolume
                    self._rglimiter_element = rglimiter
                    # Apply default ReplayGain settings
                    rgvolume.set_property("album-mode", True)
                    rgvolume.set_property("pre-amp", 0.0)
                    logger.info(
                        "GStreamer rgvolume + rglimiter elements loaded"
                    )
                else:
                    # Fallback: eq [-> spectrum] -> sink (no ReplayGain)
                    audio_bin.add(eq)

                    if spectrum is not None:
                        audio_bin.add(spectrum)

                    audio_bin.add(audio_sink)

                    if spectrum is not None:
                        eq.link(spectrum)
                        spectrum.link(audio_sink)
                    else:
                        eq.link(audio_sink)

                    pad = eq.get_static_pad("sink")
                    ghost = Gst.GhostPad.new("sink", pad)
                    audio_bin.add_pad(ghost)
                    logger.warning(
                        "rgvolume or rglimiter not available; "
                        "ReplayGain disabled"
                    )

                self._pipeline.set_property("audio-sink", audio_bin)
                self._equalizer_element = eq
                logger.info("GStreamer equalizer-10bands element loaded")
            else:
                logger.warning(
                    "equalizer-10bands or autoaudiosink not available; "
                    "equalizer disabled"
                )
        except Exception:
            logger.warning(
                "Failed to set up audio processing chain; "
                "continuing without it",
                exc_info=True,
            )

        # Set initial volume
        self._pipeline.set_property("volume", 0.7)

        # Bus for EOS / error messages
        bus = self._pipeline.get_bus()
        bus.add_signal_watch()
        bus.connect("message", self._on_bus_message)

        # Gapless playback signal
        self._pipeline.connect("about-to-finish", self._on_about_to_finish)

    # ------------------------------------------------------------------
    # Configuration
    # ------------------------------------------------------------------

    def set_uri_resolver(self, resolver: Callable[[Track], str]) -> None:
        """Set the function that converts a Track into a playable URI."""
        self._uri_resolver = resolver

    def set_crossfade_service(
        self, crossfade: Optional["CrossfadeService"]
    ) -> None:
        """Set the crossfade service for fade-in/fade-out transitions."""
        self._crossfade = crossfade

    # ------------------------------------------------------------------
    # Equalizer
    # ------------------------------------------------------------------

    def set_eq_band(self, band: int, gain_db: float) -> None:
        """Set a single equalizer band gain (dB).

        Does nothing if the equalizer element was not available at
        initialisation time.
        """
        if self._equalizer_element is not None:
            self._equalizer_element.set_property(f"band{band}", gain_db)

    def get_equalizer_element(self) -> Optional[Gst.Element]:
        """Return the GStreamer equalizer element, or ``None``."""
        return self._equalizer_element

    # ------------------------------------------------------------------
    # ReplayGain
    # ------------------------------------------------------------------

    @property
    def replaygain_enabled(self) -> bool:
        """Whether ReplayGain normalization is currently enabled."""
        return self._replaygain_enabled

    def set_replaygain_enabled(self, enabled: bool) -> None:
        """Enable or disable ReplayGain volume normalization.

        When enabled, the ``rgvolume`` element applies its normal gain
        adjustment (pre-amp = 0 dB).  When disabled, the pre-amp is set
        to ``-60 dB`` which effectively mutes the ReplayGain correction
        while keeping the element in the pipeline.
        """
        self._replaygain_enabled = enabled
        if self._rgvolume_element is not None:
            self._rgvolume_element.set_property(
                "pre-amp", 0.0 if enabled else -60.0
            )

    def set_replaygain_mode(self, mode: str) -> None:
        """Set the ReplayGain mode to ``"album"`` or ``"track"``.

        Maps to the ``album-mode`` property of the ``rgvolume`` element:
        ``True`` for album mode, ``False`` for track mode.
        """
        if mode not in ("album", "track"):
            raise ValueError(
                f"Invalid ReplayGain mode {mode!r}; "
                "expected 'album' or 'track'"
            )
        self._replaygain_mode = mode
        if self._rgvolume_element is not None:
            self._rgvolume_element.set_property(
                "album-mode", mode == "album"
            )

    @property
    def replaygain_mode(self) -> str:
        """Current ReplayGain mode (``'album'`` or ``'track'``)."""
        return self._replaygain_mode

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def volume(self) -> float:
        """Current volume (0.0 -- 1.0)."""
        return float(self._pipeline.get_property("volume"))

    @volume.setter
    def volume(self, value: float) -> None:
        clamped = max(0.0, min(1.0, value))
        self._pipeline.set_property("volume", clamped)
        # Track the user-intended volume (not fade volume) for crossfade
        # restore.  Only update when not in a crossfade operation.
        if self._crossfade is None or not self._crossfade.is_fading:
            self._target_volume = clamped

    @property
    def state(self) -> str:
        """Current player state string."""
        return self._state

    # ------------------------------------------------------------------
    # Playback controls
    # ------------------------------------------------------------------

    def play_track(self, track: Track) -> None:
        """Resolve *track* to a URI and start playing it.

        URI resolution (which may involve a Tidal network call) is done
        in a background thread so the GTK main thread is never blocked.
        A generation counter prevents stale resolvers from overwriting
        a newer play request.  After starting playback, the next track's
        URI is prefetched into ``_uri_cache`` for gapless transitions.
        """
        self._play_generation += 1
        gen = self._play_generation

        def _resolve_and_play() -> None:
            uri = self._resolve_uri(track)
            if uri is None:
                return

            # Check generation before any cache/playback side-effects
            if gen != self._play_generation:
                return

            def _start_playback(_uri=uri, _track=track, _gen=gen):
                # Discard if a newer play_track() was called while resolving
                if _gen != self._play_generation:
                    return False
                self._pipeline.set_state(Gst.State.NULL)
                self._pipeline.set_property("uri", _uri)
                self._pipeline.set_state(Gst.State.PLAYING)
                self._set_state(PlayerState.PLAYING)
                self.emit("track-changed", _track)
                self._start_position_polling()

                # Start fade-in if crossfade is enabled.
                if self._crossfade is not None and self._crossfade.enabled:
                    self._crossfade.start_fade_in(self, self._target_volume)
                return False  # Don't repeat

            GLib.idle_add(_start_playback)

            # Prefetch the next track's URI for gapless playback
            self._prefetch_next_uri(gen)

        threading.Thread(target=_resolve_and_play, daemon=True).start()

    def play(self) -> None:
        """Resume playback or play the current queue track."""
        if self._state == PlayerState.PAUSED:
            self._pipeline.set_state(Gst.State.PLAYING)
            self._set_state(PlayerState.PLAYING)
            self._start_position_polling()
        elif self._state == PlayerState.STOPPED:
            track = self.queue.current
            if track is not None:
                self.play_track(track)

    def pause(self) -> None:
        """Pause playback."""
        if self._state == PlayerState.PLAYING:
            self._pipeline.set_state(Gst.State.PAUSED)
            self._set_state(PlayerState.PAUSED)
            self._stop_position_polling()

    def play_pause(self) -> None:
        """Toggle between playing and paused."""
        if self._state == PlayerState.PLAYING:
            self.pause()
        else:
            self.play()

    def stop(self) -> None:
        """Stop playback entirely."""
        self._play_generation += 1
        with self._cache_lock:
            self._uri_cache.clear()
        if self._crossfade is not None:
            self._crossfade.cancel()
        self._pipeline.set_state(Gst.State.NULL)
        self._set_state(PlayerState.STOPPED)
        self._stop_position_polling()

    def next_track(self) -> None:
        """Advance the queue and play the next track, or stop."""
        track = self.queue.next()
        if track is not None:
            self.play_track(track)
        else:
            self.stop()

    def previous_track(self) -> None:
        """Go to previous track, or restart current if past 3 seconds."""
        pos = self.get_position()
        if pos is not None and pos > 3.0:
            self.seek(0.0)
            return
        track = self.queue.previous()
        if track is not None:
            self.play_track(track)

    def seek(self, position_seconds: float) -> None:
        """Seek to *position_seconds* in the current track."""
        position_ns = int(position_seconds * Gst.SECOND)
        self._pipeline.seek_simple(
            Gst.Format.TIME,
            Gst.SeekFlags.FLUSH | Gst.SeekFlags.KEY_UNIT,
            position_ns,
        )

    def get_position(self) -> Optional[float]:
        """Return current playback position in seconds, or None."""
        ok, position = self._pipeline.query_position(Gst.Format.TIME)
        if ok:
            return position / Gst.SECOND
        return None

    def get_duration(self) -> Optional[float]:
        """Return total track duration in seconds, or None."""
        ok, duration = self._pipeline.query_duration(Gst.Format.TIME)
        if ok:
            return duration / Gst.SECOND
        return None

    def play_queue(
        self, tracks: list[Track], start_index: int = 0
    ) -> None:
        """Replace the queue and start playing from *start_index*."""
        with self._cache_lock:
            self._uri_cache.clear()
        self.queue.replace(tracks)
        if tracks and 0 <= start_index < len(tracks):
            self.queue.jump_to(start_index)
            track = self.queue.current
            if track is not None:
                self.play_track(track)

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    def dispose(self) -> None:
        """Release GStreamer resources."""
        self._play_generation += 1
        with self._cache_lock:
            self._uri_cache.clear()
        if self._crossfade is not None:
            self._crossfade.cancel()
        self._stop_position_polling()
        self._pipeline.set_state(Gst.State.NULL)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _resolve_uri(self, track: Track) -> Optional[str]:
        """Convert a Track to a playable URI via the configured resolver."""
        if self._uri_resolver is None:
            return None
        return self._uri_resolver(track)

    def _prefetch_next_uri(self, gen: int) -> None:
        """Resolve the next track's URI and store it in ``_uri_cache``.

        Called from a background thread after ``play_track`` starts
        playback.  The cached URI is consumed by ``_on_about_to_finish``
        so the GStreamer streaming thread never blocks on a network call.

        *gen* is the play-generation at the time the caller was spawned;
        the prefetch is skipped if the generation has since changed.

        Uses an atomic queue snapshot to avoid race conditions with the
        main thread.
        """
        if gen != self._play_generation:
            return

        snap = self.queue.snapshot()
        next_index = snap.position + 1

        if next_index < len(snap.tracks):
            next_track = snap.tracks[next_index]
        elif snap.repeat_mode.value == "queue" and snap.tracks:
            next_track = snap.tracks[0]
        else:
            return

        if not next_track.source_id:
            return

        with self._cache_lock:
            # Already cached — nothing to do
            if next_track.source_id in self._uri_cache:
                return

        uri = self._resolve_uri(next_track)
        if uri is None:
            return

        # Re-check generation after the (potentially slow) resolve
        if gen != self._play_generation:
            return

        with self._cache_lock:
            self._uri_cache[next_track.source_id] = uri

    def _set_state(self, new_state: str) -> None:
        """Update internal state and emit signal."""
        if self._state != new_state:
            self._state = new_state
            self.emit("state-changed", new_state)

    # ------------------------------------------------------------------
    # Bus message handling
    # ------------------------------------------------------------------

    def _on_bus_message(
        self,
        _bus: Gst.Bus,
        message: Gst.Message,
    ) -> None:
        """Handle GStreamer bus messages."""
        msg_type = message.type

        if msg_type == Gst.MessageType.EOS:
            self.emit("eos")
            # Advance the queue position (the URI was already pre-set by
            # about-to-finish for gapless playback).
            track = self.queue.next()
            if track is not None:
                self.emit("track-changed", track)
                # Trigger fade-in for the gapless-transitioned track.
                if self._crossfade is not None and self._crossfade.enabled:
                    self._crossfade.start_fade_in(
                        self, self._target_volume
                    )
            else:
                self.stop()

        elif msg_type == Gst.MessageType.ERROR:
            err, debug = message.parse_error()
            print(f"GStreamer error: {err.message}")
            if debug:
                print(f"  Debug: {debug}")
            self.next_track()

        elif msg_type == Gst.MessageType.ELEMENT:
            self._handle_spectrum_message(message)

    def _handle_spectrum_message(self, message: Gst.Message) -> None:
        """Parse spectrum element messages and emit spectrum-data."""
        if self._spectrum_element is None:
            return

        structure = message.get_structure()
        if structure is None:
            return

        name = structure.get_name()
        if name != "spectrum":
            return

        try:
            magnitudes = structure.get_value("magnitude")
            if magnitudes is None:
                return

            # Normalise: magnitude values are in dB (threshold..0)
            # Map to 0.0..1.0 range
            threshold = float(self._spectrum_threshold)
            levels: list[float] = []
            for i in range(min(len(magnitudes), self._spectrum_bands)):
                mag = float(magnitudes[i])
                # Clamp and normalise
                normalised = (mag - threshold) / (-threshold)
                levels.append(max(0.0, min(1.0, normalised)))

            self.emit("spectrum-data", levels)
        except Exception:
            # Degrade gracefully if parsing fails
            pass

    # ------------------------------------------------------------------
    # Gapless playback
    # ------------------------------------------------------------------

    def _on_about_to_finish(self, _pipeline: Gst.Element) -> None:
        """Pre-set the next URI for gapless playback.

        This callback fires from the GStreamer streaming thread shortly
        before the current stream ends.  We peek at the next track
        *without* advancing the queue position (the actual advance
        happens in the EOS handler).

        The URI is looked up in ``_uri_cache`` (populated by
        ``_prefetch_next_uri``) so no network I/O occurs on this thread.
        If the cache misses (e.g. the user skipped rapidly), gapless
        playback is skipped and the EOS handler falls back to normal
        track advancement.

        If crossfade is enabled, a fade-out is started on the current
        track's audio.
        """
        snap = self.queue.snapshot()
        next_index = snap.position + 1

        if next_index < len(snap.tracks):
            next_track = snap.tracks[next_index]
        elif snap.repeat_mode.value == "queue" and snap.tracks:
            next_track = snap.tracks[0]
        else:
            return

        # Look up the prefetched URI — never resolve synchronously here
        uri: Optional[str] = None
        if next_track.source_id:
            with self._cache_lock:
                uri = self._uri_cache.pop(next_track.source_id, None)

        # For local tracks the resolver just builds a file:// URI (no
        # network I/O), so it is safe to call synchronously as a fallback.
        if uri is None and next_track.source == Source.LOCAL:
            uri = self._resolve_uri(next_track)

        if uri is not None:
            self._pipeline.set_property("uri", uri)

        # Start fade-out if crossfade is enabled.
        if self._crossfade is not None and self._crossfade.enabled:
            self._crossfade.start_fade_out(self)

    # ------------------------------------------------------------------
    # Position polling
    # ------------------------------------------------------------------

    def _start_position_polling(self) -> None:
        """Begin emitting position-updated every 500 ms."""
        self._stop_position_polling()
        self._position_poll_id = GLib.timeout_add(500, self._poll_position)

    def _stop_position_polling(self) -> None:
        """Cancel the position polling timer."""
        if self._position_poll_id is not None:
            GLib.source_remove(self._position_poll_id)
            self._position_poll_id = None

    def _poll_position(self) -> bool:
        """Emit the current position and duration.

        Returns ``True`` to keep the timer running, ``False`` to stop.
        """
        if self._state != PlayerState.PLAYING:
            self._position_poll_id = None
            return False

        pos = self.get_position()
        dur = self.get_duration()
        if pos is not None and dur is not None:
            self.emit("position-updated", pos, dur)

        return True
