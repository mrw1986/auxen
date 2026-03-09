"""Tests for play history tracking and listening statistics."""

import os
import tempfile
from datetime import UTC, datetime, timedelta

import pytest

from auxen.db import Database
from auxen.models import Source, Track


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


# ------------------------------------------------------------------
# record_play_history
# ------------------------------------------------------------------


class TestRecordPlayHistory:
    def test_returns_row_id(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        row_id = db.record_play_history(track_id)
        assert isinstance(row_id, int)
        assert row_id > 0

    def test_records_with_duration(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        db.record_play_history(track_id, duration_listened=180.5)
        history = db.get_play_history(limit=1)
        assert len(history) == 1
        assert history[0]["duration_listened"] == 180.5

    def test_records_without_duration(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        db.record_play_history(track_id)
        history = db.get_play_history(limit=1)
        assert len(history) == 1
        assert history[0]["duration_listened"] is None

    def test_records_played_at_timestamp(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        before = datetime.now(UTC).isoformat()
        db.record_play_history(track_id)
        after = datetime.now(UTC).isoformat()

        history = db.get_play_history(limit=1)
        played_at = history[0]["played_at"]
        assert before <= played_at <= after

    def test_multiple_plays_of_same_track(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        db.record_play_history(track_id, duration_listened=60.0)
        db.record_play_history(track_id, duration_listened=120.0)
        db.record_play_history(track_id, duration_listened=30.0)

        history = db.get_play_history(limit=10)
        assert len(history) == 3


# ------------------------------------------------------------------
# get_play_history
# ------------------------------------------------------------------


class TestGetPlayHistory:
    def test_returns_reverse_chronological_order(self, db: Database) -> None:
        t1 = db.insert_track(_make_track(title="First", source_id="1"))
        t2 = db.insert_track(_make_track(title="Second", source_id="2"))
        t3 = db.insert_track(_make_track(title="Third", source_id="3"))

        db.record_play_history(t1)
        db.record_play_history(t2)
        db.record_play_history(t3)

        history = db.get_play_history(limit=10)
        assert len(history) == 3
        assert history[0]["title"] == "Third"
        assert history[1]["title"] == "Second"
        assert history[2]["title"] == "First"

    def test_limit_parameter(self, db: Database) -> None:
        for i in range(5):
            tid = db.insert_track(
                _make_track(title=f"T{i}", source_id=str(i))
            )
            db.record_play_history(tid)

        history = db.get_play_history(limit=3)
        assert len(history) == 3

    def test_includes_track_info(self, db: Database) -> None:
        tid = db.insert_track(
            _make_track(
                title="Money",
                artist="Pink Floyd",
                album="Dark Side",
                source_id="money1",
            )
        )
        db.record_play_history(tid, duration_listened=300.0)

        history = db.get_play_history(limit=1)
        entry = history[0]
        assert entry["title"] == "Money"
        assert entry["artist"] == "Pink Floyd"
        assert entry["album"] == "Dark Side"
        assert entry["track_id"] == tid
        assert "played_at" in entry
        assert "id" in entry

    def test_empty_history(self, db: Database) -> None:
        history = db.get_play_history()
        assert history == []


# ------------------------------------------------------------------
# get_listening_stats
# ------------------------------------------------------------------


class TestGetListeningStats:
    def test_returns_correct_structure(self, db: Database) -> None:
        stats = db.get_listening_stats()
        assert "total_tracks_played" in stats
        assert "total_listen_time_hours" in stats
        assert "top_artists" in stats
        assert "top_tracks" in stats
        assert "most_active_hour" in stats
        assert "avg_tracks_per_day" in stats

    def test_empty_stats(self, db: Database) -> None:
        stats = db.get_listening_stats()
        assert stats["total_tracks_played"] == 0
        assert stats["total_listen_time_hours"] == 0
        assert stats["top_artists"] == []
        assert stats["top_tracks"] == []
        assert stats["most_active_hour"] is None
        assert stats["avg_tracks_per_day"] == 0

    def test_total_tracks_played(self, db: Database) -> None:
        t1 = db.insert_track(_make_track(title="A", source_id="1"))
        t2 = db.insert_track(_make_track(title="B", source_id="2"))

        db.record_play_history(t1, duration_listened=100.0)
        db.record_play_history(t2, duration_listened=200.0)
        db.record_play_history(t1, duration_listened=150.0)

        stats = db.get_listening_stats()
        assert stats["total_tracks_played"] == 3

    def test_total_listen_time(self, db: Database) -> None:
        tid = db.insert_track(_make_track())
        # 3600 seconds = 1 hour
        db.record_play_history(tid, duration_listened=3600.0)

        stats = db.get_listening_stats()
        assert stats["total_listen_time_hours"] == 1.0

    def test_top_artists(self, db: Database) -> None:
        t1 = db.insert_track(
            _make_track(
                title="Song1", artist="Artist A", source_id="1"
            )
        )
        t2 = db.insert_track(
            _make_track(
                title="Song2", artist="Artist B", source_id="2"
            )
        )
        t3 = db.insert_track(
            _make_track(
                title="Song3", artist="Artist A", source_id="3"
            )
        )

        db.record_play_history(t1, duration_listened=60.0)
        db.record_play_history(t2, duration_listened=60.0)
        db.record_play_history(t3, duration_listened=60.0)
        db.record_play_history(t1, duration_listened=60.0)

        stats = db.get_listening_stats()
        top_artists = stats["top_artists"]
        assert len(top_artists) >= 2
        # Artist A has 3 plays, Artist B has 1
        assert top_artists[0] == ("Artist A", 3)
        assert top_artists[1] == ("Artist B", 1)

    def test_top_tracks(self, db: Database) -> None:
        t1 = db.insert_track(
            _make_track(title="Popular", artist="Band", source_id="1")
        )
        t2 = db.insert_track(
            _make_track(title="Unpopular", artist="Band", source_id="2")
        )

        for _ in range(5):
            db.record_play_history(t1, duration_listened=60.0)
        db.record_play_history(t2, duration_listened=60.0)

        stats = db.get_listening_stats()
        top_tracks = stats["top_tracks"]
        assert len(top_tracks) >= 2
        assert top_tracks[0] == (t1, "Popular", "Band", 5)
        assert top_tracks[1] == (t2, "Unpopular", "Band", 1)

    def test_top_artists_limited_to_10(self, db: Database) -> None:
        for i in range(15):
            tid = db.insert_track(
                _make_track(
                    title=f"Song{i}",
                    artist=f"Artist{i}",
                    source_id=str(i),
                )
            )
            db.record_play_history(tid, duration_listened=60.0)

        stats = db.get_listening_stats()
        assert len(stats["top_artists"]) == 10

    def test_top_tracks_limited_to_10(self, db: Database) -> None:
        for i in range(15):
            tid = db.insert_track(
                _make_track(
                    title=f"Song{i}",
                    artist="Artist",
                    source_id=str(i),
                )
            )
            db.record_play_history(tid, duration_listened=60.0)

        stats = db.get_listening_stats()
        assert len(stats["top_tracks"]) == 10

    def test_most_active_hour(self, db: Database) -> None:
        tid = db.insert_track(_make_track())
        db.record_play_history(tid, duration_listened=60.0)

        stats = db.get_listening_stats()
        # The most active hour should be the current UTC hour
        assert stats["most_active_hour"] is not None
        assert 0 <= stats["most_active_hour"] <= 23

    def test_avg_tracks_per_day(self, db: Database) -> None:
        tid = db.insert_track(_make_track())
        # Record 30 plays (should average 1 per day over 30 days)
        for _ in range(30):
            db.record_play_history(tid, duration_listened=60.0)

        stats = db.get_listening_stats()
        assert stats["avg_tracks_per_day"] == 1.0

    def test_listen_time_with_null_durations(self, db: Database) -> None:
        """Plays without duration_listened should not break the total."""
        tid = db.insert_track(_make_track())
        db.record_play_history(tid, duration_listened=None)
        db.record_play_history(tid, duration_listened=3600.0)

        stats = db.get_listening_stats()
        assert stats["total_listen_time_hours"] == 1.0


# ------------------------------------------------------------------
# get_recently_played_history (deduplicated)
# ------------------------------------------------------------------


class TestGetRecentlyPlayedHistory:
    def test_deduplicates_tracks(self, db: Database) -> None:
        t1 = db.insert_track(_make_track(title="Repeated", source_id="1"))
        t2 = db.insert_track(_make_track(title="Unique", source_id="2"))

        db.record_play_history(t1, duration_listened=60.0)
        db.record_play_history(t2, duration_listened=60.0)
        db.record_play_history(t1, duration_listened=120.0)

        tracks = db.get_recently_played_history(limit=10)
        assert len(tracks) == 2
        # "Repeated" should be first (its most recent play is latest)
        assert tracks[0].title == "Repeated"
        assert tracks[1].title == "Unique"

    def test_returns_track_objects(self, db: Database) -> None:
        tid = db.insert_track(
            _make_track(
                title="Test Track",
                artist="Test Artist",
                source_id="test1",
            )
        )
        db.record_play_history(tid, duration_listened=100.0)

        tracks = db.get_recently_played_history(limit=10)
        assert len(tracks) == 1
        track = tracks[0]
        assert isinstance(track, Track)
        assert track.title == "Test Track"
        assert track.artist == "Test Artist"
        assert track.id == tid

    def test_limit_parameter(self, db: Database) -> None:
        for i in range(5):
            tid = db.insert_track(
                _make_track(title=f"T{i}", source_id=str(i))
            )
            db.record_play_history(tid, duration_listened=60.0)

        tracks = db.get_recently_played_history(limit=3)
        assert len(tracks) == 3

    def test_empty_history(self, db: Database) -> None:
        tracks = db.get_recently_played_history()
        assert tracks == []

    def test_order_by_most_recent_play(self, db: Database) -> None:
        t1 = db.insert_track(_make_track(title="First", source_id="1"))
        t2 = db.insert_track(_make_track(title="Second", source_id="2"))

        db.record_play_history(t2, duration_listened=60.0)
        db.record_play_history(t1, duration_listened=60.0)

        tracks = db.get_recently_played_history(limit=10)
        assert tracks[0].title == "First"
        assert tracks[1].title == "Second"
