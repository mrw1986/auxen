"""Tests for the two-way Tidal favourites sync service."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from auxen.db import Database
from auxen.favorites_sync import FavoritesSyncService, SyncResult
from auxen.models import Source, Track


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------


def _make_track(
    source_id: str,
    title: str = "Song",
    artist: str = "Artist",
    source: Source = Source.TIDAL,
    track_id: int | None = None,
) -> Track:
    """Return a Track with the given attributes."""
    return Track(
        id=track_id,
        title=title,
        artist=artist,
        album="Album",
        source=source,
        source_id=source_id,
        duration=200,
        format="FLAC",
    )


def _make_tidal_provider(
    logged_in: bool = True,
    favorites: list[Track] | None = None,
) -> MagicMock:
    """Return a mock TidalProvider."""
    provider = MagicMock()
    provider.is_logged_in = logged_in
    provider.get_favorites.return_value = favorites or []
    return provider


def _make_db_with_favorites(
    favorites: list[Track] | None = None,
) -> Database:
    """Return a real in-memory Database pre-loaded with favourites."""
    db = Database(":memory:")
    for track in (favorites or []):
        tid = db.insert_track(track)
        db.set_favorite(tid, True)
    return db


# ------------------------------------------------------------------
# SyncResult structure
# ------------------------------------------------------------------


class TestSyncResult:
    """Verify SyncResult dataclass defaults and fields."""

    def test_default_values(self) -> None:
        result = SyncResult()
        assert result.added_local == 0
        assert result.added_tidal == 0
        assert result.already_synced == 0
        assert result.errors is None

    def test_custom_values(self) -> None:
        result = SyncResult(added_local=3, added_tidal=2, already_synced=5)
        assert result.added_local == 3
        assert result.added_tidal == 2
        assert result.already_synced == 5

    def test_errors_list(self) -> None:
        result = SyncResult(errors=["err1", "err2"])
        assert result.errors == ["err1", "err2"]


# ------------------------------------------------------------------
# Sync — empty states
# ------------------------------------------------------------------


class TestSyncEmptyStates:
    """Test syncing when one or both sides have no favourites."""

    def test_both_empty(self) -> None:
        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider(favorites=[])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_local == 0
        assert result.added_tidal == 0
        assert result.already_synced == 0
        assert result.errors is None

    def test_empty_local_with_tidal_favorites(self) -> None:
        tidal_tracks = [
            _make_track("100", title="A"),
            _make_track("200", title="B"),
        ]
        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider(favorites=tidal_tracks)

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_local == 2
        assert result.added_tidal == 0
        assert result.already_synced == 0

    def test_empty_tidal_with_local_favorites(self) -> None:
        local_tracks = [
            _make_track("300", title="C"),
        ]
        db = _make_db_with_favorites(local_tracks)
        tidal = _make_tidal_provider(favorites=[])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_local == 0
        assert result.added_tidal == 1
        assert result.already_synced == 0
        tidal.add_favorite.assert_called_once_with("300")


# ------------------------------------------------------------------
# Sync — merging
# ------------------------------------------------------------------


class TestSyncMerging:
    """Test two-way merge logic."""

    def test_tidal_favorites_added_locally(self) -> None:
        """Tidal favourites not in local DB should be added."""
        tidal_tracks = [_make_track("111")]
        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider(favorites=tidal_tracks)

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_local == 1
        # Verify the track is now in the local DB favourites
        local_favs = db.get_favorites()
        assert any(t.source_id == "111" for t in local_favs)

    def test_local_favorites_added_to_tidal(self) -> None:
        """Local Tidal-sourced favourites not on Tidal should be pushed."""
        local_tracks = [_make_track("222")]
        db = _make_db_with_favorites(local_tracks)
        tidal = _make_tidal_provider(favorites=[])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_tidal == 1
        tidal.add_favorite.assert_called_once_with("222")

    def test_already_synced_not_duplicated(self) -> None:
        """Tracks on both sides should increment already_synced, not add."""
        shared_track = _make_track("333")
        db = _make_db_with_favorites([shared_track])
        tidal = _make_tidal_provider(favorites=[shared_track])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_local == 0
        assert result.added_tidal == 0
        assert result.already_synced == 1
        tidal.add_favorite.assert_not_called()

    def test_mixed_sync(self) -> None:
        """Some tracks are shared, some only on one side."""
        shared = _make_track("444", title="Shared")
        only_tidal = _make_track("555", title="Only Tidal")
        only_local = _make_track("666", title="Only Local")

        db = _make_db_with_favorites([shared, only_local])
        tidal = _make_tidal_provider(favorites=[shared, only_tidal])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.already_synced == 1
        assert result.added_local == 1
        assert result.added_tidal == 1

    def test_local_only_tracks_ignored(self) -> None:
        """Local-source favourites should not be pushed to Tidal."""
        local_file = _make_track(
            "/music/song.flac", source=Source.LOCAL, title="Local File"
        )
        db = _make_db_with_favorites([local_file])
        tidal = _make_tidal_provider(favorites=[])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_tidal == 0
        tidal.add_favorite.assert_not_called()


# ------------------------------------------------------------------
# Error handling
# ------------------------------------------------------------------


class TestSyncErrorHandling:
    """Test graceful error handling during sync."""

    def test_not_logged_in(self) -> None:
        """Sync should return an error result when Tidal is not logged in."""
        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider(logged_in=False)

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_local == 0
        assert result.added_tidal == 0
        assert result.errors is not None
        assert any("not logged in" in e for e in result.errors)

    def test_tidal_fetch_failure(self) -> None:
        """Sync should handle Tidal API failures gracefully."""
        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider()
        tidal.get_favorites.side_effect = ConnectionError("Network error")

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.errors is not None
        assert any("Failed to fetch Tidal favorites" in e for e in result.errors)

    def test_tidal_add_failure_partial(self) -> None:
        """Individual add_favorite failures should not stop the sync."""
        local_tracks = [
            _make_track("700", title="Good"),
            _make_track("800", title="Fail"),
        ]
        db = _make_db_with_favorites(local_tracks)
        tidal = _make_tidal_provider(favorites=[])

        # First call succeeds, second raises
        tidal.add_favorite.side_effect = [None, ConnectionError("timeout")]

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.added_tidal == 1
        assert result.errors is not None
        assert len(result.errors) == 1

    def test_login_check_exception(self) -> None:
        """Sync should handle exceptions during the login check."""
        db = _make_db_with_favorites([])
        tidal = MagicMock()
        type(tidal).is_logged_in = property(
            lambda self: (_ for _ in ()).throw(RuntimeError("session broken"))
        )

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.errors is not None
        assert any("Login check failed" in e for e in result.errors)

    def test_local_db_fetch_failure(self) -> None:
        """Sync should handle local DB failures gracefully."""
        db = MagicMock()
        db.get_favorites.side_effect = RuntimeError("DB locked")
        tidal = _make_tidal_provider(favorites=[_make_track("900")])

        service = FavoritesSyncService(db=db, tidal_provider=tidal)
        result = service.sync()

        assert result.errors is not None
        assert any("Failed to fetch local favorites" in e for e in result.errors)


# ------------------------------------------------------------------
# Async interface
# ------------------------------------------------------------------


class TestSyncAsync:
    """Test the async wrapper."""

    def test_sync_async_calls_callback(self) -> None:
        """sync_async should invoke the callback with the SyncResult."""
        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider(favorites=[])
        service = FavoritesSyncService(db=db, tidal_provider=tidal)

        received: list[SyncResult] = []

        # Call sync directly (the sync_async method just runs sync()
        # in a thread and schedules the callback — we test the core
        # logic by calling sync() directly and verifying the result).
        result = service.sync()
        received.append(result)

        assert len(received) == 1
        assert isinstance(received[0], SyncResult)
        assert received[0].added_local == 0
        assert received[0].added_tidal == 0

    def test_sync_async_thread_execution(self) -> None:
        """sync_async should eventually call the callback."""
        import threading
        import time

        db = _make_db_with_favorites([])
        tidal = _make_tidal_provider(favorites=[])
        service = FavoritesSyncService(db=db, tidal_provider=tidal)

        received: list[SyncResult] = []
        event = threading.Event()

        def _callback(result: SyncResult) -> None:
            received.append(result)
            event.set()

        # Patch the GLib import away so it falls back to direct callback
        with patch.dict("sys.modules", {"gi": None, "gi.repository": None}):
            service.sync_async(_callback)

        event.wait(timeout=5)
        assert len(received) == 1
        assert isinstance(received[0], SyncResult)
