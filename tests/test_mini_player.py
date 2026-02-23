"""Tests for auxen.views.mini_player — MiniPlayerWindow."""

from unittest.mock import MagicMock, patch

import pytest

from auxen.views.mini_player import MiniPlayerWindow, _format_time


# ---- Helpers ----


def _make_mini_player(**kwargs) -> MiniPlayerWindow:
    """Create a MiniPlayerWindow with GTK display mocked."""
    with patch.object(MiniPlayerWindow, "__init__", lambda self, **kw: None):
        mp = MiniPlayerWindow.__new__(MiniPlayerWindow)
    # Manually initialise the attributes that __init__ would set
    mp._on_play_pause = kwargs.get("on_play_pause")
    mp._on_next = kwargs.get("on_next")
    mp._on_close_request_cb = kwargs.get("on_close_request")
    mp._on_maximize_request = kwargs.get("on_maximize_request")
    mp._is_playing = False
    mp._drag_start_x = 0.0
    mp._drag_start_y = 0.0

    # Create mock widgets
    mp._title_label = MagicMock()
    mp._artist_label = MagicMock()
    mp._play_btn = MagicMock()
    mp._progress_scale = MagicMock()
    mp._art_image = MagicMock()
    mp._art_placeholder = MagicMock()
    mp._current_time_label = MagicMock()
    mp._total_time_label = MagicMock()
    return mp


# ═══════════════════════════════════════════════════════════
# _format_time helper
# ═══════════════════════════════════════════════════════════


class TestFormatTime:
    """Verify the _format_time helper function."""

    def test_format_zero(self) -> None:
        assert _format_time(0) == "0:00"

    def test_format_one_minute(self) -> None:
        assert _format_time(60) == "1:00"

    def test_format_90_seconds(self) -> None:
        assert _format_time(90) == "1:30"

    def test_format_negative_clamps_to_zero(self) -> None:
        assert _format_time(-10) == "0:00"

    def test_format_fractional_seconds(self) -> None:
        assert _format_time(65.7) == "1:05"


# ═══════════════════════════════════════════════════════════
# update_track
# ═══════════════════════════════════════════════════════════


class TestUpdateTrack:
    """Test update_track sets title and artist labels."""

    def test_update_track_sets_title(self) -> None:
        mp = _make_mini_player()
        mp.update_track("Test Song", "Test Artist")
        mp._title_label.set_label.assert_called_with("Test Song")

    def test_update_track_sets_artist(self) -> None:
        mp = _make_mini_player()
        mp.update_track("Test Song", "Test Artist")
        mp._artist_label.set_label.assert_called_with("Test Artist")

    def test_update_track_empty_strings(self) -> None:
        mp = _make_mini_player()
        mp.update_track("", "")
        mp._title_label.set_label.assert_called_with("")
        mp._artist_label.set_label.assert_called_with("")

    def test_update_track_unicode(self) -> None:
        mp = _make_mini_player()
        mp.update_track("Sch\u00f6ne M\u00fcsik", "K\u00fcnstler")
        mp._title_label.set_label.assert_called_with("Sch\u00f6ne M\u00fcsik")
        mp._artist_label.set_label.assert_called_with("K\u00fcnstler")


# ═══════════════════════════════════════════════════════════
# set_playing
# ═══════════════════════════════════════════════════════════


class TestSetPlaying:
    """Test set_playing changes the play button icon."""

    def test_set_playing_true(self) -> None:
        mp = _make_mini_player()
        mp.set_playing(True)
        mp._play_btn.set_icon_name.assert_called_with(
            "media-playback-pause-symbolic"
        )
        assert mp._is_playing is True

    def test_set_playing_false(self) -> None:
        mp = _make_mini_player()
        mp._is_playing = True
        mp.set_playing(False)
        mp._play_btn.set_icon_name.assert_called_with(
            "media-playback-start-symbolic"
        )
        assert mp._is_playing is False

    def test_set_playing_toggle(self) -> None:
        mp = _make_mini_player()
        mp.set_playing(True)
        assert mp._is_playing is True
        mp.set_playing(False)
        assert mp._is_playing is False
        mp.set_playing(True)
        assert mp._is_playing is True


# ═══════════════════════════════════════════════════════════
# update_position
# ═══════════════════════════════════════════════════════════


