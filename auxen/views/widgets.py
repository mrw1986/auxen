"""Shared reusable widgets for Auxen views."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import Gtk


def make_tidal_source_badge(
    label_text: str = "Tidal",
    css_class: str = "source-badge-tidal",
    icon_size: int = 12,
) -> Gtk.Box:
    """Create a Tidal source badge with icon + text in a pill shape.

    Returns a Gtk.Box that looks like the old Gtk.Label badge but with a
    tidal-symbolic icon prepended.

    Parameters
    ----------
    label_text:
        Text to display (e.g. "Tidal", "TIDAL", "Playlist").
    css_class:
        CSS class for styling (e.g. "source-badge-tidal", "nav-badge-tidal").
    icon_size:
        Pixel size for the Tidal icon.
    """
    box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
    box.add_css_class(css_class)

    icon = Gtk.Image.new_from_icon_name("tidal-symbolic")
    icon.set_pixel_size(icon_size)
    box.append(icon)

    label = Gtk.Label(label=label_text)
    box.append(label)

    return box
