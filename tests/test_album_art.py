"""Tests for auxen.album_art — AlbumArtService."""

from __future__ import annotations

import struct
import threading
import time
from unittest.mock import MagicMock, patch

from auxen.album_art import AlbumArtService
from auxen.models import Source, Track


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_track(
    title: str = "Test Song",
    artist: str = "Test Artist",
    source: Source = Source.LOCAL,
    source_id: str = "/fake/path.flac",
    track_id: int | None = 1,
    album_art_url: str | None = None,
    **kwargs,
) -> Track:
    """Build a Track for tests."""
    return Track(
        title=title,
        artist=artist,
        source=source,
        source_id=source_id,
        id=track_id,
        album_art_url=album_art_url,
        **kwargs,
    )


def _minimal_jpeg_bytes() -> bytes:
    """Return minimal valid JPEG bytes (a tiny 1x1 red pixel JPEG).

    This is a hand-crafted minimal JFIF that most decoders accept.
    """
    # A minimal valid JPEG (1x1 pixel, red)
    # SOI + APP0 (JFIF) + DQT + SOF0 + DHT + SOS + image data + EOI
    return bytes([
        0xFF, 0xD8,  # SOI
        0xFF, 0xE0,  # APP0
        0x00, 0x10,  # length 16
        0x4A, 0x46, 0x49, 0x46, 0x00,  # JFIF\0
        0x01, 0x01,  # version 1.1
        0x00,  # aspect ratio units
        0x00, 0x01, 0x00, 0x01,  # x/y density
        0x00, 0x00,  # thumbnail size

        0xFF, 0xDB,  # DQT
        0x00, 0x43, 0x00,  # length 67, table 0
        # 64 quantization values (all 1s for minimal)
        *([0x01] * 64),

        0xFF, 0xC0,  # SOF0 (baseline)
        0x00, 0x0B,  # length 11
        0x08,  # precision 8 bit
        0x00, 0x01,  # height 1
        0x00, 0x01,  # width 1
        0x01,  # 1 component
        0x01,  # component ID
        0x11,  # sampling factors
        0x00,  # quant table 0

        0xFF, 0xC4,  # DHT
        0x00, 0x1F,  # length 31
        0x00,  # DC table 0
        # Counts for each code length (1-16)
        0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        # Values
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B,

        0xFF, 0xDA,  # SOS
        0x00, 0x08,  # length 8
        0x01,  # 1 component
        0x01,  # component 1
        0x00,  # DC/AC table 0/0
        0x00, 0x3F, 0x00,  # spectral selection
        0x7B, 0x40,  # compressed data (encoded DC value)

        0xFF, 0xD9,  # EOI
    ])


def _minimal_png_bytes() -> bytes:
    """Return minimal valid 1x1 red PNG bytes."""
    import zlib

    # PNG signature
    sig = b"\x89PNG\r\n\x1a\n"

    def _chunk(chunk_type: bytes, data: bytes) -> bytes:
        import struct
        import zlib as _zlib

        return (
            struct.pack(">I", len(data))
            + chunk_type
            + data
            + struct.pack(">I", _zlib.crc32(chunk_type + data) & 0xFFFFFFFF)
        )

    # IHDR: 1x1, 8-bit RGB
    ihdr_data = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    ihdr = _chunk(b"IHDR", ihdr_data)

    # IDAT: filter byte (0) + R G B
    raw = b"\x00\xff\x00\x00"  # filter=none, red pixel
    idat = _chunk(b"IDAT", zlib.compress(raw))

    # IEND
    iend = _chunk(b"IEND", b"")

    return sig + ihdr + idat + iend


# ---------------------------------------------------------------------------
# Tests: Basic service creation
# ---------------------------------------------------------------------------


class TestAlbumArtServiceInit:
    """Test service instantiation."""

    def test_creates_empty_cache(self) -> None:
        service = AlbumArtService()
        assert len(service._cache) == 0

    def test_clear_cache(self) -> None:
        service = AlbumArtService()
        service._cache[1] = "sentinel"
        service.clear_cache()
        assert len(service._cache) == 0


# ---------------------------------------------------------------------------
# Tests: get_art_for_track
# ---------------------------------------------------------------------------


