"""Local media provider — discovers and reads metadata from audio files."""

from __future__ import annotations

import hashlib
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

# Directory for caching extracted embedded album art.
_ART_CACHE_DIR = os.path.join(
    os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")),
    "auxen",
    "embedded_art",
)

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

        # --- Extract and cache embedded album art ---
        album_art_url = _extract_and_cache_art(filepath, artist, album)

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
            album_art_url=album_art_url,
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


def _extract_and_cache_art(
    filepath: str,
    artist: Optional[str],
    album: Optional[str],
) -> Optional[str]:
    """Extract embedded cover art from *filepath* and save to the disk cache.

    Returns the absolute path to the cached JPEG, or ``None`` if the file
    has no embedded art.  Uses a hash of ``artist + album`` as the cache
    filename so that tracks from the same album share one cached image.
    """
    # Build a stable cache key from artist+album so all tracks in the
    # same album reuse a single cached image file.
    cache_key = f"{artist or ''}\x00{album or ''}"
    hexdigest = hashlib.sha256(cache_key.encode("utf-8")).hexdigest()[:24]
    cache_path = os.path.join(_ART_CACHE_DIR, f"{hexdigest}.jpg")

    # If we already extracted art for this album, reuse it.
    if os.path.isfile(cache_path):
        return cache_path

    # Extract embedded art bytes using the same helpers as AlbumArtService.
    art_bytes = _extract_embedded_art_bytes(filepath)
    if art_bytes is None:
        return None

    try:
        os.makedirs(_ART_CACHE_DIR, exist_ok=True)

        # Write to a temp file first, then rename for atomicity.
        tmp_path = cache_path + ".tmp"
        with open(tmp_path, "wb") as fh:
            fh.write(art_bytes)
        os.replace(tmp_path, cache_path)

        logger.debug("Cached embedded art for %s - %s -> %s", artist, album, cache_path)
        return cache_path
    except Exception:
        logger.debug("Failed to cache embedded art from %s", filepath, exc_info=True)
        # Clean up partial write
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        return None


def _extract_embedded_art_bytes(filepath: str) -> Optional[bytes]:
    """Return raw image bytes from the embedded art tag of *filepath*.

    Supports FLAC, MP3 (ID3), OGG/Opus, and M4A/AAC/MP4.
    """
    ext = os.path.splitext(filepath)[1].lower()
    try:
        if ext == ".flac":
            from mutagen.flac import FLAC

            audio = FLAC(filepath)
            if audio.pictures:
                return bytes(audio.pictures[0].data)

        elif ext == ".mp3":
            from mutagen.id3 import ID3

            tags = ID3(filepath)
            for key, frame in tags.items():
                if key.startswith("APIC") and frame.data:
                    return bytes(frame.data)

        elif ext in (".ogg", ".opus"):
            audio = MutagenFile(filepath)
            if audio is None:
                return None
            pictures = getattr(audio, "pictures", None)
            if pictures:
                return bytes(pictures[0].data)
            # Fallback: METADATA_BLOCK_PICTURE tag
            if audio.tags:
                import base64

                from mutagen.flac import Picture

                for b64_data in audio.tags.get("METADATA_BLOCK_PICTURE", []):
                    try:
                        pic = Picture(base64.b64decode(b64_data))
                        if pic.data:
                            return bytes(pic.data)
                    except Exception:
                        pass

        elif ext in (".m4a", ".aac", ".mp4"):
            from mutagen.mp4 import MP4

            audio = MP4(filepath)
            if audio.tags is not None:
                covers = audio.tags.get("covr")
                if covers:
                    return bytes(covers[0])
    except Exception:
        logger.debug(
            "Failed to extract embedded art from %s", filepath, exc_info=True
        )
    return None
