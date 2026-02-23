"""Tests for auxen.sleep_timer — SleepTimer service, presets, and fade-out."""

from unittest.mock import MagicMock, patch

import pytest

from auxen.sleep_timer import (
    FADE_DURATION_SECONDS,
    FADE_STEP_INTERVAL_MS,
    PRESET_DURATIONS,
    SleepTimer,
)


# ---- Helpers ----


def _make_timer(**kwargs) -> SleepTimer:
    """Create a SleepTimer with GLib scheduling mocked out."""
    with patch("auxen.sleep_timer.SleepTimer._schedule_tick"), \
         patch("auxen.sleep_timer.SleepTimer._cancel_sources"):
        timer = SleepTimer(**kwargs)
    return timer


def _start_timer(timer: SleepTimer, minutes: int = 30) -> None:
    """Start the timer with GLib scheduling mocked."""
    with patch.object(timer, "_schedule_tick"), \
         patch.object(timer, "_cancel_sources"):
        timer.start(minutes)


def _cancel_timer(timer: SleepTimer) -> None:
    """Cancel the timer with GLib source removal mocked."""
    with patch.object(timer, "_cancel_sources"):
        timer.cancel()


# ═══════════════════════════════════════════════════════════
# Defaults / Initial State
# ═══════════════════════════════════════════════════════════


class TestSleepTimerDefaults:
    """Verify initial state."""

    def test_not_active_by_default(self) -> None:
        timer = _make_timer()
        assert timer.is_active is False

    def test_remaining_is_zero_when_inactive(self) -> None:
        timer = _make_timer()
        assert timer.get_remaining() == 0

    def test_fade_out_enabled_by_default(self) -> None:
        timer = _make_timer()
        assert timer.fade_out_enabled is True

    def test_end_of_track_false_by_default(self) -> None:
        timer = _make_timer()
        assert timer.end_of_track is False


# ═══════════════════════════════════════════════════════════
# Start / Cancel Lifecycle
# ═══════════════════════════════════════════════════════════


class TestStartCancel:
    """Start and cancel lifecycle."""

    def test_start_activates_timer(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 15)
        assert timer.is_active is True

    def test_start_sets_remaining(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 30)
        assert timer.get_remaining() == 30 * 60

    def test_start_sets_remaining_correctly(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 45)
        assert timer.get_remaining() == 45 * 60

    def test_cancel_deactivates_timer(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 15)
        _cancel_timer(timer)
        assert timer.is_active is False

    def test_cancel_resets_remaining(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 15)
        _cancel_timer(timer)
        assert timer.get_remaining() == 0

    def test_cancel_when_inactive_is_noop(self) -> None:
        timer = _make_timer()
        _cancel_timer(timer)
        assert timer.is_active is False
        assert timer.get_remaining() == 0

    def test_restart_resets_remaining(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 15)
        _start_timer(timer, 60)
        assert timer.get_remaining() == 60 * 60

    def test_start_invalid_duration_raises(self) -> None:
        timer = _make_timer()
        with pytest.raises(ValueError):
            _start_timer(timer, 0)

    def test_start_negative_duration_raises(self) -> None:
        timer = _make_timer()
        with pytest.raises(ValueError):
            _start_timer(timer, -5)


# ═══════════════════════════════════════════════════════════
# is_active property
# ═══════════════════════════════════════════════════════════


class TestIsActive:
    """is_active in various states."""

    def test_active_after_start(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 15)
        assert timer.is_active is True

    def test_inactive_after_cancel(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 15)
        _cancel_timer(timer)
        assert timer.is_active is False

    def test_active_after_end_of_track(self) -> None:
        timer = _make_timer()
        with patch.object(timer, "_cancel_sources"):
            timer.start_end_of_track()
        assert timer.is_active is True


# ═══════════════════════════════════════════════════════════
# get_remaining
# ═══════════════════════════════════════════════════════════


