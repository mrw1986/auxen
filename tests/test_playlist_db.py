"""Tests for playlist CRUD methods in auxen.db — Database class."""

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


class TestCreatePlaylist:
    def test_create_playlist_returns_id(self, db: Database) -> None:
        playlist_id = db.create_playlist("My Playlist")
        assert isinstance(playlist_id, int)
        assert playlist_id > 0

    def test_create_playlist_with_default_color(self, db: Database) -> None:
        playlist_id = db.create_playlist("Default Color")
        playlist = db.get_playlist(playlist_id)
        assert playlist is not None
        assert playlist["color"] == "#d4a039"

    def test_create_playlist_with_custom_color(self, db: Database) -> None:
        playlist_id = db.create_playlist("Custom Color", color="#00c4cc")
        playlist = db.get_playlist(playlist_id)
        assert playlist is not None
        assert playlist["color"] == "#00c4cc"

    def test_create_multiple_playlists(self, db: Database) -> None:
        id1 = db.create_playlist("Playlist 1")
        id2 = db.create_playlist("Playlist 2")
        id3 = db.create_playlist("Playlist 3")
        assert id1 != id2 != id3
        assert len(db.get_playlists()) == 3


class TestGetPlaylist:
    def test_get_playlist_returns_dict(self, db: Database) -> None:
        playlist_id = db.create_playlist("Test Playlist", color="#e74c3c")
        playlist = db.get_playlist(playlist_id)
        assert playlist is not None
        assert playlist["id"] == playlist_id
        assert playlist["name"] == "Test Playlist"
        assert playlist["color"] == "#e74c3c"
        assert playlist["track_count"] == 0

    def test_get_nonexistent_playlist_returns_none(
        self, db: Database
    ) -> None:
        result = db.get_playlist(9999)
        assert result is None

    def test_get_playlist_track_count(self, db: Database) -> None:
        playlist_id = db.create_playlist("With Tracks")
        t1 = db.insert_track(_make_track(title="T1", source_id="1"))
        t2 = db.insert_track(_make_track(title="T2", source_id="2"))
        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)

        playlist = db.get_playlist(playlist_id)
        assert playlist is not None
        assert playlist["track_count"] == 2


class TestGetPlaylists:
    def test_get_playlists_empty(self, db: Database) -> None:
        playlists = db.get_playlists()
        assert playlists == []

    def test_get_playlists_returns_all(self, db: Database) -> None:
        db.create_playlist("A")
        db.create_playlist("B")
        db.create_playlist("C")
        playlists = db.get_playlists()
        assert len(playlists) == 3
        names = {p["name"] for p in playlists}
        assert names == {"A", "B", "C"}

    def test_get_playlists_includes_track_counts(
        self, db: Database
    ) -> None:
        id1 = db.create_playlist("Empty")
        id2 = db.create_playlist("Has Tracks")
        t1 = db.insert_track(_make_track(title="T1", source_id="1"))
        t2 = db.insert_track(_make_track(title="T2", source_id="2"))
        db.add_track_to_playlist(id2, t1)
        db.add_track_to_playlist(id2, t2)

        playlists = db.get_playlists()
        by_name = {p["name"]: p for p in playlists}
        assert by_name["Empty"]["track_count"] == 0
        assert by_name["Has Tracks"]["track_count"] == 2


class TestDeletePlaylist:
    def test_delete_playlist(self, db: Database) -> None:
        playlist_id = db.create_playlist("To Delete")
        assert db.get_playlist(playlist_id) is not None

        db.delete_playlist(playlist_id)
        assert db.get_playlist(playlist_id) is None

    def test_delete_playlist_removes_track_associations(
        self, db: Database
    ) -> None:
        playlist_id = db.create_playlist("To Delete")
        t1 = db.insert_track(_make_track(title="T1", source_id="1"))
        db.add_track_to_playlist(playlist_id, t1)

        db.delete_playlist(playlist_id)
        # Playlist tracks should be cleaned up
        tracks = db.get_playlist_tracks(playlist_id)
        assert tracks == []

    def test_delete_nonexistent_playlist_no_error(
        self, db: Database
    ) -> None:
        # Should not raise
        db.delete_playlist(9999)


class TestRenamePlaylist:
    def test_rename_playlist(self, db: Database) -> None:
        playlist_id = db.create_playlist("Old Name")
        db.rename_playlist(playlist_id, "New Name")
        playlist = db.get_playlist(playlist_id)
        assert playlist is not None
        assert playlist["name"] == "New Name"

    def test_rename_preserves_other_fields(self, db: Database) -> None:
        playlist_id = db.create_playlist("Original", color="#9b59b6")
        db.rename_playlist(playlist_id, "Renamed")
        playlist = db.get_playlist(playlist_id)
        assert playlist is not None
        assert playlist["color"] == "#9b59b6"


