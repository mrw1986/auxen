"""Tests for auxen.lyrics — LyricsService."""

from __future__ import annotations

import os
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

from auxen.lyrics import LyricsService
from auxen.models import Source, Track


def _make_track(
    title: str = "Test Song",
    artist: str = "Test Artist",
    source: Source = Source.LOCAL,
    source_id: str = "/fake/path.flac",
    **kwargs,
) -> Track:
    """Helper to build a Track for tests."""
    return Track(
        title=title,
        artist=artist,
        source=source,
        source_id=source_id,
        **kwargs,
    )


class TestLyricsServiceCache:
    """Verify that lyrics are cached after the first fetch."""

    def test_cache_hit_avoids_refetch(self) -> None:
        service = LyricsService()
        track = _make_track(source=Source.TIDAL, source_id="12345")

        # First call — miss (Tidal tracks always return None)
        result1 = service.get_lyrics(track)
        assert result1 is None

        # Manually inject into cache
        service._cache[("Test Song", "Test Artist")] = "cached lyrics"

        result2 = service.get_lyrics(track)
        assert result2 == "cached lyrics"

    def test_clear_cache(self) -> None:
        service = LyricsService()
        service._cache[("X", "Y")] = "hello"
        service.clear_cache()
        assert len(service._cache) == 0


class TestLyricsServiceTidalTrack:
    """Tidal tracks have no local file; lyrics should always be None."""

    def test_tidal_track_returns_none(self) -> None:
        service = LyricsService()
        track = _make_track(source=Source.TIDAL, source_id="999")
        assert service.get_lyrics(track) is None


class TestLyricsServiceMissingFile:
    """A local track whose file does not exist should return None."""

    def test_nonexistent_file_returns_none(self) -> None:
        service = LyricsService()
        track = _make_track(source_id="/no/such/file.flac")
        assert service.get_lyrics(track) is None


