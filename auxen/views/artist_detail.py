"""Artist detail page view for the Auxen music player."""

from __future__ import annotations

import logging
import random
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, GLib, Gtk, Pango

from auxen.models import Track
from auxen.views.context_menu import AlbumContextMenu, TrackContextMenu
from auxen.views.widgets import DragScrollHelper, make_tidal_source_badge

logger = logging.getLogger(__name__)


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    if seconds is None or seconds <= 0:
        return "0:00"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


def _format_total_duration(seconds: float) -> str:
    """Format total seconds as a human-readable string."""
    if seconds <= 0:
        return "0 min"
    total = int(seconds)
    hours = total // 3600
    minutes = (total % 3600) // 60
    if hours > 0:
        return f"{hours} hr {minutes} min"
    return f"{minutes} min"


def _make_source_badge(source: str) -> Gtk.Widget:
    """Create a small pill badge indicating the track source."""
    if source == "tidal":
        badge = make_tidal_source_badge(
            label_text=source.capitalize(),
            css_class="source-badge-tidal",
            icon_size=10,
        )
    else:
        badge = Gtk.Label(label=source.capitalize())
        badge.add_css_class("source-badge-local")
    badge.set_valign(Gtk.Align.CENTER)
    return badge


def _make_album_card(
    album: str, track_count: int, year: int | None, source: str,
    cover_url: str | None = None, tidal_id: str | None = None,
) -> Gtk.Box:
    """Build a single album card for the artist's albums row."""
    card = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=6,
    )
    card.add_css_class("album-card")

    # Album art with overlay badge
    overlay = Gtk.Overlay()
    overlay.add_css_class("album-card-art-container")

    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.set_size_request(160, 160)
    art_box.set_vexpand(False)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
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

    overlay.set_child(art_box)

    if source == "tidal":
        badge = make_tidal_source_badge(
            label_text=source.capitalize(),
            css_class="source-badge-tidal",
            icon_size=10,
        )
    else:
        badge = Gtk.Label(label=source.capitalize())
        badge.add_css_class("source-badge-local")
    badge.set_halign(Gtk.Align.START)
    badge.set_valign(Gtk.Align.START)
    badge.set_margin_top(8)
    badge.set_margin_start(8)
    overlay.add_overlay(badge)
    overlay.set_clip_overlay(badge, True)

    card.append(overlay)

    # Album title
    title_label = Gtk.Label(label=album)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(18)
    title_label.add_css_class("body")
    title_label.set_margin_start(4)
    title_label.set_margin_end(4)
    card.append(title_label)

    # Year + track count subtitle
    subtitle_parts = []
    if year is not None:
        subtitle_parts.append(str(year))
    track_word = "track" if track_count == 1 else "tracks"
    subtitle_parts.append(f"{track_count} {track_word}")
    subtitle_text = " \u2022 ".join(subtitle_parts)

    subtitle_label = Gtk.Label(label=subtitle_text)
    subtitle_label.set_xalign(0)
    subtitle_label.set_ellipsize(Pango.EllipsizeMode.END)
    subtitle_label.set_max_width_chars(18)
    subtitle_label.add_css_class("caption")
    subtitle_label.add_css_class("dim-label")
    subtitle_label.set_margin_start(4)
    subtitle_label.set_margin_end(4)
    card.append(subtitle_label)

    # Store data for click handling and async art loading
    card._album_title = album  # type: ignore[attr-defined]
    card._album_source = source  # type: ignore[attr-defined]
    card._tidal_id = tidal_id  # type: ignore[attr-defined]
    card._art_icon = art_icon  # type: ignore[attr-defined]
    card._art_image = art_image  # type: ignore[attr-defined]
    card._art_box = art_box  # type: ignore[attr-defined]
    card._cover_url = cover_url  # type: ignore[attr-defined]
    return card


