"""Tests for auxen.m3u — M3U playlist import and export."""

import os
import tempfile

import pytest

from auxen.db import Database
from auxen.m3u import M3UService
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


@pytest.fixture
def svc():
    """Return a fresh M3UService instance."""
    return M3UService()


def _make_local_track(
    title: str = "Echoes",
    artist: str = "Pink Floyd",
    source_id: str = "/music/echoes.flac",
    duration: float | None = 1380.0,
    album: str | None = "Meddle",
    **kwargs,
) -> Track:
    return Track(
        title=title,
        artist=artist,
        source=Source.LOCAL,
        source_id=source_id,
        duration=duration,
        album=album,
        **kwargs,
    )


def _make_tidal_track(
    title: str = "Blinding Lights",
    artist: str = "The Weeknd",
    source_id: str = "123456789",
    duration: float | None = 200.0,
    album: str | None = "After Hours",
    **kwargs,
) -> Track:
    return Track(
        title=title,
        artist=artist,
        source=Source.TIDAL,
        source_id=source_id,
        duration=duration,
        album=album,
        **kwargs,
    )


def _insert_local_track(db: Database, track: Track) -> Track:
    """Insert a local track and register its file path, return updated track."""
    track_id = db.insert_track(track)
    db.insert_local_file(
        track_id,
        file_path=track.source_id,
        file_size=5_000_000,
        file_modified_at="2024-01-01T00:00:00",
    )
    inserted = db.get_track(track_id)
    assert inserted is not None
    return inserted


def _insert_tidal_track(db: Database, track: Track) -> Track:
    """Insert a Tidal track and return the updated track."""
    track_id = db.insert_track(track)
    inserted = db.get_track(track_id)
    assert inserted is not None
    return inserted


# =====================================================================
# Export tests
# =====================================================================


class TestExportToStringLocalTracks:
    """Test export_to_string with local tracks."""

    def test_export_single_local_track(self, svc: M3UService) -> None:
        track = _make_local_track()
        result = svc.export_to_string([track])
        lines = result.strip().split("\n")
        assert lines[0] == "#EXTM3U"
        assert lines[1] == "#EXTINF:1380,Pink Floyd - Echoes"
        assert lines[2] == "/music/echoes.flac"

    def test_export_multiple_local_tracks(self, svc: M3UService) -> None:
        tracks = [
            _make_local_track(
                title="Time", artist="Pink Floyd",
                source_id="/music/time.flac", duration=413.0,
            ),
            _make_local_track(
                title="Money", artist="Pink Floyd",
                source_id="/music/money.flac", duration=382.0,
            ),
        ]
        result = svc.export_to_string(tracks)
        lines = result.strip().split("\n")
        assert lines[0] == "#EXTM3U"
        # Two tracks = 2 EXTINF + 2 path = 4 content lines + header = 5
        assert len(lines) == 5

    def test_export_local_track_no_duration(self, svc: M3UService) -> None:
        track = _make_local_track(duration=None)
        result = svc.export_to_string([track])
        assert "#EXTINF:-1," in result

    def test_export_uses_db_file_path(
        self, svc: M3UService, db: Database
    ) -> None:
        track = _make_local_track(source_id="/raw/path.flac")
        inserted = _insert_local_track(db, track)
        result = svc.export_to_string([inserted], db=db)
        assert "/raw/path.flac" in result


class TestExportToStringTidalTracks:
    """Test export_to_string with Tidal tracks."""

    def test_export_single_tidal_track(self, svc: M3UService) -> None:
        track = _make_tidal_track()
        result = svc.export_to_string([track])
        lines = result.strip().split("\n")
        assert lines[0] == "#EXTM3U"
        assert lines[1] == "#EXTINF:200,The Weeknd - Blinding Lights"
        assert lines[2] == "# tidal://123456789"

    def test_export_tidal_uses_comment_marker(
        self, svc: M3UService
    ) -> None:
        track = _make_tidal_track(source_id="987654")
        result = svc.export_to_string([track])
        assert "# tidal://987654" in result


class TestExportToStringExtended:
    """Test extended vs simple M3U format."""

    def test_export_extended_true(self, svc: M3UService) -> None:
        track = _make_local_track()
        result = svc.export_to_string([track], extended=True)
        assert result.startswith("#EXTM3U\n")
        assert "#EXTINF:" in result

    def test_export_extended_false(self, svc: M3UService) -> None:
        track = _make_local_track()
        result = svc.export_to_string([track], extended=False)
        assert "#EXTM3U" not in result
        assert "#EXTINF" not in result
        assert "/music/echoes.flac" in result

    def test_export_mixed_sources(self, svc: M3UService) -> None:
        tracks = [
            _make_local_track(),
            _make_tidal_track(),
        ]
        result = svc.export_to_string(tracks)
        assert "/music/echoes.flac" in result
        assert "# tidal://123456789" in result


# =====================================================================
# Import tests
# =====================================================================


