"""Tests for auxen.lastfm — LastFmService."""

from __future__ import annotations

import hashlib
import json
import time
from io import BytesIO
from unittest.mock import MagicMock, patch

import pytest

from auxen.lastfm import (
    LastFmService,
    _DEFAULT_API_KEY,
    _DEFAULT_API_SECRET,
    _make_api_sig,
    should_scrobble,
)


# ---- Helpers ----


def _make_db(**overrides: str) -> MagicMock:
    """Create a mock database that returns stored settings."""
    settings: dict[str, str | None] = {
        "lastfm_api_key": None,
        "lastfm_api_secret": None,
        "lastfm_session_key": None,
        "lastfm_username": None,
        "lastfm_enabled": None,
    }
    settings.update(overrides)
    db = MagicMock()
    db.get_setting.side_effect = lambda key, default=None: settings.get(
        key, default
    )
    return db


# ===================================================================
# Authentication
# ===================================================================


class TestAuthentication:
    """Tests for authentication state and URL generation."""

    def test_is_authenticated_false_initially(self) -> None:
        svc = LastFmService()
        assert svc.is_authenticated() is False

    def test_is_authenticated_true_after_session_key_restored(self) -> None:
        db = _make_db(lastfm_session_key="abc123")
        svc = LastFmService(db=db)
        assert svc.is_authenticated() is True

    def test_get_auth_url_format(self) -> None:
        svc = LastFmService(api_key="MY_KEY")
        url = svc.get_auth_url()
        assert "api_key=MY_KEY" in url
        assert url.startswith("https://www.last.fm/api/auth/")

    def test_get_auth_url_uses_default_key(self) -> None:
        svc = LastFmService()
        url = svc.get_auth_url()
        assert f"api_key={_DEFAULT_API_KEY}" in url

    def test_username_none_when_not_authenticated(self) -> None:
        svc = LastFmService()
        assert svc.username is None

    def test_username_restored_from_db(self) -> None:
        db = _make_db(
            lastfm_session_key="sk123",
            lastfm_username="testuser",
        )
        svc = LastFmService(db=db)
        assert svc.username == "testuser"

    def test_disconnect_clears_session(self) -> None:
        db = _make_db(lastfm_session_key="sk123", lastfm_username="me")
        svc = LastFmService(db=db)
        assert svc.is_authenticated() is True

        svc.disconnect()
        assert svc.is_authenticated() is False
        assert svc.username is None
        db.set_setting.assert_any_call("lastfm_session_key", "")
        db.set_setting.assert_any_call("lastfm_username", "")


# ===================================================================
# complete_auth
# ===================================================================


class TestCompleteAuth:
    """Tests for the complete_auth token-exchange method."""

    @patch("auxen.lastfm.urllib.request.urlopen")
    def test_complete_auth_success(self, mock_urlopen) -> None:
        response_body = json.dumps(
            {"session": {"key": "session_key_123", "name": "my_user"}}
        ).encode()
        mock_resp = MagicMock()
        mock_resp.read.return_value = response_body
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        db = _make_db()
        svc = LastFmService(db=db, api_key="k", api_secret="s")
        result = svc.complete_auth("my_token")

        assert result is True
        assert svc.is_authenticated() is True
        assert svc.username == "my_user"
        db.set_setting.assert_any_call(
            "lastfm_session_key", "session_key_123"
        )
        db.set_setting.assert_any_call("lastfm_username", "my_user")

    @patch("auxen.lastfm.urllib.request.urlopen")
    def test_complete_auth_failure(self, mock_urlopen) -> None:
        mock_urlopen.side_effect = Exception("network error")
        svc = LastFmService(api_key="k", api_secret="s")
        result = svc.complete_auth("bad_token")
        assert result is False
        assert svc.is_authenticated() is False


# ===================================================================
# Scrobble Rules
# ===================================================================


