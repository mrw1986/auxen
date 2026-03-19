"""Properties dialogs for artists and albums.

Displays detailed metadata and allows setting custom cover art
via a file picker.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gio, GLib, Gtk

if TYPE_CHECKING:
    from auxen.db import Database

logger = logging.getLogger(__name__)


def _format_duration(total_seconds: int) -> str:
    """Format seconds into a human-readable duration string."""
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours > 0:
        return f"{hours}h {minutes}m {seconds}s"
    if minutes > 0:
        return f"{minutes}m {seconds}s"
    return f"{seconds}s"


def _create_info_row(label: str, value: str) -> Gtk.Box:
    """Create a horizontal label-value row for the properties grid."""
    row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
    row.set_margin_start(4)
    row.set_margin_end(4)
    row.set_margin_top(2)
    row.set_margin_bottom(2)

    lbl = Gtk.Label(label=label)
    lbl.add_css_class("dim-label")
    lbl.set_halign(Gtk.Align.START)
    lbl.set_size_request(100, -1)
    row.append(lbl)

    val = Gtk.Label(label=value)
    val.set_halign(Gtk.Align.START)
    val.set_hexpand(True)
    val.set_wrap(True)
    val.set_selectable(True)
    row.append(val)

    return row


def _open_image_file_dialog(
    parent: Gtk.Widget,
    callback,
) -> None:
    """Open a file dialog for selecting an image file."""
    dialog = Gtk.FileDialog()
    dialog.set_title("Select Image")

    img_filter = Gtk.FileFilter()
    img_filter.set_name("Images")
    img_filter.add_mime_type("image/png")
    img_filter.add_mime_type("image/jpeg")
    img_filter.add_mime_type("image/webp")

    filters = Gio.ListStore.new(Gtk.FileFilter)
    filters.append(img_filter)
    dialog.set_filters(filters)
    dialog.set_default_filter(img_filter)

    dialog.open(parent, None, callback)


def show_artist_properties(
    parent_widget: Gtk.Widget,
    artist_name: str,
    db: Database,
    artist_image_service=None,
    on_art_changed: "Callable[[], None] | None" = None,
) -> None:
    """Show a properties dialog for an artist.

    Parameters
    ----------
    parent_widget:
        The widget to present the dialog relative to.
    artist_name:
        The name of the artist.
    db:
        The application database instance.
    artist_image_service:
        Optional artist image service for loading artist images.
    on_art_changed:
        Optional callback invoked when custom art is set or cleared,
        so the caller can refresh views.
    """
    # Gather data from DB
    albums = db.get_artist_albums(artist_name)
    tracks = db.get_artist_tracks(artist_name)

    album_count = len(albums)
    track_count = len(tracks)

    # Determine source
    sources = set()
    for t in tracks:
        sources.add(getattr(t, "source", "local"))
    if len(sources) > 1:
        source_str = "Local + Tidal"
    elif sources:
        src = sources.pop()
        source_str = str(src).replace("Source.", "").title()
    else:
        source_str = "Unknown"

    # Total duration
    total_dur = sum(getattr(t, "duration", 0) or 0 for t in tracks)

    # Check for custom art
    custom_art_key = f"custom_art:artist:{artist_name}"
    custom_art_path = db.get_setting(custom_art_key)

    # Build content
    content = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL, spacing=8
    )
    content.set_margin_top(8)
    content.set_margin_bottom(4)
    content.set_margin_start(8)
    content.set_margin_end(8)

    # Info rows
    content.append(_create_info_row("Artist", artist_name))
    content.append(_create_info_row("Albums", str(album_count)))
    content.append(_create_info_row("Tracks", str(track_count)))
    content.append(_create_info_row("Source", source_str))
    if total_dur > 0:
        content.append(
            _create_info_row("Total Duration", _format_duration(total_dur))
        )
    if custom_art_path:
        content.append(
            _create_info_row("Custom Image", custom_art_path)
        )

    # Separator before button
    content.append(Gtk.Separator())

    # Custom art button
    art_btn = Gtk.Button(label="Set Custom Image\u2026")
    art_btn.add_css_class("flat")
    art_btn.set_margin_top(4)

    art_status = Gtk.Label(label="")
    art_status.add_css_class("dim-label")
    art_status.set_visible(False)

    def _on_file_selected(_dialog, result):
        try:
            gfile = _dialog.open_finish(result)
            if gfile is not None:
                path = gfile.get_path()
                db.set_setting(custom_art_key, path)
                art_status.set_label(f"Custom image set: {path}")
                art_status.set_visible(True)
                logger.info(
                    "Custom artist image set for %s: %s",
                    artist_name,
                    path,
                )
                if on_art_changed is not None:
                    on_art_changed()
        except GLib.Error:
            # User cancelled the dialog
            pass

    def _on_art_btn_clicked(_btn):
        _open_image_file_dialog(parent_widget, _on_file_selected)

    art_btn.connect("clicked", _on_art_btn_clicked)
    content.append(art_btn)

    # Clear custom art button (only if one is set)
    if custom_art_path:
        clear_btn = Gtk.Button(label="Clear Custom Image")
        clear_btn.add_css_class("flat")
        clear_btn.add_css_class("destructive-action")

        def _on_clear_clicked(_btn):
            db.set_setting(custom_art_key, "")
            art_status.set_label("Custom image cleared")
            art_status.set_visible(True)
            if on_art_changed is not None:
                on_art_changed()

        clear_btn.connect("clicked", _on_clear_clicked)
        content.append(clear_btn)

    content.append(art_status)

    # Create dialog
    dialog = Adw.AlertDialog()
    dialog.set_heading(f"Artist Properties \u2014 {artist_name}")
    dialog.set_extra_child(content)
    dialog.add_response("close", "Close")
    dialog.set_default_response("close")
    dialog.set_close_response("close")
    dialog.present(parent_widget)


def show_album_properties(
    parent_widget: Gtk.Widget,
    album_name: str,
    artist_name: str,
    db: Database,
    on_art_changed: "Callable[[], None] | None" = None,
) -> None:
    """Show a properties dialog for an album.

    Parameters
    ----------
    parent_widget:
        The widget to present the dialog relative to.
    album_name:
        The name of the album.
    artist_name:
        The name of the album artist.
    db:
        The application database instance.
    on_art_changed:
        Optional callback invoked when custom art is set or cleared,
        so the caller can refresh views.
    """
    # Gather data from DB
    tracks = db.get_tracks_by_album(album_name, artist_name)
    track_count = len(tracks)

    # Determine source
    sources = set()
    for t in tracks:
        sources.add(getattr(t, "source", "local"))
    if len(sources) > 1:
        source_str = "Local + Tidal"
    elif sources:
        src = sources.pop()
        source_str = str(src).replace("Source.", "").title()
    else:
        source_str = "Unknown"

    # Year
    years = set()
    for t in tracks:
        y = getattr(t, "year", None)
        if y:
            years.add(y)
    year_str = ", ".join(str(y) for y in sorted(years)) if years else "Unknown"

    # Total duration
    total_dur = sum(getattr(t, "duration", 0) or 0 for t in tracks)

    # Quality info — collect unique bitrate/sample rate info
    qualities = set()
    for t in tracks:
        br = getattr(t, "bitrate", None)
        sr = getattr(t, "sample_rate", None)
        if br:
            qualities.add(f"{br} kbps")
        if sr:
            qualities.add(f"{sr} Hz")
    quality_str = ", ".join(sorted(qualities)) if qualities else "N/A"

    # Disc info
    disc_numbers = set()
    for t in tracks:
        dn = getattr(t, "disc_number", None)
        if dn:
            disc_numbers.add(dn)
    disc_count = len(disc_numbers)

    # Check for custom art
    custom_art_key = f"custom_art:album:{album_name}:{artist_name}"
    custom_art_path = db.get_setting(custom_art_key)

    # Build content
    content = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL, spacing=8
    )
    content.set_margin_top(8)
    content.set_margin_bottom(4)
    content.set_margin_start(8)
    content.set_margin_end(8)

    # Info rows
    content.append(_create_info_row("Album", album_name))
    content.append(_create_info_row("Artist", artist_name))
    content.append(_create_info_row("Year", year_str))
    content.append(_create_info_row("Tracks", str(track_count)))
    if disc_count > 1:
        content.append(_create_info_row("Discs", str(disc_count)))
    content.append(_create_info_row("Source", source_str))
    if total_dur > 0:
        content.append(
            _create_info_row("Duration", _format_duration(total_dur))
        )
    if quality_str != "N/A":
        content.append(_create_info_row("Quality", quality_str))
    if custom_art_path:
        content.append(
            _create_info_row("Custom Cover", custom_art_path)
        )

    # Separator before button
    content.append(Gtk.Separator())

    # Custom art button
    art_btn = Gtk.Button(label="Set Custom Cover Art\u2026")
    art_btn.add_css_class("flat")
    art_btn.set_margin_top(4)

    art_status = Gtk.Label(label="")
    art_status.add_css_class("dim-label")
    art_status.set_visible(False)

    def _on_file_selected(_dialog, result):
        try:
            gfile = _dialog.open_finish(result)
            if gfile is not None:
                path = gfile.get_path()
                db.set_setting(custom_art_key, path)
                art_status.set_label(f"Custom cover set: {path}")
                art_status.set_visible(True)
                logger.info(
                    "Custom album cover set for %s by %s: %s",
                    album_name,
                    artist_name,
                    path,
                )
                if on_art_changed is not None:
                    on_art_changed()
        except GLib.Error:
            # User cancelled the dialog
            pass

    def _on_art_btn_clicked(_btn):
        _open_image_file_dialog(parent_widget, _on_file_selected)

    art_btn.connect("clicked", _on_art_btn_clicked)
    content.append(art_btn)

    # Clear custom art button (only if one is set)
    if custom_art_path:
        clear_btn = Gtk.Button(label="Clear Custom Cover Art")
        clear_btn.add_css_class("flat")
        clear_btn.add_css_class("destructive-action")

        def _on_clear_clicked(_btn):
            db.set_setting(custom_art_key, "")
            art_status.set_label("Custom cover cleared")
            art_status.set_visible(True)
            if on_art_changed is not None:
                on_art_changed()

        clear_btn.connect("clicked", _on_clear_clicked)
        content.append(clear_btn)

    content.append(art_status)

    # Create dialog
    dialog = Adw.AlertDialog()
    dialog.set_heading(f"Album Properties \u2014 {album_name}")
    dialog.set_extra_child(content)
    dialog.add_response("close", "Close")
    dialog.set_default_response("close")
    dialog.set_close_response("close")
    dialog.present(parent_widget)
