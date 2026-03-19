"""Smart playlist detail view for the Auxen music player."""

from __future__ import annotations

import logging
import random
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gtk

from auxen.views.context_menu import TrackContextMenu
from auxen.views.view_mode import (
    ViewMode,
    make_view_mode_toggle,
    set_active_mode,
)
from auxen.views.widgets import (
    DragScrollHelper,
    make_compact_track_row,
    make_standard_track_row,
)

logger = logging.getLogger(__name__)


def _format_total_duration(seconds: float) -> str:
    """Format total seconds as a human-readable string."""
    if seconds <= 0:
        return ""
    total = int(seconds)
    hours = total // 3600
    minutes = (total % 3600) // 60
    if hours > 0:
        return f"{hours}h {minutes}m"
    return f"{minutes}m"


def _make_play_count_widget(track) -> Gtk.Label | None:
    """Create a play count badge widget if the track has plays."""
    play_count = getattr(track, "play_count", 0)
    if play_count and play_count > 0:
        play_word = "play" if play_count == 1 else "plays"
        count_label = Gtk.Label(label=f"{play_count} {play_word}")
        count_label.add_css_class("play-count-badge")
        count_label.set_valign(Gtk.Align.CENTER)
        return count_label
    return None


def _make_smart_track_row(
    index: int,
    track,
    on_artist_clicked=None,
    on_album_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a single row for the smart-playlist track list.

    Uses the shared make_standard_track_row with a play count badge
    as an extra widget.
    """
    # Build extra widgets: play count badge
    extras_after: list[Gtk.Widget] = []
    count_widget = _make_play_count_widget(track)
    if count_widget is not None:
        extras_after.append(count_widget)

    row = make_standard_track_row(
        track,
        index=index,
        show_art=True,
        show_source_badge=True,
        show_quality_badge=False,
        show_duration=True,
        css_class="playlist-track-row",
        on_artist_clicked=on_artist_clicked,
        on_album_clicked=on_album_clicked,
        extra_widgets_after=extras_after if extras_after else None,
    )
    return row


class SmartPlaylistView(Gtk.ScrolledWindow):
    """Scrollable smart playlist detail view with header and track list."""

    __gtype_name__ = "SmartPlaylistView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        self._tracks: list = []
        self._definition: dict | None = None
        self._smart_id: str | None = None
        self._view_mode: ViewMode = ViewMode.LIST
        self._db = None

        # Public callbacks
        self.on_play_track: Callable | None = None
        self.on_play_all: Callable | None = None
        self.on_back: Callable | None = None
        self.on_refresh: Callable | None = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None
        self._current_menu: object = None

        # Root container
        self._root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=20,
        )
        self._root.set_margin_top(24)
        self._root.set_margin_bottom(24)
        self._root.set_margin_start(32)
        self._root.set_margin_end(32)

        # ---- Back button ----
        back_btn = Gtk.Button.new_from_icon_name("go-previous-symbolic")
        back_btn.add_css_class("flat")
        back_btn.set_halign(Gtk.Align.START)
        back_btn.set_tooltip_text("Back")
        back_btn.connect("clicked", self._on_back_clicked)
        self._root.append(back_btn)

        # ---- 1. Header section ----
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
        )
        header_box.add_css_class("smart-playlist-header")

        # Icon
        self._header_icon = Gtk.Image.new_from_icon_name(
            "starred-symbolic"
        )
        self._header_icon.set_pixel_size(48)
        self._header_icon.add_css_class("smart-playlist-icon")
        self._header_icon.set_valign(Gtk.Align.CENTER)
        header_box.append(self._header_icon)

        # Name + description column
        name_info_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
        )
        name_info_box.set_hexpand(True)
        name_info_box.set_valign(Gtk.Align.CENTER)

        self._name_label = Gtk.Label(label="Smart Playlist")
        self._name_label.set_xalign(0)
        self._name_label.add_css_class("greeting-label")
        name_info_box.append(self._name_label)

        self._desc_label = Gtk.Label(label="")
        self._desc_label.set_xalign(0)
        self._desc_label.add_css_class("caption")
        self._desc_label.add_css_class("dim-label")
        self._desc_label.set_wrap(True)
        name_info_box.append(self._desc_label)

        self._info_label = Gtk.Label(label="0 tracks")
        self._info_label.set_xalign(0)
        self._info_label.add_css_class("caption")
        self._info_label.add_css_class("dim-label")
        name_info_box.append(self._info_label)

        header_box.append(name_info_box)
        self._root.append(header_box)

        # ---- 2. Action buttons ----
        actions_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )

        play_all_btn = Gtk.Button()
        play_all_icon = Gtk.Image.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        play_all_label = Gtk.Label(label="Play All")
        play_all_inner = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        play_all_inner.append(play_all_icon)
        play_all_inner.append(play_all_label)
        play_all_btn.set_child(play_all_inner)
        play_all_btn.add_css_class("suggested-action")
        play_all_btn.connect("clicked", self._on_play_all_clicked)
        actions_box.append(play_all_btn)

        shuffle_btn = Gtk.Button()
        shuffle_icon = Gtk.Image.new_from_icon_name(
            "media-playlist-shuffle-symbolic"
        )
        shuffle_label = Gtk.Label(label="Shuffle")
        shuffle_inner = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        shuffle_inner.append(shuffle_icon)
        shuffle_inner.append(shuffle_label)
        shuffle_btn.set_child(shuffle_inner)
        shuffle_btn.add_css_class("flat")
        shuffle_btn.connect("clicked", self._on_shuffle_clicked)
        actions_box.append(shuffle_btn)

        # Spacer
        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        actions_box.append(spacer)

        # Refresh button
        refresh_btn = Gtk.Button()
        refresh_icon = Gtk.Image.new_from_icon_name(
            "view-refresh-symbolic"
        )
        refresh_label = Gtk.Label(label="Refresh")
        refresh_inner = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        refresh_inner.append(refresh_icon)
        refresh_inner.append(refresh_label)
        refresh_btn.set_child(refresh_inner)
        refresh_btn.add_css_class("flat")
        refresh_btn.connect("clicked", self._on_refresh_clicked)
        actions_box.append(refresh_btn)

        # View mode toggle (inline with actions)
        self._view_mode_toggle = make_view_mode_toggle(
            on_mode_changed=self._on_view_mode_changed,
            initial_mode=ViewMode.LIST,
            include_grid=False,
        )
        self._view_mode_toggle.set_valign(Gtk.Align.CENTER)
        actions_box.append(self._view_mode_toggle)

        self._root.append(actions_box)

        # ---- 3. Content stack (list vs empty state) ----
        self._content_stack = Gtk.Stack()
        self._content_stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._content_stack.set_transition_duration(150)
        self._content_stack.set_vexpand(True)

        # -- Track list --
        self._track_list = Gtk.ListBox()
        self._track_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._track_list.add_css_class("boxed-list")
        self._track_list.connect("row-activated", self._on_row_activated)
        self._content_stack.add_named(self._track_list, "list")

        # -- Empty state --
        empty_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        empty_box.add_css_class("playlist-empty-state")
        empty_box.set_vexpand(True)
        empty_box.set_margin_top(80)

        empty_icon = Gtk.Image.new_from_icon_name(
            "media-playlist-consecutive-symbolic"
        )
        empty_icon.set_pixel_size(64)
        empty_box.append(empty_icon)

        empty_title = Gtk.Label(label="No matching tracks")
        empty_title.add_css_class("title-3")
        empty_box.append(empty_title)

        empty_subtitle = Gtk.Label(
            label="Play some music and check back later"
        )
        empty_subtitle.add_css_class("caption")
        empty_box.append(empty_subtitle)

        self._content_stack.add_named(empty_box, "empty")
        self._root.append(self._content_stack)

        self.set_child(self._root)

        # Show empty by default
        self._content_stack.set_visible_child_name("empty")

    # ---- Public API ----

    def set_database(self, db) -> None:
        """Set the database reference for view mode persistence."""
        self._db = db
        # Restore persisted view mode
        try:
            saved = db.get_setting("view_mode_smart_playlist", "list")
            for mode in ViewMode:
                if mode.value == saved:
                    self._view_mode = mode
                    set_active_mode(self._view_mode_toggle, mode)
                    break
        except Exception:
            pass

    def show_playlist(
        self,
        smart_id: str,
        tracks: list,
        definition: dict,
    ) -> None:
        """Display a smart playlist's tracks and metadata."""
        self._smart_id = smart_id
        self._tracks = tracks
        self._definition = definition

        # Update header
        self._name_label.set_label(definition.get("name", ""))
        self._desc_label.set_label(definition.get("description", ""))
        self._header_icon.set_from_icon_name(
            definition.get("icon", "starred-symbolic")
        )

        # Info line
        count = len(tracks)
        track_word = "track" if count == 1 else "tracks"
        total_dur = sum(t.duration for t in tracks if t.duration)
        dur_text = _format_total_duration(total_dur)
        info = f"{count} {track_word}"
        if dur_text:
            info += f" \u2022 {dur_text}"
        self._info_label.set_label(info)

        # Rebuild track list
        self._rebuild_track_list()

    def set_callbacks(
        self,
        on_play_track: Callable | None = None,
        on_play_all: Callable | None = None,
        on_back: Callable | None = None,
        on_refresh: Callable | None = None,
        on_artist_clicked: Callable | None = None,
        on_album_clicked: Callable | None = None,
    ) -> None:
        """Wire up all callbacks at once."""
        self.on_play_track = on_play_track
        self.on_play_all = on_play_all
        self.on_back = on_back
        self.on_refresh = on_refresh
        self._on_artist_clicked = on_artist_clicked
        self._on_album_clicked = on_album_clicked

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    # ---- Internal helpers ----

    def _rebuild_track_list(self) -> None:
        """Clear and repopulate the track list."""
        while True:
            row = self._track_list.get_row_at_index(0)
            if row is None:
                break
            self._track_list.remove(row)

        if not self._tracks:
            self._content_stack.set_visible_child_name("empty")
            return

        if self._view_mode == ViewMode.COMPACT_LIST:
            self._rebuild_track_list_compact()
        else:
            self._rebuild_track_list_full()

        self._content_stack.set_visible_child_name("list")

    def _rebuild_track_list_full(self) -> None:
        """Rebuild track list in full (list) mode."""
        for idx, track in enumerate(self._tracks):
            row = _make_smart_track_row(
                index=idx,
                track=track,
                on_artist_clicked=getattr(
                    self, "_on_artist_clicked", None
                ),
                on_album_clicked=getattr(
                    self, "_on_album_clicked", None
                ),
            )
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

    def _rebuild_track_list_compact(self) -> None:
        """Rebuild track list in compact mode (no art, single-line)."""
        for idx, track in enumerate(self._tracks):
            # Build play count as extra widget for compact mode
            extras: list[Gtk.Widget] = []
            count_widget = _make_play_count_widget(track)
            if count_widget is not None:
                extras.append(count_widget)

            row = make_compact_track_row(
                track,
                index=idx,
                show_source_badge=True,
                on_artist_clicked=getattr(
                    self, "_on_artist_clicked", None
                ),
                on_album_clicked=getattr(
                    self, "_on_album_clicked", None
                ),
                extra_widgets_after=extras if extras else None,
            )
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

    def _on_view_mode_changed(self, mode: ViewMode) -> None:
        """Handle view mode toggle changes."""
        self._view_mode = mode
        if self._db is not None:
            try:
                self._db.set_setting(
                    "view_mode_smart_playlist", mode.value
                )
            except Exception:
                pass
        self._rebuild_track_list()

    # ---- Context menu helpers ----

    def _attach_context_gesture(
        self, row: Gtk.ListBoxRow, track
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

    # ---- Button handlers ----

    def _on_back_clicked(self, _btn) -> None:
        """Navigate back."""
        if self.on_back:
            self.on_back()

    def _on_play_all_clicked(self, _btn) -> None:
        """Play all tracks."""
        if self.on_play_all and self._tracks:
            self.on_play_all(self._tracks)

    def _on_shuffle_clicked(self, _btn) -> None:
        """Shuffle and play all tracks."""
        if self.on_play_all and self._tracks:
            shuffled = list(self._tracks)
            random.shuffle(shuffled)
            self.on_play_all(shuffled)

    def _on_refresh_clicked(self, _btn) -> None:
        """Refresh the smart playlist."""
        if self.on_refresh and self._smart_id:
            self.on_refresh(self._smart_id)

    def _on_row_activated(
        self, _listbox: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Play a track when its row is activated."""
        idx = row.get_index()
        if self.on_play_track and 0 <= idx < len(self._tracks):
            self.on_play_track(self._tracks[idx])
