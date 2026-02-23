"""Tests for auxen.db — Database class."""

import os
import tempfile

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


class TestInsertAndGetTrack:
    def test_insert_returns_id(self, db: Database) -> None:
        track = _make_track()
        track_id = db.insert_track(track)
        assert isinstance(track_id, int)
        assert track_id > 0

    def test_get_track_returns_track(self, db: Database) -> None:
        track = _make_track(
            album="Meddle",
            album_artist="Pink Floyd",
            genre="Progressive Rock",
            year=1971,
            duration=1380.0,
            track_number=6,
            disc_number=1,
            bitrate=None,
            format="FLAC",
            sample_rate=44100,
            bit_depth=16,
        )
        track_id = db.insert_track(track)
        result = db.get_track(track_id)

        assert result is not None
        assert result.id == track_id
        assert result.title == "Echoes"
        assert result.artist == "Pink Floyd"
        assert result.source == Source.LOCAL
        assert result.source_id == "/music/echoes.flac"
        assert result.album == "Meddle"
        assert result.album_artist == "Pink Floyd"
        assert result.genre == "Progressive Rock"
        assert result.year == 1971
        assert result.duration == 1380.0
        assert result.track_number == 6
        assert result.disc_number == 1
        assert result.format == "FLAC"
        assert result.sample_rate == 44100
        assert result.bit_depth == 16

    def test_get_nonexistent_track_returns_none(self, db: Database) -> None:
        result = db.get_track(9999)
        assert result is None

    def test_insert_or_replace_on_duplicate(self, db: Database) -> None:
        """INSERT OR REPLACE uses (source, source_id) uniqueness."""
        track1 = _make_track(title="Version 1")
        id1 = db.insert_track(track1)

        track2 = _make_track(title="Version 2")
        id2 = db.insert_track(track2)

        # Should have replaced the row
        result = db.get_track(id2)
        assert result is not None
        assert result.title == "Version 2"


class TestGetAllTracks:
    def test_returns_all_tracks_ordered_by_added_at_desc(
        self, db: Database
    ) -> None:
        db.insert_track(_make_track(title="First", source_id="1"))
        db.insert_track(_make_track(title="Second", source_id="2"))
        db.insert_track(_make_track(title="Third", source_id="3"))

        tracks = db.get_all_tracks()
        assert len(tracks) == 3
        # Most recently added first
        assert tracks[0].title == "Third"
        assert tracks[1].title == "Second"
        assert tracks[2].title == "First"

    def test_empty_db_returns_empty_list(self, db: Database) -> None:
        assert db.get_all_tracks() == []


class TestGetTracksBySource:
    def test_filters_by_source(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Local Track", source=Source.LOCAL, source_id="local1"
            )
        )
        db.insert_track(
            _make_track(
                title="Tidal Track", source=Source.TIDAL, source_id="tidal1"
            )
        )
        db.insert_track(
            _make_track(
                title="Another Local",
                source=Source.LOCAL,
                source_id="local2",
            )
        )

        local_tracks = db.get_tracks_by_source(Source.LOCAL)
        assert len(local_tracks) == 2
        assert all(t.source == Source.LOCAL for t in local_tracks)

        tidal_tracks = db.get_tracks_by_source(Source.TIDAL)
        assert len(tidal_tracks) == 1
        assert tidal_tracks[0].title == "Tidal Track"


