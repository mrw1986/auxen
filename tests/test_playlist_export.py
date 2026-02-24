"""Tests for the playlist export flow in playlist_view.py.

These tests cover the export handler precondition checks and
the M3U writing integration, without requiring a running GTK
application (no real FileDialog is opened).
"""

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


class TestExportPreconditions:
    """Test that export handles edge cases before the dialog opens."""

    def test_empty_tracks_produces_empty_string(self, svc: M3UService) -> None:
        """Export with no tracks should produce an empty string."""
        result = svc.export_to_string([])
        assert result == ""

    def test_none_duration_uses_negative_one(self, svc: M3UService) -> None:
        """Tracks with no duration should get -1 in EXTINF."""
        track = _make_local_track(duration=None)
        result = svc.export_to_string([track])
        assert "#EXTINF:-1," in result


class TestExportWriteToFile:
    """Test the full export-to-file flow, simulating what the dialog
    callback does after the user picks a save location."""

    def test_export_local_tracks_to_file(
        self, svc: M3UService, db: Database
    ) -> None:
        t = _insert_local_track(
            db,
            _make_local_track(
                title="Test Song",
                source_id="/music/test.flac",
                duration=240.0,
            ),
        )

        fd, path = tempfile.mkstemp(suffix=".m3u")
        os.close(fd)
        try:
            svc.export_playlist([t], path, db=db)
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()

            assert "#EXTM3U" in content
            assert "#EXTINF:240,Pink Floyd - Test Song" in content
            assert "/music/test.flac" in content
        finally:
            os.unlink(path)

    def test_export_tidal_track_to_file(
        self, svc: M3UService
    ) -> None:
        track = _make_tidal_track(source_id="99887766")

        fd, path = tempfile.mkstemp(suffix=".m3u")
        os.close(fd)
        try:
            svc.export_playlist([track], path)
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()

            assert "#EXTM3U" in content
            assert "# tidal://99887766" in content
        finally:
            os.unlink(path)

    def test_export_mixed_tracks_to_file(
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
        tidal = _make_tidal_track(
            title="Tidal Song",
            source_id="mix_001",
            duration=200.0,
        )

        fd, path = tempfile.mkstemp(suffix=".m3u")
        os.close(fd)
        try:
            svc.export_playlist([local, tidal], path, db=db)
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()

            assert "/music/local.flac" in content
            assert "# tidal://mix_001" in content
            assert content.startswith("#EXTM3U\n")
        finally:
            os.unlink(path)

    def test_export_file_has_trailing_newline(
        self, svc: M3UService
    ) -> None:
        """M3U files should end with a newline (POSIX compliance)."""
        track = _make_local_track()

        fd, path = tempfile.mkstemp(suffix=".m3u")
        os.close(fd)
        try:
            svc.export_playlist([track], path)
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()
            assert content.endswith("\n")
        finally:
            os.unlink(path)

    def test_export_special_characters_in_filename(
        self, svc: M3UService
    ) -> None:
        """Filenames with special characters should not crash export."""
        track = _make_local_track(
            title="Rock & Roll",
            artist="Led Zeppelin",
            source_id="/music/rock_and_roll.flac",
        )

        fd, path = tempfile.mkstemp(suffix=".m3u")
        os.close(fd)
        try:
            svc.export_playlist([track], path)
            with open(path, "r", encoding="utf-8") as fh:
                content = fh.read()
            assert "Led Zeppelin - Rock & Roll" in content
        finally:
            os.unlink(path)


class TestExportFilterSetup:
    """Test that the GTK FileDialog filter setup is correct.

    These tests verify that Gio.ListStore + FileFilter can be
    created without errors (the core fix for the broken export).
    """

    def test_filter_store_creation(self) -> None:
        """Verify Gio.ListStore with Gtk.FileFilter works."""
        import gi

        gi.require_version("Gtk", "4.0")
        from gi.repository import Gio, Gtk

        m3u_filter = Gtk.FileFilter()
        m3u_filter.set_name("M3U Playlists")
        m3u_filter.add_pattern("*.m3u")
        m3u_filter.add_pattern("*.m3u8")

        all_filter = Gtk.FileFilter()
        all_filter.set_name("All Files")
        all_filter.add_pattern("*")

        store = Gio.ListStore.new(Gtk.FileFilter)
        store.append(m3u_filter)
        store.append(all_filter)

        assert store.get_n_items() == 2

    def test_file_dialog_with_filters(self) -> None:
        """Verify FileDialog accepts the filter list model."""
        import gi

        gi.require_version("Gtk", "4.0")
        from gi.repository import Gio, Gtk

        dialog = Gtk.FileDialog()
        dialog.set_title("Export Playlist")
        dialog.set_initial_name("Test.m3u")

        m3u_filter = Gtk.FileFilter()
        m3u_filter.set_name("M3U Playlists")
        m3u_filter.add_pattern("*.m3u")

        all_filter = Gtk.FileFilter()
        all_filter.set_name("All Files")
        all_filter.add_pattern("*")

        store = Gio.ListStore.new(Gtk.FileFilter)
        store.append(m3u_filter)
        store.append(all_filter)

        dialog.set_filters(store)
        dialog.set_default_filter(m3u_filter)

        # Verify setup
        assert dialog.get_filters() is not None
        assert dialog.get_filters().get_n_items() == 2
        assert dialog.get_default_filter() is not None
        assert dialog.get_initial_name() == "Test.m3u"