class TestGetRemaining:
    """get_remaining returns correct values."""

    def test_returns_zero_when_not_active(self) -> None:
        timer = _make_timer()
        assert timer.get_remaining() == 0

    def test_returns_seconds_when_active(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 90)
        assert timer.get_remaining() == 90 * 60

    def test_returns_zero_after_cancel(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 60)
        _cancel_timer(timer)
        assert timer.get_remaining() == 0


# ═══════════════════════════════════════════════════════════
# Fade-Out Configuration
# ═══════════════════════════════════════════════════════════


class TestFadeOut:
    """Fade-out toggle and parameters."""

    def test_fade_out_default_enabled(self) -> None:
        timer = _make_timer()
        assert timer.fade_out_enabled is True

    def test_disable_fade_out(self) -> None:
        timer = _make_timer()
        timer.fade_out_enabled = False
        assert timer.fade_out_enabled is False

    def test_enable_fade_out(self) -> None:
        timer = _make_timer()
        timer.fade_out_enabled = False
        timer.fade_out_enabled = True
        assert timer.fade_out_enabled is True

    def test_fade_duration_constant(self) -> None:
        assert FADE_DURATION_SECONDS == 30

    def test_fade_step_interval_constant(self) -> None:
        assert FADE_STEP_INTERVAL_MS == 500


# ═══════════════════════════════════════════════════════════
# Callbacks
# ═══════════════════════════════════════════════════════════


class TestCallbacks:
    """on_expire, on_tick, and on_fade_step callbacks."""

    def test_on_expire_is_stored(self) -> None:
        callback = MagicMock()
        timer = _make_timer(on_expire=callback)
        assert timer._on_expire is callback

    def test_on_tick_is_stored(self) -> None:
        callback = MagicMock()
        timer = _make_timer(on_tick=callback)
        assert timer._on_tick is callback

    def test_on_fade_step_is_stored(self) -> None:
        callback = MagicMock()
        timer = _make_timer(on_fade_step=callback)
        assert timer._on_fade_step is callback

    def test_on_expire_called_at_zero(self) -> None:
        callback = MagicMock()
        timer = _make_timer(on_expire=callback)
        _start_timer(timer, 1)
        # Simulate ticking down to zero.
        timer._remaining_seconds = 1
        with patch.object(timer, "_cancel_sources"):
            timer._on_tick_internal()
        callback.assert_called_once()

    def test_on_tick_called_each_second(self) -> None:
        tick_cb = MagicMock()
        timer = _make_timer(on_tick=tick_cb)
        _start_timer(timer, 1)
        timer._remaining_seconds = 5
        with patch.object(timer, "_schedule_fade"):
            timer._on_tick_internal()
        tick_cb.assert_called_once_with(4)

    def test_on_fade_step_called_during_fade(self) -> None:
        fade_cb = MagicMock()
        timer = _make_timer(on_fade_step=fade_cb)
        _start_timer(timer, 1)
        timer._remaining_seconds = 10
        timer._fading = True
        timer._on_fade_step_internal()
        fade_cb.assert_called_once()
        # Fraction should be approximately 10/30
        args = fade_cb.call_args[0]
        assert 0.3 <= args[0] <= 0.35

    def test_no_callback_when_none(self) -> None:
        timer = _make_timer(
            on_expire=None, on_tick=None, on_fade_step=None
        )
        _start_timer(timer, 1)
        timer._remaining_seconds = 1
        # Should not raise.
        with patch.object(timer, "_cancel_sources"):
            timer._on_tick_internal()

    def test_cancel_restores_volume_if_fading(self) -> None:
        fade_cb = MagicMock()
        timer = _make_timer(on_fade_step=fade_cb)
        _start_timer(timer, 1)
        timer._fading = True
        _cancel_timer(timer)
        fade_cb.assert_called_with(1.0)


# ═══════════════════════════════════════════════════════════
# Preset Durations
# ═══════════════════════════════════════════════════════════


