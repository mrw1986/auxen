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
        self._notification_service = None
        self._favorites_sync = None
        self._crossfade_service = None
        self._lastfm_service = None

        self.set_default_size(600, 700)

        page = Adw.PreferencesPage()
        page.set_icon_name("emblem-system-symbolic")
        page.set_title("Settings")

        page.add(self._build_library_group())
        page.add(self._build_playback_group())
        page.add(self._build_tidal_group())
        page.add(self._build_lastfm_group())
        page.add(self._build_about_group())

        self.add(page)

    # ---- Public API ----

    def set_services(
        self,
        db=None,
        tidal_provider=None,
        local_provider=None,
        player=None,
        notification_service=None,
        favorites_sync=None,
        crossfade_service=None,
        lastfm_service=None,
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
        notification_service:
            NotificationService for toggling desktop notifications.
        favorites_sync:
            FavoritesSyncService for syncing Tidal favourites.
        crossfade_service:
            CrossfadeService for crossfade transitions.
        lastfm_service:
            LastFmService for Last.fm scrobbling.
        """
        self._db = db
        self._tidal_provider = tidal_provider
        self._local_provider = local_provider
        self._player = player
        self._notification_service = notification_service
        self._favorites_sync = favorites_sync
        self._crossfade_service = crossfade_service
        self._lastfm_service = lastfm_service

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

        # Import Playlist (M3U) button
        import_row = Adw.ActionRow(
            title="Import Playlist (M3U)",
            subtitle="Import a playlist from an M3U or M3U8 file",
        )
        self._import_btn = Gtk.Button(
            icon_name="document-open-symbolic",
            valign=Gtk.Align.CENTER,
        )
        self._import_btn.add_css_class("flat")
        self._import_btn.connect("clicked", self._on_import_playlist)
        import_row.add_suffix(self._import_btn)
        import_row.set_activatable_widget(self._import_btn)
        group.add(import_row)

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

        # Track notifications
        self._notifications = Adw.SwitchRow(
            title="Show Track Notifications",
            subtitle="Display a notification when the track changes",
        )
        self._notifications.set_active(True)
        self._notifications.connect(
            "notify::active", self._on_notifications_toggled
        )
        group.add(self._notifications)

        # Crossfade
        self._crossfade_switch = Adw.SwitchRow(
            title="Crossfade",
            subtitle="Smooth volume transition between tracks",
        )
        self._crossfade_switch.set_active(False)
        self._crossfade_switch.connect(
            "notify::active", self._on_crossfade_toggled
        )
        group.add(self._crossfade_switch)

        # Crossfade duration
        self._crossfade_duration_row = Adw.ActionRow(
            title="Crossfade Duration",
        )
        self._crossfade_scale = Gtk.Scale.new_with_range(
            Gtk.Orientation.HORIZONTAL, 1.0, 12.0, 0.5
        )
        self._crossfade_scale.set_value(5.0)
        self._crossfade_scale.set_hexpand(True)
        self._crossfade_scale.set_size_request(200, -1)
        self._crossfade_scale.set_valign(Gtk.Align.CENTER)
        self._crossfade_scale.set_draw_value(True)
        self._crossfade_scale.set_value_pos(Gtk.PositionType.RIGHT)
        self._crossfade_scale.connect(
            "value-changed", self._on_crossfade_duration_changed
        )
        self._crossfade_duration_row.add_suffix(self._crossfade_scale)
        group.add(self._crossfade_duration_row)

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

        # Sync Tidal Favorites button
        sync_row = Adw.ActionRow(
            title="Sync Tidal Favorites",
            subtitle="Two-way sync between local and Tidal favorites",
        )
        self._sync_btn = Gtk.Button(
            icon_name="emblem-synchronizing-symbolic",
            valign=Gtk.Align.CENTER,
        )
        self._sync_btn.add_css_class("flat")
        self._sync_btn.connect("clicked", self._on_sync_favorites)
        sync_row.add_suffix(self._sync_btn)
        sync_row.set_activatable_widget(self._sync_btn)
        group.add(sync_row)

        return group

    # ── Last.fm ──────────────────────────────────────────

    def _build_lastfm_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Last.fm")

        # Account status
        self._lastfm_account_row = Adw.ActionRow(
            title="Account",
            subtitle="Not connected",
        )
        self._lastfm_connect_btn = Gtk.Button(
            label="Connect",
            valign=Gtk.Align.CENTER,
        )
        self._lastfm_connect_btn.add_css_class("settings-login-btn")
        self._lastfm_connect_btn.connect(
            "clicked", self._on_lastfm_connect
        )
        self._lastfm_account_row.add_suffix(self._lastfm_connect_btn)
        group.add(self._lastfm_account_row)

        # Enable scrobbling toggle
        self._lastfm_scrobble_switch = Adw.SwitchRow(
            title="Enable Scrobbling",
            subtitle="Track your listening history on Last.fm",
        )
        self._lastfm_scrobble_switch.set_active(False)
        self._lastfm_scrobble_switch.connect(
            "notify::active", self._on_lastfm_scrobble_toggled
        )
        group.add(self._lastfm_scrobble_switch)

        return group

    # ── About ────────────────────────────────────────────

    def _build_about_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="About")

        about_row = Adw.ActionRow(
            title="About Auxen",
            subtitle="Version, credits, and license information",
        )
        about_row.set_icon_name("help-about-symbolic")
        about_btn = Gtk.Button(
            icon_name="go-next-symbolic",
            valign=Gtk.Align.CENTER,
        )
        about_btn.add_css_class("flat")
        about_btn.connect("clicked", self._on_open_about)
        about_row.add_suffix(about_btn)
        about_row.set_activatable_widget(about_btn)
        group.add(about_row)

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

        try:
            # Load notification preference
            notif_raw = self._db.get_setting("notifications_enabled")
            if notif_raw is not None:
                self._notifications.set_active(notif_raw == "1")
        except Exception:
            logger.warning(
                "Failed to load notifications_enabled", exc_info=True
            )

        try:
            # Load crossfade enabled
            cf_enabled = self._db.get_setting("crossfade_enabled")
            if cf_enabled is not None:
                self._crossfade_switch.set_active(cf_enabled == "1")
        except Exception:
            logger.warning(
                "Failed to load crossfade_enabled", exc_info=True
            )

        try:
            # Load crossfade duration
            cf_duration = self._db.get_setting("crossfade_duration")
            if cf_duration is not None:
                self._crossfade_scale.set_value(float(cf_duration))
        except Exception:
            logger.warning(
                "Failed to load crossfade_duration", exc_info=True
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

        # Check Last.fm status
        if self._lastfm_service is not None:
            try:
                if self._lastfm_service.is_authenticated():
                    username = self._lastfm_service.username or "Connected"
                    self._lastfm_account_row.set_subtitle(
                        f"Connected as {username}"
                    )
                    self._lastfm_connect_btn.set_label("Disconnect")
                self._lastfm_scrobble_switch.set_active(
                    self._lastfm_service.enabled
                )
            except Exception:
                logger.warning(
                    "Failed to check Last.fm status", exc_info=True
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

    def _on_notifications_toggled(self, row: Adw.SwitchRow, _pspec) -> None:
        """Persist the notification toggle and update the service."""
        active = row.get_active()
        if self._db is not None:
            try:
                self._db.set_setting(
                    "notifications_enabled", "1" if active else "0"
                )
            except Exception:
                logger.warning(
                    "Failed to save notifications_enabled", exc_info=True
                )
        if self._notification_service is not None:
            self._notification_service.set_enabled(active)

    def _on_crossfade_toggled(self, row: Adw.SwitchRow, _pspec) -> None:
        """Persist and apply crossfade enabled/disabled."""
        enabled = row.get_active()
        if self._db is not None:
            try:
                self._db.set_setting(
                    "crossfade_enabled", "1" if enabled else "0"
                )
            except Exception:
                logger.warning(
                    "Failed to save crossfade_enabled", exc_info=True
                )
        if self._crossfade_service is not None:
            self._crossfade_service.set_enabled(enabled)

    def _on_crossfade_duration_changed(self, scale: Gtk.Scale) -> None:
        """Persist and apply crossfade duration change."""
        value = scale.get_value()
        if self._db is not None:
            try:
                self._db.set_setting("crossfade_duration", str(value))
            except Exception:
                logger.warning(
                    "Failed to save crossfade_duration", exc_info=True
                )
        if self._crossfade_service is not None:
            self._crossfade_service.set_duration(value)

    def _on_open_equalizer(self, _button: Gtk.Button) -> None:
        """Open the equalizer dialog via the parent window."""
        parent = self.get_transient_for()
        if parent is not None and hasattr(parent, "open_equalizer"):
            parent.open_equalizer()

    def _on_open_about(self, _button: Gtk.Button) -> None:
        """Open the About dialog."""
        from auxen.views.about_dialog import show_about_dialog

        parent = self.get_transient_for()
        show_about_dialog(parent or self)

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

    # ── Last.fm handlers ───────────────────────────────────

    def _on_lastfm_connect(self, _button: Gtk.Button) -> None:
        """Start or manage the Last.fm connection flow."""
        if self._lastfm_service is None:
            return

        try:
            if self._lastfm_service.is_authenticated():
                # Disconnect
                self._lastfm_service.disconnect()
                self._lastfm_account_row.set_subtitle("Not connected")
                self._lastfm_connect_btn.set_label("Connect")
                return
        except Exception:
            pass

        # Show auth URL and prompt for token
        try:
            url = self._lastfm_service.get_auth_url()
            self._lastfm_account_row.set_subtitle(
                f"Visit: {url}"
            )
            self._lastfm_connect_btn.set_sensitive(False)
            self._show_lastfm_token_dialog(url)
        except Exception:
            logger.warning("Failed to start Last.fm auth", exc_info=True)
            self._lastfm_account_row.set_subtitle("Auth failed")

    def _show_lastfm_token_dialog(self, url: str) -> None:
        """Show a dialog prompting the user to enter their Last.fm auth token."""
        dialog = Adw.MessageDialog.new(
            self,
            "Connect to Last.fm",
            (
                "1. Visit the URL shown below and authorize Auxen.\n"
                "2. Copy the token from the URL after authorization.\n"
                "3. Paste it below and click Connect.\n\n"
                f"{url}"
            ),
        )
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("connect", "Connect")
        dialog.set_response_appearance(
            "connect", Adw.ResponseAppearance.SUGGESTED
        )

        # Add a text entry to the dialog's extra child area
        entry = Gtk.Entry()
        entry.set_placeholder_text("Paste token here")
        entry.set_hexpand(True)
        dialog.set_extra_child(entry)

        dialog.connect(
            "response",
            self._on_lastfm_token_response,
            entry,
        )
        dialog.present()

    def _on_lastfm_token_response(
        self, dialog, response: str, entry: Gtk.Entry
    ) -> None:
        """Handle the token dialog response."""
        self._lastfm_connect_btn.set_sensitive(True)

        if response != "connect":
            self._lastfm_account_row.set_subtitle("Not connected")
            return

        token = entry.get_text().strip()
        if not token:
            self._lastfm_account_row.set_subtitle("Not connected")
            return

        # Complete auth in background thread
        self._lastfm_account_row.set_subtitle("Authenticating...")
        self._lastfm_connect_btn.set_sensitive(False)

        def _auth_thread() -> None:
            success = False
            try:
                success = self._lastfm_service.complete_auth(token)
            except Exception:
                logger.warning(
                    "Last.fm auth failed", exc_info=True
                )
            GLib.idle_add(self._on_lastfm_auth_complete, success)

        thread = threading.Thread(target=_auth_thread, daemon=True)
        thread.start()

    def _on_lastfm_auth_complete(self, success: bool) -> bool:
        """Update the UI after Last.fm auth completes."""
        self._lastfm_connect_btn.set_sensitive(True)
        if success and self._lastfm_service is not None:
            username = self._lastfm_service.username or "Connected"
            self._lastfm_account_row.set_subtitle(
                f"Connected as {username}"
            )
            self._lastfm_connect_btn.set_label("Disconnect")
        else:
            self._lastfm_account_row.set_subtitle("Auth failed")
        return False

    def _on_lastfm_scrobble_toggled(
        self, row: Adw.SwitchRow, _pspec
    ) -> None:
        """Persist the Last.fm scrobbling toggle."""
        active = row.get_active()
        if self._lastfm_service is not None:
            self._lastfm_service.set_enabled(active)

    def _on_sync_favorites(self, _button: Gtk.Button) -> None:
        """Trigger a two-way Tidal favorites sync."""
        if self._favorites_sync is None:
            return

        self._sync_btn.set_sensitive(False)

        def _on_result(result) -> None:
            self._sync_btn.set_sensitive(True)
            parts = []
            if result.added_local:
                parts.append(f"{result.added_local} added locally")
            if result.added_tidal:
                parts.append(f"{result.added_tidal} added to Tidal")
            if result.already_synced:
                parts.append(f"{result.already_synced} already synced")
            if result.errors:
                parts.append(f"{len(result.errors)} error(s)")
            summary = ", ".join(parts) if parts else "Nothing to sync"
            logger.info("Favorites sync result: %s", summary)

        self._favorites_sync.sync_async(_on_result)

    def _on_import_playlist(self, _button: Gtk.Button) -> None:
        """Open a file dialog to import an M3U/M3U8 playlist."""
        if self._db is None:
            return

        try:
            dialog = Gtk.FileDialog()
            dialog.set_title("Import M3U Playlist")

            # Set up M3U file filter
            m3u_filter = Gtk.FileFilter()
            m3u_filter.set_name("M3U Playlists (*.m3u, *.m3u8)")
            m3u_filter.add_pattern("*.m3u")
            m3u_filter.add_pattern("*.m3u8")
            dialog.set_default_filter(m3u_filter)

            dialog.open(
                self,
                None,
                self._on_import_file_selected,
            )
        except Exception:
            logger.warning(
                "Failed to open import playlist dialog", exc_info=True
            )

    def _on_import_file_selected(self, dialog, result) -> None:
        """Handle the M3U file selection result."""
        try:
            selected = dialog.open_finish(result)
            if selected is None:
                return
            filepath = selected.get_path()
            if not filepath or self._db is None:
                return

            from auxen.m3u import M3UService

            svc = M3UService()
            tracks = svc.import_playlist(filepath, self._db)

            if not tracks:
                logger.info(
                    "No matching tracks found in %s", filepath
                )
                return

            # Create a new playlist named after the file (without extension)
            playlist_name = Path(filepath).stem
            playlist_id = self._db.create_playlist(playlist_name)

            for track in tracks:
                if track.id is not None:
                    self._db.add_track_to_playlist(playlist_id, track.id)

            logger.info(
                "Imported playlist '%s' with %d tracks from %s",
                playlist_name,
                len(tracks),
                filepath,
            )
        except GLib.Error:
            # User cancelled — normal
            pass
        except Exception:
            logger.warning(
                "Failed to import playlist", exc_info=True
            )
