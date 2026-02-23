"""Crossfade service for the Auxen music player.

Provides smooth volume crossfade transitions between tracks.  When
enabled, the current track fades out over a configurable duration and
the next track fades in.  Uses ``GLib.timeout_add`` with a 50 ms
interval for smooth volume stepping.

When disabled, the existing gapless playback behaviour is preserved
unchanged.
"""

from __future__ import annotations

import logging
from typing import Callable, Optional

logger = logging.getLogger(__name__)

# Interval between volume steps during fade (milliseconds).
FADE_STEP_INTERVAL_MS: int = 50

# Default crossfade duration in seconds.
DEFAULT_DURATION: float = 5.0

# Allowed duration range (seconds).
MIN_DURATION: float = 1.0
MAX_DURATION: float = 12.0


class CrossfadeService:
    """Crossfade transition manager.

    Properties
    ----------
    enabled : bool
        Whether crossfade is active.
    duration : float
        Crossfade duration in seconds (clamped to 1--12).
    """

    def __init__(self) -> None:
        self._enabled: bool = False
        self._duration: float = DEFAULT_DURATION

        # Active fade state.
        self._fade_source_id: Optional[int] = None
        self._fading: bool = False
        self._fade_direction: Optional[str] = None  # "in" or "out"
        self._fade_step_count: int = 0
        self._fade_total_steps: int = 0
        self._fade_start_volume: float = 0.0
        self._fade_target_volume: float = 0.0
        self._fade_callback: Optional[Callable[[], None]] = None
        self._fade_player: object | None = None

    # ------------------------------------------------------------------
    # Public properties
    # ------------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        """Whether crossfade transitions are enabled."""
        return self._enabled

    @property
    def duration(self) -> float:
        """Crossfade duration in seconds (1--12)."""
        return self._duration

    # ------------------------------------------------------------------
    # Configuration
    # ------------------------------------------------------------------

    def set_enabled(self, enabled: bool) -> None:
        """Enable or disable crossfade transitions."""
        self._enabled = bool(enabled)
        if not self._enabled:
            self.cancel()

    def set_duration(self, seconds: float) -> None:
        """Set the crossfade duration, clamped to 1--12 seconds."""
        self._duration = max(MIN_DURATION, min(MAX_DURATION, float(seconds)))

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    def to_dict(self) -> dict:
        """Serialize current settings to a dictionary."""
        return {
            "enabled": self._enabled,
            "duration": self._duration,
        }

    def from_dict(self, data: dict) -> None:
        """Restore settings from a dictionary.

        Unknown keys are silently ignored.
        """
        if "enabled" in data:
            self.set_enabled(bool(data["enabled"]))
        if "duration" in data:
            self.set_duration(float(data["duration"]))

    # ------------------------------------------------------------------
    # Fade operations
    # ------------------------------------------------------------------

    def start_fade_out(
        self,
        player: object,
        callback_on_complete: Optional[Callable[[], None]] = None,
    ) -> None:
        """Ramp volume from current level to 0 over the configured duration.

        Parameters
        ----------
        player:
            Object with a ``volume`` property (float 0--1).
        callback_on_complete:
            Called when the fade-out finishes.
        """
        if not self._enabled:
            return

        self.cancel()

        current_volume = getattr(player, "volume", 0.7)
        total_steps = max(1, int(self._duration * 1000 / FADE_STEP_INTERVAL_MS))

        self._fade_player = player
        self._fade_callback = callback_on_complete
        self._fade_direction = "out"
        self._fade_start_volume = current_volume
        self._fade_target_volume = 0.0
        self._fade_step_count = 0
        self._fade_total_steps = total_steps
        self._fading = True

        self._schedule_fade()

    def start_fade_in(
        self,
        player: object,
        target_volume: float,
    ) -> None:
        """Ramp volume from 0 to *target_volume* over the configured duration.

        Parameters
        ----------
        player:
            Object with a ``volume`` property (float 0--1).
        target_volume:
            The final volume to reach.
        """
        if not self._enabled:
            return

        self.cancel()

        total_steps = max(1, int(self._duration * 1000 / FADE_STEP_INTERVAL_MS))

        self._fade_player = player
        self._fade_callback = None
        self._fade_direction = "in"
        self._fade_start_volume = 0.0
        self._fade_target_volume = max(0.0, min(1.0, target_volume))
        self._fade_step_count = 0
        self._fade_total_steps = total_steps
        self._fading = True

        # Set volume to 0 immediately before starting fade-in.
        try:
            player.volume = 0.0
        except Exception:
            logger.warning("Failed to set initial volume for fade-in", exc_info=True)

        self._schedule_fade()

    def cancel(self) -> None:
        """Cancel any active fade and reset internal state."""
        self._cancel_source()
        self._fading = False
        self._fade_direction = None
        self._fade_step_count = 0
        self._fade_total_steps = 0
        self._fade_start_volume = 0.0
        self._fade_target_volume = 0.0
        self._fade_callback = None
        self._fade_player = None

    # ------------------------------------------------------------------
    # State queries
    # ------------------------------------------------------------------

    @property
    def is_fading(self) -> bool:
        """Return ``True`` if a fade is currently in progress."""
        return self._fading

    @property
    def fade_direction(self) -> Optional[str]:
        """Return ``'in'``, ``'out'``, or ``None``."""
        return self._fade_direction

    # ------------------------------------------------------------------
    # Internal scheduling
    # ------------------------------------------------------------------

    def _schedule_fade(self) -> None:
        """Schedule the volume fade using GLib."""
        try:
            from gi.repository import GLib

            self._fade_source_id = GLib.timeout_add(
                FADE_STEP_INTERVAL_MS, self._on_fade_step
            )
        except Exception:
            logger.warning(
                "GLib not available; crossfade step not scheduled",
                exc_info=True,
            )

    def _cancel_source(self) -> None:
        """Remove the pending GLib timeout source."""
        try:
            from gi.repository import GLib

            if self._fade_source_id is not None:
                GLib.source_remove(self._fade_source_id)
                self._fade_source_id = None
        except Exception:
            self._fade_source_id = None

    # ------------------------------------------------------------------
    # GLib callback
    # ------------------------------------------------------------------

    def _on_fade_step(self) -> bool:
        """Called every FADE_STEP_INTERVAL_MS ms during a fade.

        Returns ``True`` to keep the timer running, ``False`` when done.
        """
        if not self._fading or self._fade_player is None:
            self._fade_source_id = None
            return False

        self._fade_step_count += 1
        progress = min(1.0, self._fade_step_count / self._fade_total_steps)

        # Linear interpolation between start and target volumes.
        volume = (
            self._fade_start_volume
            + (self._fade_target_volume - self._fade_start_volume) * progress
        )
        volume = max(0.0, min(1.0, volume))

        try:
            self._fade_player.volume = volume
        except Exception:
            logger.warning("Failed to set volume during crossfade", exc_info=True)

        if progress >= 1.0:
            # Fade complete.
            self._fading = False
            self._fade_source_id = None
            callback = self._fade_callback
            self._fade_callback = None
            self._fade_direction = None
            self._fade_player = None

            if callback is not None:
                try:
                    callback()
                except Exception:
                    logger.warning(
                        "Crossfade completion callback failed", exc_info=True
                    )
            return False

        return True
