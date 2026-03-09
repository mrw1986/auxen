"""Album art loading service for the Auxen music player.

Extracts embedded album art from local audio files using mutagen,
downloads art from URLs (Tidal), and caches results in memory with
an LRU-style eviction policy.
"""

from __future__ import annotations

import logging
import os
import threading
import urllib.parse
import urllib.request
from collections import OrderedDict
from pathlib import Path
from typing import Any, Callable, Optional

from auxen.models import Source, Track

logger = logging.getLogger(__name__)

# Maximum total bytes of cached pixbufs before LRU eviction (~50 MB).
_MAX_CACHE_BYTES = 50 * 1024 * 1024


def _pixbuf_bytes(pixbuf: Any) -> int:
    """Return the approximate byte size of a GdkPixbuf, or 0 for None."""
    if pixbuf is None:
        return 0
    try:
        return pixbuf.get_byte_length()
    except Exception:
        return 0


class AlbumArtService:
    """Load album art from embedded metadata or remote URLs.

    Lookup order for local tracks:
        1. In-memory cache (keyed by track.id + requested size)
        2. Embedded art in the audio file (APIC for MP3, pictures for
           FLAC/OGG, covr for M4A/AAC)

    For Tidal tracks:
        1. In-memory cache
        2. Download from ``track.album_art_url``

    Results (including ``None`` for missing art) are cached so
    subsequent lookups are free.  The cache is capped by total byte
    usage (default ~50 MB) rather than entry count, so a mix of small
    thumbnails and large detail images is handled efficiently.
    """

    def __init__(self) -> None:
        self._cache: OrderedDict[tuple[int, int, int], Any] = OrderedDict()
        self._cache_bytes: int = 0
        self._lock = threading.Lock()
        # Texture cache: reuse Gdk.Texture objects across widget rebuilds
        self._texture_cache: OrderedDict[tuple[int, int, int], Any] = OrderedDict()
        self._max_texture_entries = 500

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_art_for_track(self, track: Track, width: int = 48, height: int = 48) -> Any:
        """Return a ``GdkPixbuf.Pixbuf`` for *track*, or ``None``.

        The result is cached by ``track.id``.  Pass *width*/*height* to
        control the output size (default 48x48 for the now-playing bar).
        """
        if track.id is None:
            # No stable ID -- skip caching, just load
            return self._load_art(track, width, height)

        cache_key = (track.id, width, height)

        with self._lock:
            if cache_key in self._cache:
                self._cache.move_to_end(cache_key)
                return self._cache[cache_key]

        pixbuf = self._load_art(track, width, height)

        with self._lock:
            self._cache[cache_key] = pixbuf
            self._cache.move_to_end(cache_key)
            self._cache_bytes += _pixbuf_bytes(pixbuf)
            # Evict oldest entries if over byte budget
            while self._cache_bytes > _MAX_CACHE_BYTES and self._cache:
                _, evicted = self._cache.popitem(last=False)
                self._cache_bytes -= _pixbuf_bytes(evicted)

        return pixbuf

    def get_texture_for_track(
        self, track: Track, width: int = 48, height: int = 48
    ) -> Any:
        """Return a cached ``Gdk.Texture`` for *track*, or ``None``.

        This is a synchronous, main-thread-safe check that returns
        immediately if a texture is already cached.  Returns ``None``
        if the texture hasn't been created yet (caller should fall
        back to ``get_art_async``).
        """
        if track.id is None:
            return None
        cache_key = (track.id, width, height)
        with self._lock:
            texture = self._texture_cache.get(cache_key)
            if texture is not None:
                self._texture_cache.move_to_end(cache_key)
                return texture
        return None

    def cache_texture(
        self, track: Track, texture: Any, width: int = 48, height: int = 48
    ) -> None:
        """Store a ``Gdk.Texture`` in the texture cache."""
        if track.id is None or texture is None:
            return
        cache_key = (track.id, width, height)
        with self._lock:
            self._texture_cache[cache_key] = texture
            self._texture_cache.move_to_end(cache_key)
            while len(self._texture_cache) > self._max_texture_entries:
                self._texture_cache.popitem(last=False)

    def get_art_async(
        self,
        track: Track,
        callback: Callable[[Any], None],
        width: int = 48,
        height: int = 48,
    ) -> None:
        """Fetch album art in a background thread.

        *callback* is scheduled on the GLib main loop so it is safe to
        update GTK widgets from it.
        """

        def _worker() -> None:
            result = self.get_art_for_track(track, width, height)
            try:
                from gi.repository import GLib

                GLib.idle_add(callback, result)
            except Exception:
                # If GLib is unavailable (e.g. in tests), call directly.
                callback(result)

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()

    def get_art_by_url_async(
        self,
        url: str,
        callback: Callable[[Any], None],
        width: int = 48,
        height: int = 48,
    ) -> None:
        """Fetch album art from a URL in a background thread.

        Unlike ``get_art_async`` this does not require a ``Track`` object --
        it takes a raw image URL (e.g. the ``cover_url`` returned by
        Tidal explore endpoints).

        *callback* is scheduled on the GLib main loop so it is safe to
        update GTK widgets from it.
        """

        def _worker() -> None:
            result = self.load_pixbuf_from_url(url, width, height)
            try:
                from gi.repository import GLib

                GLib.idle_add(callback, result)
            except Exception:
                callback(result)

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()

    def clear_cache(self) -> None:
        """Remove all cached pixbufs and textures."""
        with self._lock:
            self._cache.clear()
            self._cache_bytes = 0
            self._texture_cache.clear()

    def get_or_create_texture(
        self, track: Track, pixbuf: Any, width: int = 48, height: int = 48
    ) -> Any:
        """Return a ``Gdk.Texture`` for *track*, creating and caching if needed.

        Call this from the main thread with a pixbuf from ``get_art_for_track``
        or from an async callback.  The texture is cached so subsequent calls
        (e.g. after widget rebuild on sort change) return the same object instantly.
        """
        if pixbuf is None:
            return None
        if track is not None and track.id is not None:
            cached = self.get_texture_for_track(track, width, height)
            if cached is not None:
                return cached
        try:
            from gi.repository import Gdk
            texture = Gdk.Texture.new_for_pixbuf(pixbuf)
        except Exception:
            return None
        if track is not None:
            self.cache_texture(track, texture, width, height)
        return texture

    # ------------------------------------------------------------------
    # Pixbuf helpers (public for direct use)
    # ------------------------------------------------------------------

    @staticmethod
    def load_pixbuf_from_bytes(
        data: bytes, width: int = 48, height: int = 48
    ) -> Any:
        """Create a scaled ``GdkPixbuf.Pixbuf`` from raw image bytes.

        Returns ``None`` if the bytes cannot be decoded.
        """
        try:
            import gi

            gi.require_version("GdkPixbuf", "2.0")
            from gi.repository import GdkPixbuf

            loader = GdkPixbuf.PixbufLoader()
            loader.write(data)
            loader.close()

            pixbuf = loader.get_pixbuf()
            if pixbuf is None:
                return None

            return pixbuf.scale_simple(
                width, height, GdkPixbuf.InterpType.BILINEAR
            )
        except Exception:
            logger.debug("Failed to load pixbuf from bytes", exc_info=True)
            return None

    @staticmethod
    def load_pixbuf_from_url(
        url: str, width: int = 48, height: int = 48
    ) -> Any:
        """Download an image from *url* and return a scaled pixbuf.

        Returns ``None`` on any network or decoding error.
        """
        _MAX_IMAGE_BYTES = 10 * 1024 * 1024  # 10 MB
        try:
            parsed = urllib.parse.urlparse(url)
            if parsed.scheme not in ("http", "https"):
                logger.debug("Rejected non-HTTP URL scheme: %s", parsed.scheme)
                return None

            req = urllib.request.Request(
                url,
                headers={"User-Agent": "Auxen/0.1"},
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = resp.read(_MAX_IMAGE_BYTES + 1)
                if len(data) > _MAX_IMAGE_BYTES:
                    logger.debug("Image too large from %s", url)
                    return None

            return AlbumArtService.load_pixbuf_from_bytes(data, width, height)
        except Exception:
            logger.debug("Failed to load pixbuf from URL %s", url, exc_info=True)
            return None

    # ------------------------------------------------------------------
    # Internal -- art extraction
    # ------------------------------------------------------------------

    def _load_art(self, track: Track, width: int, height: int) -> Any:
        """Determine track source and load art accordingly."""
        if track.source == Source.LOCAL:
            return self._load_local_art(track.source_id, width, height)

        # Tidal (or other remote source)
        if track.album_art_url:
            return self.load_pixbuf_from_url(
                track.album_art_url, width, height
            )
        return None

    def _load_local_art(
        self, file_path: str, width: int, height: int
    ) -> Any:
        """Extract embedded art from a local audio file."""
        if not file_path or not os.path.isfile(file_path):
            return None

        art_bytes = self._extract_embedded_art(file_path)
        if art_bytes:
            return self.load_pixbuf_from_bytes(art_bytes, width, height)
        return None

    # ------------------------------------------------------------------
    # Embedded art extraction (mutagen)
    # ------------------------------------------------------------------

    @staticmethod
    def _extract_embedded_art(file_path: str) -> Optional[bytes]:
        """Return raw image bytes from the file's embedded art tag."""
        try:
            ext = Path(file_path).suffix.lower()

            if ext == ".mp3":
                return AlbumArtService._extract_mp3_art(file_path)
            if ext == ".flac":
                return AlbumArtService._extract_flac_art(file_path)
            if ext in (".ogg", ".opus"):
                return AlbumArtService._extract_ogg_art(file_path)
            if ext in (".m4a", ".aac", ".mp4"):
                return AlbumArtService._extract_m4a_art(file_path)
        except Exception:
            logger.debug(
                "Failed to extract art from %s", file_path, exc_info=True
            )
        return None

    @staticmethod
    def _extract_mp3_art(file_path: str) -> Optional[bytes]:
        """Extract APIC (album art) from an MP3 ID3 tag."""
        try:
            from mutagen.id3 import ID3

            tags = ID3(file_path)
            for key, frame in tags.items():
                if key.startswith("APIC"):
                    if frame.data:
                        return bytes(frame.data)
        except Exception:
            logger.debug("Failed to extract MP3 art from %s", file_path)
        return None

    @staticmethod
    def _extract_flac_art(file_path: str) -> Optional[bytes]:
        """Extract the first picture from a FLAC file."""
        try:
            from mutagen.flac import FLAC

            audio = FLAC(file_path)
            if audio.pictures:
                return bytes(audio.pictures[0].data)
        except Exception:
            logger.debug("Failed to extract FLAC art from %s", file_path)
        return None

    @staticmethod
    def _extract_ogg_art(file_path: str) -> Optional[bytes]:
        """Extract art from OGG/Opus via mutagen pictures list."""
        try:
            import mutagen

            audio = mutagen.File(file_path)
            if audio is None:
                return None

            # OGG Vorbis and Opus store pictures in a pictures list
            pictures = getattr(audio, "pictures", None)
            if pictures:
                return bytes(pictures[0].data)

            # Some files encode art in METADATA_BLOCK_PICTURE tag
            if audio.tags:
                import base64

                from mutagen.flac import Picture

                for b64_data in audio.tags.get(
                    "METADATA_BLOCK_PICTURE", []
                ):
                    try:
                        pic = Picture(base64.b64decode(b64_data))
                        if pic.data:
                            return bytes(pic.data)
                    except Exception:
                        pass
        except Exception:
            logger.debug("Failed to extract OGG art from %s", file_path)
        return None

    @staticmethod
    def _extract_m4a_art(file_path: str) -> Optional[bytes]:
        """Extract cover art from M4A/AAC/MP4 'covr' atom."""
        try:
            from mutagen.mp4 import MP4

            audio = MP4(file_path)
            if audio.tags is None:
                return None

            covers = audio.tags.get("covr")
            if covers:
                return bytes(covers[0])
        except Exception:
            logger.debug("Failed to extract M4A art from %s", file_path)
        return None
