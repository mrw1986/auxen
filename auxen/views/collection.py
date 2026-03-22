"""Collection view for the Auxen music player."""

from __future__ import annotations

import logging
import threading
from typing import Callable

import gi

gi.require_version("Gdk", "4.0")
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, GLib, GObject, Gtk, Pango

from auxen.views.context_menu import (
    AlbumContextMenu,
    ArtistContextMenu,
    TrackContextMenu,
)
from auxen.views.view_mode import ViewMode, make_view_mode_toggle, set_active_mode
from auxen.views.widgets import (
    DragScrollHelper,
    format_duration,
    make_compact_track_row,
    make_source_badge,
    make_standard_track_row,
    make_tidal_connect_prompt,
    make_tidal_source_badge,
)

logger = logging.getLogger(__name__)

# Number of widgets to create per idle-add batch (keeps UI responsive)
_BATCH_SIZE = 25

# Sort options for each view
_SORT_OPTIONS_ALBUMS = [
    "Recently Added",
    "Name",
    "Artist",
]

_SORT_OPTIONS_ARTISTS = [
    "Name",
    "Track Count",
    "Recently Added",
]

_SORT_OPTIONS_TRACKS = [
    "Recently Added",
    "Artist",
    "Album",
    "Title",
]

_SORT_OPTIONS_PLAYLISTS = [
    "Name",
    "Track Count",
]

# Sort key functions keyed by dropdown label (tracks only).
_SORT_KEYS: dict[str, str] = {
    "Recently Added": "date_added",
    "Artist": "artist",
    "Album": "album",
    "Title": "title",
}


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    return format_duration(seconds)


def _make_source_badge_widget(source: str) -> Gtk.Widget:
    """Create a small pill badge indicating the track source."""
    return make_source_badge(source)


def _make_heart_button(track, on_unfavorite=None) -> Gtk.ToggleButton:
    """Create a heart toggle button for unfavoriting."""
    heart_btn = Gtk.ToggleButton()
    heart_btn.set_icon_name("emblem-favorite-symbolic")
    heart_btn.set_active(True)
    heart_btn.add_css_class("flat")
    heart_btn.add_css_class("collection-heart-btn")
    heart_btn.set_valign(Gtk.Align.CENTER)
    heart_btn.set_tooltip_text("Remove from collection")

    if on_unfavorite is not None:
        track_id = track.get("track_id") if isinstance(track, dict) else getattr(track, "track_id", None)
        if track_id is not None:

            def _on_heart_toggled(btn, tid=track_id, cb=on_unfavorite):
                if not btn.get_active():
                    cb(tid)

            heart_btn.connect("toggled", _on_heart_toggled)

    return heart_btn


