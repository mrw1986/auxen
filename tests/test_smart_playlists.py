"""Tests for smart auto-playlists — database queries and service layer."""

import os
import tempfile
from datetime import UTC, datetime, timedelta

import pytest

from auxen.db import Database
from auxen.models import Source, Track
from auxen.smart_playlists import SmartPlaylistService, SmartPlaylistType


@pytest.fixture
def db():
    """Create a temporary database, yield it, then clean up."""
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    database = Database(db_path=path)
    yield database
    database.close()
    if os.path.exists(path):
        os.unlink(path)


@pytest.fixture
def service(db):
    """Create a SmartPlaylistService backed by the temp database."""
    return SmartPlaylistService(db=db)


def _make_track(
    title: str = "Echoes",
    artist: str = "Pink Floyd",
    source: Source = Source.LOCAL,
    source_id: str = "/music/echoes.flac",
    **kwargs,
) -> Track:
    """Helper to create a Track with sensible defaults."""
    return Track(
        title=title,
        artist=artist,
        source=source,
        source_id=source_id,
        **kwargs,
    )


# ==================================================================
# Database query method tests
# ==================================================================


class TestGetMostPlayedTracks:
    def test_returns_ordered_by_play_count(self, db: Database) -> None:
        t1 = db.insert_track(_make_track(title="Popular", source_id="1"))
        t2 = db.insert_track(_make_track(title="Medium", source_id="2"))
        t3 = db.insert_track(_make_track(title="Rare", source_id="3"))

        # 5 plays for "Popular"
        for _ in range(5):
            db.record_play_history(t1, duration_listened=60.0)
        # 2 plays for "Medium"
        for _ in range(2):
            db.record_play_history(t2, duration_listened=60.0)
        # 1 play for "Rare"
        db.record_play_history(t3, duration_listened=60.0)

        tracks = db.get_most_played_tracks(limit=10)
        assert len(tracks) == 3
        assert tracks[0].title == "Popular"
        assert tracks[1].title == "Medium"
        assert tracks[2].title == "Rare"

    def test_limit_parameter(self, db: Database) -> None:
        for i in range(10):
            tid = db.insert_track(
                _make_track(title=f"T{i}", source_id=str(i))
            )
            db.record_play_history(tid, duration_listened=60.0)

        tracks = db.get_most_played_tracks(limit=5)
        assert len(tracks) == 5

    def test_empty_history_returns_empty(self, db: Database) -> None:
        db.insert_track(_make_track(source_id="1"))
        tracks = db.get_most_played_tracks()
        assert tracks == []


class TestGetRecentlyAddedTracks:
    def test_returns_newest_first(self, db: Database) -> None:
        db.insert_track(_make_track(title="Old", source_id="1"))
        db.insert_track(_make_track(title="Middle", source_id="2"))
        db.insert_track(_make_track(title="New", source_id="3"))

        tracks = db.get_recently_added_tracks(limit=10)
        assert len(tracks) == 3
        assert tracks[0].title == "New"
        assert tracks[1].title == "Middle"
        assert tracks[2].title == "Old"

    def test_limit_parameter(self, db: Database) -> None:
        for i in range(10):
            db.insert_track(
                _make_track(title=f"T{i}", source_id=str(i))
            )

        tracks = db.get_recently_added_tracks(limit=3)
        assert len(tracks) == 3

    def test_empty_db_returns_empty(self, db: Database) -> None:
        tracks = db.get_recently_added_tracks()
        assert tracks == []


