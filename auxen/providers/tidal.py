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
