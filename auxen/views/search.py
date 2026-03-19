"""Search view for the Auxen music player."""

from __future__ import annotations

import logging
import random
import threading

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, GLib, GObject, Gtk, Pango

from auxen.views.context_menu import TrackContextMenu
from auxen.views.widgets import DragScrollHelper, format_duration, make_standard_track_row, make_tidal_source_badge

logger = logging.getLogger(__name__)

# Placeholder data pools for generating fake search results.
_SAMPLE_ARTISTS = [
    "Massive Attack",
    "Portishead",
    "Aphex Twin",
    "Bjork",
    "Nine Inch Nails",
    "The Cure",
    "Radiohead",
    "Boards of Canada",
]

_SAMPLE_ALBUMS = [
    "Mezzanine",
    "Dummy",
    "Selected Ambient Works",
    "Homogenic",
    "The Downward Spiral",
    "Disintegration",
    "OK Computer",
    "Music Has the Right to Children",
]

_SAMPLE_DURATIONS = [
    "3:24",
    "4:12",
    "5:01",
    "3:58",
    "6:33",
    "4:47",
    "5:29",
    "2:55",
]

# Result type constants
_TYPE_TRACK = "track"
_TYPE_ALBUM = "album"
_TYPE_ARTIST = "artist"


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    return format_duration(seconds)


