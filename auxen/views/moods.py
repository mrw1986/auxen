"""Tidal Moods page -- mood-based browsing (chill, workout, focus, etc.)."""

from __future__ import annotations

import logging
import threading
from typing import Any, Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gdk, GLib, Gtk, Pango

from auxen.album_art import AlbumArtService
from auxen.views.widgets import (
    DragScrollHelper,
    HorizontalCarousel,
    make_tidal_connect_prompt,
    make_tidal_source_badge,
)

logger = logging.getLogger(__name__)


class MoodsView(Gtk.ScrolledWindow):
    """Scrollable page showing Tidal mood categories and their content."""

    __gtype_name__ = "MoodsView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        self._tidal_provider: Any = None
        self._album_art_service: Any = None
        self._on_play_playlist: Optional[Callable] = None
        self._on_album_clicked: Optional[Callable] = None
        self._on_login: Optional[Callable] = None
        self._refresh_generation: int = 0

        # Track whether we are showing the grid or a drilled-in mood
        self._showing_mood_detail = False
        self._current_mood_title: str = ""

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
        header_box.add_css_class("moods-header")

        self._title_label = Gtk.Label(label="Moods & Activities")
        self._title_label.set_xalign(0)
        self._title_label.add_css_class("greeting-label")
        self._title_label.set_hexpand(True)
        header_box.append(self._title_label)

        tidal_badge = make_tidal_source_badge(
            label_text="TIDAL",
            css_class="nav-badge-tidal",
            icon_size=12,
        )
        tidal_badge.set_valign(Gtk.Align.CENTER)
        header_box.append(tidal_badge)

        self._root.append(header_box)

        # ---- Back button (hidden initially) ----
        self._back_btn = Gtk.Button.new_from_icon_name("go-previous-symbolic")
        self._back_btn.set_label("All Moods")
        self._back_btn.add_css_class("flat")
        self._back_btn.set_halign(Gtk.Align.START)
        self._back_btn.set_visible(False)
        self._back_btn.connect("clicked", self._on_back_clicked)
        self._root.append(self._back_btn)

        # ---- 2. Login prompt (shown when not connected) ----
        self._login_prompt = self._build_login_prompt()
        self._root.append(self._login_prompt)

        # ---- 3. Moods grid (shown when connected) ----
        self._moods_grid = Gtk.FlowBox()
        self._moods_grid.set_homogeneous(True)
        self._moods_grid.set_min_children_per_line(2)
        self._moods_grid.set_max_children_per_line(5)
        self._moods_grid.set_column_spacing(16)
        self._moods_grid.set_row_spacing(16)
        self._moods_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._moods_grid.connect(
            "child-activated", self._on_mood_card_activated
        )
        self._moods_grid.set_visible(False)
        self._root.append(self._moods_grid)

        # ---- 4. Mood detail area (carousel sections) ----
        self._detail_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        self._detail_box.set_visible(False)
        self._root.append(self._detail_box)

        # ---- 5. Loading spinner ----
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

        self._loading_label = Gtk.Label(
            label="Loading moods from Tidal..."
        )
        self._loading_label.add_css_class("dim-label")
        self._spinner_box.append(self._loading_label)

        self._root.append(self._spinner_box)

        # ---- 6. Empty state ----
        self._empty_state = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._empty_state.set_visible(False)
        self._empty_state.set_margin_top(48)
        self._empty_state.set_margin_bottom(48)

        empty_icon = Gtk.Image.new_from_icon_name(
            "face-smile-symbolic"
        )
        empty_icon.set_pixel_size(64)
        empty_icon.set_opacity(0.4)
        self._empty_state.append(empty_icon)

        empty_heading = Gtk.Label(label="No Moods Available")
        empty_heading.add_css_class("title-2")
        self._empty_state.append(empty_heading)

        empty_desc = Gtk.Label(
            label="Unable to load mood categories from Tidal.\n"
            "Check your connection and try again."
        )
        empty_desc.add_css_class("dim-label")
        empty_desc.set_justify(Gtk.Justification.CENTER)
        self._empty_state.append(empty_desc)

        retry_btn = Gtk.Button(label="Retry")
        retry_btn.set_halign(Gtk.Align.CENTER)
        retry_btn.add_css_class("suggested-action")
        retry_btn.connect("clicked", lambda _btn: self.refresh())
        self._empty_state.append(retry_btn)

        self._root.append(self._empty_state)

        # ---- 7. Error state ----
        self._error_state = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._error_state.set_visible(False)
        self._error_state.set_margin_top(48)
        self._error_state.set_margin_bottom(48)

        error_icon = Gtk.Image.new_from_icon_name(
            "dialog-warning-symbolic"
        )
        error_icon.set_pixel_size(64)
        error_icon.set_opacity(0.4)
        self._error_state.append(error_icon)

        error_heading = Gtk.Label(label="Unable to load moods")
        error_heading.add_css_class("title-2")
        self._error_state.append(error_heading)

        error_desc = Gtk.Label(
            label="A network or Tidal API error occurred.\n"
            "Check your connection and try again."
        )
        error_desc.add_css_class("dim-label")
        error_desc.set_justify(Gtk.Justification.CENTER)
        self._error_state.append(error_desc)

        error_retry_btn = Gtk.Button(label="Retry")
        error_retry_btn.set_halign(Gtk.Align.CENTER)
        error_retry_btn.add_css_class("suggested-action")
        error_retry_btn.connect("clicked", lambda _btn: self.refresh())
        self._error_state.append(error_retry_btn)

        self._root.append(self._error_state)

        self.set_child(self._root)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_tidal_provider(self, tidal_provider: Any) -> None:
        """Wire the Tidal provider for fetching moods content."""
        self._tidal_provider = tidal_provider

    def set_album_art_service(self, art_service: Any) -> None:
        """Set the AlbumArtService instance for loading cover art."""
        self._album_art_service = art_service

    def set_callbacks(
        self,
        on_play_playlist: Optional[Callable] = None,
        on_album_clicked: Optional[Callable] = None,
        on_login: Optional[Callable] = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_play_playlist:
            Called with (tidal_id, name) when a playlist is clicked.
        on_album_clicked:
            Called with (album_name, artist) when an album card is clicked.
        on_login:
            Called when the user clicks the login button.
        """
        self._on_play_playlist = on_play_playlist
        self._on_album_clicked = on_album_clicked
        self._on_login = on_login

    def refresh(self) -> None:
        """Reload moods from Tidal."""
        self._refresh_generation += 1
        self._showing_mood_detail = False

        if (
            self._tidal_provider is None
            or not self._tidal_provider.is_logged_in
        ):
            self._show_login_state()
            return

        self._show_loading_state("Loading moods from Tidal...")
        gen = self._refresh_generation
        thread = threading.Thread(
            target=self._fetch_moods_thread, args=(gen,), daemon=True
        )
        thread.start()

    # ------------------------------------------------------------------
    # Internal: build widgets
    # ------------------------------------------------------------------

    def _build_login_prompt(self) -> Gtk.Box:
        """Build the login prompt shown when Tidal is not connected."""
        return make_tidal_connect_prompt(
            css_class="moods-login-prompt",
            icon_name="tidal-symbolic",
            heading_text="Connect to Tidal",
            description_text=(
                "Log in to your Tidal account to browse\n"
                "moods and activities."
            ),
            button_text="Log In to Tidal",
            on_login_clicked=self._on_login_clicked_cb,
        )

    def _make_mood_card(
        self,
        title: str,
        api_path: str,
        image_url: str | None = None,
    ) -> Gtk.FlowBoxChild:
        """Build a single mood card for the grid."""
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
        art_box.set_size_request(200, 160)
        art_box.set_vexpand(False)

        art_icon = Gtk.Image.new_from_icon_name("face-smile-symbolic")
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.3)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        # Cover art image (hidden until loaded)
        art_image = Gtk.Image()
        art_image.set_pixel_size(200)
        art_image.set_halign(Gtk.Align.FILL)
        art_image.set_valign(Gtk.Align.FILL)
        art_image.set_visible(False)
        art_box.append(art_image)

        overlay.set_child(art_box)

        # Gradient overlay with title
        text_overlay = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
            valign=Gtk.Align.END,
        )
        text_overlay.add_css_class("mix-card-text-overlay")

        title_label = Gtk.Label(label=title)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(24)
        title_label.add_css_class("mix-card-title")
        text_overlay.append(title_label)

        overlay.add_overlay(text_overlay)
        card.append(overlay)

        # Let pointer events pass through
        text_overlay.set_can_target(False)
        art_box.set_can_target(False)
        overlay.set_can_target(False)
        card.set_can_target(False)

        child = Gtk.FlowBoxChild()
        child.set_child(card)
        child._mood_title = title  # type: ignore[attr-defined]
        child._mood_api_path = api_path  # type: ignore[attr-defined]
        child._art_icon = art_icon  # type: ignore[attr-defined]
        child._art_image = art_image  # type: ignore[attr-defined]
        return child

    # ------------------------------------------------------------------
    # Internal: state management
    # ------------------------------------------------------------------

    def _show_login_state(self) -> None:
        """Show the login prompt and hide content."""
        self._login_prompt.set_visible(True)
        self._moods_grid.set_visible(False)
        self._detail_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(False)
        self._error_state.set_visible(False)
        self._back_btn.set_visible(False)

    def _show_loading_state(self, message: str = "Loading...") -> None:
        """Show a loading spinner."""
        self._login_prompt.set_visible(False)
        self._moods_grid.set_visible(False)
        self._detail_box.set_visible(False)
        self._spinner_box.set_visible(True)
        self._loading_label.set_label(message)
        self._empty_state.set_visible(False)
        self._error_state.set_visible(False)

    def _show_grid_state(self) -> None:
        """Show the moods grid."""
        self._login_prompt.set_visible(False)
        self._moods_grid.set_visible(True)
        self._detail_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(False)
        self._error_state.set_visible(False)
        self._back_btn.set_visible(False)
        self._title_label.set_label("Moods & Activities")
        self._showing_mood_detail = False

    def _show_detail_state(self, mood_title: str) -> None:
        """Show the mood detail view with carousels."""
        self._login_prompt.set_visible(False)
        self._moods_grid.set_visible(False)
        self._detail_box.set_visible(True)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(False)
        self._error_state.set_visible(False)
        self._back_btn.set_visible(True)
        self._title_label.set_label(mood_title)
        self._showing_mood_detail = True
        self._current_mood_title = mood_title

    def _show_empty_state(self) -> None:
        """Show the empty state."""
        self._login_prompt.set_visible(False)
        self._moods_grid.set_visible(False)
        self._detail_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(True)
        self._error_state.set_visible(False)
        self._back_btn.set_visible(False)

    def _show_error_state(self) -> None:
        """Show the error state."""
        self._login_prompt.set_visible(False)
        self._moods_grid.set_visible(False)
        self._detail_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._empty_state.set_visible(False)
        self._error_state.set_visible(True)
        self._back_btn.set_visible(False)

    # ------------------------------------------------------------------
    # Internal: data fetching
    # ------------------------------------------------------------------

    def _fetch_moods_thread(self, gen: int) -> None:
        """Fetch moods from Tidal in a background thread."""
        if self._tidal_provider is None:
            GLib.idle_add(self._show_login_state)
            return

        try:
            moods = self._tidal_provider.get_moods()

            if gen != self._refresh_generation:
                return

            def _apply_results() -> bool:
                if gen != self._refresh_generation:
                    return False
                if not moods:
                    self._show_empty_state()
                    return False
                self._populate_moods(moods)
                self._show_grid_state()
                return False

            GLib.idle_add(_apply_results)

        except Exception:
            logger.warning(
                "Failed to fetch moods content", exc_info=True
            )
            if gen == self._refresh_generation:
                GLib.idle_add(self._show_error_state)

    def _fetch_mood_detail_thread(
        self, api_path: str, mood_title: str, gen: int
    ) -> None:
        """Fetch mood detail content in a background thread."""
        if self._tidal_provider is None:
            GLib.idle_add(self._show_grid_state)
            return

        try:
            sections = self._tidal_provider.get_mood_page(api_path)

            if gen != self._refresh_generation:
                return

            def _apply_detail() -> bool:
                if gen != self._refresh_generation:
                    return False
                self._populate_mood_detail(sections)
                self._show_detail_state(mood_title)
                return False

            GLib.idle_add(_apply_detail)

        except Exception:
            logger.warning(
                "Failed to fetch mood detail for %s",
                mood_title,
                exc_info=True,
            )
            if gen == self._refresh_generation:
                GLib.idle_add(self._show_grid_state)

    # ------------------------------------------------------------------
    # Internal: populate widgets
    # ------------------------------------------------------------------

    def _populate_moods(self, moods: list[dict]) -> None:
        """Fill the moods grid with mood cards."""
        self._clear_flow_box(self._moods_grid)
        for mood in moods:
            child = self._make_mood_card(
                title=mood.get("title", ""),
                api_path=mood.get("api_path", ""),
                image_url=mood.get("image_url"),
            )
            self._moods_grid.append(child)
            image_url = mood.get("image_url")
            if image_url:
                self._load_card_art(child, image_url)

    def _populate_mood_detail(self, sections: list[dict]) -> None:
        """Fill the detail box with carousel sections for a mood."""
        # Clear existing detail content
        child = self._detail_box.get_first_child()
        while child is not None:
            next_child = child.get_next_sibling()
            self._detail_box.remove(child)
            child = next_child

        if not sections:
            empty_label = Gtk.Label(
                label="No content found for this mood."
            )
            empty_label.add_css_class("dim-label")
            empty_label.set_margin_top(24)
            self._detail_box.append(empty_label)
            return

        import tidalapi

        for section in sections:
            title = section.get("title", "")
            sec_type = section.get("type", "other")
            items = section.get("items", [])
            if not items:
                continue

            if sec_type == "playlists":
                carousel = HorizontalCarousel(title=title)
                for item in items:
                    card = self._make_playlist_detail_card(item)
                    carousel.append_card(card)
                    self._load_detail_card_art(card)
                self._detail_box.append(carousel)

            elif sec_type == "albums":
                carousel = HorizontalCarousel(title=title)
                for item in items:
                    card = self._make_album_detail_card(item)
                    carousel.append_card(card)
                    self._load_detail_card_art(card)
                self._detail_box.append(carousel)

            elif sec_type == "tracks":
                # Show tracks as a list section
                box = Gtk.Box(
                    orientation=Gtk.Orientation.VERTICAL, spacing=8,
                )
                header = Gtk.Label(label=title)
                header.set_xalign(0)
                header.add_css_class("section-header")
                header.set_margin_start(8)
                box.append(header)

                list_box = Gtk.ListBox()
                list_box.set_selection_mode(Gtk.SelectionMode.NONE)
                list_box.add_css_class("boxed-list")

                for item in items[:10]:
                    track_name = getattr(item, "name", "") or ""
                    artist_obj = getattr(item, "artist", None)
                    artist_name = (
                        getattr(artist_obj, "name", "")
                        if artist_obj
                        else ""
                    )
                    row = Gtk.ListBoxRow()
                    row_box = Gtk.Box(
                        orientation=Gtk.Orientation.HORIZONTAL,
                        spacing=12,
                    )
                    row_box.set_margin_top(8)
                    row_box.set_margin_bottom(8)
                    row_box.set_margin_start(12)
                    row_box.set_margin_end(12)

                    track_label = Gtk.Label(label=track_name)
                    track_label.set_xalign(0)
                    track_label.set_hexpand(True)
                    track_label.set_ellipsize(Pango.EllipsizeMode.END)
                    row_box.append(track_label)

                    if artist_name:
                        artist_label = Gtk.Label(label=artist_name)
                        artist_label.add_css_class("dim-label")
                        artist_label.set_ellipsize(
                            Pango.EllipsizeMode.END
                        )
                        row_box.append(artist_label)

                    row.set_child(row_box)
                    list_box.append(row)

                box.append(list_box)
                self._detail_box.append(box)

            elif sec_type == "mixes":
                carousel = HorizontalCarousel(title=title)
                for item in items:
                    card = self._make_mix_detail_card(item)
                    carousel.append_card(card)
                    self._load_detail_card_art(card)
                self._detail_box.append(carousel)

            else:
                # Generic: try to render as a carousel
                carousel = HorizontalCarousel(title=title)
                for item in items:
                    card = self._make_generic_detail_card(item)
                    carousel.append_card(card)
                self._detail_box.append(carousel)

    def _make_playlist_detail_card(self, item: Any) -> Gtk.Box:
        """Build a card for a playlist in mood detail view."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")
        card.set_size_request(160, -1)

        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("album-art-placeholder")
        art_box.set_size_request(160, 160)

        art_icon = Gtk.Image.new_from_icon_name("view-list-symbolic")
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        art_image = Gtk.Image()
        art_image.set_pixel_size(160)
        art_image.set_halign(Gtk.Align.CENTER)
        art_image.set_valign(Gtk.Align.CENTER)
        art_image.add_css_class("album-card-art-image")
        art_image.set_visible(False)
        art_box.append(art_image)

        card.append(art_box)

        name = getattr(item, "name", "") or getattr(item, "title", "") or ""
        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(6)
        title_label.set_margin_end(6)
        card.append(title_label)

        card._art_icon = art_icon  # type: ignore[attr-defined]
        card._art_image = art_image  # type: ignore[attr-defined]
        card._item = item  # type: ignore[attr-defined]

        # Cover art URL
        cover_url = None
        try:
            cover_url = item.image(dimensions=640)
        except Exception:
            pass
        if not cover_url:
            pic = getattr(item, "picture", None) or getattr(item, "cover", None)
            if pic:
                cover_url = (
                    f"https://resources.tidal.com/images/"
                    f"{pic.replace('-', '/')}/640x640.jpg"
                )
        card._cover_url = cover_url  # type: ignore[attr-defined]

        # Click to play playlist
        click = Gtk.GestureClick.new()
        click.set_button(1)
        click.connect("released", self._on_detail_playlist_clicked, item)
        card.add_controller(click)

        return card

    def _make_album_detail_card(self, item: Any) -> Gtk.Box:
        """Build a card for an album in mood detail view."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")
        card.set_size_request(160, -1)

        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("album-art-placeholder")
        art_box.set_size_request(160, 160)

        art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        art_image = Gtk.Image()
        art_image.set_pixel_size(160)
        art_image.set_halign(Gtk.Align.CENTER)
        art_image.set_valign(Gtk.Align.CENTER)
        art_image.add_css_class("album-card-art-image")
        art_image.set_visible(False)
        art_box.append(art_image)

        card.append(art_box)

        name = getattr(item, "name", "") or ""
        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(6)
        title_label.set_margin_end(6)
        card.append(title_label)

        artist_obj = getattr(item, "artist", None)
        artist_name = getattr(artist_obj, "name", "") if artist_obj else ""
        if artist_name:
            artist_label = Gtk.Label(label=artist_name)
            artist_label.set_xalign(0)
            artist_label.set_ellipsize(Pango.EllipsizeMode.END)
            artist_label.set_max_width_chars(18)
            artist_label.add_css_class("caption")
            artist_label.add_css_class("dim-label")
            artist_label.set_margin_start(6)
            artist_label.set_margin_end(6)
            card.append(artist_label)

        card._art_icon = art_icon  # type: ignore[attr-defined]
        card._art_image = art_image  # type: ignore[attr-defined]
        card._item = item  # type: ignore[attr-defined]

        cover_url = None
        try:
            cover_url = item.image(dimensions=640)
        except Exception:
            pass
        if not cover_url:
            pic = getattr(item, "cover", None)
            if pic:
                cover_url = (
                    f"https://resources.tidal.com/images/"
                    f"{pic.replace('-', '/')}/640x640.jpg"
                )
        card._cover_url = cover_url  # type: ignore[attr-defined]

        # Click to navigate to album
        click = Gtk.GestureClick.new()
        click.set_button(1)
        click.connect("released", self._on_detail_album_clicked, item)
        card.add_controller(click)

        return card

    def _make_mix_detail_card(self, item: Any) -> Gtk.Box:
        """Build a card for a mix in mood detail view."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")
        card.set_size_request(160, -1)

        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("album-art-placeholder")
        art_box.set_size_request(160, 160)

        art_icon = Gtk.Image.new_from_icon_name(
            "media-playlist-shuffle-symbolic"
        )
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        art_image = Gtk.Image()
        art_image.set_pixel_size(160)
        art_image.set_halign(Gtk.Align.CENTER)
        art_image.set_valign(Gtk.Align.CENTER)
        art_image.add_css_class("album-card-art-image")
        art_image.set_visible(False)
        art_box.append(art_image)

        card.append(art_box)

        name = (
            getattr(item, "title", None)
            or getattr(item, "name", None)
            or "Mix"
        )
        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(6)
        title_label.set_margin_end(6)
        card.append(title_label)

        card._art_icon = art_icon  # type: ignore[attr-defined]
        card._art_image = art_image  # type: ignore[attr-defined]
        card._item = item  # type: ignore[attr-defined]

        cover_url = None
        try:
            cover_url = item.image(dimensions=640)
        except Exception:
            pass
        if not cover_url:
            pic = getattr(item, "picture", None)
            if pic:
                cover_url = (
                    f"https://resources.tidal.com/images/"
                    f"{pic.replace('-', '/')}/640x640.jpg"
                )
        card._cover_url = cover_url  # type: ignore[attr-defined]

        # Click to play mix
        click = Gtk.GestureClick.new()
        click.set_button(1)
        click.connect("released", self._on_detail_mix_clicked, item)
        card.add_controller(click)

        return card

    def _make_generic_detail_card(self, item: Any) -> Gtk.Box:
        """Build a generic card for unknown item types."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")
        card.set_size_request(160, -1)

        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("album-art-placeholder")
        art_box.set_size_request(160, 160)

        art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        card.append(art_box)

        name = (
            getattr(item, "name", None)
            or getattr(item, "title", None)
            or ""
        )
        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(6)
        title_label.set_margin_end(6)
        card.append(title_label)

        return card

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_login_clicked_cb(self) -> None:
        """Handle login button click."""
        if self._on_login is not None:
            self._on_login()

    def _on_mood_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle clicking a mood card -- load its detail content."""
        api_path = getattr(child, "_mood_api_path", None)
        mood_title = getattr(child, "_mood_title", None)
        if not api_path or not mood_title:
            return

        self._refresh_generation += 1
        self._show_loading_state(f"Loading {mood_title}...")

        gen = self._refresh_generation
        thread = threading.Thread(
            target=self._fetch_mood_detail_thread,
            args=(api_path, mood_title, gen),
            daemon=True,
        )
        thread.start()

    def _on_back_clicked(self, _btn: Gtk.Button) -> None:
        """Handle back button -- return to moods grid."""
        self.refresh()

    def _on_detail_playlist_clicked(
        self,
        gesture: Gtk.GestureClick,
        _n_press: int,
        _x: float,
        _y: float,
        item: Any,
    ) -> None:
        """Handle clicking a playlist card in mood detail."""
        if self._on_play_playlist is not None:
            tidal_id = str(getattr(item, "id", "") or "")
            name = getattr(item, "name", "") or getattr(item, "title", "") or ""
            if tidal_id:
                self._on_play_playlist(tidal_id, name)

    def _on_detail_album_clicked(
        self,
        gesture: Gtk.GestureClick,
        _n_press: int,
        _x: float,
        _y: float,
        item: Any,
    ) -> None:
        """Handle clicking an album card in mood detail."""
        if self._on_album_clicked is not None:
            name = getattr(item, "name", "") or ""
            artist_obj = getattr(item, "artist", None)
            artist_name = getattr(artist_obj, "name", "") if artist_obj else ""
            self._on_album_clicked(name, artist_name)

    def _on_detail_mix_clicked(
        self,
        gesture: Gtk.GestureClick,
        _n_press: int,
        _x: float,
        _y: float,
        item: Any,
    ) -> None:
        """Handle clicking a mix card in mood detail."""
        if self._on_play_playlist is not None:
            tidal_id = str(getattr(item, "id", "") or "")
            name = (
                getattr(item, "title", None)
                or getattr(item, "name", None)
                or "Mix"
            )
            if tidal_id:
                # Mixes use mix tracks, not playlist tracks
                self._on_play_playlist(tidal_id, name)

    # ------------------------------------------------------------------
    # Cover art loading
    # ------------------------------------------------------------------

    def _load_card_art(self, child: Gtk.FlowBoxChild, url: str) -> None:
        """Load cover art for a mood card asynchronously."""
        art_icon = getattr(child, "_art_icon", None)
        art_image = getattr(child, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        request_token = object()
        child._art_request_token = request_token  # type: ignore[attr-defined]

        def _load():
            try:
                pixbuf = AlbumArtService.load_pixbuf_from_url(url, 400, 400)
                GLib.idle_add(_on_loaded, pixbuf)
            except Exception:
                pass

        def _on_loaded(pixbuf):
            if getattr(child, "_art_request_token", None) is not request_token:
                return False
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)
            return False

        threading.Thread(target=_load, daemon=True).start()

    def _load_detail_card_art(self, card: Gtk.Box) -> None:
        """Load cover art for a detail card asynchronously."""
        cover_url = getattr(card, "_cover_url", None)
        art_icon = getattr(card, "_art_icon", None)
        art_image = getattr(card, "_art_image", None)
        if not cover_url or art_icon is None or art_image is None:
            return

        request_token = object()
        card._art_request_token = request_token  # type: ignore[attr-defined]

        def _load():
            try:
                pixbuf = AlbumArtService.load_pixbuf_from_url(
                    cover_url, 320, 320
                )
                GLib.idle_add(_on_loaded, pixbuf)
            except Exception:
                pass

        def _on_loaded(pixbuf):
            if getattr(card, "_art_request_token", None) is not request_token:
                return False
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)
            return False

        threading.Thread(target=_load, daemon=True).start()

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
