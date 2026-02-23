"""Auxen application entry point — initializes all backend services."""

from __future__ import annotations

import json
import logging
import threading

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from pathlib import Path

from gi.repository import Adw, Gio, GLib, Gtk

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

    # ------------------------------------------------------------------
    # Startup
    # ------------------------------------------------------------------

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)

        # --- Actions ---
        quit_action = Gio.SimpleAction.new("quit", None)
        quit_action.connect("activate", lambda *_: self.quit())
        self.add_action(quit_action)
        self.set_accels_for_action("app.quit", ["<Control>q"])

        settings_action = Gio.SimpleAction.new("settings", None)
        settings_action.connect("activate", self._on_settings_action)
        self.add_action(settings_action)
        self.set_accels_for_action("app.settings", ["<primary>comma"])

        # --- CSS ---
        css_provider = Gtk.CssProvider()
        css_path = Path(__file__).resolve().parent.parent / "data" / "style.css"
        css_provider.load_from_path(str(css_path))
        Gtk.StyleContext.add_provider_for_display(
            self.get_style_manager().get_display(),
            css_provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

        # --- Database ---
        try:
            from auxen.db import Database

            self.db = Database()
        except Exception:
            logger.warning("Failed to initialize database", exc_info=True)

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

        # --- URI resolver ---
        if self.player is not None:
            self.player.set_uri_resolver(self._resolve_uri)

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

        # --- Player signal handlers ---
        if self.player is not None:
            self.player.connect("track-changed", self._on_track_changed)
            self.player.connect("state-changed", self._on_state_changed)
            self.player.connect(
                "position-updated", self._on_position_updated
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
                for track in tracks:
                    track_id = self.db.insert_track(track)
                    # Also record the local file mapping
                    if track.source_id:
                        import os

                        try:
                            stat = os.stat(track.source_id)
                            self.db.insert_local_file(
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

    # ------------------------------------------------------------------
    # Player signal handlers
    # ------------------------------------------------------------------

    def _on_track_changed(self, _player, track) -> None:
        """Update MPRIS metadata when the track changes."""
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

        # Record play in database
        if self.db is not None and track is not None and track.id is not None:
            try:
                self.db.record_play(track.id)
            except Exception:
                logger.warning("Failed to record play", exc_info=True)

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
