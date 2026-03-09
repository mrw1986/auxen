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

# App-level Last.fm API credentials (registered for Auxen).
_DEFAULT_API_KEY = "2fe383e0bc788ccf2646b48b2a1e3d3d"
_DEFAULT_API_SECRET = "6f9dc31b30ffeed6f7208c271dcc3303"

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
            self._api_key = api_key.strip()
        elif db is not None:
            stored = db.get_setting("lastfm_api_key")
            self._api_key = stored.strip() if stored else _DEFAULT_API_KEY
        else:
            self._api_key = _DEFAULT_API_KEY

        if api_secret is not None:
            self._api_secret = api_secret.strip()
        elif db is not None:
            stored = db.get_setting("lastfm_api_secret")
            self._api_secret = stored.strip() if stored else _DEFAULT_API_SECRET
        else:
            self._api_secret = _DEFAULT_API_SECRET

        # Session state
        self._session_key: str | None = None
        self._username: str | None = None
        self._enabled: bool = False
        self._auth_token: str | None = None

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

    def get_auth_token(self) -> str | None:
        """Request an auth token from Last.fm (step 1 of desktop auth).

        Returns the token string on success, or ``None`` on failure.
        The token is stored internally for ``complete_auth_from_token()``.
        """
        params = {
            "method": "auth.getToken",
            "api_key": self._api_key,
            "format": "json",
        }
        try:
            data = self._api_call(params)
            token = data.get("token")
            if token:
                self._auth_token = token
            return token
        except Exception:
            logger.warning("Failed to get Last.fm auth token", exc_info=True)
            return None

    def get_auth_url(self, token: str | None = None) -> str:
        """Return the URL the user should visit to authorize Auxen.

        Parameters
        ----------
        token:
            An auth token from ``get_auth_token()``.  When provided, the
            URL uses the token-based desktop auth flow which does not
            require a callback URL.  When ``None``, falls back to the
            basic auth URL.
        """
        if token:
            return (
                f"{_AUTH_URL}?api_key={self._api_key}"
                f"&token={token}"
            )
        return f"{_AUTH_URL}?api_key={self._api_key}"

    def validate_api_key(self) -> tuple[bool, str]:
        """Check whether the configured API key is valid.

        Returns ``(True, "")`` on success, or ``(False, error_message)``
        on failure.
        """
        key = self._api_key
        if not key or len(key) != 32:
            return False, (
                f"API key must be exactly 32 hex characters "
                f"(current length: {len(key)})"
            )

        # Test the key with a lightweight API call
        params = {
            "method": "auth.getToken",
            "api_key": key,
            "format": "json",
        }
        try:
            self._api_call(params)
            return True, ""
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            if exc.code == 403 or "invalid api key" in body.lower():
                return False, (
                    "Invalid API key. Please register your own Last.fm "
                    "API application at https://www.last.fm/api/account/create "
                    "and enter your API Key and Shared Secret in Settings."
                )
            return False, f"HTTP {exc.code}: {body[:200]}"
        except Exception as exc:
            return False, f"Connection error: {exc}"

    def complete_auth_from_token(self) -> bool:
        """Complete auth using the internally stored token (step 3).

        Call this after the user has authorized the token in their
        browser.  Returns ``True`` on success.
        """
        if not self._auth_token:
            logger.warning("No auth token available for completion")
            return False
        return self.complete_auth(self._auth_token)

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
            self._auth_token = None  # Clear after use
            return bool(self._session_key)
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            logger.warning(
                "Last.fm auth failed (HTTP %s): %s", exc.code, body[:300]
            )
            return False
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

    @property
    def api_key(self) -> str:
        """The currently configured Last.fm API key."""
        return self._api_key

    @property
    def api_secret(self) -> str:
        """The currently configured Last.fm API secret."""
        return self._api_secret

    @property
    def uses_default_credentials(self) -> bool:
        """Return ``True`` if using the built-in (placeholder) credentials."""
        return (
            self._api_key == _DEFAULT_API_KEY
            and self._api_secret == _DEFAULT_API_SECRET
        )

    def update_api_credentials(
        self, api_key: str, api_secret: str
    ) -> None:
        """Update the Last.fm API key and secret.

        Persists to the database and clears any existing session, since
        the old session key is tied to the old credentials.
        """
        api_key = api_key.strip()
        api_secret = api_secret.strip()

        self._api_key = api_key
        self._api_secret = api_secret

        # Clear session since it's tied to the old credentials
        self._session_key = None
        self._username = None

        if self._db is not None:
            self._db.set_setting("lastfm_api_key", api_key)
            self._db.set_setting("lastfm_api_secret", api_secret)
            self._db.set_setting("lastfm_session_key", "")
            self._db.set_setting("lastfm_username", "")

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
