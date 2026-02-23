"""Tests for artist detail database methods and data grouping."""

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


# ======================================================================
# get_artist_albums
# ======================================================================


class TestGetArtistAlbums:
    def test_returns_albums_for_artist(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Album X",
                source_id="1",
                year=2020,
            )
        )
        db.insert_track(
            _make_track(
                title="Track 2",
                artist="Artist A",
                album="Album X",
                source_id="2",
                year=2020,
            )
        )
        db.insert_track(
            _make_track(
                title="Track 3",
                artist="Artist A",
                album="Album Y",
                source_id="3",
                year=2022,
            )
        )

        albums = db.get_artist_albums("Artist A")
        assert len(albums) == 2

        album_names = {a["album"] for a in albums}
        assert album_names == {"Album X", "Album Y"}

    def test_returns_correct_structure(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Album X",
                source_id="1",
                year=2020,
            )
        )

        albums = db.get_artist_albums("Artist A")
        assert len(albums) == 1
        album = albums[0]
        assert "album" in album
        assert "track_count" in album
        assert "year" in album
        assert "source" in album
        assert album["album"] == "Album X"
        assert album["track_count"] == 1
        assert album["year"] == 2020
        assert album["source"] == "local"

    def test_empty_for_unknown_artist(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Album X",
                source_id="1",
            )
        )

        albums = db.get_artist_albums("Unknown Artist")
        assert albums == []

    def test_empty_db_returns_empty_list(self, db: Database) -> None:
        assert db.get_artist_albums("Anyone") == []

    def test_excludes_other_artists(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Album X",
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(
                title="Track 2",
                artist="Artist B",
                album="Album Y",
                source_id="2",
            )
        )

        albums = db.get_artist_albums("Artist A")
        assert len(albums) == 1
        assert albums[0]["album"] == "Album X"

    def test_matches_album_artist_field(self, db: Database) -> None:
        """Tracks with album_artist matching should also be included."""
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Featured",
                album_artist="Main Artist",
                album="Collab Album",
                source_id="1",
            )
        )

        albums = db.get_artist_albums("Main Artist")
        assert len(albums) == 1
        assert albums[0]["album"] == "Collab Album"

    def test_track_count_is_correct(self, db: Database) -> None:
        for i in range(5):
            db.insert_track(
                _make_track(
                    title=f"Track {i}",
                    artist="Artist A",
                    album="Big Album",
                    source_id=str(i),
                )
            )

        albums = db.get_artist_albums("Artist A")
        assert len(albums) == 1
        assert albums[0]["track_count"] == 5

    def test_excludes_tracks_without_album(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="No Album",
                artist="Artist A",
                album=None,
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(
                title="Empty Album",
                artist="Artist A",
                album="",
                source_id="2",
            )
        )
        db.insert_track(
            _make_track(
                title="Has Album",
                artist="Artist A",
                album="Real Album",
                source_id="3",
            )
        )

        albums = db.get_artist_albums("Artist A")
        assert len(albums) == 1
        assert albums[0]["album"] == "Real Album"

    def test_separate_albums_by_source(self, db: Database) -> None:
        """Same album from different sources should be separate entries."""
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Shared Album",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Shared Album",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        albums = db.get_artist_albums("Artist A")
        assert len(albums) == 2
        sources = {a["source"] for a in albums}
        assert sources == {"local", "tidal"}

    def test_year_is_max_across_tracks(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track 1",
                artist="Artist A",
                album="Album X",
                source_id="1",
                year=2018,
            )
        )
        db.insert_track(
            _make_track(
                title="Track 2",
                artist="Artist A",
                album="Album X",
                source_id="2",
                year=2020,
            )
        )

        albums = db.get_artist_albums("Artist A")
        assert albums[0]["year"] == 2020


# ======================================================================
# get_artist_tracks
# ======================================================================


