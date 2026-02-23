"""Queue side-panel widget for the Auxen music player."""

from __future__ import annotations

from typing import Callable, Optional

import gi

gi.require_version("Gdk", "4.0")
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gdk, GObject, Gtk, Pango


def _format_duration(seconds: Optional[float]) -> str:
    """Format seconds as M:SS (e.g. 3:45), or '--:--' when unknown."""
    if seconds is None or seconds < 0:
        return "--:--"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


class QueuePanel(Gtk.Box):
    """Right-side panel displaying the play queue.

    Layout (top to bottom):
        - Header row: "Queue" title + track count + close button
        - Now-playing indicator (highlighted current track)
        - Scrollable queue list
        - Empty state (when queue is empty)
        - Clear queue button at bottom
    """

    __gtype_name__ = "QueuePanel"

    def __init__(
        self,
        on_close: Callable[[], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            **kwargs,
        )

        self._on_close = on_close
        self._on_jump_to: Callable[[int], None] | None = None
        self._on_remove: Callable[[int], None] | None = None
        self._on_clear: Callable[[], None] | None = None
        self._on_move: Callable[[int, int], None] | None = None

        self._tracks: list = []
        self._current_index: int = -1

        self.add_css_class("queue-panel")
        self.set_size_request(320, -1)

        # ---- Header ----
        header = self._build_header()
        self.append(header)

        # ---- Now-playing section ----
        self._now_playing_section = self._build_now_playing_section()
        self.append(self._now_playing_section)

        # ---- Queue list (scrolled) ----
        self._scrolled = Gtk.ScrolledWindow()
        self._scrolled.set_policy(
            Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC
        )
        self._scrolled.set_vexpand(True)
        self._scrolled.set_hexpand(True)

        self._list_box = Gtk.ListBox()
        self._list_box.set_selection_mode(Gtk.SelectionMode.NONE)
        self._list_box.add_css_class("queue-list")
        self._scrolled.set_child(self._list_box)
        self.append(self._scrolled)

        # ---- Empty state ----
        self._empty_state = self._build_empty_state()
        self.append(self._empty_state)

        # ---- Clear queue button ----
        self._clear_section = self._build_clear_section()
        self.append(self._clear_section)

        # Start with empty state
        self._scrolled.set_visible(False)
        self._now_playing_section.set_visible(False)
        self._clear_section.set_visible(False)
        self._empty_state.set_visible(True)

    # ------------------------------------------------------------------
    # Builder helpers
    # ------------------------------------------------------------------

    def _build_header(self) -> Gtk.Box:
        """Build the panel header with title, count, and close button."""
        header = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        header.add_css_class("queue-header")

        title_label = Gtk.Label(label="Queue")
        title_label.set_xalign(0)
        title_label.set_hexpand(True)
        title_label.add_css_class("title-3")
        header.append(title_label)

        self._count_label = Gtk.Label(label="")
        self._count_label.add_css_class("dim-label")
        self._count_label.add_css_class("caption")
        self._count_label.set_valign(Gtk.Align.CENTER)
        header.append(self._count_label)

        close_btn = Gtk.Button.new_from_icon_name("window-close-symbolic")
        close_btn.add_css_class("flat")
        close_btn.add_css_class("queue-close-btn")
        close_btn.set_valign(Gtk.Align.CENTER)
        close_btn.connect("clicked", self._on_close_clicked)
        header.append(close_btn)

        return header

    def _build_now_playing_section(self) -> Gtk.Box:
        """Build the highlighted now-playing indicator at the top."""
        section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
        )
        section.add_css_class("queue-now-playing")

        np_label = Gtk.Label(label="Now Playing")
        np_label.set_xalign(0)
        np_label.add_css_class("caption")
        np_label.add_css_class("dim-label")
        np_label.set_margin_start(16)
        np_label.set_margin_top(8)
        section.append(np_label)

        self._np_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=10,
        )
        self._np_row.add_css_class("queue-track-row")
        self._np_row.add_css_class("queue-track-active")
        self._np_row.set_margin_start(8)
        self._np_row.set_margin_end(8)
        self._np_row.set_margin_bottom(8)

        # Playing icon
        playing_icon = Gtk.Image.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        playing_icon.set_pixel_size(14)
        playing_icon.set_valign(Gtk.Align.CENTER)
        self._np_row.append(playing_icon)

        # Track info
        np_text = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=1,
        )
        np_text.set_hexpand(True)

        self._np_title = Gtk.Label(label="")
        self._np_title.set_xalign(0)
        self._np_title.set_ellipsize(Pango.EllipsizeMode.END)
        self._np_title.set_max_width_chars(28)
        self._np_title.add_css_class("queue-track-title")
        np_text.append(self._np_title)

        self._np_artist = Gtk.Label(label="")
        self._np_artist.set_xalign(0)
        self._np_artist.set_ellipsize(Pango.EllipsizeMode.END)
        self._np_artist.set_max_width_chars(28)
        self._np_artist.add_css_class("queue-track-artist")
        np_text.append(self._np_artist)

        self._np_row.append(np_text)

        # Duration
        self._np_duration = Gtk.Label(label="")
        self._np_duration.add_css_class("queue-track-duration")
        self._np_duration.set_valign(Gtk.Align.CENTER)
        self._np_row.append(self._np_duration)

        section.append(self._np_row)

        return section

    def _build_empty_state(self) -> Gtk.Box:
        """Build the centred empty-state placeholder."""
        empty = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        empty.set_vexpand(True)
        empty.add_css_class("queue-empty-state")

        icon = Gtk.Image.new_from_icon_name("view-list-symbolic")
        icon.set_pixel_size(48)
        empty.append(icon)

        heading = Gtk.Label(label="Queue is empty")
        heading.add_css_class("dim-label")
        heading.add_css_class("title-4")
        empty.append(heading)

        subtitle = Gtk.Label(label="Play some tracks to fill the queue")
        subtitle.add_css_class("dim-label")
        subtitle.add_css_class("caption")
        empty.append(subtitle)

        return empty

    def _build_clear_section(self) -> Gtk.Box:
        """Build the bottom clear-queue button section."""
        section = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
        )
        section.set_halign(Gtk.Align.CENTER)
        section.set_margin_top(8)
        section.set_margin_bottom(12)

        clear_btn = Gtk.Button(label="Clear Queue")
        clear_btn.add_css_class("flat")
        clear_btn.add_css_class("queue-clear-btn")
        clear_btn.connect("clicked", self._on_clear_clicked)
        section.append(clear_btn)

        return section

    # ------------------------------------------------------------------
    # Row builder
    # ------------------------------------------------------------------

    def _build_track_row(self, index: int, track) -> Gtk.Box:
        """Build a single queue track row with drag-and-drop support."""
        row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        row.add_css_class("queue-track-row")
        row.set_margin_start(8)
        row.set_margin_end(8)

        is_current = index == self._current_index
        if is_current:
            row.add_css_class("queue-track-active")

        # Drag handle (grip icon)
        drag_handle = Gtk.Image.new_from_icon_name("drag-symbolic")
        drag_handle.set_pixel_size(16)
        drag_handle.add_css_class("drag-handle")
        drag_handle.set_valign(Gtk.Align.CENTER)
        drag_handle.set_tooltip_text("Drag to reorder")
        row.append(drag_handle)

        # Set up DragSource on the drag handle
        drag_source = Gtk.DragSource()
        drag_source.set_actions(Gdk.DragAction.MOVE)
        drag_source.connect(
            "prepare", self._on_drag_prepare, index
        )
        drag_source.connect(
            "drag-begin", self._on_drag_begin, row
        )
        drag_source.connect(
            "drag-end", self._on_drag_end, row
        )
        drag_handle.add_controller(drag_source)

        # Set up DropTarget on the entire row
        drop_target = Gtk.DropTarget.new(
            GObject.TYPE_INT, Gdk.DragAction.MOVE
        )
        drop_target.connect("enter", self._on_drop_enter, row)
        drop_target.connect("leave", self._on_drop_leave, row)
        drop_target.connect("drop", self._on_drop, index)
        row.add_controller(drop_target)

        # Queue position number
        position_label = Gtk.Label(label=str(index + 1))
        position_label.add_css_class("queue-track-number")
        position_label.set_valign(Gtk.Align.CENTER)
        position_label.set_size_request(28, -1)
        row.append(position_label)

        # Track info (title + artist)
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=1,
        )
        text_box.set_hexpand(True)

        title_label = Gtk.Label(label=track.title)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(24)
        title_label.add_css_class("queue-track-title")
        text_box.append(title_label)

        artist_label = Gtk.Label(label=track.artist)
        artist_label.set_xalign(0)
        artist_label.set_ellipsize(Pango.EllipsizeMode.END)
        artist_label.set_max_width_chars(24)
        artist_label.add_css_class("queue-track-artist")
        text_box.append(artist_label)

        row.append(text_box)

        # Source badge
        source_val = (
            track.source.value if hasattr(track.source, "value") else str(track.source)
        )
        badge = Gtk.Label(label=source_val.upper())
        badge_class = (
            "source-badge-tidal" if source_val == "tidal" else "source-badge-local"
        )
        badge.add_css_class(badge_class)
        badge.set_valign(Gtk.Align.CENTER)
        row.append(badge)

        # Duration
        duration_label = Gtk.Label(label=_format_duration(track.duration))
        duration_label.add_css_class("queue-track-duration")
        duration_label.set_valign(Gtk.Align.CENTER)
        row.append(duration_label)

        # Move up button
        if index > 0:
            up_btn = Gtk.Button.new_from_icon_name("go-up-symbolic")
            up_btn.add_css_class("flat")
            up_btn.add_css_class("queue-move-btn")
            up_btn.set_valign(Gtk.Align.CENTER)
            up_btn.set_tooltip_text("Move up")
            up_btn.connect("clicked", self._on_move_up_clicked, index)
            row.append(up_btn)

        # Move down button
        if index < len(self._tracks) - 1:
            down_btn = Gtk.Button.new_from_icon_name("go-down-symbolic")
            down_btn.add_css_class("flat")
            down_btn.add_css_class("queue-move-btn")
            down_btn.set_valign(Gtk.Align.CENTER)
            down_btn.set_tooltip_text("Move down")
            down_btn.connect("clicked", self._on_move_down_clicked, index)
            row.append(down_btn)

        # Remove button
        remove_btn = Gtk.Button.new_from_icon_name("window-close-symbolic")
        remove_btn.add_css_class("flat")
        remove_btn.add_css_class("queue-remove-btn")
        remove_btn.set_valign(Gtk.Align.CENTER)
        remove_btn.set_tooltip_text("Remove from queue")
        remove_btn.connect("clicked", self._on_remove_clicked, index)
        row.append(remove_btn)

        # Make the row clickable for jump-to
        click_ctrl = Gtk.GestureClick.new()
        click_ctrl.connect("released", self._on_row_clicked, index)
        row.add_controller(click_ctrl)

        return row

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def update_queue(self, tracks: list, current_index: int) -> None:
        """Refresh the queue display with the given tracks and current index."""
        self._tracks = list(tracks)
        self._current_index = current_index

        has_tracks = len(self._tracks) > 0

        # Update header count
        if has_tracks:
            self._count_label.set_label(f"{len(self._tracks)} tracks")
        else:
            self._count_label.set_label("")

        # Update now-playing section
        if has_tracks and 0 <= current_index < len(self._tracks):
            current_track = self._tracks[current_index]
            self._np_title.set_label(current_track.title)
            self._np_artist.set_label(current_track.artist)
            self._np_duration.set_label(
                _format_duration(current_track.duration)
            )
            self._now_playing_section.set_visible(True)
        else:
            self._now_playing_section.set_visible(False)

        # Rebuild the queue list
        # Remove all existing children from the list box
        while True:
            child = self._list_box.get_first_child()
            if child is None:
                break
            self._list_box.remove(child)

        if has_tracks:
            for i, track in enumerate(self._tracks):
                if i == current_index:
                    continue  # Skip current track (shown in now-playing)
                row = self._build_track_row(i, track)
                self._list_box.append(row)

        # Toggle visibility
        # Show the list only if there are tracks other than the current one
        has_upcoming = has_tracks and len(self._tracks) > 1
        self._scrolled.set_visible(has_upcoming)
        self._empty_state.set_visible(not has_tracks)
        self._clear_section.set_visible(has_tracks)

    def set_callbacks(
        self,
        on_jump_to: Callable[[int], None] | None = None,
        on_remove: Callable[[int], None] | None = None,
        on_clear: Callable[[], None] | None = None,
        on_move: Callable[[int, int], None] | None = None,
    ) -> None:
        """Wire action callbacks for the queue panel.

        Args:
            on_jump_to: Called with the track index to jump to.
            on_remove: Called with the track index to remove.
            on_clear: Called to clear the entire queue.
            on_move: Called with (from_index, to_index) to reorder.
        """
        self._on_jump_to = on_jump_to
        self._on_remove = on_remove
        self._on_clear = on_clear
        self._on_move = on_move

    # ------------------------------------------------------------------
    # Drag-and-drop handlers
    # ------------------------------------------------------------------

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
        row: Gtk.Box,
    ) -> None:
        """Add visual feedback when dragging starts."""
        row.add_css_class("drag-row-active")

    def _on_drag_end(
        self,
        _source: Gtk.DragSource,
        _drag: Gdk.Drag,
        _delete: bool,
        row: Gtk.Box,
    ) -> None:
        """Remove visual feedback when dragging ends."""
        row.remove_css_class("drag-row-active")

    def _on_drop_enter(
        self,
        _target: Gtk.DropTarget,
        _x: float,
        _y: float,
        row: Gtk.Box,
    ) -> Gdk.DragAction:
        """Show drop indicator when dragging over a row."""
        row.add_css_class("drop-indicator-bottom")
        return Gdk.DragAction.MOVE

    def _on_drop_leave(
        self,
        _target: Gtk.DropTarget,
        row: Gtk.Box,
    ) -> None:
        """Remove drop indicator when leaving a row."""
        row.remove_css_class("drop-indicator-top")
        row.remove_css_class("drop-indicator-bottom")

    def _on_drop(
        self,
        _target: Gtk.DropTarget,
        value: int,
        _x: float,
        _y: float,
        to_index: int,
    ) -> bool:
        """Handle the drop: move the track from value to to_index."""
        from_index = value
        if from_index == to_index:
            return False
        if self._on_move is not None:
            self._on_move(from_index, to_index)
        return True

    # ------------------------------------------------------------------
    # Internal handlers
    # ------------------------------------------------------------------

    def _on_close_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_close is not None:
            self._on_close()
        else:
            self.set_visible(False)

    def _on_clear_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_clear is not None:
            self._on_clear()

    def _on_remove_clicked(self, _btn: Gtk.Button, index: int) -> None:
        if self._on_remove is not None:
            self._on_remove(index)

    def _on_row_clicked(
        self, gesture: Gtk.GestureClick, _n: int, _x: float, _y: float, index: int
    ) -> None:
        """Handle click on a queue row to jump to that track."""
        # Ignore if the click was on a button (remove/move)
        widget = gesture.get_widget()
        if widget is None:
            return
        if self._on_jump_to is not None:
            self._on_jump_to(index)

    def _on_move_up_clicked(self, _btn: Gtk.Button, index: int) -> None:
        if self._on_move is not None and index > 0:
            self._on_move(index, index - 1)

    def _on_move_down_clicked(self, _btn: Gtk.Button, index: int) -> None:
        if self._on_move is not None and index < len(self._tracks) - 1:
            self._on_move(index, index + 1)
