"""Tidal streaming provider — wraps tidalapi for auth, search, and streaming."""

from __future__ import annotations

import json
import logging
import os
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional

import tidalapi

from ..models import Source, Track
from .base import ContentProvider

logger = logging.getLogger(__name__)

SESSION_FILE = Path.home() / ".local" / "share" / "auxen" / "tidal_session.json"


class TidalProvider(ContentProvider):
    """Tidal music source backed by tidalapi."""

    def __init__(self) -> None:
        self._session = tidalapi.Session()
        self._session_lock = threading.Lock()
        self._auth_generation: int = 0  # bumped on logout to invalidate races
        self._logged_in: bool = False  # cached login state (avoids slow network check)
        SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def is_logged_in(self) -> bool:
        """Return True when the session is authenticated (cached)."""
        return self._logged_in

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    def restore_session(self) -> bool:
        """Load a previously saved OAuth session from disk.

        Returns True on success, False otherwise.  Corrupt session files
        are deleted automatically.  Network errors are treated as
        transient — the session file is kept for the next attempt.
        """
        import requests

        if not SESSION_FILE.exists():
            self._logged_in = False
            return False

        try:
            data = json.loads(SESSION_FILE.read_text())
            expiry_time = data["expiry_time"]
            if isinstance(expiry_time, str):
                expiry_time = datetime.fromisoformat(expiry_time)
            with self._session_lock:
                self._session.load_oauth_session(
                    token_type=data["token_type"],
                    access_token=data["access_token"],
                    refresh_token=data["refresh_token"],
                    expiry_time=expiry_time,
                )
            self._logged_in = True
            return True
        except (requests.ConnectionError, requests.Timeout, OSError) as e:
            # Network failure — keep the session file for next attempt
            logger.warning("Tidal session restore failed (network): %s", e)
            self._logged_in = False
            return False
        except Exception:
            logger.warning(
                "Corrupt Tidal session file — removing it.", exc_info=True
            )
            self._logged_in = False
            try:
                SESSION_FILE.unlink()
            except OSError:
                pass
            return False

    def login(self, url_callback: Optional[Callable[[str], Any]] = None) -> bool:
        """Start the OAuth device-code flow and block until the user authorises.

        *url_callback* receives the full verification URL if provided;
        otherwise the URL is printed to stdout.  Returns True on success.

        A local reference to the session is held throughout the flow so
        that a concurrent ``logout()`` (which replaces ``self._session``)
        cannot corrupt the in-progress login.
        """
        try:
            with self._session_lock:
                session = self._session
                auth_gen = self._auth_generation
                login, future = session.login_oauth()
            url = login.verification_uri_complete

            if url_callback is not None:
                url_callback(f"https://{url}")
            else:
                print(f"Visit https://{url} to log in to Tidal")

            future.result()

            # Atomically verify the session identity and snapshot
            # tokens under one lock acquisition, so a concurrent
            # logout() cannot slip in between the check and the save.
            with self._session_lock:
                if (
                    self._session is not session
                    or self._auth_generation != auth_gen
                ):
                    logger.info(
                        "Tidal login completed but session was replaced "
                        "by a concurrent logout — discarding."
                    )
                    return False
                token_data = {
                    "token_type": session.token_type,
                    "access_token": session.access_token,
                    "refresh_token": session.refresh_token,
                    "expiry_time": session.expiry_time,
                }
                # Write within the lock to eliminate the race window
                # between identity check and file I/O.
                self._write_session_file(token_data)
            self._logged_in = True
            return True
        except Exception:
            logger.error("Tidal login failed.", exc_info=True)
            return False

    def logout(self) -> None:
        """Delete the persisted session file and clear in-memory auth."""
        with self._session_lock:
            self._auth_generation += 1
            self._session = tidalapi.Session()
        self._logged_in = False
        try:
            SESSION_FILE.unlink(missing_ok=True)
        except OSError:
            pass

    # ------------------------------------------------------------------
    # Subscription info
    # ------------------------------------------------------------------

    def get_subscription_info(self) -> dict:
        """Get the user's Tidal subscription information.

        Returns a dict with keys ``status``, ``type``, and ``quality``,
        or an empty dict on failure / when not logged in.
        """
        try:
            if not self.is_logged_in:
                return {}
            with self._session_lock:
                user_id = self._session.user.id
                response = self._session.request.request(
                    "GET", f"users/{user_id}/subscription"
                )
            if response.status_code == 200:
                data = response.json()
                return {
                    "status": data.get("status", "Unknown"),
                    "type": data.get("subscription", {}).get(
                        "type", "Unknown"
                    ),
                    "quality": data.get(
                        "highestSoundQuality", "Unknown"
                    ),
                }
            return {}
        except Exception:
            logger.debug("get_subscription_info failed", exc_info=True)
            return {}

    # ------------------------------------------------------------------
    # Content provider interface
    # ------------------------------------------------------------------

    def search(self, query: str, limit: int = 20) -> list[Track]:
        """Search Tidal for tracks matching *query*."""
        with self._session_lock:
            results = self._session.search(query, models=[tidalapi.Track], limit=limit)
        tidal_tracks = results.get("tracks", [])
        return [self._tidal_track_to_model(t) for t in tidal_tracks]

    def search_all(
        self, query: str, limit: int = 10
    ) -> dict[str, list]:
        """Search Tidal for tracks, albums, and artists.

        Returns a dict with keys ``"tracks"``, ``"albums"``, ``"artists"``
        where each value is a list of raw tidalapi objects.
        """
        with self._session_lock:
            results = self._session.search(
                query,
                models=[tidalapi.Track, tidalapi.Album, tidalapi.Artist],
                limit=limit,
            )
        return {
            "tracks": [
                self._tidal_track_to_model(t)
                for t in results.get("tracks", [])
            ],
            "albums": results.get("albums", []),
            "artists": results.get("artists", []),
        }

    def get_stream_uri(self, track: Track) -> str:
        """Resolve a playable stream URL for a Tidal track."""
        import time as _time

        t0 = _time.monotonic()
        with self._session_lock:
            logger.info(
                "[stream-uri] acquired lock in %.0fms for '%s'",
                (_time.monotonic() - t0) * 1000, track.title,
            )
            t1 = _time.monotonic()
            tidal_track = self._session.track(int(track.source_id))
            logger.info(
                "[stream-uri] session.track() took %.0fms",
                (_time.monotonic() - t1) * 1000,
            )
            t2 = _time.monotonic()
            url = tidal_track.get_url()
            logger.info(
                "[stream-uri] get_url() took %.0fms — total %.0fms",
                (_time.monotonic() - t2) * 1000,
                (_time.monotonic() - t0) * 1000,
            )
            return url

    # ------------------------------------------------------------------
    # Favorites
    # ------------------------------------------------------------------

    def get_favorites(self) -> list[Track]:
        """Return the user's favourite Tidal tracks."""
        with self._session_lock:
            tidal_tracks = self._session.user.favorites.tracks()
        return [self._tidal_track_to_model(t) for t in tidal_tracks]

    def add_favorite(self, track_id: str) -> None:
        """Add a track to the user's Tidal favourites."""
        with self._session_lock:
            self._session.user.favorites.add_track(int(track_id))

    def remove_favorite(self, track_id: str) -> None:
        """Remove a track from the user's Tidal favourites."""
        with self._session_lock:
            self._session.user.favorites.remove_track(int(track_id))

    def is_favorite(self, track_id: str) -> bool:
        """Check whether a track is in the user's Tidal favourites."""
        try:
            with self._session_lock:
                fav_tracks = self._session.user.favorites.tracks()
            return any(str(t.id) == track_id for t in fav_tracks)
        except Exception:
            logger.warning("Failed to check Tidal favorite status", exc_info=True)
            return False

    # ------------------------------------------------------------------
    # Discovery / Explore
    # ------------------------------------------------------------------

    def get_featured_albums(self, limit: int = 12) -> list[dict]:
        """Get Tidal's featured/new albums.

        Returns a list of dicts with keys: title, artist, cover_url, tidal_id.
        Returns an empty list if not logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                explore_page = self._session.explore()
            return self._extract_albums_from_page(explore_page, limit)
        except Exception:
            logger.debug("get_featured_albums failed", exc_info=True)
            raise

    def _extract_albums_from_categories(
        self, categories, limit: int, title_filter: set[str] | None = None,
    ) -> list[dict]:
        """Extract album dicts from page categories.

        Parameters
        ----------
        categories:
            Iterable of page categories (from explore/home).
        limit:
            Maximum albums to return.
        title_filter:
            If given, only include categories whose lowercased title
            contains at least one of these keywords.
        """
        if categories is None:
            return []
        albums: list[dict] = []
        for category in categories:
            cat_title = (getattr(category, "title", "") or "").lower()
            if title_filter and not any(k in cat_title for k in title_filter):
                continue
            items = getattr(category, "items", None)
            if items is None:
                continue
            for item in items:
                if isinstance(item, tidalapi.Album) and len(albums) < limit:
                    albums.append(self._album_to_dict(item))
            if len(albums) >= limit:
                break
        return albums[:limit]

    def _extract_albums_from_page(
        self, page, limit: int, title_filter: set[str] | None = None,
    ) -> list[dict]:
        """Extract albums from a Page, trying .categories first, then flat iteration."""
        categories = getattr(page, "categories", None)
        if categories:
            albums = self._extract_albums_from_categories(
                categories, limit, title_filter,
            )
            if albums:
                return albums
        # Flat iteration fallback (iterates through all items in all categories)
        albums: list[dict] = []
        try:
            for item in page:
                if isinstance(item, tidalapi.Album) and len(albums) < limit:
                    if title_filter:
                        # Can't filter by category title in flat mode
                        pass
                    albums.append(self._album_to_dict(item))
                if len(albums) >= limit:
                    break
        except (StopIteration, TypeError):
            pass
        return albums[:limit]

    @staticmethod
    def _album_to_dict(item) -> dict:
        """Convert a tidalapi Album to a serialisable dict."""
        cover = getattr(item, "cover", None) or ""
        cover_url = None
        if cover:
            cover_url = (
                f"https://resources.tidal.com/images/"
                f"{cover.replace('-', '/')}/640x640.jpg"
            )
        artist_name = ""
        if hasattr(item, "artist") and item.artist:
            artist_name = getattr(item.artist, "name", "")
        num_tracks = (
            getattr(item, "num_tracks", None)
            or getattr(item, "number_of_tracks", 0)
            or 0
        )
        # Capture when the user added this album to favorites
        date_added_str = ""
        user_date = getattr(item, "user_date_added", None)
        if user_date is not None:
            try:
                date_added_str = user_date.isoformat()
            except Exception:
                pass

        return {
            "title": item.name,
            "artist": artist_name,
            "cover_url": cover_url,
            "tidal_id": str(item.id),
            "num_tracks": num_tracks,
            "date_added": date_added_str,
        }

    def get_new_releases(self, limit: int = 12) -> list[dict]:
        """Get new releases from Tidal.

        Tries multiple strategies:
        1. Dedicated ``pages/new_arrivals`` page
        2. Home page categories matching new/release/arrival keywords
        3. Explore page categories matching the same keywords
        4. All albums from the explore page (featured)

        Returns a list of dicts with keys: title, artist, cover_url, tidal_id.
        Returns an empty list if not logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []

            keywords = {"new", "release", "arrival", "just added", "latest"}

            # Strategy 1: dedicated new arrivals page
            try:
                with self._session_lock:
                    page = self._session.page.get("pages/new_arrivals")
                albums = self._extract_albums_from_categories(
                    page.categories, limit,
                )
                if albums:
                    return albums
            except Exception:
                logger.debug("pages/new_arrivals unavailable", exc_info=True)

            # Strategy 2: home page categories
            try:
                with self._session_lock:
                    home = self._session.home()
                albums = self._extract_albums_from_categories(
                    home.categories, limit, title_filter=keywords,
                )
                if albums:
                    return albums
            except Exception:
                logger.debug("home page new releases unavailable", exc_info=True)

            # Strategy 3: explore page with keyword filter
            with self._session_lock:
                explore_page = self._session.explore()
            albums = self._extract_albums_from_page(
                explore_page, limit, title_filter=keywords,
            )
            if albums:
                return albums

            # Strategy 4: all albums from explore (featured fallback)
            return self._extract_albums_from_page(explore_page, limit)
        except Exception:
            logger.debug("get_new_releases failed", exc_info=True)
            raise

    def get_top_tracks(self, limit: int = 20) -> list[Track]:
        """Get Tidal's top/popular tracks.

        Returns a list of Track models.  Falls back to searching
        'top hits' if the explore page yields nothing.
        Returns an empty list if not logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            # Try explore page for track-based categories
            with self._session_lock:
                explore_page = self._session.explore()
            tracks: list[Track] = []
            for category in explore_page:
                items = getattr(category, "items", None)
                if items is None:
                    continue
                for item in items:
                    if isinstance(item, tidalapi.Track) and len(tracks) < limit:
                        tracks.append(self._tidal_track_to_model(item))
            if tracks:
                return tracks[:limit]

            # Fallback: search for popular tracks
            with self._session_lock:
                results = self._session.search(
                    "top hits", models=[tidalapi.Track], limit=limit
                )
            for t in results.get("tracks", []):
                tracks.append(self._tidal_track_to_model(t))
            return tracks[:limit]
        except Exception:
            logger.debug("get_top_tracks failed", exc_info=True)
            raise

    def get_genres(self) -> list[str]:
        """Get available genre names from Tidal.

        Returns a list of genre title strings, or an empty list if not
        logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                genres_page = self._session.genres()
            genre_names: list[str] = []
            for category in genres_page:
                cat_title = getattr(category, "title", None)
                if cat_title:
                    genre_names.append(cat_title)
                # Also check for PageLink items with titles
                items = getattr(category, "items", None)
                if items is None:
                    continue
                for item in items:
                    name = getattr(item, "header", None) or getattr(
                        item, "short_header", None
                    )
                    if name and name not in genre_names:
                        genre_names.append(name)
            return genre_names if genre_names else []
        except Exception:
            logger.debug("get_genres failed", exc_info=True)
            raise

    def search_albums(self, query: str, limit: int = 12) -> list[dict]:
        """Search Tidal for albums matching a query.

        Returns a list of dicts with keys: title, artist, cover_url, tidal_id.
        Returns an empty list if not logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                results = self._session.search(
                    query,
                    models=[tidalapi.Album],
                    limit=limit,
                )
            albums: list[dict] = []
            for item in results.get("albums", []):
                cover = getattr(item, "cover", None) or ""
                cover_url = None
                if cover:
                    cover_url = (
                        f"https://resources.tidal.com/images/"
                        f"{cover.replace('-', '/')}/640x640.jpg"
                    )
                artist_name = ""
                if hasattr(item, "artist") and item.artist:
                    artist_name = getattr(item.artist, "name", "")
                albums.append({
                    "title": item.name,
                    "artist": artist_name,
                    "cover_url": cover_url,
                    "tidal_id": str(item.id),
                })
            return albums
        except Exception:
            logger.debug("search_albums failed for '%s'", query, exc_info=True)
            raise

    # ------------------------------------------------------------------
    # Mixes & User Playlists
    # ------------------------------------------------------------------

    def get_mixes(self, limit: int = 12) -> list[dict]:
        """Get user's personalized mixes from Tidal.

        Returns a list of dicts with keys: name, description, cover_url,
        tidal_id, track_count.  Returns an empty list if not logged in.
        Raises on API failure.

        Tries ``session.mixes()`` first for personalized algorithmic
        mixes, then falls back to scanning the explore page for Mix-type
        items, and finally to the user's playlists.
        """
        try:
            if not self.is_logged_in:
                return []

            mixes: list[dict] = []

            # Attempt 1: Use session.mixes() for personalized mixes
            try:
                with self._session_lock:
                    mixes_page = self._session.mixes()
                for item in mixes_page:
                    if len(mixes) >= limit:
                        break
                    # Skip video mixes — we can't play video content
                    mix_type = getattr(item, "mix_type", None)
                    if mix_type is not None:
                        type_name = getattr(mix_type, "name", "") or ""
                        if "video" in type_name.lower():
                            continue
                    name = (
                        getattr(item, "title", None)
                        or getattr(item, "name", None)
                        or "Mix"
                    )
                    desc = (
                        getattr(item, "sub_title", None)
                        or getattr(item, "description", None)
                        or ""
                    )
                    cover_url = None
                    try:
                        cover_url = item.image(dimensions=640)
                    except Exception:
                        logger.debug(
                            "Mix %s image() failed",
                            getattr(item, "id", "?"),
                            exc_info=True,
                        )
                    # Fallback: access images attribute directly
                    if not cover_url:
                        images = getattr(item, "images", None)
                        if images is not None:
                            cover_url = (
                                getattr(images, "medium", None)
                                or getattr(images, "large", None)
                                or getattr(images, "small", None)
                            )
                    # Fallback: try sharing_images
                    if not cover_url:
                        sharing = getattr(
                            item, "sharing_images", None
                        )
                        if isinstance(sharing, dict):
                            cover_url = (
                                sharing.get("640x640")
                                or sharing.get("320x320")
                                or sharing.get("1500x1500")
                            )
                    # Fallback: construct from picture attribute
                    if not cover_url:
                        picture = getattr(item, "picture", None)
                        if picture:
                            cover_url = (
                                "https://resources.tidal.com/images/"
                                f"{picture.replace('-', '/')}"
                                "/640x640.jpg"
                            )
                    item_id = getattr(item, "id", "") or ""
                    track_count = getattr(
                        item, "num_tracks", None
                    ) or getattr(item, "number_of_tracks", 0)
                    mixes.append({
                        "name": name,
                        "description": desc,
                        "cover_url": cover_url,
                        "tidal_id": str(item_id),
                        "track_count": track_count,
                    })
            except Exception:
                logger.debug("session.mixes() failed", exc_info=True)

            if mixes:
                return mixes[:limit]

            # Attempt 2: Look for Mix items on the explore/home page
            try:
                with self._session_lock:
                    explore_page = self._session.explore()
                for category in explore_page:
                    cat_title = getattr(category, "title", "") or ""
                    items = getattr(category, "items", None)
                    if items is None:
                        continue
                    for item in items:
                        if len(mixes) >= limit:
                            break
                        # tidalapi exposes Mix objects on some page categories
                        type_name = type(item).__name__
                        if type_name == "Mix" or "mix" in cat_title.lower():
                            # Skip video mixes
                            mt = getattr(item, "mix_type", None)
                            if mt is not None:
                                mtn = getattr(mt, "name", "") or ""
                                if "video" in mtn.lower():
                                    continue
                            name = (
                                getattr(item, "title", None)
                                or getattr(item, "name", None)
                                or "Mix"
                            )
                            desc = (
                                getattr(item, "sub_title", None)
                                or getattr(item, "description", None)
                                or ""
                            )
                            cover_url = None
                            try:
                                cover_url = item.image(
                                    dimensions=640
                                )
                            except Exception:
                                pass
                            if not cover_url:
                                imgs = getattr(item, "images", None)
                                if imgs is not None:
                                    cover_url = (
                                        getattr(imgs, "medium", None)
                                        or getattr(imgs, "large", None)
                                        or getattr(imgs, "small", None)
                                    )
                            if not cover_url:
                                cover = (
                                    getattr(item, "cover", None) or ""
                                )
                                if cover:
                                    cover_url = (
                                        "https://resources.tidal.com"
                                        "/images/"
                                        f"{cover.replace('-', '/')}"
                                        "/640x640.jpg"
                                    )
                            if not cover_url:
                                pic = getattr(
                                    item, "picture", None
                                )
                                if pic:
                                    cover_url = (
                                        "https://resources.tidal.com"
                                        "/images/"
                                        f"{pic.replace('-', '/')}"
                                        "/640x640.jpg"
                                    )
                            item_id = getattr(item, "id", "") or ""
                            track_count = getattr(
                                item, "num_tracks", None
                            ) or getattr(item, "number_of_tracks", 0)
                            mixes.append({
                                "name": name,
                                "description": desc,
                                "cover_url": cover_url,
                                "tidal_id": str(item_id),
                                "track_count": track_count,
                            })
            except Exception:
                logger.debug("explore page mix scan failed", exc_info=True)

            if mixes:
                return mixes[:limit]

            # Attempt 3: Fall back to user's playlists
            try:
                playlists = self.get_user_playlists(limit=limit)
                for pl in playlists:
                    mixes.append({
                        "name": pl["name"],
                        "description": pl.get("description", ""),
                        "cover_url": pl.get("cover_url"),
                        "tidal_id": pl["tidal_id"],
                        "track_count": pl.get("track_count", 0),
                    })
            except Exception:
                logger.debug("user playlists fallback failed", exc_info=True)

            return mixes[:limit]
        except Exception:
            logger.debug("get_mixes failed", exc_info=True)
            raise

    def get_user_playlists(self, limit: int = 20) -> list[dict]:
        """Get the authenticated user's Tidal playlists.

        Returns a list of dicts with keys: name, description, cover_url,
        tidal_id, track_count.  Returns an empty list if not logged in.
        Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []

            with self._session_lock:
                tidal_playlists = self._session.user.playlists()
            playlists: list[dict] = []
            for pl in tidal_playlists:
                if len(playlists) >= limit:
                    break
                cover_url = None
                # pl.image is a method in tidalapi — call it to get a URL
                try:
                    img_fn = getattr(pl, "image", None)
                    if callable(img_fn):
                        cover_url = img_fn(640)
                    elif isinstance(img_fn, str) and img_fn:
                        cover_url = (
                            f"https://resources.tidal.com/images/"
                            f"{img_fn.replace('-', '/')}/640x640.jpg"
                        )
                except Exception:
                    pass
                desc = getattr(pl, "description", None) or ""
                track_count = getattr(
                    pl, "num_tracks", None
                ) or getattr(pl, "number_of_tracks", 0)
                playlists.append({
                    "name": pl.name,
                    "description": desc,
                    "cover_url": cover_url,
                    "tidal_id": str(pl.id),
                    "track_count": track_count,
                })
            return playlists[:limit]
        except Exception:
            logger.debug("get_user_playlists failed", exc_info=True)
            raise

    def get_album_tracks_by_id(self, tidal_id: str) -> list["Track"]:
        """Fetch tracks for a Tidal album by its ID.

        Returns a list of Track models.  Returns an empty list if not
        logged in or the album is not found.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                album = self._session.album(int(tidal_id))
                tidal_tracks = album.tracks()
            return [self._tidal_track_to_model(t) for t in tidal_tracks]
        except Exception:
            logger.debug("get_album_tracks_by_id failed for %s", tidal_id, exc_info=True)
            return []

    def get_album_tracks(self, album_name: str, artist: str) -> list["Track"]:
        """Search Tidal for an album and return all its tracks.

        Returns a list of Track models.  Returns an empty list if not
        logged in or the album is not found.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                results = self._session.search(
                    f"{artist} {album_name}", models=[tidalapi.Album], limit=5
                )
            albums = results.get("albums", [])
            # Find the best match by name
            for album in albums:
                a_name = getattr(album, "name", "") or ""
                a_artist = getattr(album, "artist", None)
                a_artist_name = getattr(a_artist, "name", "") if a_artist else ""
                if (
                    a_name.lower() == album_name.lower()
                    or album_name.lower() in a_name.lower()
                ):
                    with self._session_lock:
                        tidal_tracks = album.tracks()
                    return [
                        self._tidal_track_to_model(t) for t in tidal_tracks
                    ]
            return []
        except Exception:
            logger.debug("get_album_tracks failed", exc_info=True)
            return []

    def get_artist_info(self, artist_name: str) -> dict | None:
        """Search Tidal for an artist and return their info + albums + top tracks.

        Returns a dict with keys: name, image_url, albums (list[dict]),
        tracks (list[Track]).  Returns None if not found.
        """
        try:
            if not self.is_logged_in:
                return None
            with self._session_lock:
                results = self._session.search(
                    artist_name, models=[tidalapi.Artist], limit=5
                )
            artists = results.get("artists", [])
            if not artists:
                return None

            # Find best match
            best = None
            for a in artists:
                a_name = getattr(a, "name", "") or ""
                if a_name.lower() == artist_name.lower():
                    best = a
                    break
            if best is None:
                # Fallback to first result if close enough
                best = artists[0]

            # Get artist image
            image_url = None
            try:
                img_fn = getattr(best, "image", None)
                if callable(img_fn):
                    image_url = img_fn(480)
                elif isinstance(img_fn, str) and img_fn:
                    image_url = (
                        f"https://resources.tidal.com/images/"
                        f"{img_fn.replace('-', '/')}/480x480.jpg"
                    )
            except Exception:
                pass

            # Get albums
            albums_out: list[dict] = []
            try:
                with self._session_lock:
                    tidal_albums = best.get_albums(limit=50)
                for alb in tidal_albums:
                    albums_out.append(self._album_to_dict(alb))
            except Exception:
                logger.debug("get_artist_info: albums failed", exc_info=True)

            # Get top tracks
            tracks_out: list["Track"] = []
            try:
                with self._session_lock:
                    top = best.get_top_tracks(limit=50)
                tracks_out = [self._tidal_track_to_model(t) for t in top]
            except Exception:
                logger.debug("get_artist_info: top tracks failed", exc_info=True)

            # Get biography — 404s are expected for lesser-known artists
            bio_text: str | None = None
            try:
                bio_text = best.get_bio()
            except Exception:
                logger.debug("get_artist_info: bio unavailable for '%s'", artist_name)

            # Get similar artists — 404s are expected for lesser-known artists
            # Only fetch lightweight data (name, image, id) — do NOT fetch
            # bio/similar/albums/tracks for each similar artist to avoid
            # cascading API calls.
            similar_artists: list[dict] = []
            try:
                with self._session_lock:
                    similars = best.get_similar()
                for sa in similars[:8]:
                    sa_image = None
                    try:
                        sa_img_fn = getattr(sa, "image", None)
                        if callable(sa_img_fn):
                            sa_image = sa_img_fn(320)
                    except Exception:
                        pass
                    similar_artists.append({
                        "name": getattr(sa, "name", ""),
                        "image_url": sa_image,
                        "tidal_id": str(sa.id),
                    })
            except Exception:
                logger.debug("get_artist_info: similar artists unavailable for '%s'", artist_name)

            return {
                "name": getattr(best, "name", artist_name),
                "image_url": image_url,
                "albums": albums_out,
                "tracks": tracks_out,
                "tidal_id": str(best.id),
                "bio": bio_text,
                "similar_artists": similar_artists,
            }
        except Exception:
            logger.debug("get_artist_info failed", exc_info=True)
            return None

    def get_artist_image_url(self, artist_name: str) -> str | None:
        """Return only the image URL for an artist (lightweight).

        Unlike ``get_artist_info`` this does NOT fetch albums, tracks,
        bio, or similar artists — it only searches for the artist and
        returns their image URL.  Use this when you only need the image
        (e.g. for the artist image cache service).
        """
        try:
            if not self.is_logged_in:
                return None
            with self._session_lock:
                results = self._session.search(
                    artist_name, models=[tidalapi.Artist], limit=5
                )
            artists = results.get("artists", [])
            if not artists:
                return None

            # Find best match
            best = None
            for a in artists:
                a_name = getattr(a, "name", "") or ""
                if a_name.lower() == artist_name.lower():
                    best = a
                    break
            if best is None:
                best = artists[0]

            # Get artist image
            try:
                img_fn = getattr(best, "image", None)
                if callable(img_fn):
                    return img_fn(480)
                elif isinstance(img_fn, str) and img_fn:
                    return (
                        f"https://resources.tidal.com/images/"
                        f"{img_fn.replace('-', '/')}/480x480.jpg"
                    )
            except Exception:
                pass
            return None
        except Exception:
            logger.debug("get_artist_image_url failed for '%s'", artist_name)
            return None

    def get_playlist_tracks(self, playlist_id: str) -> list["Track"]:
        """Get tracks from a Tidal playlist by its ID.

        Returns a list of Track models.  Returns an empty list if not
        logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                playlist = self._session.playlist(playlist_id)
                tidal_tracks = playlist.tracks()
            return [self._tidal_track_to_model(t) for t in tidal_tracks]
        except Exception:
            logger.debug("get_playlist_tracks failed", exc_info=True)
            raise

    def get_mix_tracks(self, mix_id: str) -> list["Track"]:
        """Get tracks from a Tidal personalized mix by its ID.

        Mixes (My Mix 1, My Daily Discovery, etc.) use a different API
        endpoint from playlists.  ``mix.items()`` can return both Track
        and Video objects — only Track objects are converted.

        Returns a list of Track models.  Returns an empty list if not
        logged in.  Raises on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                mix_obj = self._session.mix(mix_id)
                items = mix_obj.items()
            # Filter to tracks only (skip Video objects)
            from tidalapi.media import Track as TidalTrack
            tracks = [
                self._tidal_track_to_model(item)
                for item in items
                if isinstance(item, TidalTrack)
            ]
            return tracks
        except Exception:
            logger.debug("get_mix_tracks failed for %s", mix_id, exc_info=True)
            raise

    # ------------------------------------------------------------------
    # Collection: saved albums & followed artists
    # ------------------------------------------------------------------

    def get_favorite_albums(self) -> list[dict]:
        """Return all of the user's saved/favorited albums from Tidal.

        Paginates through the full list (default page size is 50).
        """
        if not self.is_logged_in:
            return []
        try:
            all_albums = []
            offset = 0
            page_size = 50
            while True:
                with self._session_lock:
                    page = self._session.user.favorites.albums(
                        limit=page_size, offset=offset
                    )
                if not page:
                    break
                all_albums.extend(self._album_to_dict(a) for a in page)
                if len(page) < page_size:
                    break
                offset += page_size
            return all_albums
        except Exception:
            logger.debug("get_favorite_albums failed", exc_info=True)
            return []

    def get_followed_artists(self) -> list[dict]:
        """Return all of the user's followed artists from Tidal.

        Paginates through the full list (default page size is 50).
        """
        if not self.is_logged_in:
            return []
        try:
            result = []
            offset = 0
            page_size = 50
            while True:
                with self._session_lock:
                    page = self._session.user.favorites.artists(
                        limit=page_size, offset=offset
                    )
                if not page:
                    break
                for a in page:
                    image_url = None
                    try:
                        img_fn = getattr(a, "image", None)
                        if callable(img_fn):
                            image_url = img_fn(480)
                    except Exception:
                        pass
                    result.append({
                        "name": a.name,
                        "tidal_id": str(a.id),
                        "image_url": image_url,
                        "num_albums": getattr(a, "num_albums", None),
                    })
                if len(page) < page_size:
                    break
                offset += page_size
            return result
        except Exception:
            logger.debug("get_followed_artists failed", exc_info=True)
            return []

    # ------------------------------------------------------------------
    # Radio
    # ------------------------------------------------------------------

    def get_track_radio(self, track_id: str, limit: int = 50) -> list["Track"]:
        """Get radio tracks based on a seed track.

        Uses ``tidalapi.Track.get_track_radio()`` to fetch similar tracks.
        Returns a list of Track models.
        """
        if not self.is_logged_in:
            return []
        try:
            with self._session_lock:
                tidal_track = self._session.track(int(track_id))
                radio_tracks = tidal_track.get_track_radio(limit=limit)
            return [self._tidal_track_to_model(t) for t in radio_tracks]
        except Exception:
            logger.debug("get_track_radio failed for %s", track_id, exc_info=True)
            return []

    def get_artist_radio(self, artist_name: str, limit: int = 50) -> list["Track"]:
        """Get radio tracks based on an artist.

        Looks up the artist by name, then calls ``artist.get_radio()``.
        Returns a list of Track models.
        """
        if not self.is_logged_in:
            return []
        try:
            with self._session_lock:
                results = self._session.search(
                    artist_name, models=[tidalapi.Artist], limit=5
                )
            artists = results.get("artists", [])
            if not artists:
                return []
            # Find best match
            best = None
            for a in artists:
                if (getattr(a, "name", "") or "").lower() == artist_name.lower():
                    best = a
                    break
            if best is None:
                best = artists[0]
            with self._session_lock:
                radio_tracks = best.get_radio(limit=limit)
            return [self._tidal_track_to_model(t) for t in radio_tracks]
        except Exception:
            logger.debug("get_artist_radio failed for %s", artist_name, exc_info=True)
            return []

    # ------------------------------------------------------------------
    # Follow / Unfollow
    # ------------------------------------------------------------------

    def follow_artist(self, artist_name: str) -> bool:
        """Follow an artist on Tidal. Returns True on success."""
        if not self.is_logged_in:
            return False
        try:
            with self._session_lock:
                results = self._session.search(
                    artist_name, models=[tidalapi.Artist], limit=5
                )
            artists = results.get("artists", [])
            if not artists:
                return False
            best = None
            for a in artists:
                if (getattr(a, "name", "") or "").lower() == artist_name.lower():
                    best = a
                    break
            if best is None:
                best = artists[0]
            with self._session_lock:
                self._session.user.favorites.add_artist(best.id)
            return True
        except Exception:
            logger.debug("follow_artist failed for %s", artist_name, exc_info=True)
            return False

    def unfollow_artist(self, artist_name: str) -> bool:
        """Unfollow an artist on Tidal. Returns True on success."""
        if not self.is_logged_in:
            return False
        try:
            with self._session_lock:
                results = self._session.search(
                    artist_name, models=[tidalapi.Artist], limit=5
                )
            artists = results.get("artists", [])
            if not artists:
                return False
            best = None
            for a in artists:
                if (getattr(a, "name", "") or "").lower() == artist_name.lower():
                    best = a
                    break
            if best is None:
                best = artists[0]
            with self._session_lock:
                self._session.user.favorites.remove_artist(best.id)
            return True
        except Exception:
            logger.debug("unfollow_artist failed for %s", artist_name, exc_info=True)
            return False

    def is_following_artist(self, artist_name: str) -> bool:
        """Check whether the user follows an artist on Tidal."""
        if not self.is_logged_in:
            return False
        try:
            followed = self.get_followed_artists()
            return any(
                a["name"].lower() == artist_name.lower() for a in followed
            )
        except Exception:
            logger.debug("is_following_artist failed for %s", artist_name, exc_info=True)
            return False

    # ------------------------------------------------------------------
    # Credits
    # ------------------------------------------------------------------

    def get_track_credits(self, track_id: str) -> list[dict] | None:
        """Fetch credits for a Tidal track via the raw API.

        Returns a list of dicts with keys ``type`` and ``contributors``
        (list of contributor names), or None if unavailable.
        """
        if not self.is_logged_in:
            return None
        try:
            with self._session_lock:
                # Use the raw Tidal API endpoint: tracks/{id}/credits
                resp = self._session.request.request(
                    "GET", f"tracks/{int(track_id)}/credits"
                )
            raw_credits = resp.json() if resp.ok else None
            if not raw_credits:
                return None
            credits_out: list[dict] = []
            for credit in raw_credits:
                credit_type = credit.get("type", "")
                contributors = []
                for c in credit.get("contributors", []):
                    name = c.get("name", "")
                    if name:
                        contributors.append(name)
                if contributors:
                    credits_out.append({
                        "type": credit_type,
                        "contributors": contributors,
                    })
            return credits_out if credits_out else None
        except Exception:
            logger.warning("get_track_credits failed for %s", track_id, exc_info=True)
            return None

    # ------------------------------------------------------------------
    # Lyrics
    # ------------------------------------------------------------------

    def get_lyrics(self, track_id: str) -> dict | None:
        """Fetch lyrics for a Tidal track.

        Returns a dict with keys ``text``, ``subtitles``, and ``provider``,
        or ``None`` if lyrics are unavailable or the request fails.
        """
        try:
            with self._session_lock:
                tidal_track = self._session.track(int(track_id))
                lyrics_obj = tidal_track.lyrics()
            return {
                "text": getattr(lyrics_obj, "text", None) or "",
                "subtitles": getattr(lyrics_obj, "subtitles", None) or "",
                "provider": getattr(lyrics_obj, "provider", ""),
            }
        except Exception:
            logger.debug(
                "Failed to fetch Tidal lyrics for track %s", track_id,
                exc_info=True,
            )
            return None

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _save_session(self) -> None:
        """Snapshot current session tokens under lock and write to disk."""
        with self._session_lock:
            data = {
                "token_type": self._session.token_type,
                "access_token": self._session.access_token,
                "refresh_token": self._session.refresh_token,
                "expiry_time": self._session.expiry_time,
            }
        self._write_session_file(data)

    @staticmethod
    def _write_session_file(data: dict) -> None:
        """Write *data* as JSON to the session file with 0o600 permissions."""
        serializable = dict(data)
        expiry = serializable.get("expiry_time")
        if isinstance(expiry, datetime):
            serializable["expiry_time"] = expiry.isoformat()
        content = json.dumps(serializable)
        fd = os.open(str(SESSION_FILE), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            os.fchmod(fd, 0o600)
            os.write(fd, content.encode())
        finally:
            os.close(fd)

    def _tidal_track_to_model(self, tidal_track: Any) -> Track:
        """Convert a ``tidalapi.Track`` to our Track dataclass."""
        cover = getattr(tidal_track.album, "cover", None) or ""
        album_art_url: Optional[str] = None
        if cover:
            album_art_url = (
                f"https://resources.tidal.com/images/"
                f"{cover.replace('-', '/')}/640x640.jpg"
            )

        # Capture when the user added this track to favorites
        added_at_str: str | None = None
        tidal_date = getattr(tidal_track, "date_added", None) or getattr(
            tidal_track, "user_date_added", None
        )
        if tidal_date is not None:
            try:
                added_at_str = tidal_date.isoformat()
            except Exception:
                pass

        # Audio quality metadata from Tidal
        audio_quality = getattr(tidal_track, "audio_quality", None) or ""
        media_tags = getattr(tidal_track, "media_metadata_tags", None) or []
        fmt = "FLAC"
        bitrate: int | None = None
        sample_rate: int | None = None
        bit_depth: int | None = None
        if "HI_RES_LOSSLESS" in audio_quality or (
            hasattr(tidalapi.media, "MediaMetadataTags")
            and tidalapi.media.MediaMetadataTags.hi_res_lossless in media_tags
        ):
            fmt = "Hi-Res"
            sample_rate = 96000
            bit_depth = 24
            bitrate = 4608
        elif "LOSSLESS" in audio_quality:
            fmt = "FLAC"
            sample_rate = 44100
            bit_depth = 16
            bitrate = 1411
        elif "HIGH" in audio_quality:
            fmt = "AAC"
            sample_rate = 44100
            bit_depth = 16
            bitrate = 320
        elif audio_quality:
            fmt = "AAC"
            sample_rate = 44100
            bitrate = 96

        return Track(
            title=tidal_track.name,
            artist=tidal_track.artist.name,
            album=tidal_track.album.name,
            source=Source.TIDAL,
            source_id=str(tidal_track.id),
            duration=tidal_track.duration,
            format=fmt,
            bitrate=bitrate,
            sample_rate=sample_rate,
            bit_depth=bit_depth,
            album_art_url=album_art_url,
            added_at=added_at_str,
        )