class TestPresetDurations:
    """Preset duration list."""

    def test_preset_list_length(self) -> None:
        assert len(PRESET_DURATIONS) == 6

    def test_preset_values(self) -> None:
        assert PRESET_DURATIONS == [15, 30, 45, 60, 90, 120]

    def test_get_preset_durations_returns_copy(self) -> None:
        durations = SleepTimer.get_preset_durations()
        durations.append(999)
        assert 999 not in PRESET_DURATIONS

    def test_get_preset_durations_matches_constant(self) -> None:
        assert SleepTimer.get_preset_durations() == PRESET_DURATIONS


# ═══════════════════════════════════════════════════════════
# format_remaining
# ═══════════════════════════════════════════════════════════


class TestFormatRemaining:
    """format_remaining helper."""

    def test_format_zero(self) -> None:
        assert SleepTimer.format_remaining(0) == "00:00"

    def test_format_one_minute(self) -> None:
        assert SleepTimer.format_remaining(60) == "01:00"

    def test_format_90_seconds(self) -> None:
        assert SleepTimer.format_remaining(90) == "01:30"

    def test_format_two_hours(self) -> None:
        assert SleepTimer.format_remaining(7200) == "120:00"

    def test_format_59_seconds(self) -> None:
        assert SleepTimer.format_remaining(59) == "00:59"


# ═══════════════════════════════════════════════════════════
# End of Track Mode
# ═══════════════════════════════════════════════════════════


class TestEndOfTrack:
    """End-of-current-track mode."""

    def test_start_end_of_track_activates(self) -> None:
        timer = _make_timer()
        with patch.object(timer, "_cancel_sources"):
            timer.start_end_of_track()
        assert timer.is_active is True
        assert timer.end_of_track is True

    def test_end_of_track_remaining_is_zero(self) -> None:
        timer = _make_timer()
        with patch.object(timer, "_cancel_sources"):
            timer.start_end_of_track()
        # In end-of-track mode, remaining is 0 (no countdown).
        assert timer.get_remaining() == 0

    def test_cancel_clears_end_of_track(self) -> None:
        timer = _make_timer()
        with patch.object(timer, "_cancel_sources"):
            timer.start_end_of_track()
        _cancel_timer(timer)
        assert timer.end_of_track is False


# ═══════════════════════════════════════════════════════════
# Internal Tick Logic
# ═══════════════════════════════════════════════════════════


class TestTickLogic:
    """Internal tick decrement logic."""

    def test_tick_decrements_remaining(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 5)
        initial = timer.get_remaining()
        with patch.object(timer, "_schedule_fade"):
            timer._on_tick_internal()
        assert timer.get_remaining() == initial - 1

    def test_tick_returns_true_while_running(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 5)
        with patch.object(timer, "_schedule_fade"):
            result = timer._on_tick_internal()
        assert result is True

    def test_tick_returns_false_at_expiry(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 1)
        timer._remaining_seconds = 1
        with patch.object(timer, "_cancel_sources"):
            result = timer._on_tick_internal()
        assert result is False

    def test_tick_starts_fade_in_last_30_seconds(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 1)
        timer._remaining_seconds = FADE_DURATION_SECONDS + 1
        with patch.object(timer, "_schedule_fade") as mock_fade:
            timer._on_tick_internal()
        # After tick, remaining is exactly FADE_DURATION_SECONDS,
        # which should trigger fade.
        mock_fade.assert_called_once()

    def test_tick_does_not_start_fade_when_disabled(self) -> None:
        timer = _make_timer()
        timer.fade_out_enabled = False
        _start_timer(timer, 1)
        timer._remaining_seconds = FADE_DURATION_SECONDS + 1
        with patch.object(timer, "_schedule_fade") as mock_fade:
            timer._on_tick_internal()
        mock_fade.assert_not_called()

    def test_tick_does_not_restart_fade(self) -> None:
        timer = _make_timer()
        _start_timer(timer, 1)
        timer._remaining_seconds = 20
        timer._fading = True  # already fading
        with patch.object(timer, "_schedule_fade") as mock_fade:
            timer._on_tick_internal()
        mock_fade.assert_not_called()
