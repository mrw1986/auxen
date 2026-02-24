"""Artist detail page view for the Auxen music player."""

from __future__ import annotations

import logging
import random
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gtk, Pango

from auxen.models import Track
from auxen.views.context_menu import TrackContextMenu

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


def _make_source_badge(source: str) -> Gtk.Label:
    """Create a small pill badge indicating the track source."""
    badge = Gtk.Label(label=source.capitalize())
    css_class = (
        "source-badge-tidal" if source == "tidal" else "source-badge-local"
    )
    badge.add_css_class(css_class)
    badge.set_valign(Gtk.Align.CENTER)
    return badge


def _make_album_card(
    album: str, track_count: int, year: int | None, source: str,
) -> Gtk.FlowBoxChild:
    """Build a single album card for the artist's albums row."""
    card = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=6,
    )
    card.add_css_class("album-card")

    # Album art with overlay badge
    overlay = Gtk.Overlay()

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

    overlay.set_child(art_box)

    badge = Gtk.Label(label=source.capitalize())
    badge_css = (
        "source-badge-tidal" if source == "tidal" else "source-badge-local"
    )
    badge.add_css_class(badge_css)
    badge.set_halign(Gtk.Align.START)
    badge.set_valign(Gtk.Align.START)
    badge.set_margin_top(8)
    badge.set_margin_start(8)
    overlay.add_overlay(badge)

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

    child = Gtk.FlowBoxChild()
    child.set_child(card)
    # Store data for click handling
    child._album_title = album  # type: ignore[attr-defined]
    child._album_source = source  # type: ignore[attr-defined]
    return child


class ArtistDetailView(Gtk.ScrolledWindow):
    """Scrollable artist detail page with header, albums row, and track list."""

    __gtype_name__ = "ArtistDetailView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        # Callbacks
        self._on_play_track: Optional[Callable[[Track], None]] = None
        self._on_play_all: Optional[Callable[[list[Track]], None]] = None
        self._on_back: Optional[Callable[[], None]] = None
        self._on_album_clicked: Optional[
            Callable[[str, str], None]
        ] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None

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

        artist_icon = Gtk.Image.new_from_icon_name(
            "avatar-default-symbolic"
        )
        artist_icon.set_pixel_size(56)
        artist_icon.set_opacity(0.5)
        artist_icon.set_halign(Gtk.Align.CENTER)
        artist_icon.set_valign(Gtk.Align.CENTER)
        artist_icon.set_vexpand(True)
        icon_box.append(artist_icon)
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
        self._albums_section.set_margin_bottom(16)

        albums_header = Gtk.Label(label="Albums")
        albums_header.set_xalign(0)
        albums_header.add_css_class("section-header")
        self._albums_section.append(albums_header)

        # Horizontal scrolling for album cards
        albums_scroll = Gtk.ScrolledWindow()
        albums_scroll.set_policy(
            Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER
        )
        albums_scroll.set_min_content_height(240)
        albums_scroll.add_css_class("artist-detail-albums-row")

        self._album_grid = Gtk.FlowBox()
        self._album_grid.set_homogeneous(True)
        self._album_grid.set_min_children_per_line(2)
        self._album_grid.set_max_children_per_line(10)
        self._album_grid.set_column_spacing(16)
        self._album_grid.set_row_spacing(16)
        self._album_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._album_grid.connect(
            "child-activated", self._on_album_card_activated
        )

        albums_scroll.set_child(self._album_grid)
        self._albums_section.append(albums_scroll)

        self._root.append(self._albums_section)

        # ---- All Tracks section ----
        track_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        track_section.set_margin_start(32)
        track_section.set_margin_end(32)
        track_section.set_margin_bottom(32)

        track_header = Gtk.Label(label="All Tracks")
        track_header.set_xalign(0)
        track_header.add_css_class("section-header")
        track_section.append(track_header)

        self._track_list = Gtk.ListBox()
        self._track_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._track_list.add_css_class("boxed-list")
        self._track_list.connect("row-activated", self._on_row_activated)
        track_section.append(self._track_list)

        self._root.append(track_section)

        self.set_child(self._root)

    # ---- Public API ----

    def set_callbacks(
        self,
        on_play_track: Callable[[Track], None] | None = None,
        on_play_all: Callable[[list[Track]], None] | None = None,
        on_back: Callable[[], None] | None = None,
        on_album_clicked: Callable[[str, str], None] | None = None,
    ) -> None:
        """Set callback functions for user actions."""
        self._on_play_track = on_play_track
        self._on_play_all = on_play_all
        self._on_back = on_back
        self._on_album_clicked = on_album_clicked

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def show_artist(
        self,
        artist_name: str,
        albums: list[dict],
        tracks: list[Track],
        source: str,
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
        """
        self._artist_name = artist_name
        self._albums = list(albums)
        self._tracks = list(tracks)

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

        # Albums grid
        self._clear_flow_box(self._album_grid)
        if albums:
            self._albums_section.set_visible(True)
            for album_data in albums:
                self._album_grid.append(
                    _make_album_card(
                        album=album_data["album"],
                        track_count=album_data["track_count"],
                        year=album_data["year"],
                        source=album_data["source"],
                    )
                )
        else:
            self._albums_section.set_visible(False)

        # Track list
        self._clear_list_box(self._track_list)
        for i, track in enumerate(tracks):
            row = self._make_track_row(track, i)
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

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
        row_box.set_margin_top(4)
        row_box.set_margin_bottom(4)
        row_box.set_margin_start(8)
        row_box.set_margin_end(8)

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
        row.set_child(row_box)
        row.set_activatable(True)
        return row

    def _on_album_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle an album card click in the artist's albums row."""
        album_title = getattr(child, "_album_title", None)
        if (
            album_title is not None
            and self._on_album_clicked is not None
        ):
            self._on_album_clicked(album_title, self._artist_name)

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
        }

        track_data = {
            "id": track.id,
            "title": track.title,
            "artist": track.artist,
            "album": track.album or "",
            "source": track.source,
            "is_favorite": False,
        }

        menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        menu.show(widget, x, y)

    @staticmethod
    def _clear_list_box(list_box: Gtk.ListBox) -> None:
        """Remove all rows from a ListBox."""
        while True:
            row = list_box.get_row_at_index(0)
            if row is None:
                break
            list_box.remove(row)

    @staticmethod
    def _clear_flow_box(flow_box: Gtk.FlowBox) -> None:
        """Remove all children from a FlowBox."""
        while True:
            child = flow_box.get_child_at_index(0)
            if child is None:
                break
            flow_box.remove(child)

    @staticmethod
    def _clear_box(box: Gtk.Box) -> None:
        """Remove all children from a Box."""
        while True:
            child = box.get_first_child()
            if child is None:
                break
            box.remove(child)