class TestLyricsServiceLrcFile:
    """Test sidecar .lrc file reading."""

    def test_lrc_file_found(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            audio_path = os.path.join(tmp, "song.wav")
            lrc_path = os.path.join(tmp, "song.lrc")

            # Create a dummy audio file (empty — won't have embedded lyrics)
            Path(audio_path).touch()

            # Create the sidecar LRC
            Path(lrc_path).write_text(
                "[00:01.00]First line\n[00:05.00]Second line\n",
                encoding="utf-8",
            )

            service = LyricsService()
            track = _make_track(source_id=audio_path)
            result = service.get_lyrics(track)
            assert result is not None
            assert "First line" in result
            assert "Second line" in result

    def test_no_lrc_file_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            audio_path = os.path.join(tmp, "song.wav")
            Path(audio_path).touch()

            service = LyricsService()
            track = _make_track(source_id=audio_path)
            result = service.get_lyrics(track)
            assert result is None


class TestLyricsServiceEmbeddedFlac:
    """Test embedded FLAC lyrics extraction."""

    def test_flac_with_lyrics_tag(self) -> None:
        """Mock mutagen FLAC to return lyrics from the LYRICS tag."""
        service = LyricsService()

        mock_flac = MagicMock()
        mock_flac.tags = {"LYRICS": ["Hello World\nLine Two"]}

        with patch("auxen.lyrics.LyricsService._read_flac_lyrics") as mock_read:
            mock_read.return_value = "Hello World\nLine Two"

            with tempfile.TemporaryDirectory() as tmp:
                audio_path = os.path.join(tmp, "test.flac")
                Path(audio_path).touch()

                track = _make_track(source_id=audio_path)
                # Directly test the static method
                result = LyricsService._read_flac_lyrics(audio_path)
                assert result == "Hello World\nLine Two"

    def test_flac_without_lyrics_returns_none(self) -> None:
        """A FLAC file with no lyrics tags should return None."""
        service = LyricsService()

        with patch("auxen.lyrics.LyricsService._read_flac_lyrics") as mock_read:
            mock_read.return_value = None

            with tempfile.TemporaryDirectory() as tmp:
                audio_path = os.path.join(tmp, "test.flac")
                Path(audio_path).touch()

                result = LyricsService._read_flac_lyrics(audio_path)
                assert result is None


class TestLyricsServiceEmbeddedMp3:
    """Test embedded MP3 lyrics extraction."""

    def test_mp3_with_uslt_frame(self) -> None:
        service = LyricsService()

        with patch("auxen.lyrics.LyricsService._read_mp3_lyrics") as mock_read:
            mock_read.return_value = "MP3 lyrics content"

            result = LyricsService._read_mp3_lyrics("/fake/test.mp3")
            assert result == "MP3 lyrics content"


class TestLyricsServiceEmbeddedM4a:
    """Test embedded M4A/AAC lyrics extraction."""

    def test_m4a_with_lyrics_atom(self) -> None:
        service = LyricsService()

        with patch("auxen.lyrics.LyricsService._read_m4a_lyrics") as mock_read:
            mock_read.return_value = "M4A lyrics content"

            result = LyricsService._read_m4a_lyrics("/fake/test.m4a")
            assert result == "M4A lyrics content"


class TestLyricsServiceAsync:
    """Test the async callback mechanism."""

    def test_async_callback_invoked(self) -> None:
        """get_lyrics_async should call the callback with the result."""
        service = LyricsService()
        track = _make_track(source=Source.TIDAL, source_id="999")

        results: list = []
        event = threading.Event()

        def callback(lyrics):
            results.append(lyrics)
            event.set()

        # Patch GLib.idle_add to call the function synchronously
        with patch("gi.repository.GLib.idle_add", side_effect=lambda fn, *a: fn(*a)):
            service.get_lyrics_async(track, callback)

        event.wait(timeout=5)
        assert len(results) == 1
        assert results[0] is None  # Tidal track -> no lyrics

    def test_async_callback_with_cached_lyrics(self) -> None:
        """Async callback should return cached lyrics."""
        service = LyricsService()
        service._cache[("CachedSong", "CachedArtist")] = "cached lyrics text"
        track = _make_track(
            title="CachedSong",
            artist="CachedArtist",
            source=Source.TIDAL,
            source_id="123",
        )

        results: list = []
        event = threading.Event()

        def callback(lyrics):
            results.append(lyrics)
            event.set()

        with patch("gi.repository.GLib.idle_add", side_effect=lambda fn, *a: fn(*a)):
            service.get_lyrics_async(track, callback)

        event.wait(timeout=5)
        assert len(results) == 1
        assert results[0] == "cached lyrics text"


class TestLyricsServiceReadEmbeddedDispatch:
    """Test the format dispatch in _read_embedded_lyrics."""

    def test_dispatch_flac(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "test.flac")
            Path(path).touch()

            with patch.object(
                LyricsService, "_read_flac_lyrics", return_value="flac lyrics"
            ) as mock:
                result = LyricsService._read_embedded_lyrics(path)
                mock.assert_called_once_with(path)
                assert result == "flac lyrics"

    def test_dispatch_mp3(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "test.mp3")
            Path(path).touch()

            with patch.object(
                LyricsService, "_read_mp3_lyrics", return_value="mp3 lyrics"
            ) as mock:
                result = LyricsService._read_embedded_lyrics(path)
                mock.assert_called_once_with(path)
                assert result == "mp3 lyrics"

    def test_dispatch_m4a(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "test.m4a")
            Path(path).touch()

            with patch.object(
                LyricsService, "_read_m4a_lyrics", return_value="m4a lyrics"
            ) as mock:
                result = LyricsService._read_embedded_lyrics(path)
                mock.assert_called_once_with(path)
                assert result == "m4a lyrics"

    def test_dispatch_unsupported_extension(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "test.wav")
            Path(path).touch()

            result = LyricsService._read_embedded_lyrics(path)
            assert result is None
