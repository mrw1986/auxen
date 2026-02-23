"""SQLite database layer for the Auxen music player."""

from __future__ import annotations

import sqlite3
from datetime import UTC, datetime, timedelta
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

CREATE TABLE IF NOT EXISTS play_history (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    track_id            INTEGER NOT NULL REFERENCES tracks(id),
    played_at           TEXT    NOT NULL,
    duration_listened   REAL
);

CREATE INDEX IF NOT EXISTS idx_tracks_source
    ON tracks(source);
CREATE INDEX IF NOT EXISTS idx_tracks_match_group
    ON tracks(match_group_id);
CREATE INDEX IF NOT EXISTS idx_tracks_title_artist
    ON tracks(title, artist);
CREATE INDEX IF NOT EXISTS idx_play_history_track_id
    ON play_history(track_id);
CREATE INDEX IF NOT EXISTS idx_play_history_played_at
    ON play_history(played_at);

CREATE TABLE IF NOT EXISTS search_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    query       TEXT    NOT NULL UNIQUE,
    searched_at TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_search_history_searched_at
    ON search_history(searched_at);
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

    def get_tracks_by_album(
        self, album: str, artist: str | None = None
    ) -> list[Track]:
        """Get all tracks belonging to an album, ordered by disc/track number.

        Parameters
        ----------
        album:
            The album name to search for (exact match).
        artist:
            Optional artist name to narrow the search.
        """
        if artist is not None:
            cur = self._conn.execute(
                """
                SELECT * FROM tracks
                WHERE album = ? AND (artist = ? OR album_artist = ?)
                ORDER BY disc_number ASC, track_number ASC, title ASC
                """,
                (album, artist, artist),
            )
        else:
            cur = self._conn.execute(
                """
                SELECT * FROM tracks
                WHERE album = ?
                ORDER BY disc_number ASC, track_number ASC, title ASC
                """,
                (album,),
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

    def get_track_by_file_path(self, file_path: str) -> Optional[Track]:
        """Look up a track by its local file path.

        Returns the Track if found, or None.
        """
        cur = self._conn.execute(
            """
            SELECT t.* FROM tracks t
            JOIN local_files lf ON lf.track_id = t.id
            WHERE lf.file_path = ?
            """,
            (file_path,),
        )
        row = cur.fetchone()
        return self._row_to_track(row) if row else None

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
    # Playlists
    # ------------------------------------------------------------------

    def create_playlist(self, name: str, color: str = "#d4a039") -> int:
        """Create a new playlist. Returns playlist ID."""
        cur = self._conn.execute(
            "INSERT INTO playlists (name, color) VALUES (?, ?)",
            (name, color),
        )
        self._conn.commit()
        return cur.lastrowid  # type: ignore[return-value]

    def get_playlists(self) -> list[dict]:
        """Get all playlists as dicts with id, name, color, track_count."""
        cur = self._conn.execute(
            """
            SELECT p.id, p.name, p.color,
                   COUNT(pt.track_id) AS track_count
            FROM playlists p
            LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
            WHERE p.source IS NULL AND p.tidal_playlist_id IS NULL
            GROUP BY p.id
            ORDER BY p.id
            """
        )
        return [
            {
                "id": row["id"],
                "name": row["name"],
                "color": row["color"],
                "track_count": row["track_count"],
            }
            for row in cur.fetchall()
        ]

    def get_playlist(self, playlist_id: int) -> dict | None:
        """Get a single playlist with its metadata."""
        cur = self._conn.execute(
            """
            SELECT p.id, p.name, p.color,
                   COUNT(pt.track_id) AS track_count
            FROM playlists p
            LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
            WHERE p.id = ?
            GROUP BY p.id
            """,
            (playlist_id,),
        )
        row = cur.fetchone()
        if row is None:
            return None
        return {
            "id": row["id"],
            "name": row["name"],
            "color": row["color"],
            "track_count": row["track_count"],
        }

    def delete_playlist(self, playlist_id: int) -> None:
        """Delete a playlist and its track associations."""
        self._conn.execute(
            "DELETE FROM playlist_tracks WHERE playlist_id = ?",
            (playlist_id,),
        )
        self._conn.execute(
            "DELETE FROM playlists WHERE id = ?",
            (playlist_id,),
        )
        self._conn.commit()

    def rename_playlist(self, playlist_id: int, name: str) -> None:
        """Rename a playlist."""
        self._conn.execute(
            "UPDATE playlists SET name = ? WHERE id = ?",
            (name, playlist_id),
        )
        self._conn.commit()

    def update_playlist_color(self, playlist_id: int, color: str) -> None:
        """Update the color of a playlist."""
        self._conn.execute(
            "UPDATE playlists SET color = ? WHERE id = ?",
            (color, playlist_id),
        )
        self._conn.commit()

    def add_track_to_playlist(
        self, playlist_id: int, track_id: int
    ) -> None:
        """Add a track to a playlist at the end.

        If the track is already in the playlist, this is a no-op.
        """
        cur = self._conn.execute(
            "SELECT 1 FROM playlist_tracks "
            "WHERE playlist_id = ? AND track_id = ?",
            (playlist_id, track_id),
        )
        if cur.fetchone() is not None:
            return

        cur = self._conn.execute(
            "SELECT COALESCE(MAX(position), -1) + 1 "
            "FROM playlist_tracks WHERE playlist_id = ?",
            (playlist_id,),
        )
        next_pos = cur.fetchone()[0]

        self._conn.execute(
            "INSERT INTO playlist_tracks "
            "(playlist_id, track_id, position) VALUES (?, ?, ?)",
            (playlist_id, track_id, next_pos),
        )
        self._conn.commit()

    def remove_track_from_playlist(
        self, playlist_id: int, track_id: int
    ) -> None:
        """Remove a track from a playlist."""
        self._conn.execute(
            "DELETE FROM playlist_tracks "
            "WHERE playlist_id = ? AND track_id = ?",
            (playlist_id, track_id),
        )
        self._conn.commit()

    def get_playlist_tracks(self, playlist_id: int) -> list[Track]:
        """Get all tracks in a playlist, ordered by position."""
        cur = self._conn.execute(
            """
            SELECT t.* FROM tracks t
            JOIN playlist_tracks pt ON pt.track_id = t.id
            WHERE pt.playlist_id = ?
            ORDER BY pt.position
            """,
            (playlist_id,),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def reorder_playlist_track(
        self, playlist_id: int, track_id: int, new_position: int
    ) -> None:
        """Move a track to a new position in the playlist.

        Shifts other tracks to accommodate the move.
        """
        cur = self._conn.execute(
            """
            SELECT track_id FROM playlist_tracks
            WHERE playlist_id = ?
            ORDER BY position
            """,
            (playlist_id,),
        )
        track_ids = [row["track_id"] for row in cur.fetchall()]

        if track_id not in track_ids:
            return

        track_ids.remove(track_id)
        new_position = max(0, min(new_position, len(track_ids)))
        track_ids.insert(new_position, track_id)

        for pos, tid in enumerate(track_ids):
            self._conn.execute(
                "UPDATE playlist_tracks SET position = ? "
                "WHERE playlist_id = ? AND track_id = ?",
                (pos, playlist_id, tid),
            )
        self._conn.commit()

    # ------------------------------------------------------------------
    # Library browsing
    # ------------------------------------------------------------------

    def get_albums(self, source: Source | None = None) -> list[dict]:
        """Get distinct albums with artist, track count, source.

        Ordered by added_at descending (most recently added first).

        Returns
        -------
        list of dicts with keys: album, artist, track_count, source, year
        """
        if source is not None:
            cur = self._conn.execute(
                """
                SELECT album,
                       COALESCE(album_artist, artist) AS artist,
                       COUNT(*) AS track_count,
                       source,
                       MAX(year) AS year,
                       MAX(added_at) AS latest_added
                FROM tracks
                WHERE album IS NOT NULL AND album != '' AND source = ?
                GROUP BY album, COALESCE(album_artist, artist), source
                ORDER BY latest_added DESC
                """,
                (source.value,),
            )
        else:
            cur = self._conn.execute(
                """
                SELECT album,
                       COALESCE(album_artist, artist) AS artist,
                       COUNT(*) AS track_count,
                       source,
                       MAX(year) AS year,
                       MAX(added_at) AS latest_added
                FROM tracks
                WHERE album IS NOT NULL AND album != ''
                GROUP BY album, COALESCE(album_artist, artist), source
                ORDER BY latest_added DESC
                """
            )
        return [
            {
                "album": row["album"],
                "artist": row["artist"],
                "track_count": row["track_count"],
                "source": row["source"],
                "year": row["year"],
            }
            for row in cur.fetchall()
        ]

    def get_artists(self, source: Source | None = None) -> list[dict]:
        """Get distinct artists with track count.

        Ordered by artist name ascending.

        Returns
        -------
        list of dicts with keys: artist, track_count, sources
        """
        if source is not None:
            cur = self._conn.execute(
                """
                SELECT artist, COUNT(*) AS track_count,
                       GROUP_CONCAT(DISTINCT source) AS sources
                FROM tracks
                WHERE source = ?
                GROUP BY artist
                ORDER BY artist COLLATE NOCASE ASC
                """,
                (source.value,),
            )
        else:
            cur = self._conn.execute(
                """
                SELECT artist, COUNT(*) AS track_count,
                       GROUP_CONCAT(DISTINCT source) AS sources
                FROM tracks
                GROUP BY artist
                ORDER BY artist COLLATE NOCASE ASC
                """
            )
        return [
            {
                "artist": row["artist"],
                "track_count": row["track_count"],
                "sources": row["sources"].split(",") if row["sources"] else [],
            }
            for row in cur.fetchall()
        ]

    def get_artist_albums(self, artist: str) -> list[dict]:
        """Get all albums by a specific artist.

        Returns a list of dicts with keys: album, track_count, year, source.
        Ordered by year descending (newest first), then album name.
        """
        cur = self._conn.execute(
            """
            SELECT album,
                   COUNT(*) AS track_count,
                   MAX(year) AS year,
                   source
            FROM tracks
            WHERE (artist = ? OR album_artist = ?)
              AND album IS NOT NULL AND album != ''
            GROUP BY album, source
            ORDER BY year DESC, album COLLATE NOCASE ASC
            """,
            (artist, artist),
        )
        return [
            {
                "album": row["album"],
                "track_count": row["track_count"],
                "year": row["year"],
                "source": row["source"],
            }
            for row in cur.fetchall()
        ]

    def get_artist_tracks(self, artist: str) -> list[Track]:
        """Get all tracks by a specific artist.

        Returns Track objects ordered by album, disc number, track number.
        """
        cur = self._conn.execute(
            """
            SELECT * FROM tracks
            WHERE artist = ? OR album_artist = ?
            ORDER BY album COLLATE NOCASE ASC,
                     disc_number ASC,
                     track_number ASC,
                     title COLLATE NOCASE ASC
            """,
            (artist, artist),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_track_count(self, source: Source | None = None) -> int:
        """Get total track count, optionally filtered by source."""
        if source is not None:
            cur = self._conn.execute(
                "SELECT COUNT(*) AS cnt FROM tracks WHERE source = ?",
                (source.value,),
            )
        else:
            cur = self._conn.execute("SELECT COUNT(*) AS cnt FROM tracks")
        row = cur.fetchone()
        return row["cnt"] if row else 0

    # ------------------------------------------------------------------
    # Play history
    # ------------------------------------------------------------------

    def record_play_history(
        self, track_id: int, duration_listened: float | None = None
    ) -> int:
        """Insert a play history record. Returns the new row id."""
        played_at = datetime.now(UTC).isoformat()
        cur = self._conn.execute(
            """
            INSERT INTO play_history (track_id, played_at, duration_listened)
            VALUES (?, ?, ?)
            """,
            (track_id, played_at, duration_listened),
        )
        self._conn.commit()
        return cur.lastrowid  # type: ignore[return-value]

    def get_play_history(self, limit: int = 50) -> list[dict]:
        """Return play history entries in reverse chronological order.

        Each dict contains: id, track_id, played_at, duration_listened,
        title, artist, album.
        """
        cur = self._conn.execute(
            """
            SELECT ph.id, ph.track_id, ph.played_at, ph.duration_listened,
                   t.title, t.artist, t.album
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            ORDER BY ph.played_at DESC
            LIMIT ?
            """,
            (limit,),
        )
        return [
            {
                "id": row["id"],
                "track_id": row["track_id"],
                "played_at": row["played_at"],
                "duration_listened": row["duration_listened"],
                "title": row["title"],
                "artist": row["artist"],
                "album": row["album"],
            }
            for row in cur.fetchall()
        ]

    def get_listening_stats(self) -> dict:
        """Return aggregate listening statistics.

        Returns a dict with:
            total_tracks_played, total_listen_time_hours,
            top_artists (list of (artist, play_count) top 10),
            top_tracks (list of (title, artist, play_count) top 10),
            most_active_hour (0-23 or None),
            avg_tracks_per_day (over last 30 days).
        """
        # Total tracks played
        cur = self._conn.execute(
            "SELECT COUNT(*) AS cnt FROM play_history"
        )
        total_tracks_played = cur.fetchone()["cnt"]

        # Total listen time in hours
        cur = self._conn.execute(
            "SELECT COALESCE(SUM(duration_listened), 0) AS total "
            "FROM play_history"
        )
        total_seconds = cur.fetchone()["total"]
        total_listen_time_hours = round(total_seconds / 3600, 1)

        # Top artists (top 10 by play count)
        cur = self._conn.execute(
            """
            SELECT t.artist, COUNT(*) AS play_count
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            GROUP BY t.artist
            ORDER BY play_count DESC
            LIMIT 10
            """
        )
        top_artists = [
            (row["artist"], row["play_count"]) for row in cur.fetchall()
        ]

        # Top tracks (top 10 by play count)
        cur = self._conn.execute(
            """
            SELECT t.title, t.artist, COUNT(*) AS play_count
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            GROUP BY ph.track_id
            ORDER BY play_count DESC
            LIMIT 10
            """
        )
        top_tracks = [
            (row["title"], row["artist"], row["play_count"])
            for row in cur.fetchall()
        ]

        # Most active hour (0-23)
        cur = self._conn.execute(
            """
            SELECT CAST(strftime('%H', played_at) AS INTEGER) AS hour,
                   COUNT(*) AS cnt
            FROM play_history
            GROUP BY hour
            ORDER BY cnt DESC
            LIMIT 1
            """
        )
        row = cur.fetchone()
        most_active_hour = row["hour"] if row else None

        # Average tracks per day over last 30 days
        thirty_days_ago = (
            datetime.now(UTC) - timedelta(days=30)
        ).isoformat()
        cur = self._conn.execute(
            """
            SELECT COUNT(*) AS cnt
            FROM play_history
            WHERE played_at >= ?
            """,
            (thirty_days_ago,),
        )
        recent_count = cur.fetchone()["cnt"]
        avg_tracks_per_day = round(recent_count / 30, 1)

        return {
            "total_tracks_played": total_tracks_played,
            "total_listen_time_hours": total_listen_time_hours,
            "top_artists": top_artists,
            "top_tracks": top_tracks,
            "most_active_hour": most_active_hour,
            "avg_tracks_per_day": avg_tracks_per_day,
        }

    # ------------------------------------------------------------------
    # Smart playlist queries
    # ------------------------------------------------------------------

    def get_most_played_tracks(self, limit: int = 50) -> list[Track]:
        """Return the most-played tracks ordered by play count descending.

        Uses the play_history table to count actual plays (more reliable
        than the cached play_count on the tracks table).
        """
        cur = self._conn.execute(
            """
            SELECT t.*, COUNT(ph.id) AS play_cnt
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            GROUP BY ph.track_id
            ORDER BY play_cnt DESC
            LIMIT ?
            """,
            (limit,),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_recently_added_tracks(self, limit: int = 50) -> list[Track]:
        """Return the most recently added tracks (by id desc)."""
        cur = self._conn.execute(
            "SELECT * FROM tracks ORDER BY id DESC LIMIT ?", (limit,)
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_heavy_rotation_tracks(
        self, days: int = 7, limit: int = 30
    ) -> list[Track]:
        """Return the most-played tracks in the last *days* days."""
        cutoff = (datetime.now(UTC) - timedelta(days=days)).isoformat()
        cur = self._conn.execute(
            """
            SELECT t.*, COUNT(ph.id) AS play_cnt
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            WHERE ph.played_at >= ?
            GROUP BY ph.track_id
            ORDER BY play_cnt DESC
            LIMIT ?
            """,
            (cutoff, limit),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_forgotten_gems(
        self,
        min_plays: int = 5,
        inactive_days: int = 30,
        limit: int = 30,
    ) -> list[Track]:
        """Return tracks with >= *min_plays* total plays but none in the
        last *inactive_days* days.
        """
        cutoff = (
            datetime.now(UTC) - timedelta(days=inactive_days)
        ).isoformat()
        cur = self._conn.execute(
            """
            SELECT t.*, COUNT(ph.id) AS play_cnt,
                   MAX(ph.played_at) AS last_play
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            GROUP BY ph.track_id
            HAVING play_cnt >= ? AND last_play < ?
            ORDER BY play_cnt DESC
            LIMIT ?
            """,
            (min_plays, cutoff, limit),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_tracks_by_duration(
        self,
        min_seconds: float | None = None,
        max_seconds: float | None = None,
        limit: int = 50,
    ) -> list[Track]:
        """Return tracks filtered by duration range.

        Tracks with NULL duration are excluded.
        """
        conditions = ["duration IS NOT NULL"]
        params: list = []

        if min_seconds is not None:
            conditions.append("duration >= ?")
            params.append(min_seconds)
        if max_seconds is not None:
            conditions.append("duration < ?")
            params.append(max_seconds)

        where = " AND ".join(conditions)
        params.append(limit)

        cur = self._conn.execute(
            f"SELECT * FROM tracks WHERE {where} "
            f"ORDER BY duration DESC LIMIT ?",
            params,
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    def get_recently_played_history(self, limit: int = 20) -> list[Track]:
        """Return most recently played tracks, deduplicated.

        Returns at most *limit* unique Track objects, ordered by the most
        recent play of each unique track.
        """
        cur = self._conn.execute(
            """
            SELECT t.*, MAX(ph.played_at) AS latest_play
            FROM play_history ph
            JOIN tracks t ON t.id = ph.track_id
            GROUP BY ph.track_id
            ORDER BY latest_play DESC
            LIMIT ?
            """,
            (limit,),
        )
        return [self._row_to_track(r) for r in cur.fetchall()]

    # ------------------------------------------------------------------
    # Search history
    # ------------------------------------------------------------------

    def add_search_history(self, query: str) -> None:
        """Insert a search query with timestamp.

        If the query already exists, update its timestamp instead of
        creating a duplicate entry.
        """
        now = datetime.now(UTC).isoformat()
        self._conn.execute(
            """
            INSERT INTO search_history (query, searched_at)
            VALUES (?, ?)
            ON CONFLICT(query) DO UPDATE SET searched_at = ?
            """,
            (query, now, now),
        )
        self._conn.commit()

    def get_search_history(self, limit: int = 10) -> list[str]:
        """Return recent search queries, newest first.

        Parameters
        ----------
        limit:
            Maximum number of queries to return (default 10).
        """
        cur = self._conn.execute(
            "SELECT query FROM search_history ORDER BY searched_at DESC, id DESC LIMIT ?",
            (limit,),
        )
        return [row["query"] for row in cur.fetchall()]

    def clear_search_history(self) -> None:
        """Delete all search history entries."""
        self._conn.execute("DELETE FROM search_history")
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
