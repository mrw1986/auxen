"""Artist image loading service for the Auxen music player.

Downloads artist images from the Tidal API, caches them on disk
and in memory for fast subsequent lookups.
"""

from __future__ import annotations

import logging
import re
import threading
import time
import urllib.request
from collections import OrderedDict
from pathlib import Path
from typing import Any, Callable

import gi

gi.require_version("GdkPixbuf", "2.0")
gi.require_version("Gdk", "4.0")

from gi.repository import GdkPixbuf, GLib

logger = logging.getLogger(__name__)

# Maximum total bytes of cached pixbufs before LRU eviction (~50 MB).
_MAX_CACHE_BYTES = 50 * 1024 * 1024

# Disk cache staleness: 30 days.
_CACHE_STALENESS_SECS = 30 * 24 * 3600

# Slug pattern: keep alphanumeric + hyphens
_SLUG_RE = re.compile(r"[^a-z0-9]+")


def _slugify(name: str) -> str:
    """Convert an artist name to a safe filesystem slug."""
    return _SLUG_RE.sub("-", name.lower()).strip("-") or "unknown"


def _pixbuf_bytes(pixbuf: Any) -> int:
    """Return the approximate byte size of a GdkPixbuf, or 0 for None."""
    if pixbuf is None:
        return 0
    try:
        return pixbuf.get_byte_length()
    except Exception:
        return 0


class ArtistImageService:
    """Load artist images from Tidal with disk + memory caching."""

    def __init__(self, tidal_provider=None) -> None:
        self._tidal = tidal_provider
        cache_base = GLib.get_user_cache_dir()
        self._cache_dir = Path(cache_base) / "auxen" / "artist_images"
        self._cache_dir.mkdir(parents=True, exist_ok=True)
        self._mem_cache: OrderedDict[tuple[str, int], Any] = OrderedDict()
        self._mem_cache_bytes: int = 0
        self._lock = threading.Lock()

    def set_tidal_provider(self, provider) -> None:
        """Set or update the Tidal provider for image fetching."""
        self._tidal = provider

    def get_artist_image_async(
        self,
        artist_name: str,
        callback: Callable[[GdkPixbuf.Pixbuf | None], None],
        size: int = 48,
    ) -> None:
        """Load an artist image asynchronously.

        Checks memory cache, then disk cache, then fetches from Tidal.
        The callback is invoked on the main GTK thread via GLib.idle_add.
        """
        # Check memory cache first (fast path)
        cache_key = (artist_name.lower(), size)
        with self._lock:
            if cache_key in self._mem_cache:
                self._mem_cache.move_to_end(cache_key)
                pixbuf = self._mem_cache[cache_key]
                GLib.idle_add(callback, pixbuf)
                return

        def _worker():
            pixbuf = self._fetch_and_cache(artist_name, size)
            GLib.idle_add(callback, pixbuf)

        threading.Thread(target=_worker, daemon=True).start()

    def _fetch_and_cache(
        self, artist_name: str, size: int
    ) -> GdkPixbuf.Pixbuf | None:
        """Fetch an artist image, using disk cache when available."""
        slug = _slugify(artist_name)
        disk_path = self._cache_dir / f"{slug}.jpg"
        cache_key = (artist_name.lower(), size)

        # 1. Check disk cache (not stale)
        if disk_path.exists():
            age = time.time() - disk_path.stat().st_mtime
            if age < _CACHE_STALENESS_SECS:
                try:
                    pixbuf = GdkPixbuf.Pixbuf.new_from_file_at_scale(
                        str(disk_path), size, size, True
                    )
                    self._put_mem_cache(cache_key, pixbuf)
                    return pixbuf
                except Exception:
                    logger.debug(
                        "Failed to load cached artist image: %s",
                        disk_path,
                        exc_info=True,
                    )

        # 2. Fetch from Tidal
        if self._tidal is None or not self._tidal.is_logged_in:
            self._put_mem_cache(cache_key, None)
            return None

        try:
            info = self._tidal.get_artist_info(artist_name)
            if info is None:
                self._put_mem_cache(cache_key, None)
                return None

            image_url = info.get("image_url")
            if not image_url:
                self._put_mem_cache(cache_key, None)
                return None

            # 3. Download image
            req = urllib.request.Request(
                image_url, headers={"User-Agent": "Auxen/1.0"}
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = resp.read()

            # 4. Save to disk cache
            try:
                disk_path.write_bytes(data)
            except Exception:
                logger.debug(
                    "Failed to write artist image cache: %s",
                    disk_path,
                    exc_info=True,
                )

            # 5. Scale and return pixbuf
            loader = GdkPixbuf.PixbufLoader()
            loader.write(data)
            loader.close()
            raw_pixbuf = loader.get_pixbuf()
            if raw_pixbuf is None:
                self._put_mem_cache(cache_key, None)
                return None

            pixbuf = raw_pixbuf.scale_simple(
                size, size, GdkPixbuf.InterpType.BILINEAR
            )
            self._put_mem_cache(cache_key, pixbuf)
            return pixbuf

        except Exception:
            logger.debug(
                "Failed to fetch artist image for %s",
                artist_name,
                exc_info=True,
            )
            self._put_mem_cache(cache_key, None)
            return None

    def _put_mem_cache(
        self, key: tuple[str, int], pixbuf: Any
    ) -> None:
        """Store a pixbuf in the memory cache with LRU eviction."""
        with self._lock:
            if key in self._mem_cache:
                old = self._mem_cache.pop(key)
                self._mem_cache_bytes -= _pixbuf_bytes(old)

            self._mem_cache[key] = pixbuf
            self._mem_cache_bytes += _pixbuf_bytes(pixbuf)

            # Evict oldest entries until under budget
            while (
                self._mem_cache_bytes > _MAX_CACHE_BYTES
                and len(self._mem_cache) > 1
            ):
                _, evicted = self._mem_cache.popitem(last=False)
                self._mem_cache_bytes -= _pixbuf_bytes(evicted)