class TestImportFromStringValid:
    """Test import_from_string with valid M3U content."""

    def test_import_local_tracks(
        self, svc: M3UService, db: Database
    ) -> None:
        t1 = _insert_local_track(
            db,
            _make_local_track(
                title="Time", source_id="/music/time.flac"
            ),
        )
        t2 = _insert_local_track(
            db,
            _make_local_track(
                title="Money", source_id="/music/money.flac"
            ),
        )

        content = (
            "#EXTM3U\n"
            "#EXTINF:413,Pink Floyd - Time\n"
            "/music/time.flac\n"
            "#EXTINF:382,Pink Floyd - Money\n"
            "/music/money.flac\n"
        )

        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 2
        assert tracks[0].title == "Time"
        assert tracks[1].title == "Money"

    def test_import_without_extinf(
        self, svc: M3UService, db: Database
    ) -> None:
        """A simple M3U without EXTINF lines should still work."""
        _insert_local_track(
            db,
            _make_local_track(title="Song", source_id="/music/song.mp3"),
        )

        content = "/music/song.mp3\n"
        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 1
        assert tracks[0].title == "Song"

    def test_import_tidal_tracks(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_tidal_track(
            db,
            _make_tidal_track(source_id="777888"),
        )

        content = (
            "#EXTM3U\n"
            "#EXTINF:200,The Weeknd - Blinding Lights\n"
            "# tidal://777888\n"
        )

        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 1
        assert tracks[0].source == Source.TIDAL
        assert tracks[0].source_id == "777888"


class TestImportFromStringRelativePaths:
    """Test import_from_string with relative file paths."""

    def test_import_relative_path(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(
                title="Rel Song", source_id="/base/dir/song.flac"
            ),
        )

        content = "#EXTM3U\n#EXTINF:300,Artist - Rel Song\nsong.flac\n"
        tracks = svc.import_from_string(content, db, base_dir="/base/dir")
        assert len(tracks) == 1
        assert tracks[0].title == "Rel Song"

    def test_import_relative_path_subdirectory(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(
                title="Sub Song", source_id="/base/sub/song.flac"
            ),
        )

        content = "sub/song.flac\n"
        tracks = svc.import_from_string(content, db, base_dir="/base")
        assert len(tracks) == 1
        assert tracks[0].title == "Sub Song"


class TestImportFromStringMissingTracks:
    """Test graceful handling of missing tracks."""

    def test_missing_tracks_skipped(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(
                title="Exists", source_id="/music/exists.flac"
            ),
        )

        content = (
            "#EXTM3U\n"
            "#EXTINF:100,Artist - Missing\n"
            "/music/missing.flac\n"
            "#EXTINF:200,Pink Floyd - Exists\n"
            "/music/exists.flac\n"
        )

        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 1
        assert tracks[0].title == "Exists"

    def test_all_tracks_missing_returns_empty(
        self, svc: M3UService, db: Database
    ) -> None:
        content = (
            "#EXTM3U\n"
            "#EXTINF:100,A - B\n"
            "/no/such/file.flac\n"
        )
        tracks = svc.import_from_string(content, db)
        assert tracks == []


# =====================================================================
# Round-trip tests
# =====================================================================


class TestRoundTrip:
    """Test exporting then re-importing produces the same tracks."""

    def test_round_trip_local(
        self, svc: M3UService, db: Database
    ) -> None:
        t1 = _insert_local_track(
            db,
            _make_local_track(
                title="A", source_id="/music/a.flac", duration=100.0,
            ),
        )
        t2 = _insert_local_track(
            db,
            _make_local_track(
                title="B", source_id="/music/b.flac", duration=200.0,
            ),
        )

        exported = svc.export_to_string([t1, t2], db=db)
        imported = svc.import_from_string(exported, db)

        assert len(imported) == 2
        assert imported[0].id == t1.id
        assert imported[1].id == t2.id

    def test_round_trip_tidal(
        self, svc: M3UService, db: Database
    ) -> None:
        t = _insert_tidal_track(
            db,
            _make_tidal_track(source_id="rt_tidal_1"),
        )

        exported = svc.export_to_string([t])
        imported = svc.import_from_string(exported, db)

        assert len(imported) == 1
        assert imported[0].id == t.id

    def test_round_trip_mixed(
        self, svc: M3UService, db: Database
    ) -> None:
        local = _insert_local_track(
            db,
            _make_local_track(
                title="Local Song",
                source_id="/music/local.flac",
                duration=180.0,
            ),
        )
        tidal = _insert_tidal_track(
            db,
            _make_tidal_track(
                title="Tidal Song",
                source_id="mix_tidal_1",
                duration=240.0,
            ),
        )

        exported = svc.export_to_string([local, tidal], db=db)
        imported = svc.import_from_string(exported, db)

        assert len(imported) == 2
        assert imported[0].id == local.id
        assert imported[1].id == tidal.id


# =====================================================================
# Edge cases
# =====================================================================


class TestEmptyPlaylist:
    """Test empty playlist handling."""

    def test_export_empty_playlist(self, svc: M3UService) -> None:
        result = svc.export_to_string([])
        assert result == ""

    def test_import_empty_string(
        self, svc: M3UService, db: Database
    ) -> None:
        tracks = svc.import_from_string("", db)
        assert tracks == []

    def test_import_header_only(
        self, svc: M3UService, db: Database
    ) -> None:
        tracks = svc.import_from_string("#EXTM3U\n", db)
        assert tracks == []


class TestM3U8Encoding:
    """Test M3U8 (UTF-8) file handling."""

    def test_export_utf8_characters(self, svc: M3UService) -> None:
        track = _make_local_track(
            title="Nuit d'ete",
            artist="Francoise Hardy",
            source_id="/music/nuit.flac",
            duration=180.0,
        )
        result = svc.export_to_string([track])
        assert "Francoise Hardy - Nuit d'ete" in result

    def test_export_to_m3u8_file(
        self, svc: M3UService, db: Database
    ) -> None:
        track = _make_local_track(
            title="Goteborg",
            artist="Swedish Band",
            source_id="/music/goteborg.flac",
        )
        inserted = _insert_local_track(db, track)

        fd, path = tempfile.mkstemp(suffix=".m3u8")
        os.close(fd)
        try:
            svc.export_playlist([inserted], path, db=db)
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()
            assert "Goteborg" in content
            assert "/music/goteborg.flac" in content
        finally:
            os.unlink(path)

    def test_import_m3u8_file(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(
                title="Cafe", source_id="/music/cafe.flac"
            ),
        )

        fd, path = tempfile.mkstemp(suffix=".m3u8")
        os.close(fd)
        try:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(
                    "#EXTM3U\n"
                    "#EXTINF:200,Artist - Cafe\n"
                    "/music/cafe.flac\n"
                )
            tracks = svc.import_playlist(path, db)
            assert len(tracks) == 1
            assert tracks[0].title == "Cafe"
        finally:
            os.unlink(path)

    def test_import_unicode_artist_title(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(
                title="Reve",
                artist="Artiste Francais",
                source_id="/music/reve.flac",
            ),
        )

        content = (
            "#EXTM3U\n"
            "#EXTINF:180,Artiste Francais - Reve\n"
            "/music/reve.flac\n"
        )
        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 1


class TestExportPlaylistFile:
    """Test the export_playlist method (writes to disk)."""

    def test_export_and_reimport(
        self, svc: M3UService, db: Database
    ) -> None:
        t = _insert_local_track(
            db,
            _make_local_track(
                title="Disk Song", source_id="/music/disk.flac"
            ),
        )

        fd, path = tempfile.mkstemp(suffix=".m3u")
        os.close(fd)
        try:
            svc.export_playlist([t], path, db=db)
            reimported = svc.import_playlist(path, db)
            assert len(reimported) == 1
            assert reimported[0].id == t.id
        finally:
            os.unlink(path)


class TestParseExtinf:
    """Test the EXTINF parser."""

    def test_standard_extinf(self, svc: M3UService) -> None:
        info = svc._parse_extinf("#EXTINF:300,Artist - Title")
        assert info["duration"] == 300
        assert info["artist"] == "Artist"
        assert info["title"] == "Title"

    def test_extinf_without_artist_separator(
        self, svc: M3UService
    ) -> None:
        info = svc._parse_extinf("#EXTINF:120,Just A Title")
        assert info["duration"] == 120
        assert info["artist"] is None
        assert info["title"] == "Just A Title"

    def test_extinf_negative_duration(self, svc: M3UService) -> None:
        info = svc._parse_extinf("#EXTINF:-1,Unknown - Song")
        assert info["duration"] == -1
        assert info["artist"] == "Unknown"
        assert info["title"] == "Song"

    def test_extinf_with_multiple_dashes(self, svc: M3UService) -> None:
        info = svc._parse_extinf(
            "#EXTINF:200,AC/DC - Back In Black - Remastered"
        )
        assert info["artist"] == "AC/DC"
        assert info["title"] == "Back In Black - Remastered"


class TestIgnoredLines:
    """Test that comments and blank lines are properly ignored."""

    def test_blank_lines_ignored(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(title="X", source_id="/music/x.flac"),
        )

        content = (
            "#EXTM3U\n"
            "\n"
            "\n"
            "#EXTINF:100,Artist - X\n"
            "\n"
            "/music/x.flac\n"
            "\n"
        )
        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 1

    def test_unknown_comments_ignored(
        self, svc: M3UService, db: Database
    ) -> None:
        _insert_local_track(
            db,
            _make_local_track(title="Y", source_id="/music/y.flac"),
        )

        content = (
            "#EXTM3U\n"
            "# This is a custom comment\n"
            "#PLAYLIST:My Cool Playlist\n"
            "#EXTINF:100,Artist - Y\n"
            "/music/y.flac\n"
        )
        tracks = svc.import_from_string(content, db)
        assert len(tracks) == 1
