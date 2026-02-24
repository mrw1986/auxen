"""Auxen application entry point — initializes all backend services."""

from __future__ import annotations

import json
import logging
import threading
import time

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from pathlib import Path

from gi.repository import Adw, Gdk, Gio, GLib, Gtk

from auxen.window import AuxenWindow

logger = logging.getLogger(__name__)


class AuxenApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id="io.github.auxen.Auxen")

        # Backend services — initialised in do_startup()
        self.db = None
        self.player = None
        self.local_provider = None
        self.tidal_provider = None
        self.mpris = None
        self.sleep_timer = None
        self.notification_service = None
        self.favorites_sync = None
        self.smart_playlist_service = None
        self.crossfade_service = None
        self.lastfm_service = None

        # Play history tracking
        self._current_track_start: float | None = None
        self._previous_track_id: int | None = None

        # Last.fm scrobble tracking
        self._scrobble_start_time: float | None = None
        self._scrobble_track = None

    # ------------------------------------------------------------------
    # Startup
    # ------------------------------------------------------------------

    def do_startup(self) -> None:
        # Temporarily suppress the specific Adwaita warning about
        # gtk-application-prefer-dark-theme (user's settings.ini may
        # enable it; we use AdwStyleManager instead).  Only the known
        # warning is suppressed — other Adwaita warnings pass through.
        def _suppress_dark_theme_warning(
            _domain: str,
            _level: GLib.LogLevelFlags,
            message: str,
            *_args,
        ) -> None:
            if "prefer-dark-theme" not in (message or ""):
                GLib.log_default_handler("Adwaita", _level, message)

        _handler = GLib.log_set_handler(
            "Adwaita",
            GLib.LogLevelFlags.LEVEL_WARNING,
            _suppress_dark_theme_warning,
            None,
        )

        try:
            Adw.Application.do_startup(self)
        finally:
            # Always restore normal Adwaita warning logging
            GLib.log_remove_handler("Adwaita", _handler)

        # Color scheme is applied after DB init — see below

        # --- Actions ---
        quit_action = Gio.SimpleAction.new("quit", None)
        quit_action.connect("activate", lambda *_: self.quit())
        self.add_action(quit_action)
        self.set_accels_for_action("app.quit", ["<Control>q"])

        settings_action = Gio.SimpleAction.new("settings", None)
        settings_action.connect("activate", self._on_settings_action)
        self.add_action(settings_action)
        self.set_accels_for_action("app.settings", ["<primary>comma"])

        eq_action = Gio.SimpleAction.new("equalizer", None)
        eq_action.connect("activate", self._on_equalizer_action)
        self.add_action(eq_action)
        self.set_accels_for_action("app.equalizer", ["<Control>e"])

        # --- Playback shortcuts ---
        play_pause_action = Gio.SimpleAction.new("play-pause", None)
        play_pause_action.connect("activate", self._on_play_pause_action)
        self.add_action(play_pause_action)
        self.set_accels_for_action(
            "app.play-pause", ["space", "AudioPlay"]
        )

        next_action = Gio.SimpleAction.new("next-track", None)
        next_action.connect("activate", self._on_next_action)
        self.add_action(next_action)
        self.set_accels_for_action(
            "app.next-track", ["n", "AudioNext"]
        )

        prev_action = Gio.SimpleAction.new("previous-track", None)
        prev_action.connect("activate", self._on_previous_action)
        self.add_action(prev_action)
        self.set_accels_for_action(
            "app.previous-track", ["p", "AudioPrev"]
        )

        stop_action = Gio.SimpleAction.new("stop", None)
        stop_action.connect("activate", self._on_stop_action)
        self.add_action(stop_action)
        self.set_accels_for_action("app.stop", ["AudioStop"])

        vol_up_action = Gio.SimpleAction.new("volume-up", None)
        vol_up_action.connect("activate", self._on_volume_up_action)
        self.add_action(vol_up_action)
        self.set_accels_for_action("app.volume-up", ["plus", "equal"])

        vol_down_action = Gio.SimpleAction.new("volume-down", None)
        vol_down_action.connect("activate", self._on_volume_down_action)
        self.add_action(vol_down_action)
        self.set_accels_for_action("app.volume-down", ["minus"])

        mute_action = Gio.SimpleAction.new("mute-toggle", None)
        mute_action.connect("activate", self._on_mute_action)
        self.add_action(mute_action)
        self.set_accels_for_action("app.mute-toggle", ["m"])

        # --- Navigation shortcuts ---
        nav_home_action = Gio.SimpleAction.new("nav-home", None)
        nav_home_action.connect("activate", self._on_nav_home_action)
        self.add_action(nav_home_action)
        self.set_accels_for_action("app.nav-home", ["<Control>1"])

        nav_search_action = Gio.SimpleAction.new("nav-search", None)
        nav_search_action.connect("activate", self._on_nav_search_action)
        self.add_action(nav_search_action)
        self.set_accels_for_action(
            "app.nav-search", ["<Control>2"]
        )

        nav_library_action = Gio.SimpleAction.new("nav-library", None)
        nav_library_action.connect("activate", self._on_nav_library_action)
        self.add_action(nav_library_action)
        self.set_accels_for_action("app.nav-library", ["<Control>3"])

        focus_search_action = Gio.SimpleAction.new("focus-search", None)
        focus_search_action.connect(
            "activate", self._on_focus_search_action
        )
        self.add_action(focus_search_action)
        self.set_accels_for_action("app.focus-search", ["<Control>f"])

        lyrics_action = Gio.SimpleAction.new("toggle-lyrics", None)
        lyrics_action.connect("activate", self._on_toggle_lyrics_action)
        self.add_action(lyrics_action)
        self.set_accels_for_action("app.toggle-lyrics", ["<Control>l"])

        queue_action = Gio.SimpleAction.new("toggle-queue", None)
        queue_action.connect("activate", self._on_toggle_queue_action)
        self.add_action(queue_action)
        self.set_accels_for_action("app.toggle-queue", ["<Control>k"])

        mini_player_action = Gio.SimpleAction.new(
            "toggle-mini-player", None
        )
        mini_player_action.connect(
            "activate", self._on_toggle_mini_player_action
        )
        self.add_action(mini_player_action)
        self.set_accels_for_action(
            "app.toggle-mini-player", ["<Control>m"]
        )

        sleep_timer_action = Gio.SimpleAction.new("sleep-timer", None)
        sleep_timer_action.connect(
            "activate", self._on_sleep_timer_action
        )
        self.add_action(sleep_timer_action)
        self.set_accels_for_action("app.sleep-timer", ["<Control>t"])

        about_action = Gio.SimpleAction.new("about", None)
        about_action.connect("activate", self._on_about_action)
        self.add_action(about_action)
        self.set_accels_for_action("app.about", ["<Control>question"])

        sync_favorites_action = Gio.SimpleAction.new(
            "sync-tidal-favorites", None
        )
        sync_favorites_action.connect(
            "activate", self._on_sync_favorites_action
        )
        self.add_action(sync_favorites_action)

        # --- CSS ---
        css_provider = Gtk.CssProvider()
        css_path = Path(__file__).resolve().parent.parent / "data" / "style.css"
        css_provider.load_from_path(str(css_path))
        display = Gdk.Display.get_default()
        if display is not None:
            Gtk.StyleContext.add_provider_for_display(
                display,
                css_provider,
                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1,
            )

            # Register custom icon search path so tidal-symbolic is found
            icon_theme = Gtk.IconTheme.get_for_display(display)
            icon_dir = str(
                Path(__file__).resolve().parent.parent / "data" / "icons"
            )
            icon_theme.add_search_path(icon_dir)

        # --- Database ---
        try:
            from auxen.db import Database

            self.db = Database()
        except Exception:
            logger.warning("Failed to initialize database", exc_info=True)

        # --- Color scheme (preference-driven, default: dark) ---
        style_manager = Adw.StyleManager.get_default()
        color_scheme = Adw.ColorScheme.FORCE_DARK
        if self.db is not None:
            try:
                pref = self.db.get_setting("color_scheme", "dark")
                scheme_map = {
                    "light": Adw.ColorScheme.FORCE_LIGHT,
                    "dark": Adw.ColorScheme.FORCE_DARK,
                    "system": Adw.ColorScheme.DEFAULT,
                }
                color_scheme = scheme_map.get(pref, Adw.ColorScheme.FORCE_DARK)
            except Exception:
                logger.warning("Failed to load color scheme preference", exc_info=True)
        style_manager.set_color_scheme(color_scheme)

        # --- Smart Playlist Service ---
        if self.db is not None:
            try:
                from auxen.smart_playlists import SmartPlaylistService

                self.smart_playlist_service = SmartPlaylistService(
                    db=self.db
                )
            except Exception:
                logger.warning(
                    "Failed to initialize smart playlist service",
                    exc_info=True,
                )

        # --- Player ---
        try:
            gi.require_version("Gst", "1.0")
            from auxen.player import Player

            self.player = Player()
        except Exception:
            logger.warning(
                "Failed to initialize GStreamer player", exc_info=True
            )

        # --- Local Provider ---
        try:
            from auxen.providers.local import LocalProvider

            self.local_provider = LocalProvider(self._get_music_dirs())
        except Exception:
            logger.warning(
                "Failed to initialize local provider", exc_info=True
            )

        # --- Tidal Provider ---
        try:
            from auxen.providers.tidal import TidalProvider

            self.tidal_provider = TidalProvider()
            try:
                self.tidal_provider.restore_session()
            except Exception:
                logger.warning(
                    "Failed to restore Tidal session", exc_info=True
                )
        except Exception:
            logger.warning(
                "Failed to initialize Tidal provider", exc_info=True
            )

        # --- Favorites Sync Service ---
        if self.db is not None and self.tidal_provider is not None:
            try:
                from auxen.favorites_sync import FavoritesSyncService

                self.favorites_sync = FavoritesSyncService(
                    db=self.db,
                    tidal_provider=self.tidal_provider,
                )
            except Exception:
                logger.warning(
                    "Failed to initialize favorites sync service",
                    exc_info=True,
                )

        # --- URI resolver ---
        if self.player is not None:
            self.player.set_uri_resolver(self._resolve_uri)

        # --- Crossfade Service ---
        try:
            from auxen.crossfade import CrossfadeService

            self.crossfade_service = CrossfadeService()
            if self.player is not None:
                self.player.set_crossfade_service(self.crossfade_service)
        except Exception:
            logger.warning(
                "Failed to initialize crossfade service", exc_info=True
            )

        # --- Apply stored crossfade settings ---
        if self.crossfade_service is not None and self.db is not None:
            try:
                cf_enabled_raw = self.db.get_setting(
                    "crossfade_enabled", "0"
                )
                cf_enabled = cf_enabled_raw == "1"
                self.crossfade_service.set_enabled(cf_enabled)

                cf_duration_raw = self.db.get_setting(
                    "crossfade_duration", "5.0"
                )
                try:
                    self.crossfade_service.set_duration(
                        float(cf_duration_raw)
                    )
                except (ValueError, TypeError):
                    pass
            except Exception:
                logger.warning(
                    "Failed to apply stored crossfade settings",
                    exc_info=True,
                )

        # --- MPRIS ---
        try:
            from auxen.mpris import MprisService

            self.mpris = MprisService(
                app_id="io.github.auxen.Auxen", app=self
            )
            self._wire_mpris()
        except Exception:
            logger.warning(
                "Failed to initialize MPRIS service", exc_info=True
            )

        # --- Sleep Timer ---
        try:
            from auxen.sleep_timer import SleepTimer

            self.sleep_timer = SleepTimer(
                on_expire=self._on_sleep_timer_expire,
                on_tick=self._on_sleep_timer_tick,
                on_fade_step=self._on_sleep_timer_fade,
            )
        except Exception:
            logger.warning(
                "Failed to initialize sleep timer", exc_info=True
            )

        # --- Notification Service ---
        try:
            from auxen.notifications import NotificationService

            self.notification_service = NotificationService(app=self)
            # Load persisted enabled state from database
            if self.db is not None:
                raw = self.db.get_setting("notifications_enabled")
                if raw is not None:
                    self.notification_service.set_enabled(raw == "1")
        except Exception:
            logger.warning(
                "Failed to initialize notification service", exc_info=True
            )

        # --- Last.fm Service ---
        if self.db is not None:
            try:
                from auxen.lastfm import LastFmService

                self.lastfm_service = LastFmService(db=self.db)
            except Exception:
                logger.warning(
                    "Failed to initialize Last.fm service", exc_info=True
                )

        # --- Player signal handlers ---
        if self.player is not None:
            self.player.connect("track-changed", self._on_track_changed)
            self.player.connect("state-changed", self._on_state_changed)
            self.player.connect(
                "position-updated", self._on_position_updated
            )

        # --- Apply stored ReplayGain settings ---
        if self.player is not None and self.db is not None:
            try:
                rg_enabled_raw = self.db.get_setting(
                    "replaygain_enabled", "1"
                )
                rg_enabled = rg_enabled_raw != "0"
                self.player.set_replaygain_enabled(rg_enabled)

                rg_mode = self.db.get_setting(
                    "replaygain_mode", "album"
                )
                if rg_mode in ("album", "track"):
                    self.player.set_replaygain_mode(rg_mode)
            except Exception:
                logger.warning(
                    "Failed to apply stored ReplayGain settings",
                    exc_info=True,
                )

    # ------------------------------------------------------------------
    # Activate
    # ------------------------------------------------------------------

    def do_activate(self) -> None:
        win = self.get_active_window()
        if not win:
            win = AuxenWindow(application=self)
            win.wire_services(self)
        win.present()

        # Trigger initial library scan in the background
        GLib.idle_add(self._initial_scan)

        # Trigger Tidal favorites sync in the background
        GLib.idle_add(self._initial_favorites_sync)

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _get_music_dirs(self) -> list[str]:
        """Read music directories from the database, with a sensible default."""
        default = [str(Path.home() / "Music")]
        if self.db is None:
            return default
        try:
            raw = self.db.get_setting("music_dirs")
            if raw:
                dirs = json.loads(raw)
                if isinstance(dirs, list) and dirs:
                    return [str(d) for d in dirs]
        except Exception:
            logger.warning(
                "Failed to read music_dirs setting", exc_info=True
            )
        return default

    def _resolve_uri(self, track) -> str | None:
        """Convert a Track to a playable URI using the appropriate provider."""
        try:
            if track.is_local and self.local_provider is not None:
                return self.local_provider.get_stream_uri(track)
            if track.is_tidal and self.tidal_provider is not None:
                return self.tidal_provider.get_stream_uri(track)
        except Exception:
            logger.warning(
                "Failed to resolve URI for track: %s", track.title,
                exc_info=True,
            )
        return None

    def _initial_scan(self) -> bool:
        """Scan local directories in a background thread, then update the UI."""
        if self.local_provider is None or self.db is None:
            return False  # Don't repeat

        def _scan_thread() -> None:
            try:
                tracks = self.local_provider.scan()
                with self.db.batch() as db:
                    for track in tracks:
                        track_id = db.insert_track(track)
                        # Also record the local file mapping
                        if track.source_id:
                            import os

                            try:
                                stat = os.stat(track.source_id)
                                db.insert_local_file(
                                    track_id=track_id,
                                    file_path=track.source_id,
                                    file_size=stat.st_size,
                                    file_modified_at=str(stat.st_mtime),
                                )
                            except OSError:
                                pass
                logger.info("Scanned %d local tracks", len(tracks))
            except Exception:
                logger.warning("Initial scan failed", exc_info=True)
            finally:
                # Refresh the home page on the main thread
                GLib.idle_add(self._refresh_home)

        thread = threading.Thread(target=_scan_thread, daemon=True)
        thread.start()
        return False  # Don't repeat

    def _refresh_home(self) -> bool:
        """Refresh the home page with data from the database."""
        win = self.get_active_window()
        if win is not None and self.db is not None:
            try:
                win.refresh_home(self.db)
            except Exception:
                logger.warning("Failed to refresh home page", exc_info=True)
        return False  # Don't repeat

    def _initial_favorites_sync(self) -> bool:
        """Trigger a Tidal favorites sync in the background on startup."""
        if self.favorites_sync is None:
            return False

        def _on_sync_result(result) -> None:
            logger.info(
                "Tidal favorites sync: added_local=%d, added_tidal=%d, "
                "already_synced=%d",
                result.added_local,
                result.added_tidal,
                result.already_synced,
            )
            if result.errors:
                for err in result.errors:
                    logger.warning("Favorites sync error: %s", err)

        self.favorites_sync.sync_async(_on_sync_result)
        return False  # Don't repeat

    def _on_sync_favorites_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        """Handle the manual sync-tidal-favorites action."""
        if self.favorites_sync is None:
            return

        def _on_result(result) -> None:
            logger.info(
                "Manual Tidal favorites sync: added_local=%d, "
                "added_tidal=%d, already_synced=%d",
                result.added_local,
                result.added_tidal,
                result.already_synced,
            )

        self.favorites_sync.sync_async(_on_result)

    # ------------------------------------------------------------------
    # MPRIS wiring
    # ------------------------------------------------------------------

    def _wire_mpris(self) -> None:
        """Connect MPRIS callbacks to the player."""
        if self.mpris is None or self.player is None:
            return

        self.mpris.on_play = self.player.play
        self.mpris.on_pause = self.player.pause
        self.mpris.on_stop = self.player.stop
        self.mpris.on_next = self.player.next_track
        self.mpris.on_previous = self.player.previous_track

        def _mpris_seek(position_us: int) -> None:
            if self.player is not None:
                self.player.seek(position_us / 1_000_000)

        self.mpris.on_seek = _mpris_seek

        def _mpris_raise() -> None:
            win = self.get_active_window()
            if win is not None:
                win.present()

        self.mpris.on_raise = _mpris_raise

        def _mpris_volume(vol: float) -> None:
            if self.player is not None:
                self.player.volume = vol

        self.mpris.on_volume_changed = _mpris_volume

        def _mpris_loop(status: str) -> None:
            if self.player is None:
                return
            from auxen.queue import RepeatMode

            mpris_to_repeat = {
                "None": RepeatMode.OFF,
                "Track": RepeatMode.TRACK,
                "Playlist": RepeatMode.QUEUE,
            }
            mode = mpris_to_repeat.get(status)
            if mode is not None:
                self.player.queue.repeat_mode = mode

        self.mpris.on_loop_changed = _mpris_loop

        def _mpris_shuffle(enabled: bool) -> None:
            if self.player is not None:
                if enabled:
                    self.player.queue.shuffle()
                else:
                    self.player.queue.unshuffle()

        self.mpris.on_shuffle_changed = _mpris_shuffle

    # ------------------------------------------------------------------
    # Player signal handlers
    # ------------------------------------------------------------------

    def _on_track_changed(self, _player, track) -> None:
        """Update MPRIS metadata and send notification when the track changes."""
        # Desktop notification
        if self.notification_service is not None and track is not None:
            try:
                self.notification_service.notify_track_change(
                    title=track.title,
                    artist=track.artist,
                    album=track.album or "",
                )
            except Exception:
                logger.warning(
                    "Failed to send track notification", exc_info=True
                )

        if self.mpris is not None and track is not None:
            try:
                track_id_path = (
                    f"/org/mpris/MediaPlayer2/Track/{track.id or 0}"
                )
                self.mpris.update_metadata(
                    track_id=track_id_path,
                    title=track.title,
                    artists=[track.artist],
                    album=track.album or "",
                    length_seconds=track.duration or 0,
                    art_url=track.album_art_url,
                )
            except Exception:
                logger.warning(
                    "Failed to update MPRIS metadata", exc_info=True
                )

        # Last.fm: scrobble the *previous* track if criteria are met
        if (
            self.lastfm_service is not None
            and self._scrobble_track is not None
            and self._scrobble_start_time is not None
        ):
            try:
                from auxen.lastfm import should_scrobble

                prev = self._scrobble_track
                play_secs = time.monotonic() - self._scrobble_start_time
                track_dur = prev.duration or 0
                if should_scrobble(play_secs, track_dur):
                    self.lastfm_service.scrobble(
                        title=prev.title,
                        artist=prev.artist,
                        album=prev.album or "",
                        duration=track_dur,
                        timestamp=int(
                            time.time() - play_secs
                        ),
                    )
            except Exception:
                logger.warning(
                    "Failed to scrobble to Last.fm", exc_info=True
                )

        # Record play history for the *previous* track
        if (
            self.db is not None
            and self._previous_track_id is not None
            and self._current_track_start is not None
        ):
            try:
                duration_listened = time.monotonic() - self._current_track_start
                self.db.record_play_history(
                    self._previous_track_id,
                    duration_listened=duration_listened,
                )
            except Exception:
                logger.warning(
                    "Failed to record play history", exc_info=True
                )

        # Record play in database (updates play_count / last_played_at)
        if self.db is not None and track is not None and track.id is not None:
            try:
                self.db.record_play(track.id)
            except Exception:
                logger.warning("Failed to record play", exc_info=True)

        # Last.fm: send now-playing for the *new* track
        if self.lastfm_service is not None and track is not None:
            try:
                self.lastfm_service.update_now_playing(
                    title=track.title,
                    artist=track.artist,
                    album=track.album or "",
                    duration=track.duration or 0,
                )
            except Exception:
                logger.warning(
                    "Failed to send Last.fm now-playing", exc_info=True
                )

        # Track the new track for play history duration calculation
        if track is not None and track.id is not None:
            self._previous_track_id = track.id
            self._current_track_start = time.monotonic()
        else:
            self._previous_track_id = None
            self._current_track_start = None

        # Track the new track for Last.fm scrobble duration
        if track is not None:
            self._scrobble_track = track
            self._scrobble_start_time = time.monotonic()
        else:
            self._scrobble_track = None
            self._scrobble_start_time = None

    def _on_state_changed(self, _player, state) -> None:
        """Update MPRIS playback status when the player state changes."""
        if self.mpris is not None:
            status_map = {
                "playing": "Playing",
                "paused": "Paused",
                "stopped": "Stopped",
            }
            mpris_status = status_map.get(state, "Stopped")
            try:
                self.mpris.update_playback_status(mpris_status)
            except Exception:
                logger.warning(
                    "Failed to update MPRIS playback status", exc_info=True
                )

    def _on_position_updated(self, _player, position, duration) -> None:
        """Update MPRIS position when the player reports progress."""
        if self.mpris is not None:
            try:
                self.mpris.update_position(int(position * 1_000_000))
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Settings action
    # ------------------------------------------------------------------

    def _on_settings_action(self, _action: Gio.SimpleAction, _param) -> None:
        win = self.props.active_window
        if win:
            win._open_settings()

    def _on_equalizer_action(self, _action: Gio.SimpleAction, _param) -> None:
        win = self.props.active_window
        if win:
            win.open_equalizer()

    # ------------------------------------------------------------------
    # Playback shortcut handlers
    # ------------------------------------------------------------------

    def _on_play_pause_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        """Play/pause — skip if focus is on a text entry widget."""
        win = self.props.active_window
        if win is not None:
            focus = win.get_focus()
            if isinstance(focus, (Gtk.Entry, Gtk.SearchEntry, Gtk.Text)):
                return
        if self.player is not None:
            self.player.play_pause()

    def _on_next_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            focus = win.get_focus()
            if isinstance(focus, (Gtk.Entry, Gtk.SearchEntry, Gtk.Text)):
                return
        if self.player is not None:
            self.player.next_track()

    def _on_previous_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            focus = win.get_focus()
            if isinstance(focus, (Gtk.Entry, Gtk.SearchEntry, Gtk.Text)):
                return
        if self.player is not None:
            self.player.previous_track()

    def _on_stop_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        if self.player is not None:
            self.player.stop()

    def _on_volume_up_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.adjust_volume(5.0)

    def _on_volume_down_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.adjust_volume(-5.0)

    def _on_mute_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            focus = win.get_focus()
            if isinstance(focus, (Gtk.Entry, Gtk.SearchEntry, Gtk.Text)):
                return
            win.toggle_mute()

    # ------------------------------------------------------------------
    # Navigation shortcut handlers
    # ------------------------------------------------------------------

    def _on_nav_home_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.navigate_to("home")

    def _on_nav_search_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.focus_search()

    def _on_nav_library_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.navigate_to("library")

    def _on_focus_search_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.focus_search()

    def _on_toggle_lyrics_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.toggle_lyrics_panel()

    def _on_toggle_queue_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.toggle_queue_panel()

    def _on_toggle_mini_player_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is None:
            # If no active window, try to get any window (mini player
            # may be visible while main window is hidden)
            windows = self.get_windows()
            for w in windows:
                if hasattr(w, "toggle_mini_player"):
                    w.toggle_mini_player()
                    return
        else:
            if hasattr(win, "toggle_mini_player"):
                win.toggle_mini_player()

    def _on_sleep_timer_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        win = self.props.active_window
        if win is not None:
            win.open_sleep_timer()

    def _on_about_action(
        self, _action: Gio.SimpleAction, _param
    ) -> None:
        from auxen.views.about_dialog import show_about_dialog

        win = self.props.active_window
        if win is not None:
            show_about_dialog(win)

    # ------------------------------------------------------------------
    # Sleep timer callbacks
    # ------------------------------------------------------------------

    def _on_sleep_timer_expire(self) -> None:
        """Pause playback when the sleep timer expires."""
        if self.player is not None:
            self.player.pause()
        # Update the window indicator.
        win = self.get_active_window()
        if win is not None:
            win.set_sleep_timer_active(False)

    def _on_sleep_timer_tick(self, remaining_seconds: int) -> None:
        """Forward timer tick to the active window for UI updates."""
        win = self.get_active_window()
        if win is not None:
            win.on_sleep_timer_tick(remaining_seconds)

    def _on_sleep_timer_fade(self, volume_fraction: float) -> None:
        """Adjust player volume during fade-out."""
        if self.player is not None:
            self.player.volume = volume_fraction * 0.7
