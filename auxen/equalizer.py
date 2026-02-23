"""10-band graphic equalizer service for the Auxen music player.

Wraps GStreamer's ``equalizer-10bands`` element, providing preset
management, per-band gain control, enable/disable toggle, and
serialisation helpers for database persistence.
"""

from __future__ import annotations

import logging
from typing import Callable, Optional

logger = logging.getLogger(__name__)

# Standard 10-band ISO frequencies (Hz)
BAND_FREQUENCIES: list[str] = [
    "31", "62", "125", "250", "500",
    "1k", "2k", "4k", "8k", "16k",
]

NUM_BANDS: int = 10
MIN_GAIN_DB: float = -12.0
MAX_GAIN_DB: float = 12.0
DEFAULT_GAIN_DB: float = 0.0

# Built-in presets: name -> list of 10 gain values (dB)
PRESETS: dict[str, list[float]] = {
    "Flat": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    "Bass Boost": [6, 5, 4, 2, 0, 0, 0, 0, 0, 0],
    "Treble Boost": [0, 0, 0, 0, 0, 0, 2, 4, 5, 6],
    "Vocal": [-2, -1, 0, 2, 4, 4, 2, 0, -1, -2],
    "Rock": [4, 3, 1, 0, -1, -1, 0, 2, 3, 4],
    "Pop": [-1, 0, 2, 4, 4, 2, 0, -1, -2, -2],
    "Jazz": [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
    "Classical": [0, 0, 0, 0, 0, 0, -1, -2, -2, -4],
    "Electronic": [5, 4, 2, 0, -1, 0, 1, 3, 4, 5],
    "Hip-Hop": [5, 4, 2, 0, -1, 0, 1, 0, 2, 3],
}


def _clamp_gain(value: float) -> float:
    """Clamp a gain value to the allowed range."""
    return max(MIN_GAIN_DB, min(MAX_GAIN_DB, float(value)))


class Equalizer:
    """10-band graphic equalizer with preset support.

    Parameters
    ----------
    on_band_changed:
        Optional callback ``(band_index: int, gain_db: float) -> None``
        invoked whenever a band value changes.  Used to push updates
        to the GStreamer pipeline in real time.
    """

    def __init__(
        self,
        on_band_changed: Optional[Callable[[int, float], None]] = None,
    ) -> None:
        self._bands: list[float] = [DEFAULT_GAIN_DB] * NUM_BANDS
        self._enabled: bool = True
        self._on_band_changed = on_band_changed

    # ------------------------------------------------------------------
    # Band getters / setters
    # ------------------------------------------------------------------

    def get_bands(self) -> list[float]:
        """Return a copy of the current band gain values."""
        return list(self._bands)

    def set_band(self, index: int, gain_db: float) -> None:
        """Set the gain for a single band.

        The value is clamped to [-12, +12] dB.  If the equalizer is
        enabled, the ``on_band_changed`` callback is fired.
        """
        if not 0 <= index < NUM_BANDS:
            raise IndexError(
                f"Band index {index} out of range (0..{NUM_BANDS - 1})"
            )
        clamped = _clamp_gain(gain_db)
        self._bands[index] = clamped
        if self._enabled and self._on_band_changed is not None:
            self._on_band_changed(index, clamped)

    def set_bands(self, gains: list[float]) -> None:
        """Set all 10 bands at once.

        *gains* must contain exactly 10 values.  Each value is clamped
        to [-12, +12] dB.
        """
        if len(gains) != NUM_BANDS:
            raise ValueError(
                f"Expected {NUM_BANDS} gain values, got {len(gains)}"
            )
        for i, g in enumerate(gains):
            self.set_band(i, g)

    # ------------------------------------------------------------------
    # Presets
    # ------------------------------------------------------------------

    def apply_preset(self, name: str) -> None:
        """Apply a named preset.

        Raises ``KeyError`` if *name* is not a known preset.
        """
        if name not in PRESETS:
            raise KeyError(f"Unknown preset: {name!r}")
        self.set_bands(list(PRESETS[name]))

    def get_preset_names(self) -> list[str]:
        """Return the ordered list of available preset names."""
        return list(PRESETS.keys())

    # ------------------------------------------------------------------
    # Enable / disable
    # ------------------------------------------------------------------

    def is_enabled(self) -> bool:
        """Return whether the equalizer is currently active."""
        return self._enabled

    def set_enabled(self, enabled: bool) -> None:
        """Enable or disable the equalizer.

        When *enabled* is ``True``, all current band values are pushed
        to the pipeline.  When ``False``, all bands are set to 0 dB
        (flat) in the pipeline without altering the stored values.
        """
        self._enabled = bool(enabled)
        if self._on_band_changed is not None:
            for i in range(NUM_BANDS):
                if self._enabled:
                    self._on_band_changed(i, self._bands[i])
                else:
                    self._on_band_changed(i, DEFAULT_GAIN_DB)

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    def to_dict(self) -> dict:
        """Serialise the equalizer state to a plain dict."""
        return {
            "enabled": self._enabled,
            "bands": list(self._bands),
        }

    def from_dict(self, data: dict) -> None:
        """Restore equalizer state from a dict produced by ``to_dict``.

        Unknown keys are silently ignored; missing keys keep their
        current values.
        """
        if "enabled" in data:
            self.set_enabled(bool(data["enabled"]))
        if "bands" in data:
            bands = data["bands"]
            if isinstance(bands, list) and len(bands) == NUM_BANDS:
                self.set_bands([float(g) for g in bands])