class TestScrobbleRules:
    """Test the ``should_scrobble`` function."""

    def test_less_than_30s_is_not_scrobbled(self) -> None:
        assert should_scrobble(play_seconds=29, track_duration=60) is False

    def test_exactly_30s_is_not_scrobbled(self) -> None:
        # "more than 30 seconds" means > 30, not >= 30
        assert should_scrobble(play_seconds=30, track_duration=300) is False

    def test_31s_with_short_track_is_scrobbled(self) -> None:
        # 31s out of 60s => ~52% which is > 50%
        assert should_scrobble(play_seconds=31, track_duration=60) is True

    def test_50_percent_of_duration_is_scrobbled(self) -> None:
        # Exactly 50% of 100s = 50s
        assert should_scrobble(play_seconds=50, track_duration=100) is True

    def test_less_than_50_percent_not_scrobbled(self) -> None:
        # 49s of 100s = 49%, below threshold, and < 240s
        assert should_scrobble(play_seconds=49, track_duration=100) is False

    def test_4_minutes_always_scrobbles(self) -> None:
        # 240s = 4 minutes, even if < 50% of a very long track
        assert should_scrobble(play_seconds=240, track_duration=600) is True

    def test_over_4_minutes_always_scrobbles(self) -> None:
        assert should_scrobble(play_seconds=300, track_duration=1200) is True

    def test_unknown_duration_with_enough_play_time(self) -> None:
        # Duration 0 (unknown), but played 240s => scrobble
        assert should_scrobble(play_seconds=240, track_duration=0) is True

    def test_unknown_duration_with_short_play_time(self) -> None:
        # Duration 0 (unknown), played 60s, < 240s, 50% of 0 is 0 => no
        assert should_scrobble(play_seconds=60, track_duration=0) is False

    def test_negative_duration_treated_as_unknown(self) -> None:
        assert should_scrobble(play_seconds=60, track_duration=-1) is False
        assert should_scrobble(play_seconds=240, track_duration=-1) is True


# ===================================================================
# API Signature Generation
# ===================================================================


class TestApiSignature:
    """Test the ``_make_api_sig`` function."""

    def test_basic_signature(self) -> None:
        params = {"method": "auth.getSession", "api_key": "KEY", "token": "T"}
        sig = _make_api_sig(params, "SECRET")

        # Manually compute expected
        ordered = "api_keyKEYmethodauth.getSessiontokenT"
        expected = hashlib.md5(
            (ordered + "SECRET").encode("utf-8")
        ).hexdigest()
        assert sig == expected

    def test_signature_is_deterministic(self) -> None:
        params = {"a": "1", "b": "2"}
        assert _make_api_sig(params, "s") == _make_api_sig(params, "s")

    def test_signature_differs_with_different_secret(self) -> None:
        params = {"a": "1"}
        assert _make_api_sig(params, "s1") != _make_api_sig(params, "s2")

    def test_params_are_sorted(self) -> None:
        params_a = {"z": "1", "a": "2"}
        params_b = {"a": "2", "z": "1"}
        assert _make_api_sig(params_a, "s") == _make_api_sig(params_b, "s")


# ===================================================================
# Now Playing
# ===================================================================


class TestUpdateNowPlaying:
    """Test the update_now_playing method."""

    def test_does_not_crash_when_not_authenticated(self) -> None:
        svc = LastFmService()
        svc.set_enabled(True)
        # Should not raise
        svc.update_now_playing("Song", "Artist")

    def test_does_not_crash_when_disabled(self) -> None:
        db = _make_db(lastfm_session_key="sk")
        svc = LastFmService(db=db)
        svc.set_enabled(False)
        # Should not raise
        svc.update_now_playing("Song", "Artist")

    @patch("auxen.lastfm.LastFmService._api_post")
    def test_sends_now_playing_when_enabled_and_authenticated(
        self, mock_post
    ) -> None:
        mock_post.return_value = {"nowplaying": {}}
        db = _make_db(lastfm_session_key="sk123")
        svc = LastFmService(db=db, api_key="k", api_secret="s")
        svc.set_enabled(True)
        svc.update_now_playing(
            "Test Song", "Test Artist", album="Test Album", duration=180
        )
        # Wait for background thread
        import threading

        for t in threading.enumerate():
            if t.daemon and t.is_alive() and t != threading.main_thread():
                t.join(timeout=2)

        mock_post.assert_called_once()
        call_params = mock_post.call_args[0][0]
        assert call_params["method"] == "track.updateNowPlaying"
        assert call_params["track"] == "Test Song"
        assert call_params["artist"] == "Test Artist"
        assert call_params["album"] == "Test Album"
        assert call_params["duration"] == "180"


# ===================================================================
# Scrobble
# ===================================================================


