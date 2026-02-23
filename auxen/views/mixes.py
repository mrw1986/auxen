"""Tidal Mixes page -- personalized mixes and user playlists."""

from __future__ import annotations

import logging
from typing import Any, Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import GLib, Gtk, Pango

logger = logging.getLogger(__name__)


class MixesView(Gtk.ScrolledWindow):
    """Scrollable page showing Tidal personalized mixes and user playlists."""

    __gtype_name__ = "MixesView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        self._tidal_provider: Any = None
        self._on_play_mix: Optional[Callable] = None
        self._on_login: Optional[Callable] = None

        # Root container
        self._root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        self._root.set_margin_top(24)
        self._root.set_margin_bottom(24)
        self._root.set_margin_start(32)
        self._root.set_margin_end(32)

        # ---- 1. Header ----
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header_box.add_css_class("mixes-header")

        title_label = Gtk.Label(label="Your Mixes")
        title_label.set_xalign(0)
        title_label.add_css_class("greeting-label")
        header_box.append(title_label)

        tidal_badge = Gtk.Label(label="TIDAL")
        tidal_badge.add_css_class("nav-badge-tidal")
        tidal_badge.set_valign(Gtk.Align.CENTER)
        header_box.append(tidal_badge)

        self._root.append(header_box)

        # ---- 2. Login prompt (shown when not connected) ----
        self._login_prompt = self._build_login_prompt()
        self._root.append(self._login_prompt)

        # ---- 3. Content area (shown when connected) ----
        self._content_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        self._content_box.set_visible(False)

        # -- Mixes section --
        self._mixes_header = Gtk.Label(label="Personalized Mixes")
        self._mixes_header.set_xalign(0)
        self._mixes_header.add_css_class("section-header")
        self._content_box.append(self._mixes_header)

        self._mixes_grid = Gtk.FlowBox()
        self._mixes_grid.set_homogeneous(True)
        self._mixes_grid.set_min_children_per_line(2)
        self._mixes_grid.set_max_children_per_line(5)
        self._mixes_grid.set_column_spacing(16)
        self._mixes_grid.set_row_spacing(16)
        self._mixes_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._mixes_grid.connect(
            "child-activated", self._on_mix_card_activated
        )
        self._content_box.append(self._mixes_grid)

        # -- User Playlists section --
        self._playlists_header = Gtk.Label(label="Your Tidal Playlists")
        self._playlists_header.set_xalign(0)
        self._playlists_header.add_css_class("section-header")
        self._content_box.append(self._playlists_header)

        self._playlists_grid = Gtk.FlowBox()
        self._playlists_grid.set_homogeneous(True)
        self._playlists_grid.set_min_children_per_line(2)
        self._playlists_grid.set_max_children_per_line(5)
        self._playlists_grid.set_column_spacing(16)
        self._playlists_grid.set_row_spacing(16)
        self._playlists_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._playlists_grid.connect(
            "child-activated", self._on_playlist_card_activated
        )
        self._content_box.append(self._playlists_grid)

        self._root.append(self._content_box)

        # ---- 4. Loading spinner ----
        self._spinner_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._spinner_box.set_visible(False)
        self._spinner_box.set_margin_top(48)

        spinner = Gtk.Spinner()
        spinner.set_size_request(32, 32)
        spinner.start()
        self._spinner_box.append(spinner)

        loading_label = Gtk.Label(label="Loading your mixes from Tidal...")
        loading_label.add_css_class("dim-label")
        self._spinner_box.append(loading_label)

        self._root.append(self._spinner_box)

        self.set_child(self._root)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_tidal_provider(self, tidal_provider: Any) -> None:
        """Wire the Tidal provider for fetching mixes content."""
        self._tidal_provider = tidal_provider

    def set_callbacks(
        self,
        on_play_mix: Optional[Callable] = None,
        on_login: Optional[Callable] = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_play_mix:
            Called with (tidal_id, name) when a mix/playlist card is
            clicked.
        on_login:
            Called when the user clicks the login button.
        """
        self._on_play_mix = on_play_mix
        self._on_login = on_login

    def refresh(self) -> None:
        """Reload content from Tidal.

        Shows the login prompt if not connected, otherwise fetches
        personalized mixes and user playlists.
        """
        if (
            self._tidal_provider is None
            or not self._tidal_provider.is_logged_in
        ):
            self._show_login_state()
            return

        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(True)

        # Fetch data in an idle callback to avoid blocking the UI
        GLib.idle_add(self._fetch_content)

    # ------------------------------------------------------------------
    # Internal: build widgets
    # ------------------------------------------------------------------

    def _build_login_prompt(self) -> Gtk.Box:
        """Build the login prompt card shown when Tidal is not connected."""
        prompt = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        prompt.add_css_class("mixes-login-prompt")
        prompt.set_margin_top(48)
        prompt.set_margin_bottom(48)

        icon = Gtk.Image.new_from_icon_name("media-playlist-shuffle-symbolic")
        icon.set_pixel_size(64)
        icon.set_opacity(0.4)
        prompt.append(icon)

        heading = Gtk.Label(label="Connect to Tidal")
        heading.add_css_class("title-1")
        prompt.append(heading)

        description = Gtk.Label(
            label="Log in to your Tidal account to see your\n"
            "personalized mixes and playlists."
        )
        description.add_css_class("dim-label")
        description.set_justify(Gtk.Justification.CENTER)
        prompt.append(description)

        login_btn = Gtk.Button(label="Log In to Tidal")
        login_btn.add_css_class("suggested-action")
        login_btn.add_css_class("pill")
        login_btn.set_halign(Gtk.Align.CENTER)
        login_btn.connect("clicked", self._on_login_clicked)
        prompt.append(login_btn)

        return prompt

    def _make_mix_card(
        self,
        name: str,
        description: str,
        tidal_id: str,
        track_count: int = 0,
    ) -> Gtk.FlowBoxChild:
        """Build a single mix card for the mixes grid."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        card.add_css_class("mix-card")

        # Art area with gradient overlay
        overlay = Gtk.Overlay()

        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.FILL,
            valign=Gtk.Align.FILL,
        )
        art_box.add_css_class("mix-card-art")
        art_box.set_size_request(200, 200)

        art_icon = Gtk.Image.new_from_icon_name(
            "media-playlist-shuffle-symbolic"
        )
        art_icon.set_pixel_size(56)
        art_icon.set_opacity(0.3)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        overlay.set_child(art_box)

        # Gradient overlay with text at bottom
        text_overlay = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
            valign=Gtk.Align.END,
        )
        text_overlay.add_css_class("mix-card-text-overlay")

        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(20)
        title_label.add_css_class("mix-card-title")
        text_overlay.append(title_label)

        # Description or track count
        desc_text = description
        if not desc_text and track_count > 0:
            desc_text = f"{track_count} tracks"
        if desc_text:
            desc_label = Gtk.Label(label=desc_text)
            desc_label.set_xalign(0)
            desc_label.set_ellipsize(Pango.EllipsizeMode.END)
            desc_label.set_max_width_chars(24)
            desc_label.add_css_class("mix-card-description")
            text_overlay.append(desc_label)

        overlay.add_overlay(text_overlay)
        card.append(overlay)

        child = Gtk.FlowBoxChild()
        child.set_child(card)
        # Store data for click handling
        child._mix_tidal_id = tidal_id  # type: ignore[attr-defined]
        child._mix_name = name  # type: ignore[attr-defined]
        return child

    def _make_playlist_card(
        self,
        name: str,
        description: str,
        tidal_id: str,
        track_count: int = 0,
    ) -> Gtk.FlowBoxChild:
        """Build a single playlist card for the playlists grid."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        card.add_css_class("mix-card")

        # Art area
        overlay = Gtk.Overlay()

        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.FILL,
            valign=Gtk.Align.FILL,
        )
        art_box.add_css_class("mix-card-art")
        art_box.add_css_class("mix-card-art-playlist")
        art_box.set_size_request(200, 200)

        art_icon = Gtk.Image.new_from_icon_name(
            "view-list-symbolic"
        )
        art_icon.set_pixel_size(56)
        art_icon.set_opacity(0.3)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        overlay.set_child(art_box)

        # Badge
        badge = Gtk.Label(label="Playlist")
        badge.add_css_class("source-badge-tidal")
        badge.set_halign(Gtk.Align.START)
        badge.set_valign(Gtk.Align.START)
        badge.set_margin_top(8)
        badge.set_margin_start(8)
        overlay.add_overlay(badge)

        # Text overlay
        text_overlay = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
            valign=Gtk.Align.END,
        )
        text_overlay.add_css_class("mix-card-text-overlay")

        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(20)
        title_label.add_css_class("mix-card-title")
        text_overlay.append(title_label)

        desc_text = description
        if not desc_text and track_count > 0:
            desc_text = f"{track_count} tracks"
        if desc_text:
            desc_label = Gtk.Label(label=desc_text)
            desc_label.set_xalign(0)
            desc_label.set_ellipsize(Pango.EllipsizeMode.END)
            desc_label.set_max_width_chars(24)
            desc_label.add_css_class("mix-card-description")
            text_overlay.append(desc_label)

        overlay.add_overlay(text_overlay)
        card.append(overlay)

        child = Gtk.FlowBoxChild()
        child.set_child(card)
        child._mix_tidal_id = tidal_id  # type: ignore[attr-defined]
        child._mix_name = name  # type: ignore[attr-defined]
        return child

    # ------------------------------------------------------------------
    # Internal: state management
    # ------------------------------------------------------------------

    def _show_login_state(self) -> None:
        """Show the login prompt and hide content."""
        self._login_prompt.set_visible(True)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(False)

    def _show_content_state(self) -> None:
        """Show the content area and hide the login prompt."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(True)
        self._spinner_box.set_visible(False)

    def _fetch_content(self) -> bool:
        """Fetch mixes and playlists from Tidal (runs in idle callback).

        Returns False to remove the idle source.
        """
        if self._tidal_provider is None:
            self._show_login_state()
            return False

        try:
            # Fetch personalized mixes
            mixes = self._tidal_provider.get_mixes(limit=12)
            self._populate_mixes(mixes)

            # Fetch user playlists
            playlists = self._tidal_provider.get_user_playlists(limit=20)
            self._populate_playlists(playlists)

            self._show_content_state()

            # Hide sections if empty
            has_mixes = len(mixes) > 0
            self._mixes_header.set_visible(has_mixes)
            self._mixes_grid.set_visible(has_mixes)

            has_playlists = len(playlists) > 0
            self._playlists_header.set_visible(has_playlists)
            self._playlists_grid.set_visible(has_playlists)

            # If both are empty, show a note
            if not has_mixes and not has_playlists:
                self._show_login_state()

        except Exception:
            logger.warning(
                "Failed to fetch mixes content", exc_info=True
            )
            self._show_login_state()

        return False

    def _populate_mixes(self, mixes: list[dict]) -> None:
        """Fill the mixes grid with mix cards."""
        self._clear_flow_box(self._mixes_grid)
        for mix in mixes:
            self._mixes_grid.append(
                self._make_mix_card(
                    name=mix.get("name", "Mix"),
                    description=mix.get("description", ""),
                    tidal_id=mix.get("tidal_id", ""),
                    track_count=mix.get("track_count", 0),
                )
            )

    def _populate_playlists(self, playlists: list[dict]) -> None:
        """Fill the playlists grid with playlist cards."""
        self._clear_flow_box(self._playlists_grid)
        for pl in playlists:
            self._playlists_grid.append(
                self._make_playlist_card(
                    name=pl.get("name", "Playlist"),
                    description=pl.get("description", ""),
                    tidal_id=pl.get("tidal_id", ""),
                    track_count=pl.get("track_count", 0),
                )
            )

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_login_clicked(self, _btn: Gtk.Button) -> None:
        """Handle login button click."""
        if self._on_login is not None:
            self._on_login()

    def _on_mix_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle a mix card click."""
        tidal_id = getattr(child, "_mix_tidal_id", None)
        name = getattr(child, "_mix_name", None)
        if (
            tidal_id is not None
            and name is not None
            and self._on_play_mix is not None
        ):
            self._on_play_mix(tidal_id, name)

    def _on_playlist_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle a playlist card click."""
        tidal_id = getattr(child, "_mix_tidal_id", None)
        name = getattr(child, "_mix_name", None)
        if (
            tidal_id is not None
            and name is not None
            and self._on_play_mix is not None
        ):
            self._on_play_mix(tidal_id, name)

    # ------------------------------------------------------------------
    # Utility
    # ------------------------------------------------------------------

    @staticmethod
    def _clear_flow_box(flow_box: Gtk.FlowBox) -> None:
        """Remove all children from a FlowBox."""
        while True:
            child = flow_box.get_child_at_index(0)
            if child is None:
                break
            flow_box.remove(child)
