"""Tidal streaming provider — wraps tidalapi for auth, search, and streaming."""

from __future__ import annotations

import json
import logging
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
        SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def is_logged_in(self) -> bool:
        """Return True when the session is authenticated."""
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
            self._session.load_oauth_session(
                token_type=data["token_type"],
                access_token=data["access_token"],
                refresh_token=data["refresh_token"],
                expiry_time=data["expiry_time"],
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
        """
        try:
            login, future = self._session.login_oauth()
            url = login.verification_uri_complete

            if url_callback is not None:
                url_callback(f"https://{url}")
            else:
                print(f"Visit https://{url} to log in to Tidal")

            future.result()
            self._save_session()
            return True
        except Exception:
            logger.error("Tidal login failed.", exc_info=True)
            return False

    def logout(self) -> None:
        """Delete the persisted session file."""
        try:
            SESSION_FILE.unlink(missing_ok=True)
        except OSError:
            pass

    # ------------------------------------------------------------------
    # Content provider interface
    # ------------------------------------------------------------------

    def search(self, query: str, limit: int = 20) -> list[Track]:
        """Search Tidal for tracks matching *query*."""
        results = self._session.search(query, models=[tidalapi.Track], limit=limit)
        tidal_tracks = results.get("tracks", [])
        return [self._tidal_track_to_model(t) for t in tidal_tracks]

    def get_stream_uri(self, track: Track) -> str:
        """Resolve a playable stream URL for a Tidal track."""
        tidal_track = self._session.track(int(track.source_id))
        return tidal_track.get_url()

    # ------------------------------------------------------------------
    # Favorites
    # ------------------------------------------------------------------

    def get_favorites(self) -> list[Track]:
        """Return the user's favourite Tidal tracks."""
        tidal_tracks = self._session.user.favorites.tracks()
        return [self._tidal_track_to_model(t) for t in tidal_tracks]

    def add_favorite(self, track_id: str) -> None:
        """Add a track to the user's Tidal favourites."""
        self._session.user.favorites.add_track(int(track_id))

    def remove_favorite(self, track_id: str) -> None:
        """Remove a track from the user's Tidal favourites."""
        self._session.user.favorites.remove_track(int(track_id))

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
    # Internal helpers
    # ------------------------------------------------------------------

    def _save_session(self) -> None:
        """Persist the current OAuth tokens to disk."""
        data = {
            "token_type": self._session.token_type,
            "access_token": self._session.access_token,
            "refresh_token": self._session.refresh_token,
            "expiry_time": self._session.expiry_time,
        }
        SESSION_FILE.write_text(json.dumps(data))

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
