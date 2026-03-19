"""Now-playing bar for the Auxen music player."""

from __future__ import annotations

from typing import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Adw, Gdk, GdkPixbuf, GLib, Gtk

from auxen.queue import RepeatMode
from auxen.views.visualizer import SpectrumVisualizer

# Marquee animation constants
_MARQUEE_SPEED_PX_PER_SEC = 30  # scroll speed in pixels per second
_MARQUEE_PAUSE_MS = 2000  # pause at start and end in milliseconds


def _format_time(seconds: float) -> str:
    """Format seconds as M:SS (e.g. 3:45)."""
    total = max(0, int(seconds))
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


class _MarqueeLabel(Gtk.ScrolledWindow):
    """A label that scrolls horizontally when text overflows.

    Uses a ScrolledWindow with EXTERNAL/NEVER policy so no scrollbar is
    visible.  When the label is wider than the container, an
    Adw.TimedAnimation scrolls the hadjustment back and forth with pauses.
    """

    def __init__(self, label: str = "", **kwargs) -> None:
        super().__init__(**kwargs)
        self.set_policy(Gtk.PolicyType.EXTERNAL, Gtk.PolicyType.NEVER)
        self.set_vexpand(False)
        self.set_hexpand(True)
        self.set_valign(Gtk.Align.CENTER)
        # Remove any default min-content-width so we shrink freely
        self.set_min_content_width(0)
        self.set_propagate_natural_width(False)

        self._label = Gtk.Label(label=label)
        self._label.set_xalign(0)
        self._label.set_single_line_mode(True)
        self._label.set_max_width_chars(-1)
        self._label.set_width_chars(4)
        self._label.set_hexpand(False)
        self.set_child(self._label)

        self._animation: Adw.TimedAnimation | None = None
        self._phase_timeout_id: int = 0
        self._scroll_phase: int = 0  # 0=pause-start,1=fwd,2=pause-end,3=rev

    # ── Delegate common Gtk.Label methods ──

    @property
    def label_widget(self) -> Gtk.Label:
        """Access the inner Gtk.Label directly (for CSS classes, etc.)."""
        return self._label

    def set_label(self, text: str) -> None:
        self._label.set_label(text)
        self._restart_marquee()

    def get_label(self) -> str:
        return self._label.get_label()

    def set_xalign(self, xalign: float) -> None:
        self._label.set_xalign(xalign)

    def add_css_class(self, css_class: str) -> None:  # type: ignore[override]
        self._label.add_css_class(css_class)

    def remove_css_class(self, css_class: str) -> None:  # type: ignore[override]
        self._label.remove_css_class(css_class)

    def set_cursor(self, cursor: Gdk.Cursor | None) -> None:
        # Set cursor on both the scrolled window and the label
        super().set_cursor(cursor)
        self._label.set_cursor(cursor)

    def add_controller(self, controller: Gtk.EventController) -> None:
        # Attach gesture controllers to the scrolled window so clicks work
        super().add_controller(controller)

    # ── Marquee animation logic ──

    def _get_overflow(self) -> float:
        """Return how many pixels the label overflows the container, or 0."""
        hadj = self.get_hadjustment()
        if hadj is None:
            return 0.0
        upper = hadj.get_upper()
        page = hadj.get_page_size()
        if upper <= page or page <= 0:
            return 0.0
        return upper - page

    def _stop_animation(self) -> None:
        """Cancel any running animation and pending timeouts."""
        if self._animation is not None:
            self._animation.pause()
            self._animation = None
        if self._phase_timeout_id:
            GLib.source_remove(self._phase_timeout_id)
            self._phase_timeout_id = 0
        # Reset scroll position
        hadj = self.get_hadjustment()
        if hadj is not None:
            hadj.set_value(0)

    def _restart_marquee(self) -> None:
        """Stop current animation and schedule a new check after layout."""
        self._stop_animation()
        # Wait for layout to settle before checking overflow
        GLib.timeout_add(500, self._check_and_start)

    def _check_and_start(self) -> bool:
        """Check if text overflows and start animation if so."""
        overflow = self._get_overflow()
        if overflow > 0:
            self._scroll_phase = 0
            self._start_phase()
        return False  # one-shot

    def _start_phase(self) -> None:
        """Start the current phase of the marquee cycle."""
        overflow = self._get_overflow()
        if overflow <= 0:
            return

        hadj = self.get_hadjustment()
        if hadj is None:
            return

        if self._scroll_phase == 0:
            # Phase 0: pause at start for 2s, then scroll forward
            self._phase_timeout_id = GLib.timeout_add(
                _MARQUEE_PAUSE_MS, self._on_pause_done
            )
        elif self._scroll_phase == 1:
            # Phase 1: scroll from 0 to overflow
            duration_ms = int((overflow / _MARQUEE_SPEED_PX_PER_SEC) * 1000)
            duration_ms = max(duration_ms, 200)
            target = Adw.CallbackAnimationTarget.new(
                lambda val: hadj.set_value(val)
            )
            self._animation = Adw.TimedAnimation.new(
                self, 0, overflow, duration_ms, target
            )
            self._animation.set_easing(Adw.Easing.LINEAR)
            self._animation.connect("done", self._on_scroll_done)
            self._animation.play()
        elif self._scroll_phase == 2:
            # Phase 2: pause at end for 2s, then scroll back
            self._phase_timeout_id = GLib.timeout_add(
                _MARQUEE_PAUSE_MS, self._on_pause_done
            )
        elif self._scroll_phase == 3:
            # Phase 3: scroll from overflow back to 0
            duration_ms = int((overflow / _MARQUEE_SPEED_PX_PER_SEC) * 1000)
            duration_ms = max(duration_ms, 200)
            target = Adw.CallbackAnimationTarget.new(
                lambda val: hadj.set_value(val)
            )
            self._animation = Adw.TimedAnimation.new(
                self, overflow, 0, duration_ms, target
            )
            self._animation.set_easing(Adw.Easing.LINEAR)
            self._animation.connect("done", self._on_scroll_done)
            self._animation.play()

    def _on_pause_done(self) -> bool:
        """Called when a pause phase completes."""
        self._phase_timeout_id = 0
        self._scroll_phase = (self._scroll_phase + 1) % 4
        self._start_phase()
        return False  # one-shot

    def _on_scroll_done(self, _animation: Adw.TimedAnimation) -> None:
        """Called when a scroll animation completes."""
        self._animation = None
        self._scroll_phase = (self._scroll_phase + 1) % 4
        self._start_phase()


