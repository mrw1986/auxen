"""Library browsing view for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import GLib, Gtk, Pango

from auxen.models import Source
from auxen.views.context_menu import TrackContextMenu

logger = logging.getLogger(__name__)

# Sort options for each view mode
_SORT_OPTIONS_ALBUMS = [
    "Recently Added",
    "Name (A-Z)",
    "Name (Z-A)",
    "Artist",
]

_SORT_OPTIONS_ARTISTS = [
    "Name (A-Z)",
    "Name (Z-A)",
    "Track Count",
]

_SORT_OPTIONS_TRACKS = [
    "Recently Added",
    "Name (A-Z)",
    "Name (Z-A)",
    "Artist",
]


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    if seconds is None or seconds <= 0:
        return "0:00"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


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
    album: str, artist: str, source: str
) -> Gtk.FlowBoxChild:
    """Build a single album card for the library grid."""
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

    # Title
    title_label = Gtk.Label(label=album)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(18)
    title_label.add_css_class("body")
    title_label.set_margin_start(4)
    title_label.set_margin_end(4)
    card.append(title_label)

    # Artist
    artist_label = Gtk.Label(label=artist)
    artist_label.set_xalign(0)
    artist_label.set_ellipsize(Pango.EllipsizeMode.END)
    artist_label.set_max_width_chars(18)
    artist_label.add_css_class("caption")
    artist_label.add_css_class("dim-label")
    artist_label.set_margin_start(4)
    artist_label.set_margin_end(4)
    card.append(artist_label)

    child = Gtk.FlowBoxChild()
    child.set_child(card)
    # Store album/artist data for click handling
    child._album_title = album  # type: ignore[attr-defined]
    child._album_artist = artist  # type: ignore[attr-defined]
    return child


def _make_artist_row(
    artist: str, track_count: int, sources: list[str]
) -> Gtk.ListBoxRow:
    """Build a single row for the artists list."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("library-artist-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    # Artist icon placeholder
    icon = Gtk.Image.new_from_icon_name("avatar-default-symbolic")
    icon.set_pixel_size(32)
    icon.set_opacity(0.5)
    icon.set_valign(Gtk.Align.CENTER)
    row_box.append(icon)

    # Artist name
    name_label = Gtk.Label(label=artist)
    name_label.set_xalign(0)
    name_label.set_hexpand(True)
    name_label.set_ellipsize(Pango.EllipsizeMode.END)
    name_label.set_max_width_chars(40)
    name_label.add_css_class("body")
    name_label.set_valign(Gtk.Align.CENTER)
    row_box.append(name_label)

    # Source badges
    for src in sorted(set(sources)):
        badge = _make_source_badge(src)
        row_box.append(badge)

    # Track count
    count_label = Gtk.Label(
        label=f"{track_count} track{'s' if track_count != 1 else ''}"
    )
    count_label.add_css_class("caption")
    count_label.add_css_class("library-track-count")
    count_label.set_valign(Gtk.Align.CENTER)
    row_box.append(count_label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    row.set_activatable(True)
    # Store artist name for click handling
    row._artist_name = artist  # type: ignore[attr-defined]
    return row


def _make_track_row(track) -> Gtk.ListBoxRow:
    """Build a single row for the tracks list."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("library-track-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    # Mini album art placeholder
    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.add_css_class("album-art-mini")
    art_box.set_size_request(48, 48)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(20)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_vexpand(True)
    art_box.append(art_icon)
    row_box.append(art_box)

    # Title + Artist + Album column
    text_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=2,
    )
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_label = Gtk.Label()
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(40)
    title_label.add_css_class("body")
    title_label.set_markup(
        f"<b>{GLib.markup_escape_text(track.title)}</b>"
    )
    text_box.append(title_label)

    subtitle_parts = [track.artist]
    if track.album:
        subtitle_parts.append(track.album)
    subtitle_text = " \u2014 ".join(subtitle_parts)

    subtitle_label = Gtk.Label(label=subtitle_text)
    subtitle_label.set_xalign(0)
    subtitle_label.set_ellipsize(Pango.EllipsizeMode.END)
    subtitle_label.set_max_width_chars(50)
    subtitle_label.add_css_class("caption")
    subtitle_label.add_css_class("dim-label")
    text_box.append(subtitle_label)

    row_box.append(text_box)

    # Source badge
    source_badge = _make_source_badge(track.source.value)
    row_box.append(source_badge)

    # Quality badge
    quality = track.quality_label
    if quality and quality != "Unknown":
        quality_badge = Gtk.Label(label=quality)
        quality_badge.add_css_class("favorites-quality-badge")
        quality_badge.set_valign(Gtk.Align.CENTER)
        row_box.append(quality_badge)

    # Duration
    dur_label = Gtk.Label(label=_format_duration(track.duration))
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    dur_label.set_margin_start(4)
    row_box.append(dur_label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    row.set_activatable(True)
    # Store track reference for context menu
    row._track_data = track  # type: ignore[attr-defined]
    return row


class LibraryView(Gtk.Box):
    """Library browsing view with albums, artists, and tracks modes."""

    __gtype_name__ = "LibraryView"

    def __init__(self, **kwargs) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
            **kwargs,
        )

        self._db = None
        self._active_filter: str = "All"
        self._active_view: str = "albums"
        self._active_sort: str = "Recently Added"

        # Callbacks
        self._on_album_clicked: Optional[
            Callable[[str, str], None]
        ] = None
        self._on_play_track: Optional[Callable] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None

        # Track data caches
        self._all_tracks: list = []
        self._all_albums: list[dict] = []
        self._all_artists: list[dict] = []

        # ---- Non-scrollable header section ----
        header_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
        )
        header_section.set_margin_top(24)
        header_section.set_margin_start(32)
        header_section.set_margin_end(32)

        # 1. Header with title and track count
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header_box.add_css_class("library-header")

        lib_icon = Gtk.Image.new_from_icon_name(
            "library-music-symbolic"
        )
        lib_icon.set_pixel_size(28)
        lib_icon.add_css_class("favorites-header-icon")
        header_box.append(lib_icon)

        title_label = Gtk.Label(label="Library")
        title_label.set_xalign(0)
        title_label.add_css_class("greeting-label")
        header_box.append(title_label)

        self._count_label = Gtk.Label()
        self._count_label.set_xalign(0)
        self._count_label.add_css_class("caption")
        self._count_label.add_css_class("library-track-count")
        self._count_label.set_valign(Gtk.Align.END)
        self._count_label.set_margin_bottom(6)
        header_box.append(self._count_label)

        header_section.append(header_box)

        # 2. View mode toggle + filter tabs + sort dropdown
        controls_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
        )

        # View mode buttons
        view_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
        )
        view_box.add_css_class("library-view-toggle")

        self._view_buttons: list[Gtk.ToggleButton] = []
        for label_text, view_name in [
            ("Albums", "albums"),
            ("Artists", "artists"),
            ("Tracks", "tracks"),
        ]:
            btn = Gtk.ToggleButton(label=label_text)
            btn.add_css_class("filter-btn")
            btn._view_name = view_name  # type: ignore[attr-defined]
            btn.connect("toggled", self._on_view_toggled)
            view_box.append(btn)
            self._view_buttons.append(btn)

        controls_box.append(view_box)

        # Separator
        sep = Gtk.Separator(orientation=Gtk.Orientation.VERTICAL)
        sep.set_margin_top(4)
        sep.set_margin_bottom(4)
        controls_box.append(sep)

        # Filter tabs (All / Tidal / Local)
        filter_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )

        self._filter_buttons: list[Gtk.ToggleButton] = []
        for label_text in ("All", "Tidal", "Local"):
            btn = Gtk.ToggleButton(label=label_text)
            btn.add_css_class("filter-btn")
            btn.connect("toggled", self._on_filter_toggled)
            filter_box.append(btn)
            self._filter_buttons.append(btn)

        controls_box.append(filter_box)

        # Spacer
        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        controls_box.append(spacer)

        # Sort dropdown
        sort_label = Gtk.Label(label="Sort by:")
        sort_label.add_css_class("caption")
        sort_label.add_css_class("dim-label")
        sort_label.set_valign(Gtk.Align.CENTER)
        controls_box.append(sort_label)

        self._sort_model = Gtk.StringList.new(_SORT_OPTIONS_ALBUMS)
        self._sort_dropdown = Gtk.DropDown(model=self._sort_model)
        self._sort_dropdown.set_selected(0)
        self._sort_dropdown.add_css_class("favorites-sort-dropdown")
        self._sort_dropdown.set_valign(Gtk.Align.CENTER)
        self._sort_dropdown.connect(
            "notify::selected", self._on_sort_changed
        )
        controls_box.append(self._sort_dropdown)

        header_section.append(controls_box)

        self.append(header_section)

        # ---- Scrollable content area ----
        self._scrolled = Gtk.ScrolledWindow()
        self._scrolled.set_policy(
            Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC
        )
        self._scrolled.set_vexpand(True)

        # Content stack for view modes
        self._content_stack = Gtk.Stack()
        self._content_stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._content_stack.set_transition_duration(150)

        # Albums view (FlowBox grid)
        albums_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        albums_container.set_margin_top(16)
        albums_container.set_margin_start(32)
        albums_container.set_margin_end(32)
        albums_container.set_margin_bottom(32)

        self._album_grid = Gtk.FlowBox()
        self._album_grid.set_homogeneous(True)
        self._album_grid.set_min_children_per_line(2)
        self._album_grid.set_max_children_per_line(6)
        self._album_grid.set_column_spacing(16)
        self._album_grid.set_row_spacing(16)
        self._album_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._album_grid.connect(
            "child-activated", self._on_album_card_activated
        )
        albums_container.append(self._album_grid)

        self._content_stack.add_named(albums_container, "albums")

        # Artists view (ListBox)
        artists_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        artists_container.set_margin_top(16)
        artists_container.set_margin_start(32)
        artists_container.set_margin_end(32)
        artists_container.set_margin_bottom(32)

        self._artist_list = Gtk.ListBox()
        self._artist_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._artist_list.add_css_class("boxed-list")
        self._artist_list.connect(
            "row-activated", self._on_artist_row_activated
        )
        artists_container.append(self._artist_list)

        self._content_stack.add_named(artists_container, "artists")

        # Tracks view (ListBox)
        tracks_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        tracks_container.set_margin_top(16)
        tracks_container.set_margin_start(32)
        tracks_container.set_margin_end(32)
        tracks_container.set_margin_bottom(32)

        self._track_list = Gtk.ListBox()
        self._track_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._track_list.add_css_class("boxed-list")
        self._track_list.connect(
            "row-activated", self._on_track_row_activated
        )
        tracks_container.append(self._track_list)

        self._content_stack.add_named(tracks_container, "tracks")

        # Empty state
        empty_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        empty_box.add_css_class("favorites-empty-state")
        empty_box.set_vexpand(True)
        empty_box.set_margin_top(80)

        empty_icon = Gtk.Image.new_from_icon_name(
            "folder-music-symbolic"
        )
        empty_icon.set_pixel_size(64)
        empty_box.append(empty_icon)

        empty_title = Gtk.Label(label="No music in library")
        empty_title.add_css_class("title-3")
        empty_box.append(empty_title)

        empty_subtitle = Gtk.Label(
            label="Add local files or connect Tidal in Settings"
        )
        empty_subtitle.add_css_class("caption")
        empty_box.append(empty_subtitle)

        self._content_stack.add_named(empty_box, "empty")

        self._scrolled.set_child(self._content_stack)
        self.append(self._scrolled)

        # Activate "Albums" view and "All" filter by default
        self._view_buttons[0].set_active(True)
        self._filter_buttons[0].set_active(True)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_database(self, db) -> None:
        """Wire the library view to a database."""
        self._db = db
        self.refresh()

    def refresh(self) -> None:
        """Reload all data from the database and refresh the display."""
        if self._db is None:
            return
        try:
            self._all_albums = self._db.get_albums()
            self._all_artists = self._db.get_artists()
            self._all_tracks = self._db.get_all_tracks()
            self._update_count_label()
            self._refresh_current_view()
        except Exception:
            logger.warning(
                "Failed to refresh library view", exc_info=True
            )

    def set_callbacks(
        self,
        on_album_clicked: Callable[[str, str], None] | None = None,
        on_play_track: Callable | None = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_album_clicked:
            Called with (album_name, artist) when an album card is clicked.
        on_play_track:
            Called with a Track object when a track is clicked.
        """
        self._on_album_clicked = on_album_clicked
        self._on_play_track = on_play_track

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    # ------------------------------------------------------------------
    # Context menu helpers
    # ------------------------------------------------------------------

    def _attach_context_gesture(
        self, row: Gtk.ListBoxRow, track
    ) -> None:
        """Attach a right-click gesture to a track row."""
        if self._context_callbacks is None or track is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(_gesture, _n_press, x, y, trk=track):
            self._show_track_context_menu(row, x, y, trk)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_track_context_menu(
        self, widget: Gtk.Widget, x: float, y: float, track
    ) -> None:
        """Create and display a context menu for a track."""
        if self._context_callbacks is None:
            return

        playlists = []
        if self._get_playlists is not None:
            playlists = self._get_playlists()

        callbacks = {
            "on_play": lambda t=track: self._context_callbacks["on_play"](t),
            "on_play_next": lambda t=track: self._context_callbacks["on_play_next"](t),
            "on_add_to_queue": lambda t=track: self._context_callbacks["on_add_to_queue"](t),
            "on_add_to_playlist": lambda pid, t=track: self._context_callbacks["on_add_to_playlist"](t, pid),
            "on_new_playlist": lambda t=track: self._context_callbacks["on_new_playlist"](t),
            "on_toggle_favorite": lambda t=track: self._context_callbacks["on_toggle_favorite"](t),
            "on_go_to_album": lambda t=track: self._context_callbacks["on_go_to_album"](t),
        }

        track_data = {
            "id": getattr(track, "id", None),
            "title": getattr(track, "title", ""),
            "artist": getattr(track, "artist", ""),
            "album": getattr(track, "album", ""),
            "source": getattr(track, "source", None),
            "is_favorite": False,
        }

        menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        menu.show(widget, x, y)

    # ------------------------------------------------------------------
    # View mode switching
    # ------------------------------------------------------------------

    def _on_view_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior for view mode buttons."""
        if not toggled_btn.get_active():
            any_active = any(
                b.get_active() for b in self._view_buttons
            )
            if not any_active:
                toggled_btn.set_active(True)
            return

        # Deactivate all other buttons
        for btn in self._view_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)

        view_name = getattr(toggled_btn, "_view_name", "albums")
        self._active_view = view_name
        self._update_sort_options()
        self._refresh_current_view()

    def _update_sort_options(self) -> None:
        """Update the sort dropdown options based on the active view."""
        if self._active_view == "albums":
            options = _SORT_OPTIONS_ALBUMS
        elif self._active_view == "artists":
            options = _SORT_OPTIONS_ARTISTS
        else:
            options = _SORT_OPTIONS_TRACKS

        self._sort_model = Gtk.StringList.new(options)
        self._sort_dropdown.set_model(self._sort_model)
        self._sort_dropdown.set_selected(0)
        self._active_sort = options[0]

    # ------------------------------------------------------------------
    # Filter handling
    # ------------------------------------------------------------------

    def _on_filter_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior for filter tabs."""
        if not toggled_btn.get_active():
            any_active = any(
                b.get_active() for b in self._filter_buttons
            )
            if not any_active:
                toggled_btn.set_active(True)
            return

        for btn in self._filter_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)

        self._active_filter = toggled_btn.get_label() or "All"
        self._refresh_current_view()

    # ------------------------------------------------------------------
    # Sort handling
    # ------------------------------------------------------------------

    def _on_sort_changed(
        self, dropdown: Gtk.DropDown, _pspec: object
    ) -> None:
        """Handle sort dropdown selection changes."""
        idx = dropdown.get_selected()
        # Use the correct options list for the active view
        if self._active_view == "albums":
            options = _SORT_OPTIONS_ALBUMS
        elif self._active_view == "artists":
            options = _SORT_OPTIONS_ARTISTS
        else:
            options = _SORT_OPTIONS_TRACKS

        if 0 <= idx < len(options):
            self._active_sort = options[idx]
            self._refresh_current_view()

    # ------------------------------------------------------------------
    # Data filtering and sorting
    # ------------------------------------------------------------------

    def _get_source_filter(self) -> Source | None:
        """Return the Source enum for the active filter, or None for All."""
        if self._active_filter == "Tidal":
            return Source.TIDAL
        if self._active_filter == "Local":
            return Source.LOCAL
        return None

    def _get_filtered_albums(self) -> list[dict]:
        """Return albums filtered by the active source filter."""
        source = self._get_source_filter()
        if source is None:
            return list(self._all_albums)
        return [
            a for a in self._all_albums if a["source"] == source.value
        ]

    def _get_sorted_albums(
        self, albums: list[dict]
    ) -> list[dict]:
        """Sort albums by the active sort criterion."""
        if self._active_sort == "Name (A-Z)":
            return sorted(
                albums,
                key=lambda a: (a["album"] or "").lower(),
            )
        if self._active_sort == "Name (Z-A)":
            return sorted(
                albums,
                key=lambda a: (a["album"] or "").lower(),
                reverse=True,
            )
        if self._active_sort == "Artist":
            return sorted(
                albums,
                key=lambda a: (a["artist"] or "").lower(),
            )
        # Default: Recently Added (already ordered by added_at desc)
        return albums

    def _get_filtered_artists(self) -> list[dict]:
        """Return artists filtered by the active source filter."""
        source = self._get_source_filter()
        if source is None:
            return list(self._all_artists)
        return [
            a
            for a in self._all_artists
            if source.value in a["sources"]
        ]

    def _get_sorted_artists(
        self, artists: list[dict]
    ) -> list[dict]:
        """Sort artists by the active sort criterion."""
        if self._active_sort == "Name (Z-A)":
            return sorted(
                artists,
                key=lambda a: (a["artist"] or "").lower(),
                reverse=True,
            )
        if self._active_sort == "Track Count":
            return sorted(
                artists,
                key=lambda a: a["track_count"],
                reverse=True,
            )
        # Default: Name (A-Z) -- already ordered
        return artists

    def _get_filtered_tracks(self) -> list:
        """Return tracks filtered by the active source filter."""
        source = self._get_source_filter()
        if source is None:
            return list(self._all_tracks)
        return [
            t for t in self._all_tracks if t.source == source
        ]

    def _get_sorted_tracks(self, tracks: list) -> list:
        """Sort tracks by the active sort criterion."""
        if self._active_sort == "Name (A-Z)":
            return sorted(
                tracks, key=lambda t: (t.title or "").lower()
            )
        if self._active_sort == "Name (Z-A)":
            return sorted(
                tracks,
                key=lambda t: (t.title or "").lower(),
                reverse=True,
            )
        if self._active_sort == "Artist":
            return sorted(
                tracks,
                key=lambda t: (t.artist or "").lower(),
            )
        # Default: Recently Added (already ordered)
        return tracks

    # ------------------------------------------------------------------
    # View refresh
    # ------------------------------------------------------------------

    def _update_count_label(self) -> None:
        """Update the track count label in the header."""
        count = len(self._all_tracks)
        track_word = "track" if count == 1 else "tracks"
        self._count_label.set_label(f"{count} {track_word}")

    def _refresh_current_view(self) -> None:
        """Refresh the currently active view."""
        if self._active_view == "albums":
            self._refresh_albums()
        elif self._active_view == "artists":
            self._refresh_artists()
        else:
            self._refresh_tracks()

    def _refresh_albums(self) -> None:
        """Rebuild the albums grid."""
        self._clear_flow_box(self._album_grid)

        filtered = self._get_filtered_albums()
        sorted_albums = self._get_sorted_albums(filtered)

        if not sorted_albums:
            self._content_stack.set_visible_child_name("empty")
            return

        for album_data in sorted_albums:
            self._album_grid.append(
                _make_album_card(
                    album=album_data["album"],
                    artist=album_data["artist"],
                    source=album_data["source"],
                )
            )
        self._content_stack.set_visible_child_name("albums")

    def _refresh_artists(self) -> None:
        """Rebuild the artists list."""
        self._clear_list_box(self._artist_list)

        filtered = self._get_filtered_artists()
        sorted_artists = self._get_sorted_artists(filtered)

        if not sorted_artists:
            self._content_stack.set_visible_child_name("empty")
            return

        for artist_data in sorted_artists:
            self._artist_list.append(
                _make_artist_row(
                    artist=artist_data["artist"],
                    track_count=artist_data["track_count"],
                    sources=artist_data["sources"],
                )
            )
        self._content_stack.set_visible_child_name("artists")

    def _refresh_tracks(self) -> None:
        """Rebuild the tracks list."""
        self._clear_list_box(self._track_list)

        filtered = self._get_filtered_tracks()
        sorted_tracks = self._get_sorted_tracks(filtered)

        if not sorted_tracks:
            self._content_stack.set_visible_child_name("empty")
            return

        for track in sorted_tracks:
            row = _make_track_row(track)
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

        self._content_stack.set_visible_child_name("tracks")

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_album_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle an album card being clicked."""
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if (
            album_title is not None
            and album_artist is not None
            and self._on_album_clicked is not None
        ):
            self._on_album_clicked(album_title, album_artist)

    def _on_artist_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle an artist row being clicked.

        Filters the library to show only that artist's tracks.
        """
        artist_name = getattr(row, "_artist_name", None)
        if artist_name is not None:
            # Switch to tracks view filtered by this artist
            self._active_view = "tracks"
            # Update view toggle buttons
            for btn in self._view_buttons:
                view_name = getattr(btn, "_view_name", "")
                btn.handler_block_by_func(self._on_view_toggled)
                btn.set_active(view_name == "tracks")
                btn.handler_unblock_by_func(self._on_view_toggled)
            self._update_sort_options()
            # Filter tracks to this artist
            source = self._get_source_filter()
            filtered = [
                t
                for t in self._all_tracks
                if t.artist == artist_name
                and (source is None or t.source == source)
            ]
            self._clear_list_box(self._track_list)
            if not filtered:
                self._content_stack.set_visible_child_name("empty")
                return
            for track in filtered:
                row = _make_track_row(track)
                self._attach_context_gesture(row, track)
                self._track_list.append(row)
            self._content_stack.set_visible_child_name("tracks")

    def _on_track_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle a track row being clicked to play."""
        index = row.get_index()
        # Build the current visible track list from the active filter/sort
        source = self._get_source_filter()
        filtered = self._get_filtered_tracks()
        sorted_tracks = self._get_sorted_tracks(filtered)
        if 0 <= index < len(sorted_tracks):
            track = sorted_tracks[index]
            if self._on_play_track is not None:
                self._on_play_track(track)

    # ------------------------------------------------------------------
    # Static helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _clear_flow_box(flow_box: Gtk.FlowBox) -> None:
        """Remove all children from a FlowBox."""
        while True:
            child = flow_box.get_child_at_index(0)
            if child is None:
                break
            flow_box.remove(child)

    @staticmethod
    def _clear_list_box(list_box: Gtk.ListBox) -> None:
        """Remove all rows from a ListBox."""
        while True:
            row = list_box.get_row_at_index(0)
            if row is None:
                break
            list_box.remove(row)
