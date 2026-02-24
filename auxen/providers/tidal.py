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
        SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def is_logged_in(self) -> bool:
        """Return True when the session is authenticated."""
        with self._session_lock:
            return self._session.check_login()

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    def restore_session(self) -> bool:
        """Load a previously saved OAuth session from disk.

        Returns True on success, False otherwise.  Corrupt session files
        are deleted automatically.
        """
        if not SESSION_FILE.exists():
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
            return True
        except Exception:
            logger.warning("Corrupt Tidal session file — removing it.", exc_info=True)
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
            return True
        except Exception:
            logger.error("Tidal login failed.", exc_info=True)
            return False

    def logout(self) -> None:
        """Delete the persisted session file and clear in-memory auth."""
        with self._session_lock:
            self._auth_generation += 1
            self._session = tidalapi.Session()
        try:
            SESSION_FILE.unlink(missing_ok=True)
        except OSError:
            pass

    # ------------------------------------------------------------------
    # Content provider interface
    # ------------------------------------------------------------------

    def search(self, query: str, limit: int = 20) -> list[Track]:
        """Search Tidal for tracks matching *query*."""
        with self._session_lock:
            results = self._session.search(query, models=[tidalapi.Track], limit=limit)
        tidal_tracks = results.get("tracks", [])
        return [self._tidal_track_to_model(t) for t in tidal_tracks]

    def get_stream_uri(self, track: Track) -> str:
        """Resolve a playable stream URL for a Tidal track."""
        with self._session_lock:
            tidal_track = self._session.track(int(track.source_id))
            return tidal_track.get_url()

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
        Returns an empty list if not logged in or on API failure.
        """
        try:
            if not self.is_logged_in:
                return []
            with self._session_lock:
                explore_page = self._session.explore()
            albums: list[dict] = []
            for category in explore_page:
                items = getattr(category, "items", None)
                if items is None:
                    continue
                for item in items:
                    if isinstance(item, tidalapi.Album) and len(albums) < limit:
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
            return albums[:limit]
        except Exception:
            logger.debug("get_featured_albums failed", exc_info=True)
            return []

    def get_new_releases(self, limit: int = 12) -> list[dict]:
        """Get new releases from Tidal.

        Returns a list of dicts with keys: title, artist, cover_url, tidal_id.
        Falls back to searching 'new releases' if the explore page fails.
        """
        try:
            if not self.is_logged_in:
                return []
            # Try the explore page first for new-release categories
            with self._session_lock:
                explore_page = self._session.explore()
            albums: list[dict] = []
            for category in explore_page:
                cat_title = getattr(category, "title", "") or ""
                if "new" not in cat_title.lower():
                    continue
                items = getattr(category, "items", None)
                if items is None:
                    continue
                for item in items:
                    if isinstance(item, tidalapi.Album) and len(albums) < limit:
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
            if albums:
                return albums[:limit]

            # Fallback: search for new releases
            with self._session_lock:
                results = self._session.search(
                    "new releases", models=[tidalapi.Album], limit=limit
                )
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
            return albums[:limit]
        except Exception:
            logger.debug("get_new_releases failed", exc_info=True)
            return []

    def get_top_tracks(self, limit: int = 20) -> list[Track]:
        """Get Tidal's top/popular tracks.

        Returns a list of Track models.  Falls back to searching
        'top hits' if the explore page yields nothing.
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
            return []

    def get_genres(self) -> list[str]:
        """Get available genre names from Tidal.

        Returns a list of genre title strings, or an empty list on failure.
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
            return []

    # ------------------------------------------------------------------
    # Mixes & User Playlists
    # ------------------------------------------------------------------

    def get_mixes(self, limit: int = 12) -> list[dict]:
        """Get user's personalized mixes from Tidal.

        Returns a list of dicts with keys: name, description, cover_url,
        tidal_id, track_count.  Returns an empty list if not logged in
        or on API failure.

        Tries the user's mixes via the home/explore page first; falls
        back to the user's playlists if no dedicated mixes are found.
        """
        try:
            if not self.is_logged_in:
                return []

            mixes: list[dict] = []

            # Attempt 1: Look for Mix items on the explore/home page
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
                            cover = getattr(item, "cover", None) or ""
                            cover_url = None
                            if cover:
                                cover_url = (
                                    f"https://resources.tidal.com/images/"
                                    f"{cover.replace('-', '/')}/640x640.jpg"
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

            # Attempt 2: Fall back to user's playlists
            playlists = self.get_user_playlists(limit=limit)
            for pl in playlists:
                mixes.append({
                    "name": pl["name"],
                    "description": pl.get("description", ""),
                    "cover_url": pl.get("cover_url"),
                    "tidal_id": pl["tidal_id"],
                    "track_count": pl.get("track_count", 0),
                })

            return mixes[:limit]
        except Exception:
            logger.debug("get_mixes failed", exc_info=True)
            return []

    def get_user_playlists(self, limit: int = 20) -> list[dict]:
        """Get the authenticated user's Tidal playlists.

        Returns a list of dicts with keys: name, description, cover_url,
        tidal_id, track_count.  Returns an empty list if not logged in
        or on API failure.
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
                cover = getattr(pl, "image", None) or getattr(
                    pl, "picture", None
                ) or getattr(pl, "square_image", None) or ""
                cover_url = None
                if cover:
                    cover_url = (
                        f"https://resources.tidal.com/images/"
                        f"{cover.replace('-', '/')}/640x640.jpg"
                    )
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
            return []

    def get_playlist_tracks(self, playlist_id: str) -> list["Track"]:
        """Get tracks from a Tidal playlist by its ID.

        Returns a list of Track models.
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
            return []

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

        return Track(
            title=tidal_track.name,
            artist=tidal_track.artist.name,
            album=tidal_track.album.name,
            source=Source.TIDAL,
            source_id=str(tidal_track.id),
            duration=tidal_track.duration,
            format="FLAC",
            album_art_url=album_art_url,
        )
