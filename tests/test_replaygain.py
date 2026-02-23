"""Tests for ReplayGain volume normalization in the Auxen player."""

import os
import tempfile
from unittest.mock import MagicMock, patch

import pytest

from auxen.db import Database


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def db():
    """Create a temporary database, yield it, then clean up."""
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    database = Database(db_path=path)
    yield database
    database.close()
    if os.path.exists(path):
        os.unlink(path)


def _make_mock_player():
    """Create a Player-like object with mocked GStreamer internals.

    We patch ``Gst.init``, ``Gst.ElementFactory.make``, and GObject so
    that the Player class can be instantiated without a running GStreamer
    daemon.
    """
    mock_gst = MagicMock()
    mock_gst.init.return_value = None

    # Prepare mock elements
    mock_pipeline = MagicMock(name="playbin3")
    mock_rgvolume = MagicMock(name="rgvolume")
    mock_rglimiter = MagicMock(name="rglimiter")
    mock_eq = MagicMock(name="equalizer-10bands")
    mock_audiosink = MagicMock(name="autoaudiosink")
    mock_audio_bin = MagicMock(name="audio-bin")

    # Ghost pad
    mock_pad = MagicMock(name="sink-pad")
    mock_ghost = MagicMock(name="ghost-pad")
    mock_rgvolume.get_static_pad.return_value = mock_pad

    def factory_make(element_name, instance_name):
        mapping = {
            "playbin3": mock_pipeline,
            "rgvolume": mock_rgvolume,
            "rglimiter": mock_rglimiter,
            "equalizer-10bands": mock_eq,
            "autoaudiosink": mock_audiosink,
        }
        return mapping.get(element_name)

    mock_gst.ElementFactory.make.side_effect = factory_make
    mock_gst.Bin.new.return_value = mock_audio_bin
    mock_gst.GhostPad.new.return_value = mock_ghost

    # Mock bus
    mock_bus = MagicMock()
    mock_pipeline.get_bus.return_value = mock_bus

    return {
        "gst": mock_gst,
        "pipeline": mock_pipeline,
        "rgvolume": mock_rgvolume,
        "rglimiter": mock_rglimiter,
        "eq": mock_eq,
        "audio_bin": mock_audio_bin,
    }


@pytest.fixture
def player_with_mocks():
    """Instantiate a real Player with fully mocked GStreamer."""
    mocks = _make_mock_player()

    with (
        patch("auxen.player.Gst", mocks["gst"]),
        patch.object(
            # Prevent GObject.__init__ from trying GObject introspection
            __import__("auxen.player", fromlist=["Player"]).Player,
            "__init__",
            lambda self: None,
        ),
    ):
        from auxen.player import Player

        player = Player.__new__(Player)
        # Manually initialise the attributes the constructor would set
        player._pipeline = mocks["pipeline"]
        player._rgvolume_element = mocks["rgvolume"]
        player._rglimiter_element = mocks["rglimiter"]
        player._equalizer_element = mocks["eq"]
        player._replaygain_enabled = True
        player._replaygain_mode = "album"
        # Other Player attributes that may be accessed
        player._state = "stopped"
        player._uri_resolver = None
        player._position_poll_id = None

        from auxen.queue import PlayQueue

        player.queue = PlayQueue()

    return player, mocks


@pytest.fixture
def player_without_rg():
    """Player where rgvolume/rglimiter were not available."""
    mocks = _make_mock_player()
    # Simulate missing rgvolume/rglimiter
    mocks["rgvolume"] = None
    mocks["rglimiter"] = None

    from auxen.player import Player

    player = Player.__new__(Player)
    player._pipeline = mocks["pipeline"]
    player._rgvolume_element = None
    player._rglimiter_element = None
    player._equalizer_element = mocks["eq"]
    player._replaygain_enabled = True
    player._replaygain_mode = "album"
    player._state = "stopped"
    player._uri_resolver = None
    player._position_poll_id = None

    from auxen.queue import PlayQueue

    player.queue = PlayQueue()

    return player, mocks


# ---------------------------------------------------------------------------
# Tests: default state
# ---------------------------------------------------------------------------


class TestReplayGainDefaults:
    """Verify initial default values."""

    def test_default_enabled(self, player_with_mocks) -> None:
        player, _ = player_with_mocks
        assert player.replaygain_enabled is True

    def test_default_mode(self, player_with_mocks) -> None:
        player, _ = player_with_mocks
        assert player.replaygain_mode == "album"


# ---------------------------------------------------------------------------
# Tests: set_replaygain_enabled
# ---------------------------------------------------------------------------


class TestSetReplayGainEnabled:
    """Test enabling/disabling ReplayGain."""

    def test_disable_sets_property(self, player_with_mocks) -> None:
        player, mocks = player_with_mocks
        player.set_replaygain_enabled(False)
        assert player.replaygain_enabled is False
        mocks["rgvolume"].set_property.assert_called_with(
            "pre-amp", -60.0
        )

    def test_enable_sets_property(self, player_with_mocks) -> None:
        player, mocks = player_with_mocks
        player.set_replaygain_enabled(False)
        mocks["rgvolume"].reset_mock()
        player.set_replaygain_enabled(True)
        assert player.replaygain_enabled is True
        mocks["rgvolume"].set_property.assert_called_with("pre-amp", 0.0)

    def test_toggle_roundtrip(self, player_with_mocks) -> None:
        player, _ = player_with_mocks
        player.set_replaygain_enabled(False)
        assert player.replaygain_enabled is False
        player.set_replaygain_enabled(True)
        assert player.replaygain_enabled is True

    def test_disable_without_element_no_error(
        self, player_without_rg
    ) -> None:
        """When rgvolume is not available, disabling should not raise."""
        player, _ = player_without_rg
        player.set_replaygain_enabled(False)
        assert player.replaygain_enabled is False

    def test_enable_without_element_no_error(
        self, player_without_rg
    ) -> None:
        """When rgvolume is not available, enabling should not raise."""
        player, _ = player_without_rg
        player.set_replaygain_enabled(True)
        assert player.replaygain_enabled is True


