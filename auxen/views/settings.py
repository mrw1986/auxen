"""Settings dialog for the Auxen music player."""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk


class AuxenSettings(Adw.PreferencesWindow):
    """Preferences window with library, playback, tidal, and about groups."""

    __gtype_name__ = "AuxenSettings"

    def __init__(self, **kwargs) -> None:
        super().__init__(
            title="Settings",
            search_enabled=False,
            **kwargs,
        )

        self.set_default_size(600, 700)

        page = Adw.PreferencesPage()
        page.set_icon_name("emblem-system-symbolic")
        page.set_title("Settings")

        page.add(self._build_library_group())
        page.add(self._build_playback_group())
        page.add(self._build_tidal_group())
        page.add(self._build_about_group())

        self.add(page)

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
        rescan_btn = Gtk.Button(
            icon_name="view-refresh-symbolic",
            valign=Gtk.Align.CENTER,
        )
        rescan_btn.add_css_class("flat")
        rescan_btn.add_css_class("settings-rescan-btn")
        rescan_btn.connect("clicked", self._on_rescan)
        rescan_row.add_suffix(rescan_btn)
        rescan_row.set_activatable_widget(rescan_btn)
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
        self._replaygain.set_active(False)
        group.add(self._replaygain)

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

    # ── Signal handlers (stubs) ──────────────────────────

    def _on_add_folder(self, _button: Gtk.Button) -> None:
        """Placeholder: open a file chooser to add a music folder."""

    def _on_rescan(self, _button: Gtk.Button) -> None:
        """Placeholder: trigger a library rescan."""

    def _on_tidal_login(self, _button: Gtk.Button) -> None:
        """Placeholder: start the Tidal OAuth login flow."""
