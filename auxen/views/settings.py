"""Settings dialog for the Auxen music player."""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gio, GLib, Gtk

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

        page.add(self._build_appearance_group())
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

    # ── Appearance ────────────────────────────────────────

    def _build_appearance_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Appearance")

        # Theme combo row: Dark, Light, System
        self._theme_row = Adw.ComboRow(
            title="Theme",
            subtitle="Choose light, dark, or follow system",
        )
        model = Gtk.StringList.new(["Dark", "Light", "System"])
        self._theme_row.set_model(model)
        self._theme_row.set_selected(0)  # Default: dark
        self._theme_row.connect(
            "notify::selected", self._on_theme_changed
        )
        group.add(self._theme_row)

        return group

    # ── Library ──────────────────────────────────────────

    def _build_library_group(self) -> Adw.PreferencesGroup:
        self._library_group = Adw.PreferencesGroup(title="Library")

        # Folder rows are built dynamically; placeholder list
        self._folder_rows: list[Adw.ActionRow] = []

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
        self._library_group.add(add_folder_row)

        group = self._library_group

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
        self._source_priority = Adw.ComboRow(
            title="Source Priority",
            subtitle="Choose which source to prefer when a track is available from multiple sources",
        )
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
        self._audio_quality = Adw.ComboRow(
            title="Tidal Audio Quality",
            subtitle="Streaming quality for Tidal tracks",
        )
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
        self._gapless = Adw.SwitchRow(
            title="Gapless Playback",
            subtitle="Eliminate silence between consecutive tracks",
        )
        self._gapless.set_active(True)
        group.add(self._gapless)

        # ReplayGain
        self._replaygain = Adw.SwitchRow(
            title="ReplayGain Normalization",
            subtitle="Normalize volume levels for consistent loudness",
        )
        self._replaygain.set_active(True)
        self._replaygain.connect(
            "notify::active", self._on_replaygain_toggled
        )
        group.add(self._replaygain)

        # ReplayGain mode
        self._replaygain_mode = Adw.ComboRow(
            title="ReplayGain Mode",
            subtitle="Adjusts playback volume to normalize loudness across tracks",
        )
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
        eq_row.set_icon_name("view-media-equalizer")
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

        # Auto-Sync Favorites toggle
        self._auto_sync_row = Adw.SwitchRow(
            title="Auto-Sync Favorites",
            subtitle="Automatically sync Tidal favorites every 5 minutes",
        )
        self._auto_sync_row.connect(
            "notify::active", self._on_auto_sync_toggled
        )
        group.add(self._auto_sync_row)

        # Sync Now button
        sync_row = Adw.ActionRow(
            title="Sync Now",
            subtitle="Trigger a manual two-way favorites sync",
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

        # Last sync time display
        self._last_sync_row = Adw.ActionRow(
            title="Last Sync",
            subtitle="Never",
        )
        group.add(self._last_sync_row)

        return group

    # ── Last.fm ──────────────────────────────────────────

    def _build_lastfm_group(self) -> Adw.PreferencesGroup:
        group = Adw.PreferencesGroup(title="Last.fm")

        # API Key entry
        self._lastfm_api_key_row = Adw.EntryRow(
            title="API Key",
        )
        self._lastfm_api_key_row.set_tooltip_text(
            "Register at https://www.last.fm/api/account/create"
        )
        # Save on focus-out instead of every keystroke to avoid
        # partial saves and session invalidation while typing.
        key_focus_ctrl = Gtk.EventControllerFocus()
        key_focus_ctrl.connect(
            "leave", self._on_lastfm_key_focus_out
        )
        self._lastfm_api_key_row.add_controller(key_focus_ctrl)
        group.add(self._lastfm_api_key_row)

        # API Secret entry
        self._lastfm_api_secret_row = Adw.PasswordEntryRow(
            title="Shared Secret",
        )
        self._lastfm_api_secret_row.set_tooltip_text(
            "The Shared Secret from your Last.fm API application"
        )
        secret_focus_ctrl = Gtk.EventControllerFocus()
        secret_focus_ctrl.connect(
            "leave", self._on_lastfm_secret_focus_out
        )
        self._lastfm_api_secret_row.add_controller(secret_focus_ctrl)
        group.add(self._lastfm_api_secret_row)

        # Help text for registering an API application
        self._lastfm_help_row = Adw.ActionRow(
            title="Get API credentials",
            subtitle="Register at last.fm/api/account/create",
        )
        self._lastfm_help_row.set_icon_name("dialog-information-symbolic")
        help_link_btn = Gtk.Button(
            icon_name="go-next-symbolic",
            valign=Gtk.Align.CENTER,
        )
        help_link_btn.add_css_class("flat")
        help_link_btn.connect("clicked", self._on_lastfm_help_clicked)
        self._lastfm_help_row.add_suffix(help_link_btn)
        self._lastfm_help_row.set_activatable_widget(help_link_btn)
        group.add(self._lastfm_help_row)

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

        self._loading_settings = True
        try:
            self._load_settings_inner()
        finally:
            self._loading_settings = False

    def _load_settings_inner(self) -> None:
        """Internal settings loader (guarded by _loading_settings flag)."""
        try:
            # Load color scheme / theme preference
            scheme = self._db.get_setting("color_scheme", "dark")
            scheme_to_idx = {"dark": 0, "light": 1, "system": 2}
            idx = scheme_to_idx.get(scheme, 0)
            self._theme_row.set_selected(idx)
        except Exception:
            logger.warning(
                "Failed to load color_scheme setting", exc_info=True
            )

        try:
            # Load music directories
            raw = self._db.get_setting("music_dirs")
            dirs: list[str] = []
            if raw:
                loaded = json.loads(raw)
                if isinstance(loaded, list):
                    dirs = loaded
            self._rebuild_folder_rows(dirs)
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

        # Check Tidal login status and fetch subscription info
        if self._tidal_provider is not None:
            try:
                if self._tidal_provider.is_logged_in:
                    self._account_row.set_subtitle("Connected")
                    self._login_btn.set_label("Log Out")
                    self._fetch_tidal_subscription()
            except Exception:
                logger.warning(
                    "Failed to check Tidal login status", exc_info=True
                )

        # Load Last.fm API credentials and status
        if self._lastfm_service is not None:
            try:
                # Populate API key/secret fields from the service
                api_key = self._lastfm_service.api_key
                api_secret = self._lastfm_service.api_secret
                if not self._lastfm_service.uses_default_credentials:
                    self._lastfm_api_key_row.set_text(api_key)
                    self._lastfm_api_secret_row.set_text(api_secret)

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

        # Load auto-sync toggle state
        try:
            if self._favorites_sync is not None:
                self._auto_sync_row.set_active(
                    self._favorites_sync.auto_sync_enabled
                )
            self._update_last_sync_display()
        except Exception:
            logger.warning(
                "Failed to load auto-sync settings", exc_info=True
            )

    # ── Signal handlers ──────────────────────────────────

    def _on_theme_changed(self, row: Adw.ComboRow, _pspec) -> None:
        """Persist and apply the selected color scheme."""
        if getattr(self, "_loading_settings", False):
            return
        idx = row.get_selected()
        # 0 = Dark, 1 = Light, 2 = System
        scheme_names = {0: "dark", 1: "light", 2: "system"}
        scheme_name = scheme_names.get(idx, "dark")

        if self._db is not None:
            try:
                self._db.set_setting("color_scheme", scheme_name)
            except Exception:
                logger.warning(
                    "Failed to save color_scheme", exc_info=True
                )

        # Apply immediately
        scheme_map = {
            "light": Adw.ColorScheme.FORCE_LIGHT,
            "dark": Adw.ColorScheme.FORCE_DARK,
            "system": Adw.ColorScheme.DEFAULT,
        }
        color_scheme = scheme_map.get(scheme_name, Adw.ColorScheme.FORCE_DARK)
        Adw.StyleManager.get_default().set_color_scheme(color_scheme)

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
                        self._rebuild_folder_rows(current_dirs)
                        logger.info("Added music folder: %s", folder_path)
        except GLib.Error:
            # User cancelled the dialog — this is normal
            pass
        except Exception:
            logger.warning("Failed to add folder", exc_info=True)

    def _rebuild_folder_rows(self, dirs: list[str]) -> None:
        """Remove existing folder rows and rebuild one per directory."""
        for row in self._folder_rows:
            self._library_group.remove(row)
        self._folder_rows.clear()

        for folder_path in dirs:
            row = Adw.ActionRow(title=folder_path)
            row.set_icon_name("folder-symbolic")
            remove_btn = Gtk.Button(
                icon_name="list-remove-symbolic",
                valign=Gtk.Align.CENTER,
                tooltip_text="Remove folder",
            )
            remove_btn.add_css_class("flat")
            remove_btn.connect(
                "clicked", self._on_remove_folder, folder_path
            )
            row.add_suffix(remove_btn)
            self._library_group.add(row)
            self._folder_rows.append(row)

    def _on_remove_folder(
        self, _button: Gtk.Button, folder_path: str
    ) -> None:
        """Remove *folder_path* from the stored music directories."""
        if self._db is None:
            return
        try:
            raw = self._db.get_setting("music_dirs")
            current_dirs: list[str] = []
            if raw:
                loaded = json.loads(raw)
                if isinstance(loaded, list):
                    current_dirs = loaded
            if folder_path in current_dirs:
                current_dirs.remove(folder_path)
                self._db.set_setting(
                    "music_dirs", json.dumps(current_dirs)
                )
                self._rebuild_folder_rows(current_dirs)
                logger.info("Removed music folder: %s", folder_path)
        except Exception:
            logger.warning("Failed to remove folder", exc_info=True)

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
                with self._db.batch() as db:
                    for track in tracks:
                        db.insert_track(track)
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

    def _fetch_tidal_subscription(self) -> None:
        """Fetch Tidal subscription info in a background thread and update the UI."""
        if self._tidal_provider is None:
            return

        def _fetch_thread() -> None:
            info = self._tidal_provider.get_subscription_info()
            GLib.idle_add(self._on_subscription_fetched, info)

        thread = threading.Thread(target=_fetch_thread, daemon=True)
        thread.start()

    def _on_subscription_fetched(self, info: dict) -> bool:
        """Update the subscription row with fetched info (runs on main thread)."""
        if not info:
            self._subscription_row.set_subtitle("\u2014")
            return False

        # Map API type values to user-friendly labels
        type_map = {
            "HIFI": "HiFi",
            "HIFI_PLUS": "HiFi Plus",
            "PREMIUM": "Premium",
            "FREE": "Free",
        }
        raw_type = info.get("type", "Unknown")
        display_type = type_map.get(raw_type, raw_type.replace("_", " ").title())

        # Map quality values to user-friendly labels
        quality_map = {
            "HI_RES_LOSSLESS": "Hi-Res Lossless",
            "HI_RES": "Hi-Res",
            "LOSSLESS": "Lossless",
            "HIGH": "High",
            "LOW": "Low",
        }
        raw_quality = info.get("quality", "")
        display_quality = quality_map.get(raw_quality, raw_quality)

        label = display_type
        if display_quality and display_quality != "Unknown":
            label += f" ({display_quality})"

        self._subscription_row.set_subtitle(label)
        return False

    def _on_tidal_login(self, _button: Gtk.Button) -> None:
        """Start or manage the Tidal login flow.

        For logout, handles it directly.  For login, delegates to the
        main window's proven ``_start_tidal_login()`` flow which uses an
        ``Adw.AlertDialog`` and ``Gtk.UriLauncher`` reliably.
        """
        if self._tidal_provider is None:
            return

        try:
            is_logged_in = self._tidal_provider.is_logged_in
        except Exception:
            is_logged_in = False

        if is_logged_in:
            try:
                self._tidal_provider.logout()
            except Exception:
                logger.warning("Tidal logout failed", exc_info=True)
                self._account_row.set_subtitle("Logout failed")
                return
            # Stop auto-sync polling on logout
            if self._favorites_sync is not None:
                self._favorites_sync.stop_polling()
            self._account_row.set_subtitle("Not connected")
            self._subscription_row.set_subtitle("\u2014")
            self._login_btn.set_label("Log In")
            parent = self.get_transient_for()
            if parent is not None:
                if hasattr(parent, "_update_sidebar_account"):
                    parent._update_sidebar_account()
                if hasattr(parent, "_refresh_tidal_views"):
                    parent._refresh_tidal_views()
            return

        # Delegate to the main window's proven login flow
        parent = self.get_transient_for()
        if parent is not None and hasattr(parent, "_start_tidal_login"):
            self.close()
            parent._start_tidal_login()

    # ── Last.fm handlers ───────────────────────────────────

    def _on_lastfm_key_focus_out(
        self, _controller: Gtk.EventControllerFocus
    ) -> None:
        """Save Last.fm credentials when the API key field loses focus."""
        if getattr(self, "_loading_settings", False):
            return
        self._save_lastfm_credentials()

    def _on_lastfm_secret_focus_out(
        self, _controller: Gtk.EventControllerFocus
    ) -> None:
        """Save Last.fm credentials when the secret field loses focus."""
        if getattr(self, "_loading_settings", False):
            return
        self._save_lastfm_credentials()

    def _save_lastfm_credentials(self) -> None:
        """Persist updated Last.fm API credentials to the service.

        Only saves when both fields are non-empty. Does NOT reset
        the existing session -- that only happens when the user
        explicitly disconnects or connects with new credentials.
        """
        if self._lastfm_service is None:
            return

        api_key = self._lastfm_api_key_row.get_text().strip()
        api_secret = self._lastfm_api_secret_row.get_text().strip()

        # Only save if both fields have content
        if api_key and api_secret:
            try:
                # Persist credentials without resetting the session.
                # The session is only invalidated when the user clicks
                # Connect/Disconnect explicitly.
                if self._lastfm_service._db is not None:
                    self._lastfm_service._db.set_setting(
                        "lastfm_api_key", api_key
                    )
                    self._lastfm_service._db.set_setting(
                        "lastfm_api_secret", api_secret
                    )
                self._lastfm_service._api_key = api_key
                self._lastfm_service._api_secret = api_secret
                logger.info("Last.fm API credentials saved")
            except Exception:
                logger.warning(
                    "Failed to save Last.fm credentials",
                    exc_info=True,
                )

    def _on_lastfm_help_clicked(self, _button: Gtk.Button) -> None:
        """Open the Last.fm API registration page in the default browser."""
        parent = self.get_transient_for() or self
        launcher = Gtk.UriLauncher.new(
            "https://www.last.fm/api/account/create"
        )
        launcher.launch(parent, None, None, None)

    def _on_lastfm_connect(self, _button: Gtk.Button) -> None:
        """Start or manage the Last.fm connection flow.

        Uses the proper Last.fm desktop auth flow:
        1. Request an auth token from the Last.fm API
        2. Open the browser with the token-based auth URL
        3. User authorizes in the browser
        4. Exchange the token for a session key
        """
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

        # Step 1: Validate the API key first
        self._lastfm_account_row.set_subtitle("Validating API key...")
        self._lastfm_connect_btn.set_sensitive(False)

        def _validate_and_get_token() -> None:
            valid, error_msg = self._lastfm_service.validate_api_key()
            if not valid:
                GLib.idle_add(
                    self._on_lastfm_validation_failed, error_msg
                )
                return

            # Step 2: Get an auth token
            token = self._lastfm_service.get_auth_token()
            if not token:
                GLib.idle_add(
                    self._on_lastfm_validation_failed,
                    "Failed to obtain auth token from Last.fm. "
                    "Check your API key and try again.",
                )
                return

            # Step 3: Build the auth URL with the token
            url = self._lastfm_service.get_auth_url(token)
            GLib.idle_add(self._show_lastfm_auth_dialog, url)

        thread = threading.Thread(
            target=_validate_and_get_token, daemon=True
        )
        thread.start()

    def _on_lastfm_validation_failed(self, error_msg: str) -> bool:
        """Show an error when API key validation fails."""
        self._lastfm_connect_btn.set_sensitive(True)
        self._lastfm_account_row.set_subtitle(error_msg)
        logger.warning("Last.fm API key validation failed: %s", error_msg)
        return False

    def _show_lastfm_auth_dialog(self, url: str) -> bool:
        """Show the Last.fm authorization dialog.

        Opens the authorization URL in the default browser and shows
        a dialog asking the user to confirm they authorized the app.
        The token exchange happens automatically since the token was
        already obtained via ``get_auth_token()``.
        """
        self._lastfm_connect_btn.set_sensitive(True)
        self._lastfm_account_row.set_subtitle("Waiting for authorization...")

        # Open the auth URL in the default browser
        parent = self.get_transient_for() or self
        launcher = Gtk.UriLauncher.new(url)
        launcher.launch(parent, None, None, None)

        dialog = Adw.MessageDialog.new(
            self,
            "Connect to Last.fm",
            (
                "A browser window has been opened to Last.fm.\n\n"
                "1. Authorize Auxen in the browser.\n"
                "2. Once authorized, click 'Complete' below.\n\n"
                "If the browser didn't open, click the link below:"
            ),
        )
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("complete", "Complete")
        dialog.set_response_appearance(
            "complete", Adw.ResponseAppearance.SUGGESTED
        )

        # Clickable link
        link_btn = Gtk.LinkButton.new_with_label(url, url)
        link_btn.set_halign(Gtk.Align.CENTER)
        dialog.set_extra_child(link_btn)

        dialog.connect(
            "response",
            self._on_lastfm_auth_dialog_response,
        )
        dialog.present()
        return False

    def _on_lastfm_auth_dialog_response(
        self, dialog, response: str
    ) -> None:
        """Handle the auth dialog response (complete or cancel)."""
        if response != "complete":
            self._lastfm_account_row.set_subtitle("Not connected")
            return

        # Exchange the pre-fetched token for a session key
        self._lastfm_account_row.set_subtitle("Authenticating...")
        self._lastfm_connect_btn.set_sensitive(False)

        def _auth_thread() -> None:
            success = False
            try:
                success = self._lastfm_service.complete_auth_from_token()
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
            self._lastfm_account_row.set_subtitle(
                "Auth failed - did you authorize in the browser?"
            )
        return False

    def _on_lastfm_scrobble_toggled(
        self, row: Adw.SwitchRow, _pspec
    ) -> None:
        """Persist the Last.fm scrobbling toggle."""
        active = row.get_active()
        if self._lastfm_service is not None:
            self._lastfm_service.set_enabled(active)

    def _on_auto_sync_toggled(
        self, row: Adw.SwitchRow, _pspec
    ) -> None:
        """Toggle automatic Tidal favorites syncing."""
        if getattr(self, "_loading_settings", False):
            return
        active = row.get_active()
        if self._favorites_sync is not None:
            self._favorites_sync.auto_sync_enabled = active

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
            # Update last sync time display
            self._update_last_sync_display()

        self._favorites_sync.sync_async(_on_result)

    def _update_last_sync_display(self) -> None:
        """Update the Last Sync row subtitle from the database."""
        if not hasattr(self, "_last_sync_row"):
            return
        if self._favorites_sync is None:
            return
        try:
            last_sync = self._favorites_sync.last_sync_time
            if last_sync:
                from datetime import datetime

                try:
                    dt = datetime.fromisoformat(last_sync)
                    display = dt.strftime("%Y-%m-%d %H:%M:%S UTC")
                except (ValueError, TypeError):
                    display = last_sync
                self._last_sync_row.set_subtitle(display)
            else:
                self._last_sync_row.set_subtitle("Never")
        except Exception:
            self._last_sync_row.set_subtitle("Unknown")

    def _on_import_playlist(self, _button: Gtk.Button) -> None:
        """Open a file dialog to import an M3U/M3U8 playlist."""
        if self._db is None:
            return

        try:
            dialog = Gtk.FileDialog()
            dialog.set_title("Import M3U Playlist")

            # Set up M3U file filter (must use ListStore for portal dialogs)
            m3u_filter = Gtk.FileFilter()
            m3u_filter.set_name("M3U Playlists (*.m3u, *.m3u8)")
            m3u_filter.add_pattern("*.m3u")
            m3u_filter.add_pattern("*.m3u8")

            filter_store = Gio.ListStore.new(Gtk.FileFilter)
            filter_store.append(m3u_filter)
            dialog.set_filters(filter_store)
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
