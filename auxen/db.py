"""SQLite database layer for the Auxen music player."""

from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Optional

from auxen.models import Source, Track

_DEFAULT_DB_PATH = Path.home() / ".local" / "share" / "auxen" / "library.db"

_CREATE_TABLES = """
CREATE TABLE IF NOT EXISTS tracks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT    NOT NULL,
    artist          TEXT    NOT NULL,
    album           TEXT,
    album_artist    TEXT,
    genre           TEXT,
    year            INTEGER,
    duration        REAL,
    track_number    INTEGER,
    disc_number     INTEGER,
    source          TEXT    NOT NULL,
    source_id       TEXT    NOT NULL,
    bitrate         INTEGER,
    format          TEXT,
    sample_rate     INTEGER,
    bit_depth       INTEGER,
    album_art_url   TEXT,
    match_group_id  TEXT,
    added_at        TEXT    DEFAULT (datetime('now')),
    last_played_at  TEXT,
    play_count      INTEGER DEFAULT 0,
    UNIQUE(source, source_id)
);

CREATE TABLE IF NOT EXISTS local_files (
    track_id        INTEGER NOT NULL REFERENCES tracks(id),
    file_path       TEXT    NOT NULL UNIQUE,
    file_size       INTEGER,
    file_modified_at TEXT,
    needs_rescan    INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS favorites (
    track_id        INTEGER NOT NULL UNIQUE REFERENCES tracks(id),
    match_group_id  TEXT,
    added_at        TEXT    DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS playlists (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    name                TEXT    NOT NULL,
    color               TEXT,
    source              TEXT,
    tidal_playlist_id   TEXT
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
    playlist_id INTEGER NOT NULL REFERENCES playlists(id),
    track_id    INTEGER NOT NULL REFERENCES tracks(id),
    position    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT
);

CREATE INDEX IF NOT EXISTS idx_tracks_source
    ON tracks(source);
CREATE INDEX IF NOT EXISTS idx_tracks_match_group
    ON tracks(match_group_id);
CREATE INDEX IF NOT EXISTS idx_tracks_title_artist
    ON tracks(title, artist);
"""