def _make_result_row(
    title: str,
    artist: str,
    album: str,
    source: str,
    duration: str,
    track=None,
    on_artist_clicked=None,
    on_album_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a single search result row using the shared widget."""
    # Build a dict-like track for the shared function
    track_dict = {
        "title": title,
        "artist": artist,
        "album": album,
        "source": source,
        "duration": duration,
    }
    row = make_standard_track_row(
        track_dict,
        show_art=True,
        show_source_badge=True,
        show_quality_badge=False,
        show_duration=True,
        art_size=40,
        css_class="search-result-row",
        on_artist_clicked=on_artist_clicked,
        on_album_clicked=on_album_clicked,
    )
    # Store the real track object for context menu use
    row._track_data = track  # type: ignore[attr-defined]
    return row


def _make_album_result_row(
    name: str,
    artist_name: str,
    num_tracks: int | None = None,
    on_clicked=None,
    tidal_id: str | None = None,
) -> Gtk.ListBoxRow:
    """Build a search result row for an album."""
    row = Gtk.ListBoxRow()
    row.add_css_class("search-result-row")
    row.add_css_class("track-row-hover")

    box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    box.set_margin_top(8)
    box.set_margin_bottom(8)
    box.set_margin_start(12)
    box.set_margin_end(12)

    # Album art placeholder
    art_box = Gtk.Box()
    art_box.set_size_request(40, 40)
    art_box.add_css_class("album-art-placeholder")
    art_box.set_valign(Gtk.Align.CENTER)

    art_icon = Gtk.Image.new_from_icon_name("media-optical-symbolic")
    art_icon.set_pixel_size(20)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_box.append(art_icon)

    art_image = Gtk.Image()
    art_image.set_pixel_size(40)
    art_image.set_size_request(40, 40)
    art_image.set_visible(False)
    art_box.append(art_image)

    row._art_icon = art_icon  # type: ignore[attr-defined]
    row._art_image = art_image  # type: ignore[attr-defined]
    row._art_box = art_box  # type: ignore[attr-defined]

    box.append(art_box)

    # Text column
    text_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=2,
    )
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_label = Gtk.Label(label=name)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.add_css_class("heading")
    text_box.append(title_label)

    subtitle = artist_name
    if num_tracks is not None:
        subtitle += f"  \u00b7  {num_tracks} tracks"
    sub_label = Gtk.Label(label=subtitle)
    sub_label.set_xalign(0)
    sub_label.set_ellipsize(Pango.EllipsizeMode.END)
    sub_label.add_css_class("caption")
    sub_label.add_css_class("dim-label")
    text_box.append(sub_label)

    box.append(text_box)

    # Type badge
    badge = Gtk.Label(label="Album")
    badge.add_css_class("caption")
    badge.add_css_class("dim-label")
    badge.set_valign(Gtk.Align.CENTER)
    box.append(badge)

    row.set_child(box)

    # Make the entire row clickable to navigate to album detail
    if on_clicked is not None:
        row.set_cursor_from_name("pointer")
        gesture = Gtk.GestureClick.new()
        gesture.set_button(1)

        def _on_album_row_click(
            gest, n_press, _x, _y,
            _cb=on_clicked, _name=name, _artist=artist_name,
            _tid=tidal_id,
        ):
            if n_press != 1:
                return
            gest.set_state(Gtk.EventSequenceState.CLAIMED)
            _cb(_name, _artist, _tid)

        gesture.connect("released", _on_album_row_click)
        row.add_controller(gesture)

    return row


def _make_artist_result_row(
    name: str,
    on_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a search result row for an artist."""
    row = Gtk.ListBoxRow()
    row.add_css_class("search-result-row")
    row.add_css_class("track-row-hover")

    box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    box.set_margin_top(8)
    box.set_margin_bottom(8)
    box.set_margin_start(12)
    box.set_margin_end(12)

    # Artist image placeholder
    art_box = Gtk.Box()
    art_box.set_size_request(40, 40)
    art_box.add_css_class("album-art-placeholder")
    art_box.set_valign(Gtk.Align.CENTER)

    art_icon = Gtk.Image.new_from_icon_name(
        "avatar-default-symbolic"
    )
    art_icon.set_pixel_size(20)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_box.append(art_icon)

    art_image = Gtk.Image()
    art_image.set_pixel_size(40)
    art_image.set_size_request(40, 40)
    art_image.set_visible(False)
    art_box.append(art_image)

    row._art_icon = art_icon  # type: ignore[attr-defined]
    row._art_image = art_image  # type: ignore[attr-defined]
    row._art_box = art_box  # type: ignore[attr-defined]

    box.append(art_box)

    # Artist name
    name_label = Gtk.Label(label=name)
    name_label.set_xalign(0)
    name_label.set_hexpand(True)
    name_label.set_ellipsize(Pango.EllipsizeMode.END)
    name_label.add_css_class("heading")
    name_label.set_valign(Gtk.Align.CENTER)
    box.append(name_label)

    # Type badge
    badge = Gtk.Label(label="Artist")
    badge.add_css_class("caption")
    badge.add_css_class("dim-label")
    badge.set_valign(Gtk.Align.CENTER)
    box.append(badge)

    row.set_child(box)

    # Make the entire row clickable to navigate to artist detail
    if on_clicked is not None:
        row.set_cursor_from_name("pointer")
        gesture = Gtk.GestureClick.new()
        gesture.set_button(1)

        def _on_artist_row_click(
            gest, n_press, _x, _y, _cb=on_clicked, _name=name
        ):
            if n_press != 1:
                return
            gest.set_state(Gtk.EventSequenceState.CLAIMED)
            _cb(_name)

        gesture.connect("released", _on_artist_row_click)
        row.add_controller(gesture)

    return row


class SearchView(Gtk.Box):
    """Search view with debounced input and scrollable result list."""

    __gtype_name__ = "SearchView"

    # Debounce delay in milliseconds.
    DEBOUNCE_MS = 300

    def __init__(self, **kwargs) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            **kwargs,
        )

        self._debounce_id: int | None = None
        self._search_generation: int = 0
        self._db = None
        self._tidal_provider = None

        # Context menu callbacks
        self._context_callbacks: dict | None = None
        self._get_playlists: object = None
        self._current_menu: object = None

        # Navigation callbacks for clickable artist/album labels
        self._on_nav_artist_clicked = None
        self._on_nav_album_clicked = None

        # Filter state — "all", "track", "album", "artist"
        self._active_filter: str = "all"
        # Cache of latest search results for re-filtering without re-searching
        self._cached_results: list[dict] = []

        # ---- Search entry ----
        entry_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
        )
        entry_box.set_margin_top(24)
        entry_box.set_margin_bottom(8)
        entry_box.set_margin_start(32)
        entry_box.set_margin_end(32)

        self._search_entry = Gtk.Entry()
        self._search_entry.set_placeholder_text(
            "Search tracks, albums, artists..."
        )
        self._search_entry.set_icon_from_icon_name(
            Gtk.EntryIconPosition.PRIMARY, "system-search-symbolic"
        )
        self._search_entry.set_icon_from_icon_name(
            Gtk.EntryIconPosition.SECONDARY, "edit-clear-symbolic"
        )
        self._search_entry.add_css_class("search-entry")
        self._search_entry.set_hexpand(True)
        self._search_entry.connect("changed", self._on_search_changed)
        self._search_entry.connect("icon-press", self._on_icon_press)

        # Escape key clears the entry
        key_controller = Gtk.EventControllerKey()
        key_controller.connect("key-pressed", self._on_entry_key_pressed)
        self._search_entry.add_controller(key_controller)

        # Track focus to show/hide history
        focus_controller = Gtk.EventControllerFocus()
        focus_controller.connect("enter", self._on_entry_focus_enter)
        self._search_entry.add_controller(focus_controller)

        entry_box.append(self._search_entry)

        self.append(entry_box)

        # ---- Filter tab buttons ----
        self._filter_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
        )
        self._filter_box.set_margin_start(32)
        self._filter_box.set_margin_end(32)
        self._filter_box.set_margin_bottom(12)
        self._filter_box.set_visible(False)  # Hidden until results arrive

        self._filter_buttons: list[Gtk.ToggleButton] = []
        for label_text, filter_name in [
            ("All", "all"),
            ("Tracks", _TYPE_TRACK),
            ("Albums", _TYPE_ALBUM),
            ("Artists", _TYPE_ARTIST),
        ]:
            btn = Gtk.ToggleButton(label=label_text)
            btn.add_css_class("filter-btn")
            btn._filter_name = filter_name  # type: ignore[attr-defined]
            btn.connect("toggled", self._on_filter_toggled)
            self._filter_box.append(btn)
            self._filter_buttons.append(btn)

        # Default: "All" is active
        self._filter_buttons[0].set_active(True)

        self.append(self._filter_box)

        # ---- Scrollable results area ----
        self._scroll = Gtk.ScrolledWindow()
        self._scroll.set_policy(
            Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC
        )
        self._scroll.set_vexpand(True)
        self._drag_scroll = DragScrollHelper(self._scroll)

        # Container that switches between results list and empty state.
        self._results_stack = Gtk.Stack()
        self._results_stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._results_stack.set_transition_duration(150)

        # -- Results list --
        results_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
        )
        results_box.set_margin_start(32)
        results_box.set_margin_end(32)
        results_box.set_margin_bottom(24)

        self._results_list = Gtk.ListBox()
        self._results_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._results_list.add_css_class("boxed-list")
        results_box.append(self._results_list)

        self._results_stack.add_named(results_box, "results")

        # -- Search history section --
        self._history_box = self._build_history_section()
        self._results_stack.add_named(self._history_box, "history")

        # -- Empty state: initial (before search) --
        self._empty_initial = self._build_empty_state(
            icon_name="system-search-symbolic",
            message="Search your library",
        )
        self._results_stack.add_named(self._empty_initial, "empty-initial")

        # -- Empty state: no results --
        self._empty_no_results = self._build_empty_state(
            icon_name="edit-find-symbolic",
            message="No results found",
        )
        self._results_stack.add_named(
            self._empty_no_results, "empty-no-results"
        )

        # Start with initial empty state.
        self._results_stack.set_visible_child_name("empty-initial")

        self._scroll.set_child(self._results_stack)
        self.append(self._scroll)

    # ---- Public API ----

    def focus_entry(self) -> None:
        """Focus the search text input so the user can start typing."""
        self._search_entry.grab_focus()

    def set_providers(self, db=None, tidal_provider=None) -> None:
        """Wire the search view to real data providers.

        Parameters
        ----------
        db:
            Database instance for searching local tracks.
        tidal_provider:
            TidalProvider instance for searching Tidal.
        """
        self._db = db
        self._tidal_provider = tidal_provider

    def set_album_art_service(self, art_service) -> None:
        """Set the AlbumArtService instance for loading album art."""
        self._album_art_service = art_service

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def set_navigation_callbacks(
        self,
        on_artist_clicked=None,
        on_album_clicked=None,
    ) -> None:
        """Set callbacks for clickable artist/album navigation.

        Parameters
        ----------
        on_artist_clicked:
            Callback receiving (artist_name: str).
        on_album_clicked:
            Callback receiving (album_name: str, artist: str,
            tidal_id: str | None).
        """
        self._on_nav_artist_clicked = on_artist_clicked
        self._on_nav_album_clicked = on_album_clicked

    def _nav_album_from_track(
        self, album_name: str, artist: str
    ) -> None:
        """Bridge for track-row album clicks (2-arg) to nav callback (3-arg)."""
        if self._on_nav_album_clicked is not None:
            self._on_nav_album_clicked(album_name, artist, None)

    # ---- Filter tab handlers ----

    def _on_filter_toggled(
        self, toggled_btn: Gtk.ToggleButton
    ) -> None:
        """Enforce radio-button behavior for filter tab buttons."""
        if not toggled_btn.get_active():
            # Prevent un-toggling the last active button
            any_active = any(
                b.get_active() for b in self._filter_buttons
            )
            if not any_active:
                toggled_btn.set_active(True)
            return

        # Deactivate all other buttons
        for btn in self._filter_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)

        filter_name = getattr(
            toggled_btn, "_filter_name", "all"
        )
        self._active_filter = filter_name
        # Re-filter displayed results from cache (no re-search)
        self._apply_filter()

    def _apply_filter(self) -> None:
        """Show/hide result rows based on the active filter tab.

        Uses ``_cached_results`` so we never re-search, just re-render
        the visible subset.
        """
        self._clear_results()

        filtered = self._cached_results
        if self._active_filter != "all":
            filtered = [
                r
                for r in self._cached_results
                if r.get("_result_type") == self._active_filter
            ]

        if not filtered:
            if self._cached_results:
                # Results exist but none match the current filter
                self._results_stack.set_visible_child_name(
                    "empty-no-results"
                )
            return

        for result in filtered:
            result_type = result.get("_result_type", _TYPE_TRACK)

            if result_type == _TYPE_ALBUM:
                tidal_obj = result.get("_tidal_obj")
                tidal_id = None
                if tidal_obj is not None:
                    tidal_id = str(getattr(tidal_obj, "id", ""))
                row = _make_album_result_row(
                    name=result["title"],
                    artist_name=result.get("artist", ""),
                    num_tracks=result.get("_num_tracks"),
                    on_clicked=self._on_nav_album_clicked,
                    tidal_id=tidal_id or None,
                )
                row._result_type = _TYPE_ALBUM  # type: ignore[attr-defined]
                if tidal_obj is not None:
                    row._tidal_album = tidal_obj  # type: ignore[attr-defined]
                    self._load_album_art_from_tidal(row, tidal_obj)
                self._results_list.append(row)

            elif result_type == _TYPE_ARTIST:
                row = _make_artist_result_row(
                    name=result["title"],
                    on_clicked=self._on_nav_artist_clicked,
                )
                row._result_type = _TYPE_ARTIST  # type: ignore[attr-defined]
                tidal_obj = result.get("_tidal_obj")
                if tidal_obj is not None:
                    row._tidal_artist = tidal_obj  # type: ignore[attr-defined]
                    self._load_artist_image_from_tidal(row, tidal_obj)
                self._results_list.append(row)

            else:
                # Track result
                track = result.get("_track")
                row = _make_result_row(
                    title=result["title"],
                    artist=result.get("artist", ""),
                    album=result.get("album", ""),
                    source=result["source"],
                    duration=result["duration"],
                    track=track,
                    on_artist_clicked=self._on_nav_artist_clicked,
                    on_album_clicked=self._nav_album_from_track,
                )
                row._result_type = _TYPE_TRACK  # type: ignore[attr-defined]
                self._attach_context_gesture(row, track)
                self._attach_drag_source_to_row(row, track)
                self._results_list.append(row)
                self._load_row_art(row, track)

        self._results_stack.set_visible_child_name("results")

    def _update_filter_counts(self) -> None:
        """Update filter button labels with counts from cached results."""
        counts: dict[str, int] = {
            "all": len(self._cached_results),
            _TYPE_TRACK: 0,
            _TYPE_ALBUM: 0,
            _TYPE_ARTIST: 0,
        }
        for r in self._cached_results:
            rtype = r.get("_result_type", _TYPE_TRACK)
            if rtype in counts:
                counts[rtype] += 1

        label_map = {
            "all": "All",
            _TYPE_TRACK: "Tracks",
            _TYPE_ALBUM: "Albums",
            _TYPE_ARTIST: "Artists",
        }
        for btn in self._filter_buttons:
            fname = getattr(btn, "_filter_name", "all")
            count = counts.get(fname, 0)
            base = label_map.get(fname, fname.title())
            if count > 0:
                btn.set_label(f"{base} ({count})")
            else:
                btn.set_label(base)

    # ---- Drag source helpers ----

    @staticmethod
    def _attach_drag_source_to_row(
        row: Gtk.ListBoxRow, track
    ) -> None:
        """Attach a DragSource to a track row for drag-to-playlist."""
        if track is None:
            return
        track_id = getattr(track, "id", None)
        if track_id is None:
            return

        drag_source = Gtk.DragSource.new()
        drag_source.set_actions(Gdk.DragAction.COPY)

        def _on_prepare(_src, _x, _y, tid=track_id):
            value = GObject.Value(GObject.TYPE_STRING, str(tid))
            return Gdk.ContentProvider.new_for_value(value)

        drag_source.connect("prepare", _on_prepare)
        row.add_controller(drag_source)

    # ---- Context menu helpers ----

    def _attach_context_gesture(
        self, row: Gtk.ListBoxRow, track
    ) -> None:
        """Attach a right-click gesture to a search result row."""
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
        self, widget: Gtk.Widget, x: float, y: float, track
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
            "id": getattr(track, "id", None),
            "title": getattr(track, "title", ""),
            "artist": getattr(track, "artist", ""),
            "album": getattr(track, "album", ""),
            "source": getattr(track, "source", None),
            "source_id": getattr(track, "source_id", None),
            "is_favorite": False,
        }

        self._current_menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    # ---- Empty state builder ----

    @staticmethod
    def _build_empty_state(icon_name: str, message: str) -> Gtk.Box:
        """Create a centered empty-state widget with icon and message."""
        box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        box.add_css_class("search-empty-state")
        box.set_vexpand(True)
        box.set_margin_top(80)

        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(64)
        icon.set_opacity(0.3)
        box.append(icon)

        label = Gtk.Label(label=message)
        label.add_css_class("title-3")
        label.add_css_class("dim-label")
        box.append(label)

        return box

    # ---- Search history ----

    def _build_history_section(self) -> Gtk.Box:
        """Build the recent searches section container."""
        section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        section.add_css_class("search-history-section")
        section.set_margin_start(32)
        section.set_margin_end(32)
        section.set_margin_top(8)
        section.set_margin_bottom(24)

        # Header
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        header_box.set_margin_bottom(4)

        header_icon = Gtk.Image.new_from_icon_name(
            "document-open-recent-symbolic"
        )
        header_icon.set_pixel_size(16)
        header_icon.add_css_class("dim-label")
        header_box.append(header_icon)

        header_label = Gtk.Label(label="Recent Searches")
        header_label.set_xalign(0)
        header_label.add_css_class("caption")
        header_label.add_css_class("dim-label")
        header_label.set_hexpand(True)
        header_box.append(header_label)

        section.append(header_box)

        # History list
        self._history_list = Gtk.ListBox()
        self._history_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._history_list.add_css_class("boxed-list")
        section.append(self._history_list)

        # Clear history button
        self._clear_history_btn = Gtk.Button(label="Clear History")
        self._clear_history_btn.add_css_class("search-history-clear")
        self._clear_history_btn.add_css_class("flat")
        self._clear_history_btn.set_halign(Gtk.Align.CENTER)
        self._clear_history_btn.set_margin_top(8)
        self._clear_history_btn.connect(
            "clicked", self._on_clear_history_clicked
        )
        section.append(self._clear_history_btn)

        return section

    def _make_history_row(self, query: str) -> Gtk.ListBoxRow:
        """Build a single search history row."""
        row_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        row_box.add_css_class("search-history-row")

        # Clock icon
        clock_icon = Gtk.Image.new_from_icon_name(
            "document-open-recent-symbolic"
        )
        clock_icon.set_pixel_size(16)
        clock_icon.add_css_class("dim-label")
        clock_icon.set_valign(Gtk.Align.CENTER)
        row_box.append(clock_icon)

        # Query text (clickable area)
        label = Gtk.Label(label=query)
        label.set_xalign(0)
        label.set_hexpand(True)
        label.set_ellipsize(Pango.EllipsizeMode.END)
        label.set_max_width_chars(50)
        label.add_css_class("body")
        label.set_valign(Gtk.Align.CENTER)
        row_box.append(label)

        # Remove button (x)
        remove_btn = Gtk.Button.new_from_icon_name(
            "window-close-symbolic"
        )
        remove_btn.add_css_class("flat")
        remove_btn.add_css_class("circular")
        remove_btn.set_valign(Gtk.Align.CENTER)
        remove_btn.set_tooltip_text("Remove from history")
        remove_btn.connect(
            "clicked",
            self._on_remove_history_item,
            query,
        )
        row_box.append(remove_btn)

        row = Gtk.ListBoxRow()
        row.add_css_class("track-row-hover")
        row.set_child(row_box)

        # Click gesture for the row to trigger search
        gesture = Gtk.GestureClick(button=1)
        gesture.connect("pressed", self._on_history_row_clicked, query)
        row.add_controller(gesture)

        return row

    def _refresh_history(self) -> None:
        """Reload history items from the database and display them."""
        # Clear existing rows
        while True:
            row = self._history_list.get_row_at_index(0)
            if row is None:
                break
            self._history_list.remove(row)

        if self._db is None:
            return

        queries = self._db.get_search_history(limit=10)
        if not queries:
            # No history — show the initial empty state instead
            self._results_stack.set_visible_child_name("empty-initial")
            return

        for query in queries:
            row = self._make_history_row(query)
            self._history_list.append(row)

        self._results_stack.set_visible_child_name("history")

    def _on_entry_focus_enter(
        self, controller: Gtk.EventControllerFocus
    ) -> None:
        """When the search entry gains focus, show history if empty."""
        query = self._search_entry.get_text().strip()
        if not query:
            self._refresh_history()

    def _on_history_row_clicked(
        self,
        gesture: Gtk.GestureClick,
        n_press: int,
        x: float,
        y: float,
        query: str,
    ) -> None:
        """Handle clicking a history row — populate and search."""
        self._search_entry.set_text(query)

    def _on_remove_history_item(
        self, button: Gtk.Button, query: str
    ) -> None:
        """Remove a single item from search history."""
        if self._db is not None:
            self._db.delete_search_history_item(query)
        self._refresh_history()

    def _on_clear_history_clicked(self, button: Gtk.Button) -> None:
        """Handle the Clear History button click."""
        if self._db is not None:
            self._db.clear_search_history()
        self._refresh_history()

    # ---- Entry event handlers ----

    def _on_icon_press(
        self, entry: Gtk.Entry, icon_pos: Gtk.EntryIconPosition
    ) -> None:
        """Clear the entry when the secondary (clear) icon is clicked."""
        if icon_pos == Gtk.EntryIconPosition.SECONDARY:
            entry.set_text("")

    def _on_entry_key_pressed(
        self,
        controller: Gtk.EventControllerKey,
        keyval: int,
        keycode: int,
        state: Gdk.ModifierType,
    ) -> bool:
        """Handle Escape key to clear the search entry."""
        if keyval == Gdk.KEY_Escape:
            self._search_entry.set_text("")
            return True
        return False

    # ---- Debounced search ----

    def _on_search_changed(self, entry: Gtk.Entry) -> None:
        """Handle search entry text changes with debounce."""
        # Cancel any pending debounce timeout.
        if self._debounce_id is not None:
            GLib.source_remove(self._debounce_id)
            self._debounce_id = None

        query = entry.get_text().strip()

        if not query:
            self._search_generation += 1
            self._clear_results()
            self._cached_results = []
            self._filter_box.set_visible(False)
            self._reset_filter_buttons()
            self._refresh_history()
            return

        # Schedule the actual search after the debounce delay.
        self._debounce_id = GLib.timeout_add(
            self.DEBOUNCE_MS,
            self._on_debounce_expired,
            query,
        )

    def _on_debounce_expired(self, query: str) -> bool:
        """Execute the search after debounce period expires."""
        self._debounce_id = None

        # Save queries with 2+ characters to history
        if len(query) >= 2 and self._db is not None:
            try:
                self._db.add_search_history(query)
            except Exception:
                logger.warning("Failed to save search history", exc_info=True)

        # Run the search in a background thread to avoid blocking the UI
        # (especially Tidal network calls).
        self._search_generation += 1
        gen = self._search_generation

        def _search_thread() -> None:
            results = self._do_search(query)
            if gen == self._search_generation:
                GLib.idle_add(self._populate_results, results, gen)

        thread = threading.Thread(target=_search_thread, daemon=True)
        thread.start()
        # Return False to prevent GLib from repeating the timeout.
        return GLib.SOURCE_REMOVE

    # ---- Result population ----

    def _clear_results(self) -> None:
        """Remove all rows from the result list."""
        while True:
            row = self._results_list.get_row_at_index(0)
            if row is None:
                break
            self._results_list.remove(row)

    def _reset_filter_buttons(self) -> None:
        """Reset filter buttons to 'All' active with base labels."""
        self._active_filter = "all"
        for btn in self._filter_buttons:
            fname = getattr(btn, "_filter_name", "all")
            btn.set_active(fname == "all")
            label_map = {
                "all": "All",
                _TYPE_TRACK: "Tracks",
                _TYPE_ALBUM: "Albums",
                _TYPE_ARTIST: "Artists",
            }
            btn.set_label(label_map.get(fname, fname.title()))

    def _populate_results(
        self,
        results: list[dict],
        gen: int | None = None,
    ) -> None:
        """Fill the result list with search result rows."""
        if gen is not None and gen != self._search_generation:
            return

        # Cache results for filter switching
        self._cached_results = results

        if not results:
            self._filter_box.set_visible(False)
            self._results_stack.set_visible_child_name("empty-no-results")
            return

        # Show filter tabs and update counts
        self._filter_box.set_visible(True)
        self._update_filter_counts()

        # Apply current filter (renders the rows)
        self._apply_filter()

    def _load_row_art(self, row: Gtk.ListBoxRow, track) -> None:
        """Load album art asynchronously for a search result row."""
        art_service = getattr(self, "_album_art_service", None)
        if art_service is None or track is None:
            return

        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            if getattr(row, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)
                art_box = getattr(row, "_art_box", None)
                if art_box is not None:
                    art_box.remove_css_class("album-art-placeholder")

        scale = row.get_scale_factor() or 1
        art_px = 48 * scale
        art_service.get_art_async(
            track, _on_art_loaded, width=art_px, height=art_px
        )

    def _load_album_art_from_tidal(
        self, row: Gtk.ListBoxRow, tidal_album
    ) -> None:
        """Load album cover art from a tidalapi Album object."""
        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        try:
            image_url = tidal_album.image(160)
        except Exception:
            return
        if not image_url:
            return

        from auxen.album_art import AlbumArtService

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        def _load() -> None:
            try:
                pixbuf = AlbumArtService.load_pixbuf_from_url(
                    image_url, 40
                )
            except Exception:
                pixbuf = None
            if getattr(row, "_art_request_token", None) is not request_token:
                return

            def _apply(pb=pixbuf):
                if pb is not None:
                    texture = Gdk.Texture.new_for_pixbuf(pb)
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)
                    art_box = getattr(row, "_art_box", None)
                    if art_box is not None:
                        art_box.remove_css_class(
                            "album-art-placeholder"
                        )

            GLib.idle_add(_apply)

        thread = threading.Thread(target=_load, daemon=True)
        thread.start()

    def _load_artist_image_from_tidal(
        self, row: Gtk.ListBoxRow, tidal_artist
    ) -> None:
        """Load artist image from a tidalapi Artist object."""
        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        try:
            image_url = tidal_artist.image(160)
        except Exception:
            return
        if not image_url:
            return

        from auxen.album_art import AlbumArtService

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        def _load() -> None:
            try:
                pixbuf = AlbumArtService.load_pixbuf_from_url(
                    image_url, 40
                )
            except Exception:
                pixbuf = None
            if getattr(row, "_art_request_token", None) is not request_token:
                return

            def _apply(pb=pixbuf):
                if pb is not None:
                    texture = Gdk.Texture.new_for_pixbuf(pb)
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)
                    art_box = getattr(row, "_art_box", None)
                    if art_box is not None:
                        art_box.remove_css_class(
                            "album-art-placeholder"
                        )

            GLib.idle_add(_apply)

        thread = threading.Thread(target=_load, daemon=True)
        thread.start()

    # ---- Search logic ----

    def _do_search(self, query: str) -> list[dict]:
        """Search the database and optionally Tidal for matching tracks.

        Falls back to placeholder results if no providers are wired.
        """
        if not query:
            return []

        # If we have real providers, use them
        if self._db is not None:
            return self._do_real_search(query)

        # Fallback: placeholder search
        return self._do_placeholder_search(query)

    def _do_real_search(self, query: str) -> list[dict]:
        """Perform a real search using the database and Tidal."""
        results: list[dict] = []

        # Search local database (tracks only)
        try:
            db_tracks = self._db.search(query)
            for track in db_tracks:
                results.append({
                    "title": track.title,
                    "artist": track.artist,
                    "album": track.album or "",
                    "source": track.source.value,
                    "duration": _format_duration(track.duration),
                    "_track": track,
                    "_source_id": track.source_id,
                    "_result_type": _TYPE_TRACK,
                })
        except Exception:
            logger.warning("Database search failed", exc_info=True)

        # Search Tidal if available and logged in
        if self._tidal_provider is not None:
            try:
                if self._tidal_provider.is_logged_in:
                    tidal_results = self._tidal_provider.search_all(
                        query, limit=10
                    )

                    # Add track results (deduplicated)
                    seen_ids = {
                        r["_source_id"]
                        for r in results
                        if r.get("_source_id")
                    }
                    for track in tidal_results.get("tracks", []):
                        if track.source_id not in seen_ids:
                            seen_ids.add(track.source_id)
                            results.append({
                                "title": track.title,
                                "artist": track.artist,
                                "album": track.album or "",
                                "source": track.source.value,
                                "duration": _format_duration(
                                    track.duration
                                ),
                                "_track": track,
                                "_source_id": track.source_id,
                                "_result_type": _TYPE_TRACK,
                            })

                    # Add album results
                    for album in tidal_results.get("albums", []):
                        artist_name = ""
                        if hasattr(album, "artist") and album.artist:
                            artist_name = getattr(
                                album.artist, "name", ""
                            )
                        elif hasattr(album, "artists") and album.artists:
                            artist_name = ", ".join(
                                a.name for a in album.artists
                            )
                        results.append({
                            "title": getattr(album, "name", ""),
                            "artist": artist_name,
                            "album": "",
                            "source": "tidal",
                            "duration": "",
                            "_result_type": _TYPE_ALBUM,
                            "_tidal_obj": album,
                            "_num_tracks": getattr(
                                album, "num_tracks", None
                            ),
                        })

                    # Add artist results
                    for artist in tidal_results.get("artists", []):
                        results.append({
                            "title": getattr(artist, "name", ""),
                            "artist": "",
                            "album": "",
                            "source": "tidal",
                            "duration": "",
                            "_result_type": _TYPE_ARTIST,
                            "_tidal_obj": artist,
                        })
            except Exception:
                logger.warning("Tidal search failed", exc_info=True)

        return results

    @staticmethod
    def _do_placeholder_search(query: str) -> list[dict]:
        """Generate placeholder search results based on the query.

        This method returns fake results that incorporate the query text
        in the track titles.  It is designed to be replaced later with
        real database / provider calls.
        """
        if not query:
            return []

        # Use the query length as a seed so repeated identical queries
        # produce the same results within a session.
        rng = random.Random(query.lower())  # noqa: S311
        count = rng.randint(3, 5)

        results: list[dict] = []
        for i in range(count):
            artist = rng.choice(_SAMPLE_ARTISTS)
            album = rng.choice(_SAMPLE_ALBUMS)
            source = rng.choice(["tidal", "local"])
            duration = rng.choice(_SAMPLE_DURATIONS)
            title = f"{query.title()} (Track {i + 1})"

            results.append(
                {
                    "title": title,
                    "artist": artist,
                    "album": album,
                    "source": source,
                    "duration": duration,
                    "_result_type": _TYPE_TRACK,
                }
            )

        return results