class TestGetHeavyRotationTracks:
    def test_filters_by_date_range(self, db: Database) -> None:
        t1 = db.insert_track(
            _make_track(title="Recent", source_id="1")
        )
        t2 = db.insert_track(
            _make_track(title="Old", source_id="2")
        )

        # Recent plays (within 7 days — these are recorded "now")
        for _ in range(3):
            db.record_play_history(t1, duration_listened=60.0)

        # Manually insert old play history (15 days ago)
        old_date = (
            datetime.now(UTC) - timedelta(days=15)
        ).isoformat()
        db._conn.execute(
            "INSERT INTO play_history (track_id, played_at, duration_listened) "
            "VALUES (?, ?, ?)",
            (t2, old_date, 60.0),
        )
        db._conn.commit()

        tracks = db.get_heavy_rotation_tracks(days=7, limit=10)
        assert len(tracks) == 1
        assert tracks[0].title == "Recent"

    def test_returns_ordered_by_count(self, db: Database) -> None:
        t1 = db.insert_track(
            _make_track(title="Hot", source_id="1")
        )
        t2 = db.insert_track(
            _make_track(title="Warm", source_id="2")
        )

        for _ in range(5):
            db.record_play_history(t1, duration_listened=60.0)
        for _ in range(2):
            db.record_play_history(t2, duration_listened=60.0)

        tracks = db.get_heavy_rotation_tracks(days=7, limit=10)
        assert tracks[0].title == "Hot"
        assert tracks[1].title == "Warm"

    def test_empty_returns_empty(self, db: Database) -> None:
        tracks = db.get_heavy_rotation_tracks()
        assert tracks == []


class TestGetForgottenGems:
    def test_correct_criteria(self, db: Database) -> None:
        """Track with 6 plays all older than 30 days qualifies."""
        t1 = db.insert_track(
            _make_track(title="Forgotten", source_id="1")
        )
        t2 = db.insert_track(
            _make_track(title="Active", source_id="2")
        )

        # Old plays for "Forgotten" (45 days ago)
        old_date = (
            datetime.now(UTC) - timedelta(days=45)
        ).isoformat()
        for _ in range(6):
            db._conn.execute(
                "INSERT INTO play_history (track_id, played_at, duration_listened) "
                "VALUES (?, ?, ?)",
                (t1, old_date, 60.0),
            )
        db._conn.commit()

        # Recent plays for "Active"
        for _ in range(6):
            db.record_play_history(t2, duration_listened=60.0)

        gems = db.get_forgotten_gems(
            min_plays=5, inactive_days=30, limit=10
        )
        assert len(gems) == 1
        assert gems[0].title == "Forgotten"

    def test_not_enough_plays_excluded(self, db: Database) -> None:
        """Track with only 3 plays should not qualify (min_plays=5)."""
        tid = db.insert_track(
            _make_track(title="Few Plays", source_id="1")
        )
        old_date = (
            datetime.now(UTC) - timedelta(days=45)
        ).isoformat()
        for _ in range(3):
            db._conn.execute(
                "INSERT INTO play_history (track_id, played_at, duration_listened) "
                "VALUES (?, ?, ?)",
                (tid, old_date, 60.0),
            )
        db._conn.commit()

        gems = db.get_forgotten_gems(
            min_plays=5, inactive_days=30, limit=10
        )
        assert gems == []

    def test_empty_returns_empty(self, db: Database) -> None:
        gems = db.get_forgotten_gems()
        assert gems == []


class TestGetTracksByDuration:
    def test_min_seconds_filter(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Long", source_id="1", duration=400.0
            )
        )
        db.insert_track(
            _make_track(
                title="Short", source_id="2", duration=120.0
            )
        )

        tracks = db.get_tracks_by_duration(min_seconds=360.0)
        assert len(tracks) == 1
        assert tracks[0].title == "Long"

    def test_max_seconds_filter(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Long", source_id="1", duration=400.0
            )
        )
        db.insert_track(
            _make_track(
                title="Short", source_id="2", duration=120.0
            )
        )

        tracks = db.get_tracks_by_duration(max_seconds=180.0)
        assert len(tracks) == 1
        assert tracks[0].title == "Short"

    def test_min_and_max_filter(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Very Long", source_id="1", duration=600.0
            )
        )
        db.insert_track(
            _make_track(
                title="Medium", source_id="2", duration=300.0
            )
        )
        db.insert_track(
            _make_track(
                title="Short", source_id="3", duration=100.0
            )
        )

        tracks = db.get_tracks_by_duration(
            min_seconds=200.0, max_seconds=400.0
        )
        assert len(tracks) == 1
        assert tracks[0].title == "Medium"

    def test_null_duration_excluded(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="No Duration", source_id="1", duration=None
            )
        )
        db.insert_track(
            _make_track(
                title="Has Duration", source_id="2", duration=300.0
            )
        )

        tracks = db.get_tracks_by_duration()
        assert len(tracks) == 1
        assert tracks[0].title == "Has Duration"

    def test_ordered_by_duration_desc(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Short", source_id="1", duration=100.0
            )
        )
        db.insert_track(
            _make_track(
                title="Long", source_id="2", duration=500.0
            )
        )
        db.insert_track(
            _make_track(
                title="Medium", source_id="3", duration=300.0
            )
        )

        tracks = db.get_tracks_by_duration()
        assert tracks[0].title == "Long"
        assert tracks[1].title == "Medium"
        assert tracks[2].title == "Short"

    def test_empty_returns_empty(self, db: Database) -> None:
        tracks = db.get_tracks_by_duration()
        assert tracks == []