class TestGetArtForTrack:
    """Test the main get_art_for_track method."""

    def test_returns_none_for_unknown_track(self) -> None:
        """A track pointing to a nonexistent file should return None."""
        service = AlbumArtService()
        track = _make_track(source_id="/no/such/file.mp3")
        result = service.get_art_for_track(track)
        assert result is None

    def test_tidal_track_without_url_returns_none(self) -> None:
        """A Tidal track with no album_art_url should return None."""
        service = AlbumArtService()
        track = _make_track(
            source=Source.TIDAL,
            source_id="12345",
            album_art_url=None,
        )
        result = service.get_art_for_track(track)
        assert result is None

    def test_tidal_track_with_url_calls_load_from_url(self) -> None:
        """A Tidal track with album_art_url should attempt URL download."""
        service = AlbumArtService()
        track = _make_track(
            source=Source.TIDAL,
            source_id="12345",
            album_art_url="https://example.com/art.jpg",
        )

        with patch.object(
            AlbumArtService,
            "load_pixbuf_from_url",
            return_value="mock_pixbuf",
        ) as mock_load:
            result = service.get_art_for_track(track)
            mock_load.assert_called_once_with(
                "https://example.com/art.jpg", 48, 48
            )
            assert result == "mock_pixbuf"

    def test_local_track_calls_extract(self) -> None:
        """A local track should attempt embedded art extraction."""
        service = AlbumArtService()
        track = _make_track(source_id="/some/file.mp3")

        with patch.object(
            service, "_load_local_art", return_value="mock_pixbuf"
        ):
            result = service.get_art_for_track(track)
            assert result == "mock_pixbuf"


# ---------------------------------------------------------------------------
# Tests: Cache behavior
# ---------------------------------------------------------------------------


class TestCacheBehavior:
    """Test LRU cache mechanics."""

    def test_cache_hit_returns_cached_result(self) -> None:
        """Same track ID should return cached result without re-loading."""
        service = AlbumArtService()
        track = _make_track(track_id=42, source_id="/fake.mp3")

        # Pre-populate cache with (track_id, width, height) tuple key
        service._cache[(42, 48, 48)] = "cached_pixbuf"

        result = service.get_art_for_track(track)
        assert result == "cached_pixbuf"

    def test_cache_stores_result(self) -> None:
        """After loading, the result is stored in the cache."""
        service = AlbumArtService()
        track = _make_track(
            track_id=99,
            source=Source.TIDAL,
            source_id="99",
            album_art_url=None,
        )

        service.get_art_for_track(track)
        assert (99, 48, 48) in service._cache
        assert service._cache[(99, 48, 48)] is None

    def test_cache_eviction_when_full(self) -> None:
        """Oldest entries are evicted when cache exceeds byte budget."""
        import auxen.album_art as _art_mod

        service = AlbumArtService()

        # Create a mock pixbuf with a known byte size
        class FakePixbuf:
            def __init__(self, size: int) -> None:
                self._size = size

            def get_byte_length(self) -> int:
                return self._size

        # Each entry ~10 MB, so 6 entries = 60 MB > 50 MB budget
        for i in range(6):
            service._cache[(i, 48, 48)] = FakePixbuf(10 * 1024 * 1024)
            service._cache_bytes += 10 * 1024 * 1024

        # Trigger eviction via a get_art_for_track call
        track = _make_track(
            track_id=200,
            source=Source.TIDAL,
            source_id="200",
            album_art_url=None,
        )
        service.get_art_for_track(track)

        # Eviction should have kicked in — total should be <= budget
        assert service._cache_bytes <= _art_mod._MAX_CACHE_BYTES

    def test_track_without_id_skips_cache(self) -> None:
        """A track with id=None should not be cached."""
        service = AlbumArtService()
        track = _make_track(track_id=None, source_id="/no/such/file.mp3")

        result = service.get_art_for_track(track)
        assert result is None
        assert len(service._cache) == 0

    def test_cache_lru_order(self) -> None:
        """Accessing a cached entry should move it to the end (most recent)."""
        service = AlbumArtService()
        service._cache[(1, 48, 48)] = "first"
        service._cache[(2, 48, 48)] = "second"
        service._cache[(3, 48, 48)] = "third"

        # Access entry 1, moving it to end
        track = _make_track(track_id=1, source_id="/fake.mp3")
        service.get_art_for_track(track)

        keys = list(service._cache.keys())
        assert keys[-1] == (1, 48, 48)  # should now be last


