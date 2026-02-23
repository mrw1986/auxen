"""Audio spectrum visualizer widget for the Auxen music player."""

from __future__ import annotations

import math

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import GLib, Gtk  # noqa: E402


class SpectrumVisualizer(Gtk.DrawingArea):
    """Custom widget that draws frequency bars reacting to audio data.

    The visualizer displays a row of vertical bars whose heights
    correspond to frequency magnitudes.  When active, bars smoothly
    interpolate towards their target values at ~30 fps.  When
    deactivated, bars fade to zero gracefully.

    Parameters
    ----------
    bar_count : int
        Number of frequency bars to display.
    bar_width : int
        Width of each bar in pixels.
    bar_gap : int
        Gap between bars in pixels.
    bar_color : str
        Hex colour string for the bars (e.g. ``"#d4a039"``).
    max_height : int
        Maximum bar height in pixels.
    """

    __gtype_name__ = "SpectrumVisualizer"

    def __init__(
        self,
        bar_count: int = 16,
        bar_width: int = 4,
        bar_gap: int = 2,
        bar_color: str = "#d4a039",
        max_height: int = 32,
    ) -> None:
        super().__init__()

        self._bar_count: int = bar_count
        self._bar_width: int = bar_width
        self._bar_gap: int = bar_gap
        self._bar_color: str = bar_color
        self._max_height: int = max_height

        # Current displayed levels (smoothed)
        self._levels: list[float] = [0.0] * bar_count
        # Target levels from the latest spectrum data
        self._target_levels: list[float] = [0.0] * bar_count

        self._active: bool = False
        self._timer_id: int | None = None

        # Smoothing factor: higher = snappier, lower = smoother
        self._ease_up: float = 0.35
        self._ease_down: float = 0.15

        # Parse the colour once
        self._r, self._g, self._b = self._parse_hex_color(bar_color)

        # Widget sizing
        total_width = bar_count * bar_width + max(0, bar_count - 1) * bar_gap
        self.set_size_request(total_width, max_height)
        self.set_valign(Gtk.Align.CENTER)
        self.set_halign(Gtk.Align.CENTER)

        self.add_css_class("spectrum-visualizer")

        self.set_draw_func(self._draw)

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def bar_count(self) -> int:
        """Number of frequency bars."""
        return self._bar_count

    @property
    def bar_width(self) -> int:
        """Width of each bar in pixels."""
        return self._bar_width

    @property
    def bar_gap(self) -> int:
        """Gap between bars in pixels."""
        return self._bar_gap

    @property
    def bar_color(self) -> str:
        """Hex colour string for bars."""
        return self._bar_color

    @property
    def max_height(self) -> int:
        """Maximum bar height in pixels."""
        return self._max_height

    @property
    def levels(self) -> list[float]:
        """Current smoothed bar levels (read-only copy)."""
        return list(self._levels)

    @property
    def active(self) -> bool:
        """Whether the visualizer is currently active."""
        return self._active

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def update_spectrum(self, new_levels: list[float]) -> None:
        """Set target bar levels from spectrum data.

        Parameters
        ----------
        new_levels : list[float]
            A list of float values in [0.0, 1.0] for each bar.
            Values are clamped.  If the list is shorter than
            ``bar_count``, remaining bars target zero.  If longer,
            extras are ignored.
        """
        for i in range(self._bar_count):
            if i < len(new_levels):
                self._target_levels[i] = max(0.0, min(1.0, new_levels[i]))
            else:
                self._target_levels[i] = 0.0

    def set_active(self, active: bool) -> None:
        """Activate or deactivate the visualizer.

        When deactivated, target levels are set to zero so bars fade
        out.  The redraw timer is started/stopped accordingly.
        """
        self._active = active
        if active:
            self._start_timer()
        else:
            # Set all targets to zero for a smooth fade-out
            self._target_levels = [0.0] * self._bar_count
            # Keep the timer running briefly to animate the fade
            # It will auto-stop when levels reach zero
            if self._timer_id is None:
                self._start_timer()

    # ------------------------------------------------------------------
    # Drawing
    # ------------------------------------------------------------------

    def _draw(
        self,
        _area: Gtk.DrawingArea,
        cr,
        width: int,
        height: int,
    ) -> None:
        """Cairo draw callback for the frequency bars."""
        cr.set_source_rgba(self._r, self._g, self._b, 0.9)

        for i in range(self._bar_count):
            level = self._levels[i]
            bar_h = level * height
            x = i * (self._bar_width + self._bar_gap)
            y = height - bar_h

            if bar_h > 0:
                # Draw rounded-top bars
                radius = min(self._bar_width / 2, 2)
                self._draw_rounded_bar(cr, x, y, self._bar_width, bar_h, radius)
                cr.fill()

    @staticmethod
    def _draw_rounded_bar(
        cr, x: float, y: float, w: float, h: float, r: float
    ) -> None:
        """Draw a rectangle with rounded top corners."""
        if h < r * 2:
            r = h / 2
        cr.new_path()
        cr.move_to(x, y + h)
        cr.line_to(x, y + r)
        cr.arc(x + r, y + r, r, math.pi, 1.5 * math.pi)
        cr.line_to(x + w - r, y)
        cr.arc(x + w - r, y + r, r, 1.5 * math.pi, 2 * math.pi)
        cr.line_to(x + w, y + h)
        cr.close_path()

    # ------------------------------------------------------------------
    # Animation timer
    # ------------------------------------------------------------------

    def _start_timer(self) -> None:
        """Start the ~30 fps redraw timer."""
        if self._timer_id is not None:
            return
        self._timer_id = GLib.timeout_add(33, self._tick)

    def _stop_timer(self) -> None:
        """Stop the redraw timer."""
        if self._timer_id is not None:
            GLib.source_remove(self._timer_id)
            self._timer_id = None

    def _tick(self) -> bool:
        """Interpolate levels towards targets and trigger a redraw.

        Returns ``True`` to keep the timer running, ``False`` to stop.
        """
        any_nonzero = False
        for i in range(self._bar_count):
            target = self._target_levels[i]
            current = self._levels[i]
            diff = target - current

            if abs(diff) < 0.005:
                self._levels[i] = target
            elif diff > 0:
                self._levels[i] = current + diff * self._ease_up
            else:
                self._levels[i] = current + diff * self._ease_down

            if self._levels[i] > 0.001:
                any_nonzero = True

        self.queue_draw()

        # Auto-stop the timer when inactive and all bars have faded
        if not self._active and not any_nonzero:
            self._timer_id = None
            return False

        return True

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_hex_color(hex_color: str) -> tuple[float, float, float]:
        """Parse a hex colour string to (r, g, b) floats in [0, 1]."""
        color = hex_color.lstrip("#")
        if len(color) != 6:
            return (0.83, 0.63, 0.22)  # fallback amber
        r = int(color[0:2], 16) / 255.0
        g = int(color[2:4], 16) / 255.0
        b = int(color[4:6], 16) / 255.0
        return (r, g, b)