# ==================================================================
# SmartPlaylistService tests
# ==================================================================


class TestSmartPlaylistServiceDefinitions:
    def test_get_definitions_returns_all_types(
        self, service: SmartPlaylistService
    ) -> None:
        definitions = service.get_definitions()
        assert len(definitions) == 7
        ids = {d["id"] for d in definitions}
        assert ids == {
            "most_played",
            "recently_added",
            "recently_played",
            "heavy_rotation",
            "forgotten_gems",
            "long_tracks",
            "short_tracks",
        }

    def test_definitions_have_required_keys(
        self, service: SmartPlaylistService
    ) -> None:
        definitions = service.get_definitions()
        for defn in definitions:
            assert "id" in defn
            assert "name" in defn
            assert "icon" in defn
            assert "description" in defn

    def test_get_definition_by_id(
        self, service: SmartPlaylistService
    ) -> None:
        defn = service.get_definition("most_played")
        assert defn is not None
        assert defn["name"] == "Most Played"

    def test_get_definition_nonexistent_returns_none(
        self, service: SmartPlaylistService
    ) -> None:
        defn = service.get_definition("nonexistent")
        assert defn is None


class TestSmartPlaylistServiceGetTracks:
    def test_dispatches_most_played(
        self, db: Database, service: SmartPlaylistService
    ) -> None:
        tid = db.insert_track(
            _make_track(title="Top Song", source_id="1")
        )
        for _ in range(5):
            db.record_play_history(tid, duration_listened=60.0)

        tracks = service.get_tracks("most_played")
        assert len(tracks) >= 1
        assert tracks[0].title == "Top Song"

    def test_dispatches_recently_added(
        self, db: Database, service: SmartPlaylistService
    ) -> None:
        db.insert_track(
            _make_track(title="New Song", source_id="1")
        )

        tracks = service.get_tracks("recently_added")
        assert len(tracks) == 1
        assert tracks[0].title == "New Song"

    def test_dispatches_long_tracks(
        self, db: Database, service: SmartPlaylistService
    ) -> None:
        db.insert_track(
            _make_track(
                title="Epic", source_id="1", duration=420.0
            )
        )
        db.insert_track(
            _make_track(
                title="Short", source_id="2", duration=120.0
            )
        )

        tracks = service.get_tracks("long_tracks")
        assert len(tracks) == 1
        assert tracks[0].title == "Epic"

    def test_dispatches_short_tracks(
        self, db: Database, service: SmartPlaylistService
    ) -> None:
        db.insert_track(
            _make_track(
                title="Long", source_id="1", duration=400.0
            )
        )
        db.insert_track(
            _make_track(
                title="Quick", source_id="2", duration=120.0
            )
        )

        tracks = service.get_tracks("short_tracks")
        assert len(tracks) == 1
        assert tracks[0].title == "Quick"

    def test_unknown_playlist_returns_empty(
        self, service: SmartPlaylistService
    ) -> None:
        tracks = service.get_tracks("nonexistent_type")
        assert tracks == []

    def test_smart_playlist_type_enum_values(self) -> None:
        """Verify the enum values match the definition IDs."""
        assert SmartPlaylistType.MOST_PLAYED.value == "most_played"
        assert SmartPlaylistType.RECENTLY_ADDED.value == "recently_added"
        assert SmartPlaylistType.RECENTLY_PLAYED.value == "recently_played"
        assert SmartPlaylistType.HEAVY_ROTATION.value == "heavy_rotation"
        assert SmartPlaylistType.FORGOTTEN_GEMS.value == "forgotten_gems"
        assert SmartPlaylistType.LONG_TRACKS.value == "long_tracks"
        assert SmartPlaylistType.SHORT_TRACKS.value == "short_tracks"
