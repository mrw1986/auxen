"""Tidal Mixes page -- personalized mixes and user playlists."""

from __future__ import annotations

import logging
import threading
from typing import Any, Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gio, GLib, Gtk, Pango

from auxen.views.context_menu import Gdk_Rectangle
from auxen.views.widgets import DragScrollHelper, make_tidal_connect_prompt, make_tidal_source_badge

logger = logging.getLogger(__name__)


class MixesView(Gtk.ScrolledWindow):
    """Scrollable page showing Tidal personalized mixes and user playlists."""

    __gtype_name__ = "MixesView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        self._tidal_provider: Any = None
        self._on_play_mix: Optional[Callable] = None
        self._on_login: Optional[Callable] = None
        self._refresh_generation: int = 0
        self._current_menu: Optional[Gtk.PopoverMenu] = None
        self._menu_action_group: Optional[Gio.SimpleActionGroup] = None
        self._menu_parent: Optional[Gtk.Widget] = None

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

        tidal_badge = make_tidal_source_badge(
            label_text="TIDAL",
            css_class="nav-badge-tidal",
            icon_size=12,
        )
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
        self._mixes_grid.set_min_children_per_line(1)
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
        self._playlists_grid.set_min_children_per_line(1)
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

        # ---- 5. Empty state (shown when connected but no mixes found) ----
        self._empty_state = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._empty_state.add_css_class("mixes-empty-state")
        self._empty_state.set_visible(False)
        self._empty_state.set_margin_top(48)
        self._empty_state.set_margin_bottom(48)

        empty_icon = Gtk.Image.new_from_icon_name(
            "media-playlist-shuffle-symbolic"
        )
        empty_icon.set_pixel_size(64)
        empty_icon.set_opacity(0.4)
        self._empty_state.append(empty_icon)

        empty_heading = Gtk.Label(label="No Mixes Available")
        empty_heading.add_css_class("title-2")
        self._empty_state.append(empty_heading)

        empty_desc = Gtk.Label(
            label="No mixes available yet. Listen to more music\n"
            "and Tidal will create personalized mixes for you."
        )
        empty_desc.add_css_class("dim-label")
        empty_desc.set_justify(Gtk.Justification.CENTER)
        self._empty_state.append(empty_desc)

        self._root.append(self._empty_state)

        # ---- Error state (shown on fetch failure) ----
        self._error_state_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._error_state_box.set_visible(False)
        self._error_state_box.set_margin_top(48)
        self._error_state_box.set_margin_bottom(48)

        error_icon = Gtk.Image.new_from_icon_name(
            "dialog-warning-symbolic"
        )
        error_icon.set_pixel_size(64)
        error_icon.set_opacity(0.4)
        self._error_state_box.append(error_icon)

        error_heading = Gtk.Label(label="Unable to load mixes")
        error_heading.add_css_class("title-2")
        self._error_state_box.append(error_heading)

        error_desc = Gtk.Label(
            label="A network or Tidal API error occurred.\n"
            "Check your connection and try again."
        )
        error_desc.add_css_class("dim-label")
        error_desc.set_justify(Gtk.Justification.CENTER)
        self._error_state_box.append(error_desc)

        retry_btn = Gtk.Button(label="Retry")
        retry_btn.set_halign(Gtk.Align.CENTER)
        retry_btn.add_css_class("suggested-action")
        retry_btn.connect("clicked", lambda _btn: self.refresh())
        self._error_state_box.append(retry_btn)

        self._root.append(self._error_state_box)

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
            Called with (tidal_id, name, is_playlist) when a mix/playlist
            card is clicked.  *is_playlist* is ``True`` for user
            playlists, ``False`` for personalized mixes.
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
        # Bump generation before auth check to invalidate in-flight fetches.
        self._refresh_generation += 1

        if (
            self._tidal_provider is None
            or not self._tidal_provider.is_logged_in
        ):
            self._show_login_state()
            return

        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(True)
        self._empty_state.set_visible(False)
        self._error_state_box.set_visible(False)

        gen = self._refresh_generation
        thread = threading.Thread(
            target=self._fetch_content_thread, args=(gen,), daemon=True
        )
        thread.start()

    # ------------------------------------------------------------------
    # Internal: build widgets
    # ------------------------------------------------------------------

    def _build_login_prompt(self) -> Gtk.Box:
        """Build the login prompt card shown when Tidal is not connected."""
        return make_tidal_connect_prompt(
            css_class="mixes-login-prompt",
            icon_name="tidal-symbolic",
            heading_text="Connect to Tidal",
            description_text=(
                "Log in to your Tidal account to see your\n"
                "personalized mixes and playlists."
            ),
            button_text="Log In to Tidal",
            on_login_clicked=self._on_login_clicked_cb,
        )

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
        art_box.set_vexpand(False)

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

        # Let pointer events pass through to the FlowBoxChild
        text_overlay.set_can_target(False)
        art_box.set_can_target(False)
        overlay.set_can_target(False)
        card.set_can_target(False)

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
        art_box.set_vexpand(False)

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
        badge = make_tidal_source_badge(
            label_text="Playlist",
            css_class="source-badge-tidal",
            icon_size=10,
        )
        badge.set_halign(Gtk.Align.END)
        badge.set_valign(Gtk.Align.START)
        badge.set_margin_top(8)
        badge.set_margin_end(8)
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

        # Let pointer events pass through to the FlowBoxChild
        text_overlay.set_can_target(False)
        badge.set_can_target(False)
        art_box.set_can_target(False)
        overlay.set_can_target(False)
        card.set_can_target(False)

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
        self._empty_state.set_visible(False)
        self._error_state_box.set_visible(False)

    def _show_content_state(self) -> None:
        """Show the content area and hide the login prompt."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(True)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(False)
        self._error_state_box.set_visible(False)

    def _show_empty_state(self) -> None:
        """Show the empty state when connected but no mixes are available."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(True)
        self._error_state_box.set_visible(False)

    def _show_error_state(self) -> None:
        """Show an error state with retry button on fetch failure."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(False)
        self._error_state_box.set_visible(True)

    def _fetch_content_thread(self, gen: int) -> None:
        """Fetch mixes and playlists from Tidal in a background thread."""
        if self._tidal_provider is None:
            GLib.idle_add(self._show_login_state)
            return

        try:
            mixes = self._tidal_provider.get_mixes(limit=12)
            playlists = self._tidal_provider.get_user_playlists(limit=20)

            if gen != self._refresh_generation:
                return

            def _apply_results() -> bool:
                if gen != self._refresh_generation:
                    return False
                self._populate_mixes(mixes)
                self._populate_playlists(playlists)
                self._show_content_state()

                has_mixes = len(mixes) > 0
                self._mixes_header.set_visible(has_mixes)
                self._mixes_grid.set_visible(has_mixes)

                has_playlists = len(playlists) > 0
                self._playlists_header.set_visible(has_playlists)
                self._playlists_grid.set_visible(has_playlists)

                if not has_mixes and not has_playlists:
                    self._show_empty_state()
                return False

            GLib.idle_add(_apply_results)

        except Exception:
            logger.warning(
                "Failed to fetch mixes content", exc_info=True
            )
            if gen == self._refresh_generation:
                GLib.idle_add(self._show_error_state)

    def _populate_mixes(self, mixes: list[dict]) -> None:
        """Fill the mixes grid with mix cards."""
        self._clear_flow_box(self._mixes_grid)
        for mix in mixes:
            child = self._make_mix_card(
                name=mix.get("name", "Mix"),
                description=mix.get("description", ""),
                tidal_id=mix.get("tidal_id", ""),
                track_count=mix.get("track_count", 0),
            )
            child._mix_is_playlist = False  # type: ignore[attr-defined]
            self._attach_mix_context_gesture(child)
            self._mixes_grid.append(child)

    def _populate_playlists(self, playlists: list[dict]) -> None:
        """Fill the playlists grid with playlist cards."""
        self._clear_flow_box(self._playlists_grid)
        for pl in playlists:
            child = self._make_playlist_card(
                name=pl.get("name", "Playlist"),
                description=pl.get("description", ""),
                tidal_id=pl.get("tidal_id", ""),
                track_count=pl.get("track_count", 0),
            )
            child._mix_is_playlist = True  # type: ignore[attr-defined]
            self._attach_mix_context_gesture(child)
            self._playlists_grid.append(child)

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_login_clicked_cb(self) -> None:
        """Handle login button click (no-arg callback for shared prompt)."""
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
        is_playlist = getattr(child, "_mix_is_playlist", False)
        if (
            tidal_id is not None
            and name is not None
            and self._on_play_mix is not None
        ):
            self._on_play_mix(tidal_id, name, is_playlist)

    def _on_playlist_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle a playlist card click."""
        tidal_id = getattr(child, "_mix_tidal_id", None)
        name = getattr(child, "_mix_name", None)
        is_playlist = getattr(child, "_mix_is_playlist", True)
        if (
            tidal_id is not None
            and name is not None
            and self._on_play_mix is not None
        ):
            self._on_play_mix(tidal_id, name, is_playlist)

    # ------------------------------------------------------------------
    # Right-click context menu
    # ------------------------------------------------------------------

    def _attach_mix_context_gesture(self, child: Gtk.FlowBoxChild) -> None:
        """Attach a right-click gesture to a mix/playlist FlowBoxChild."""
        gesture = Gtk.GestureClick.new()
        gesture.set_button(3)  # Right-click
        gesture.connect("pressed", self._on_card_right_click, child)
        child.add_controller(gesture)

    def _on_card_right_click(
        self,
        gesture: Gtk.GestureClick,
        _n_press: int,
        x: float,
        y: float,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Show a context menu with 'Play' when right-clicking a card."""
        tidal_id = getattr(child, "_mix_tidal_id", None)
        name = getattr(child, "_mix_name", None)
        if tidal_id is None or name is None:
            return

        # Clean up any existing menu
        self._cleanup_current_menu()

        # Build menu model
        menu = Gio.Menu()
        section = Gio.Menu()
        section.append("Play", "mixctx.play")
        menu.append_section(None, section)

        # Build action group
        action_group = Gio.SimpleActionGroup()
        play_action = Gio.SimpleAction.new("play", None)

        is_playlist = getattr(child, "_mix_is_playlist", True)

        def _on_play(_action, _param):
            if self._on_play_mix is not None:
                self._on_play_mix(tidal_id, name, is_playlist)

        play_action.connect("activate", _on_play)
        action_group.add_action(play_action)

        # Create and show popover
        popover = Gtk.PopoverMenu.new_from_model(menu)
        popover.set_parent(child)
        popover.set_has_arrow(False)
        popover.add_css_class("context-menu")

        rect = Gdk_Rectangle(x, y)
        popover.set_pointing_to(rect)

        self._menu_action_group = action_group
        self._menu_parent = child
        child.insert_action_group("mixctx", action_group)

        popover.connect("closed", self._on_menu_closed, child)
        self._current_menu = popover
        popover.popup()

    def _on_menu_closed(
        self,
        popover: Gtk.PopoverMenu,
        child: Gtk.Widget,
    ) -> None:
        """Clean up action group and popover when context menu closes.

        Defers action group removal so menu-item activate signals fire first.
        """
        if popover is self._current_menu:
            GLib.idle_add(
                self._deferred_remove_action_group, child, popover
            )
        GLib.idle_add(self._deferred_cleanup_popover, popover)

    def _deferred_remove_action_group(
        self, widget: Gtk.Widget, expected_popover: Gtk.PopoverMenu
    ) -> bool:
        """Remove action group on idle, after actions have fired."""
        if (
            expected_popover is not self._current_menu
            and self._current_menu is not None
        ):
            return GLib.SOURCE_REMOVE
        try:
            widget.insert_action_group("mixctx", None)
        except Exception:
            pass
        return GLib.SOURCE_REMOVE

    def _deferred_cleanup_popover(
        self, popover: Gtk.PopoverMenu
    ) -> bool:
        """Unparent the popover on idle."""
        if popover is self._current_menu:
            if self._current_menu.get_parent() is not None:
                self._current_menu.unparent()
            self._current_menu = None
        elif popover is not None and popover.get_parent() is not None:
            popover.unparent()
        return GLib.SOURCE_REMOVE

    def _cleanup_current_menu(self) -> None:
        """Tear down any existing context menu popover."""
        if self._current_menu is not None:
            if self._menu_parent is not None:
                self._menu_parent.insert_action_group("mixctx", None)
            if self._current_menu.get_parent() is not None:
                self._current_menu.unparent()
            self._current_menu = None
            self._menu_action_group = None
            self._menu_parent = None

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