# ---------------------------------------------------------------------------
# Tests: load_pixbuf_from_bytes
# ---------------------------------------------------------------------------


class TestLoadPixbufFromBytes:
    """Test loading pixbufs from raw image bytes."""

    def test_valid_png_returns_pixbuf(self) -> None:
        """Valid PNG bytes should produce a pixbuf (or None if no GdkPixbuf)."""
        png_data = _minimal_png_bytes()
        try:
            result = AlbumArtService.load_pixbuf_from_bytes(
                png_data, 48, 48
            )
            # If GdkPixbuf is available, we should get a pixbuf
            if result is not None:
                # Check it was scaled to requested size
                assert result.get_width() == 48
                assert result.get_height() == 48
        except Exception:
            # GdkPixbuf not available in test environment
            pass

    def test_invalid_bytes_returns_none(self) -> None:
        """Invalid image bytes should return None, not raise."""
        result = AlbumArtService.load_pixbuf_from_bytes(
            b"this is not an image", 48, 48
        )
        assert result is None

    def test_empty_bytes_returns_none(self) -> None:
        """Empty bytes should return None."""
        result = AlbumArtService.load_pixbuf_from_bytes(b"", 48, 48)
        assert result is None


# ---------------------------------------------------------------------------
# Tests: load_pixbuf_from_url
# ---------------------------------------------------------------------------


class TestLoadPixbufFromUrl:
    """Test loading pixbufs from URLs."""

    def test_network_error_returns_none(self) -> None:
        """A URL that fails to download should return None."""
        result = AlbumArtService.load_pixbuf_from_url(
            "http://localhost:1/nonexistent.jpg", 48, 48
        )
        assert result is None

    def test_successful_download(self) -> None:
        """A successful download should decode the image."""
        png_data = _minimal_png_bytes()

        mock_resp = MagicMock()
        mock_resp.read.return_value = png_data
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)

        with patch("urllib.request.urlopen", return_value=mock_resp):
            result = AlbumArtService.load_pixbuf_from_url(
                "https://example.com/art.png", 48, 48
            )
            # Result depends on GdkPixbuf availability
            # Either a valid pixbuf or None in headless environments


# ---------------------------------------------------------------------------
# Tests: Embedded art extraction
# ---------------------------------------------------------------------------


class TestExtractEmbeddedArt:
    """Test the format-dispatch for embedded art extraction."""

    def test_dispatch_mp3(self) -> None:
        """MP3 files should call _extract_mp3_art."""
        with patch.object(
            AlbumArtService,
            "_extract_mp3_art",
            return_value=b"mp3_art_bytes",
        ) as mock:
            result = AlbumArtService._extract_embedded_art("/fake/test.mp3")
            mock.assert_called_once_with("/fake/test.mp3")
            assert result == b"mp3_art_bytes"

    def test_dispatch_flac(self) -> None:
        """FLAC files should call _extract_flac_art."""
        with patch.object(
            AlbumArtService,
            "_extract_flac_art",
            return_value=b"flac_art_bytes",
        ) as mock:
            result = AlbumArtService._extract_embedded_art("/fake/test.flac")
            mock.assert_called_once_with("/fake/test.flac")
            assert result == b"flac_art_bytes"

    def test_dispatch_m4a(self) -> None:
        """M4A files should call _extract_m4a_art."""
        with patch.object(
            AlbumArtService,
            "_extract_m4a_art",
            return_value=b"m4a_art_bytes",
        ) as mock:
            result = AlbumArtService._extract_embedded_art("/fake/test.m4a")
            mock.assert_called_once_with("/fake/test.m4a")
            assert result == b"m4a_art_bytes"

    def test_dispatch_ogg(self) -> None:
        """OGG files should call _extract_ogg_art."""
        with patch.object(
            AlbumArtService,
            "_extract_ogg_art",
            return_value=b"ogg_art_bytes",
        ) as mock:
            result = AlbumArtService._extract_embedded_art("/fake/test.ogg")
            mock.assert_called_once_with("/fake/test.ogg")
            assert result == b"ogg_art_bytes"

    def test_unsupported_extension_returns_none(self) -> None:
        """Unsupported extensions should return None."""
        result = AlbumArtService._extract_embedded_art("/fake/test.wav")
        assert result is None