class NowPlayingBar(Gtk.Box):
    """Persistent now-playing bar with track info, transport controls, and extras.

    Responsive layout levels:
        0 (full, >=800): 3-section horizontal, all controls visible
        1 (compact, 600-799): hide volume slider + quality badge
        2 (narrow, 450-599): 2-row layout — Row1=[Art+Info+Fav+Prev/Play/Next],
                             Row2=[full-width progress]. Hide shuffle/repeat/volume/lyrics/queue.
        3 (ultra, <450): single row [Art+Info+Play], no progress bar.
    """

    __gtype_name__ = "NowPlayingBar"

    def __init__(
        self,
        on_play_pause: Callable[[], None] | None = None,
        on_next: Callable[[], None] | None = None,
        on_previous: Callable[[], None] | None = None,
        on_shuffle: Callable[[bool], None] | None = None,
        on_repeat: Callable[[RepeatMode], None] | None = None,
        on_lyrics_toggle: Callable[[bool], None] | None = None,
        on_queue_toggle: Callable[[bool], None] | None = None,
        on_favorite: Callable[[bool], None] | None = None,
        **kwargs,
    ) -> None:
        # Outer container is VERTICAL so we can stack main_row + progress_row
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
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
        self._repeat_mode: RepeatMode = RepeatMode.OFF
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
        self.set_size_request(-1, 60)
        self.set_vexpand(False)

        # ---- Main row: left + center + right (horizontal) ----
        self._main_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
        )
        self._main_row.set_hexpand(True)
        self.append(self._main_row)

        # ---- Left section: Track info ----
        self._left_section = self._build_left_section()
        self._left_section.set_hexpand(True)
        self._main_row.append(self._left_section)

        # ---- Center section: Transport + Progress ----
        self._center_section = self._build_center_section()
        self._center_section.set_hexpand(True)
        self._main_row.append(self._center_section)

        # ---- Right section: Extra controls ----
        self._right_section = self._build_right_section()
        self._right_section.set_hexpand(False)
        self._main_row.append(self._right_section)

        # Inline transport controls for narrow mode (hidden by default)
        self._inline_transport = self._build_inline_transport()
        self._inline_transport.set_visible(False)
        self._left_section.append(self._inline_transport)

        # Responsive layout state
        self._responsive_level = 0
        self._responsive_pending = False
        # Track whether progress row is reparented to outer box
        self._progress_reparented = False

    def do_size_allocate(self, width: int, height: int, baseline: int) -> None:
        """Detect width changes and schedule responsive layout updates."""
        super().do_size_allocate(width, height, baseline)
        if width < 450:
            level = 3  # ultra-narrow
        elif width < 600:
            level = 2  # narrow
        elif width < 800:
            level = 1  # compact
        else:
            level = 0  # full
        if level != self._responsive_level and not self._responsive_pending:
            self._responsive_level = level
            self._responsive_pending = True
            GLib.idle_add(self._apply_responsive_layout)

    def _apply_responsive_layout(self) -> bool:
        """Apply visibility and reparenting changes for the current responsive level."""
        self._responsive_pending = False
        level = self._responsive_level

        # Volume controls: only at full width
        self._volume_scale.set_visible(level == 0)
        self._volume_btn.set_visible(level == 0)

        # Quality badge: only at full width
        if level == 0:
            has_quality = bool(self._quality_badge.get_label())
            self._quality_badge.set_visible(has_quality)
        else:
            self._quality_badge.set_visible(False)

        # Shuffle/repeat: levels 0-1 only
        self._shuffle_btn.set_visible(level < 2)
        self._repeat_btn.set_visible(level < 2)

        # Time labels: levels 0-1 in center, level 2 in reparented progress row
        self._current_time_label.set_visible(level < 2)
        self._total_time_label.set_visible(level < 2)

        # Lyrics/queue buttons: levels 0-1 only
        self._lyrics_btn.set_visible(level < 2)
        self._queue_btn.set_visible(level < 2)

        # Level 2 (narrow): reparent progress row to outer box (full-width below)
        # and show inline prev/play/next in left section
        if level == 2:
            if not self._progress_reparented:
                self._center_section.remove(self._progress_row)
                self.append(self._progress_row)
                self._progress_reparented = True
            self._center_section.set_visible(False)
            self._right_section.set_visible(False)
            self._progress_row.set_visible(True)
            self._inline_transport.set_visible(True)
            self._inline_play_btn.set_visible(False)
            self.add_css_class("now-playing-narrow")
            self.set_size_request(-1, 85)
        elif level == 3:
            # Ultra-narrow: hide everything except left + inline play
            if self._progress_reparented:
                self.remove(self._progress_row)
                self._center_section.append(self._progress_row)
                self._progress_reparented = False
            self._center_section.set_visible(False)
            self._right_section.set_visible(False)
            self._progress_row.set_visible(False)
            self._inline_transport.set_visible(False)
            self._inline_play_btn.set_visible(True)
            self.remove_css_class("now-playing-narrow")
            self.set_size_request(-1, 60)
        else:
            # Levels 0-1: restore normal layout
            if self._progress_reparented:
                self.remove(self._progress_row)
                self._center_section.append(self._progress_row)
                self._progress_reparented = False
            self._center_section.set_visible(True)
            self._right_section.set_visible(True)
            self._progress_row.set_visible(True)
            self._inline_transport.set_visible(False)
            self._inline_play_btn.set_visible(False)
            self.remove_css_class("now-playing-narrow")
            self.set_size_request(-1, 60)

        return False  # Remove idle callback

    # ── Left section ──────────────────────────────────────

    def _build_left_section(self) -> Gtk.Box:
        """Build the track-info section (art, title, artist, favorite)."""
        left = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        left.set_valign(Gtk.Align.CENTER)
        left.set_size_request(-1, -1)

        # Album art container
        self._art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._art_box.add_css_class("now-playing-art")
        self._art_box.set_size_request(48, 48)
        self._art_box.set_vexpand(False)

        # Placeholder icon (shown when no album art is available)
        self._art_placeholder = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        self._art_placeholder.set_pixel_size(32)
        self._art_placeholder.set_opacity(0.4)
        self._art_placeholder.set_halign(Gtk.Align.CENTER)
        self._art_placeholder.set_valign(Gtk.Align.CENTER)
        self._art_placeholder.set_vexpand(True)

        # Actual album art image (hidden until art is loaded)
        self._art_image = Gtk.Image()
        self._art_image.set_pixel_size(48)
        self._art_image.set_size_request(48, 48)
        self._art_image.set_halign(Gtk.Align.FILL)
        self._art_image.set_valign(Gtk.Align.FILL)
        self._art_image.add_css_class("now-playing-art-image")
        self._art_image.set_visible(False)

        self._art_box.append(self._art_placeholder)
        self._art_box.append(self._art_image)

        # Spectrum visualizer overlaid at the bottom of the album art
        self._visualizer = SpectrumVisualizer(
            bar_count=8,
            bar_width=4,
            bar_gap=1,
            bar_color="#d4a039",
            max_height=14,
        )
        self._visualizer.set_visible(False)
        self._visualizer.set_halign(Gtk.Align.FILL)
        self._visualizer.set_valign(Gtk.Align.END)

        art_overlay = Gtk.Overlay()
        art_overlay.set_child(self._art_box)
        art_overlay.add_overlay(self._visualizer)
        left.append(art_overlay)

        # Title + Artist
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_valign(Gtk.Align.CENTER)
        text_box.set_hexpand(True)

        self._title_label = _MarqueeLabel(label="No Track Playing")
        self._title_label.set_xalign(0)
        self._title_label.set_hexpand(True)
        self._title_label.add_css_class("now-playing-track-title")
        self._title_label.add_css_class("clickable-link")
        self._title_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))

        title_click = Gtk.GestureClick.new()
        title_click.set_button(1)
        title_click.connect("released", self._on_title_label_clicked)
        self._title_label.add_controller(title_click)

        text_box.append(self._title_label)

        self._artist_label = _MarqueeLabel(label="")
        self._artist_label.set_xalign(0)
        self._artist_label.set_hexpand(True)
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
        self._fav_btn.set_tooltip_text("Add to Collection")
        self._fav_btn.connect("toggled", self._on_favorite_toggled)
        left.append(self._fav_btn)

        # Inline play/pause for ultra-narrow mode (hidden by default)
        self._inline_play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        self._inline_play_btn.add_css_class("flat")
        self._inline_play_btn.add_css_class("now-playing-control-btn")
        self._inline_play_btn.set_valign(Gtk.Align.CENTER)
        self._inline_play_btn.set_visible(False)
        self._inline_play_btn.connect(
            "clicked", self._on_play_pause_clicked
        )
        left.append(self._inline_play_btn)

        return left

    def _build_inline_transport(self) -> Gtk.Box:
        """Build compact prev/play/next buttons for narrow mode."""
        box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=2,
        )
        box.set_valign(Gtk.Align.CENTER)

        prev_btn = Gtk.Button.new_from_icon_name(
            "media-skip-backward-symbolic"
        )
        prev_btn.add_css_class("flat")
        prev_btn.add_css_class("now-playing-control-btn")
        prev_btn.set_valign(Gtk.Align.CENTER)
        prev_btn.set_tooltip_text("Previous Track")
        prev_btn.connect("clicked", self._on_prev_clicked)
        box.append(prev_btn)

        play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        play_btn.add_css_class("now-playing-play-btn")
        play_btn.set_valign(Gtk.Align.CENTER)
        play_btn.set_tooltip_text("Play")
        play_btn.connect("clicked", self._on_play_pause_clicked)
        self._inline_transport_play_btn = play_btn
        box.append(play_btn)

        next_btn = Gtk.Button.new_from_icon_name(
            "media-skip-forward-symbolic"
        )
        next_btn.add_css_class("flat")
        next_btn.add_css_class("now-playing-control-btn")
        next_btn.set_valign(Gtk.Align.CENTER)
        next_btn.set_tooltip_text("Next Track")
        next_btn.connect("clicked", self._on_next_clicked)
        box.append(next_btn)

        return box

    # ── Center section ────────────────────────────────────

    def _build_center_section(self) -> Gtk.Box:
        """Build transport controls and progress bar."""
        center = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
        )
        center.set_valign(Gtk.Align.CENTER)
        center.set_halign(Gtk.Align.FILL)

        # Transport controls row
        transport = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
        )
        transport.set_halign(Gtk.Align.CENTER)

        # Shuffle
        self._shuffle_btn = Gtk.ToggleButton()
        self._shuffle_btn.set_icon_name("media-playlist-shuffle-symbolic")
        self._shuffle_btn.add_css_class("flat")
        self._shuffle_btn.add_css_class("now-playing-control-btn")
        self._shuffle_btn.set_valign(Gtk.Align.CENTER)
        self._shuffle_btn.set_tooltip_text("Shuffle")
        self._shuffle_btn.connect("toggled", self._on_shuffle_toggled)
        transport.append(self._shuffle_btn)

        # Previous
        prev_btn = Gtk.Button.new_from_icon_name(
            "media-skip-backward-symbolic"
        )
        prev_btn.add_css_class("flat")
        prev_btn.add_css_class("now-playing-control-btn")
        prev_btn.set_valign(Gtk.Align.CENTER)
        prev_btn.set_tooltip_text("Previous Track")
        prev_btn.connect("clicked", self._on_prev_clicked)
        transport.append(prev_btn)

        # Play/Pause (larger, circular)
        self._play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        self._play_btn.add_css_class("now-playing-play-btn")
        self._play_btn.set_valign(Gtk.Align.CENTER)
        self._play_btn.set_tooltip_text("Play")
        self._play_btn.connect("clicked", self._on_play_pause_clicked)
        transport.append(self._play_btn)

        # Next
        next_btn = Gtk.Button.new_from_icon_name(
            "media-skip-forward-symbolic"
        )
        next_btn.add_css_class("flat")
        next_btn.add_css_class("now-playing-control-btn")
        next_btn.set_valign(Gtk.Align.CENTER)
        next_btn.set_tooltip_text("Next Track")
        next_btn.connect("clicked", self._on_next_clicked)
        transport.append(next_btn)

        # Repeat (3-state: Off → All → One → Off)
        self._repeat_btn = Gtk.Button()
        self._repeat_btn.set_icon_name("media-playlist-repeat-symbolic")
        self._repeat_btn.add_css_class("flat")
        self._repeat_btn.add_css_class("now-playing-control-btn")
        self._repeat_btn.set_valign(Gtk.Align.CENTER)
        self._repeat_btn.set_tooltip_text("Repeat Off")
        self._repeat_btn.connect("clicked", self._on_repeat_clicked)
        transport.append(self._repeat_btn)

        center.append(transport)

        # Progress row
        self._progress_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        self._progress_row.set_hexpand(True)

        self._current_time_label = Gtk.Label(label="0:00")
        self._current_time_label.add_css_class("now-playing-time")
        self._progress_row.append(self._current_time_label)

        self._progress_scale = Gtk.Scale.new_with_range(
            Gtk.Orientation.HORIZONTAL, 0, 100, 1
        )
        self._progress_scale.set_draw_value(False)
        self._progress_scale.set_size_request(20, -1)
        self._progress_scale.set_hexpand(True)
        self._progress_scale.add_css_class("now-playing-progress")
        self._progress_row.append(self._progress_scale)

        self._total_time_label = Gtk.Label(label="0:00")
        self._total_time_label.add_css_class("now-playing-time")
        self._progress_row.append(self._total_time_label)

        center.append(self._progress_row)

        return center

    # ── Right section ─────────────────────────────────────

    def _build_right_section(self) -> Gtk.Box:
        """Build extra controls (quality badge, volume, queue)."""
        right = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=4,
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
        self._sleep_timer_indicator.set_tooltip_text("Sleep Timer Active")

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
        self._volume_btn.set_tooltip_text("Volume")
        right.append(self._volume_btn)

        # Volume slider
        self._volume_scale = Gtk.Scale.new_with_range(
            Gtk.Orientation.HORIZONTAL, 0, 100, 1
        )
        self._volume_scale.set_draw_value(False)
        self._volume_scale.set_value(70)
        self._volume_scale.set_size_request(80, -1)
        self._volume_scale.add_css_class("now-playing-volume")
        self._volume_scale.set_tooltip_text("Volume")
        right.append(self._volume_scale)

        # Lyrics toggle button
        self._lyrics_btn = Gtk.ToggleButton()
        self._lyrics_btn.set_icon_name("view-media-lyrics")
        self._lyrics_btn.add_css_class("flat")
        self._lyrics_btn.add_css_class("now-playing-control-btn")
        self._lyrics_btn.add_css_class("lyrics-toggle-btn")
        self._lyrics_btn.set_valign(Gtk.Align.CENTER)
        self._lyrics_btn.set_tooltip_text("Show Lyrics")
        self._lyrics_btn.connect("toggled", self._on_lyrics_toggled)
        right.append(self._lyrics_btn)

        # Queue toggle button
        self._queue_btn = Gtk.ToggleButton()
        self._queue_btn.set_icon_name("view-media-playlist")
        self._queue_btn.add_css_class("flat")
        self._queue_btn.add_css_class("now-playing-control-btn")
        self._queue_btn.add_css_class("queue-toggle-btn")
        self._queue_btn.set_valign(Gtk.Align.CENTER)
        self._queue_btn.set_tooltip_text("Show Queue")
        self._queue_btn.connect("toggled", self._on_queue_toggled)
        right.append(self._queue_btn)

        return right

    # ── Public API ────────────────────────────────────────

    def set_idle(self, idle: bool) -> None:
        """Toggle the bar between idle (no track) and active states.

        When idle the bar stays visible at the same fixed height but
        controls are dimmed / insensitive so there is no layout reflow.
        """
        if idle:
            self.add_css_class("now-playing-idle")
            self._play_btn.set_sensitive(False)
        else:
            self.remove_css_class("now-playing-idle")
            self._play_btn.set_sensitive(True)

    def update_track(
        self,
        title: str,
        artist: str,
        quality_label: str = "",
        source: str = "",
        album: str = "",
        track=None,
    ) -> None:
        """Update the displayed track info."""
        self._title_label.set_label(title or "No Track Playing")
        self._artist_label.set_label(artist)
        self._current_artist = artist
        self._current_album = album

        if quality_label:
            self._quality_badge.set_label(quality_label)
            self._quality_badge.set_visible(self._responsive_level == 0)
            # Build detailed tooltip with bitrate/sample rate/bit depth
            from auxen.views.widgets import QUALITY_TOOLTIPS
            parts = [QUALITY_TOOLTIPS.get(
                quality_label, f"{quality_label} Audio"
            )]
            if track is not None:
                bitrate = getattr(track, "bitrate", None)
                sample_rate = getattr(track, "sample_rate", None)
                bit_depth = getattr(track, "bit_depth", None)
                details = []
                if bitrate and bitrate > 0:
                    details.append(f"{bitrate} kbps")
                if sample_rate and sample_rate > 0:
                    sr = sample_rate / 1000 if sample_rate >= 1000 else sample_rate
                    details.append(f"{sr:g} kHz")
                if bit_depth and bit_depth > 0:
                    details.append(f"{bit_depth}-bit")
                if details:
                    parts.append(" · ".join(details))
            self._quality_badge.set_tooltip_text("\n".join(parts))
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
        """Toggle the play/pause button icon and tooltip."""
        self._is_playing = is_playing
        icon = (
            "media-playback-pause-symbolic"
            if is_playing
            else "media-playback-start-symbolic"
        )
        self._play_btn.set_icon_name(icon)
        self._play_btn.set_tooltip_text("Pause" if is_playing else "Play")
        self._inline_play_btn.set_icon_name(icon)
        self._inline_transport_play_btn.set_icon_name(icon)

    _ART_SIZE = 64  # logical pixel size for the player-bar album art

    def set_album_art(self, pixbuf: GdkPixbuf.Pixbuf | None) -> None:
        """Set the album art image, or fall back to placeholder if None."""
        if pixbuf is not None:
            texture = Gdk.Texture.new_for_pixbuf(pixbuf)
            self._art_image.set_from_paintable(texture)
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
            btn.set_tooltip_text("Shuffle (On)")
        else:
            btn.remove_css_class("active")
            btn.set_tooltip_text("Shuffle")

        if self._on_shuffle:
            self._on_shuffle(self._shuffle_active)
        else:
            print(  # noqa: T201
                f"[NowPlaying] shuffle -> {self._shuffle_active}"
            )

    def _on_repeat_clicked(self, _btn: Gtk.Button) -> None:
        # Cycle: OFF → QUEUE (All) → TRACK (One) → OFF
        if self._repeat_mode == RepeatMode.OFF:
            self._repeat_mode = RepeatMode.QUEUE
        elif self._repeat_mode == RepeatMode.QUEUE:
            self._repeat_mode = RepeatMode.TRACK
        else:
            self._repeat_mode = RepeatMode.OFF
        self._apply_repeat_ui()

        if self._on_repeat:
            self._on_repeat(self._repeat_mode)
        else:
            print(  # noqa: T201
                f"[NowPlaying] repeat -> {self._repeat_mode}"
            )

    def _apply_repeat_ui(self) -> None:
        """Update the repeat button icon, tooltip, and active state."""
        btn = self._repeat_btn
        if self._repeat_mode == RepeatMode.OFF:
            btn.set_icon_name("media-playlist-repeat-symbolic")
            btn.remove_css_class("active")
            btn.set_tooltip_text("Repeat Off")
        elif self._repeat_mode == RepeatMode.QUEUE:
            btn.set_icon_name("media-playlist-repeat-symbolic")
            btn.add_css_class("active")
            btn.set_tooltip_text("Repeat All")
        else:  # TRACK
            btn.set_icon_name("media-playlist-repeat-song-symbolic")
            btn.add_css_class("active")
            btn.set_tooltip_text("Repeat One")

    def _on_favorite_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._favorite_active = btn.get_active()
        if self._favorite_active:
            btn.add_css_class("active")
            btn.set_tooltip_text("Remove from Collection")
        else:
            btn.remove_css_class("active")
            btn.set_tooltip_text("Add to Collection")

        if self._on_favorite:
            self._on_favorite(self._favorite_active)

    def _on_lyrics_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._lyrics_active = btn.get_active()
        if self._lyrics_active:
            btn.add_css_class("active")
            btn.set_tooltip_text("Hide Lyrics")
        else:
            btn.remove_css_class("active")
            btn.set_tooltip_text("Show Lyrics")

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
            self._fav_btn.set_tooltip_text("Remove from Collection")
        else:
            self._fav_btn.remove_css_class("active")
            self._fav_btn.set_tooltip_text("Add to Collection")
        self._fav_btn.handler_unblock_by_func(self._on_favorite_toggled)

    def set_lyrics_active(self, active: bool) -> None:
        """Programmatically set the lyrics toggle state without triggering callback."""
        self._lyrics_active = active
        self._lyrics_btn.handler_block_by_func(self._on_lyrics_toggled)
        self._lyrics_btn.set_active(active)
        if active:
            self._lyrics_btn.add_css_class("active")
            self._lyrics_btn.set_tooltip_text("Hide Lyrics")
        else:
            self._lyrics_btn.remove_css_class("active")
            self._lyrics_btn.set_tooltip_text("Show Lyrics")
        self._lyrics_btn.handler_unblock_by_func(self._on_lyrics_toggled)

    def _on_queue_toggled(self, btn: Gtk.ToggleButton) -> None:
        self._queue_active = btn.get_active()
        if self._queue_active:
            btn.add_css_class("active")
            btn.set_tooltip_text("Hide Queue")
        else:
            btn.remove_css_class("active")
            btn.set_tooltip_text("Show Queue")

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
            self._queue_btn.set_tooltip_text("Hide Queue")
        else:
            self._queue_btn.remove_css_class("active")
            self._queue_btn.set_tooltip_text("Show Queue")
        self._queue_btn.handler_unblock_by_func(self._on_queue_toggled)
