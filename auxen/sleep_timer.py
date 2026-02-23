"""Sleep timer service for the Auxen music player.

Provides a countdown timer that can pause/stop playback after a
user-selected duration.  Supports preset durations, a configurable
``on_expire`` callback, an optional volume fade-out over the last 30
seconds, and a per-second ``on_tick`` callback so the UI can display
the remaining time.

The timer uses ``GLib.timeout_add_seconds`` for the main countdown and
``GLib.timeout_add`` (500 ms interval) for the smooth volume ramp-down.
"""

from __future__ import annotations

import logging
import math
from typing import Callable, Optional

logger = logging.getLogger(__name__)

# Preset durations offered to the user (minutes).
PRESET_DURATIONS: list[int] = [15, 30, 45, 60, 90, 120]

# Duration of the volume fade-out ramp (seconds).
FADE_DURATION_SECONDS: int = 30

# Interval between volume fade steps (milliseconds).
FADE_STEP_INTERVAL_MS: int = 500


class SleepTimer:
    """Countdown sleep timer with optional volume fade-out.

    Parameters
    ----------
    on_expire:
        Called (with no arguments) when the timer reaches zero.
    on_tick:
        Called every second with the number of *remaining seconds* so
        that the UI can update a countdown display.
    on_fade_step:
        Called with ``(volume_fraction: float)`` during the fade-out
        ramp.  *volume_fraction* goes from 1.0 down to 0.0.
    """

    def __init__(
        self,
        on_expire: Optional[Callable[[], None]] = None,
        on_tick: Optional[Callable[[int], None]] = None,
        on_fade_step: Optional[Callable[[float], None]] = None,
    ) -> None:
        self._on_expire = on_expire
        self._on_tick = on_tick
        self._on_fade_step = on_fade_step

        self._remaining_seconds: int = 0
        self._active: bool = False
        self._fade_out_enabled: bool = True
        self._end_of_track: bool = False

        # GLib source IDs (None when not scheduled).
        self._tick_source_id: Optional[int] = None
        self._fade_source_id: Optional[int] = None

        # Volume state for fade-out.
        self._original_volume: float = 1.0
        self._fading: bool = False

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def is_active(self) -> bool:
        """Return ``True`` when the timer is running."""
        return self._active

    @property
    def fade_out_enabled(self) -> bool:
        """Return whether fade-out is enabled."""
        return self._fade_out_enabled

    @fade_out_enabled.setter
    def fade_out_enabled(self, value: bool) -> None:
        self._fade_out_enabled = bool(value)

    @property
    def end_of_track(self) -> bool:
        """Return whether "end of current track" mode is active."""
        return self._end_of_track

    @end_of_track.setter
    def end_of_track(self, value: bool) -> None:
        self._end_of_track = bool(value)

    def get_remaining(self) -> int:
        """Return the number of seconds remaining, or 0 if inactive."""
        return self._remaining_seconds if self._active else 0

    def start(self, minutes: int) -> None:
        """Start (or restart) the timer for *minutes* minutes.

        If the timer is already running it is cancelled and restarted.
        """
        if minutes < 1:
            raise ValueError("Duration must be at least 1 minute")

        # Cancel any running timer first.
        if self._active:
            self._cancel_sources()

        self._remaining_seconds = minutes * 60
        self._active = True
        self._fading = False
        self._end_of_track = False

        self._schedule_tick()

    def cancel(self) -> None:
        """Cancel the running timer (no-op if not active)."""
        was_fading = self._fading
        self._cancel_sources()
        self._active = False
        self._remaining_seconds = 0
        self._fading = False
        self._end_of_track = False

        # If we were fading, restore volume.
        if was_fading and self._on_fade_step is not None:
            self._on_fade_step(1.0)

    def start_end_of_track(self) -> None:
        """Activate "end of current track" mode.

        The actual stopping is handled externally (the player checks
        this flag at end-of-stream).
        """
        if self._active:
            self._cancel_sources()

        self._active = True
        self._end_of_track = True
        self._remaining_seconds = 0
        self._fading = False

    # ------------------------------------------------------------------
    # Preset helpers
    # ------------------------------------------------------------------

    @staticmethod
    def get_preset_durations() -> list[int]:
        """Return the list of preset durations (minutes)."""
        return list(PRESET_DURATIONS)

    @staticmethod
    def format_remaining(seconds: int) -> str:
        """Format *seconds* as ``MM:SS``."""
        mins = seconds // 60
        secs = seconds % 60
        return f"{mins:02d}:{secs:02d}"

    # ------------------------------------------------------------------
    # Internal scheduling
    # ------------------------------------------------------------------

    def _schedule_tick(self) -> None:
        """Schedule the once-per-second tick using GLib."""
        try:
            from gi.repository import GLib

            self._tick_source_id = GLib.timeout_add_seconds(
                1, self._on_tick_internal
            )
        except Exception:
            logger.warning(
                "GLib not available; sleep timer tick not scheduled",
                exc_info=True,
            )

    def _schedule_fade(self) -> None:
        """Schedule the volume fade-out ramp using GLib."""
        try:
            from gi.repository import GLib

            self._fading = True
            self._fade_source_id = GLib.timeout_add(
                FADE_STEP_INTERVAL_MS, self._on_fade_step_internal
            )
        except Exception:
            logger.warning(
                "GLib not available; fade-out not scheduled",
                exc_info=True,
            )

    def _cancel_sources(self) -> None:
        """Remove any pending GLib timeout sources."""
        try:
            from gi.repository import GLib

            if self._tick_source_id is not None:
                GLib.source_remove(self._tick_source_id)
                self._tick_source_id = None
            if self._fade_source_id is not None:
                GLib.source_remove(self._fade_source_id)
                self._fade_source_id = None
        except Exception:
            self._tick_source_id = None
            self._fade_source_id = None

    # ------------------------------------------------------------------
    # GLib callbacks
    # ------------------------------------------------------------------

    def _on_tick_internal(self) -> bool:
        """Called every second by the GLib main loop.

        Returns ``True`` to keep the timer running, ``False`` to stop.
        """
        if not self._active:
            self._tick_source_id = None
            return False

        self._remaining_seconds -= 1

        # Notify the UI.
        if self._on_tick is not None:
            try:
                self._on_tick(self._remaining_seconds)
            except Exception:
                logger.warning("on_tick callback failed", exc_info=True)

        # Should we start fading?
        if (
            self._fade_out_enabled
            and not self._fading
            and self._remaining_seconds <= FADE_DURATION_SECONDS
            and self._remaining_seconds > 0
        ):
            self._schedule_fade()

        # Timer expired.
        if self._remaining_seconds <= 0:
            self._active = False
            self._tick_source_id = None
            self._expire()
            return False

        return True

    def _on_fade_step_internal(self) -> bool:
        """Called every FADE_STEP_INTERVAL_MS ms during fade-out.

        Returns ``True`` to keep running, ``False`` when done.
        """
        if not self._active or not self._fading:
            self._fade_source_id = None
            return False

        remaining = max(0, self._remaining_seconds)
        if remaining <= 0:
            fraction = 0.0
        else:
            fraction = remaining / FADE_DURATION_SECONDS
            fraction = max(0.0, min(1.0, fraction))

        if self._on_fade_step is not None:
            try:
                self._on_fade_step(fraction)
            except Exception:
                logger.warning(
                    "on_fade_step callback failed", exc_info=True
                )

        if remaining <= 0:
            self._fade_source_id = None
            return False

        return True

    def _expire(self) -> None:
        """Handle timer expiry: cancel fade, invoke the on_expire callback."""
        self._cancel_sources()
        self._fading = False

        if self._on_expire is not None:
            try:
                self._on_expire()
            except Exception:
                logger.warning(
                    "on_expire callback failed", exc_info=True
                )
