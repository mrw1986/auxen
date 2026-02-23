"""Settings dialog for the Auxen music player."""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, GLib, Gtk

logger = logging.getLogger(__name__)


class AuxenSettings(Adw.PreferencesWindow):
    """Preferences window with library, playback, tidal, and about groups."""

    __gtype_name__ = "AuxenSettings"

    def __init__(self, **kwargs) -> None:
        super().__init__(
            title="Settings",
            search_enabled=False,
            **kwargs,
        )

        self._db = None
        self._tidal_provider = None
        self._local_provider = None
        self._player = None

        self.set_default_size(600, 700)

        page = Adw.PreferencesPage()
        page.set_icon_name("emblem-system-symbolic")
        page.set_title("Settings")

        page.add(self._build_library_group())
        page.add(self._build_playback_group())
        page.add(self._build_tidal_group())
        page.add(self._build_about_group())

        self.add(page)

    # ---- Public API ----

    def set_services(
        self, db=None, tidal_provider=None, local_provider=None, player=None
    ) -> None:
        """Wire backend services to the settings dialog.

        Parameters
        ----------
        db:
            Database instance for reading/writing settings.
        tidal_provider:
            TidalProvider instance for Tidal auth.
        local_provider:
            LocalProvider instance for rescanning.
        player:
            Player instance for applying playback settings.
        """
        self._db = db
        self._tidal_provider = tidal_provider
        self._local_provider = local_provider
        self._player = player

        # Load current settings from database
        self._load_settings()

    # ── Library ──────────────────────────────────────────

    def _build_library_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Library")

        # Current music folder display
        music_dir = str(Path.home() / "Music")
        self._music_dir_row = Adw.ActionRow(
            title="Music Folder",
            subtitle=music_dir,
        )
        self._music_dir_row.set_icon_name("folder-music-symbolic")
        group.add(self._music_dir_row)

        # Add Folder button
        add_folder_row = Adw.ActionRow(title="Add Folder")
        add_folder_btn = Gtk.Button(
            icon_name="list-add-symbolic",
            valign=Gtk.Align.CENTER,
        )
        add_folder_btn.add_css_class("flat")
        add_folder_btn.connect("clicked", self._on_add_folder)
        add_folder_row.add_suffix(add_folder_btn)
        add_folder_row.set_activatable_widget(add_folder_btn)
        group.add(add_folder_row)

        # Rescan Library button
        rescan_row = Adw.ActionRow(title="Rescan Library")
        self._rescan_btn = Gtk.Button(
            icon_name="view-refresh-symbolic",
            valign=Gtk.Align.CENTER,
        )
        self._rescan_btn.add_css_class("flat")
        self._rescan_btn.add_css_class("settings-rescan-btn")
        self._rescan_btn.connect("clicked", self._on_rescan)
        rescan_row.add_suffix(self._rescan_btn)
        rescan_row.set_activatable_widget(self._rescan_btn)
        group.add(rescan_row)

        return group

    # ── Playback ─────────────────────────────────────────

    def _build_playback_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Playback")

        # Source priority
        self._source_priority = Adw.ComboRow(title="Source Priority")
        source_model = Gtk.StringList.new(
            [
                "Prefer Local",
                "Prefer Tidal",
                "Prefer Higher Quality",
                "Always Ask",
            ]
        )
        self._source_priority.set_model(source_model)
        self._source_priority.set_selected(0)
        group.add(self._source_priority)

        # Audio quality
        self._audio_quality = Adw.ComboRow(title="Tidal Audio Quality")
        quality_model = Gtk.StringList.new(
            [
                "Low (96 kbps)",
                "High (320 kbps)",
                "Lossless (FLAC)",
                "Hi-Res (up to 24-bit/192kHz)",
            ]
        )
        self._audio_quality.set_model(quality_model)
        self._audio_quality.set_selected(2)
        group.add(self._audio_quality)

        # Gapless playback
        self._gapless = Adw.SwitchRow(title="Gapless Playback")
        self._gapless.set_active(True)
        group.add(self._gapless)

        # ReplayGain
        self._replaygain = Adw.SwitchRow(title="ReplayGain Normalization")
        self._replaygain.set_active(True)
        self._replaygain.connect(
            "notify::active", self._on_replaygain_toggled
        )
        group.add(self._replaygain)

        # ReplayGain mode
        self._replaygain_mode = Adw.ComboRow(title="ReplayGain Mode")
        mode_model = Gtk.StringList.new(["Album", "Track"])
        self._replaygain_mode.set_model(mode_model)
        self._replaygain_mode.set_selected(0)
        self._replaygain_mode.connect(
            "notify::selected", self._on_replaygain_mode_changed
        )
        group.add(self._replaygain_mode)

        # Equalizer
        eq_row = Adw.ActionRow(
            title="Equalizer",
            subtitle="10-band graphic equalizer with presets",
        )
        eq_row.set_icon_name("media-eq-symbolic")
        eq_btn = Gtk.Button(
            icon_name="go-next-symbolic",
            valign=Gtk.Align.CENTER,
        )
        eq_btn.add_css_class("flat")
        eq_btn.connect("clicked", self._on_open_equalizer)
        eq_row.add_suffix(eq_btn)
        eq_row.set_activatable_widget(eq_btn)
        group.add(eq_row)

        return group

    # ── Tidal ────────────────────────────────────────────

    def _build_tidal_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Tidal")

        # Account status
        self._account_row = Adw.ActionRow(
            title="Account",
            subtitle="Not connected",
        )
        self._login_btn = Gtk.Button(
            label="Log In",
            valign=Gtk.Align.CENTER,
        )
        self._login_btn.add_css_class("settings-login-btn")
        self._login_btn.connect("clicked", self._on_tidal_login)
        self._account_row.add_suffix(self._login_btn)
        group.add(self._account_row)

        # Subscription info
        self._subscription_row = Adw.ActionRow(
            title="Subscription",
            subtitle="\u2014",
        )
        group.add(self._subscription_row)

        return group

    # ── About ────────────────────────────────────────────

    def _build_about_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="About")

        version_row = Adw.ActionRow(
            title="Version",
            subtitle="0.1.0",
        )
        group.add(version_row)

        credits_row = Adw.ActionRow(
            title="Created by",
            subtitle="mrw1986",
        )
        group.add(credits_row)

        license_row = Adw.ActionRow(
            title="License",
            subtitle="GPL-3.0",
        )
        group.add(license_row)

        return group

    # ── Settings loading ──────────────────────────────────

    def _load_settings(self) -> None:
        """Load current settings from the database."""
        if self._db is None:
            return

        try:
            # Load music directories
            raw = self._db.get_setting("music_dirs")
            if raw:
                dirs = json.loads(raw)
                if isinstance(dirs, list) and dirs:
                    self._music_dir_row.set_subtitle(", ".join(dirs))
        except Exception:
            logger.warning("Failed to load music_dirs setting", exc_info=True)

        try:
            # Load source priority
            priority = self._db.get_setting("source_priority")
            if priority is not None:
                priority_map = {
                    "prefer_local": 0,
                    "prefer_tidal": 1,
                    "prefer_quality": 2,
                    "always_ask": 3,
                }
                idx = priority_map.get(priority, 0)
                self._source_priority.set_selected(idx)
        except Exception:
            logger.warning("Failed to load source_priority", exc_info=True)

        try:
            # Load audio quality
            quality = self._db.get_setting("tidal_quality")
            if quality is not None:
                quality_map = {"low": 0, "high": 1, "lossless": 2, "hires": 3}
                idx = quality_map.get(quality, 2)
                self._audio_quality.set_selected(idx)
        except Exception:
            logger.warning("Failed to load tidal_quality", exc_info=True)

        try:
            # Load ReplayGain enabled
            rg_enabled = self._db.get_setting("replaygain_enabled")
            if rg_enabled is not None:
                self._replaygain.set_active(rg_enabled == "1")
        except Exception:
            logger.warning(
                "Failed to load replaygain_enabled", exc_info=True
            )

        try:
            # Load ReplayGain mode
            rg_mode = self._db.get_setting("replaygain_mode")
            if rg_mode is not None:
                mode_map = {"album": 0, "track": 1}
                idx = mode_map.get(rg_mode, 0)
                self._replaygain_mode.set_selected(idx)
        except Exception:
            logger.warning(
                "Failed to load replaygain_mode", exc_info=True
            )

        # Check Tidal login status
        if self._tidal_provider is not None:
            try:
                if self._tidal_provider.is_logged_in:
                    self._account_row.set_subtitle("Connected")
                    self._login_btn.set_label("Log Out")
            except Exception:
                logger.warning(
                    "Failed to check Tidal login status", exc_info=True
                )

    # ── Signal handlers ──────────────────────────────────

    def _on_add_folder(self, _button: Gtk.Button) -> None:
        """Open a file chooser to add a music folder."""
        try:
            dialog = Gtk.FileDialog()
            dialog.set_title("Select Music Folder")
            dialog.select_folder(
                self,
                None,
                self._on_folder_selected,
            )
        except Exception:
            logger.warning("Failed to open folder dialog", exc_info=True)

    def _on_folder_selected(self, dialog, result) -> None:
        """Handle the folder selection result."""
        try:
            folder = dialog.select_folder_finish(result)
            if folder is not None:
                folder_path = folder.get_path()
                if folder_path and self._db is not None:
                    # Read current dirs and add the new one
                    raw = self._db.get_setting("music_dirs")
                    current_dirs = []
                    if raw:
                        try:
                            current_dirs = json.loads(raw)
                        except (json.JSONDecodeError, TypeError):
                            pass
                    if not isinstance(current_dirs, list):
                        current_dirs = []

                    if folder_path not in current_dirs:
                        current_dirs.append(folder_path)
                        self._db.set_setting(
                            "music_dirs", json.dumps(current_dirs)
                        )
                        self._music_dir_row.set_subtitle(
                            ", ".join(current_dirs)
                        )
                        logger.info("Added music folder: %s", folder_path)
        except GLib.Error:
            # User cancelled the dialog — this is normal
            pass
        except Exception:
            logger.warning("Failed to add folder", exc_info=True)

    def _on_rescan(self, _button: Gtk.Button) -> None:
        """Trigger a library rescan in a background thread."""
        if self._local_provider is None or self._db is None:
            return

        self._rescan_btn.set_sensitive(False)

        def _rescan_thread() -> None:
            try:
                # Re-read music dirs in case they changed
                raw = self._db.get_setting("music_dirs")
                if raw:
                    try:
                        dirs = json.loads(raw)
                        if isinstance(dirs, list) and dirs:
                            self._local_provider._music_dirs = [
                                str(d) for d in dirs
                            ]
                    except (json.JSONDecodeError, TypeError):
                        pass

                tracks = self._local_provider.scan()
                for track in tracks:
                    self._db.insert_track(track)
                logger.info("Rescanned: found %d tracks", len(tracks))
            except Exception:
                logger.warning("Rescan failed", exc_info=True)
            finally:
                GLib.idle_add(self._on_rescan_complete)

        thread = threading.Thread(target=_rescan_thread, daemon=True)
        thread.start()

    def _on_rescan_complete(self) -> bool:
        """Re-enable the rescan button on the main thread."""
        self._rescan_btn.set_sensitive(True)
        return False

    def _on_replaygain_toggled(self, row: Adw.SwitchRow, _pspec) -> None:
        """Persist and apply ReplayGain enabled/disabled."""
        enabled = row.get_active()
        if self._db is not None:
            try:
                self._db.set_setting(
                    "replaygain_enabled", "1" if enabled else "0"
                )
            except Exception:
                logger.warning(
                    "Failed to save replaygain_enabled", exc_info=True
                )
        if self._player is not None:
            try:
                self._player.set_replaygain_enabled(enabled)
            except Exception:
                logger.warning(
                    "Failed to apply replaygain_enabled", exc_info=True
                )

    def _on_replaygain_mode_changed(
        self, row: Adw.ComboRow, _pspec
    ) -> None:
        """Persist and apply ReplayGain mode change."""
        idx = row.get_selected()
        mode = "album" if idx == 0 else "track"
        if self._db is not None:
            try:
                self._db.set_setting("replaygain_mode", mode)
            except Exception:
                logger.warning(
                    "Failed to save replaygain_mode", exc_info=True
                )
        if self._player is not None:
            try:
                self._player.set_replaygain_mode(mode)
            except Exception:
                logger.warning(
                    "Failed to apply replaygain_mode", exc_info=True
                )

    def _on_open_equalizer(self, _button: Gtk.Button) -> None:
        """Open the equalizer dialog via the parent window."""
        parent = self.get_transient_for()
        if parent is not None and hasattr(parent, "open_equalizer"):
            parent.open_equalizer()

    def _on_tidal_login(self, _button: Gtk.Button) -> None:
        """Start or manage the Tidal login flow."""
        if self._tidal_provider is None:
            return

        try:
            if self._tidal_provider.is_logged_in:
                # Log out
                self._tidal_provider.logout()
                self._account_row.set_subtitle("Not connected")
                self._login_btn.set_label("Log In")
                return
        except Exception:
            pass

        # Start login in a background thread
        self._login_btn.set_sensitive(False)
        self._account_row.set_subtitle("Authenticating...")

        def _login_thread() -> None:
            try:

                def _url_callback(url: str) -> None:
                    GLib.idle_add(
                        self._show_login_url, url
                    )

                success = self._tidal_provider.login(
                    url_callback=_url_callback
                )
                GLib.idle_add(self._on_login_complete, success)
            except Exception:
                logger.warning("Tidal login failed", exc_info=True)
                GLib.idle_add(self._on_login_complete, False)

        thread = threading.Thread(target=_login_thread, daemon=True)
        thread.start()

    def _show_login_url(self, url: str) -> bool:
        """Display the Tidal login URL to the user."""
        self._account_row.set_subtitle(f"Visit: {url}")
        return False

    def _on_login_complete(self, success: bool) -> bool:
        """Update the UI after Tidal login completes."""
        self._login_btn.set_sensitive(True)
        if success:
            self._account_row.set_subtitle("Connected")
            self._login_btn.set_label("Log Out")
        else:
            self._account_row.set_subtitle("Login failed")
        return False