class ArtistDetailView(Gtk.ScrolledWindow):
    """Scrollable artist detail page with header, albums row, and track list."""

    __gtype_name__ = "ArtistDetailView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        # Callbacks
        self._on_play_track: Optional[Callable[[Track], None]] = None
        self._on_play_all: Optional[Callable[[list[Track]], None]] = None
        self._on_back: Optional[Callable[[], None]] = None
        self._on_album_clicked: Optional[
            Callable[..., None]
        ] = None
        self._on_similar_artist_clicked: Optional[
            Callable[[str], None]
        ] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None
        self._album_context_callbacks: Optional[dict] = None
        self._get_album_playlists: Optional[Callable] = None
        self._current_menu: object = None

        # Data
        self._tracks: list[Track] = []
        self._albums: list[dict] = []
        self._artist_name: str = ""

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

        # ---- Artist header ----
        self._header = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=24,
        )
        self._header.add_css_class("artist-detail-header")
        self._header.set_margin_top(8)
        self._header.set_margin_bottom(24)
        self._header.set_margin_start(32)
        self._header.set_margin_end(32)

        # Artist icon placeholder
        icon_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        icon_box.add_css_class("artist-detail-icon")
        icon_box.set_size_request(120, 120)
        icon_box.set_vexpand(False)

        self._artist_icon = Gtk.Image.new_from_icon_name(
            "avatar-default-symbolic"
        )
        self._artist_icon.set_pixel_size(56)
        self._artist_icon.set_opacity(0.5)
        self._artist_icon.set_halign(Gtk.Align.CENTER)
        self._artist_icon.set_valign(Gtk.Align.CENTER)
        self._artist_icon.set_vexpand(True)
        icon_box.append(self._artist_icon)

        self._artist_image = Gtk.Image()
        self._artist_image.set_pixel_size(120)
        self._artist_image.set_size_request(120, 120)
        self._artist_image.set_halign(Gtk.Align.FILL)
        self._artist_image.set_valign(Gtk.Align.FILL)
        self._artist_image.add_css_class("artist-detail-photo")
        self._artist_image.set_visible(False)
        icon_box.append(self._artist_image)

        self._icon_box = icon_box
        self._header.append(icon_box)

        # Info column
        info_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        info_box.set_valign(Gtk.Align.CENTER)
        info_box.set_hexpand(True)

        self._name_label = Gtk.Label(label="")
        self._name_label.set_xalign(0)
        self._name_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._name_label.set_max_width_chars(40)
        self._name_label.add_css_class("artist-detail-name")
        info_box.append(self._name_label)

        self._meta_label = Gtk.Label(label="")
        self._meta_label.set_xalign(0)
        self._meta_label.add_css_class("album-detail-meta")
        info_box.append(self._meta_label)

        # Source badges
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
        self._play_all_btn.set_icon_name(
            "media-playback-start-symbolic"
        )
        self._play_all_btn.connect(
            "clicked", self._on_play_all_clicked
        )
        btn_box.append(self._play_all_btn)

        self._shuffle_btn = Gtk.Button(label="Shuffle")
        self._shuffle_btn.add_css_class("flat")
        self._shuffle_btn.set_icon_name(
            "media-playlist-shuffle-symbolic"
        )
        self._shuffle_btn.connect(
            "clicked", self._on_shuffle_clicked
        )
        btn_box.append(self._shuffle_btn)

        info_box.append(btn_box)

        self._header.append(info_box)
        self._root.append(self._header)

        # ---- Albums section ----
        self._albums_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        self._albums_section.set_margin_start(32)
        self._albums_section.set_margin_end(32)
        self._albums_section.set_margin_bottom(24)

        albums_header = Gtk.Label(label="Albums")
        albums_header.set_xalign(0)
        albums_header.add_css_class("section-header")
        self._albums_section.append(albums_header)

        # Horizontal scrolling for album cards
        self._albums_scroll = Gtk.ScrolledWindow()
        self._albums_scroll.set_policy(
            Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER
        )
        self._albums_scroll.set_min_content_height(240)
        self._albums_scroll.add_css_class("artist-detail-albums-row")

        self._album_grid = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
        )

        self._albums_scroll.set_child(self._album_grid)
        self._albums_section.append(self._albums_scroll)

        # Drag-to-scroll with kinetic momentum
        self._albums_drag_helper = DragScrollHelper(self._albums_scroll)

        self._root.append(self._albums_section)

        # ---- All Tracks section ----
        track_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        track_section.set_margin_start(32)
        track_section.set_margin_end(32)
        track_section.set_margin_bottom(32)

        track_header = Gtk.Label(label="Top Tracks")
        track_header.set_xalign(0)
        track_header.add_css_class("section-header")
        track_section.append(track_header)

        self._track_list = Gtk.ListBox()
        self._track_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._track_list.add_css_class("boxed-list")
        self._track_list.connect("row-activated", self._on_row_activated)
        track_section.append(self._track_list)

        self._root.append(track_section)

        # ---- Biography section ----
        self._bio_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        self._bio_section.set_margin_start(32)
        self._bio_section.set_margin_end(32)
        self._bio_section.set_margin_bottom(24)
        self._bio_section.set_visible(False)

        bio_header = Gtk.Label(label="About")
        bio_header.set_xalign(0)
        bio_header.add_css_class("section-header")
        self._bio_section.append(bio_header)

        self._bio_label = Gtk.Label(label="")
        self._bio_label.set_xalign(0)
        self._bio_label.set_yalign(0)
        self._bio_label.set_wrap(True)
        self._bio_label.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
        self._bio_label.set_max_width_chars(80)
        self._bio_label.add_css_class("body")
        self._bio_label.add_css_class("artist-bio-text")
        self._bio_label.set_selectable(True)
        self._bio_section.append(self._bio_label)

        self._root.append(self._bio_section)

        # ---- Similar Artists section ----
        self._similar_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        self._similar_section.set_margin_start(32)
        self._similar_section.set_margin_end(32)
        self._similar_section.set_margin_bottom(32)
        self._similar_section.set_visible(False)

        similar_header = Gtk.Label(label="Similar Artists")
        similar_header.set_xalign(0)
        similar_header.add_css_class("section-header")
        self._similar_section.append(similar_header)

        self._similar_scroll = Gtk.ScrolledWindow()
        self._similar_scroll.set_policy(
            Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER
        )
        self._similar_scroll.set_min_content_height(160)

        self._similar_grid = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )

        self._similar_scroll.set_child(self._similar_grid)
        self._similar_section.append(self._similar_scroll)

        # Drag-to-scroll with kinetic momentum
        self._similar_drag_helper = DragScrollHelper(self._similar_scroll)

        self._root.append(self._similar_section)

        # ---- Videos section ----
        self._videos_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        self._videos_section.set_margin_start(32)
        self._videos_section.set_margin_end(32)
        self._videos_section.set_margin_bottom(32)
        self._videos_section.set_visible(False)

        videos_header = Gtk.Label(label="Videos")
        videos_header.set_xalign(0)
        videos_header.add_css_class("section-header")
        self._videos_section.append(videos_header)

        self._videos_scroll = Gtk.ScrolledWindow()
        self._videos_scroll.set_policy(
            Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER
        )
        self._videos_scroll.set_min_content_height(180)

        self._videos_grid = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
        )

        self._videos_scroll.set_child(self._videos_grid)
        self._videos_section.append(self._videos_scroll)

        # Drag-to-scroll with kinetic momentum
        self._videos_drag_helper = DragScrollHelper(self._videos_scroll)

        self._root.append(self._videos_section)

        self.set_child(self._root)

    # ---- Public API ----

    def set_callbacks(
        self,
        on_play_track: Callable[[Track], None] | None = None,
        on_play_all: Callable[[list[Track]], None] | None = None,
        on_back: Callable[[], None] | None = None,
        on_album_clicked: Callable[[str, str], None] | None = None,
        on_similar_artist_clicked: Callable[[str], None] | None = None,
    ) -> None:
        """Set callback functions for user actions."""
        self._on_play_track = on_play_track
        self._on_play_all = on_play_all
        self._on_back = on_back
        self._on_album_clicked = on_album_clicked
        self._on_similar_artist_clicked = on_similar_artist_clicked

    def highlight_playing_track(self, track) -> None:
        """Highlight the currently playing track in the top tracks list."""
        if not hasattr(self, "_track_list"):
            return
        playing_sid = getattr(track, "source_id", None) if track else None
        playing_key = (
            (getattr(track, "title", ""), getattr(track, "artist", ""))
            if track
            else None
        )
        row = self._track_list.get_first_child()
        while row is not None:
            td = getattr(row, "_track_data", None)
            match = False
            if td is not None and track is not None:
                td_sid = getattr(td, "source_id", None)
                if playing_sid and td_sid and playing_sid == td_sid:
                    match = True
                elif playing_key and (
                    getattr(td, "title", ""), getattr(td, "artist", "")
                ) == playing_key:
                    match = True
            if match:
                row.add_css_class("now-playing-row")
            else:
                row.remove_css_class("now-playing-row")
            row = row.get_next_sibling()

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def set_album_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the album right-click context menu."""
        self._album_context_callbacks = callbacks
        self._get_album_playlists = get_playlists

    def show_artist(
        self,
        artist_name: str,
        albums: list[dict],
        tracks: list[Track],
        source: str,
        image_url: str | None = None,
        bio: str | None = None,
        similar_artists: list[dict] | None = None,
        videos: list[dict] | None = None,
    ) -> None:
        """Populate the view with artist data.

        Parameters
        ----------
        artist_name:
            The artist name to display.
        albums:
            List of album dicts with keys: album, track_count, year, source.
        tracks:
            All tracks by this artist.
        source:
            Primary source identifier (e.g. "local", "tidal").
        image_url:
            Optional URL for the artist's photo.
        bio:
            Optional biography text.
        similar_artists:
            Optional list of similar artist dicts with keys: name, image_url, tidal_id.
        videos:
            Optional list of video dicts with keys: title, duration, thumbnail_url, video_url.
        """
        self._artist_name = artist_name
        self._albums = list(albums)
        self._tracks = list(tracks)

        # Load artist image if URL provided
        self._artist_image.set_visible(False)
        self._artist_icon.set_visible(True)
        if image_url:
            self._load_artist_image(image_url)

        # Update header
        self._name_label.set_label(artist_name)

        # Compute metadata
        track_count = len(tracks)
        total_seconds = sum(
            t.duration for t in tracks if t.duration is not None
        )
        album_count = len(albums)

        meta_parts: list[str] = []
        meta_parts.append(
            f"{album_count} album{'s' if album_count != 1 else ''}"
        )
        meta_parts.append(
            f"{track_count} track{'s' if track_count != 1 else ''}"
        )
        meta_parts.append(_format_total_duration(total_seconds))
        self._meta_label.set_label(" \u2022 ".join(meta_parts))

        # Source badges
        self._clear_box(self._source_badge_box)
        sources = sorted({a["source"] for a in albums}) if albums else [source]
        for src in sources:
            self._source_badge_box.append(_make_source_badge(src))

        # Albums row (horizontal scrollable)
        self._clear_box(self._album_grid)
        if albums:
            self._albums_section.set_visible(True)
            for album_data in albums:
                card = _make_album_card(
                    album=album_data["album"],
                    track_count=album_data["track_count"],
                    year=album_data["year"],
                    source=album_data["source"],
                    cover_url=album_data.get("cover_url"),
                    tidal_id=album_data.get("tidal_id"),
                )
                # Click gesture for album card activation
                gesture = Gtk.GestureClick(button=1)
                gesture.connect(
                    "released", self._on_album_card_click, card,
                )
                card.add_controller(gesture)
                # Right-click gesture for album context menu
                self._attach_album_context_gesture(card)
                self._album_grid.append(card)
                # Load cover art if URL available
                url = getattr(card, "_cover_url", None)
                if url:
                    self._load_album_cover(card, url)
        else:
            self._albums_section.set_visible(False)

        # Top tracks (limit to 50)
        display_tracks = tracks[:50]
        self._clear_list_box(self._track_list)
        for i, track in enumerate(display_tracks):
            row = self._make_track_row(track, i)
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

        # Biography
        if bio:
            markup = self._format_bio(bio)
            self._bio_label.set_use_markup(True)
            self._bio_label.set_markup(markup)
            self._bio_section.set_visible(True)
        else:
            self._bio_section.set_visible(False)

        # Similar artists
        self._clear_box(self._similar_grid)
        if similar_artists:
            self._similar_section.set_visible(True)
            for sa in similar_artists:
                card = self._make_similar_artist_card(
                    sa["name"], sa.get("image_url"),
                )
                # Click gesture for similar artist activation
                gesture = Gtk.GestureClick(button=1)
                gesture.connect(
                    "released", self._on_similar_card_click, card,
                )
                card.add_controller(gesture)
                self._similar_grid.append(card)
        else:
            self._similar_section.set_visible(False)

        # Videos
        self._clear_box(self._videos_grid)
        if videos:
            self._videos_section.set_visible(True)
            for video in videos:
                card = self._make_video_card(video)
                self._videos_grid.append(card)
        else:
            self._videos_section.set_visible(False)

    def _load_artist_image(self, url: str) -> None:
        """Load artist image from URL in a background thread."""
        import urllib.request

        request_token = object()
        self._artist_image_token = request_token

        def _fetch() -> bytes | None:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Auxen/1.0"})
                with urllib.request.urlopen(req, timeout=10) as resp:
                    return resp.read()
            except Exception:
                return None

        def _on_done(data: bytes | None) -> bool:
            if getattr(self, "_artist_image_token", None) is not request_token:
                return False
            if data is not None:
                try:
                    loader = GdkPixbuf.PixbufLoader()
                    loader.write(data)
                    loader.close()
                    pixbuf = loader.get_pixbuf()
                    if pixbuf:
                        texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                        self._artist_image.set_from_paintable(texture)
                        self._artist_image.set_visible(True)
                        self._artist_icon.set_visible(False)
                except Exception:
                    pass
            return False

        import threading

        def _thread():
            data = _fetch()
            GLib.idle_add(_on_done, data)

        threading.Thread(target=_thread, daemon=True).start()

    def _load_album_cover(self, card: Gtk.Box, url: str) -> None:
        """Load album cover art from URL in a background thread."""
        import threading
        import urllib.request

        request_token = object()
        card._art_token = request_token  # type: ignore[attr-defined]

        def _fetch() -> bytes | None:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Auxen/1.0"})
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
                        art_image = getattr(card, "_art_image", None)
                        art_icon = getattr(card, "_art_icon", None)
                        art_box = getattr(card, "_art_box", None)
                        if art_image:
                            art_image.set_from_paintable(texture)
                            art_image.set_visible(True)
                        if art_icon:
                            art_icon.set_visible(False)
                        if art_box:
                            art_box.remove_css_class("album-art-placeholder")
                except Exception:
                    pass
            return False

        def _thread():
            data = _fetch()
            GLib.idle_add(_on_done, data)

        threading.Thread(target=_thread, daemon=True).start()

    # ---- Internal ----

    def _make_track_row(
        self, track: Track, index: int,
    ) -> Gtk.ListBoxRow:
        """Build a single track row."""
        row_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        row_box.add_css_class("album-detail-track-row")

        # Track number
        track_num = track.track_number if track.track_number else index + 1
        num_label = Gtk.Label(label=str(track_num))
        num_label.add_css_class("album-detail-track-number")
        num_label.set_xalign(1)
        num_label.set_size_request(32, -1)
        row_box.append(num_label)

        # Title + Album column
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_hexpand(True)
        text_box.set_valign(Gtk.Align.CENTER)

        title_label = Gtk.Label(label=track.title)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(40)
        title_label.add_css_class("body")
        text_box.append(title_label)

        if track.album:
            album_label = Gtk.Label(label=track.album)
            album_label.set_xalign(0)
            album_label.set_ellipsize(Pango.EllipsizeMode.END)
            album_label.set_max_width_chars(40)
            album_label.add_css_class("caption")
            album_label.add_css_class("dim-label")
            text_box.append(album_label)

        row_box.append(text_box)

        # Source badge
        source_badge = _make_source_badge(track.source.value)
        row_box.append(source_badge)

        # Duration
        dur_label = Gtk.Label(label=_format_duration(track.duration))
        dur_label.add_css_class("caption")
        dur_label.add_css_class("dim-label")
        dur_label.set_valign(Gtk.Align.CENTER)
        row_box.append(dur_label)

        row = Gtk.ListBoxRow()
        row.add_css_class("track-row-hover")
        row.set_child(row_box)
        row.set_activatable(True)
        row._track_data = track  # type: ignore[attr-defined]
        return row

    def _make_similar_artist_card(
        self, name: str, image_url: str | None,
    ) -> Gtk.Box:
        """Build a clickable card for a similar artist."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
            halign=Gtk.Align.CENTER,
        )
        card.add_css_class("similar-artist-card")

        # Artist image placeholder
        img_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        img_box.add_css_class("similar-artist-image-box")
        img_box.set_size_request(80, 80)
        img_box.set_vexpand(False)

        icon = Gtk.Image.new_from_icon_name("avatar-default-symbolic")
        icon.set_pixel_size(32)
        icon.set_opacity(0.4)
        icon.set_halign(Gtk.Align.CENTER)
        icon.set_valign(Gtk.Align.CENTER)
        icon.set_vexpand(True)
        img_box.append(icon)

        img = Gtk.Image()
        img.set_pixel_size(80)
        img.set_size_request(80, 80)
        img.set_halign(Gtk.Align.FILL)
        img.set_valign(Gtk.Align.FILL)
        img.add_css_class("similar-artist-photo")
        img.set_visible(False)
        img_box.append(img)

        card.append(img_box)

        name_label = Gtk.Label(label=name)
        name_label.set_ellipsize(Pango.EllipsizeMode.END)
        name_label.set_max_width_chars(12)
        name_label.add_css_class("caption")
        card.append(name_label)

        card._artist_name = name  # type: ignore[attr-defined]

        # Load image if available
        if image_url:
            self._load_similar_artist_image(card, icon, img, img_box, image_url)

        return card

    def _load_similar_artist_image(
        self,
        card: Gtk.Box,
        icon: Gtk.Image,
        img: Gtk.Image,
        img_box: Gtk.Box,
        url: str,
    ) -> None:
        """Load a similar artist's image from URL."""
        import threading
        import urllib.request

        request_token = object()
        card._img_token = request_token  # type: ignore[attr-defined]

        def _fetch() -> bytes | None:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Auxen/1.0"})
                with urllib.request.urlopen(req, timeout=10) as resp:
                    return resp.read()
            except Exception:
                return None

        def _on_done(data: bytes | None) -> bool:
            if getattr(card, "_img_token", None) is not request_token:
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
                        img_box.remove_css_class("similar-artist-image-box")
                except Exception:
                    pass
            return False

        def _thread():
            data = _fetch()
            GLib.idle_add(_on_done, data)

        threading.Thread(target=_thread, daemon=True).start()

    def _on_similar_card_click(
        self,
        gesture: Gtk.GestureClick,
        _n_press: int,
        _x: float,
        _y: float,
        card: Gtk.Box,
    ) -> None:
        """Handle a similar artist card click."""
        if self._similar_drag_helper.is_dragging:
            return
        name = getattr(card, "_artist_name", None)
        if name and self._on_similar_artist_clicked:
            self._on_similar_artist_clicked(name)

    def _on_album_card_click(
        self,
        gesture: Gtk.GestureClick,
        _n_press: int,
        _x: float,
        _y: float,
        card: Gtk.Box,
    ) -> None:
        """Handle an album card click in the artist's albums row."""
        # Suppress click if the user was dragging to scroll
        if self._albums_drag_helper.is_dragging:
            return
        album_title = getattr(card, "_album_title", None)
        tidal_id = getattr(card, "_tidal_id", None)
        if (
            album_title is not None
            and self._on_album_clicked is not None
        ):
            self._on_album_clicked(album_title, self._artist_name, tidal_id)

    def _attach_album_context_gesture(self, card: Gtk.Box) -> None:
        """Attach a right-click gesture to an album card."""
        if self._album_context_callbacks is None:
            return
        album_title = getattr(card, "_album_title", None)
        album_artist = getattr(card, "_album_artist", self._artist_name)
        if album_title is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(g, n_press, x, y, a=album_title, ar=album_artist):
            if n_press != 1:
                return
            self._show_album_context_menu(card, x, y, a, ar)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        card.add_controller(gesture)

    def _show_album_context_menu(
        self, widget: Gtk.Widget, x: float, y: float,
        album_name: str, artist: str,
    ) -> None:
        """Create and display a context menu for an album card."""
        if self._album_context_callbacks is None:
            return
        playlists = []
        if self._get_album_playlists is not None:
            playlists = self._get_album_playlists()

        cbs = self._album_context_callbacks
        _noop = lambda *_args: None
        callbacks = {
            "on_play_album": lambda a=album_name, ar=artist: cbs.get("on_play_album", _noop)(a, ar),
            "on_play_album_next": lambda a=album_name, ar=artist: cbs.get("on_play_album_next", _noop)(a, ar),
            "on_add_album_to_queue": lambda a=album_name, ar=artist: cbs.get("on_add_album_to_queue", _noop)(a, ar),
            "on_add_to_playlist": lambda pid, a=album_name, ar=artist: cbs.get("on_add_to_playlist", _noop)(a, ar, pid),
            "on_new_playlist": lambda a=album_name, ar=artist: cbs.get("on_new_playlist", _noop)(a, ar),
            "on_add_to_favorites": lambda a=album_name, ar=artist: cbs.get("on_add_to_favorites", _noop)(a, ar),
            "on_go_to_artist": lambda a=album_name, ar=artist: cbs.get("on_go_to_artist", _noop)(a, ar),
            "on_shuffle_album": lambda a=album_name, ar=artist: cbs.get("on_shuffle_album", _noop)(a, ar),
            "on_properties": lambda a=album_name, ar=artist: cbs.get("on_properties", _noop)(a, ar),
        }
        album_data = {"album": album_name, "artist": artist}
        self._current_menu = AlbumContextMenu(
            album_data=album_data, callbacks=callbacks, playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    def _on_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow,
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
        """Handle the Shuffle button."""
        if self._on_play_all is not None and self._tracks:
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

    def _make_video_card(self, video: dict) -> Gtk.Box:
        """Build a clickable card for a video."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")

        # Thumbnail placeholder
        thumb_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        thumb_box.add_css_class("album-art-placeholder")
        thumb_box.set_size_request(220, 124)
        thumb_box.set_vexpand(False)

        thumb_icon = Gtk.Image.new_from_icon_name(
            "video-x-generic-symbolic"
        )
        thumb_icon.set_pixel_size(48)
        thumb_icon.set_opacity(0.4)
        thumb_icon.set_halign(Gtk.Align.CENTER)
        thumb_icon.set_valign(Gtk.Align.CENTER)
        thumb_icon.set_vexpand(True)
        thumb_box.append(thumb_icon)

        thumb_image = Gtk.Image()
        thumb_image.set_pixel_size(220)
        thumb_image.set_size_request(220, 124)
        thumb_image.set_halign(Gtk.Align.FILL)
        thumb_image.set_valign(Gtk.Align.FILL)
        thumb_image.add_css_class("album-card-art-image")
        thumb_image.set_visible(False)
        thumb_box.append(thumb_image)

        card.append(thumb_box)

        # Video title
        title_label = Gtk.Label(
            label=video.get("title", "")
        )
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(24)
        title_label.add_css_class("body")
        title_label.set_margin_start(4)
        title_label.set_margin_end(4)
        card.append(title_label)

        # Duration
        duration = video.get("duration", 0)
        if duration:
            dur_label = Gtk.Label(
                label=_format_duration(duration)
            )
            dur_label.set_xalign(0)
            dur_label.add_css_class("caption")
            dur_label.add_css_class("dim-label")
            dur_label.set_margin_start(4)
            dur_label.set_margin_end(4)
            card.append(dur_label)

        # Click opens video externally
        video_url = video.get("video_url", "")
        if video_url:
            gesture = Gtk.GestureClick(button=1)

            def _on_click(
                _g, _n, _x, _y, url=video_url
            ):
                launcher = Gtk.UriLauncher.new(url)
                launcher.launch(None, None, None)

            gesture.connect("released", _on_click)
            card.add_controller(gesture)
            card.set_cursor(Gdk.Cursor.new_from_name("pointer"))

        # Load thumbnail async
        thumbnail_url = video.get("thumbnail_url")
        if thumbnail_url:
            self._load_video_thumbnail(
                card, thumb_icon, thumb_image, thumb_box,
                thumbnail_url,
            )

        return card

    def _load_video_thumbnail(
        self,
        card: Gtk.Box,
        icon: Gtk.Image,
        img: Gtk.Image,
        thumb_box: Gtk.Box,
        url: str,
    ) -> None:
        """Load a video thumbnail from URL."""
        import threading
        import urllib.request

        request_token = object()
        card._thumb_token = request_token  # type: ignore[attr-defined]

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
            if getattr(card, "_thumb_token", None) is not request_token:
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
                        thumb_box.remove_css_class(
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
    def _format_bio(bio: str) -> str:
        """Convert Tidal bio markup to Pango markup."""
        import re

        text = bio
        # Convert <br>, <br/>, <br /> to newlines
        text = re.sub(r'<br\s*/?>', '\n', text)
        # Convert [wimpLink ...]Name[/wimpLink] to placeholder tokens
        # (do this before HTML processing to protect the content)
        links: list[str] = []
        def _save_link(m):
            links.append(m.group(1))
            return f'\x00LINK{len(links) - 1}\x00'
        text = re.sub(
            r'\[wimpLink[^\]]*\](.*?)\[/wimpLink\]', _save_link, text,
        )
        # Preserve Pango-compatible HTML tags: b, i, u, s, sub, sup
        kept_tags: dict[str, str] = {}
        def _save_tag(m):
            tag = m.group(0)
            key = f'\x00TAG{len(kept_tags)}\x00'
            kept_tags[key] = tag
            return key
        text = re.sub(
            r'</?(?:b|i|u|s|sub|sup|big|small|tt|span)[^>]*>',
            _save_tag, text, flags=re.IGNORECASE,
        )
        # Strip all remaining HTML tags
        text = re.sub(r'<[^>]+>', '', text)
        # Escape Pango special chars in the remaining text
        text = text.replace('&', '&amp;')
        text = text.replace('<', '&lt;')
        text = text.replace('>', '&gt;')
        # Restore kept HTML tags
        for key, tag in kept_tags.items():
            text = text.replace(key, tag)
        # Restore wimpLink content as bold
        for i, name in enumerate(links):
            safe_name = name.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            text = text.replace(f'\x00LINK{i}\x00', f'<b>{safe_name}</b>')
        # Collapse excessive blank lines
        text = re.sub(r'\n{3,}', '\n\n', text).strip()
        return text

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