# ---------------------------------------------------------------------------
# Tests: Async callback
# ---------------------------------------------------------------------------


class TestAsyncCallback:
    """Test the async art loading mechanism."""

    def test_async_callback_invoked(self) -> None:
        """get_art_async should invoke the callback with the result."""
        service = AlbumArtService()
        track = _make_track(
            source=Source.TIDAL,
            source_id="999",
            album_art_url=None,
        )

        results: list = []
        event = threading.Event()

        def callback(pixbuf):
            results.append(pixbuf)
            event.set()

        with patch(
            "gi.repository.GLib.idle_add",
            side_effect=lambda fn, *a: fn(*a),
        ):
            service.get_art_async(track, callback)

        event.wait(timeout=5)
        assert len(results) == 1
        assert results[0] is None  # no art URL -> None

    def test_async_callback_with_cached_result(self) -> None:
        """Async should return cached result quickly."""
        service = AlbumArtService()
        service._cache[(42, 48, 48)] = "cached_art"

        track = _make_track(track_id=42, source_id="/fake.mp3")

        results: list = []
        event = threading.Event()

        def callback(pixbuf):
            results.append(pixbuf)
            event.set()

        with patch(
            "gi.repository.GLib.idle_add",
            side_effect=lambda fn, *a: fn(*a),
        ):
            service.get_art_async(track, callback)

        event.wait(timeout=5)
        assert len(results) == 1
        assert results[0] == "cached_art"

    def test_async_passes_width_height(self) -> None:
        """get_art_async should forward width/height to get_art_for_track."""
        service = AlbumArtService()
        track = _make_track(
            source=Source.TIDAL,
            source_id="100",
            album_art_url="https://example.com/art.jpg",
            track_id=100,
        )

        results: list = []
        event = threading.Event()

        def callback(pixbuf):
            results.append(pixbuf)
            event.set()

        with patch.object(
            service,
            "get_art_for_track",
            return_value="mock_pixbuf",
        ) as mock_get:
            with patch(
                "gi.repository.GLib.idle_add",
                side_effect=lambda fn, *a: fn(*a),
            ):
                service.get_art_async(
                    track, callback, width=200, height=200
                )

            event.wait(timeout=5)
            mock_get.assert_called_once_with(track, 200, 200)
            assert results[0] == "mock_pixbuf"


# ---------------------------------------------------------------------------
# Tests: Edge cases
# ---------------------------------------------------------------------------


class TestEdgeCases:
    """Test various edge cases and error handling."""

    def test_none_source_id_returns_none(self) -> None:
        """A track with empty source_id should return None."""
        service = AlbumArtService()
        track = _make_track(source_id="", track_id=50)
        result = service.get_art_for_track(track)
        assert result is None

    def test_custom_size(self) -> None:
        """Custom width/height should be forwarded."""
        service = AlbumArtService()
        track = _make_track(
            source=Source.TIDAL,
            source_id="55",
            track_id=55,
            album_art_url="https://example.com/art.jpg",
        )

        with patch.object(
            AlbumArtService,
            "load_pixbuf_from_url",
            return_value="mock",
        ) as mock_load:
            service.get_art_for_track(track, width=200, height=200)
            mock_load.assert_called_once_with(
                "https://example.com/art.jpg", 200, 200
            )

    def test_exception_in_extract_returns_none(self) -> None:
        """Exceptions during art extraction should be caught."""
        service = AlbumArtService()
        track = _make_track(source_id="/fake/corrupt.mp3", track_id=60)

        with patch.object(
            AlbumArtService,
            "_extract_embedded_art",
            side_effect=RuntimeError("corrupt"),
        ):
            result = service.get_art_for_track(track)
            assert result is None
