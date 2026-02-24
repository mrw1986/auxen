"""M3U/M3U8 playlist import and export for the Auxen music player."""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import TYPE_CHECKING

from auxen.models import Source, Track

if TYPE_CHECKING:
    from auxen.db import Database

logger = logging.getLogger(__name__)

# Tidal tracks are written with this comment prefix so they can be
# round-tripped but won't break players that only understand file paths.
_TIDAL_URI_PREFIX = "# tidal://"


class M3UService:
    """Import and export playlists in M3U/M3U8 format."""

    # ------------------------------------------------------------------
    # Export
    # ------------------------------------------------------------------

    def export_playlist(
        self,
        tracks: list[Track],
        filepath: str,
        extended: bool = True,
        db: Database | None = None,
    ) -> None:
        """Write *tracks* to an M3U file at *filepath*.

        Parameters
        ----------
        tracks:
            The ordered list of tracks to write.
        filepath:
            Destination file path.  If it ends with ``.m3u8`` the file
            is written as UTF-8; otherwise Latin-1 is attempted with a
            UTF-8 fallback.
        extended:
            When ``True`` (the default), write ``#EXTM3U`` and
            ``#EXTINF`` lines.
        db:
            Optional database used to resolve local file paths.
        """
        content = self._build_m3u_string(tracks, extended=extended, db=db)
        encoding = self._encoding_for(filepath)

        with open(filepath, "w", encoding=encoding, errors="replace") as fh:
            fh.write(content)

    def export_to_string(
        self,
        tracks: list[Track],
        extended: bool = True,
        db: Database | None = None,
    ) -> str:
        """Return the M3U content as a string (always UTF-8 safe)."""
        return self._build_m3u_string(tracks, extended=extended, db=db)

    # ------------------------------------------------------------------
    # Import
    # ------------------------------------------------------------------

    def import_playlist(
        self,
        filepath: str,
        db: Database,
    ) -> list[Track]:
        """Parse an M3U/M3U8 file and return matched Track objects.

        Tracks are looked up in the database by file path (for local
        tracks) or by artist/title (as a fallback).  Unmatched entries
        are silently skipped.
        """
        encoding = self._encoding_for(filepath)
        try:
            with open(filepath, "r", encoding=encoding) as fh:
                content = fh.read()
        except UnicodeDecodeError:
            # Fall back to UTF-8 for .m3u files that are actually UTF-8
            with open(filepath, "r", encoding="utf-8") as fh:
                content = fh.read()

        base_dir = str(Path(filepath).resolve().parent)
        return self._parse_m3u_string(content, db, base_dir=base_dir)

    def import_from_string(
        self,
        content: str,
        db: Database,
        base_dir: str | None = None,
    ) -> list[Track]:
        """Parse M3U content from a string and return matched tracks."""
        return self._parse_m3u_string(content, db, base_dir=base_dir)

    # ------------------------------------------------------------------
    # Internal — export helpers
    # ------------------------------------------------------------------

    def _build_m3u_string(
        self,
        tracks: list[Track],
        extended: bool = True,
        db: Database | None = None,
    ) -> str:
        """Build the M3U text for *tracks*."""
        if not tracks:
            return ""

        lines: list[str] = []

        if extended:
            lines.append("#EXTM3U")

        def _safe(value: str | None) -> str:
            """Strip line breaks to prevent M3U line injection."""
            if not value:
                return ""
            return value.replace("\r", " ").replace("\n", " ")

        for track in tracks:
            if extended:
                duration = int(track.duration) if track.duration else -1
                artist = _safe(track.artist)
                title = _safe(track.title)
                extinf = f"#EXTINF:{duration},{artist} - {title}"
                lines.append(extinf)

            if track.source == Source.TIDAL:
                # Tidal tracks get a comment marker so generic players
                # ignore them but Auxen can re-import them.
                lines.append(f"{_TIDAL_URI_PREFIX}{_safe(track.source_id)}")
            else:
                # Local track — use the actual file path if available.
                file_path = None
                if db is not None and track.id is not None:
                    file_path = db.get_local_file_path(track.id)
                if file_path is None:
                    # Fall back to source_id which is typically the path.
                    file_path = track.source_id
                lines.append(_safe(file_path))

        # Trailing newline for POSIX compliance.
        return "\n".join(lines) + "\n" if lines else ""

    # ------------------------------------------------------------------
    # Internal — import helpers
    # ------------------------------------------------------------------

    def _parse_m3u_string(
        self,
        content: str,
        db: Database,
        base_dir: str | None = None,
    ) -> list[Track]:
        """Parse M3U *content* and return matched Track objects."""
        tracks: list[Track] = []
        pending_extinf: dict | None = None

        for raw_line in content.splitlines():
            line = raw_line.strip()
            if not line:
                continue

            # Header — skip.
            if line.upper() == "#EXTM3U":
                continue

            # EXTINF line — stash metadata for next path line.
            if line.upper().startswith("#EXTINF:"):
                pending_extinf = self._parse_extinf(line)
                continue

            # Other comments that are not Tidal URIs — skip.
            if line.startswith("#") and not line.startswith(
                _TIDAL_URI_PREFIX
            ):
                continue

            # Tidal URI line.
            if line.startswith(_TIDAL_URI_PREFIX):
                tidal_id = line[len(_TIDAL_URI_PREFIX) :]
                track = self._lookup_tidal(tidal_id, db, pending_extinf)
                if track is not None:
                    tracks.append(track)
                pending_extinf = None
                continue

            # File path line.
            resolved = self._resolve_path(line, base_dir)
            track = db.get_track_by_file_path(resolved)
            if track is not None:
                tracks.append(track)
            else:
                # Try the raw (unresolved) path as well.
                if resolved != line:
                    track = db.get_track_by_file_path(line)
                    if track is not None:
                        tracks.append(track)
                    else:
                        logger.debug(
                            "M3U import: no match for path %r", line
                        )
                else:
                    logger.debug(
                        "M3U import: no match for path %r", line
                    )

            pending_extinf = None

        return tracks

    @staticmethod
    def _parse_extinf(line: str) -> dict:
        """Parse an ``#EXTINF:duration,artist - title`` line.

        Returns a dict with keys ``duration``, ``artist``, ``title``.
        """
        info: dict = {"duration": None, "artist": None, "title": None}
        try:
            # Strip the "#EXTINF:" prefix.
            payload = line.split(":", 1)[1]
            # Split on the first comma: "duration,display"
            parts = payload.split(",", 1)
            if parts:
                try:
                    info["duration"] = int(parts[0].strip())
                except (ValueError, IndexError):
                    pass
            if len(parts) > 1:
                display = parts[1].strip()
                if " - " in display:
                    artist, title = display.split(" - ", 1)
                    info["artist"] = artist.strip()
                    info["title"] = title.strip()
                else:
                    info["title"] = display
        except Exception:
            logger.debug("Failed to parse EXTINF line: %r", line)
        return info

    @staticmethod
    def _resolve_path(path: str, base_dir: str | None) -> str:
        """Resolve a potentially relative path against *base_dir*."""
        if os.path.isabs(path):
            return path
        if base_dir is not None:
            return str(Path(base_dir) / path)
        return path

    @staticmethod
    def _lookup_tidal(
        tidal_id: str,
        db: Database,
        extinf: dict | None,
    ) -> Track | None:
        """Try to find a Tidal track in the database."""
        # Direct source_id lookup.
        tracks = db.get_tracks_by_source(Source.TIDAL)
        for t in tracks:
            if t.source_id == tidal_id:
                return t

        # Fallback: match by artist + title from EXTINF.
        if extinf and extinf.get("artist") and extinf.get("title"):
            results = db.search(extinf["title"])
            for t in results:
                if (
                    t.source == Source.TIDAL
                    and t.artist.lower() == extinf["artist"].lower()
                ):
                    return t

        return None

    # ------------------------------------------------------------------
    # Internal — encoding
    # ------------------------------------------------------------------

    @staticmethod
    def _encoding_for(filepath: str) -> str:
        """Return the encoding to use based on file extension."""
        if filepath.lower().endswith(".m3u8"):
            return "utf-8"
        return "utf-8"