def _make_favorite_row(
    track: dict[str, str | int],
    on_unfavorite=None,
    track_obj=None,
    on_artist_clicked=None,
    on_album_clicked=None,
    on_play_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a single row for the collection list using the shared widget."""
    heart_btn = _make_heart_button(track, on_unfavorite)
    # Prefer the canonical Track object for callbacks (play button),
    # while still supporting dict-backed display fallback.
    display_track = track_obj if track_obj is not None else track

    row = make_standard_track_row(
        display_track,
        show_art=True,
        show_source_badge=True,
        show_quality_badge=True,
        show_duration=True,
        art_size=48,
        css_class="collection-row",
        on_artist_clicked=on_artist_clicked,
        on_album_clicked=on_album_clicked,
        on_play_clicked=on_play_clicked,
        extra_widgets_after=[heart_btn],
    )
    # Store track object reference for context menu
    row._track_obj = track_obj  # type: ignore[attr-defined]
    return row


def _make_clickable_artist_label(
    artist: str,
    on_artist_clicked: Callable | None = None,
    css_classes: list[str] | None = None,
    max_chars: int = 18,
    margin_start: int = 6,
    margin_end: int = 6,
) -> Gtk.Label:
    """Create an artist label, optionally clickable for navigation."""
    lbl = Gtk.Label(label=artist)
    lbl.set_xalign(0)
    lbl.set_ellipsize(Pango.EllipsizeMode.END)
    lbl.set_max_width_chars(max_chars)
    for cls in (css_classes or ["caption", "dim-label"]):
        lbl.add_css_class(cls)
    lbl.set_margin_start(margin_start)
    lbl.set_margin_end(margin_end)
    if on_artist_clicked is not None:
        lbl.add_css_class("track-nav-link")
        g = Gtk.GestureClick.new()
        g.set_button(1)

        def _on_click(gest, n_press, _x, _y, _cb=on_artist_clicked, _a=artist):
            if n_press != 1:
                return
            gest.set_state(Gtk.EventSequenceState.CLAIMED)
            _cb(_a)

        g.connect("released", _on_click)
        lbl.add_controller(g)
    return lbl


def _make_album_card(
    album: str, artist: str, source: str,
    on_artist_clicked: Callable | None = None,
) -> Gtk.FlowBoxChild:
    """Build a single album card for the collection grid."""
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

    # Album art image (hidden until loaded).
    art_image = Gtk.Image()
    art_image.set_pixel_size(160)
    art_image.set_halign(Gtk.Align.CENTER)
    art_image.set_valign(Gtk.Align.CENTER)
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
        badge.set_tooltip_text("Streaming from Tidal")
    else:
        badge = Gtk.Label(label=source.capitalize())
        badge.add_css_class("source-badge-local")
        badge.set_tooltip_text("Local library file")
    badge.set_halign(Gtk.Align.END)
    badge.set_valign(Gtk.Align.START)
    badge.set_margin_top(8)
    badge.set_margin_end(12)
    overlay.add_overlay(badge)
    overlay.set_clip_overlay(badge, True)

    # -- Hover overlay (darkens art) --
    hover_overlay = Gtk.Box()
    hover_overlay.add_css_class("album-card-hover-overlay")
    hover_overlay.set_halign(Gtk.Align.FILL)
    hover_overlay.set_valign(Gtk.Align.FILL)
    hover_overlay.set_can_target(False)  # Let clicks pass through to play btn
    overlay.add_overlay(hover_overlay)

    # -- Play button (centered, revealed on hover) --
    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_can_target(True)
    play_btn.set_focusable(True)
    play_btn.set_tooltip_text(f"Play {album}")
    overlay.add_overlay(play_btn)

    card.append(overlay)

    # Title
    title_label = Gtk.Label(label=album)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(18)
    title_label.add_css_class("body")
    title_label.set_margin_start(6)
    title_label.set_margin_end(6)
    card.append(title_label)

    # Artist (clickable if callback provided)
    artist_label = _make_clickable_artist_label(
        artist, on_artist_clicked=on_artist_clicked,
    )
    card.append(artist_label)

    child = Gtk.FlowBoxChild()
    child.set_activatable(False)  # Prevent FlowBoxChild from stealing clicks
    child.set_child(card)
    # Store album/artist data for click handling
    child._album_title = album  # type: ignore[attr-defined]
    child._album_artist = artist  # type: ignore[attr-defined]
    # Store references to art widgets for async loading
    child._art_icon = art_icon  # type: ignore[attr-defined]
    child._art_image = art_image  # type: ignore[attr-defined]
    # Store play button for wiring callbacks
    child._play_btn = play_btn  # type: ignore[attr-defined]
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

    # Art overlay: placeholder + loaded image + play button
    art_overlay = Gtk.Overlay()
    art_overlay.set_size_request(32, 32)
    art_overlay.set_valign(Gtk.Align.CENTER)

    icon = Gtk.Image.new_from_icon_name("avatar-default-symbolic")
    icon.set_pixel_size(32)
    icon.set_opacity(0.5)
    icon.set_valign(Gtk.Align.CENTER)
    icon.set_halign(Gtk.Align.CENTER)
    art_overlay.set_child(icon)

    art_image = Gtk.Image()
    art_image.set_pixel_size(32)
    art_image.set_size_request(32, 32)
    art_image.set_halign(Gtk.Align.CENTER)
    art_image.set_valign(Gtk.Align.CENTER)
    art_image.add_css_class("artist-row-image")
    art_image.set_visible(False)
    art_overlay.add_overlay(art_image)

    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("flat")
    play_btn.add_css_class("album-row-play-overlay")
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {artist}")
    play_btn.set_opacity(0)
    art_overlay.add_overlay(play_btn)

    # Hover controller: show/hide play button
    motion = Gtk.EventControllerMotion.new()

    def _on_enter(*_args):
        play_btn.set_opacity(1)

    def _on_leave(*_args):
        play_btn.set_opacity(0)

    motion.connect("enter", _on_enter)
    motion.connect("leave", _on_leave)
    row_box.add_controller(motion)

    row_box.append(art_overlay)

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
        badge = _make_source_badge_widget(src)
        row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)
    row.set_activatable(True)
    row._artist_name = artist
    row._art_icon = icon
    row._art_image = art_image
    row._play_btn = play_btn
    return row


def _make_album_list_row(
    album: str, artist: str, source: str, track_count: int = 0,
    on_artist_clicked: Callable | None = None,
) -> Gtk.ListBoxRow:
    """Build a list-mode row for an album (art + title + artist + count)."""
    row_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
    row_box.add_css_class("library-album-list-row")

    # Art overlay: placeholder + loaded image + play button
    art_overlay = Gtk.Overlay()
    art_overlay.set_size_request(40, 40)
    art_overlay.set_valign(Gtk.Align.CENTER)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(24)
    art_icon.set_opacity(0.4)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_overlay.set_child(art_icon)

    art_image = Gtk.Image()
    art_image.set_pixel_size(40)
    art_image.set_size_request(40, 40)
    art_image.set_halign(Gtk.Align.CENTER)
    art_image.set_valign(Gtk.Align.CENTER)
    art_image.add_css_class("album-row-art-image")
    art_image.set_visible(False)
    art_overlay.add_overlay(art_image)

    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("flat")
    play_btn.add_css_class("album-row-play-overlay")
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {album}")
    play_btn.set_opacity(0)
    art_overlay.add_overlay(play_btn)

    # Hover controller: show/hide play button
    motion = Gtk.EventControllerMotion.new()

    def _on_enter(*_args):
        play_btn.set_opacity(1)

    def _on_leave(*_args):
        play_btn.set_opacity(0)

    motion.connect("enter", _on_enter)
    motion.connect("leave", _on_leave)
    row_box.add_controller(motion)

    row_box.append(art_overlay)

    text_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_label = Gtk.Label(label=album)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.add_css_class("body")
    text_box.append(title_label)

    artist_label = _make_clickable_artist_label(
        artist, on_artist_clicked=on_artist_clicked,
        css_classes=["caption", "dim-label"],
        max_chars=40, margin_start=0, margin_end=0,
    )
    text_box.append(artist_label)

    row_box.append(text_box)

    badge = _make_source_badge_widget(source)
    row_box.append(badge)

    if track_count > 0:
        count_label = Gtk.Label(
            label=f"{track_count} track{'s' if track_count != 1 else ''}"
        )
        count_label.add_css_class("caption")
        count_label.add_css_class("library-track-count")
        count_label.set_valign(Gtk.Align.CENTER)
        row_box.append(count_label)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)
    row.set_activatable(True)
    row._album_title = album
    row._album_artist = artist
    row._art_icon = art_icon
    row._art_image = art_image
    row._play_btn = play_btn
    return row


def _make_album_compact_row(
    album: str, artist: str, source: str, index: int = 0,
    on_artist_clicked: Callable | None = None,
) -> Gtk.ListBoxRow:
    """Build a compact row for an album."""
    row_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
    row_box.add_css_class("compact-track-row")

    # Index number / play button swap container
    num_play_box = Gtk.Box()
    num_play_box.set_size_request(28, -1)
    num_play_box.set_valign(Gtk.Align.CENTER)
    num_play_box.set_halign(Gtk.Align.CENTER)

    num_label = Gtk.Label(label=str(index + 1))
    num_label.add_css_class("caption")
    num_label.add_css_class("dim-label")
    num_label.set_size_request(28, -1)
    num_label.set_xalign(1)
    num_label.set_valign(Gtk.Align.CENTER)
    num_play_box.append(num_label)

    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("flat")
    play_btn.add_css_class("album-row-play-overlay")
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {album}")
    play_btn.set_visible(False)
    num_play_box.append(play_btn)

    row_box.append(num_play_box)

    # Hover controller: swap number ↔ play button
    motion = Gtk.EventControllerMotion.new()

    def _on_enter(*_args):
        num_label.set_visible(False)
        play_btn.set_visible(True)
        play_btn.set_opacity(1)

    def _on_leave(*_args):
        num_label.set_visible(True)
        play_btn.set_visible(False)

    motion.connect("enter", _on_enter)
    motion.connect("leave", _on_leave)
    row_box.add_controller(motion)

    title_label = Gtk.Label(label=album)
    title_label.set_xalign(0)
    title_label.set_hexpand(True)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.add_css_class("body")
    title_label.set_valign(Gtk.Align.CENTER)
    row_box.append(title_label)

    artist_label = _make_clickable_artist_label(
        artist, on_artist_clicked=on_artist_clicked,
        css_classes=["caption", "dim-label"],
        max_chars=25, margin_start=0, margin_end=0,
    )
    artist_label.set_xalign(1)
    artist_label.set_valign(Gtk.Align.CENTER)
    row_box.append(artist_label)

    badge = _make_source_badge_widget(source)
    row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)
    row.set_activatable(True)
    row._album_title = album
    row._album_artist = artist
    row._play_btn = play_btn
    return row


def _make_artist_card(
    artist: str, track_count: int, sources: list[str]
) -> Gtk.FlowBoxChild:
    """Build an artist card for grid view."""
    card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    card.add_css_class("album-card")

    overlay = Gtk.Overlay()

    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.set_size_request(160, 160)
    art_box.set_vexpand(False)

    art_icon = Gtk.Image.new_from_icon_name("avatar-default-symbolic")
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

    overlay.set_child(art_box)

    for src in sorted(set(sources)):
        if src == "tidal":
            badge = make_tidal_source_badge(
                label_text=src.capitalize(),
                css_class="source-badge-tidal",
                icon_size=10,
            )
        else:
            badge = Gtk.Label(label=src.capitalize())
            badge.add_css_class("source-badge-local")
        badge.set_halign(Gtk.Align.END)
        badge.set_valign(Gtk.Align.START)
        badge.set_margin_top(8)
        badge.set_margin_end(12)
        overlay.add_overlay(badge)
        break

    hover_overlay = Gtk.Box()
    hover_overlay.add_css_class("album-card-hover-overlay")
    hover_overlay.set_halign(Gtk.Align.FILL)
    hover_overlay.set_valign(Gtk.Align.FILL)
    hover_overlay.set_can_target(False)  # Let clicks pass through to play btn
    overlay.add_overlay(hover_overlay)

    play_btn = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_can_target(True)
    play_btn.set_focusable(True)
    play_btn.set_tooltip_text(f"Play {artist}")
    overlay.add_overlay(play_btn)

    card.append(overlay)

    name_label = Gtk.Label(label=artist)
    name_label.set_xalign(0)
    name_label.set_ellipsize(Pango.EllipsizeMode.END)
    name_label.set_max_width_chars(18)
    name_label.add_css_class("body")
    name_label.set_margin_start(6)
    name_label.set_margin_end(6)
    card.append(name_label)

    child = Gtk.FlowBoxChild()
    child.set_activatable(False)  # Prevent FlowBoxChild from stealing clicks
    child.set_child(card)
    child._artist_name = artist
    child._art_icon = art_icon
    child._art_image = art_image
    child._play_btn = play_btn
    return child


def _make_artist_compact_row(
    artist: str, sources: list[str], index: int = 0,
) -> Gtk.ListBoxRow:
    """Build a compact row for an artist."""
    row_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
    row_box.add_css_class("compact-track-row")

    # Index number / play button swap container
    num_play_box = Gtk.Box()
    num_play_box.set_size_request(28, -1)
    num_play_box.set_valign(Gtk.Align.CENTER)
    num_play_box.set_halign(Gtk.Align.CENTER)

    num_label = Gtk.Label(label=str(index + 1))
    num_label.add_css_class("caption")
    num_label.add_css_class("dim-label")
    num_label.set_size_request(28, -1)
    num_label.set_xalign(1)
    num_label.set_valign(Gtk.Align.CENTER)
    num_play_box.append(num_label)

    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("flat")
    play_btn.add_css_class("album-row-play-overlay")
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {artist}")
    play_btn.set_visible(False)
    num_play_box.append(play_btn)

    row_box.append(num_play_box)

    # Hover controller: swap number ↔ play button
    motion = Gtk.EventControllerMotion.new()

    def _on_enter(*_args):
        num_label.set_visible(False)
        play_btn.set_visible(True)
        play_btn.set_opacity(1)

    def _on_leave(*_args):
        num_label.set_visible(True)
        play_btn.set_visible(False)

    motion.connect("enter", _on_enter)
    motion.connect("leave", _on_leave)
    row_box.add_controller(motion)

    name_label = Gtk.Label(label=artist)
    name_label.set_xalign(0)
    name_label.set_hexpand(True)
    name_label.set_ellipsize(Pango.EllipsizeMode.END)
    name_label.add_css_class("body")
    name_label.set_valign(Gtk.Align.CENTER)
    row_box.append(name_label)

    for src in sorted(set(sources)):
        badge = _make_source_badge_widget(src)
        row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)
    row.set_activatable(True)
    row._artist_name = artist
    row._play_btn = play_btn
    return row


def _make_track_grid_card(
    title: str, artist: str, source: str,
    on_artist_clicked: Callable | None = None,
) -> Gtk.FlowBoxChild:
    """Build a track card for grid view (art + title + artist)."""
    card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    card.add_css_class("album-card")

    overlay = Gtk.Overlay()

    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER,
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
    art_image.set_halign(Gtk.Align.CENTER)
    art_image.set_valign(Gtk.Align.CENTER)
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
    badge.set_halign(Gtk.Align.END)
    badge.set_valign(Gtk.Align.START)
    badge.set_margin_top(8)
    badge.set_margin_end(12)
    overlay.add_overlay(badge)

    hover_overlay = Gtk.Box()
    hover_overlay.add_css_class("album-card-hover-overlay")
    hover_overlay.set_halign(Gtk.Align.FILL)
    hover_overlay.set_valign(Gtk.Align.FILL)
    hover_overlay.set_can_target(False)  # Let clicks pass through to play btn
    overlay.add_overlay(hover_overlay)

    play_btn = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_can_target(True)
    play_btn.set_focusable(True)
    play_btn.set_tooltip_text(f"Play {title}")
    overlay.add_overlay(play_btn)

    card.append(overlay)

    title_label = Gtk.Label(label=title)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(18)
    title_label.add_css_class("body")
    title_label.set_margin_start(6)
    title_label.set_margin_end(6)
    card.append(title_label)

    artist_label = _make_clickable_artist_label(
        artist, on_artist_clicked=on_artist_clicked,
    )
    card.append(artist_label)

    child = Gtk.FlowBoxChild()
    child.set_activatable(False)  # Prevent FlowBoxChild from stealing clicks
    child.set_child(card)
    child._art_icon = art_icon
    child._art_image = art_image
    child._play_btn = play_btn
    return child


class CollectionView(Gtk.Box):
    """Collection view with fixed header and scrollable content."""

    __gtype_name__ = "CollectionView"

    def __init__(self, **kwargs) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
            **kwargs,
        )

        self._db = None
        self._tidal_provider = None
        self._album_art_service = None
        self._artist_image_service = None
        self._all_favorites: list[dict[str, str | int]] = []
        self._cached_tidal_albums: list[dict] = []
        self._cached_tidal_artists: list[dict] = []
        self._cached_tidal_playlists: list[dict] = []
        self._tidal_collection_generation: int = 0
        self._refresh_pending_id: int = 0  # debounce token for _refresh_current_view
        self._batch_generation: int = 0  # cancel token for batched widget creation
        # Pre-merged collection data (rebuilt on refresh / async fetch complete)
        self._merged_albums: list[dict] = []
        self._merged_artists: list[dict] = []
        self._active_filter: str = "All"
        self._active_sort: str = "Date Added"
        self._sort_ascending: bool = True
        self._active_view: str = "tracks"
        self._view_mode: ViewMode = ViewMode.LIST

        # Context menu callbacks
        self._context_callbacks: dict | None = None
        self._get_playlists = None
        self._album_context_callbacks: dict | None = None
        self._get_album_playlists = None
        self._artist_context_callbacks: dict | None = None
        self._current_menu: object = None
        # Navigation callbacks
        self._on_album_clicked: Callable[[str, str], None] | None = None
        self._on_artist_clicked: Callable[[str], None] | None = None
        self._on_play_album: Callable[[str, str], None] | None = None
        self._on_play_track: Callable | None = None
        self._on_playlist_clicked: Callable | None = None
        # Track objects keyed by track_id or "sid:<source_id>" for play/context use
        self._track_objects: dict[int | str, object] = {}
        # Callback: on_favorite_changed(track_id: int, is_favorite: bool)
        self.on_favorite_changed: Callable[[int, bool], None] | None = None
        # Callback: triggered when user clicks "Log In to Tidal" in filter empty state
        self.on_tidal_login: Callable | None = None

        # ---- Non-scrollable header section ----
        header_section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
        )
        header_section.set_margin_top(24)
        header_section.set_margin_start(16)
        header_section.set_margin_end(16)

        # ---- 1. Header row ----
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header_box.add_css_class("collection-header")

        heart_icon = Gtk.Image.new_from_icon_name(
            "emblem-favorite-symbolic"
        )
        heart_icon.set_pixel_size(28)
        heart_icon.add_css_class("collection-header-icon")
        header_box.append(heart_icon)

        title_label = Gtk.Label(label="Collection")
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

        # ---- 2. View toggle row ----
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
            ("Playlists", "playlists"),
        ]:
            btn = Gtk.ToggleButton(label=label_text)
            btn.add_css_class("filter-btn")
            btn._view_name = view_name  # type: ignore[attr-defined]
            btn.connect("toggled", self._on_view_toggled)
            view_box.append(btn)
            self._view_buttons.append(btn)

        header_section.append(view_box)

        # ---- 2b. Filter pills row (All / Tidal / Local) ----
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

        # Activate "All" by default
        self._filter_buttons[0].set_active(True)

        header_section.append(filter_box)

        # ---- 3. Sort + view mode row ----
        sort_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        sort_row.set_margin_bottom(12)

        # Sort dropdown
        self._sort_label = Gtk.Label(label="Sort by:")
        self._sort_label.add_css_class("caption")
        self._sort_label.add_css_class("dim-label")
        self._sort_label.set_valign(Gtk.Align.CENTER)
        sort_row.append(self._sort_label)

        self._sort_model = Gtk.StringList.new(_SORT_OPTIONS_TRACKS)
        self._sort_dropdown = Gtk.DropDown(model=self._sort_model)
        self._sort_dropdown.set_selected(0)
        self._sort_dropdown.add_css_class("collection-sort-dropdown")
        self._sort_dropdown.set_valign(Gtk.Align.CENTER)
        self._sort_dropdown.connect(
            "notify::selected", self._on_sort_changed
        )
        sort_row.append(self._sort_dropdown)

        # Sort direction toggle button
        self._sort_dir_btn = Gtk.Button.new_from_icon_name(
            "view-sort-ascending-symbolic"
        )
        self._sort_dir_btn.add_css_class("flat")
        self._sort_dir_btn.set_valign(Gtk.Align.CENTER)
        self._sort_dir_btn.set_tooltip_text("Ascending")
        self._sort_dir_btn.connect("clicked", self._on_sort_dir_clicked)
        sort_row.append(self._sort_dir_btn)

        # Spacer
        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        sort_row.append(spacer)

        # View mode toggle (list/compact/grid) - shown only on tracks tab
        self._view_mode_toggle = make_view_mode_toggle(
            on_mode_changed=self._on_view_mode_changed,
            initial_mode=ViewMode.LIST,
        )
        self._view_mode_toggle.set_valign(Gtk.Align.CENTER)
        sort_row.append(self._view_mode_toggle)

        header_section.append(sort_row)
        self.append(header_section)

        # Activate "Tracks" by default (after controls_box is set up)
        self._view_buttons[2].set_active(True)

        # ---- Scrollable content area ----
        self._scrolled = Gtk.ScrolledWindow()
        self._scrolled.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._scrolled.set_vexpand(True)
        self._drag_scroll = DragScrollHelper(self._scrolled)

        # ---- Content stack ----
        self._content_stack = Gtk.Stack()
        self._content_stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._content_stack.set_transition_duration(150)
        self._content_stack.set_vexpand(True)

        # -- Albums view (FlowBox grid) --
        albums_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        albums_container.set_margin_top(16)
        albums_container.set_margin_start(16)
        albums_container.set_margin_end(16)
        albums_container.set_margin_bottom(32)

        self._album_grid = Gtk.FlowBox()
        self._album_grid.set_homogeneous(True)
        self._album_grid.set_min_children_per_line(1)
        self._album_grid.set_max_children_per_line(6)
        self._album_grid.set_column_spacing(16)
        self._album_grid.set_row_spacing(16)
        self._album_grid.set_selection_mode(Gtk.SelectionMode.NONE)
        albums_container.append(self._album_grid)

        self._content_stack.add_named(albums_container, "albums")

        # -- Artists view (ListBox) --
        artists_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        artists_container.set_margin_top(16)
        artists_container.set_margin_start(16)
        artists_container.set_margin_end(16)
        artists_container.set_margin_bottom(32)

        self._artist_list = Gtk.ListBox()
        self._artist_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._artist_list.add_css_class("boxed-list")
        self._artist_list.connect(
            "row-activated", self._on_artist_row_activated
        )
        artists_container.append(self._artist_list)

        self._content_stack.add_named(artists_container, "artists")

        # -- Tracks list --
        self._favorites_list = Gtk.ListBox()
        self._favorites_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._favorites_list.add_css_class("boxed-list")
        self._favorites_list.connect(
            "row-activated", self._on_track_row_activated
        )
        list_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        list_container.set_margin_top(16)
        list_container.set_margin_start(16)
        list_container.set_margin_end(16)
        list_container.set_margin_bottom(32)
        list_container.append(self._favorites_list)
        self._content_stack.add_named(list_container, "tracks")

        # -- Tracks grid (FlowBox for grid mode) --
        self._favorites_grid = Gtk.FlowBox()
        self._favorites_grid.set_homogeneous(True)
        self._favorites_grid.set_min_children_per_line(1)
        self._favorites_grid.set_max_children_per_line(6)
        self._favorites_grid.set_row_spacing(16)
        self._favorites_grid.set_column_spacing(16)
        self._favorites_grid.set_selection_mode(Gtk.SelectionMode.NONE)

        grid_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        grid_container.set_margin_top(16)
        grid_container.set_margin_start(16)
        grid_container.set_margin_end(16)
        grid_container.set_margin_bottom(32)
        grid_container.append(self._favorites_grid)
        self._content_stack.add_named(grid_container, "tracks-grid")

        # -- Albums list view (for list/compact modes) --
        albums_list_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        albums_list_container.set_margin_top(16)
        albums_list_container.set_margin_start(16)
        albums_list_container.set_margin_end(16)
        albums_list_container.set_margin_bottom(32)

        self._album_list = Gtk.ListBox()
        self._album_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._album_list.add_css_class("boxed-list")
        self._album_list.connect(
            "row-activated", self._on_album_row_activated
        )
        albums_list_container.append(self._album_list)
        self._content_stack.add_named(albums_list_container, "albums-list")

        # -- Artists grid view (FlowBox for grid mode) --
        artists_grid_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        artists_grid_container.set_margin_top(16)
        artists_grid_container.set_margin_start(16)
        artists_grid_container.set_margin_end(16)
        artists_grid_container.set_margin_bottom(32)

        self._artist_grid = Gtk.FlowBox()
        self._artist_grid.set_homogeneous(True)
        self._artist_grid.set_min_children_per_line(1)
        self._artist_grid.set_max_children_per_line(6)
        self._artist_grid.set_column_spacing(16)
        self._artist_grid.set_row_spacing(16)
        self._artist_grid.set_selection_mode(Gtk.SelectionMode.NONE)
        artists_grid_container.append(self._artist_grid)
        self._content_stack.add_named(artists_grid_container, "artists-grid")

        # -- Playlists view (ListBox) --
        playlists_container = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        playlists_container.set_margin_top(16)
        playlists_container.set_margin_start(16)
        playlists_container.set_margin_end(16)
        playlists_container.set_margin_bottom(32)

        self._playlist_list = Gtk.ListBox()
        self._playlist_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._playlist_list.add_css_class("boxed-list")
        self._playlist_list.connect(
            "row-activated", self._on_playlist_row_activated
        )
        playlists_container.append(self._playlist_list)
        self._content_stack.add_named(playlists_container, "playlists")

        # -- Empty state --
        empty_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        empty_box.add_css_class("collection-empty-state")
        empty_box.set_vexpand(True)
        empty_box.set_margin_top(80)

        empty_icon = Gtk.Image.new_from_icon_name(
            "emblem-favorite-symbolic"
        )
        empty_icon.set_pixel_size(64)
        empty_box.append(empty_icon)

        self._empty_title = Gtk.Label(label="No collection items yet")
        self._empty_title.add_css_class("title-3")
        empty_box.append(self._empty_title)

        self._empty_subtitle = Gtk.Label(
            label="Heart any track to add it here"
        )
        self._empty_subtitle.add_css_class("caption")
        empty_box.append(self._empty_subtitle)

        self._content_stack.add_named(empty_box, "empty")

        # -- Tidal not-connected state --
        tidal_connect_box = make_tidal_connect_prompt(
            css_class="explore-login-prompt",
            icon_name="tidal-symbolic",
            heading_text="Connect to Tidal",
            description_text=(
                "Log in to your Tidal account to see\n"
                "your Tidal favorites here."
            ),
            button_text="Log In to Tidal",
            on_login_clicked=self._on_tidal_login_clicked_cb,
        )
        tidal_connect_box.set_vexpand(True)

        self._content_stack.add_named(tidal_connect_box, "tidal-connect")

        self._scrolled.set_child(self._content_stack)
        self.append(self._scrolled)

        # Populate initial view
        self._refresh_current_view()

    # ---- Public API ----

    def set_content_width(self, width: int) -> None:
        """Adjust header layout based on available content width."""
        self._sort_label.set_visible(width >= 500)

    def set_database(self, db) -> None:
        """Wire the collection view to a real database."""
        self._db = db
        self._restore_persisted_state()
        # Restore per-tab view mode (after tab is restored)
        self._restore_tab_view_mode()
        self._load_from_db()
        self._refresh_current_view()

    def set_tidal_provider(self, tidal_provider) -> None:
        """Wire the Tidal provider for syncing favorites on toggle."""
        self._tidal_provider = tidal_provider
        self._update_tidal_pill_sensitivity()

    def _update_tidal_pill_sensitivity(self) -> None:
        """Enable/disable the Tidal filter pill based on login state."""
        logged_in = (
            self._tidal_provider is not None
            and self._tidal_provider.is_logged_in
        )
        # Tidal pill is the second filter button (index 1)
        if len(self._filter_buttons) >= 2:
            tidal_btn = self._filter_buttons[1]
            tidal_btn.set_sensitive(logged_in)
            tidal_btn.set_tooltip_text(
                "" if logged_in else "Log in to Tidal to use this filter"
            )
            # If Tidal was selected and user logged out, switch to All
            if not logged_in and tidal_btn.get_active():
                self._filter_buttons[0].set_active(True)

    def set_album_art_service(self, art_service) -> None:
        """Set the AlbumArtService instance for loading album art."""
        self._album_art_service = art_service

    def set_artist_image_service(self, service) -> None:
        """Set the ArtistImageService for loading artist photos."""
        self._artist_image_service = service

    def refresh(self) -> None:
        """Reload favorites from the database and refresh the display."""
        # Reset fetch latch so a fresh Tidal fetch can occur
        self._tidal_fetch_attempted = False
        # Invalidate any in-flight async fetch
        self._tidal_fetch_generation = getattr(
            self, "_tidal_fetch_generation", 0
        ) + 1
        self._update_tidal_pill_sensitivity()
        if self._db is not None:
            self._load_from_db()
        self._rebuild_merged_collections()
        self._refresh_current_view()
        # Kick off async Tidal collection fetch (albums + artists)
        self._fetch_tidal_collection_async()

    def set_callbacks(
        self,
        on_album_clicked: Callable[[str, str], None] | None = None,
        on_artist_clicked: Callable[[str], None] | None = None,
        on_play_album: Callable[[str, str], None] | None = None,
        on_play_track: Callable | None = None,
        on_playlist_clicked: Callable | None = None,
    ) -> None:
        """Set callback functions for navigation actions."""
        self._on_album_clicked = on_album_clicked
        self._on_artist_clicked = on_artist_clicked
        self._on_play_album = on_play_album
        self._on_play_track = on_play_track
        self._on_playlist_clicked = on_playlist_clicked

    # ------------------------------------------------------------------
    # Scroll position persistence
    # ------------------------------------------------------------------

    def get_scroll_position(self) -> float:
        """Return the current vertical scroll position."""
        if hasattr(self, "_scrolled"):
            return self._scrolled.get_vadjustment().get_value()
        return 0.0

    def set_scroll_position(self, value: float) -> None:
        """Restore a previously saved scroll position."""
        if hasattr(self, "_scrolled"):
            self._scrolled.get_vadjustment().set_value(value)

    def highlight_playing_track(self, track) -> None:
        """Highlight the currently playing track in the favorites list/grid."""
        playing_sid = getattr(track, "source_id", None) if track else None
        playing_title = getattr(track, "title", "") if track else ""
        playing_artist = getattr(track, "artist", "") if track else ""

        def _get_field(obj, field, default=""):
            """Get a field from an object or dict."""
            if isinstance(obj, dict):
                return obj.get(field, default)
            return getattr(obj, field, default)

        def _match_track(td):
            if td is None or track is None:
                return False
            # Primary: match by source_id (most reliable)
            td_sid = _get_field(td, "source_id", None)
            if playing_sid and td_sid and str(playing_sid) == str(td_sid):
                return True
            # Fallback: match by (title, artist) tuple — both must be non-empty
            td_title = _get_field(td, "title", "")
            td_artist = _get_field(td, "artist", "")
            if (
                playing_title and playing_artist
                and td_title and td_artist
                and td_title == playing_title
                and td_artist == playing_artist
            ):
                return True
            return False

        # Highlight in list view
        if hasattr(self, "_favorites_list"):
            row = self._favorites_list.get_first_child()
            while row is not None:
                td = getattr(row, "_track_obj", None) or getattr(
                    row, "_track_data", None
                )
                if _match_track(td):
                    row.add_css_class("now-playing-row")
                else:
                    row.remove_css_class("now-playing-row")
                row = row.get_next_sibling()

        # Highlight in grid view
        if hasattr(self, "_favorites_grid"):
            child = self._favorites_grid.get_first_child()
            while child is not None:
                td = getattr(child, "_track_obj", None) or getattr(
                    child, "_track_data", None
                )
                if _match_track(td):
                    child.add_css_class("now-playing-row")
                else:
                    child.remove_css_class("now-playing-row")
                child = child.get_next_sibling()

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists,
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

    def set_artist_context_callbacks(
        self,
        callbacks: dict,
    ) -> None:
        """Set callback functions for the artist right-click context menu."""
        self._artist_context_callbacks = callbacks

    # ---- Album art loading ----

    def load_album_art_for_card(
        self, child: Gtk.FlowBoxChild, track
    ) -> None:
        """Load album art asynchronously for a grid album card."""
        art_service = self._album_art_service
        if art_service is None or track is None:
            return

        art_icon = getattr(child, "_art_icon", None)
        art_image = getattr(child, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        scale = child.get_scale_factor() or 1
        art_px = 160 * scale

        # Fast path: use cached texture immediately (avoids flicker on sort change)
        cached_texture = art_service.get_texture_for_track(track, art_px, art_px)
        if cached_texture is not None:
            art_image.set_from_paintable(cached_texture)
            art_image.set_visible(True)
            art_icon.set_visible(False)
            return

        request_token = object()
        child._art_request_token = request_token  # type: ignore[attr-defined]

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            if getattr(child, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = art_service.get_or_create_texture(track, pixbuf, art_px, art_px)
                if texture is not None:
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)

        art_service.get_art_async(track, _on_art_loaded, width=art_px, height=art_px)

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
        """Attach a right-click gesture to a favorites row."""
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

    def _attach_grid_context_gesture(
        self, child: Gtk.FlowBoxChild, album_tracks: list
    ) -> None:
        """Attach a right-click gesture to a grid album card."""
        if self._context_callbacks is None or not album_tracks:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(g, n_press, x, y, trk=album_tracks[0]):
            if n_press != 1:
                return
            self._show_track_context_menu(child, x, y, trk)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        child.add_controller(gesture)

    def _attach_grid_play_button(
        self, child: Gtk.FlowBoxChild, album_tracks: list
    ) -> None:
        """Wire play button on a grid card to play the first track."""
        play_btn = getattr(child, "_play_btn", None)
        if play_btn is None or not album_tracks:
            return
        if self._context_callbacks is None:
            return

        _noop = lambda *_args: None
        on_play = self._context_callbacks.get("on_play", _noop)

        def _on_clicked(_btn, trk=album_tracks[0]):
            on_play(trk)

        play_btn.connect("clicked", _on_clicked)

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
            "is_favorite": True,
        }

        self._current_menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    # ---- Album context menu helpers ----

    def _attach_album_context_gesture(
        self, child: Gtk.FlowBoxChild
    ) -> None:
        """Attach a right-click gesture to an album card."""
        if self._album_context_callbacks is None:
            return

        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if album_title is None or album_artist is None:
            return

        tidal_id = getattr(child, "_tidal_id", None)
        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(
            g, n_press, x, y,
            a=album_title, ar=album_artist, tid=tidal_id
        ):
            if n_press != 1:
                return
            self._show_album_context_menu(child, x, y, a, ar, tidal_id=tid)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        child.add_controller(gesture)

    def _show_album_context_menu(
        self,
        widget: Gtk.Widget,
        x: float,
        y: float,
        album_name: str,
        artist: str,
        tidal_id: str | None = None,
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
            "on_add_to_favorites": lambda a=album_name, ar=artist, tid=tidal_id: cbs.get("on_add_to_favorites", _noop)(a, ar, tid),
            "on_remove_from_collection": lambda a=album_name, ar=artist, tid=tidal_id: cbs.get("on_remove_from_collection", _noop)(a, ar, tid),
            "on_go_to_artist": lambda a=album_name, ar=artist: cbs.get("on_go_to_artist", _noop)(a, ar),
            "on_shuffle_album": lambda a=album_name, ar=artist: cbs.get("on_shuffle_album", _noop)(a, ar),
            "on_properties": lambda a=album_name, ar=artist: cbs.get("on_properties", _noop)(a, ar),
        }

        album_data = {
            "album": album_name,
            "artist": artist,
            "tidal_id": tidal_id,
        }

        # Determine if album is saved in collection
        is_saved = False
        if tidal_id:
            # Check cached tidal albums first
            is_saved = any(
                str(a.get("tidal_id", "")) == str(tidal_id)
                for a in self._cached_tidal_albums
            )
            # Fallback: if we're on the Albums tab showing merged albums,
            # a tidal-sourced album with a tidal_id is by definition saved
            if not is_saved:
                is_saved = any(
                    str(a.get("tidal_id", "")) == str(tidal_id)
                    for a in self._merged_albums
                    if a.get("source") == "tidal"
                )

        self._current_menu = AlbumContextMenu(
            album_data=album_data,
            callbacks=callbacks,
            playlists=playlists,
            is_saved_in_collection=is_saved,
        )
        self._current_menu.show(widget, x, y)

    # ---- Artist context menu helpers ----

    def _attach_artist_context_gesture(
        self, row: Gtk.ListBoxRow
    ) -> None:
        """Attach a right-click gesture to an artist row."""
        if self._artist_context_callbacks is None:
            return

        artist_name = getattr(row, "_artist_name", None)
        if artist_name is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(
            g, n_press, x, y, name=artist_name
        ):
            if n_press != 1:
                return
            self._show_artist_context_menu(row, x, y, name)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_artist_context_menu(
        self,
        widget: Gtk.Widget,
        x: float,
        y: float,
        artist_name: str,
    ) -> None:
        """Create and display a context menu for an artist row."""
        if self._artist_context_callbacks is None:
            return

        cbs = self._artist_context_callbacks
        _noop = lambda *_args: None
        callbacks = {
            "on_play_all": lambda name=artist_name: cbs.get("on_play_all", _noop)(name),
            "on_add_all_to_queue": lambda name=artist_name: cbs.get("on_add_all_to_queue", _noop)(name),
            "on_view_artist": lambda name=artist_name: cbs.get("on_view_artist", _noop)(name),
            "on_artist_radio": lambda name=artist_name: cbs.get("on_artist_radio", _noop)(name),
            "on_artist_mix": lambda name=artist_name: cbs.get("on_artist_mix", _noop)(name),
            "on_follow_artist": lambda name=artist_name: cbs.get("on_follow_artist", _noop)(name),
            "on_unfollow_artist": lambda name=artist_name: cbs.get("on_unfollow_artist", _noop)(name),
            "on_shuffle_artist": lambda name=artist_name: cbs.get("on_shuffle_artist", _noop)(name),
            "on_properties": lambda name=artist_name: cbs.get("on_properties", _noop)(name),
        }

        artist_data = {"artist": artist_name}

        self._current_menu = ArtistContextMenu(
            artist_data=artist_data,
            callbacks=callbacks,
        )
        self._current_menu.show(widget, x, y)

    # ---- Album play button helper ----

    def _attach_album_play_button(self, child: Gtk.FlowBoxChild) -> None:
        """Wire the play button on an album card to play the album."""
        play_btn = getattr(child, "_play_btn", None)
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if play_btn is None or album_title is None or album_artist is None:
            return

        def _on_play_clicked(
            _btn, a=album_title, ar=album_artist
        ):
            cb = self._on_play_album
            if cb is not None:
                cb(a, ar)

        play_btn.connect("clicked", _on_play_clicked)

    # ---- Navigation callbacks ----

    def _attach_album_card_click_gesture(
        self, child: Gtk.FlowBoxChild
    ) -> None:
        """Attach a left-click gesture for album card navigation.

        This replaces FlowBox child-activated for cards with
        ``set_activatable(False)``, so the play button can receive
        clicks without the FlowBoxChild stealing them.
        """
        gesture = Gtk.GestureClick.new()
        gesture.set_button(1)
        gesture.set_propagation_phase(Gtk.PropagationPhase.BUBBLE)

        def _on_click(g, n_press, _x, _y):
            if n_press != 1:
                return
            # Don't navigate if the play button was clicked
            seq = g.get_last_updated_sequence()
            if seq is not None and g.get_sequence_state(seq) == Gtk.EventSequenceState.DENIED:
                return
            play_btn = getattr(child, "_play_btn", None)
            if play_btn is not None:
                btn_native = play_btn.get_native()
                if btn_native is not None:
                    success, bx, by = play_btn.compute_point(child, 0, 0)
                    if success:
                        w = play_btn.get_width()
                        h = play_btn.get_height()
                        if bx <= _x <= bx + w and by <= _y <= by + h:
                            return
            album = getattr(child, "_album_title", None)
            artist = getattr(child, "_album_artist", None)
            if album and artist and self._on_album_clicked:
                self._on_album_clicked(album, artist)

        gesture.connect("released", _on_click)
        child.add_controller(gesture)

    def _attach_artist_card_click_gesture(
        self, child: Gtk.FlowBoxChild
    ) -> None:
        """Attach a left-click gesture for artist card navigation."""
        gesture = Gtk.GestureClick.new()
        gesture.set_button(1)
        gesture.set_propagation_phase(Gtk.PropagationPhase.BUBBLE)

        def _on_click(g, n_press, _x, _y):
            if n_press != 1:
                return
            # Don't navigate if the play button was clicked
            seq = g.get_last_updated_sequence()
            if seq is not None and g.get_sequence_state(seq) == Gtk.EventSequenceState.DENIED:
                return
            play_btn = getattr(child, "_play_btn", None)
            if play_btn is not None:
                btn_native = play_btn.get_native()
                if btn_native is not None:
                    success, bx, by = play_btn.compute_point(child, 0, 0)
                    if success:
                        w = play_btn.get_width()
                        h = play_btn.get_height()
                        if bx <= _x <= bx + w and by <= _y <= by + h:
                            return
            artist_name = getattr(child, "_artist_name", None)
            if artist_name and self._on_artist_clicked:
                self._on_artist_clicked(artist_name)

        gesture.connect("released", _on_click)
        child.add_controller(gesture)

    def _on_album_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle album list row click — navigate to album detail."""
        album = getattr(row, "_album_title", None)
        artist = getattr(row, "_album_artist", None)
        if album and artist and self._on_album_clicked:
            self._on_album_clicked(album, artist)

    def _on_artist_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle artist row click — navigate to artist detail."""
        artist_name = getattr(row, "_artist_name", None)
        if artist_name and self._on_artist_clicked:
            self._on_artist_clicked(artist_name)

    def _on_track_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle track row click — play the track."""
        track_obj = getattr(row, "_track_obj", None)
        if track_obj is not None and self._on_play_track is not None:
            self._on_play_track(track_obj)

    # ---- Internal helpers ----

    def _resolve_track_obj(self, track_dict: dict) -> object | None:
        """Look up the canonical Track object for a favorites dict entry.

        Tries ``track_id`` first (local DB tracks), then falls back to
        ``source_id`` (Tidal-only tracks that have no local DB id).
        """
        tid = track_dict.get("track_id")
        if tid is not None:
            obj = self._track_objects.get(tid)
            if obj is not None:
                return obj
        sid = track_dict.get("source_id")
        if sid:
            return self._track_objects.get(f"sid:{sid}")
        return None

    def _load_from_db(self) -> None:
        """Load favorites from the database into the internal list."""
        if self._db is None:
            return

        try:
            tracks = self._db.get_favorites()
            self._all_favorites = []
            self._track_objects = {}
            for track in tracks:
                self._all_favorites.append({
                    "title": track.title,
                    "artist": track.artist,
                    "album": track.album or "",
                    "source": track.source.value,
                    "quality": track.quality_label,
                    "duration": _format_duration(track.duration),
                    "date_added": track.added_at or "",
                    "track_id": track.id,
                    "source_id": str(track.source_id),
                })
                if track.id is not None:
                    self._track_objects[track.id] = track
                if track.source_id:
                    self._track_objects[f"sid:{track.source_id}"] = track
        except Exception:
            logger.warning("Failed to load favorites from database", exc_info=True)
            self._all_favorites = []
            self._track_objects = {}

    def _on_unfavorite(self, track_id: int) -> None:
        """Remove a track from favorites via the database."""
        if self._db is not None:
            try:
                track_obj = self._track_objects.get(track_id)
                self._db.set_favorite(track_id, False)

                # Also remove from Tidal in a background thread
                if (
                    track_obj is not None
                    and self._tidal_provider is not None
                    and getattr(track_obj, "is_tidal", False)
                ):
                    tidal = self._tidal_provider
                    sid = track_obj.source_id

                    def _remove_tidal_fav():
                        try:
                            tidal.remove_favorite(sid)
                        except Exception:
                            logger.warning(
                                "Failed to remove Tidal favorite",
                                exc_info=True,
                            )

                    threading.Thread(
                        target=_remove_tidal_fav, daemon=True
                    ).start()

                self._load_from_db()
                self._refresh_current_view()
                if self.on_favorite_changed is not None:
                    self.on_favorite_changed(track_id, False)
            except Exception:
                logger.warning("Failed to unfavorite track", exc_info=True)

    def _on_tidal_login_clicked_cb(self) -> None:
        """Handle Tidal login click."""
        if self.on_tidal_login is not None:
            self.on_tidal_login()

    # ---- Data aggregation ----

    def _rebuild_merged_collections(self) -> None:
        """Rebuild the merged album and artist lists from caches.

        Called once on refresh() and when the async Tidal fetch completes,
        NOT on every filter/sort change.
        """
        # -- Albums --
        albums: dict[tuple[str, str], dict] = {}
        for ta in self._cached_tidal_albums:
            key = (
                ta.get("title", "") or "Unknown Album",
                ta.get("artist", "") or "Unknown Artist",
            )
            albums[key] = {
                "album": key[0],
                "artist": key[1],
                "source": "tidal",
                "track_count": ta.get("num_tracks", 0) or 0,
                "date_added": ta.get("date_added", ""),
                "cover_url": ta.get("cover_url"),
                "tidal_id": ta.get("tidal_id"),
            }
        local_counts: dict[tuple[str, str], dict] = {}
        for track in self._all_favorites:
            if track.get("source") == "local":
                key = (
                    track.get("album", "") or "Unknown Album",
                    track.get("artist", "") or "Unknown Artist",
                )
                if key not in local_counts:
                    local_counts[key] = {"count": 0, "date_added": ""}
                local_counts[key]["count"] += 1
                track_date = track.get("date_added", "")
                if track_date > local_counts[key]["date_added"]:
                    local_counts[key]["date_added"] = track_date
        for key, info in local_counts.items():
            if key not in albums:
                albums[key] = {
                    "album": key[0],
                    "artist": key[1],
                    "source": "local",
                    "track_count": info["count"],
                    "date_added": info["date_added"],
                }
        self._merged_albums = list(albums.values())

        # -- Artists --
        artists: dict[str, dict] = {}
        for ta in self._cached_tidal_artists:
            name = ta.get("name", "") or "Unknown Artist"
            artists[name] = {
                "artist": name,
                "track_count": 0,
                "sources": ["tidal"],
                "image_url": ta.get("image_url"),
                "tidal_id": ta.get("tidal_id"),
            }
        # Count ALL favorited tracks per artist (local + tidal)
        for track in self._all_favorites:
            name = track.get("artist", "") or "Unknown Artist"
            source = track.get("source", "local")
            if name not in artists:
                artists[name] = {
                    "artist": name,
                    "track_count": 0,
                    "sources": [source],
                }
            else:
                if source not in artists[name]["sources"]:
                    artists[name]["sources"].append(source)
            artists[name]["track_count"] += 1
        self._merged_artists = list(artists.values())

    # ---- Filtering ----

    @property
    def _tidal_connected(self) -> bool:
        """Return True if the Tidal provider is logged in."""
        return (
            self._tidal_provider is not None
            and self._tidal_provider.is_logged_in
        )

    def _get_filtered_tracks(self) -> list[dict[str, str | int]]:
        """Return favorites filtered by the active source filter."""
        if self._active_filter == "All":
            if self._tidal_connected:
                return list(self._all_favorites)
            return [t for t in self._all_favorites if t["source"] != "tidal"]
        source_key = self._active_filter.lower()
        return [
            t
            for t in self._all_favorites
            if t["source"] == source_key
        ]

    def _get_filtered_albums(self) -> list[dict]:
        """Return merged albums filtered by the active source filter."""
        if self._active_filter == "All":
            if self._tidal_connected:
                return list(self._merged_albums)
            return [a for a in self._merged_albums if a["source"] != "tidal"]
        source_key = self._active_filter.lower()
        return [a for a in self._merged_albums if a["source"] == source_key]

    def _get_filtered_artists(self) -> list[dict]:
        """Return merged artists filtered by the active source filter."""
        if self._active_filter == "All":
            if self._tidal_connected:
                return list(self._merged_artists)
            return [
                a for a in self._merged_artists
                if "tidal" not in a["sources"] or "local" in a["sources"]
            ]
        source_key = self._active_filter.lower()
        return [
            a for a in self._merged_artists
            if source_key in a["sources"]
        ]

    # ---- Sorting ----

    def _get_sorted_tracks(
        self, tracks: list[dict[str, str | int]]
    ) -> list[dict[str, str | int]]:
        """Sort tracks by the active sort criterion and direction."""
        sort_field = _SORT_KEYS.get(self._active_sort, "date_added")
        # For date_added: ascending = oldest first, descending = newest first
        if sort_field == "date_added":
            reverse = not self._sort_ascending
        else:
            reverse = not self._sort_ascending

        def _sort_key(t: dict) -> tuple[int, str]:
            val = str(t.get(sort_field, "") or "")
            # Items with no value sort last regardless of direction
            if not val:
                return (1, "")
            return (0, val)

        return sorted(tracks, key=_sort_key, reverse=reverse)

    def _get_sorted_albums(self, albums: list[dict]) -> list[dict]:
        """Sort albums by the active sort criterion and direction."""
        reverse = not self._sort_ascending
        if self._active_sort in ("Name", "Name (A-Z)", "Name (Z-A)"):
            return sorted(
                albums,
                key=lambda a: (a["album"] or "").lower(),
                reverse=reverse,
            )
        if self._active_sort == "Artist":
            return sorted(
                albums,
                key=lambda a: (a["artist"] or "").lower(),
                reverse=reverse,
            )
        # Recently Added — items with no date sort last
        def _date_key(a: dict) -> tuple[int, str]:
            val = a.get("date_added", "") or ""
            if not val:
                return (1, "")
            return (0, val)

        return sorted(
            albums,
            key=_date_key,
            reverse=not reverse,  # desc by default for dates
        )

    def _get_sorted_artists(self, artists: list[dict]) -> list[dict]:
        """Sort artists by the active sort criterion and direction."""
        reverse = not self._sort_ascending
        if self._active_sort in ("Name", "Name (A-Z)", "Name (Z-A)"):
            return sorted(
                artists,
                key=lambda a: (a["artist"] or "").lower(),
                reverse=reverse,
            )
        if self._active_sort == "Track Count":
            return sorted(
                artists,
                key=lambda a: a["track_count"],
                reverse=reverse,
            )
        if self._active_sort == "Recently Added":
            return sorted(
                artists,
                key=lambda a: a.get("latest_added") or "",
                reverse=not reverse,
            )
        # Default: name
        return sorted(
            artists,
            key=lambda a: (a["artist"] or "").lower(),
            reverse=reverse,
        )

    # ---- List/FlowBox clearing ----

    @staticmethod
    def _clear_list_box(list_box: Gtk.ListBox) -> None:
        """Remove all rows from a ListBox."""
        list_box.remove_all()

    @staticmethod
    def _clear_flow_box(flow_box: Gtk.FlowBox) -> None:
        """Remove all children from a FlowBox."""
        flow_box.remove_all()

    # ---- View refresh ----

    def _update_count_label(self, count: int, item_type: str = "track") -> None:
        """Update the count label in the header."""
        word = item_type if count == 1 else f"{item_type}s"
        self._count_label.set_label(f"{count} {word}")

    def _refresh_current_view(self) -> None:
        """Schedule a debounced refresh of the current view.

        Rapid calls (e.g. fast filter clicks) are collapsed into a single
        refresh that fires ~50 ms after the last request.
        """
        self._refresh_pending_id += 1
        token = self._refresh_pending_id
        GLib.timeout_add(50, self._do_refresh, token)

    def _do_refresh(self, token: int) -> bool:
        """Execute the actual view refresh if *token* is still current."""
        if token != self._refresh_pending_id:
            return False  # superseded by a newer request
        if not hasattr(self, "_favorites_list"):
            return False

        # Show the Tidal connect card when filtering by Tidal and not logged in
        tidal_not_connected = (
            self._active_filter == "Tidal"
            and (
                self._tidal_provider is None
                or not self._tidal_provider.is_logged_in
            )
        )
        if tidal_not_connected:
            self._count_label.set_label("0 tracks")
            self._content_stack.set_visible_child_name("tidal-connect")
            return False

        if self._active_view == "albums":
            self._refresh_albums()
        elif self._active_view == "artists":
            self._refresh_artists()
        elif self._active_view == "playlists":
            self._refresh_playlists()
        else:
            self._refresh_tracks()
        return False

    def _find_album_track(self, album: str, artist: str):
        """Return the first track object matching album+artist, or None."""
        for track_dict in self._all_favorites:
            if (
                track_dict.get("album") == album
                and track_dict.get("artist") == artist
            ):
                return self._resolve_track_obj(track_dict)
        return None

    def _refresh_albums(self) -> None:
        """Rebuild the albums display based on current view mode (batched)."""
        self._clear_flow_box(self._album_grid)
        self._clear_list_box(self._album_list)

        filtered = self._get_filtered_albums()
        sorted_albums = self._get_sorted_albums(filtered)

        self._update_count_label(len(sorted_albums), "album")

        if not sorted_albums:
            self._empty_title.set_label("No albums in collection")
            self._empty_subtitle.set_label(
                "Save albums on Tidal or heart local tracks"
            )
            self._content_stack.set_visible_child_name("empty")
            return

        mode = self._view_mode
        if mode == ViewMode.GRID:
            self._content_stack.set_visible_child_name("albums")
        else:
            self._content_stack.set_visible_child_name("albums-list")

        self._batch_generation += 1
        gen = self._batch_generation
        offset = 0

        def _add_batch() -> bool:
            nonlocal offset
            if gen != self._batch_generation:
                return False
            end = min(offset + _BATCH_SIZE, len(sorted_albums))
            for i in range(offset, end):
                album_data = sorted_albums[i]
                if mode == ViewMode.GRID:
                    card = _make_album_card(
                        album=album_data["album"],
                        artist=album_data["artist"],
                        source=album_data["source"],
                        on_artist_clicked=self._on_artist_clicked,
                    )
                    card._tidal_id = album_data.get("tidal_id")
                    self._attach_album_context_gesture(card)
                    self._attach_album_play_button(card)
                    self._attach_album_card_click_gesture(card)
                    self._album_grid.append(card)

                    cover_url = album_data.get("cover_url")
                    if cover_url and self._album_art_service is not None:
                        self._load_album_art_from_url(card, cover_url)
                    else:
                        representative_track = self._find_album_track(
                            album_data["album"], album_data["artist"]
                        )
                        self.load_album_art_for_card(card, representative_track)
                elif mode == ViewMode.COMPACT_LIST:
                    row = _make_album_compact_row(
                        album=album_data["album"],
                        artist=album_data["artist"],
                        source=album_data["source"],
                        index=i,
                        on_artist_clicked=self._on_artist_clicked,
                    )
                    row._tidal_id = album_data.get("tidal_id")
                    self._attach_album_context_gesture(row)
                    self._attach_album_play_button(row)
                    self._album_list.append(row)
                else:  # LIST
                    row = _make_album_list_row(
                        album=album_data["album"],
                        artist=album_data["artist"],
                        source=album_data["source"],
                        track_count=album_data.get("track_count", 0),
                        on_artist_clicked=self._on_artist_clicked,
                    )
                    row._tidal_id = album_data.get("tidal_id")
                    self._attach_album_context_gesture(row)
                    self._attach_album_play_button(row)
                    self._album_list.append(row)
                    cover_url = album_data.get("cover_url")
                    if cover_url and self._album_art_service is not None:
                        self._load_album_art_from_url(row, cover_url)
                    else:
                        representative_track = self._find_album_track(
                            album_data["album"], album_data["artist"]
                        )
                        self._load_art_for_album_row(row, representative_track)
            offset = end
            return offset < len(sorted_albums)

        GLib.idle_add(_add_batch)

    def _load_album_art_from_url(
        self, child: Gtk.FlowBoxChild, cover_url: str
    ) -> None:
        """Load album art from a URL for an album card."""
        art_service = self._album_art_service
        if art_service is None:
            return

        art_icon = getattr(child, "_art_icon", None)
        art_image = getattr(child, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        request_token = object()
        child._art_request_token = request_token  # type: ignore[attr-defined]

        scale = child.get_scale_factor() or 1
        art_px = 160 * scale

        def _load():
            try:
                pixbuf = art_service.load_pixbuf_from_url(
                    cover_url, art_px, art_px
                )
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

    def _refresh_artists(self) -> None:
        """Rebuild the artists display based on current view mode (batched)."""
        self._clear_list_box(self._artist_list)
        self._clear_flow_box(self._artist_grid)

        filtered = self._get_filtered_artists()
        sorted_artists = self._get_sorted_artists(filtered)

        self._update_count_label(len(sorted_artists), "artist")

        if not sorted_artists:
            self._empty_title.set_label("No artists in collection")
            self._empty_subtitle.set_label(
                "Follow artists on Tidal or heart local tracks"
            )
            self._content_stack.set_visible_child_name("empty")
            return

        mode = self._view_mode
        if mode == ViewMode.GRID:
            self._content_stack.set_visible_child_name("artists-grid")
        else:
            self._content_stack.set_visible_child_name("artists")

        self._batch_generation += 1
        gen = self._batch_generation
        offset = 0
        load_images = self._artist_image_service is not None

        def _add_batch() -> bool:
            nonlocal offset
            if gen != self._batch_generation:
                return False
            end = min(offset + _BATCH_SIZE, len(sorted_artists))
            for i in range(offset, end):
                artist_data = sorted_artists[i]
                if mode == ViewMode.GRID:
                    card = _make_artist_card(
                        artist=artist_data["artist"],
                        track_count=artist_data["track_count"],
                        sources=artist_data["sources"],
                    )
                    self._attach_artist_context_gesture(card)
                    self._attach_artist_play_button(card)
                    self._attach_artist_card_click_gesture(card)
                    self._artist_grid.append(card)
                    if load_images:
                        self._load_artist_image_for_card(card)
                elif mode == ViewMode.COMPACT_LIST:
                    row = _make_artist_compact_row(
                        artist=artist_data["artist"],
                        sources=artist_data["sources"],
                        index=i,
                    )
                    self._attach_artist_context_gesture(row)
                    self._attach_artist_play_button(row)
                    self._artist_list.append(row)
                else:  # LIST
                    row = _make_artist_row(
                        artist=artist_data["artist"],
                        track_count=artist_data["track_count"],
                        sources=artist_data["sources"],
                    )
                    self._attach_artist_context_gesture(row)
                    self._attach_artist_play_button(row)
                    self._artist_list.append(row)
                    if load_images:
                        self._load_artist_image_for_row(row)
            offset = end
            return offset < len(sorted_artists)

        GLib.idle_add(_add_batch)

    def _refresh_playlists(self) -> None:
        """Rebuild the playlists display (batched)."""
        self._clear_list_box(self._playlist_list)

        playlists = list(self._cached_tidal_playlists)

        # Sort
        if self._active_sort == "Name":
            playlists.sort(
                key=lambda p: (p.get("name", "") or "").lower(),
                reverse=not self._sort_ascending,
            )
        elif self._active_sort == "Track Count":
            playlists.sort(
                key=lambda p: p.get("track_count", 0) or 0,
                reverse=not self._sort_ascending,
            )

        self._update_count_label(len(playlists), "playlist")

        if not playlists:
            if (
                self._tidal_provider is None
                or not self._tidal_provider.is_logged_in
            ):
                self._content_stack.set_visible_child_name("tidal-connect")
            else:
                self._empty_title.set_label("No playlists in collection")
                self._empty_subtitle.set_label(
                    "Create or save playlists on Tidal"
                )
                self._content_stack.set_visible_child_name("empty")
            return

        self._content_stack.set_visible_child_name("playlists")

        self._batch_generation += 1
        gen = self._batch_generation
        offset = 0

        def _add_batch() -> bool:
            nonlocal offset
            if gen != self._batch_generation:
                return False
            end = min(offset + _BATCH_SIZE, len(playlists))
            for i in range(offset, end):
                pl = playlists[i]
                row = self._make_playlist_row(pl)
                self._playlist_list.append(row)
                cover_url = pl.get("cover_url")
                if cover_url and self._album_art_service is not None:
                    self._load_playlist_art(row, cover_url)
            offset = end
            return offset < len(playlists)

        GLib.idle_add(_add_batch)

    def _make_playlist_row(self, playlist: dict) -> Gtk.ListBoxRow:
        """Build a single playlist row for the collection list."""
        row = Gtk.ListBoxRow()
        row.add_css_class("collection-playlist-row")

        hbox = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        hbox.set_margin_top(8)
        hbox.set_margin_bottom(8)
        hbox.set_margin_start(12)
        hbox.set_margin_end(12)

        # Cover art placeholder
        art_icon = Gtk.Image.new_from_icon_name("view-list-symbolic")
        art_icon.set_pixel_size(48)
        art_icon.set_size_request(48, 48)
        art_icon.set_opacity(0.4)
        hbox.append(art_icon)

        # Cover art image (hidden until loaded)
        art_image = Gtk.Image()
        art_image.set_pixel_size(48)
        art_image.set_size_request(48, 48)
        art_image.set_visible(False)
        hbox.append(art_image)

        # Text info
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_hexpand(True)
        text_box.set_valign(Gtk.Align.CENTER)

        name = playlist.get("name", "Playlist")
        title_label = Gtk.Label(label=name)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.add_css_class("body")
        text_box.append(title_label)

        # Subtitle: track count + creator
        parts: list[str] = []
        track_count = playlist.get("track_count", 0) or 0
        if track_count:
            parts.append(f"{track_count} track{'s' if track_count != 1 else ''}")
        creator = playlist.get("creator", "")
        if creator:
            parts.append(f"by {creator}")
        elif playlist.get("is_user_created"):
            parts.append("by you")
        subtitle = " \u2022 ".join(parts) if parts else ""
        if subtitle:
            sub_label = Gtk.Label(label=subtitle)
            sub_label.set_xalign(0)
            sub_label.set_ellipsize(Pango.EllipsizeMode.END)
            sub_label.add_css_class("caption")
            sub_label.add_css_class("dim-label")
            text_box.append(sub_label)

        hbox.append(text_box)

        # Tidal badge
        badge = make_tidal_source_badge(
            label_text="Tidal",
            css_class="source-badge-tidal",
            icon_size=10,
        )
        badge.set_valign(Gtk.Align.CENTER)
        hbox.append(badge)

        row.set_child(hbox)

        # Store data for click handling
        tidal_id = playlist.get("tidal_id", "")
        row._playlist_tidal_id = tidal_id  # type: ignore[attr-defined]
        row._playlist_name = name  # type: ignore[attr-defined]
        row._art_icon = art_icon  # type: ignore[attr-defined]
        row._art_image = art_image  # type: ignore[attr-defined]
        return row

    def _load_playlist_art(self, row: Gtk.ListBoxRow, url: str) -> None:
        """Load cover art for a playlist row from a URL."""
        if self._album_art_service is None:
            return
        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        gen = self._batch_generation

        def _on_loaded(pixbuf):
            if gen != self._batch_generation:
                return
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)

        self._album_art_service.load_pixbuf_from_url(
            url, 48, callback=_on_loaded
        )

    def _on_playlist_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle click on a playlist row in the collection."""
        tidal_id = getattr(row, "_playlist_tidal_id", None)
        name = getattr(row, "_playlist_name", None)
        if tidal_id and self._on_playlist_clicked:
            self._on_playlist_clicked(tidal_id, name)

    def _load_artist_image_for_row(self, row: Gtk.ListBoxRow) -> None:
        """Request an async artist image for a row."""
        service = self._artist_image_service
        if service is None:
            return
        artist_name = getattr(row, "_artist_name", None)
        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if not artist_name or art_icon is None or art_image is None:
            return

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        def _on_loaded(pixbuf) -> None:
            if getattr(row, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)

        scale = row.get_scale_factor() or 1
        service.get_artist_image_async(artist_name, _on_loaded, size=32 * scale)

    def _load_artist_image_for_card(self, card: Gtk.FlowBoxChild) -> None:
        """Request an async artist image for a grid card."""
        service = self._artist_image_service
        if service is None:
            return
        artist_name = getattr(card, "_artist_name", None)
        art_icon = getattr(card, "_art_icon", None)
        art_image = getattr(card, "_art_image", None)
        if not artist_name or art_icon is None or art_image is None:
            return

        request_token = object()
        card._art_request_token = request_token

        def _on_loaded(pixbuf) -> None:
            if getattr(card, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)

        scale = card.get_scale_factor() or 1
        service.get_artist_image_async(artist_name, _on_loaded, size=160 * scale)

    def _load_art_for_album_row(self, row, track) -> None:
        """Load album art asynchronously for an album list row."""
        art_service = self._album_art_service
        if art_service is None or track is None:
            return
        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        # Fast path: use cached texture immediately
        cached_texture = art_service.get_texture_for_track(track, 40, 40)
        if cached_texture is not None:
            art_image.set_from_paintable(cached_texture)
            art_image.set_visible(True)
            art_icon.set_visible(False)
            return

        request_token = object()
        row._art_request_token = request_token

        def _on_loaded(pixbuf) -> None:
            if getattr(row, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = art_service.get_or_create_texture(track, pixbuf, 40, 40)
                if texture is not None:
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)

        art_service.get_art_async(track, _on_loaded, width=40, height=40)

    def _attach_artist_play_button(self, widget) -> None:
        """Wire the play overlay button on an artist card or row."""
        play_btn = getattr(widget, "_play_btn", None)
        if play_btn is None:
            return
        artist_name = getattr(widget, "_artist_name", None)
        if artist_name is None:
            return

        def _on_play(_btn, name=artist_name):
            if self._on_artist_clicked is not None:
                self._on_artist_clicked(name)

        play_btn.connect("clicked", _on_play)

    def _refresh_tracks(self) -> None:
        """Rebuild the tracks display from current filter/sort state."""
        # Clear both list and grid
        self._clear_list_box(self._favorites_list)
        self._clear_flow_box(self._favorites_grid)

        filtered = self._get_filtered_tracks()
        sorted_tracks = self._get_sorted_tracks(filtered)

        # When the Tidal filter is active, we are logged in, and the local
        # DB has no Tidal tracks yet, fetch them from the Tidal API.
        if (
            self._active_filter == "Tidal"
            and not sorted_tracks
            and self._tidal_provider is not None
            and self._tidal_provider.is_logged_in
            and not getattr(self, "_tidal_fetch_attempted", False)
        ):
            self._tidal_fetch_attempted = True
            self._count_label.set_label("Loading...")
            self._content_stack.set_visible_child_name("empty")
            self._empty_title.set_label("Loading Tidal favorites...")
            self._empty_subtitle.set_label(
                "Fetching your favorites from Tidal"
            )
            self._fetch_tidal_favorites_async()
            return

        self._update_count_label(len(sorted_tracks))

        if not sorted_tracks:
            if self._all_favorites:
                self._empty_title.set_label(
                    f"No {self._active_filter} collection items"
                )
                self._empty_subtitle.set_label(
                    "Try a different source filter"
                )
            else:
                self._empty_title.set_label("No collection items yet")
                self._empty_subtitle.set_label(
                    "Heart any track to add it here"
                )
            self._content_stack.set_visible_child_name("empty")
            return

        # Dispatch by view mode
        if self._view_mode == ViewMode.GRID:
            self._refresh_tracks_grid(sorted_tracks)
        elif self._view_mode == ViewMode.COMPACT_LIST:
            self._refresh_tracks_compact(sorted_tracks)
        else:
            self._refresh_tracks_standard(sorted_tracks)

    def _refresh_tracks_standard(self, sorted_tracks: list[dict]) -> None:
        """Render favorites in standard LIST mode (batched)."""
        self._content_stack.set_visible_child_name("tracks")
        self._batch_generation += 1
        gen = self._batch_generation
        offset = 0
        unfavorite_cb = self._on_unfavorite if self._db is not None else None

        def _add_batch() -> bool:
            nonlocal offset
            if gen != self._batch_generation:
                return False
            end = min(offset + _BATCH_SIZE, len(sorted_tracks))
            for i in range(offset, end):
                track = sorted_tracks[i]
                track_obj = self._resolve_track_obj(track)
                row = _make_favorite_row(
                    track,
                    on_unfavorite=unfavorite_cb,
                    track_obj=track_obj,
                    on_artist_clicked=self._on_artist_clicked,
                    on_album_clicked=self._on_album_clicked,
                    on_play_clicked=self._on_play_track,
                )
                self._attach_context_gesture(row, track_obj)
                self._attach_drag_source_to_row(row, track_obj)
                self._favorites_list.append(row)
                self._load_art_for_track_row(row, track_obj)
            offset = end
            return offset < len(sorted_tracks)

        GLib.idle_add(_add_batch)

    def _load_art_for_track_row(
        self, row: Gtk.ListBoxRow, track
    ) -> None:
        """Load album art asynchronously for a track list row."""
        art_service = self._album_art_service
        if art_service is None or track is None:
            return

        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        scale = row.get_scale_factor() or 1
        art_px = 48 * scale

        # Fast path: use cached texture immediately
        cached_texture = art_service.get_texture_for_track(track, art_px, art_px)
        if cached_texture is not None:
            art_image.set_from_paintable(cached_texture)
            art_image.set_visible(True)
            art_icon.set_visible(False)
            return

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            if getattr(row, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = art_service.get_or_create_texture(track, pixbuf, art_px, art_px)
                if texture is not None:
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)

        art_service.get_art_async(track, _on_art_loaded, width=art_px, height=art_px)

    def _refresh_tracks_compact(self, sorted_tracks: list[dict]) -> None:
        """Render favorites in COMPACT_LIST mode (batched)."""
        self._content_stack.set_visible_child_name("tracks")
        self._batch_generation += 1
        gen = self._batch_generation
        offset = 0
        unfavorite_cb = self._on_unfavorite if self._db is not None else None

        def _add_batch() -> bool:
            nonlocal offset
            if gen != self._batch_generation:
                return False
            end = min(offset + _BATCH_SIZE, len(sorted_tracks))
            for i in range(offset, end):
                track = sorted_tracks[i]
                track_obj = self._resolve_track_obj(track)
                heart_btn = _make_heart_button(track, unfavorite_cb)
                row = make_compact_track_row(
                    track,
                    index=i,
                    show_source_badge=True,
                    show_quality_badge=True,
                    on_artist_clicked=self._on_artist_clicked,
                    on_album_clicked=self._on_album_clicked,
                    extra_widgets_after=[heart_btn],
                )
                row._track_obj = track_obj  # type: ignore[attr-defined]
                self._attach_context_gesture(row, track_obj)
                self._attach_drag_source_to_row(row, track_obj)
                self._favorites_list.append(row)
            offset = end
            return offset < len(sorted_tracks)

        GLib.idle_add(_add_batch)

    def _refresh_tracks_grid(self, sorted_tracks: list[dict]) -> None:
        """Render favorites in GRID mode as individual track cards (batched)."""
        self._content_stack.set_visible_child_name("tracks-grid")
        self._batch_generation += 1
        gen = self._batch_generation
        offset = 0

        def _add_batch() -> bool:
            nonlocal offset
            if gen != self._batch_generation:
                return False
            end = min(offset + _BATCH_SIZE, len(sorted_tracks))
            for i in range(offset, end):
                t = sorted_tracks[i]
                title = t.get("title", "") or "Unknown"
                artist = t.get("artist", "") or "Unknown"
                source = str(t.get("source", "local"))
                card = _make_track_grid_card(
                    title, artist, source,
                    on_artist_clicked=self._on_artist_clicked,
                )

                track_obj = self._resolve_track_obj(t)
                if track_obj is not None:
                    card._track_obj = track_obj
                    self._attach_grid_context_gesture(card, [track_obj])
                    self._attach_grid_play_button(card, [track_obj])
                    self.load_album_art_for_card(card, track_obj)

                self._favorites_grid.append(card)
            offset = end
            return offset < len(sorted_tracks)

        GLib.idle_add(_add_batch)

    def _fetch_tidal_favorites_async(self) -> None:
        """Fetch Tidal favorites in a background thread and merge results."""
        self._tidal_fetch_generation = getattr(
            self, "_tidal_fetch_generation", 0
        ) + 1
        gen = self._tidal_fetch_generation

        def _worker():
            try:
                tidal_tracks = self._tidal_provider.get_favorites()
            except Exception:
                logger.warning(
                    "Failed to fetch Tidal favorites", exc_info=True
                )
                tidal_tracks = []

            def _apply():
                if gen != self._tidal_fetch_generation:
                    return False

                if not tidal_tracks:
                    self._refresh_current_view()
                    return False

                existing_source_ids = {
                    str(t.get("source_id", ""))
                    for t in self._all_favorites
                    if t.get("source") == "tidal"
                }

                new_entries: list[dict] = []
                for track in tidal_tracks:
                    sid = str(track.source_id)
                    if sid in existing_source_ids:
                        continue
                    entry = {
                        "title": track.title,
                        "artist": track.artist,
                        "album": track.album or "",
                        "source": track.source.value,
                        "quality": track.quality_label,
                        "duration": _format_duration(track.duration),
                        "date_added": track.added_at or "",
                        "track_id": track.id,
                        "source_id": str(track.source_id),
                    }
                    new_entries.append(entry)
                    if track.id is not None:
                        self._track_objects[track.id] = track
                    if track.source_id:
                        self._track_objects[f"sid:{track.source_id}"] = track

                self._all_favorites.extend(new_entries)
                self._refresh_current_view()
                return False

            GLib.idle_add(_apply)

        threading.Thread(target=_worker, daemon=True).start()

    def _fetch_tidal_collection_async(self) -> None:
        """Fetch Tidal saved albums, followed artists, and playlists in a background thread."""
        if (
            self._tidal_provider is None
            or not self._tidal_provider.is_logged_in
        ):
            return

        self._tidal_collection_generation += 1
        gen = self._tidal_collection_generation
        provider = self._tidal_provider

        def _worker():
            try:
                albums = provider.get_favorite_albums()
            except Exception:
                logger.debug("Async Tidal album fetch failed", exc_info=True)
                albums = []
            try:
                artists = provider.get_followed_artists()
            except Exception:
                logger.debug("Async Tidal artist fetch failed", exc_info=True)
                artists = []
            try:
                user_playlists = provider.get_user_playlists(limit=50)
            except Exception:
                logger.debug("Async Tidal user playlists fetch failed", exc_info=True)
                user_playlists = []
            try:
                saved_playlists = provider.get_saved_playlists()
            except Exception:
                logger.debug("Async Tidal saved playlists fetch failed", exc_info=True)
                saved_playlists = []

            # Merge user-created and saved playlists, dedup by tidal_id
            seen_ids: set[str] = set()
            merged_playlists: list[dict] = []
            for pl in user_playlists:
                tid = pl.get("tidal_id", "")
                if tid and tid not in seen_ids:
                    seen_ids.add(tid)
                    pl["is_user_created"] = True
                    merged_playlists.append(pl)
            for pl in saved_playlists:
                tid = pl.get("tidal_id", "")
                if tid and tid not in seen_ids:
                    seen_ids.add(tid)
                    pl["is_user_created"] = False
                    merged_playlists.append(pl)

            def _apply():
                if gen != self._tidal_collection_generation:
                    return False
                self._cached_tidal_albums = albums
                self._cached_tidal_artists = artists
                self._cached_tidal_playlists = merged_playlists
                self._rebuild_merged_collections()
                # Re-render if on albums, artists, or playlists tab
                if self._active_view in ("albums", "artists", "playlists"):
                    self._refresh_current_view()
                return False

            GLib.idle_add(_apply)

        threading.Thread(target=_worker, daemon=True).start()

    # ---- Signal handlers ----

    def _on_view_mode_changed(self, mode: ViewMode) -> None:
        """Handle view mode toggle (list/compact/grid) for all tabs."""
        self._view_mode = mode
        if self._db is not None:
            key = f"view_mode_collection_{self._active_view}"
            try:
                self._db.set_setting(key, mode.value)
            except Exception:
                logger.warning(
                    "Failed to persist view mode", exc_info=True
                )
        self._refresh_current_view()

    def _restore_tab_view_mode(self) -> None:
        """Restore the persisted view mode for the current tab."""
        default = ViewMode.GRID if self._active_view == "albums" else ViewMode.LIST
        mode = default
        if self._db is not None:
            saved = self._db.get_setting(f"view_mode_collection_{self._active_view}")
            if saved:
                for m in ViewMode:
                    if m.value == saved:
                        mode = m
                        break
        self._view_mode = mode
        set_active_mode(self._view_mode_toggle, mode)

    def _on_view_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior for view toggle buttons."""
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

        view_name = getattr(toggled_btn, "_view_name", "tracks")
        self._active_view = view_name
        self._update_sort_options()
        # Hide view mode toggle on playlists (list-only)
        self._view_mode_toggle.set_visible(view_name != "playlists")
        # Restore per-tab view mode
        if view_name != "playlists":
            self._restore_tab_view_mode()
        # Persist to DB
        self._persist_state()
        # Scroll to top when switching views
        if hasattr(self, "_scrolled"):
            vadj = self._scrolled.get_vadjustment()
            if vadj is not None:
                vadj.set_value(0)
        self._refresh_current_view()

    def _update_sort_options(self) -> None:
        """Update the sort dropdown options based on the active view."""
        if self._active_view == "albums":
            options = _SORT_OPTIONS_ALBUMS
        elif self._active_view == "artists":
            options = _SORT_OPTIONS_ARTISTS
        elif self._active_view == "playlists":
            options = _SORT_OPTIONS_PLAYLISTS
        else:
            options = _SORT_OPTIONS_TRACKS

        self._sort_model = Gtk.StringList.new(options)
        self._sort_dropdown.set_model(self._sort_model)
        self._sort_dropdown.set_selected(0)
        self._active_sort = options[0]

    def _on_filter_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior: only one filter active at a time."""
        if not toggled_btn.get_active():
            any_active = any(b.get_active() for b in self._filter_buttons)
            if not any_active:
                toggled_btn.set_active(True)
            return

        # Deactivate all other buttons
        for btn in self._filter_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)

        self._active_filter = toggled_btn.get_label() or "All"
        # Persist to DB
        self._persist_state()
        # Reset fetch latch and invalidate in-flight async fetches
        self._tidal_fetch_attempted = False
        self._tidal_fetch_generation = getattr(
            self, "_tidal_fetch_generation", 0
        ) + 1
        # Scroll to top when filter changes
        if hasattr(self, "_scrolled"):
            vadj = self._scrolled.get_vadjustment()
            if vadj is not None:
                vadj.set_value(0)
        self._refresh_current_view()

    def _on_sort_changed(
        self, dropdown: Gtk.DropDown, _pspec: object
    ) -> None:
        """Handle sort dropdown selection changes."""
        idx = dropdown.get_selected()
        if self._active_view == "albums":
            options = _SORT_OPTIONS_ALBUMS
        elif self._active_view == "artists":
            options = _SORT_OPTIONS_ARTISTS
        elif self._active_view == "playlists":
            options = _SORT_OPTIONS_PLAYLISTS
        else:
            options = _SORT_OPTIONS_TRACKS

        if 0 <= idx < len(options):
            new_sort = options[idx]
            if new_sort == self._active_sort:
                self._sort_ascending = not self._sort_ascending
                self._update_sort_dir_icon()
            else:
                self._active_sort = new_sort
                self._sort_ascending = True
                self._update_sort_dir_icon()
            self._persist_state()
            self._refresh_current_view()

    def _on_sort_dir_clicked(self, _btn: Gtk.Button) -> None:
        """Toggle sort direction and refresh."""
        self._sort_ascending = not self._sort_ascending
        self._update_sort_dir_icon()
        self._persist_state()
        self._refresh_current_view()

    def _update_sort_dir_icon(self) -> None:
        """Update the sort direction button icon and tooltip."""
        if self._sort_ascending:
            self._sort_dir_btn.set_icon_name("view-sort-ascending-symbolic")
            self._sort_dir_btn.set_tooltip_text("Ascending")
        else:
            self._sort_dir_btn.set_icon_name("view-sort-descending-symbolic")
            self._sort_dir_btn.set_tooltip_text("Descending")

    # ---- State persistence ----

    def _persist_state(self) -> None:
        """Save filter, view tab, sort, and direction to the database."""
        if self._db is None:
            return
        try:
            self._db.set_setting("collection_filter", self._active_filter)
            self._db.set_setting("collection_view_tab", self._active_view)
            self._db.set_setting(
                f"collection_sort_{self._active_view}", self._active_sort
            )
            self._db.set_setting(
                f"collection_sort_dir_{self._active_view}",
                "asc" if self._sort_ascending else "desc",
            )
        except Exception:
            pass

    def _restore_persisted_state(self) -> None:
        """Restore filter, view tab, and sort from the database."""
        if self._db is None:
            return
        try:
            # Restore view tab
            saved_tab = self._db.get_setting("collection_view_tab")
            if saved_tab and saved_tab in ("albums", "artists", "tracks", "playlists"):
                self._active_view = saved_tab
                for btn in self._view_buttons:
                    view_name = getattr(btn, "_view_name", "")
                    btn.handler_block_by_func(self._on_view_toggled)
                    btn.set_active(view_name == saved_tab)
                    btn.handler_unblock_by_func(self._on_view_toggled)
                self._view_mode_toggle.set_visible(saved_tab != "playlists")
                self._update_sort_options()

            # Restore sort option for the active view
            saved_sort = self._db.get_setting(
                f"collection_sort_{self._active_view}"
            )
            if saved_sort:
                # Migrate old sort names
                if saved_sort in ("Name (A-Z)", "Name (Z-A)"):
                    saved_sort = "Name"
                if self._active_view == "albums":
                    options = _SORT_OPTIONS_ALBUMS
                elif self._active_view == "artists":
                    options = _SORT_OPTIONS_ARTISTS
                elif self._active_view == "playlists":
                    options = _SORT_OPTIONS_PLAYLISTS
                else:
                    options = _SORT_OPTIONS_TRACKS
                if saved_sort in options:
                    self._active_sort = saved_sort
                    idx = options.index(saved_sort)
                    self._sort_dropdown.set_selected(idx)

            # Restore sort direction
            saved_dir = self._db.get_setting(
                f"collection_sort_dir_{self._active_view}"
            )
            if saved_dir in ("asc", "desc"):
                self._sort_ascending = saved_dir == "asc"
                self._update_sort_dir_icon()

            # Restore filter pill
            saved_filter = self._db.get_setting("collection_filter")
            if saved_filter and saved_filter in ("All", "Tidal", "Local"):
                self._active_filter = saved_filter
                for btn in self._filter_buttons:
                    label = btn.get_label()
                    btn.handler_block_by_func(self._on_filter_toggled)
                    btn.set_active(label == saved_filter)
                    btn.handler_unblock_by_func(self._on_filter_toggled)
        except Exception:
            pass