# ---------------------------------------------------------------------------
# Tests: set_replaygain_mode
# ---------------------------------------------------------------------------


class TestSetReplayGainMode:
    """Test album/track mode switching."""

    def test_set_album_mode(self, player_with_mocks) -> None:
        player, mocks = player_with_mocks
        player.set_replaygain_mode("album")
        assert player.replaygain_mode == "album"
        mocks["rgvolume"].set_property.assert_called_with(
            "album-mode", True
        )

    def test_set_track_mode(self, player_with_mocks) -> None:
        player, mocks = player_with_mocks
        player.set_replaygain_mode("track")
        assert player.replaygain_mode == "track"
        mocks["rgvolume"].set_property.assert_called_with(
            "album-mode", False
        )

    def test_invalid_mode_raises(self, player_with_mocks) -> None:
        player, _ = player_with_mocks
        with pytest.raises(ValueError, match="Invalid ReplayGain mode"):
            player.set_replaygain_mode("loudness")

    def test_mode_without_element_no_error(
        self, player_without_rg
    ) -> None:
        """When rgvolume is missing, mode change still updates state."""
        player, _ = player_without_rg
        player.set_replaygain_mode("track")
        assert player.replaygain_mode == "track"

    def test_mode_roundtrip(self, player_with_mocks) -> None:
        player, _ = player_with_mocks
        player.set_replaygain_mode("track")
        assert player.replaygain_mode == "track"
        player.set_replaygain_mode("album")
        assert player.replaygain_mode == "album"


# ---------------------------------------------------------------------------
# Tests: property access
# ---------------------------------------------------------------------------


class TestReplayGainProperties:
    """Property getter tests."""

    def test_replaygain_enabled_property_reflects_state(
        self, player_with_mocks
    ) -> None:
        player, _ = player_with_mocks
        assert player.replaygain_enabled is True
        player.set_replaygain_enabled(False)
        assert player.replaygain_enabled is False

    def test_replaygain_mode_property_reflects_state(
        self, player_with_mocks
    ) -> None:
        player, _ = player_with_mocks
        assert player.replaygain_mode == "album"
        player.set_replaygain_mode("track")
        assert player.replaygain_mode == "track"


# ---------------------------------------------------------------------------
# Tests: database persistence round-trip
# ---------------------------------------------------------------------------


class TestReplayGainSettingsPersistence:
    """Settings persistence through the database."""

    def test_save_and_load_enabled(self, db: Database) -> None:
        db.set_setting("replaygain_enabled", "1")
        assert db.get_setting("replaygain_enabled") == "1"

    def test_save_and_load_disabled(self, db: Database) -> None:
        db.set_setting("replaygain_enabled", "0")
        assert db.get_setting("replaygain_enabled") == "0"

    def test_save_and_load_mode_album(self, db: Database) -> None:
        db.set_setting("replaygain_mode", "album")
        assert db.get_setting("replaygain_mode") == "album"

    def test_save_and_load_mode_track(self, db: Database) -> None:
        db.set_setting("replaygain_mode", "track")
        assert db.get_setting("replaygain_mode") == "track"

    def test_default_when_unset(self, db: Database) -> None:
        val = db.get_setting("replaygain_enabled", "1")
        assert val == "1"

    def test_default_mode_when_unset(self, db: Database) -> None:
        val = db.get_setting("replaygain_mode", "album")
        assert val == "album"

    def test_overwrite_setting(self, db: Database) -> None:
        db.set_setting("replaygain_enabled", "1")
        db.set_setting("replaygain_enabled", "0")
        assert db.get_setting("replaygain_enabled") == "0"

    def test_full_roundtrip(self, db: Database) -> None:
        """Save both settings, read them back, apply to a mock player."""
        db.set_setting("replaygain_enabled", "0")
        db.set_setting("replaygain_mode", "track")

        mocks = _make_mock_player()
        from auxen.player import Player

        player = Player.__new__(Player)
        player._pipeline = mocks["pipeline"]
        player._rgvolume_element = mocks["rgvolume"]
        player._rglimiter_element = mocks["rglimiter"]
        player._equalizer_element = mocks["eq"]
        player._replaygain_enabled = True
        player._replaygain_mode = "album"
        player._state = "stopped"
        player._uri_resolver = None
        player._position_poll_id = None

        from auxen.queue import PlayQueue

        player.queue = PlayQueue()

        # Apply stored settings (same logic as app.py startup)
        rg_enabled_raw = db.get_setting("replaygain_enabled", "1")
        rg_enabled = rg_enabled_raw != "0"
        player.set_replaygain_enabled(rg_enabled)

        rg_mode = db.get_setting("replaygain_mode", "album")
        if rg_mode in ("album", "track"):
            player.set_replaygain_mode(rg_mode)

        assert player.replaygain_enabled is False
        assert player.replaygain_mode == "track"
