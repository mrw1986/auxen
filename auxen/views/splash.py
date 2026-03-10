"""Splash screen shown during app startup."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk


class SplashScreen(Gtk.Box):
    """Full-screen splash with logo, title, and tagline."""

    def __init__(self) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.FILL,
            valign=Gtk.Align.FILL,
            hexpand=True,
            vexpand=True,
        )
        self.add_css_class("splash-screen")

        # Spacer to vertically center content
        top_spacer = Gtk.Box(vexpand=True)
        self.append(top_spacer)

        # Logo
        self._logo = Gtk.Image()
        self._logo.set_pixel_size(120)
        self._logo.set_halign(Gtk.Align.CENTER)
        self._update_logo()
        self.append(self._logo)

        # Title
        title = Gtk.Label(label="AUXEN")
        title.add_css_class("splash-title")
        title.set_halign(Gtk.Align.CENTER)
        title.set_margin_top(16)
        self.append(title)

        # Tagline
        tagline = Gtk.Label(label="UNORTHODOX AUDIO")
        tagline.add_css_class("splash-tagline")
        tagline.set_halign(Gtk.Align.CENTER)
        tagline.set_margin_top(8)
        self.append(tagline)

        # Bottom spacer
        bottom_spacer = Gtk.Box(vexpand=True)
        self.append(bottom_spacer)

        # Listen for theme changes to swap logo
        style_mgr = Adw.StyleManager.get_default()
        style_mgr.connect("notify::dark", lambda *_: self._update_logo())

    def _update_logo(self) -> None:
        is_dark = Adw.StyleManager.get_default().get_dark()
        icon_name = "auxen-logo-dark" if is_dark else "auxen-logo-light"
        self._logo.set_from_icon_name(icon_name)
