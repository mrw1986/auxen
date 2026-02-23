"""Tests for library browsing database methods (get_albums, get_artists, get_track_count)."""

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
# get_albums
# ======================================================================


class TestGetAlbums:
    def test_returns_albums_with_track_count(self, db: Database) -> None:
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
                artist="Artist A",
                album="Album X",
                source_id="2",
            )
        )
        db.insert_track(
            _make_track(
                title="Track 3",
                artist="Artist B",
                album="Album Y",
                source_id="3",
            )
        )

        albums = db.get_albums()
        assert len(albums) == 2

        # Find Album X
        album_x = [a for a in albums if a["album"] == "Album X"]
        assert len(album_x) == 1
        assert album_x[0]["artist"] == "Artist A"
        assert album_x[0]["track_count"] == 2
        assert album_x[0]["source"] == "local"

    def test_filters_by_source(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Local Track",
                album="Local Album",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="Tidal Track",
                album="Tidal Album",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        local_albums = db.get_albums(source=Source.LOCAL)
        assert len(local_albums) == 1
        assert local_albums[0]["album"] == "Local Album"

        tidal_albums = db.get_albums(source=Source.TIDAL)
        assert len(tidal_albums) == 1
        assert tidal_albums[0]["album"] == "Tidal Album"

    def test_empty_db_returns_empty_list(self, db: Database) -> None:
        assert db.get_albums() == []

    def test_excludes_tracks_without_album(self, db: Database) -> None:
        db.insert_track(
            _make_track(title="No Album", album=None, source_id="1")
        )
        db.insert_track(
            _make_track(title="Empty Album", album="", source_id="2")
        )
        db.insert_track(
            _make_track(
                title="Has Album", album="Real Album", source_id="3"
            )
        )

        albums = db.get_albums()
        assert len(albums) == 1
        assert albums[0]["album"] == "Real Album"

    def test_includes_year(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Track",
                album="Album",
                year=1995,
                source_id="1",
            )
        )

        albums = db.get_albums()
        assert len(albums) == 1
        assert albums[0]["year"] == 1995

    def test_uses_album_artist_when_available(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Collab",
                artist="Featured",
                album_artist="Main Artist",
                album="Collab Album",
                source_id="1",
            )
        )

        albums = db.get_albums()
        assert len(albums) == 1
        assert albums[0]["artist"] == "Main Artist"

    def test_separate_albums_by_source(self, db: Database) -> None:
        """Same album from different sources should be separate entries."""
        db.insert_track(
            _make_track(
                title="Track 1",
                album="Shared Album",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="Track 1",
                album="Shared Album",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        albums = db.get_albums()
        assert len(albums) == 2
        sources = {a["source"] for a in albums}
        assert sources == {"local", "tidal"}


# ======================================================================
# get_artists
# ======================================================================


class TestGetArtists:
    def test_returns_artists_with_track_count(self, db: Database) -> None:
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

        artists = db.get_artists()
        assert len(artists) == 2

        artist_a = [a for a in artists if a["artist"] == "Artist A"]
        assert len(artist_a) == 1
        assert artist_a[0]["track_count"] == 2

    def test_ordered_by_name(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="S1", artist="Zebra", source_id="1"
            )
        )
        db.insert_track(
            _make_track(
                title="S2", artist="Apple", source_id="2"
            )
        )
        db.insert_track(
            _make_track(
                title="S3", artist="Mango", source_id="3"
            )
        )

        artists = db.get_artists()
        names = [a["artist"] for a in artists]
        assert names == ["Apple", "Mango", "Zebra"]

    def test_filters_by_source(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Local",
                artist="Local Artist",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="Tidal",
                artist="Tidal Artist",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        local_artists = db.get_artists(source=Source.LOCAL)
        assert len(local_artists) == 1
        assert local_artists[0]["artist"] == "Local Artist"

    def test_includes_sources_list(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="S1",
                artist="Both",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="S2",
                artist="Both",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        artists = db.get_artists()
        assert len(artists) == 1
        assert set(artists[0]["sources"]) == {"local", "tidal"}

    def test_empty_db_returns_empty_list(self, db: Database) -> None:
        assert db.get_artists() == []

    def test_case_insensitive_ordering(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="S1", artist="alpha", source_id="1"
            )
        )
        db.insert_track(
            _make_track(
                title="S2", artist="Beta", source_id="2"
            )
        )
        db.insert_track(
            _make_track(
                title="S3", artist="gamma", source_id="3"
            )
        )

        artists = db.get_artists()
        names = [a["artist"] for a in artists]
        assert names == ["alpha", "Beta", "gamma"]


# ======================================================================
# get_track_count
# ======================================================================


class TestGetTrackCount:
    def test_total_count(self, db: Database) -> None:
        db.insert_track(
            _make_track(title="A", source_id="1")
        )
        db.insert_track(
            _make_track(title="B", source_id="2")
        )
        db.insert_track(
            _make_track(title="C", source_id="3")
        )

        assert db.get_track_count() == 3

    def test_filtered_by_source(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Local 1",
                source=Source.LOCAL,
                source_id="l1",
            )
        )
        db.insert_track(
            _make_track(
                title="Local 2",
                source=Source.LOCAL,
                source_id="l2",
            )
        )
        db.insert_track(
            _make_track(
                title="Tidal 1",
                source=Source.TIDAL,
                source_id="t1",
            )
        )

        assert db.get_track_count(source=Source.LOCAL) == 2
        assert db.get_track_count(source=Source.TIDAL) == 1

    def test_empty_db_returns_zero(self, db: Database) -> None:
        assert db.get_track_count() == 0

    def test_zero_for_empty_source(self, db: Database) -> None:
        db.insert_track(
            _make_track(
                title="Local Only",
                source=Source.LOCAL,
                source_id="l1",
            )
        )

        assert db.get_track_count(source=Source.TIDAL) == 0
