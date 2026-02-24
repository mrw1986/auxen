"""Smart playlist detail view for the Auxen music player."""

from __future__ import annotations

import logging
import random
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import GLib, Gtk, Pango

from auxen.views.context_menu import TrackContextMenu
from auxen.views.widgets import make_tidal_source_badge

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
        return ""
    total = int(seconds)
    hours = total // 3600
    minutes = (total % 3600) // 60
    if hours > 0:
        return f"{hours}h {minutes}m"
    return f"{minutes}m"


def _make_smart_track_row(
    index: int,
    track,
    on_artist_clicked=None,
    on_album_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a single row for the smart-playlist track list."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("playlist-track-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

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

    subtitle_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL, spacing=0
    )
    subtitle_box.set_xalign(0) if hasattr(subtitle_box, "set_xalign") else None

    def _make_nav_label(text: str, callback, *cb_args) -> Gtk.Label:
        lbl = Gtk.Label(label=text)
        lbl.set_ellipsize(Pango.EllipsizeMode.END)
        lbl.set_max_width_chars(25)
        lbl.add_css_class("track-row-subtitle")
        if callback is not None:
            lbl.add_css_class("track-nav-link")
            g = Gtk.GestureClick.new()
            g.set_button(1)
            def _on_click(gest, n_press, _x, _y, _cb=callback, _args=cb_args):
                if n_press != 1:
                    return
                gest.set_state(Gtk.EventSequenceState.CLAIMED)
                _cb(*_args)
            g.connect("released", _on_click)
            lbl.add_controller(g)
        return lbl

    subtitle_box.append(
        _make_nav_label(
            track.artist, on_artist_clicked, track.artist
        )
    )
    if track.album:
        sep = Gtk.Label(label=" \u2014 ")
        sep.add_css_class("track-row-subtitle")
        subtitle_box.append(sep)
        subtitle_box.append(
            _make_nav_label(
                track.album, on_album_clicked, track.album, track.artist
            )
        )
    text_box.append(subtitle_box)

    row_box.append(text_box)

    # -- Play count badge (when available) --
    if track.play_count and track.play_count > 0:
        play_word = "play" if track.play_count == 1 else "plays"
        count_label = Gtk.Label(label=f"{track.play_count} {play_word}")
        count_label.add_css_class("play-count-badge")
        count_label.set_valign(Gtk.Align.CENTER)
        row_box.append(count_label)

    # -- Source badge --
    source_text = track.source.value.capitalize()
    if track.source.value == "tidal":
        source_badge = make_tidal_source_badge(
            label_text=source_text,
            css_class="source-badge-tidal",
            icon_size=10,
        )
    else:
        source_badge = Gtk.Label(label=source_text)
        source_badge.add_css_class("source-badge-local")
    source_badge.set_valign(Gtk.Align.CENTER)
    row_box.append(source_badge)

    # -- Duration --
    dur_label = Gtk.Label(label=_format_duration(track.duration))
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    dur_label.set_margin_start(4)
    row_box.append(dur_label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    return row


class SmartPlaylistView(Gtk.ScrolledWindow):
    """Scrollable smart playlist detail view with header and track list."""

    __gtype_name__ = "SmartPlaylistView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        self._tracks: list = []
        self._definition: dict | None = None
        self._smart_id: str | None = None

        # Public callbacks
        self.on_play_track: Callable | None = None
        self.on_play_all: Callable | None = None
        self.on_back: Callable | None = None
        self.on_refresh: Callable | None = None

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

        self._content_stack.set_visible_child_name("list")

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
