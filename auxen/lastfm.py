"""Last.fm scrobbling service for the Auxen music player.

Uses the Last.fm API v2 directly via ``urllib.request`` -- no external
library needed.  Provides authentication (web auth flow), now-playing
updates, and scrobble submission with standard Last.fm rules.

Scrobble rules (per Last.fm spec):
    - Track must have been played for > 30 seconds.
    - Track must have been played for > 50 % of its duration *or* > 4 minutes,
      whichever comes first.
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)

# -----------------------------------------------------------------------
# Constants
# -----------------------------------------------------------------------

_API_ROOT = "https://ws.audioscrobbler.com/2.0/"
_AUTH_URL = "https://www.last.fm/api/auth/"

# Placeholder API credentials -- users should replace these with their
# own values obtained from https://www.last.fm/api/account/create
_DEFAULT_API_KEY = "AUXEN_LASTFM_KEY"
_DEFAULT_API_SECRET = "AUXEN_LASTFM_SECRET"

# Minimum play thresholds for a valid scrobble.
_MIN_PLAY_SECONDS = 30
_MAX_SCROBBLE_SECONDS = 240  # 4 minutes


def _make_api_sig(params: dict[str, str], secret: str) -> str:
    """Generate a Last.fm API method signature.

    The signature is the MD5 hash of all ``key=value`` pairs sorted
    alphabetically, concatenated, with the API secret appended.
    """
    ordered = "".join(
        f"{k}{v}" for k, v in sorted(params.items())
    )
    return hashlib.md5((ordered + secret).encode("utf-8")).hexdigest()


def should_scrobble(play_seconds: float, track_duration: float) -> bool:
    """Return ``True`` if the scrobble criteria are met.

    Parameters
    ----------
    play_seconds:
        How long the user listened (seconds).
    track_duration:
        Total track length (seconds).  Use 0 or negative if unknown.
    """
    if play_seconds < _MIN_PLAY_SECONDS:
        return False

    if play_seconds >= _MAX_SCROBBLE_SECONDS:
        return True

    if track_duration > 0 and play_seconds >= track_duration * 0.5:
        return True

    return False


class LastFmService:
    """Last.fm scrobbling and now-playing service.

    Parameters
    ----------
    db:
        Optional database instance for persisting settings.  When
        ``None``, the service operates in a stateless mode (useful
        for testing).
    api_key:
        Last.fm API key.  Falls back to the built-in placeholder.
    api_secret:
        Last.fm API secret.  Falls back to the built-in placeholder.
    """

    def __init__(
        self,
        db: Any = None,
        api_key: str | None = None,
        api_secret: str | None = None,
    ) -> None:
        self._db = db

        # Load keys: constructor args > database > defaults
        if api_key is not None:
            self._api_key = api_key
        elif db is not None:
            stored = db.get_setting("lastfm_api_key")
            self._api_key = stored if stored else _DEFAULT_API_KEY
        else:
            self._api_key = _DEFAULT_API_KEY

        if api_secret is not None:
            self._api_secret = api_secret
        elif db is not None:
            stored = db.get_setting("lastfm_api_secret")
            self._api_secret = stored if stored else _DEFAULT_API_SECRET
        else:
            self._api_secret = _DEFAULT_API_SECRET

        # Session state
        self._session_key: str | None = None
        self._username: str | None = None
        self._enabled: bool = False

        # Restore session from database
        if db is not None:
            sk = db.get_setting("lastfm_session_key")
            if sk:
                self._session_key = sk
            un = db.get_setting("lastfm_username")
            if un:
                self._username = un
            en = db.get_setting("lastfm_enabled")
            if en is not None:
                self._enabled = en == "1"

    # ------------------------------------------------------------------
    # Authentication
    # ------------------------------------------------------------------

    def get_auth_url(self) -> str:
        """Return the URL the user should visit to authorize Auxen."""
        return f"{_AUTH_URL}?api_key={self._api_key}"

    def complete_auth(self, token: str) -> bool:
        """Exchange an auth *token* for a session key.

        Returns ``True`` on success.
        """
        params = {
            "method": "auth.getSession",
            "api_key": self._api_key,
            "token": token,
        }
        params["api_sig"] = _make_api_sig(params, self._api_secret)
        params["format"] = "json"

        try:
            data = self._api_call(params)
            session = data.get("session", {})
            self._session_key = session.get("key")
            self._username = session.get("name")

            if self._session_key and self._db is not None:
                self._db.set_setting(
                    "lastfm_session_key", self._session_key
                )
                if self._username:
                    self._db.set_setting(
                        "lastfm_username", self._username
                    )
            return bool(self._session_key)
        except Exception:
            logger.warning("Last.fm auth failed", exc_info=True)
            return False

    def is_authenticated(self) -> bool:
        """Return ``True`` when a session key is available."""
        return self._session_key is not None

    def disconnect(self) -> None:
        """Clear session key and username."""
        self._session_key = None
        self._username = None
        if self._db is not None:
            self._db.set_setting("lastfm_session_key", "")
            self._db.set_setting("lastfm_username", "")

    @property
    def username(self) -> str | None:
        """The authenticated Last.fm username, or ``None``."""
        return self._username

    # ------------------------------------------------------------------
    # Enabled toggle
    # ------------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        """Whether scrobbling is currently enabled."""
        return self._enabled

    def set_enabled(self, enabled: bool) -> None:
        """Toggle scrobbling on or off."""
        self._enabled = bool(enabled)
        if self._db is not None:
            self._db.set_setting(
                "lastfm_enabled", "1" if self._enabled else "0"
            )

    # ------------------------------------------------------------------
    # Scrobbling
    # ------------------------------------------------------------------

    def update_now_playing(
        self,
        title: str,
        artist: str,
        album: str = "",
        duration: float = 0,
    ) -> None:
        """Send a *now-playing* notification to Last.fm.

        This is fire-and-forget: errors are logged but not raised.
        The call is dispatched to a background thread.
        """
        if not self._enabled or not self.is_authenticated():
            return

        # Snapshot auth state before spawning the thread so a concurrent
        # disconnect() cannot clear _session_key mid-flight.
        api_key = self._api_key
        api_secret = self._api_secret
        session_key = self._session_key

        def _do() -> None:
            params: dict[str, str] = {
                "method": "track.updateNowPlaying",
                "api_key": api_key,
                "sk": session_key,  # type: ignore[arg-type]
                "artist": artist,
                "track": title,
            }
            if album:
                params["album"] = album
            if duration > 0:
                params["duration"] = str(int(duration))

            params["api_sig"] = _make_api_sig(params, api_secret)
            params["format"] = "json"

            try:
                self._api_post(params)
            except Exception:
                logger.warning(
                    "Failed to update Last.fm now-playing", exc_info=True
                )

        thread = threading.Thread(target=_do, daemon=True)
        thread.start()

    def scrobble(
        self,
        title: str,
        artist: str,
        album: str = "",
        duration: float = 0,
        timestamp: int | None = None,
    ) -> None:
        """Submit a scrobble to Last.fm.

        Parameters
        ----------
        title, artist, album, duration:
            Track metadata.
        timestamp:
            Unix timestamp when playback started.  Defaults to now.
        """
        if not self._enabled or not self.is_authenticated():
            return

        ts = timestamp if timestamp is not None else int(time.time())

        # Snapshot auth state before spawning the thread.
        api_key = self._api_key
        api_secret = self._api_secret
        session_key = self._session_key

        def _do() -> None:
            params: dict[str, str] = {
                "method": "track.scrobble",
                "api_key": api_key,
                "sk": session_key,  # type: ignore[arg-type]
                "artist": artist,
                "track": title,
                "timestamp": str(ts),
            }
            if album:
                params["album"] = album
            if duration > 0:
                params["duration"] = str(int(duration))

            params["api_sig"] = _make_api_sig(params, api_secret)
            params["format"] = "json"

            try:
                self._api_post(params)
            except Exception:
                logger.warning(
                    "Failed to scrobble to Last.fm", exc_info=True
                )

        thread = threading.Thread(target=_do, daemon=True)
        thread.start()

    # ------------------------------------------------------------------
    # Low-level API helpers
    # ------------------------------------------------------------------

    def _api_call(self, params: dict[str, str]) -> dict:
        """Perform a GET request to the Last.fm API and return the JSON
        response body as a dictionary.
        """
        url = f"{_API_ROOT}?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Auxen/0.1"},
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _api_post(self, params: dict[str, str]) -> dict:
        """Perform a POST request to the Last.fm API and return the JSON
        response body as a dictionary.
        """
        data = urllib.parse.urlencode(params).encode("utf-8")
        req = urllib.request.Request(
            _API_ROOT,
            data=data,
            method="POST",
            headers={"User-Agent": "Auxen/0.1"},
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
