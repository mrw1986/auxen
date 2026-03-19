"""Library browsing view for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable, Optional

import gi

gi.require_version("Gdk", "4.0")
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, GObject, Gtk, Pango

from auxen.models import Source
from auxen.views.context_menu import (
    AlbumContextMenu,
    ArtistContextMenu,
    TrackContextMenu,
)
from auxen.views.view_mode import ViewMode, make_view_mode_toggle, set_active_mode
from auxen.views.widgets import (
    DragScrollHelper,
    make_compact_track_row,
    make_source_badge,
    make_standard_track_row,
    make_tidal_source_badge,
)

logger = logging.getLogger(__name__)

# Sort options for each view mode
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
    "Name",
    "Artist",
]


def _make_source_badge(source: str) -> Gtk.Widget:
    """Create a small pill badge indicating the track source."""
    return make_source_badge(source)


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
    """Build a single album card for the library grid."""
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
    overlay.add_overlay(hover_overlay)

    # -- Play button (centered, revealed on hover) --
    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
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
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL, spacing=12,
    )
    row_box.add_css_class("library-album-list-row")

    # Album art with play overlay
    art_overlay = Gtk.Overlay()
    art_overlay.set_size_request(40, 40)
    art_overlay.set_valign(Gtk.Align.CENTER)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(24)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)

    art_image = Gtk.Image()
    art_image.set_pixel_size(40)
    art_image.set_size_request(40, 40)
    art_image.set_halign(Gtk.Align.CENTER)
    art_image.set_valign(Gtk.Align.CENTER)
    art_image.add_css_class("album-row-art-image")
    art_image.set_visible(False)

    art_base = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    art_base.set_size_request(40, 40)
    art_base.set_halign(Gtk.Align.CENTER)
    art_base.set_valign(Gtk.Align.CENTER)
    art_base.append(art_icon)
    art_base.append(art_image)
    art_overlay.set_child(art_base)

    play_btn = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
    play_btn.add_css_class("album-row-play-overlay")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {album}")
    play_btn.set_opacity(0)  # hidden until hover
    art_overlay.add_overlay(play_btn)

    row_box.append(art_overlay)

    # Hover controller: show/hide play button
    motion = Gtk.EventControllerMotion.new()

    def _on_enter(*_args):
        play_btn.set_opacity(1)

    def _on_leave(*_args):
        play_btn.set_opacity(0)

    motion.connect("enter", _on_enter)
    motion.connect("leave", _on_leave)
    row_box.add_controller(motion)

    # Text column
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

    # Source badge
    badge = _make_source_badge(source)
    row_box.append(badge)

    # Track count
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
    """Build a compact row for an album (number + title + artist)."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL, spacing=8,
    )
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

    badge = _make_source_badge(source)
    row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)
    row.set_activatable(True)
    row._album_title = album
    row._album_artist = artist
    row._play_btn = play_btn
    return row


def _make_artist_card(artist: str, track_count: int, sources: list[str]) -> Gtk.FlowBoxChild:
    """Build an artist card for grid view."""
    card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    card.add_css_class("album-card")

    # Artist image with overlay badge
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

    # Source badge
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
        break  # Only show first source badge

    # Hover overlay
    hover_overlay = Gtk.Box()
    hover_overlay.add_css_class("album-card-hover-overlay")
    hover_overlay.set_halign(Gtk.Align.FILL)
    hover_overlay.set_valign(Gtk.Align.FILL)
    overlay.add_overlay(hover_overlay)

    # Play button
    play_btn = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {artist}")
    overlay.add_overlay(play_btn)

    card.append(overlay)

    # Artist name
    name_label = Gtk.Label(label=artist)
    name_label.set_xalign(0)
    name_label.set_ellipsize(Pango.EllipsizeMode.END)
    name_label.set_max_width_chars(18)
    name_label.add_css_class("body")
    name_label.set_margin_start(6)
    name_label.set_margin_end(6)
    card.append(name_label)

    # Track count
    if track_count > 0:
        count_label = Gtk.Label(
            label=f"{track_count} track{'s' if track_count != 1 else ''}"
        )
        count_label.set_xalign(0)
        count_label.add_css_class("caption")
        count_label.add_css_class("dim-label")
        count_label.set_margin_start(6)
        count_label.set_margin_end(6)
        card.append(count_label)

    child = Gtk.FlowBoxChild()
    child.set_child(card)
    child._artist_name = artist
    child._art_icon = art_icon
    child._art_image = art_image
    child._play_btn = play_btn
    return child