class TestUpdatePosition:
    """Test update_position updates progress and time labels."""

    def test_update_position_normal(self) -> None:
        mp = _make_mini_player()
        mp.update_position(30.0, 120.0)
        mp._current_time_label.set_label.assert_called_with("0:30")
        mp._total_time_label.set_label.assert_called_with("2:00")
        mp._progress_scale.set_value.assert_called_once()
        # 30/120 * 100 = 25%
        args = mp._progress_scale.set_value.call_args[0]
        assert abs(args[0] - 25.0) < 0.1

    def test_update_position_zero_duration(self) -> None:
        mp = _make_mini_player()
        mp.update_position(0.0, 0.0)
        mp._progress_scale.set_value.assert_called_with(0)

    def test_update_position_full(self) -> None:
        mp = _make_mini_player()
        mp.update_position(200.0, 200.0)
        args = mp._progress_scale.set_value.call_args[0]
        assert abs(args[0] - 100.0) < 0.1

    def test_update_position_beyond_duration(self) -> None:
        mp = _make_mini_player()
        mp.update_position(300.0, 200.0)
        args = mp._progress_scale.set_value.call_args[0]
        # Should be capped at 100
        assert args[0] == 100

    def test_update_position_negative_duration(self) -> None:
        mp = _make_mini_player()
        mp.update_position(10.0, -5.0)
        mp._progress_scale.set_value.assert_called_with(0)


# ═══════════════════════════════════════════════════════════
# set_album_art
# ═══════════════════════════════════════════════════════════


class TestSetAlbumArt:
    """Test set_album_art shows image or placeholder."""

    def test_set_album_art_with_pixbuf(self) -> None:
        mp = _make_mini_player()
        mock_pixbuf = MagicMock()
        mp.set_album_art(mock_pixbuf)
        mp._art_image.set_from_pixbuf.assert_called_with(mock_pixbuf)
        mp._art_image.set_visible.assert_called_with(True)
        mp._art_placeholder.set_visible.assert_called_with(False)

    def test_set_album_art_with_none(self) -> None:
        mp = _make_mini_player()
        mp.set_album_art(None)
        mp._art_image.set_visible.assert_called_with(False)
        mp._art_placeholder.set_visible.assert_called_with(True)

    def test_set_album_art_transition(self) -> None:
        """Setting art then clearing should toggle visibility."""
        mp = _make_mini_player()
        mock_pixbuf = MagicMock()
        mp.set_album_art(mock_pixbuf)
        mp.set_album_art(None)
        # Last call should hide the image
        mp._art_image.set_visible.assert_called_with(False)
        mp._art_placeholder.set_visible.assert_called_with(True)


# ═══════════════════════════════════════════════════════════
# Button callbacks
# ═══════════════════════════════════════════════════════════


class TestCallbacks:
    """Test that button clicks invoke the correct callbacks."""

    def test_play_pause_callback(self) -> None:
        cb = MagicMock()
        mp = _make_mini_player(on_play_pause=cb)
        mp._on_play_pause_clicked(MagicMock())
        cb.assert_called_once()

    def test_next_callback(self) -> None:
        cb = MagicMock()
        mp = _make_mini_player(on_next=cb)
        mp._on_next_clicked(MagicMock())
        cb.assert_called_once()

    def test_close_callback(self) -> None:
        cb = MagicMock()
        mp = _make_mini_player(on_close_request=cb)
        mp._on_close_clicked(MagicMock())
        cb.assert_called_once()

    def test_maximize_callback(self) -> None:
        cb = MagicMock()
        mp = _make_mini_player(on_maximize_request=cb)
        mp._on_maximize_clicked(MagicMock())
        cb.assert_called_once()

    def test_play_pause_no_callback_toggles(self) -> None:
        mp = _make_mini_player()
        assert mp._is_playing is False
        mp._on_play_pause_clicked(MagicMock())
        assert mp._is_playing is True

    def test_next_no_callback_no_crash(self) -> None:
        mp = _make_mini_player()
        # Should not raise
        mp._on_next_clicked(MagicMock())

    def test_close_no_callback_no_crash(self) -> None:
        mp = _make_mini_player()
        # Should not raise
        mp._on_close_clicked(MagicMock())


# ═══════════════════════════════════════════════════════════
# Properties
# ═══════════════════════════════════════════════════════════


class TestProperties:
    """Test that widget properties return the correct mock objects."""

    def test_title_label_property(self) -> None:
        mp = _make_mini_player()
        assert mp.title_label is mp._title_label

    def test_artist_label_property(self) -> None:
        mp = _make_mini_player()
        assert mp.artist_label is mp._artist_label

    def test_play_btn_property(self) -> None:
        mp = _make_mini_player()
        assert mp.play_btn is mp._play_btn

    def test_progress_scale_property(self) -> None:
        mp = _make_mini_player()
        assert mp.progress_scale is mp._progress_scale

    def test_art_image_property(self) -> None:
        mp = _make_mini_player()
        assert mp.art_image is mp._art_image


# ═══════════════════════════════════════════════════════════
# Window close request handler
# ═══════════════════════════════════════════════════════════


class TestWindowCloseRequest:
    """Test the GTK window close-request signal handler."""

    def test_close_request_with_callback_returns_true(self) -> None:
        cb = MagicMock()
        mp = _make_mini_player(on_close_request=cb)
        result = mp._on_window_close_request(MagicMock())
        assert result is True
        cb.assert_called_once()

    def test_close_request_without_callback_returns_false(self) -> None:
        mp = _make_mini_player()
        result = mp._on_window_close_request(MagicMock())
        assert result is False