class Database:
    """Thin wrapper around an SQLite database for the Auxen library."""

    def __init__(self, db_path: str | Path | None = None) -> None:
        path = Path(db_path) if db_path else _DEFAULT_DB_PATH
        path.parent.mkdir(parents=True, exist_ok=True)

        self._conn = sqlite3.connect(str(path))
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.executescript(_CREATE_TABLES)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def close(self) -> None:
        """Close the database connection."""
        self._conn.close()

    # ------------------------------------------------------------------
    # Tracks
    # ------------------------------------------------------------------

    def insert_track(self, track: Track) -> int:
        """Insert or replace a track. Returns the row id."""
        cur = self._conn.execute(
            """
            INSERT OR REPLACE INTO tracks
                (title, artist, album, album_artist, genre, year,
                 duration, track_number, disc_number, source, source_id,
                 bitrate, format, sample_rate, bit_depth, album_art_url,
                 match_group_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                track.title,
                track.artist,
                track.album,
                track.album_artist,
                track.genre,
                track.year,
                track.duration,
                track.track_number,
                track.disc_number,
                track.source.value,
                track.source_id,
                track.bitrate,
                track.format,
                track.sample_rate,
                track.bit_depth,
                track.album_art_url,
                track.match_group_id,
            ),
        )
        self._conn.commit()
        return cur.lastrowid  # type: ignore[return-value]

    def get_track(self, track_id: int) -> Optional[Track]:
        """Fetch a single track by its primary key."""
        cur = self._conn.execute(
            "SELECT * FROM tracks WHERE id = ?", (track_id,)
        )
        row = cur.fetchone()
        return self._row_to_track(row) if row else None

    def get_all_tracks(self) -> list[Track]:
        """Return every track, most recently added first."""
        cur = self._conn.execute(
            "SELECT * FROM tracks ORDER BY added_at DESC, id DESC"
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_tracks_by_source(self, source: Source) -> list[Track]:
        """Return all tracks from the given source."""
        cur = self._conn.execute(
            "SELECT * FROM tracks WHERE source = ? ORDER BY added_at DESC, id DESC",
            (source.value,),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_recently_played(self, limit: int = 20) -> list[Track]:
        """Return the most recently played tracks."""
        cur = self._conn.execute(
            """
            SELECT * FROM tracks
            WHERE last_played_at IS NOT NULL
            ORDER BY last_played_at DESC
            LIMIT ?
            """,
            (limit,),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_recently_added(self, limit: int = 20) -> list[Track]:
        """Return the most recently added tracks."""
        cur = self._conn.execute(
            "SELECT * FROM tracks ORDER BY added_at DESC, id DESC LIMIT ?", (limit,)
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def record_play(self, track_id: int) -> None:
        """Increment play_count and update last_played_at."""
        self._conn.execute(
            """
            UPDATE tracks
            SET play_count = play_count + 1,
                last_played_at = datetime('now')
            WHERE id = ?
            """,
            (track_id,),
        )
        self._conn.commit()

    def search(self, query: str) -> list[Track]:
        """Search tracks by title, artist, or album (case-insensitive LIKE)."""
        pattern = f"%{query}%"
        cur = self._conn.execute(
            """
            SELECT * FROM tracks
            WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?
            ORDER BY title
            """,
            (pattern, pattern, pattern),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    # ------------------------------------------------------------------
    # Favorites
    # ------------------------------------------------------------------

    def set_favorite(self, track_id: int, is_favorite: bool) -> None:
        """Add or remove a track from favorites."""
        if is_favorite:
            # Look up match_group_id from the track
            track = self.get_track(track_id)
            match_group = track.match_group_id if track else None
            self._conn.execute(
                """
                INSERT OR REPLACE INTO favorites (track_id, match_group_id)
                VALUES (?, ?)
                """,
                (track_id, match_group),
            )
        else:
            self._conn.execute(
                "DELETE FROM favorites WHERE track_id = ?", (track_id,)
            )
        self._conn.commit()

    def is_favorite(self, track_id: int) -> bool:
        """Check whether a track is favorited."""
        cur = self._conn.execute(
            "SELECT 1 FROM favorites WHERE track_id = ?", (track_id,)
        )
        return cur.fetchone() is not None

    def get_favorites(self) -> list[Track]:
        """Return all favorited tracks."""
        cur = self._conn.execute(
            """
            SELECT t.* FROM tracks t
            JOIN favorites f ON f.track_id = t.id
            ORDER BY f.added_at DESC
            """
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    # ------------------------------------------------------------------
    # Settings
    # ------------------------------------------------------------------

    def get_setting(self, key: str, default: Optional[str] = None) -> Optional[str]:
        """Read a setting value, returning *default* when absent."""
        cur = self._conn.execute(
            "SELECT value FROM settings WHERE key = ?", (key,)
        )
        row = cur.fetchone()
        return row["value"] if row else default

    def set_setting(self, key: str, value: str) -> None:
        """Write a setting (insert or update)."""
        self._conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
            (key, value),
        )
        self._conn.commit()

    # ------------------------------------------------------------------
    # Local files
    # ------------------------------------------------------------------

    def insert_local_file(
        self,
        track_id: int,
        file_path: str,
        file_size: int,
        file_modified_at: str,
    ) -> None:
        """Record a local file entry linked to a track."""
        self._conn.execute(
            """
            INSERT OR REPLACE INTO local_files
                (track_id, file_path, file_size, file_modified_at)
            VALUES (?, ?, ?, ?)
            """,
            (track_id, file_path, file_size, file_modified_at),
        )
        self._conn.commit()

    def get_local_file_path(self, track_id: int) -> Optional[str]:
        """Return the file path for a local track, or None."""
        cur = self._conn.execute(
            "SELECT file_path FROM local_files WHERE track_id = ?",
            (track_id,),
        )
        row = cur.fetchone()
        return row["file_path"] if row else None

    # ------------------------------------------------------------------
    # Match groups
    # ------------------------------------------------------------------

    def get_tracks_in_match_group(self, match_group_id: str) -> list[Track]:
        """Return all tracks sharing the given match group."""
        cur = self._conn.execute(
            "SELECT * FROM tracks WHERE match_group_id = ?",
            (match_group_id,),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def set_match_group(self, track_ids: list[int], group_id: str) -> None:
        """Assign a match group id to multiple tracks."""
        for tid in track_ids:
            self._conn.execute(
                "UPDATE tracks SET match_group_id = ? WHERE id = ?",
                (group_id, tid),
            )
        self._conn.commit()

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    @staticmethod
    def _row_to_track(row: sqlite3.Row) -> Track:
        """Convert a sqlite3.Row to a Track dataclass."""
        return Track(
            id=row["id"],
            title=row["title"],
            artist=row["artist"],
            album=row["album"],
            album_artist=row["album_artist"],
            genre=row["genre"],
            year=row["year"],
            duration=row["duration"],
            track_number=row["track_number"],
            disc_number=row["disc_number"],
            source=Source(row["source"]),
            source_id=row["source_id"],
            bitrate=row["bitrate"],
            format=row["format"],
            sample_rate=row["sample_rate"],
            bit_depth=row["bit_depth"],
            album_art_url=row["album_art_url"],
            match_group_id=row["match_group_id"],
            added_at=row["added_at"],
            last_played_at=row["last_played_at"],
            play_count=row["play_count"],
        )
