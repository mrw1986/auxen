"""Playlist detail view for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable, Optional

import gi

gi.require_version("Gdk", "4.0")
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gdk, GLib, GObject, Gtk, Pango

from auxen.views.context_menu import TrackContextMenu

logger = logging.getLogger(__name__)

PLAYLIST_COLORS = [
    "#d4a039",  # Amber (default)
    "#00c4cc",  # Tidal cyan
    "#7cb87a",  # Local green
    "#9b59b6",  # Purple
    "#e74c3c",  # Red
    "#3498db",  # Blue
    "#e67e22",  # Orange
    "#1abc9c",  # Teal
]


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
        return ""
    total = int(seconds)
    hours = total // 3600
    minutes = (total % 3600) // 60
    if hours > 0:
        return f"{hours}h {minutes}m"
    return f"{minutes}m"


def _make_track_row(
    index: int,
    track,
    on_remove: Callable[[int], None] | None = None,
    on_move_up: Callable[[int], None] | None = None,
    on_move_down: Callable[[int], None] | None = None,
    is_first: bool = False,
    is_last: bool = False,
    drag_handlers: dict | None = None,
) -> Gtk.ListBoxRow:
    """Build a single row for the playlist track list.

    Args:
        drag_handlers: Optional dict with keys 'on_drag_prepare',
            'on_drag_begin', 'on_drag_end', 'on_drop_enter',
            'on_drop_leave', 'on_drop' for drag-and-drop support.
    """
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("playlist-track-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    # -- Drag handle --
    drag_handle = Gtk.Image.new_from_icon_name("drag-symbolic")
    drag_handle.set_pixel_size(16)
    drag_handle.add_css_class("drag-handle")
    drag_handle.set_valign(Gtk.Align.CENTER)
    drag_handle.set_tooltip_text("Drag to reorder")
    row_box.append(drag_handle)

    # Set up DragSource on the drag handle
    if drag_handlers is not None:
        drag_source = Gtk.DragSource()
        drag_source.set_actions(Gdk.DragAction.MOVE)
        drag_source.connect(
            "prepare", drag_handlers["on_drag_prepare"], index
        )
        drag_source.connect(
            "drag-begin", drag_handlers["on_drag_begin"], row_box
        )
        drag_source.connect(
            "drag-end", drag_handlers["on_drag_end"], row_box
        )
        drag_handle.add_controller(drag_source)

    # -- Track number --
    num_label = Gtk.Label(label=str(index + 1))
    num_label.add_css_class("caption")
    num_label.add_css_class("dim-label")
    num_label.set_size_request(28, -1)
    num_label.set_xalign(1)
    num_label.set_valign(Gtk.Align.CENTER)
    row_box.append(num_label)

    # -- Album art placeholder (48x48) --
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

    # -- Title + Artist + Album column --
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

    # -- Source badge --
    source_text = track.source.value.capitalize()
    source_badge = Gtk.Label(label=source_text)
    css_class = (
        "source-badge-tidal"
        if track.source.value == "tidal"
        else "source-badge-local"
    )
    source_badge.add_css_class(css_class)
    source_badge.set_valign(Gtk.Align.CENTER)
    row_box.append(source_badge)

    # -- Duration --
    dur_label = Gtk.Label(label=_format_duration(track.duration))
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    dur_label.set_margin_start(4)
    row_box.append(dur_label)

    # -- Reorder buttons --
    reorder_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=0,
    )
    reorder_box.set_valign(Gtk.Align.CENTER)

    up_btn = Gtk.Button.new_from_icon_name("go-up-symbolic")
    up_btn.add_css_class("flat")
    up_btn.set_tooltip_text("Move up")
    up_btn.set_sensitive(not is_first)
    if on_move_up is not None:
        track_id = track.id

        def _on_up(_btn, tid=track_id, cb=on_move_up):
            cb(tid)

        up_btn.connect("clicked", _on_up)
    reorder_box.append(up_btn)

    down_btn = Gtk.Button.new_from_icon_name("go-down-symbolic")
    down_btn.add_css_class("flat")
    down_btn.set_tooltip_text("Move down")
    down_btn.set_sensitive(not is_last)
    if on_move_down is not None:
        track_id = track.id

        def _on_down(_btn, tid=track_id, cb=on_move_down):
            cb(tid)

        down_btn.connect("clicked", _on_down)
    reorder_box.append(down_btn)

    row_box.append(reorder_box)

    # -- Remove button --
    remove_btn = Gtk.Button.new_from_icon_name(
        "edit-delete-symbolic"
    )
    remove_btn.add_css_class("flat")
    remove_btn.add_css_class("playlist-remove-btn")
    remove_btn.set_tooltip_text("Remove from playlist")
    remove_btn.set_valign(Gtk.Align.CENTER)
    if on_remove is not None:
        track_id = track.id

        def _on_remove(_btn, tid=track_id, cb=on_remove):
            cb(tid)

        remove_btn.connect("clicked", _on_remove)
    row_box.append(remove_btn)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)

    # Set up DropTarget on the ListBoxRow for drag-and-drop
    if drag_handlers is not None:
        drop_target = Gtk.DropTarget.new(
            GObject.TYPE_INT, Gdk.DragAction.MOVE
        )
        drop_target.connect(
            "enter", drag_handlers["on_drop_enter"], row_box
        )
        drop_target.connect(
            "leave", drag_handlers["on_drop_leave"], row_box
        )
        drop_target.connect(
            "drop", drag_handlers["on_drop"], index
        )
        row.add_controller(drop_target)

    return row


class PlaylistView(Gtk.ScrolledWindow):
    """Scrollable playlist detail view with header and track list."""

    __gtype_name__ = "PlaylistView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        self._db = None
        self._playlist_id: int | None = None
        self._playlist_data: dict | None = None
        self._tracks: list = []

        # Public callbacks
        self.on_play_track: Callable | None = None
        self.on_play_all: Callable | None = None
        self.on_back: Callable | None = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None

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
        header_box.add_css_class("playlist-header")

        # Color dot (large, ~40px)
        self._color_dot = Gtk.Label(label="")
        self._color_dot.add_css_class("playlist-color-dot-large")
        self._color_dot.set_size_request(40, 40)
        self._color_dot.set_valign(Gtk.Align.CENTER)
        header_box.append(self._color_dot)

        # Name + info column
        name_info_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
        )
        name_info_box.set_hexpand(True)
        name_info_box.set_valign(Gtk.Align.CENTER)

        self._name_label = Gtk.Label(label="Playlist")
        self._name_label.set_xalign(0)
        self._name_label.add_css_class("greeting-label")
        self._name_label.add_css_class("playlist-name-editable")
        name_info_box.append(self._name_label)

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

        # Export button
        export_btn = Gtk.Button()
        export_icon = Gtk.Image.new_from_icon_name(
            "document-save-as-symbolic"
        )
        export_label = Gtk.Label(label="Export")
        export_inner = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        export_inner.append(export_icon)
        export_inner.append(export_label)
        export_btn.set_child(export_inner)
        export_btn.add_css_class("flat")
        export_btn.set_tooltip_text("Export playlist as M3U")
        export_btn.connect("clicked", self._on_export_clicked)
        actions_box.append(export_btn)

        # Edit name button
        edit_btn = Gtk.Button.new_from_icon_name("document-edit-symbolic")
        edit_btn.add_css_class("flat")
        edit_btn.set_tooltip_text("Rename playlist")
        edit_btn.connect("clicked", self._on_rename_clicked)
        actions_box.append(edit_btn)

        # Color picker button
        color_btn = Gtk.Button.new_from_icon_name(
            "preferences-color-symbolic"
        )
        color_btn.add_css_class("flat")
        color_btn.set_tooltip_text("Change color")
        color_btn.connect("clicked", self._on_color_clicked)
        actions_box.append(color_btn)

        # Delete button
        delete_btn = Gtk.Button()
        delete_icon = Gtk.Image.new_from_icon_name(
            "user-trash-symbolic"
        )
        delete_label = Gtk.Label(label="Delete")
        delete_inner = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        delete_inner.append(delete_icon)
        delete_inner.append(delete_label)
        delete_btn.set_child(delete_inner)
        delete_btn.add_css_class("flat")
        delete_btn.add_css_class("destructive-action")
        delete_btn.connect("clicked", self._on_delete_clicked)
        actions_box.append(delete_btn)

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

        empty_title = Gtk.Label(label="No tracks in this playlist")
        empty_title.add_css_class("title-3")
        empty_box.append(empty_title)

        empty_subtitle = Gtk.Label(
            label="Add tracks from your library"
        )
        empty_subtitle.add_css_class("caption")
        empty_box.append(empty_subtitle)

        self._content_stack.add_named(empty_box, "empty")

        self._root.append(self._content_stack)

        self.set_child(self._root)

        # Show empty by default
        self._content_stack.set_visible_child_name("empty")

    # ---- Public API ----

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def show_playlist(self, playlist_id: int, db) -> None:
        """Load and display a playlist from the database."""
        self._db = db
        self._playlist_id = playlist_id
        self._refresh()

    # ---- Internal helpers ----

    def _refresh(self) -> None:
        """Reload playlist data and rebuild the view."""
        if self._db is None or self._playlist_id is None:
            return

        self._playlist_data = self._db.get_playlist(self._playlist_id)
        if self._playlist_data is None:
            # Playlist was deleted
            if self.on_back:
                self.on_back()
            return

        self._tracks = self._db.get_playlist_tracks(self._playlist_id)

        # Update header
        self._name_label.set_label(self._playlist_data["name"])
        self._update_color_dot(self._playlist_data["color"] or "#d4a039")

        # Info line: track count + total duration
        count = len(self._tracks)
        track_word = "track" if count == 1 else "tracks"
        total_dur = sum(
            t.duration for t in self._tracks if t.duration
        )
        dur_text = _format_total_duration(total_dur)
        info = f"{count} {track_word}"
        if dur_text:
            info += f" \u2022 {dur_text}"
        self._info_label.set_label(info)

        # Rebuild track list
        self._rebuild_track_list()

    def _update_color_dot(self, color: str) -> None:
        """Apply the playlist color to the large dot."""
        css = Gtk.CssProvider()
        css.load_from_string(
            f".playlist-color-dot-large {{ background-color: {color}; }}"
        )
        self._color_dot.get_style_context().add_provider(
            css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1
        )

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

        drag_handlers = {
            "on_drag_prepare": self._on_drag_prepare,
            "on_drag_begin": self._on_drag_begin,
            "on_drag_end": self._on_drag_end,
            "on_drop_enter": self._on_drop_enter,
            "on_drop_leave": self._on_drop_leave,
            "on_drop": self._on_drop,
        }

        for idx, track in enumerate(self._tracks):
            row = _make_track_row(
                index=idx,
                track=track,
                on_remove=self._on_remove_track,
                on_move_up=self._on_move_up,
                on_move_down=self._on_move_down,
                is_first=(idx == 0),
                is_last=(idx == len(self._tracks) - 1),
                drag_handlers=drag_handlers,
            )
            self._attach_context_gesture(row, track)
            self._track_list.append(row)

        self._content_stack.set_visible_child_name("list")

    # ---- Context menu helpers ----

    def _attach_context_gesture(
        self, row: Gtk.ListBoxRow, track
    ) -> None:
        """Attach a right-click gesture to a playlist track row."""
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
        """Create and display a context menu for a playlist track."""
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

    # ---- Drag-and-drop handlers ----

    def _on_drag_prepare(
        self,
        _source: Gtk.DragSource,
        _x: float,
        _y: float,
        index: int,
    ) -> Gdk.ContentProvider:
        """Prepare the drag data: the track index as an integer."""
        value = GObject.Value(GObject.TYPE_INT, index)
        return Gdk.ContentProvider.new_for_value(value)

    def _on_drag_begin(
        self,
        _source: Gtk.DragSource,
        _drag: Gdk.Drag,
        row_box: Gtk.Box,
    ) -> None:
        """Add visual feedback when dragging starts."""
        row_box.add_css_class("drag-row-active")

    def _on_drag_end(
        self,
        _source: Gtk.DragSource,
        _drag: Gdk.Drag,
        _delete: bool,
        row_box: Gtk.Box,
    ) -> None:
        """Remove visual feedback when dragging ends."""
        row_box.remove_css_class("drag-row-active")

    def _on_drop_enter(
        self,
        _target: Gtk.DropTarget,
        _x: float,
        _y: float,
        row_box: Gtk.Box,
    ) -> Gdk.DragAction:
        """Show drop indicator when dragging over a row."""
        row_box.add_css_class("drop-indicator-bottom")
        return Gdk.DragAction.MOVE

    def _on_drop_leave(
        self,
        _target: Gtk.DropTarget,
        row_box: Gtk.Box,
    ) -> None:
        """Remove drop indicator when leaving a row."""
        row_box.remove_css_class("drop-indicator-top")
        row_box.remove_css_class("drop-indicator-bottom")

    def _on_drop(
        self,
        _target: Gtk.DropTarget,
        value: int,
        _x: float,
        _y: float,
        to_index: int,
    ) -> bool:
        """Handle the drop: move the track to the new position."""
        from_index = value
        if from_index == to_index:
            return False
        if (
            self._db is None
            or self._playlist_id is None
            or not self._tracks
        ):
            return False
        if from_index < 0 or from_index >= len(self._tracks):
            return False
        track = self._tracks[from_index]
        self._db.reorder_playlist_track(
            self._playlist_id, track.id, to_index
        )
        self._refresh()
        return True

    # ---- Track actions ----

    def _on_remove_track(self, track_id: int) -> None:
        """Remove a track from the playlist."""
        if self._db is not None and self._playlist_id is not None:
            try:
                self._db.remove_track_from_playlist(
                    self._playlist_id, track_id
                )
                self._refresh()
            except Exception:
                logger.warning(
                    "Failed to remove track from playlist",
                    exc_info=True,
                )

    def _on_move_up(self, track_id: int) -> None:
        """Move a track up one position."""
        if self._db is None or self._playlist_id is None:
            return
        # Find current position
        for idx, track in enumerate(self._tracks):
            if track.id == track_id and idx > 0:
                self._db.reorder_playlist_track(
                    self._playlist_id, track_id, idx - 1
                )
                self._refresh()
                break

    def _on_move_down(self, track_id: int) -> None:
        """Move a track down one position."""
        if self._db is None or self._playlist_id is None:
            return
        for idx, track in enumerate(self._tracks):
            if track.id == track_id and idx < len(self._tracks) - 1:
                self._db.reorder_playlist_track(
                    self._playlist_id, track_id, idx + 1
                )
                self._refresh()
                break

    # ---- Button handlers ----

    def _on_back_clicked(self, _btn) -> None:
        """Navigate back."""
        if self.on_back:
            self.on_back()

    def _on_play_all_clicked(self, _btn) -> None:
        """Play all tracks in the playlist."""
        if self.on_play_all and self._tracks:
            self.on_play_all(self._tracks)

    def _on_shuffle_clicked(self, _btn) -> None:
        """Shuffle and play all tracks."""
        import random

        if self.on_play_all and self._tracks:
            shuffled = list(self._tracks)
            random.shuffle(shuffled)
            self.on_play_all(shuffled)

    def _on_row_activated(
        self, _listbox: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Play a track when its row is activated."""
        idx = row.get_index()
        if (
            self.on_play_track
            and 0 <= idx < len(self._tracks)
        ):
            self.on_play_track(self._tracks[idx])

    def _on_rename_clicked(self, _btn) -> None:
        """Show a dialog to rename the playlist."""
        if self._db is None or self._playlist_id is None:
            return
        if self._playlist_data is None:
            return

        dialog = Gtk.Dialog()
        dialog.set_title("Rename Playlist")
        dialog.set_modal(True)
        dialog.set_default_size(300, -1)

        # Find a parent window for transient-for
        widget = self
        while widget is not None:
            if isinstance(widget, Gtk.Window):
                dialog.set_transient_for(widget)
                break
            widget = widget.get_parent()

        dialog.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dialog.add_button("Rename", Gtk.ResponseType.OK)

        content = dialog.get_content_area()
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)
        content.set_spacing(8)

        label = Gtk.Label(label="Playlist name:")
        label.set_xalign(0)
        content.append(label)

        entry = Gtk.Entry()
        entry.set_text(self._playlist_data["name"])
        entry.set_activates_default(True)
        content.append(entry)

        dialog.set_default_response(Gtk.ResponseType.OK)

        def on_response(_dialog, response):
            if response == Gtk.ResponseType.OK:
                new_name = entry.get_text().strip()
                if new_name:
                    self._db.rename_playlist(self._playlist_id, new_name)
                    self._refresh()
            _dialog.destroy()

        dialog.connect("response", on_response)
        dialog.present()

    def _on_color_clicked(self, btn) -> None:
        """Show a popover to pick a playlist color."""
        if self._db is None or self._playlist_id is None:
            return

        popover = Gtk.Popover()
        popover.set_parent(btn)

        color_grid = Gtk.FlowBox()
        color_grid.set_max_children_per_line(4)
        color_grid.set_selection_mode(Gtk.SelectionMode.NONE)
        color_grid.set_margin_top(8)
        color_grid.set_margin_bottom(8)
        color_grid.set_margin_start(8)
        color_grid.set_margin_end(8)
        color_grid.set_column_spacing(8)
        color_grid.set_row_spacing(8)

        for color in PLAYLIST_COLORS:
            color_btn = Gtk.Button()
            color_btn.set_size_request(32, 32)

            css = Gtk.CssProvider()
            css.load_from_string(
                f"button {{ background-color: {color}; "
                f"border-radius: 9999px; min-width: 32px; "
                f"min-height: 32px; }}"
            )
            color_btn.get_style_context().add_provider(
                css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1
            )

            def _on_color_pick(
                _b, c=color, pop=popover
            ):
                self._db.update_playlist_color(self._playlist_id, c)
                self._refresh()
                pop.popdown()

            color_btn.connect("clicked", _on_color_pick)
            color_grid.insert(color_btn, -1)

        popover.set_child(color_grid)
        popover.popup()

    def _on_delete_clicked(self, _btn) -> None:
        """Confirm and delete the playlist."""
        if self._db is None or self._playlist_id is None:
            return

        dialog = Gtk.MessageDialog(
            message_type=Gtk.MessageType.WARNING,
            buttons=Gtk.ButtonsType.OK_CANCEL,
            text="Delete Playlist?",
            secondary_text=(
                "This will permanently remove the playlist. "
                "Your tracks will not be affected."
            ),
        )
        dialog.set_modal(True)

        widget = self
        while widget is not None:
            if isinstance(widget, Gtk.Window):
                dialog.set_transient_for(widget)
                break
            widget = widget.get_parent()

        def on_response(_dialog, response):
            if response == Gtk.ResponseType.OK:
                self._db.delete_playlist(self._playlist_id)
                _dialog.destroy()
                if self.on_back:
                    self.on_back()
            else:
                _dialog.destroy()

        dialog.connect("response", on_response)
        dialog.present()

    def _on_export_clicked(self, _btn) -> None:
        """Export the playlist as an M3U file using Gtk.FileDialog."""
        if not self._tracks or self._playlist_data is None:
            return

        try:
            dialog = Gtk.FileDialog()
            dialog.set_title("Export Playlist")

            # Suggest a filename based on playlist name
            safe_name = (
                self._playlist_data["name"]
                .replace("/", "_")
                .replace("\\", "_")
            )
            dialog.set_initial_name(f"{safe_name}.m3u")

            # Add M3U file filter
            m3u_filter = Gtk.FileFilter()
            m3u_filter.set_name("M3U Playlists")
            m3u_filter.add_pattern("*.m3u")
            m3u_filter.add_pattern("*.m3u8")
            dialog.set_default_filter(m3u_filter)

            # Find parent window
            parent = self
            while parent is not None:
                if isinstance(parent, Gtk.Window):
                    break
                parent = parent.get_parent()

            dialog.save(
                parent,
                None,
                self._on_export_save_response,
            )
        except Exception:
            logger.warning("Failed to open export dialog", exc_info=True)

    def _on_export_save_response(self, dialog, result) -> None:
        """Handle the file dialog save result for M3U export."""
        try:
            dest = dialog.save_finish(result)
            if dest is not None:
                filepath = dest.get_path()
                if filepath:
                    from auxen.m3u import M3UService

                    svc = M3UService()
                    svc.export_playlist(
                        self._tracks, filepath, db=self._db
                    )
                    logger.info("Exported playlist to %s", filepath)
        except GLib.Error:
            # User cancelled — normal
            pass
        except Exception:
            logger.warning("Failed to export playlist", exc_info=True)
