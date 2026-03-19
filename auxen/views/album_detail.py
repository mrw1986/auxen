"""Album detail page view for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, GLib, Gtk, Pango

from auxen.models import Track
from auxen.views.context_menu import TrackContextMenu
from auxen.views.widgets import (
    DragScrollHelper,
    HorizontalCarousel,
    format_duration,
    make_source_badge,
    make_standard_track_row,
)

logger = logging.getLogger(__name__)


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS (delegates to shared utility)."""
    return format_duration(seconds)


def _format_total_duration(seconds: float) -> str:
    """Format total seconds as a human-readable string (e.g. '1 hr 23 min')."""
    if seconds <= 0:
        return "0 min"
    total = int(seconds)
    hours = total // 3600
    minutes = (total % 3600) // 60
    if hours > 0:
        return f"{hours} hr {minutes} min"
    return f"{minutes} min"


def _make_source_badge(source: str) -> Gtk.Widget:
    """Create a small pill badge (delegates to shared utility)."""
    return make_source_badge(source)


class AlbumDetailView(Gtk.ScrolledWindow):
    """Scrollable album detail page with header and track list."""

    __gtype_name__ = "AlbumDetailView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        # Callbacks
        self._on_play_track: Optional[Callable[[Track], None]] = None
        self._on_play_all: Optional[Callable[[list[Track]], None]] = None
        self._on_back: Optional[Callable[[], None]] = None
        self._on_artist_navigate: Optional[Callable[[str], None]] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None
        self._current_menu: object = None

        # Track data
        self._tracks: list[Track] = []
        self._current_track_id: Optional[int] = None
        self._current_artist: str = ""

        # Root container
        self._root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )

        # ---- Back button row ----
        back_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        back_row.set_margin_top(16)
        back_row.set_margin_start(24)
        back_row.set_margin_bottom(8)

        self._back_btn = Gtk.Button()
        self._back_btn.set_icon_name("go-previous-symbolic")
        self._back_btn.add_css_class("flat")
        self._back_btn.set_tooltip_text("Back")
        self._back_btn.connect("clicked", self._on_back_clicked)

        back_label = Gtk.Label(label="Back")
        back_label.add_css_class("dim-label")

        back_row.append(self._back_btn)
        back_row.append(back_label)
        self._root.append(back_row)

        # ---- Album header ----
        self._header = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=24,
        )
        self._header.add_css_class("album-detail-header")
        self._header.set_margin_top(8)
        self._header.set_margin_bottom(24)
        self._header.set_margin_start(32)
        self._header.set_margin_end(32)

        # Album art container
        self._art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._art_box.add_css_class("album-detail-art")
        self._art_box.set_size_request(200, 200)
        self._art_box.set_vexpand(False)

        # Placeholder icon
        self._art_placeholder = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        self._art_placeholder.set_pixel_size(64)
        self._art_placeholder.set_opacity(0.4)
        self._art_placeholder.set_halign(Gtk.Align.CENTER)
        self._art_placeholder.set_valign(Gtk.Align.CENTER)
        self._art_placeholder.set_vexpand(True)

        # Album art image (hidden until loaded).
        # Use Gtk.Image + set_pixel_size + set_from_paintable (texture)
        # so the image renders at 200 CSS pixels (2x asset fetched for
        # HiDPI). Gtk.Picture was avoided due to pixman errors.
        self._art_image = Gtk.Image()
        self._art_image.set_pixel_size(200)
        self._art_image.set_halign(Gtk.Align.CENTER)
        self._art_image.set_valign(Gtk.Align.CENTER)
        self._art_image.add_css_class("album-detail-art-image")
        self._art_image.set_visible(False)

        self._art_box.append(self._art_placeholder)
        self._art_box.append(self._art_image)
        self._header.append(self._art_box)

        # Info column
        info_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        info_box.set_valign(Gtk.Align.CENTER)
        info_box.set_hexpand(True)

        self._title_label = Gtk.Label(label="")
        self._title_label.set_xalign(0)
        self._title_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._title_label.set_max_width_chars(40)
        self._title_label.add_css_class("album-detail-title")
        info_box.append(self._title_label)

        self._artist_label = Gtk.Label(label="")
        self._artist_label.set_xalign(0)
        self._artist_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._artist_label.set_max_width_chars(40)
        self._artist_label.add_css_class("album-detail-artist")
        self._artist_label.add_css_class("clickable-link")
        self._artist_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))

        artist_click = Gtk.GestureClick.new()
        artist_click.set_button(1)
        artist_click.connect("released", self._on_artist_label_clicked)
        self._artist_label.add_controller(artist_click)

        info_box.append(self._artist_label)

        self._meta_label = Gtk.Label(label="")
        self._meta_label.set_xalign(0)
        self._meta_label.add_css_class("album-detail-meta")
        info_box.append(self._meta_label)

        # Source badge
        self._source_badge_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        self._source_badge_box.set_margin_top(4)
        info_box.append(self._source_badge_box)

        # Action buttons row
        btn_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        btn_box.set_margin_top(12)

        self._play_all_btn = Gtk.Button(label="Play All")
        self._play_all_btn.add_css_class("album-detail-play-all-btn")
        self._play_all_btn.set_icon_name("media-playback-start-symbolic")
        self._play_all_btn.connect("clicked", self._on_play_all_clicked)
        btn_box.append(self._play_all_btn)

        self._shuffle_btn = Gtk.Button(label="Shuffle")
        self._shuffle_btn.add_css_class("flat")
        self._shuffle_btn.set_icon_name("media-playlist-shuffle-symbolic")
        self._shuffle_btn.connect("clicked", self._on_shuffle_clicked)
        btn_box.append(self._shuffle_btn)

        info_box.append(btn_box)

        self._header.append(info_box)
        self._root.append(self._header)

        # ---- Track list ----
        track_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        track_section.set_margin_start(32)
        track_section.set_margin_end(32)
        track_section.set_margin_bottom(32)

        track_header = Gtk.Label(label="Tracks")
        track_header.set_xalign(0)
        track_header.add_css_class("section-header")
        track_section.append(track_header)

        self._track_list = Gtk.ListBox()
        self._track_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._track_list.add_css_class("boxed-list")
        self._track_list.connect("row-activated", self._on_row_activated)
        track_section.append(self._track_list)

        self._root.append(track_section)

        # ---- Review section (collapsible) ----
        self._review_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        self._review_section.set_margin_start(32)
        self._review_section.set_margin_end(32)
        self._review_section.set_margin_bottom(24)
        self._review_section.set_visible(False)

        review_header_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        review_header_label = Gtk.Label(label="Review")
        review_header_label.set_xalign(0)
        review_header_label.set_hexpand(True)
        review_header_label.add_css_class("section-header")
        review_header_row.append(review_header_label)

        self._review_toggle_btn = Gtk.ToggleButton()
        self._review_toggle_btn.set_icon_name("pan-down-symbolic")
        self._review_toggle_btn.add_css_class("flat")
        self._review_toggle_btn.add_css_class("circular")
        self._review_toggle_btn.set_tooltip_text("Expand/Collapse")
        self._review_toggle_btn.set_active(False)
        self._review_toggle_btn.connect(
            "toggled", self._on_review_toggle
        )
        review_header_row.append(self._review_toggle_btn)
        self._review_section.append(review_header_row)

        self._review_label = Gtk.Label(label="")
        self._review_label.set_xalign(0)
        self._review_label.set_yalign(0)
        self._review_label.set_wrap(True)
        self._review_label.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
        self._review_label.set_max_width_chars(80)
        self._review_label.add_css_class("body")
        self._review_label.set_selectable(True)
        self._review_label.set_visible(False)
        self._review_section.append(self._review_label)

        self._root.append(self._review_section)

        # ---- Similar Albums carousel ----
        self._similar_albums_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        self._similar_albums_section.set_margin_start(32)
        self._similar_albums_section.set_margin_end(32)
        self._similar_albums_section.set_margin_bottom(32)
        self._similar_albums_section.set_visible(False)

        self._similar_albums_carousel = HorizontalCarousel(
            title="Similar Albums",
        )
        self._similar_albums_section.append(
            self._similar_albums_carousel
        )
        self._root.append(self._similar_albums_section)

        # Callback for navigating to an album from similar albums
        self._on_similar_album_clicked: Optional[
            Callable[..., None]
        ] = None

        self.set_child(self._root)

    # ---- Public API ----

    def set_callbacks(
        self,
        on_play_track: Callable[[Track], None] | None = None,
        on_play_all: Callable[[list[Track]], None] | None = None,
        on_back: Callable[[], None] | None = None,
        on_artist_navigate: Callable[[str], None] | None = None,
        on_similar_album_clicked: Callable[..., None] | None = None,
    ) -> None:
        """Set callback functions for user actions."""
        self._on_play_track = on_play_track
        self._on_play_all = on_play_all
        self._on_back = on_back
        self._on_artist_navigate = on_artist_navigate
        self._on_similar_album_clicked = on_similar_album_clicked

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def show_album(
        self,
        album_name: str,
        artist: str,
        tracks: list[Track],
        source: str,
    ) -> None:
        """Populate the view with album data."""
        self._tracks = list(tracks)
        self._current_artist = artist

        # Reset album art to placeholder
        self.set_album_art(None)

        # Update header
        self._title_label.set_label(album_name)
        self._artist_label.set_label(artist)

        # Compute metadata
        track_count = len(tracks)
        total_seconds = sum(
            t.duration for t in tracks if t.duration is not None
        )
        year = None
        for t in tracks:
            if t.year is not None:
                year = t.year
                break

        meta_parts: list[str] = []
        if year is not None:
            meta_parts.append(str(year))
        meta_parts.append(
            f"{track_count} track{'s' if track_count != 1 else ''}"
        )
        meta_parts.append(_format_total_duration(total_seconds))
        self._meta_label.set_label(" \u2022 ".join(meta_parts))

        # Source badge
        self._clear_box(self._source_badge_box)
        self._source_badge_box.append(_make_source_badge(source))

        # Reset review and similar albums sections
        self._review_section.set_visible(False)
        self._review_label.set_visible(False)
        self._review_toggle_btn.set_active(False)
        self._similar_albums_section.set_visible(False)
        self._similar_albums_carousel.clear()

        # Track list
        self._clear_list_box(self._track_list)
        for i, track in enumerate(tracks):
            row = self._make_track_row(track, i)
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

    def set_album_art(self, pixbuf: GdkPixbuf.Pixbuf | None) -> None:
        """Set the album header art, or fall back to placeholder if None."""
        if pixbuf is not None:
            texture = Gdk.Texture.new_for_pixbuf(pixbuf)
            self._art_image.set_from_paintable(texture)
            self._art_image.set_visible(True)
            self._art_placeholder.set_visible(False)
        else:
            self._art_image.set_visible(False)
            self._art_placeholder.set_visible(True)

    def set_current_track(self, track_id: int | None) -> None:
        """Highlight the currently playing track in the list."""
        self._current_track_id = track_id
        # Re-apply styling to all rows
        index = 0
        while True:
            row = self._track_list.get_row_at_index(index)
            if row is None:
                break
            row_box = row.get_child()
            if row_box is not None:
                if index < len(self._tracks):
                    t = self._tracks[index]
                    if t.id == track_id:
                        row_box.add_css_class(
                            "album-detail-track-playing"
                        )
                    else:
                        row_box.remove_css_class(
                            "album-detail-track-playing"
                        )
            index += 1

    # ---- Internal ----

    def _make_track_row(
        self, track: Track, index: int
    ) -> Gtk.ListBoxRow:
        """Build a single track row using the shared standard layout."""
        # Album detail uses track.track_number when available
        display_index = (
            (track.track_number - 1) if track.track_number else index
        )
        row = make_standard_track_row(
            track,
            index=display_index,
            show_art=False,
            show_subtitle=False,
            show_source_badge=False,
            show_quality_badge=True,
            show_duration=True,
            css_class="album-detail-track-row",
        )

        # Highlight if currently playing
        row_box = row.get_child()
        if (
            self._current_track_id is not None
            and track.id == self._current_track_id
        ):
            row_box.add_css_class("album-detail-track-playing")

        row.set_activatable(True)
        return row

    def _on_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle a track row being clicked."""
        index = row.get_index()
        if 0 <= index < len(self._tracks):
            track = self._tracks[index]
            if self._on_play_track is not None:
                self._on_play_track(track)

    def _on_play_all_clicked(self, _btn: Gtk.Button) -> None:
        """Handle the Play All button."""
        if self._on_play_all is not None and self._tracks:
            self._on_play_all(list(self._tracks))

    def _on_shuffle_clicked(self, _btn: Gtk.Button) -> None:
        """Handle the Shuffle button — play all in random order."""
        if self._on_play_all is not None and self._tracks:
            import random

            shuffled = list(self._tracks)
            random.shuffle(shuffled)
            self._on_play_all(shuffled)

    def _on_back_clicked(self, _btn: Gtk.Button) -> None:
        """Handle the Back button."""
        if self._on_back is not None:
            self._on_back()

    # ------------------------------------------------------------------
    # Context menu helpers
    # ------------------------------------------------------------------

    def _attach_context_gesture(
        self, row: Gtk.ListBoxRow, track: Track
    ) -> None:
        """Attach a right-click gesture to a track row."""
        if self._context_callbacks is None or track is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(g, n_press, x, y, trk=track):
            if n_press != 1:
                return
            self._show_track_context_menu(row, x, y, trk)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_track_context_menu(
        self, widget: Gtk.Widget, x: float, y: float, track: Track
    ) -> None:
        """Create and display a context menu for a track."""
        if self._context_callbacks is None:
            return

        playlists = []
        if self._get_playlists is not None:
            playlists = self._get_playlists()

        _noop = lambda *_args: None
        callbacks = {
            "on_play": lambda t=track: self._context_callbacks.get("on_play", _noop)(t),
            "on_play_next": lambda t=track: self._context_callbacks.get("on_play_next", _noop)(t),
            "on_add_to_queue": lambda t=track: self._context_callbacks.get("on_add_to_queue", _noop)(t),
            "on_add_to_playlist": lambda pid, t=track: self._context_callbacks.get("on_add_to_playlist", _noop)(t, pid),
            "on_new_playlist": lambda t=track: self._context_callbacks.get("on_new_playlist", _noop)(t),
            "on_toggle_favorite": lambda t=track: self._context_callbacks.get("on_toggle_favorite", _noop)(t),
            "on_go_to_album": lambda t=track: self._context_callbacks.get("on_go_to_album", _noop)(t),
            "on_go_to_artist": lambda t=track: self._context_callbacks.get("on_go_to_artist", _noop)(t),
            "on_track_radio": lambda t=track: self._context_callbacks.get("on_track_radio", _noop)(t),
            "on_track_mix": lambda t=track: self._context_callbacks.get("on_track_mix", _noop)(t),
            "on_view_lyrics": lambda t=track: self._context_callbacks.get("on_view_lyrics", _noop)(t),
            "on_credits": lambda t=track: self._context_callbacks.get("on_credits", _noop)(t),
        }

        track_data = {
            "id": track.id,
            "title": track.title,
            "artist": track.artist,
            "album": track.album or "",
            "source": track.source,
            "source_id": getattr(track, "source_id", None),
            "is_favorite": False,
        }

        self._current_menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    def _on_artist_label_clicked(
        self,
        _gesture: Gtk.GestureClick,
        n_press: int,
        _x: float,
        _y: float,
    ) -> None:
        """Handle single click on the artist label — navigate to artist detail."""
        if n_press != 1:
            return
        if self._on_artist_navigate is not None and self._current_artist:
            self._on_artist_navigate(self._current_artist)

    # ------------------------------------------------------------------
    # Review + Similar Albums (called from window after background fetch)
    # ------------------------------------------------------------------

    def set_review(self, review_text: str | None) -> None:
        """Show or hide the album review section."""
        if review_text:
            self._review_label.set_label(review_text)
            self._review_section.set_visible(True)
            # Start collapsed
            self._review_toggle_btn.set_active(False)
            self._review_label.set_visible(False)
        else:
            self._review_section.set_visible(False)

    def _on_review_toggle(self, btn: Gtk.ToggleButton) -> None:
        """Toggle the review text visibility."""
        expanded = btn.get_active()
        self._review_label.set_visible(expanded)
        btn.set_icon_name(
            "pan-up-symbolic" if expanded else "pan-down-symbolic"
        )

    def set_similar_albums(self, albums: list[dict]) -> None:
        """Populate the Similar Albums carousel.

        Each dict should have keys: title, artist, cover_url, tidal_id,
        num_tracks.
        """
        self._similar_albums_carousel.clear()
        if not albums:
            self._similar_albums_section.set_visible(False)
            return

        self._similar_albums_section.set_visible(True)
        for album_data in albums:
            card = self._make_similar_album_card(album_data)
            self._similar_albums_carousel.append_card(card)

    def _make_similar_album_card(self, album_data: dict) -> Gtk.Box:
        """Build a clickable card for a similar album."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")

        # Album art placeholder
        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("album-art-placeholder")
        art_box.set_size_request(160, 160)
        art_box.set_vexpand(False)

        art_icon = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        art_image = Gtk.Image()
        art_image.set_pixel_size(160)
        art_image.set_size_request(160, 160)
        art_image.set_halign(Gtk.Align.FILL)
        art_image.set_valign(Gtk.Align.FILL)
        art_image.add_css_class("album-card-art-image")
        art_image.set_visible(False)
        art_box.append(art_image)

        card.append(art_box)

        # Album title
        title_label = Gtk.Label(
            label=album_data.get("title", "")
        )
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(4)
        title_label.set_margin_end(4)
        card.append(title_label)

        # Artist subtitle
        artist_name = album_data.get("artist", "")
        if artist_name:
            artist_label = Gtk.Label(label=artist_name)
            artist_label.set_xalign(0)
            artist_label.set_ellipsize(Pango.EllipsizeMode.END)
            artist_label.set_max_width_chars(18)
            artist_label.add_css_class("caption")
            artist_label.add_css_class("dim-label")
            artist_label.set_margin_start(4)
            artist_label.set_margin_end(4)
            card.append(artist_label)

        # Store data for click handling
        card._album_title = album_data.get("title", "")  # type: ignore[attr-defined]
        card._album_artist = artist_name  # type: ignore[attr-defined]
        card._tidal_id = album_data.get("tidal_id")  # type: ignore[attr-defined]

        # Click gesture
        gesture = Gtk.GestureClick(button=1)
        gesture.connect(
            "released", self._on_similar_album_clicked_gesture, card
        )
        card.add_controller(gesture)

        # Load cover art async
        cover_url = album_data.get("cover_url")
        if cover_url:
            self._load_similar_album_cover(
                card, art_icon, art_image, art_box, cover_url
            )

        return card

    def _on_similar_album_clicked_gesture(
        self,
        _gesture: Gtk.GestureClick,
        _n_press: int,
        _x: float,
        _y: float,
        card: Gtk.Box,
    ) -> None:
        """Handle a similar album card click."""
        title = getattr(card, "_album_title", None)
        artist = getattr(card, "_album_artist", "")
        tidal_id = getattr(card, "_tidal_id", None)
        if title and self._on_similar_album_clicked:
            self._on_similar_album_clicked(title, artist, tidal_id)

    def _load_similar_album_cover(
        self,
        card: Gtk.Box,
        icon: Gtk.Image,
        img: Gtk.Image,
        art_box: Gtk.Box,
        url: str,
    ) -> None:
        """Load a similar album's cover art from URL."""
        import threading
        import urllib.request

        request_token = object()
        card._art_token = request_token  # type: ignore[attr-defined]

        def _fetch() -> bytes | None:
            try:
                req = urllib.request.Request(
                    url, headers={"User-Agent": "Auxen/1.0"}
                )
                with urllib.request.urlopen(req, timeout=10) as resp:
                    return resp.read()
            except Exception:
                return None

        def _on_done(data: bytes | None) -> bool:
            if getattr(card, "_art_token", None) is not request_token:
                return False
            if data is not None:
                try:
                    loader = GdkPixbuf.PixbufLoader()
                    loader.write(data)
                    loader.close()
                    pixbuf = loader.get_pixbuf()
                    if pixbuf:
                        texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                        img.set_from_paintable(texture)
                        img.set_visible(True)
                        icon.set_visible(False)
                        art_box.remove_css_class(
                            "album-art-placeholder"
                        )
                except Exception:
                    pass
            return False

        def _thread():
            data = _fetch()
            GLib.idle_add(_on_done, data)

        threading.Thread(target=_thread, daemon=True).start()

    @staticmethod
    def _clear_list_box(list_box: Gtk.ListBox) -> None:
        """Remove all rows from a ListBox."""
        while True:
            row = list_box.get_row_at_index(0)
            if row is None:
                break
            list_box.remove(row)

    @staticmethod
    def _clear_box(box: Gtk.Box) -> None:
        """Remove all children from a Box."""
        while True:
            child = box.get_first_child()
            if child is None:
                break
            box.remove(child)