class TestGetArtistTracks:
    def test_returns_tracks_for_artist(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Song 1",
                artist="Artist A",
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(
                title="Song 2",
                artist="Artist A",
                source_id="2",
            )
        )
        db.insert_track(
            _make_track(
                title="Song 3",
                artist="Artist B",
                source_id="3",
            )
        )

        tracks = db.get_artist_tracks("Artist A")
        assert len(tracks) == 2
        assert all(isinstance(t, Track) for t in tracks)

    def test_returns_track_objects(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Song 1",
                artist="Artist A",
                album="Album X",
                source_id="1",
                duration=240.0,
            )
        )

        tracks = db.get_artist_tracks("Artist A")
        assert len(tracks) == 1
        track = tracks[0]
        assert track.title == "Song 1"
        assert track.artist == "Artist A"
        assert track.album == "Album X"
        assert track.duration == 240.0

    def test_empty_for_unknown_artist(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Song 1",
                artist="Artist A",
                source_id="1",
            )
        )

        tracks = db.get_artist_tracks("Unknown Artist")
        assert tracks == []

    def test_ordered_by_album_disc_track(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track B3",
                artist="Artist A",
                album="B Album",
                disc_number=1,
                track_number=3,
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(
                title="Track A1",
                artist="Artist A",
                album="A Album",
                disc_number=1,
                track_number=1,
                source_id="2",
            )
        )
        db.insert_track(
            _make_track(
                title="Track A2",
                artist="Artist A",
                album="A Album",
                disc_number=1,
                track_number=2,
                source_id="3",
            )
        )

        tracks = db.get_artist_tracks("Artist A")
        titles = [t.title for t in tracks]
        assert titles == ["Track A1", "Track A2", "Track B3"]

    def test_includes_album_artist_matches(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Collab Song",
                artist="Featured",
                album_artist="Main Artist",
                source_id="1",
            )
        )

        tracks = db.get_artist_tracks("Main Artist")
        assert len(tracks) == 1
        assert tracks[0].title == "Collab Song"

    def test_empty_db_returns_empty_list(self, db: Database) -> None:
        assert db.get_artist_tracks("Anyone") == []


# ======================================================================
# Grouping by album
# ======================================================================


class TestGroupingByAlbum:
    def test_tracks_can_be_grouped_by_album(self, db: Database) -> None:
        """Verify that tracks returned can be grouped by album name."""
        for i in range(3):
            db.insert_track(
                _make_track(
                    title=f"Song A{i}",
                    artist="Artist A",
                    album="Album X",
                    source_id=f"ax{i}",
                )
            )
        for i in range(2):
            db.insert_track(
                _make_track(
                    title=f"Song B{i}",
                    artist="Artist A",
                    album="Album Y",
                    source_id=f"ay{i}",
                )
            )

        tracks = db.get_artist_tracks("Artist A")
        grouped: dict[str, list[Track]] = {}
        for t in tracks:
            album_name = t.album or "Unknown"
            grouped.setdefault(album_name, []).append(t)

        assert len(grouped) == 2
        assert len(grouped["Album X"]) == 3
        assert len(grouped["Album Y"]) == 2

    def test_albums_and_tracks_are_consistent(self, db: Database) -> None:
        """Albums list and tracks list should account for the same data."""
        for i in range(4):
            db.insert_track(
                _make_track(
                    title=f"Track {i}",
                    artist="Artist A",
                    album="Album Z",
                    source_id=str(i),
                )
            )

        albums = db.get_artist_albums("Artist A")
        tracks = db.get_artist_tracks("Artist A")

        assert len(albums) == 1
        assert albums[0]["track_count"] == len(tracks)
        assert albums[0]["track_count"] == 4

    def test_tracks_without_album_not_in_albums(
        self, db: Database
    ) -> None:
        """Tracks without album appear in tracks list but not albums."""
        db.insert_track(
            _make_track(
                title="Single",
                artist="Artist A",
                album=None,
                source_id="1",
            )
        )
        db.insert_track(
            _make_track(
                title="Album Song",
                artist="Artist A",
                album="Real Album",
                source_id="2",
            )
        )

        albums = db.get_artist_albums("Artist A")
        tracks = db.get_artist_tracks("Artist A")

        assert len(albums) == 1
        assert len(tracks) == 2

    def test_multi_source_artist(self, db: Database) -> None:
        """Artist with tracks from multiple sources."""
        db.insert_track(
            _make_track(
                title="Local Song",
                artist="Artist A",
                album="Album X",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="Tidal Song",
                artist="Artist A",
                album="Album X",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        tracks = db.get_artist_tracks("Artist A")
        assert len(tracks) == 2
        sources = {t.source for t in tracks}
        assert sources == {Source.LOCAL, Source.TIDAL}