def _make_artist_compact_row(
    artist: str, track_count: int, sources: list[str], index: int = 0,
) -> Gtk.ListBoxRow:
    """Build a compact row for an artist."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL, spacing=8,
    )
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
        badge = _make_source_badge(src)
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
    overlay.add_overlay(hover_overlay)

    play_btn = Gtk.Button.new_from_icon_name("media-playback-start-symbolic")
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
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
    child.set_child(card)
    child._art_icon = art_icon
    child._art_image = art_image
    child._play_btn = play_btn
    return child


def _make_track_row(
    track,
    extra_widgets_after: list[Gtk.Widget] | None = None,
    on_artist_clicked=None,
    on_album_clicked=None,
    on_play_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a single row for the tracks list using the shared widget."""
    row = make_standard_track_row(
        track,
        show_art=True,
        show_source_badge=True,
        show_quality_badge=True,
        show_duration=True,
        art_size=48,
        css_class="library-track-row",
        on_artist_clicked=on_artist_clicked,
        on_album_clicked=on_album_clicked,
        on_play_clicked=on_play_clicked,
        extra_widgets_after=extra_widgets_after,
    )
    row.set_activatable(True)
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
        self._active_view: str = "albums"
        self._active_sort: str = "Recently Added"
        self._sort_ascending: bool = True
        self._view_mode: ViewMode = ViewMode.LIST

        # Album art service for loading cover images
        self._album_art_service = None
        # Artist image service for loading artist photos
        self._artist_image_service = None

        # Callbacks
        self._on_album_clicked: Optional[
            Callable[[str, str], None]
        ] = None
        self._on_play_track: Optional[Callable] = None
        self._on_artist_clicked: Optional[
            Callable[[str], None]
        ] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None

        # Album context menu callbacks
        self._album_context_callbacks: Optional[dict] = None
        self._get_album_playlists: Optional[Callable] = None

        # Artist context menu callbacks
        self._artist_context_callbacks: Optional[dict] = None

        # Keep a reference to the last-shown context menu so the Python
        # wrapper (and its action group callbacks) is not garbage-collected
        # while the popover is still visible.
        self._current_menu = None

        # Favorite toggle callback (called with track object)
        self.on_favorite_toggled: Optional[Callable] = None

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
        header_section.set_margin_start(16)
        header_section.set_margin_end(16)

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
        lib_icon.add_css_class("collection-header-icon")
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

        # 2. View mode toggle row
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

        header_section.append(view_box)

        # 3. Sort + view mode row
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

        self._sort_model = Gtk.StringList.new(_SORT_OPTIONS_ALBUMS)
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
        self._view_mode_toggle.set_visible(True)
        sort_row.append(self._view_mode_toggle)

        header_section.append(sort_row)

        self.append(header_section)

        # ---- Scrollable content area ----
        self._scrolled = Gtk.ScrolledWindow()
        self._scrolled.set_policy(
            Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC
        )
        self._scrolled.set_vexpand(True)
        self._drag_scroll = DragScrollHelper(self._scrolled)

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
        albums_container.set_margin_start(16)
        albums_container.set_margin_end(16)
        albums_container.set_margin_bottom(32)

        self._album_grid = Gtk.FlowBox()
        self._album_grid.set_homogeneous(True)
        self._album_grid.set_min_children_per_line(1)
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

        # Tracks view (ListBox)
        tracks_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        tracks_container.set_margin_top(16)
        tracks_container.set_margin_start(16)
        tracks_container.set_margin_end(16)
        tracks_container.set_margin_bottom(32)

        self._track_list = Gtk.ListBox()
        self._track_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._track_list.add_css_class("boxed-list")
        self._track_list.connect(
            "row-activated", self._on_track_row_activated
        )
        tracks_container.append(self._track_list)

        self._content_stack.add_named(tracks_container, "tracks")

        # Tracks grid view (FlowBox for grid mode)
        tracks_grid_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )
        tracks_grid_container.set_margin_top(16)
        tracks_grid_container.set_margin_start(16)
        tracks_grid_container.set_margin_end(16)
        tracks_grid_container.set_margin_bottom(32)

        self._tracks_album_grid = Gtk.FlowBox()
        self._tracks_album_grid.set_homogeneous(True)
        self._tracks_album_grid.set_min_children_per_line(1)
        self._tracks_album_grid.set_max_children_per_line(6)
        self._tracks_album_grid.set_column_spacing(16)
        self._tracks_album_grid.set_row_spacing(16)
        self._tracks_album_grid.set_selection_mode(
            Gtk.SelectionMode.SINGLE
        )
        self._tracks_album_grid.connect(
            "child-activated", self._on_track_grid_card_activated
        )
        tracks_grid_container.append(self._tracks_album_grid)

        self._content_stack.add_named(
            tracks_grid_container, "tracks-grid"
        )

        # Albums list view (ListBox for list/compact modes)
        albums_list_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL, spacing=0,
        )
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

        # Artists grid view (FlowBox for grid mode)
        artists_grid_container = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL, spacing=0,
        )
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
        self._artist_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._artist_grid.connect(
            "child-activated", self._on_artist_card_activated
        )
        artists_grid_container.append(self._artist_grid)
        self._content_stack.add_named(artists_grid_container, "artists-grid")

        # Empty state
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

        # Activate "Albums" view by default
        self._view_buttons[0].set_active(True)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_album_art_service(self, art_service) -> None:
        """Set the AlbumArtService instance for loading album art."""
        self._album_art_service = art_service

    def set_artist_image_service(self, service) -> None:
        """Set the ArtistImageService for loading artist photos."""
        self._artist_image_service = service

    def load_album_art_for_card(
        self, child: Gtk.FlowBoxChild, track
    ) -> None:
        """Load album art asynchronously for an album card.

        *child* is a FlowBoxChild created by ``_make_album_card`` and
        *track* is a Track object whose art should be loaded.
        """
        art_service = self._album_art_service
        if art_service is None or track is None:
            return

        art_icon = getattr(child, "_art_icon", None)
        art_image = getattr(child, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        request_token = object()
        child._art_request_token = request_token  # type: ignore[attr-defined]

        # Fetch at logical_size * scale_factor for crisp rendering on HiDPI.
        scale = child.get_scale_factor() or 1
        art_px = 160 * scale

        # Fast path: use cached texture immediately (avoids flicker on sort change)
        cached_texture = art_service.get_texture_for_track(track, art_px, art_px)
        if cached_texture is not None:
            art_image.set_from_paintable(cached_texture)
            art_image.set_visible(True)
            art_icon.set_visible(False)
            return

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            # Guard: skip if the card was recycled during async fetch
            if getattr(child, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = art_service.get_or_create_texture(track, pixbuf, art_px, art_px)
                if texture is not None:
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)

        art_service.get_art_async(track, _on_art_loaded, width=art_px, height=art_px)

    def set_content_width(self, width: int) -> None:
        """Adjust header layout based on available content width."""
        self._sort_label.set_visible(width >= 500)

    def set_database(self, db) -> None:
        """Wire the library view to a database."""
        self._db = db
        # Restore persisted view tab and sort
        self._restore_persisted_state()
        # Restore per-tab view mode (after tab is restored)
        self._restore_tab_view_mode()
        self.refresh()

    def refresh(self) -> None:
        """Reload local-only data from the database and refresh the display."""
        if self._db is None:
            return
        try:
            self._all_albums = self._db.get_albums(source=Source.LOCAL)
            self._all_artists = self._db.get_artists(source=Source.LOCAL)
            self._all_tracks = self._db.get_tracks_by_source(Source.LOCAL)
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
        on_artist_clicked: Callable[[str], None] | None = None,
        on_play_album: Callable[[str, str], None] | None = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_album_clicked:
            Called with (album_name, artist) when an album card is clicked.
        on_play_track:
            Called with a Track object when a track is clicked.
        on_artist_clicked:
            Called with artist_name when an artist row is clicked.
        on_play_album:
            Called with (album_name, artist) when the play button on an
            album card is clicked.
        """
        self._on_album_clicked = on_album_clicked
        self._on_play_track = on_play_track
        self._on_artist_clicked = on_artist_clicked
        self._on_play_album = on_play_album

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
        """Highlight the currently playing track in the track list/grid."""
        playing_sid = getattr(track, "source_id", None) if track else None
        playing_key = (
            (getattr(track, "title", ""), getattr(track, "artist", ""))
            if track
            else None
        )

        def _match_track(td):
            if td is None or track is None:
                return False
            td_sid = getattr(td, "source_id", None)
            if playing_sid and td_sid and playing_sid == td_sid:
                return True
            if playing_key and (
                getattr(td, "title", ""), getattr(td, "artist", "")
            ) == playing_key:
                return True
            return False

        # Highlight in list view
        if hasattr(self, "_track_list"):
            row = self._track_list.get_first_child()
            while row is not None:
                td = getattr(row, "_track_data", None)
                if _match_track(td):
                    row.add_css_class("now-playing-row")
                else:
                    row.remove_css_class("now-playing-row")
                row = row.get_next_sibling()

        # Highlight in grid view
        if hasattr(self, "_tracks_album_grid"):
            child = self._tracks_album_grid.get_first_child()
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

    def set_artist_context_callbacks(
        self,
        callbacks: dict,
    ) -> None:
        """Set callback functions for the artist right-click context menu."""
        self._artist_context_callbacks = callbacks

    # ------------------------------------------------------------------
    # Album play button + drag source helpers
    # ------------------------------------------------------------------

    def _attach_play_button(self, child: Gtk.FlowBoxChild) -> None:
        """Wire the play button on an album card to play the album."""
        play_btn = getattr(child, "_play_btn", None)
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if play_btn is None or album_title is None or album_artist is None:
            return

        def _on_play_clicked(
            _btn, a=album_title, ar=album_artist
        ):
            cb = getattr(self, "_on_play_album", None)
            if cb is not None:
                cb(a, ar)

        play_btn.connect("clicked", _on_play_clicked)

    def _attach_album_list_play_button(self, row) -> None:
        """Wire the play button on a list/compact album row."""
        play_btn = getattr(row, "_play_btn", None)
        album_title = getattr(row, "_album_title", None)
        album_artist = getattr(row, "_album_artist", None)
        if play_btn is None or album_title is None or album_artist is None:
            return

        def _on_play_clicked(
            _btn, a=album_title, ar=album_artist
        ):
            cb = getattr(self, "_on_play_album", None)
            if cb is not None:
                cb(a, ar)

        play_btn.connect("clicked", _on_play_clicked)

    def _attach_drag_source_to_row(
        self, row: Gtk.ListBoxRow, track
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

    def _attach_drag_source_to_album_card(
        self, child: Gtk.FlowBoxChild
    ) -> None:
        """Attach a DragSource to an album card for drag-to-playlist."""
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if album_title is None or album_artist is None:
            return

        drag_source = Gtk.DragSource.new()
        drag_source.set_actions(Gdk.DragAction.COPY)

        def _on_prepare(
            _src, _x, _y, a=album_title, ar=album_artist
        ):
            if self._db is None:
                return None
            try:
                tracks = self._db.get_tracks_by_album(a, ar)
                if not tracks:
                    return None
                ids_str = ",".join(
                    str(t.id) for t in tracks if t.id is not None
                )
                if not ids_str:
                    return None
                value = GObject.Value(GObject.TYPE_STRING, ids_str)
                return Gdk.ContentProvider.new_for_value(value)
            except Exception:
                logger.warning(
                    "Failed to get album tracks for drag",
                    exc_info=True,
                )
                return None

        drag_source.connect("prepare", _on_prepare)
        child.add_controller(drag_source)

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

    # ------------------------------------------------------------------
    # Album context menu helpers
    # ------------------------------------------------------------------

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

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(
            g, n_press, x, y,
            a=album_title, ar=album_artist
        ):
            if n_press != 1:
                return
            self._show_album_context_menu(child, x, y, a, ar)
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

        album_data = {
            "album": album_name,
            "artist": artist,
        }

        self._current_menu = AlbumContextMenu(
            album_data=album_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    # ------------------------------------------------------------------
    # Artist context menu helpers
    # ------------------------------------------------------------------

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
        self._persist_view_tab()
        self._update_sort_options()
        # Show view mode toggle on all tabs
        self._view_mode_toggle.set_visible(True)
        # Restore per-tab view mode
        self._restore_tab_view_mode()
        self._refresh_current_view()

    def _restore_tab_view_mode(self) -> None:
        """Restore the persisted view mode for the current tab."""
        default = ViewMode.GRID if self._active_view == "albums" else ViewMode.LIST
        mode = default
        if self._db is not None:
            saved = self._db.get_setting(f"view_mode_library_{self._active_view}")
            if saved:
                for m in ViewMode:
                    if m.value == saved:
                        mode = m
                        break
        self._view_mode = mode
        set_active_mode(self._view_mode_toggle, mode)

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

    def _persist_view_tab(self) -> None:
        """Save the active view tab to the database."""
        if self._db is not None:
            try:
                self._db.set_setting("library_view_tab", self._active_view)
            except Exception:
                pass

    def _persist_sort(self) -> None:
        """Save the active sort and direction for the current view tab."""
        if self._db is not None:
            try:
                key = f"library_sort_{self._active_view}"
                self._db.set_setting(key, self._active_sort)
                self._db.set_setting(
                    f"library_sort_dir_{self._active_view}",
                    "asc" if self._sort_ascending else "desc",
                )
            except Exception:
                pass

    def _restore_persisted_state(self) -> None:
        """Restore view tab and sort from the database."""
        if self._db is None:
            return
        try:
            saved_tab = self._db.get_setting("library_view_tab")
            if saved_tab and saved_tab in ("albums", "artists", "tracks"):
                self._active_view = saved_tab
                for btn in self._view_buttons:
                    view_name = getattr(btn, "_view_name", "")
                    btn.handler_block_by_func(self._on_view_toggled)
                    btn.set_active(view_name == saved_tab)
                    btn.handler_unblock_by_func(self._on_view_toggled)
                self._view_mode_toggle.set_visible(True)
                self._update_sort_options()

            saved_sort = self._db.get_setting(f"library_sort_{self._active_view}")
            if saved_sort:
                # Migrate old sort names
                if saved_sort in ("Name (A-Z)", "Name (Z-A)"):
                    saved_sort = "Name"
                # Find the sort option in the current dropdown
                if self._active_view == "albums":
                    options = _SORT_OPTIONS_ALBUMS
                elif self._active_view == "artists":
                    options = _SORT_OPTIONS_ARTISTS
                else:
                    options = _SORT_OPTIONS_TRACKS
                if saved_sort in options:
                    self._active_sort = saved_sort
                    idx = options.index(saved_sort)
                    self._sort_dropdown.set_selected(idx)

            saved_dir = self._db.get_setting(
                f"library_sort_dir_{self._active_view}"
            )
            if saved_dir in ("asc", "desc"):
                self._sort_ascending = saved_dir == "asc"
                self._update_sort_dir_icon()
        except Exception:
            pass

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
            new_sort = options[idx]
            if new_sort == self._active_sort:
                # Same sort selected again — toggle direction
                self._sort_ascending = not self._sort_ascending
                self._update_sort_dir_icon()
            else:
                self._active_sort = new_sort
                self._sort_ascending = True
                self._update_sort_dir_icon()
            self._persist_sort()
            self._refresh_current_view()

    def _on_sort_dir_clicked(self, _btn: Gtk.Button) -> None:
        """Toggle sort direction and refresh."""
        self._sort_ascending = not self._sort_ascending
        self._update_sort_dir_icon()
        self._persist_sort()
        self._refresh_current_view()

    def _update_sort_dir_icon(self) -> None:
        """Update the sort direction button icon and tooltip."""
        if self._sort_ascending:
            self._sort_dir_btn.set_icon_name("view-sort-ascending-symbolic")
            self._sort_dir_btn.set_tooltip_text("Ascending")
        else:
            self._sort_dir_btn.set_icon_name("view-sort-descending-symbolic")
            self._sort_dir_btn.set_tooltip_text("Descending")

    # ------------------------------------------------------------------
    # Data filtering and sorting
    # ------------------------------------------------------------------

    def _get_filtered_albums(self) -> list[dict]:
        """Return all local albums."""
        return list(self._all_albums)

    def _get_sorted_albums(
        self, albums: list[dict]
    ) -> list[dict]:
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
        # Recently Added: ascending = oldest first, descending = newest first
        if reverse:
            return albums
        return list(reversed(albums))

    def _get_filtered_artists(self) -> list[dict]:
        """Return all local artists."""
        return list(self._all_artists)

    def _get_sorted_artists(
        self, artists: list[dict]
    ) -> list[dict]:
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
                reverse=not reverse,  # newest first when ascending
            )
        # Default
        return sorted(
            artists,
            key=lambda a: (a["artist"] or "").lower(),
            reverse=reverse,
        )

    def _get_filtered_tracks(self) -> list:
        """Return all local tracks."""
        return list(self._all_tracks)

    def _get_sorted_tracks(self, tracks: list) -> list:
        """Sort tracks by the active sort criterion and direction."""
        reverse = not self._sort_ascending
        if self._active_sort in ("Name", "Name (A-Z)", "Name (Z-A)"):
            return sorted(
                tracks,
                key=lambda t: (t.title or "").lower(),
                reverse=reverse,
            )
        if self._active_sort == "Artist":
            return sorted(
                tracks,
                key=lambda t: (t.artist or "").lower(),
                reverse=reverse,
            )
        # Recently Added: ascending = oldest first, descending = newest first
        if reverse:
            return tracks
        return list(reversed(tracks))

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

    def _find_album_track(self, album: str, artist: str):
        """Return the first track matching album+artist from the cache, or None."""
        for track in self._all_tracks:
            if getattr(track, "album", None) == album and getattr(track, "artist", None) == artist:
                return track
        return None

    def _refresh_albums(self) -> None:
        """Rebuild the albums display based on current view mode."""
        self._clear_flow_box(self._album_grid)
        self._clear_list_box(self._album_list)

        filtered = self._get_filtered_albums()
        sorted_albums = self._get_sorted_albums(filtered)

        if not sorted_albums:
            self._content_stack.set_visible_child_name("empty")
            return

        if self._view_mode == ViewMode.GRID:
            for album_data in sorted_albums:
                card = _make_album_card(
                    album=album_data["album"],
                    artist=album_data["artist"],
                    source=album_data["source"],
                    on_artist_clicked=self._on_artist_clicked,
                )
                self._attach_album_context_gesture(card)
                self._attach_play_button(card)
                self._attach_drag_source_to_album_card(card)
                self._album_grid.append(card)
                representative_track = self._find_album_track(
                    album_data["album"], album_data["artist"]
                )
                self.load_album_art_for_card(card, representative_track)
            self._content_stack.set_visible_child_name("albums")
        elif self._view_mode == ViewMode.COMPACT_LIST:
            for i, album_data in enumerate(sorted_albums):
                row = _make_album_compact_row(
                    album=album_data["album"],
                    artist=album_data["artist"],
                    source=album_data["source"],
                    index=i,
                    on_artist_clicked=self._on_artist_clicked,
                )
                self._attach_album_context_gesture(row)
                self._attach_album_list_play_button(row)
                self._album_list.append(row)
            self._content_stack.set_visible_child_name("albums-list")
        else:  # LIST
            for album_data in sorted_albums:
                row = _make_album_list_row(
                    album=album_data["album"],
                    artist=album_data["artist"],
                    source=album_data["source"],
                    track_count=album_data.get("track_count", 0),
                    on_artist_clicked=self._on_artist_clicked,
                )
                self._attach_album_context_gesture(row)
                self._attach_album_list_play_button(row)
                self._album_list.append(row)
                representative_track = self._find_album_track(
                    album_data["album"], album_data["artist"]
                )
                self._load_art_for_album_row(row, representative_track)
            self._content_stack.set_visible_child_name("albums-list")

    def _refresh_artists(self) -> None:
        """Rebuild the artists display based on current view mode."""
        self._clear_list_box(self._artist_list)
        self._clear_flow_box(self._artist_grid)

        filtered = self._get_filtered_artists()
        sorted_artists = self._get_sorted_artists(filtered)

        if not sorted_artists:
            self._content_stack.set_visible_child_name("empty")
            return

        if self._view_mode == ViewMode.GRID:
            cards = []
            for artist_data in sorted_artists:
                card = _make_artist_card(
                    artist=artist_data["artist"],
                    track_count=artist_data["track_count"],
                    sources=artist_data["sources"],
                )
                self._attach_artist_context_gesture(card)
                self._attach_artist_play_button(card)
                self._artist_grid.append(card)
                cards.append(card)
            self._content_stack.set_visible_child_name("artists-grid")
            if self._artist_image_service is not None:
                for card in cards:
                    self._load_artist_image_for_card(card)
        elif self._view_mode == ViewMode.COMPACT_LIST:
            for i, artist_data in enumerate(sorted_artists):
                row = _make_artist_compact_row(
                    artist=artist_data["artist"],
                    track_count=artist_data["track_count"],
                    sources=artist_data["sources"],
                    index=i,
                )
                self._attach_artist_context_gesture(row)
                self._attach_artist_play_button(row)
                self._artist_list.append(row)
            self._content_stack.set_visible_child_name("artists")
        else:  # LIST
            rows = []
            for artist_data in sorted_artists:
                row = _make_artist_row(
                    artist=artist_data["artist"],
                    track_count=artist_data["track_count"],
                    sources=artist_data["sources"],
                )
                self._attach_artist_context_gesture(row)
                self._attach_artist_play_button(row)
                self._artist_list.append(row)
                rows.append(row)
            self._content_stack.set_visible_child_name("artists")
            if self._artist_image_service is not None:
                for row in rows:
                    self._load_artist_image_for_row(row)

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

    def _load_art_for_album_row(self, row: Gtk.ListBoxRow, track) -> None:
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
        # Disable click target on grid cards (FlowBoxChild handles click)
        # but keep it enabled on list rows (button must be clickable)
        if isinstance(widget, Gtk.FlowBoxChild):
            play_btn.set_can_target(False)

    def _on_view_mode_changed(self, mode: ViewMode) -> None:
        """Handle view mode toggle (list/compact/grid) for all tabs."""
        self._view_mode = mode
        # Persist per-tab preference
        if self._db is not None:
            key = f"view_mode_library_{self._active_view}"
            try:
                self._db.set_setting(key, mode.value)
            except Exception:
                logger.warning(
                    "Failed to persist view mode", exc_info=True
                )
        self._refresh_current_view()

    def _refresh_tracks(self) -> None:
        """Rebuild the tracks list/grid based on the current view mode."""
        self._clear_list_box(self._track_list)
        self._clear_flow_box(self._tracks_album_grid)

        filtered = self._get_filtered_tracks()
        sorted_tracks = self._get_sorted_tracks(filtered)

        if not sorted_tracks:
            self._content_stack.set_visible_child_name("empty")
            return

        if self._view_mode == ViewMode.GRID:
            self._refresh_tracks_grid(sorted_tracks)
        elif self._view_mode == ViewMode.COMPACT_LIST:
            self._refresh_tracks_compact(sorted_tracks)
        else:
            self._refresh_tracks_list(sorted_tracks)

    def _make_heart_button(self, track) -> Gtk.ToggleButton:
        """Create a heart toggle button for a library track row."""
        heart_btn = Gtk.ToggleButton()
        heart_btn.set_icon_name("emblem-favorite-symbolic")
        heart_btn.add_css_class("flat")
        heart_btn.add_css_class("collection-heart-btn")
        heart_btn.set_valign(Gtk.Align.CENTER)

        # Query current favorite state from DB
        track_id = getattr(track, "id", None)
        is_fav = False
        if self._db is not None and track_id is not None:
            try:
                is_fav = self._db.is_favorite(track_id)
            except Exception:
                pass
        heart_btn.set_active(is_fav)
        heart_btn.set_tooltip_text(
            "Remove from collection" if is_fav else "Add to collection"
        )

        def _on_toggled(btn, trk=track):
            btn.set_tooltip_text(
                "Remove from collection" if btn.get_active() else "Add to collection"
            )
            if self.on_favorite_toggled is not None:
                self.on_favorite_toggled(trk)

        heart_btn.connect("toggled", _on_toggled)
        return heart_btn

    def _refresh_tracks_list(self, tracks: list) -> None:
        """Render tracks in full LIST mode."""
        for track in tracks:
            heart_btn = self._make_heart_button(track)
            row = _make_track_row(
                track,
                extra_widgets_after=[heart_btn],
                on_artist_clicked=self._on_artist_clicked,
                on_album_clicked=self._on_album_clicked,
                on_play_clicked=self._on_play_track,
            )
            self._attach_context_gesture(row, track)
            self._attach_drag_source_to_row(row, track)
            self._track_list.append(row)
            self._load_art_for_track_row(row, track)
        self._content_stack.set_visible_child_name("tracks")

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

    def _refresh_tracks_compact(self, tracks: list) -> None:
        """Render tracks in COMPACT_LIST mode."""
        for idx, track in enumerate(tracks):
            heart_btn = self._make_heart_button(track)
            row = make_compact_track_row(
                track,
                index=idx,
                show_source_badge=True,
                show_quality_badge=True,
                on_artist_clicked=self._on_artist_clicked,
                on_album_clicked=self._on_album_clicked,
                extra_widgets_after=[heart_btn],
            )
            row.set_activatable(True)
            self._attach_context_gesture(row, track)
            self._attach_drag_source_to_row(row, track)
            self._track_list.append(row)
        self._content_stack.set_visible_child_name("tracks")

    def _refresh_tracks_grid(self, tracks: list) -> None:
        """Render tracks in GRID mode with individual track cards."""
        for track in tracks:
            title = getattr(track, "title", "") or "Unknown"
            artist = getattr(track, "artist", "") or "Unknown"
            source = getattr(track, "source", None)
            source_str = source.value if hasattr(source, "value") else "local"

            card = _make_track_grid_card(
                title, artist, source_str,
                on_artist_clicked=self._on_artist_clicked,
            )
            card._track_obj = track
            self._attach_track_grid_play_button(card, track)
            self._attach_context_gesture(card, track)
            self._attach_drag_source_to_row(card, track)
            self._tracks_album_grid.append(card)
            self.load_album_art_for_card(card, track)

        self._content_stack.set_visible_child_name("tracks-grid")

    def _attach_track_grid_play_button(
        self, card: Gtk.FlowBoxChild, track
    ) -> None:
        """Wire the play overlay button on a track grid card."""
        play_btn = getattr(card, "_play_btn", None)
        if play_btn is None:
            return

        def _on_play(_btn, t=track):
            if self._on_play_track is not None:
                self._on_play_track(t)

        play_btn.connect("clicked", _on_play)

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_track_grid_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle a track grid card being clicked to play."""
        track = getattr(child, "_track_obj", None)
        if track is not None and self._on_play_track is not None:
            self._on_play_track(track)

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

    def _on_album_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle an album list row being clicked."""
        album_title = getattr(row, "_album_title", None)
        album_artist = getattr(row, "_album_artist", None)
        if (
            album_title is not None
            and album_artist is not None
            and self._on_album_clicked is not None
        ):
            self._on_album_clicked(album_title, album_artist)

    def _on_artist_card_activated(
        self, _flow_box: Gtk.FlowBox, child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle an artist card in grid mode being clicked."""
        artist_name = getattr(child, "_artist_name", None)
        if artist_name is not None and self._on_artist_clicked is not None:
            self._on_artist_clicked(artist_name)

    def _on_artist_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle an artist row being clicked.

        Navigates to the artist detail page if the callback is set,
        otherwise filters the library to show only that artist's tracks.
        """
        artist_name = getattr(row, "_artist_name", None)
        if artist_name is None:
            return

        # Navigate to artist detail page if callback is wired
        if self._on_artist_clicked is not None:
            self._on_artist_clicked(artist_name)
            return

        # Fallback: switch to tracks view filtered by this artist
        self._active_view = "tracks"
        # Update view toggle buttons
        for btn in self._view_buttons:
            view_name = getattr(btn, "_view_name", "")
            btn.handler_block_by_func(self._on_view_toggled)
            btn.set_active(view_name == "tracks")
            btn.handler_unblock_by_func(self._on_view_toggled)
        self._update_sort_options()
        # Filter tracks to this artist (all local)
        filtered = [
            t
            for t in self._all_tracks
            if t.artist == artist_name
        ]
        self._clear_list_box(self._track_list)
        if not filtered:
            self._content_stack.set_visible_child_name("empty")
            return
        for track in filtered:
            track_row = _make_track_row(
                track,
                on_artist_clicked=self._on_artist_clicked,
                on_album_clicked=self._on_album_clicked,
            )
            self._attach_context_gesture(track_row, track)
            self._attach_drag_source_to_row(track_row, track)
            self._track_list.append(track_row)
        self._content_stack.set_visible_child_name("tracks")

    def _on_track_row_activated(
        self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle a track row being clicked to play."""
        index = row.get_index()
        # Build the current visible track list from the active filter/sort
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
