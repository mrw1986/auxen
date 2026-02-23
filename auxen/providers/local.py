"""Local media provider — discovers and reads metadata from audio files."""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Optional
from urllib.parse import quote

import mutagen
from mutagen import File as MutagenFile

from ..models import Source, Track
from .base import ContentProvider

logger = logging.getLogger(__name__)

# Extensions recognised as audio files.
SUPPORTED_EXTENSIONS: set[str] = {
    ".flac",
    ".mp3",
    ".aac",
    ".m4a",
    ".ogg",
    ".opus",
    ".wav",
    ".wma",
    ".alac",
    ".aif",
    ".aiff",
}

# Map from extension to canonical format name used in Track.format.
FORMAT_MAP: dict[str, str] = {
    ".flac": "FLAC",
    ".mp3": "MP3",
    ".aac": "AAC",
    ".m4a": "AAC",
    ".ogg": "OGG",
    ".opus": "OPUS",
    ".wav": "WAV",
    ".wma": "WMA",
    ".alac": "ALAC",
    ".aif": "ALAC",
    ".aiff": "ALAC",
}


class LocalProvider(ContentProvider):
    """Scans local directories for audio files and reads their metadata."""

    def __init__(self, music_dirs: list[str]) -> None:
        self._music_dirs = music_dirs

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def scan(self) -> list[Track]:
        """Walk all configured directories and return a Track for every audio file found."""
        tracks: list[Track] = []
        for directory in self._music_dirs:
            if not os.path.isdir(directory):
                logger.warning("Music directory does not exist: %s", directory)
                continue
            for root, _dirs, files in os.walk(directory):
                for filename in files:
                    filepath = os.path.join(root, filename)
                    ext = os.path.splitext(filename)[1].lower()
                    if ext not in SUPPORTED_EXTENSIONS:
                        continue
                    track = self._file_to_track(filepath)
                    if track is not None:
                        tracks.append(track)
        return tracks

    def search(self, query: str) -> list[Track]:
        """Return an empty list — search is handled by the DB layer."""
        return []

    def get_stream_uri(self, track: Track) -> str:
        """Return a ``file://`` URI for the given local track."""
        return "file://" + quote(track.source_id, safe="/")

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _file_to_track(self, filepath: str) -> Optional[Track]:
        """Read metadata from *filepath* and return a Track, or None on failure."""
        try:
            audio = MutagenFile(filepath, easy=True)
        except Exception:
            logger.warning("Could not read metadata from %s", filepath, exc_info=True)
            return None

        ext = os.path.splitext(filepath)[1].lower()
        fmt = FORMAT_MAP.get(ext, ext.lstrip(".").upper())

        # --- Extract tags (mutagen returns lists) ---
        title = _first_tag(audio, "title")
        artist = _first_tag(audio, "artist")
        album = _first_tag(audio, "album")
        album_artist = _first_tag(audio, "albumartist")
        genre = _first_tag(audio, "genre")

        # Year — mutagen stores in 'date'; take first 4 chars.
        raw_date = _first_tag(audio, "date")
        year: Optional[int] = None
        if raw_date and len(raw_date) >= 4:
            try:
                year = int(raw_date[:4])
            except ValueError:
                pass

        # Track / disc number — may be "3/12".
        track_number = _parse_number_tag(audio, "tracknumber")
        disc_number = _parse_number_tag(audio, "discnumber")

        # --- Audio info from mutagen.info ---
        duration: Optional[float] = None
        bitrate: Optional[int] = None
        sample_rate: Optional[int] = None
        bit_depth: Optional[int] = None

        if audio is not None and audio.info is not None:
            info = audio.info
            duration = getattr(info, "length", None)
            raw_bitrate = getattr(info, "bitrate", None)
            if raw_bitrate is not None:
                # mutagen reports bitrate in bps; convert to kbps
                bitrate = raw_bitrate // 1000 if raw_bitrate > 1000 else raw_bitrate
            sample_rate = getattr(info, "sample_rate", None)
            bit_depth = getattr(info, "bits_per_sample", None)

        # --- Fallbacks: infer artist / album from directory structure ---
        # Expected layout: .../Artist/Album/file.ext
        path = Path(filepath)
        if not title:
            # Use filename without extension as title
            title = path.stem
        if not artist:
            # Parent of parent is the artist dir
            try:
                artist = path.parent.parent.name or "Unknown Artist"
            except Exception:
                artist = "Unknown Artist"
        if not album:
            try:
                album = path.parent.name or None
            except Exception:
                pass

        return Track(
            title=title,
            artist=artist,
            source=Source.LOCAL,
            source_id=filepath,
            album=album,
            album_artist=album_artist,
            genre=genre,
            year=year,
            duration=duration,
            track_number=track_number,
            disc_number=disc_number,
            bitrate=bitrate,
            format=fmt,
            sample_rate=sample_rate,
            bit_depth=bit_depth,
        )


# ------------------------------------------------------------------
# Module-level helpers
# ------------------------------------------------------------------


def _first_tag(audio: Optional[mutagen.FileType], key: str) -> Optional[str]:
    """Return the first value for *key*, or None."""
    if audio is None:
        return None
    values = audio.get(key)
    if values and isinstance(values, list):
        return str(values[0])
    return None


def _parse_number_tag(audio: Optional[mutagen.FileType], key: str) -> Optional[int]:
    """Parse a track/disc number tag that might be '3/12'."""
    raw = _first_tag(audio, key)
    if raw is None:
        return None
    try:
        return int(raw.split("/")[0])
    except (ValueError, IndexError):
        return None
