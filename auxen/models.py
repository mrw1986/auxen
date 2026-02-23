"""Data models for the Auxen music player."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class Source(Enum):
    """Where a track originates from."""

    LOCAL = "local"
    TIDAL = "tidal"


class SourcePriority(Enum):
    """User preference for which source to play when duplicates exist."""

    PREFER_LOCAL = "prefer_local"
    PREFER_TIDAL = "prefer_tidal"
    PREFER_QUALITY = "prefer_quality"
    ALWAYS_ASK = "always_ask"


# Lossless formats that score based on bit depth / sample rate.
_LOSSLESS_FORMATS = frozenset({"FLAC", "WAV", "ALAC"})

# Lossy formats whose score depends on bitrate.
_LOSSY_HQ_FORMATS = frozenset({"AAC", "OGG", "OPUS"})


@dataclass
class Track:
    """A single music track, either local or from Tidal."""

    # Required fields
    title: str
    artist: str
    source: Source
    source_id: str

    # Optional metadata
    album: Optional[str] = None
    album_artist: Optional[str] = None
    genre: Optional[str] = None
    year: Optional[int] = None
    duration: Optional[float] = None
    track_number: Optional[int] = None
    disc_number: Optional[int] = None
    bitrate: Optional[int] = None
    format: Optional[str] = None
    sample_rate: Optional[int] = None
    bit_depth: Optional[int] = None
    album_art_url: Optional[str] = None
    match_group_id: Optional[str] = None

    # Database fields
    id: Optional[int] = None
    added_at: Optional[str] = None
    last_played_at: Optional[str] = None
    play_count: int = field(default=0)

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def is_local(self) -> bool:
        """True when the track comes from a local file."""
        return self.source == Source.LOCAL

    @property
    def is_tidal(self) -> bool:
        """True when the track comes from Tidal."""
        return self.source == Source.TIDAL

    @property
    def quality_score(self) -> int:
        """Return an integer quality score for comparison.

        Higher is better:
            1000 — Hi-Res FLAC (24-bit, 96 kHz+)
             500 — FLAC / WAV / ALAC 16-bit (or unknown depth)
             300 — AAC / OGG / OPUS 320 kbps
             250 — MP3 320 kbps
             200 — AAC / OGG / OPUS < 320 kbps
             100 — MP3 < 320 kbps (or unknown bitrate)
               0 — Unknown / unsupported format
        """
        fmt = (self.format or "").upper()

        if fmt in _LOSSLESS_FORMATS:
            if (
                self.bit_depth is not None
                and self.bit_depth >= 24
                and self.sample_rate is not None
                and self.sample_rate >= 96000
            ):
                return 1000
            return 500

        if fmt in _LOSSY_HQ_FORMATS:
            if self.bitrate is not None and self.bitrate >= 320:
                return 300
            return 200

        if fmt == "MP3":
            if self.bitrate is not None and self.bitrate >= 320:
                return 250
            return 100

        return 0

    @property
    def quality_label(self) -> str:
        """Return a human-readable quality label."""
        fmt = (self.format or "").upper()

        if fmt in _LOSSLESS_FORMATS:
            if (
                self.bit_depth is not None
                and self.bit_depth >= 24
                and self.sample_rate is not None
                and self.sample_rate >= 96000
            ):
                return "Hi-Res"
            return fmt

        if fmt in _LOSSY_HQ_FORMATS:
            return fmt

        if fmt == "MP3":
            return "MP3"

        return "Unknown"