class TestAddTrackToPlaylist:
    def test_add_track_to_playlist(self, db: Database) -> None:
        playlist_id = db.create_playlist("My Playlist")
        track_id = db.insert_track(_make_track())
        db.add_track_to_playlist(playlist_id, track_id)

        tracks = db.get_playlist_tracks(playlist_id)
        assert len(tracks) == 1
        assert tracks[0].title == "Echoes"

    def test_add_multiple_tracks_preserves_order(
        self, db: Database
    ) -> None:
        playlist_id = db.create_playlist("Ordered")
        t1 = db.insert_track(_make_track(title="First", source_id="1"))
        t2 = db.insert_track(_make_track(title="Second", source_id="2"))
        t3 = db.insert_track(_make_track(title="Third", source_id="3"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)
        db.add_track_to_playlist(playlist_id, t3)

        tracks = db.get_playlist_tracks(playlist_id)
        assert len(tracks) == 3
        assert tracks[0].title == "First"
        assert tracks[1].title == "Second"
        assert tracks[2].title == "Third"

    def test_add_duplicate_track_ignored(self, db: Database) -> None:
        playlist_id = db.create_playlist("No Dupes")
        track_id = db.insert_track(_make_track())
        db.add_track_to_playlist(playlist_id, track_id)
        db.add_track_to_playlist(playlist_id, track_id)

        tracks = db.get_playlist_tracks(playlist_id)
        assert len(tracks) == 1


class TestRemoveTrackFromPlaylist:
    def test_remove_track_from_playlist(self, db: Database) -> None:
        playlist_id = db.create_playlist("Remove Test")
        t1 = db.insert_track(_make_track(title="Keep", source_id="1"))
        t2 = db.insert_track(_make_track(title="Remove", source_id="2"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)

        db.remove_track_from_playlist(playlist_id, t2)
        tracks = db.get_playlist_tracks(playlist_id)
        assert len(tracks) == 1
        assert tracks[0].title == "Keep"

    def test_remove_nonexistent_track_no_error(self, db: Database) -> None:
        playlist_id = db.create_playlist("Test")
        # Should not raise
        db.remove_track_from_playlist(playlist_id, 9999)


class TestGetPlaylistTracks:
    def test_get_tracks_ordered_by_position(self, db: Database) -> None:
        playlist_id = db.create_playlist("Ordered")
        t1 = db.insert_track(_make_track(title="A", source_id="1"))
        t2 = db.insert_track(_make_track(title="B", source_id="2"))
        t3 = db.insert_track(_make_track(title="C", source_id="3"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)
        db.add_track_to_playlist(playlist_id, t3)

        tracks = db.get_playlist_tracks(playlist_id)
        assert [t.title for t in tracks] == ["A", "B", "C"]

    def test_empty_playlist_returns_empty_list(self, db: Database) -> None:
        playlist_id = db.create_playlist("Empty")
        assert db.get_playlist_tracks(playlist_id) == []

    def test_nonexistent_playlist_returns_empty_list(
        self, db: Database
    ) -> None:
        assert db.get_playlist_tracks(9999) == []


class TestReorderPlaylistTrack:
    def test_move_track_to_beginning(self, db: Database) -> None:
        playlist_id = db.create_playlist("Reorder")
        t1 = db.insert_track(_make_track(title="A", source_id="1"))
        t2 = db.insert_track(_make_track(title="B", source_id="2"))
        t3 = db.insert_track(_make_track(title="C", source_id="3"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)
        db.add_track_to_playlist(playlist_id, t3)

        # Move C (position 2) to the beginning (position 0)
        db.reorder_playlist_track(playlist_id, t3, 0)
        tracks = db.get_playlist_tracks(playlist_id)
        assert [t.title for t in tracks] == ["C", "A", "B"]

    def test_move_track_to_end(self, db: Database) -> None:
        playlist_id = db.create_playlist("Reorder")
        t1 = db.insert_track(_make_track(title="A", source_id="1"))
        t2 = db.insert_track(_make_track(title="B", source_id="2"))
        t3 = db.insert_track(_make_track(title="C", source_id="3"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)
        db.add_track_to_playlist(playlist_id, t3)

        # Move A (position 0) to the end (position 2)
        db.reorder_playlist_track(playlist_id, t1, 2)
        tracks = db.get_playlist_tracks(playlist_id)
        assert [t.title for t in tracks] == ["B", "C", "A"]

    def test_move_track_to_middle(self, db: Database) -> None:
        playlist_id = db.create_playlist("Reorder")
        t1 = db.insert_track(_make_track(title="A", source_id="1"))
        t2 = db.insert_track(_make_track(title="B", source_id="2"))
        t3 = db.insert_track(_make_track(title="C", source_id="3"))
        t4 = db.insert_track(_make_track(title="D", source_id="4"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)
        db.add_track_to_playlist(playlist_id, t3)
        db.add_track_to_playlist(playlist_id, t4)

        # Move D (position 3) to position 1
        db.reorder_playlist_track(playlist_id, t4, 1)
        tracks = db.get_playlist_tracks(playlist_id)
        assert [t.title for t in tracks] == ["A", "D", "B", "C"]

    def test_move_track_same_position_no_change(
        self, db: Database
    ) -> None:
        playlist_id = db.create_playlist("Reorder")
        t1 = db.insert_track(_make_track(title="A", source_id="1"))
        t2 = db.insert_track(_make_track(title="B", source_id="2"))

        db.add_track_to_playlist(playlist_id, t1)
        db.add_track_to_playlist(playlist_id, t2)

        db.reorder_playlist_track(playlist_id, t1, 0)
        tracks = db.get_playlist_tracks(playlist_id)
        assert [t.title for t in tracks] == ["A", "B"]
