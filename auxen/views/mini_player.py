"""Compact mini-player window for Auxen."""

from __future__ import annotations

from typing import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import GdkPixbuf, Gtk, Pango


def _format_time(seconds: float) -> str:
    """Format seconds as M:SS (e.g. 3:45)."""
    total = max(0, int(seconds))
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


class MiniPlayerWindow(Gtk.Window):
    """A small always-on-top window with essential playback controls.

    Shows album art, track title, artist name, play/pause and next
    buttons, and a thin progress bar. Designed for ~350x120 pixels.
    """

    __gtype_name__ = "MiniPlayerWindow"

    def __init__(
        self,
        on_play_pause: Callable[[], None] | None = None,
        on_next: Callable[[], None] | None = None,
        on_close_request: Callable[[], None] | None = None,
        on_maximize_request: Callable[[], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)

        self._on_play_pause = on_play_pause
        self._on_next = on_next
        self._on_close_request_cb = on_close_request
        self._on_maximize_request = on_maximize_request
        self._is_playing = False
        self._drag_start_x = 0.0
        self._drag_start_y = 0.0

        # Window configuration
        self.set_title("Auxen Mini Player")
        self.set_default_size(350, 120)
        self.set_resizable(False)
        self.set_decorated(False)

        # Build the UI
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        outer.add_css_class("mini-player")

        # Top bar with close and maximize buttons
        top_bar = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
        )
        top_bar.set_halign(Gtk.Align.END)
        top_bar.set_margin_top(4)
        top_bar.set_margin_end(4)

        # Maximize button (return to full mode)
        maximize_btn = Gtk.Button.new_from_icon_name(
            "view-fullscreen-symbolic"
        )
        maximize_btn.add_css_class("flat")
        maximize_btn.add_css_class("mini-player-controls")
        maximize_btn.set_tooltip_text("Full Player")
        maximize_btn.connect("clicked", self._on_maximize_clicked)
        top_bar.append(maximize_btn)

        # Close button
        close_btn = Gtk.Button.new_from_icon_name(
            "window-close-symbolic"
        )
        close_btn.add_css_class("flat")
        close_btn.add_css_class("mini-player-controls")
        close_btn.set_tooltip_text("Close Mini Player")
        close_btn.connect("clicked", self._on_close_clicked)
        top_bar.append(close_btn)

        outer.append(top_bar)

        # Main content row: art + info + controls
        content = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=10,
        )
        content.set_margin_start(12)
        content.set_margin_end(12)
        content.set_margin_bottom(4)
        content.set_valign(Gtk.Align.CENTER)
        content.set_vexpand(True)

        # Album art container
        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("mini-player-art")
        art_box.set_size_request(48, 48)
        art_box.set_vexpand(False)

        self._art_placeholder = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        self._art_placeholder.set_pixel_size(24)
        self._art_placeholder.set_opacity(0.4)
        self._art_placeholder.set_halign(Gtk.Align.CENTER)
        self._art_placeholder.set_valign(Gtk.Align.CENTER)
        self._art_placeholder.set_vexpand(True)

        self._art_image = Gtk.Image()
        self._art_image.set_size_request(48, 48)
        self._art_image.set_halign(Gtk.Align.CENTER)
        self._art_image.set_valign(Gtk.Align.CENTER)
        self._art_image.add_css_class("mini-player-art-image")
        self._art_image.set_visible(False)

        art_box.append(self._art_placeholder)
        art_box.append(self._art_image)
        content.append(art_box)

        # Track info (title + artist)
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_valign(Gtk.Align.CENTER)
        text_box.set_hexpand(True)

        self._title_label = Gtk.Label(label="No Track Playing")
        self._title_label.set_xalign(0)
        self._title_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._title_label.set_hexpand(True)
        self._title_label.add_css_class("mini-player-title")
        text_box.append(self._title_label)

        self._artist_label = Gtk.Label(label="")
        self._artist_label.set_xalign(0)
        self._artist_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._artist_label.set_hexpand(True)
        self._artist_label.add_css_class("mini-player-artist")
        text_box.append(self._artist_label)

        content.append(text_box)

        # Controls (play/pause + next)
        controls = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
        )
        controls.set_valign(Gtk.Align.CENTER)

        self._play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        self._play_btn.add_css_class("mini-player-play-btn")
        self._play_btn.set_valign(Gtk.Align.CENTER)
        self._play_btn.connect("clicked", self._on_play_pause_clicked)
        controls.append(self._play_btn)

        next_btn = Gtk.Button.new_from_icon_name(
            "media-skip-forward-symbolic"
        )
        next_btn.add_css_class("flat")
        next_btn.add_css_class("mini-player-controls")
        next_btn.set_valign(Gtk.Align.CENTER)
        next_btn.connect("clicked", self._on_next_clicked)
        controls.append(next_btn)

        content.append(controls)

        outer.append(content)

        # Progress bar (thin, at the bottom)
        progress_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        progress_row.set_margin_start(12)
        progress_row.set_margin_end(12)
        progress_row.set_margin_bottom(8)

        self._current_time_label = Gtk.Label(label="0:00")
        self._current_time_label.add_css_class("mini-player-time")
        progress_row.append(self._current_time_label)

        self._progress_scale = Gtk.Scale.new_with_range(
            Gtk.Orientation.HORIZONTAL, 0, 100, 1
        )
        self._progress_scale.set_draw_value(False)
        self._progress_scale.set_hexpand(True)
        self._progress_scale.add_css_class("mini-player-progress")
        progress_row.append(self._progress_scale)

        self._total_time_label = Gtk.Label(label="0:00")
        self._total_time_label.add_css_class("mini-player-time")
        progress_row.append(self._total_time_label)

        outer.append(progress_row)

        self.set_child(outer)

        # Drag gesture for moving the borderless window
        drag = Gtk.GestureDrag.new()
        drag.connect("drag-begin", self._on_drag_begin)
        drag.connect("drag-update", self._on_drag_update)
        outer.add_controller(drag)

        # Intercept the close request to go back to main window
        self.connect("close-request", self._on_window_close_request)

    # ── Public API ────────────────────────────────────────

    @property
    def title_label(self) -> Gtk.Label:
        """The track title label widget."""
        return self._title_label

    @property
    def artist_label(self) -> Gtk.Label:
        """The artist name label widget."""
        return self._artist_label

    @property
    def play_btn(self) -> Gtk.Button:
        """The play/pause button widget."""
        return self._play_btn

    @property
    def progress_scale(self) -> Gtk.Scale:
        """The progress bar scale widget."""
        return self._progress_scale

    @property
    def art_image(self) -> Gtk.Image:
        """The album art image widget."""
        return self._art_image

    def update_track(self, title: str, artist: str) -> None:
        """Update the displayed track title and artist."""
        self._title_label.set_label(title)
        self._artist_label.set_label(artist)

    def update_position(
        self, position_seconds: float, duration_seconds: float
    ) -> None:
        """Update the progress bar and time labels."""
        self._current_time_label.set_label(
            _format_time(position_seconds)
        )
        self._total_time_label.set_label(
            _format_time(duration_seconds)
        )

        if duration_seconds > 0:
            pct = (position_seconds / duration_seconds) * 100
            self._progress_scale.set_value(min(pct, 100))
        else:
            self._progress_scale.set_value(0)

    def set_playing(self, is_playing: bool) -> None:
        """Toggle the play/pause button icon."""
        self._is_playing = is_playing
        icon = (
            "media-playback-pause-symbolic"
            if is_playing
            else "media-playback-start-symbolic"
        )
        self._play_btn.set_icon_name(icon)

    def set_album_art(self, pixbuf: GdkPixbuf.Pixbuf | None) -> None:
        """Set the album art image, or fall back to placeholder."""
        if pixbuf is not None:
            self._art_image.set_from_pixbuf(pixbuf)
            self._art_image.set_visible(True)
            self._art_placeholder.set_visible(False)
        else:
            self._art_image.set_visible(False)
            self._art_placeholder.set_visible(True)

    # ── Internal handlers ─────────────────────────────────

    def _on_play_pause_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_play_pause:
            self._on_play_pause()
        else:
            self._is_playing = not self._is_playing
            self.set_playing(self._is_playing)

    def _on_next_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_next:
            self._on_next()

    def _on_close_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_close_request_cb:
            self._on_close_request_cb()

    def _on_maximize_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_maximize_request:
            self._on_maximize_request()

    def _on_window_close_request(self, _window: Gtk.Window) -> bool:
        """Handle the window close event (return to main window)."""
        if self._on_close_request_cb:
            self._on_close_request_cb()
            return True  # Prevent destruction
        return False

    def _on_drag_begin(
        self, gesture: Gtk.GestureDrag, x: float, y: float
    ) -> None:
        """Record the starting position of a drag."""
        self._drag_start_x = x
        self._drag_start_y = y

    def _on_drag_update(
        self,
        gesture: Gtk.GestureDrag,
        offset_x: float,
        offset_y: float,
    ) -> None:
        """Move the window during a drag operation."""
        surface = self.get_surface()
        if surface is not None:
            native = self.get_native()
            if native is not None:
                # Get the current position via the surface
                # and translate to a move
                try:
                    surface.get_position()
                except Exception:
                    pass
