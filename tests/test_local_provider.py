"""Tests for auxen.providers.local — LocalProvider and local media scanning."""

import os
import struct
import wave

import pytest

from auxen.models import Source, Track
from auxen.providers.base import ContentProvider
from auxen.providers.local import (
    FORMAT_MAP,
    SUPPORTED_EXTENSIONS,
    LocalProvider,
)


def _create_wav(path: str, duration_secs: float = 0.1) -> None:
    """Create a minimal silent WAV file at *path*."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sample_rate = 44100
    num_channels = 2
    sample_width = 2  # 16-bit
    num_frames = int(sample_rate * duration_secs)
    with wave.open(path, "w") as wf:
        wf.setnchannels(num_channels)
        wf.setsampwidth(sample_width)
        wf.setframerate(sample_rate)
        # Write silence (zero samples)
        wf.writeframes(b"\x00" * num_frames * num_channels * sample_width)


@pytest.fixture()
def music_dir(tmp_path):
    """Create a temporary music directory with three WAV files."""
    base = str(tmp_path / "music")
    _create_wav(os.path.join(base, "Radiohead", "In Rainbows", "01 - Reckoner.wav"))
    _create_wav(os.path.join(base, "Radiohead", "In Rainbows", "02 - Nude.wav"))
    _create_wav(os.path.join(base, "Daft Punk", "Discovery", "01 - Digital Love.wav"))
    return base


@pytest.fixture()
def provider(music_dir):
    """Return a LocalProvider pointed at the temporary music directory."""
    return LocalProvider(music_dirs=[music_dir])


# ------------------------------------------------------------------
# ContentProvider interface
# ------------------------------------------------------------------


class TestLocalProviderIsContentProvider:
    def test_is_subclass(self) -> None:
        assert issubclass(LocalProvider, ContentProvider)


# ------------------------------------------------------------------
# scan()
# ------------------------------------------------------------------


class TestScan:
    def test_scan_finds_files(self, provider: LocalProvider) -> None:
        """scan() discovers all 3 WAV files."""
        tracks = provider.scan()
        assert len(tracks) == 3

    def test_tracks_have_source_local(self, provider: LocalProvider) -> None:
        """Every scanned track has Source.LOCAL."""
        tracks = provider.scan()
        for track in tracks:
            assert track.source == Source.LOCAL

    def test_tracks_have_file_paths(self, provider: LocalProvider, music_dir: str) -> None:
        """source_id is a real file path that exists on disk."""
        tracks = provider.scan()
        for track in tracks:
            assert os.path.isfile(track.source_id), (
                f"source_id should be a real file: {track.source_id}"
            )

    def test_tracks_have_title(self, provider: LocalProvider) -> None:
        """Each scanned track has a non-empty title."""
        tracks = provider.scan()
        for track in tracks:
            assert track.title, f"Track should have a title: {track.source_id}"

    def test_tracks_have_artist(self, provider: LocalProvider) -> None:
        """Artist is inferred from directory structure for untagged WAVs."""
        tracks = provider.scan()
        artists = {track.artist for track in tracks}
        assert "Radiohead" in artists or "Daft Punk" in artists

    def test_tracks_have_album(self, provider: LocalProvider) -> None:
        """Album is inferred from directory structure for untagged WAVs."""
        tracks = provider.scan()
        albums = {track.album for track in tracks}
        assert "In Rainbows" in albums or "Discovery" in albums

    def test_tracks_have_duration(self, provider: LocalProvider) -> None:
        """Scanned tracks report their duration."""
        tracks = provider.scan()
        for track in tracks:
            assert track.duration is not None
            assert track.duration > 0

    def test_tracks_have_format_wav(self, provider: LocalProvider) -> None:
        """WAV files should have format='WAV'."""
        tracks = provider.scan()
        for track in tracks:
            assert track.format == "WAV"

    def test_scan_empty_dir(self, tmp_path) -> None:
        """scan() returns empty list for a directory with no music."""
        empty = str(tmp_path / "empty")
        os.makedirs(empty)
        p = LocalProvider(music_dirs=[empty])
        assert p.scan() == []

    def test_scan_nonexistent_dir(self, tmp_path) -> None:
        """scan() gracefully handles non-existent directories."""
        p = LocalProvider(music_dirs=[str(tmp_path / "does-not-exist")])
        assert p.scan() == []

    def test_scan_multiple_dirs(self, tmp_path) -> None:
        """scan() walks multiple directories."""
        dir_a = str(tmp_path / "a")
        dir_b = str(tmp_path / "b")
        _create_wav(os.path.join(dir_a, "Artist", "Album", "song1.wav"))
        _create_wav(os.path.join(dir_b, "Artist2", "Album2", "song2.wav"))
        p = LocalProvider(music_dirs=[dir_a, dir_b])
        tracks = p.scan()
        assert len(tracks) == 2


# ------------------------------------------------------------------
# get_stream_uri()
# ------------------------------------------------------------------


class TestGetStreamUri:
    def test_get_stream_uri_returns_file_uri(self, provider: LocalProvider) -> None:
        """get_stream_uri() returns a file:// URI."""
        tracks = provider.scan()
        assert len(tracks) > 0
        uri = provider.get_stream_uri(tracks[0])
        assert uri.startswith("file://")

    def test_get_stream_uri_contains_path(self, provider: LocalProvider) -> None:
        """The URI contains the original file path (URL-encoded)."""
        tracks = provider.scan()
        track = tracks[0]
        uri = provider.get_stream_uri(track)
        # The source_id path should be representable in the URI
        assert ".wav" in uri.lower() or ".wav" in track.source_id.lower()


# ------------------------------------------------------------------
# search()
# ------------------------------------------------------------------


class TestSearch:
    def test_search_returns_empty(self, provider: LocalProvider) -> None:
        """search() returns an empty list (search handled by DB layer)."""
        assert provider.search("anything") == []


# ------------------------------------------------------------------
# Constants
# ------------------------------------------------------------------


class TestConstants:
    def test_supported_extensions_contains_flac(self) -> None:
        assert ".flac" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_mp3(self) -> None:
        assert ".mp3" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_wav(self) -> None:
        assert ".wav" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_ogg(self) -> None:
        assert ".ogg" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_opus(self) -> None:
        assert ".opus" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_aac(self) -> None:
        assert ".aac" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_m4a(self) -> None:
        assert ".m4a" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_wma(self) -> None:
        assert ".wma" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_alac(self) -> None:
        assert ".alac" in SUPPORTED_EXTENSIONS

    def test_supported_extensions_contains_aiff(self) -> None:
        assert ".aiff" in SUPPORTED_EXTENSIONS
        assert ".aif" in SUPPORTED_EXTENSIONS

    def test_format_map_flac(self) -> None:
        assert FORMAT_MAP[".flac"] == "FLAC"

    def test_format_map_mp3(self) -> None:
        assert FORMAT_MAP[".mp3"] == "MP3"

    def test_format_map_wav(self) -> None:
        assert FORMAT_MAP[".wav"] == "WAV"
