"""Now-playing bar for the Auxen music player."""

from __future__ import annotations

from typing import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, Gtk, Pango

from auxen.views.visualizer import SpectrumVisualizer


def _format_time(seconds: float) -> str:
    """Format seconds as M:SS (e.g. 3:45)."""
    total = max(0, int(seconds))
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


class NowPlayingBar(Gtk.Box):
    """Persistent now-playing bar with track info, transport controls, and extras."""

    __gtype_name__ = "NowPlayingBar"

    def __init__(
        self,
        on_play_pause: Callable[[], None] | None = None,
        on_next: Callable[[], None] | None = None,
        on_previous: Callable[[], None] | None = None,
        on_shuffle: Callable[[bool], None] | None = None,
        on_repeat: Callable[[bool], None] | None = None,
        on_lyrics_toggle: Callable[[bool], None] | None = None,
        on_queue_toggle: Callable[[bool], None] | None = None,
        on_favorite: Callable[[bool], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(
            orientation=Gtk.Orientation.HORIZONTAL,
            **kwargs,
        )

        self._on_play_pause = on_play_pause
        self._on_next = on_next
        self._on_previous = on_previous
        self._on_shuffle = on_shuffle
        self._on_repeat = on_repeat
        self._on_lyrics_toggle = on_lyrics_toggle
        self._on_queue_toggle = on_queue_toggle
        self._on_favorite = on_favorite

        self._is_playing = False
        self._shuffle_active = False
        self._repeat_active = False
        self._favorite_active = False
        self._lyrics_active = False
        self._queue_active = False
        self._sleep_timer_active = False

        # Navigation callbacks for clickable artist/title
        self.on_artist_clicked: Callable[[str], None] | None = None
        self.on_album_clicked: Callable[[str, str], None] | None = None
        self._current_artist: str = ""
        self._current_album: str = ""

        self.add_css_class("now-playing-bar")

        # ---- Left section: Track info ----
        left = self._build_left_section()
        left.set_hexpand(True)
        self.append(left)

        # ---- Center section: Transport + Progress ----
        center = self._build_center_section()
        center.set_hexpand(True)
        self.append(center)

        # ---- Right section: Extra controls ----
        right = self._build_right_section()
        right.set_hexpand(True)
        self.append(right)

    # ── Left section ──────────────────────────────────────

    def _build_left_section(self) -> Gtk.Box:
        """Build the track-info section (art, title, artist, favorite)."""
        left = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        left.set_valign(Gtk.Align.CENTER)

        # Album art container
        self._art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._art_box.add_css_class("now-playing-art")
        self._art_box.set_size_request(48, 48)

        # Placeholder icon (shown when no album art is available)
        self._art_placeholder = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        self._art_placeholder.set_pixel_size(24)
        self._art_placeholder.set_opacity(0.4)
        self._art_placeholder.set_halign(Gtk.Align.CENTER)
        self._art_placeholder.set_valign(Gtk.Align.CENTER)
        self._art_placeholder.set_vexpand(True)

        # Actual album art image (hidden until art is loaded)
        self._art_image = Gtk.Image()
        self._art_image.set_size_request(48, 48)
        self._art_image.set_halign(Gtk.Align.CENTER)
        self._art_image.set_valign(Gtk.Align.CENTER)
        self._art_image.add_css_class("now-playing-art-image")
        self._art_image.set_visible(False)

        self._art_box.append(self._art_placeholder)
        self._art_box.append(self._art_image)
        left.append(self._art_box)

        # Title + Artist
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_valign(Gtk.Align.CENTER)

        self._title_label = Gtk.Label(label="No Track Playing")
        self._title_label.set_xalign(0)
        self._title_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._title_label.set_max_width_chars(30)
        self._title_label.add_css_class("now-playing-track-title")
        self._title_label.add_css_class("clickable-link")
        self._title_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))

        title_click = Gtk.GestureClick.new()
        title_click.set_button(1)
        title_click.connect("released", self._on_title_label_clicked)
        self._title_label.add_controller(title_click)

        text_box.append(self._title_label)

        self._artist_label = Gtk.Label(label="")
        self._artist_label.set_xalign(0)
        self._artist_label.set_ellipsize(Pango.EllipsizeMode.END)
        self._artist_label.set_max_width_chars(30)
        self._artist_label.add_css_class("now-playing-track-artist")
        self._artist_label.add_css_class("clickable-link")
        self._artist_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))

        artist_click = Gtk.GestureClick.new()
        artist_click.set_button(1)
        artist_click.connect("released", self._on_artist_label_clicked)
        self._artist_label.add_controller(artist_click)

        text_box.append(self._artist_label)

        left.append(text_box)

        # Favorite button
        self._fav_btn = Gtk.ToggleButton()
        self._fav_btn.set_icon_name("emblem-favorite-symbolic")
        self._fav_btn.add_css_class("flat")
        self._fav_btn.add_css_class("now-playing-control-btn")
        self._fav_btn.set_valign(Gtk.Align.CENTER)
        self._fav_btn.connect("toggled", self._on_favorite_toggled)
        left.append(self._fav_btn)

        return left

    # ── Center section ────────────────────────────────────

    def _build_center_section(self) -> Gtk.Box:
        """Build transport controls and progress bar."""
        center = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        center.set_valign(Gtk.Align.CENTER)
        center.set_halign(Gtk.Align.CENTER)

        # Transport controls row
        transport = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        transport.set_halign(Gtk.Align.CENTER)

        # Shuffle
        self._shuffle_btn = Gtk.ToggleButton()
        self._shuffle_btn.set_icon_name("media-playlist-shuffle-symbolic")
        self._shuffle_btn.add_css_class("flat")
        self._shuffle_btn.add_css_class("now-playing-control-btn")
        self._shuffle_btn.set_valign(Gtk.Align.CENTER)
        self._shuffle_btn.connect("toggled", self._on_shuffle_toggled)
        transport.append(self._shuffle_btn)

        # Previous
        prev_btn = Gtk.Button.new_from_icon_name(
            "media-skip-backward-symbolic"
        )
        prev_btn.add_css_class("flat")
        prev_btn.add_css_class("now-playing-control-btn")
        prev_btn.set_valign(Gtk.Align.CENTER)
        prev_btn.connect("clicked", self._on_prev_clicked)
        transport.append(prev_btn)

        # Play/Pause (larger, circular)
        self._play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        self._play_btn.add_css_class("now-playing-play-btn")
        self._play_btn.set_valign(Gtk.Align.CENTER)
        self._play_btn.connect("clicked", self._on_play_pause_clicked)
        transport.append(self._play_btn)

        # Next
        next_btn = Gtk.Button.new_from_icon_name(
            "media-skip-forward-symbolic"
        )
        next_btn.add_css_class("flat")
        next_btn.add_css_class("now-playing-control-btn")
        next_btn.set_valign(Gtk.Align.CENTER)
        next_btn.connect("clicked", self._on_next_clicked)
        transport.append(next_btn)

        # Repeat
        self._repeat_btn = Gtk.ToggleButton()
        self._repeat_btn.set_icon_name("media-playlist-repeat-symbolic")
        self._repeat_btn.add_css_class("flat")
        self._repeat_btn.add_css_class("now-playing-control-btn")
        self._repeat_btn.set_valign(Gtk.Align.CENTER)
        self._repeat_btn.connect("toggled", self._on_repeat_toggled)
        transport.append(self._repeat_btn)

        center.append(transport)

        # Spectrum visualizer (between transport and progress)
        self._visualizer = SpectrumVisualizer(
            bar_count=16,
            bar_width=4,
            bar_gap=2,
            bar_color="#d4a039",
            max_height=24,
        )
        self._visualizer.set_visible(False)
        center.append(self._visualizer)

        # Progress row
        progress_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        progress_row.set_halign(Gtk.Align.CENTER)

        self._current_time_label = Gtk.Label(label="0:00")
        self._current_time_label.add_css_class("now-playing-time")
        progress_row.append(self._current_time_label)

        self._progress_scale = Gtk.Scale.new_with_range(
            Gtk.Orientation.HORIZONTAL, 0, 100, 1
        )
        self._progress_scale.set_draw_value(False)
        self._progress_scale.set_size_request(280, -1)
        self._progress_scale.add_css_class("now-playing-progress")
        progress_row.append(self._progress_scale)

        self._total_time_label = Gtk.Label(label="0:00")
        self._total_time_label.add_css_class("now-playing-time")
        progress_row.append(self._total_time_label)

        center.append(progress_row)

        return center

    # ── Right section ─────────────────────────────────────

    def _build_right_section(self) -> Gtk.Box:
        """Build extra controls (quality badge, volume, queue)."""
        right = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        right.set_valign(Gtk.Align.CENTER)
        right.set_halign(Gtk.Align.END)

        # Sleep timer active indicator (pulsing dot)
        self._sleep_timer_indicator = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
        )
        self._sleep_timer_indicator.set_valign(Gtk.Align.CENTER)
        self._sleep_timer_indicator.set_visible(False)

        sleep_dot = Gtk.Box()
        sleep_dot.add_css_class("sleep-timer-active-indicator")
        sleep_dot.set_size_request(8, 8)
        self._sleep_timer_indicator.append(sleep_dot)

        sleep_icon = Gtk.Image.new_from_icon_name(
            "weather-clear-night-symbolic"
        )
        sleep_icon.set_pixel_size(14)
        sleep_icon.set_opacity(0.7)
        self._sleep_timer_indicator.append(sleep_icon)

        right.append(self._sleep_timer_indicator)

        # Quality badge
        self._quality_badge = Gtk.Label(label="")
        self._quality_badge.add_css_class("now-playing-quality-badge")
        self._quality_badge.set_valign(Gtk.Align.CENTER)
        self._quality_badge.set_visible(False)
        right.append(self._quality_badge)

        # Volume icon
        self._volume_btn = Gtk.Button.new_from_icon_name(
            "audio-volume-high-symbolic"
        )
        self._volume_btn.add_css_class("flat")
        self._volume_btn.add_css_class("now-playing-control-btn")
        self._volume_btn.set_valign(Gtk.Align.CENTER)
        right.append(self._volume_btn)

        # Volume slider
        self._volume_scale = Gtk.Scale.new_with_range(
            Gtk.Orientation.HORIZONTAL, 0, 100, 1
        )
        self._volume_scale.set_draw_value(False)
        self._volume_scale.set_value(70)
        self._volume_scale.set_size_request(120, -1)
        self._volume_scale.add_css_class("now-playing-volume")
        right.append(self._volume_scale)

        # Lyrics toggle button
        self._lyrics_btn = Gtk.ToggleButton()
        self._lyrics_btn.set_icon_name(
            "format-justify-left-symbolic"
        )
        self._lyrics_btn.add_css_class("flat")
        self._lyrics_btn.add_css_class("now-playing-control-btn")
        self._lyrics_btn.add_css_class("lyrics-toggle-btn")
        self._lyrics_btn.set_valign(Gtk.Align.CENTER)
        self._lyrics_btn.set_tooltip_text("Lyrics")
        self._lyrics_btn.connect("toggled", self._on_lyrics_toggled)
        right.append(self._lyrics_btn)

        # Queue toggle button
        self._queue_btn = Gtk.ToggleButton()
        self._queue_btn.set_icon_name("view-list-symbolic")
        self._queue_btn.add_css_class("flat")
        self._queue_btn.add_css_class("now-playing-control-btn")
        self._queue_btn.add_css_class("queue-toggle-btn")
        self._queue_btn.set_valign(Gtk.Align.CENTER)
        self._queue_btn.set_tooltip_text("Queue")
        self._queue_btn.connect("toggled", self._on_queue_toggled)
        right.append(self._queue_btn)

        return right

    # ── Public API ────────────────────────────────────────

    def update_track(
        self,
        title: str,
        artist: str,
        quality_label: str = "",
        source: str = "",
        album: str = "",
    ) -> None:
        """Update the displayed track info."""
        self._title_label.set_label(title)
        self._artist_label.set_label(artist)
        self._current_artist = artist
        self._current_album = album

        if quality_label:
            self._quality_badge.set_label(quality_label)
            self._quality_badge.set_visible(True)
        else:
            self._quality_badge.set_visible(False)

    def update_position(
        self, position_seconds: float, duration_seconds: float
    ) -> None:
        """Update the progress bar and time labels."""
        self._current_time_label.set_label(_format_time(position_seconds))
        self._total_time_label.set_label(_format_time(duration_seconds))

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
        """Set the album art image, or fall back to placeholder if None."""
        if pixbuf is not None:
            self._art_image.set_from_pixbuf(pixbuf)
            self._art_image.set_visible(True)
            self._art_placeholder.set_visible(False)
        else:
            self._art_image.set_visible(False)
            self._art_placeholder.set_visible(True)

    def set_sleep_timer_active(self, active: bool) -> None:
        """Show or hide the sleep timer indicator dot."""
        self._sleep_timer_active = active
        self._sleep_timer_indicator.set_visible(active)

    @property
    def visualizer(self) -> SpectrumVisualizer:
        """Return the spectrum visualizer widget."""
        return self._visualizer

    def set_visualizer_active(self, active: bool) -> None:
        """Show/hide the spectrum visualizer and toggle its animation."""
        self._visualizer.set_visible(active)
        self._visualizer.set_active(active)

    # ── Internal handlers ─────────────────────────────────

    def _on_artist_label_clicked(
        self,
        _gesture: Gtk.GestureClick,
        n_press: int,
        _x: float,
        _y: float,
    ) -> None:
        """Handle single click on the artist label — navigate to artist detail."""
        if n_press != 1:
            return
        if self.on_artist_clicked is not None and self._current_artist:
            self.on_artist_clicked(self._current_artist)

    def _on_title_label_clicked(
        self,
        _gesture: Gtk.GestureClick,
        n_press: int,
        _x: float,
        _y: float,
    ) -> None:
        """Handle single click on the title label — navigate to album detail."""
        if n_press != 1:
            return
        if (
            self.on_album_clicked is not None
            and self._current_album
            and self._current_artist
        ):
            self.on_album_clicked(self._current_album, self._current_artist)

    def _on_play_pause_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_play_pause:
            self._on_play_pause()
        else:
            self._is_playing = not self._is_playing
            self.set_playing(self._is_playing)
            print(  # noqa: T201
                f"[NowPlaying] play/pause -> playing={self._is_playing}"
            )

    def _on_prev_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_previous:
            self._on_previous()
        else:
            print("[NowPlaying] previous")  # noqa: T201

    def _on_next_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_next:
            self._on_next()
        else:
            print("[NowPlaying] next")  # noqa: T201

    def _on_shuffle_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._shuffle_active = btn.get_active()
        if self._shuffle_active:
            btn.add_css_class("active")
        else:
            btn.remove_css_class("active")

        if self._on_shuffle:
            self._on_shuffle(self._shuffle_active)
        else:
            print(  # noqa: T201
                f"[NowPlaying] shuffle -> {self._shuffle_active}"
            )

    def _on_repeat_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._repeat_active = btn.get_active()
        if self._repeat_active:
            btn.add_css_class("active")
        else:
            btn.remove_css_class("active")

        if self._on_repeat:
            self._on_repeat(self._repeat_active)
        else:
            print(  # noqa: T201
                f"[NowPlaying] repeat -> {self._repeat_active}"
            )

    def _on_favorite_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._favorite_active = btn.get_active()
        if self._favorite_active:
            btn.add_css_class("active")
        else:
            btn.remove_css_class("active")

        if self._on_favorite:
            self._on_favorite(self._favorite_active)

    def _on_lyrics_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._lyrics_active = btn.get_active()
        if self._lyrics_active:
            btn.add_css_class("active")
        else:
            btn.remove_css_class("active")

        if self._on_lyrics_toggle:
            self._on_lyrics_toggle(self._lyrics_active)
        else:
            print(  # noqa: T201
                f"[NowPlaying] lyrics -> {self._lyrics_active}"
            )

    def set_favorite_active(self, active: bool) -> None:
        """Programmatically set the favorite toggle state without triggering callback."""
        self._favorite_active = active
        self._fav_btn.handler_block_by_func(self._on_favorite_toggled)
        self._fav_btn.set_active(active)
        if active:
            self._fav_btn.add_css_class("active")
        else:
            self._fav_btn.remove_css_class("active")
        self._fav_btn.handler_unblock_by_func(self._on_favorite_toggled)

    def set_lyrics_active(self, active: bool) -> None:
        """Programmatically set the lyrics toggle state without triggering callback."""
        self._lyrics_active = active
        self._lyrics_btn.handler_block_by_func(self._on_lyrics_toggled)
        self._lyrics_btn.set_active(active)
        if active:
            self._lyrics_btn.add_css_class("active")
        else:
            self._lyrics_btn.remove_css_class("active")
        self._lyrics_btn.handler_unblock_by_func(self._on_lyrics_toggled)

    def _on_queue_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._queue_active = btn.get_active()
        if self._queue_active:
            btn.add_css_class("active")
        else:
            btn.remove_css_class("active")

        if self._on_queue_toggle:
            self._on_queue_toggle(self._queue_active)
        else:
            print(  # noqa: T201
                f"[NowPlaying] queue -> {self._queue_active}"
            )

    def set_queue_active(self, active: bool) -> None:
        """Programmatically set the queue toggle state without triggering callback."""
        self._queue_active = active
        self._queue_btn.handler_block_by_func(self._on_queue_toggled)
        self._queue_btn.set_active(active)
        if active:
            self._queue_btn.add_css_class("active")
        else:
            self._queue_btn.remove_css_class("active")
        self._queue_btn.handler_unblock_by_func(self._on_queue_toggled)