class TestFavorites:
    def test_set_and_check_favorite(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        assert db.is_favorite(track_id) is False

        db.set_favorite(track_id, True)
        assert db.is_favorite(track_id) is True

    def test_unfavorite(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        db.set_favorite(track_id, True)
        assert db.is_favorite(track_id) is True

        db.set_favorite(track_id, False)
        assert db.is_favorite(track_id) is False

    def test_get_favorites(self, db: Database) -> None:
        id1 = db.insert_track(
            _make_track(title="Fav1", source_id="1")
        )
        id2 = db.insert_track(
            _make_track(title="NotFav", source_id="2")
        )
        id3 = db.insert_track(
            _make_track(title="Fav2", source_id="3")
        )

        db.set_favorite(id1, True)
        db.set_favorite(id3, True)

        favs = db.get_favorites()
        assert len(favs) == 2
        fav_titles = {f.title for f in favs}
        assert fav_titles == {"Fav1", "Fav2"}


class TestSearchTracks:
    def test_search_by_title(self, db: Database) -> None:
        db.insert_track(
            _make_track(title="Comfortably Numb", source_id="1")
        )
        db.insert_track(_make_track(title="Money", source_id="2"))

        results = db.search("comfort")
        assert len(results) == 1
        assert results[0].title == "Comfortably Numb"

    def test_search_by_artist(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Stairway",
                artist="Led Zeppelin",
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(title="Money", artist="Pink Floyd", source_id="2")
        )

        results = db.search("zeppelin")
        assert len(results) == 1
        assert results[0].artist == "Led Zeppelin"

    def test_search_by_album(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Time",
                album="The Dark Side of the Moon",
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(title="Money", album="Same Album", source_id="2")
        )

        results = db.search("dark side")
        assert len(results) == 1
        assert results[0].title == "Time"

    def test_search_no_results(self, db: Database) -> None:
        db.insert_track(_make_track(source_id="1"))
        results = db.search("nonexistent query xyz")
        assert results == []

    def test_search_case_insensitive(self, db: Database) -> None:
        db.insert_track(
            _make_track(title="Comfortably Numb", source_id="1")
        )
        results = db.search("COMFORTABLY")
        assert len(results) == 1


class TestUpdatePlayCount:
    def test_record_play_increments_count(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        track = db.get_track(track_id)
        assert track is not None
        assert track.play_count == 0

        db.record_play(track_id)
        track = db.get_track(track_id)
        assert track is not None
        assert track.play_count == 1

        db.record_play(track_id)
        track = db.get_track(track_id)
        assert track is not None
        assert track.play_count == 2

    def test_record_play_sets_last_played(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        track = db.get_track(track_id)
        assert track is not None
        assert track.last_played_at is None

        db.record_play(track_id)
        track = db.get_track(track_id)
        assert track is not None
        assert track.last_played_at is not None


class TestGetSetting:
    def test_set_and_get(self, db: Database) -> None:
        db.set_setting("theme", "dark")
        assert db.get_setting("theme") == "dark"

    def test_get_missing_returns_default(self, db: Database) -> None:
        assert db.get_setting("missing") is None
        assert db.get_setting("missing", "fallback") == "fallback"

    def test_overwrite_setting(self, db: Database) -> None:
        db.set_setting("volume", "50")
        db.set_setting("volume", "75")
        assert db.get_setting("volume") == "75"


class TestRecentlyPlayed:
    def test_returns_recently_played(self, db: Database) -> None:
        id1 = db.insert_track(_make_track(title="A", source_id="1"))
        id2 = db.insert_track(_make_track(title="B", source_id="2"))
        id3 = db.insert_track(_make_track(title="C", source_id="3"))

        db.record_play(id1)
        db.record_play(id2)
        # id3 never played

        recent = db.get_recently_played(limit=20)
        assert len(recent) == 2
        played_titles = {t.title for t in recent}
        assert played_titles == {"A", "B"}

    def test_limit_parameter(self, db: Database) -> None:
        for i in range(5):
            tid = db.insert_track(
                _make_track(title=f"T{i}", source_id=str(i))
            )
            db.record_play(tid)

        recent = db.get_recently_played(limit=3)
        assert len(recent) == 3


class TestRecentlyAdded:
    def test_returns_recently_added(self, db: Database) -> None:
        db.insert_track(_make_track(title="Old", source_id="1"))
        db.insert_track(_make_track(title="New", source_id="2"))

        recent = db.get_recently_added(limit=1)
        assert len(recent) == 1
        assert recent[0].title == "New"


class TestLocalFiles:
    def test_insert_and_get_local_file_path(self, db: Database) -> None:
        track_id = db.insert_track(_make_track())
        db.insert_local_file(
            track_id,
            file_path="/music/echoes.flac",
            file_size=50_000_000,
            file_modified_at="2024-01-01T00:00:00",
        )

        path = db.get_local_file_path(track_id)
        assert path == "/music/echoes.flac"

    def test_get_local_file_path_nonexistent(self, db: Database) -> None:
        assert db.get_local_file_path(9999) is None


class TestMatchGroups:
    def test_set_match_group(self, db: Database) -> None:
        id1 = db.insert_track(
            _make_track(title="Song", source=Source.LOCAL, source_id="l1")
        )
        id2 = db.insert_track(
            _make_track(title="Song", source=Source.TIDAL, source_id="t1")
        )

        group_id = "match-abc-123"
        db.set_match_group([id1, id2], group_id)

        tracks = db.get_tracks_in_match_group(group_id)
        assert len(tracks) == 2
        ids = {t.id for t in tracks}
        assert ids == {id1, id2}

    def test_get_tracks_in_nonexistent_group(self, db: Database) -> None:
        assert db.get_tracks_in_match_group("no-such-group") == []
