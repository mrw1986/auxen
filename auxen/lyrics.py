"""Lyrics fetching service for the Auxen music player.

Retrieves lyrics from the Tidal API (for Tidal tracks), embedded
metadata (FLAC, MP3, M4A/AAC), and sidecar .lrc files.  Results are
cached in memory to avoid re-reading.
"""

from __future__ import annotations

import logging
import os
import threading
from pathlib import Path
from typing import Callable, Optional

from auxen.models import Source, Track

logger = logging.getLogger(__name__)


class LyricsService:
    """Fetch lyrics from embedded tags, sidecar LRC files, or Tidal.

    Lookup order:
        1. In-memory cache
        2. For Tidal tracks: Tidal lyrics API (falls back to embedded/LRC)
        3. Embedded lyrics in the audio file (FLAC / MP3 / M4A)
        4. Sidecar ``.lrc`` file next to the audio file
    """

    def __init__(self) -> None:
        self._cache: dict[tuple[str, str], Optional[str]] = {}
        self._lock = threading.Lock()
        self._tidal_provider = None

    def set_tidal_provider(self, provider) -> None:
        """Wire a TidalProvider so lyrics can be fetched for Tidal tracks."""
        self._tidal_provider = provider

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_lyrics(self, track: Track) -> Optional[str]:
        """Return lyrics text for *track*, or ``None`` if unavailable."""
        cache_key = (track.title, track.artist)

        with self._lock:
            if cache_key in self._cache:
                return self._cache[cache_key]

        lyrics = self._fetch_lyrics(track)

        with self._lock:
            self._cache[cache_key] = lyrics

        return lyrics

    def get_lyrics_async(
        self,
        track: Track,
        callback: Callable[[Optional[str]], None],
    ) -> None:
        """Fetch lyrics in a background thread, then invoke *callback*.

        The *callback* is scheduled on the GLib main loop so it is safe
        to update GTK widgets from it.
        """

        def _worker() -> None:
            result = self.get_lyrics(track)
            try:
                from gi.repository import GLib

                GLib.idle_add(callback, result)
            except Exception:
                # If GLib is unavailable (e.g. in tests), call directly.
                callback(result)

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()

    def clear_cache(self) -> None:
        """Remove all cached lyrics."""
        with self._lock:
            self._cache.clear()

    # ------------------------------------------------------------------
    # Internal — lyrics fetching
    # ------------------------------------------------------------------

    def _fetch_lyrics(self, track: Track) -> Optional[str]:
        """Try Tidal lyrics (for Tidal tracks), embedded metadata, then sidecar LRC."""
        # Tidal tracks: try the Tidal lyrics API first
        if track.source == Source.TIDAL:
            tidal_lyrics = self._fetch_tidal_lyrics(track)
            if tidal_lyrics:
                return tidal_lyrics
            return None

        # Local tracks: embedded metadata, then sidecar LRC
        file_path = track.source_id
        if not file_path or not os.path.isfile(file_path):
            return None

        # 1. Embedded lyrics
        embedded = self._read_embedded_lyrics(file_path)
        if embedded:
            return embedded

        # 2. Sidecar .lrc file
        lrc = self._read_lrc_file(file_path)
        if lrc:
            return lrc

        return None

    def _fetch_tidal_lyrics(self, track: Track) -> Optional[str]:
        """Fetch lyrics from the Tidal API for a Tidal track."""
        if self._tidal_provider is None:
            return None

        try:
            result = self._tidal_provider.get_lyrics(track.source_id)
            if result is None:
                return None

            # Prefer plain text lyrics; fall back to subtitles (timed lyrics)
            text = result.get("text", "")
            if text and text.strip():
                return text.strip()

            subtitles = result.get("subtitles", "")
            if subtitles and subtitles.strip():
                return subtitles.strip()
        except Exception:
            logger.debug(
                "Failed to fetch Tidal lyrics for %s", track.title,
                exc_info=True,
            )

        return None

    # ------------------------------------------------------------------
    # Embedded lyrics readers
    # ------------------------------------------------------------------

    @staticmethod
    def _read_embedded_lyrics(file_path: str) -> Optional[str]:
        """Extract lyrics from embedded tags using mutagen."""
        try:
            import mutagen
            from mutagen.flac import FLAC
            from mutagen.id3 import ID3
            from mutagen.mp4 import MP4

            ext = Path(file_path).suffix.lower()

            if ext == ".flac":
                return LyricsService._read_flac_lyrics(file_path)
            if ext == ".mp3":
                return LyricsService._read_mp3_lyrics(file_path)
            if ext in (".m4a", ".aac", ".mp4"):
                return LyricsService._read_m4a_lyrics(file_path)

        except Exception:
            logger.debug(
                "Failed to read embedded lyrics from %s", file_path,
                exc_info=True,
            )
        return None

    @staticmethod
    def _read_flac_lyrics(file_path: str) -> Optional[str]:
        """Read lyrics from FLAC Vorbis comments."""
        try:
            from mutagen.flac import FLAC

            audio = FLAC(file_path)
            if audio.tags is None:
                return None

            # Try LYRICS first, then UNSYNCEDLYRICS
            for tag_name in ("LYRICS", "lyrics", "UNSYNCEDLYRICS", "unsyncedlyrics"):
                values = audio.tags.get(tag_name)
                if values:
                    text = values[0] if isinstance(values, list) else str(values)
                    text = str(text).strip()
                    if text:
                        return text
        except Exception:
            logger.debug("Failed to read FLAC lyrics from %s", file_path)
        return None

    @staticmethod
    def _read_mp3_lyrics(file_path: str) -> Optional[str]:
        """Read lyrics from MP3 ID3 USLT frames."""
        try:
            from mutagen.id3 import ID3

            tags = ID3(file_path)
            # USLT frames are keyed like "USLT::eng" or just "USLT"
            for key, frame in tags.items():
                if key.startswith("USLT"):
                    text = str(frame.text).strip()
                    if text:
                        return text
        except Exception:
            logger.debug("Failed to read MP3 lyrics from %s", file_path)
        return None

    @staticmethod
    def _read_m4a_lyrics(file_path: str) -> Optional[str]:
        """Read lyrics from M4A/AAC/MP4 atoms."""
        try:
            from mutagen.mp4 import MP4

            audio = MP4(file_path)
            if audio.tags is None:
                return None

            lyrics_values = audio.tags.get("\xa9lyr")
            if lyrics_values:
                text = lyrics_values[0] if isinstance(lyrics_values, list) else str(lyrics_values)
                text = str(text).strip()
                if text:
                    return text
        except Exception:
            logger.debug("Failed to read M4A lyrics from %s", file_path)
        return None

    # ------------------------------------------------------------------
    # Sidecar LRC file
    # ------------------------------------------------------------------

    @staticmethod
    def _read_lrc_file(audio_path: str) -> Optional[str]:
        """Look for a ``.lrc`` file alongside the audio file."""
        lrc_path = Path(audio_path).with_suffix(".lrc")
        if not lrc_path.is_file():
            return None

        try:
            text = lrc_path.read_text(encoding="utf-8").strip()
            if text:
                return text
        except Exception:
            logger.debug("Failed to read LRC file %s", lrc_path)
        return None