class TestScrobble:
    """Test the scrobble method."""

    def test_does_not_crash_when_not_authenticated(self) -> None:
        svc = LastFmService()
        svc.set_enabled(True)
        # Should not raise
        svc.scrobble("Song", "Artist")

    def test_does_not_crash_when_disabled(self) -> None:
        db = _make_db(lastfm_session_key="sk")
        svc = LastFmService(db=db)
        svc.set_enabled(False)
        # Should not raise
        svc.scrobble("Song", "Artist")

    @patch("auxen.lastfm.LastFmService._api_post")
    def test_sends_scrobble_when_enabled_and_authenticated(
        self, mock_post
    ) -> None:
        mock_post.return_value = {"scrobbles": {}}
        db = _make_db(lastfm_session_key="sk123")
        svc = LastFmService(db=db, api_key="k", api_secret="s")
        svc.set_enabled(True)

        ts = 1700000000
        svc.scrobble(
            "Track", "Band", album="Album", duration=200, timestamp=ts
        )
        # Wait for background thread
        import threading

        for t in threading.enumerate():
            if t.daemon and t.is_alive() and t != threading.main_thread():
                t.join(timeout=2)

        mock_post.assert_called_once()
        call_params = mock_post.call_args[0][0]
        assert call_params["method"] == "track.scrobble"
        assert call_params["track"] == "Track"
        assert call_params["artist"] == "Band"
        assert call_params["timestamp"] == str(ts)


# ===================================================================
# Enabled Toggle
# ===================================================================


class TestEnabledToggle:
    """Test the enabled property and set_enabled method."""

    def test_disabled_by_default(self) -> None:
        svc = LastFmService()
        assert svc.enabled is False

    def test_set_enabled_true(self) -> None:
        svc = LastFmService()
        svc.set_enabled(True)
        assert svc.enabled is True

    def test_set_enabled_false(self) -> None:
        svc = LastFmService()
        svc.set_enabled(True)
        svc.set_enabled(False)
        assert svc.enabled is False

    def test_set_enabled_persists_to_db(self) -> None:
        db = _make_db()
        svc = LastFmService(db=db)
        svc.set_enabled(True)
        db.set_setting.assert_any_call("lastfm_enabled", "1")
        svc.set_enabled(False)
        db.set_setting.assert_any_call("lastfm_enabled", "0")

    def test_enabled_restored_from_db(self) -> None:
        db = _make_db(lastfm_enabled="1")
        svc = LastFmService(db=db)
        assert svc.enabled is True

    def test_set_enabled_coerces_to_bool(self) -> None:
        svc = LastFmService()
        svc.set_enabled(0)
        assert svc.enabled is False
        svc.set_enabled(1)
        assert svc.enabled is True


# ===================================================================
# API Key Configuration
# ===================================================================


class TestApiKeyConfig:
    """Test API key loading from constructor and database."""

    def test_default_api_key(self) -> None:
        svc = LastFmService()
        assert svc._api_key == _DEFAULT_API_KEY

    def test_custom_api_key_from_constructor(self) -> None:
        svc = LastFmService(api_key="custom_key")
        assert svc._api_key == "custom_key"

    def test_api_key_from_database(self) -> None:
        db = _make_db(lastfm_api_key="db_key")
        svc = LastFmService(db=db)
        assert svc._api_key == "db_key"

    def test_constructor_key_overrides_db(self) -> None:
        db = _make_db(lastfm_api_key="db_key")
        svc = LastFmService(db=db, api_key="ctor_key")
        assert svc._api_key == "ctor_key"


# ===================================================================
# Error Handling
# ===================================================================


class TestErrorHandling:
    """Service should not crash on API errors."""

    @patch("auxen.lastfm.LastFmService._api_post")
    def test_now_playing_api_error_is_logged(self, mock_post) -> None:
        mock_post.side_effect = Exception("timeout")
        db = _make_db(lastfm_session_key="sk")
        svc = LastFmService(db=db, api_key="k", api_secret="s")
        svc.set_enabled(True)
        # Should not raise
        svc.update_now_playing("Song", "Artist")
        import threading

        for t in threading.enumerate():
            if t.daemon and t.is_alive() and t != threading.main_thread():
                t.join(timeout=2)

    @patch("auxen.lastfm.LastFmService._api_post")
    def test_scrobble_api_error_is_logged(self, mock_post) -> None:
        mock_post.side_effect = Exception("500 internal")
        db = _make_db(lastfm_session_key="sk")
        svc = LastFmService(db=db, api_key="k", api_secret="s")
        svc.set_enabled(True)
        # Should not raise
        svc.scrobble("Song", "Artist")
        import threading

        for t in threading.enumerate():
            if t.daemon and t.is_alive() and t != threading.main_thread():
                t.join(timeout=2)
