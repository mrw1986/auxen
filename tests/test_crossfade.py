"""Tests for auxen.crossfade -- CrossfadeService settings and fade logic."""

from unittest.mock import MagicMock, patch

import pytest

from auxen.crossfade import (
    DEFAULT_DURATION,
    FADE_STEP_INTERVAL_MS,
    MAX_DURATION,
    MIN_DURATION,
    CrossfadeService,
)


# ---- Helpers ----


def _make_service() -> CrossfadeService:
    """Create a CrossfadeService with GLib scheduling mocked out."""
    return CrossfadeService()


def _make_player(volume: float = 0.7) -> MagicMock:
    """Create a mock player object with a volume property."""
    player = MagicMock()
    player.volume = volume
    return player


# =====================================================================
# Defaults / Initial State
# =====================================================================


class TestDefaults:
    """Verify initial state."""

    def test_disabled_by_default(self) -> None:
        svc = _make_service()
        assert svc.enabled is False

    def test_default_duration(self) -> None:
        svc = _make_service()
        assert svc.duration == DEFAULT_DURATION

    def test_default_duration_is_5(self) -> None:
        svc = _make_service()
        assert svc.duration == 5.0

    def test_not_fading_by_default(self) -> None:
        svc = _make_service()
        assert svc.is_fading is False

    def test_fade_direction_none_by_default(self) -> None:
        svc = _make_service()
        assert svc.fade_direction is None

    def test_constants(self) -> None:
        assert FADE_STEP_INTERVAL_MS == 50
        assert MIN_DURATION == 1.0
        assert MAX_DURATION == 12.0
        assert DEFAULT_DURATION == 5.0


# =====================================================================
# Enabled Toggle
# =====================================================================


class TestEnabledToggle:
    """set_enabled / enabled property."""

    def test_enable(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        assert svc.enabled is True

    def test_disable(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        svc.set_enabled(False)
        assert svc.enabled is False

    def test_disable_cancels_active_fade(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        svc._fading = True
        svc._fade_direction = "out"
        svc.set_enabled(False)
        assert svc.is_fading is False
        assert svc.fade_direction is None

    def test_enable_with_truthy_value(self) -> None:
        svc = _make_service()
        svc.set_enabled(1)
        assert svc.enabled is True

    def test_disable_with_falsy_value(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        svc.set_enabled(0)
        assert svc.enabled is False


# =====================================================================
# Duration Clamping
# =====================================================================


class TestDuration:
    """set_duration clamping behaviour."""

    def test_set_normal_duration(self) -> None:
        svc = _make_service()
        svc.set_duration(7.0)
        assert svc.duration == 7.0

    def test_clamp_below_minimum(self) -> None:
        svc = _make_service()
        svc.set_duration(0.5)
        assert svc.duration == MIN_DURATION

    def test_clamp_above_maximum(self) -> None:
        svc = _make_service()
        svc.set_duration(20.0)
        assert svc.duration == MAX_DURATION

    def test_clamp_negative(self) -> None:
        svc = _make_service()
        svc.set_duration(-5.0)
        assert svc.duration == MIN_DURATION

    def test_minimum_boundary(self) -> None:
        svc = _make_service()
        svc.set_duration(1.0)
        assert svc.duration == 1.0

    def test_maximum_boundary(self) -> None:
        svc = _make_service()
        svc.set_duration(12.0)
        assert svc.duration == 12.0

    def test_fractional_duration(self) -> None:
        svc = _make_service()
        svc.set_duration(3.5)
        assert svc.duration == 3.5


# =====================================================================
# to_dict / from_dict Persistence
# =====================================================================


class TestPersistence:
    """to_dict and from_dict round-trip."""

    def test_to_dict_default(self) -> None:
        svc = _make_service()
        d = svc.to_dict()
        assert d == {"enabled": False, "duration": 5.0}

    def test_to_dict_custom(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        svc.set_duration(8.0)
        d = svc.to_dict()
        assert d == {"enabled": True, "duration": 8.0}

    def test_from_dict_restores_settings(self) -> None:
        svc = _make_service()
        svc.from_dict({"enabled": True, "duration": 10.0})
        assert svc.enabled is True
        assert svc.duration == 10.0

    def test_round_trip(self) -> None:
        svc1 = _make_service()
        svc1.set_enabled(True)
        svc1.set_duration(6.5)
        data = svc1.to_dict()

        svc2 = _make_service()
        svc2.from_dict(data)
        assert svc2.enabled == svc1.enabled
        assert svc2.duration == svc1.duration

    def test_from_dict_ignores_unknown_keys(self) -> None:
        svc = _make_service()
        svc.from_dict({"enabled": True, "duration": 3.0, "unknown_key": "value"})
        assert svc.enabled is True
        assert svc.duration == 3.0

    def test_from_dict_partial_enabled_only(self) -> None:
        svc = _make_service()
        svc.from_dict({"enabled": True})
        assert svc.enabled is True
        assert svc.duration == DEFAULT_DURATION  # unchanged

    def test_from_dict_partial_duration_only(self) -> None:
        svc = _make_service()
        svc.from_dict({"duration": 9.0})
        assert svc.enabled is False  # unchanged
        assert svc.duration == 9.0

    def test_from_dict_empty(self) -> None:
        svc = _make_service()
        svc.from_dict({})
        assert svc.enabled is False
        assert svc.duration == DEFAULT_DURATION

    def test_from_dict_clamps_duration(self) -> None:
        svc = _make_service()
        svc.from_dict({"duration": 99.0})
        assert svc.duration == MAX_DURATION


# =====================================================================
# Cancel
# =====================================================================


class TestCancel:
    """cancel() resets all fade state."""

    def test_cancel_resets_fading(self) -> None:
        svc = _make_service()
        svc._fading = True
        svc._fade_direction = "out"
        svc.cancel()
        assert svc.is_fading is False

    def test_cancel_resets_direction(self) -> None:
        svc = _make_service()
        svc._fade_direction = "in"
        svc.cancel()
        assert svc.fade_direction is None

    def test_cancel_resets_step_count(self) -> None:
        svc = _make_service()
        svc._fade_step_count = 50
        svc.cancel()
        assert svc._fade_step_count == 0

    def test_cancel_resets_total_steps(self) -> None:
        svc = _make_service()
        svc._fade_total_steps = 100
        svc.cancel()
        assert svc._fade_total_steps == 0

    def test_cancel_resets_callback(self) -> None:
        svc = _make_service()
        svc._fade_callback = lambda: None
        svc.cancel()
        assert svc._fade_callback is None

    def test_cancel_resets_player(self) -> None:
        svc = _make_service()
        svc._fade_player = MagicMock()
        svc.cancel()
        assert svc._fade_player is None

    def test_cancel_when_not_fading_is_noop(self) -> None:
        svc = _make_service()
        svc.cancel()  # should not raise
        assert svc.is_fading is False


# =====================================================================
# Fade-Out
# =====================================================================


class TestFadeOut:
    """start_fade_out behaviour."""

    def test_fade_out_does_nothing_when_disabled(self) -> None:
        svc = _make_service()
        svc.set_enabled(False)
        player = _make_player()
        svc.start_fade_out(player)
        assert svc.is_fading is False

    def test_fade_out_sets_state_when_enabled(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        assert svc.is_fading is True
        assert svc.fade_direction == "out"

    def test_fade_out_stores_start_volume(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.5)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        assert svc._fade_start_volume == 0.5

    def test_fade_out_target_is_zero(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.8)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        assert svc._fade_target_volume == 0.0

    def test_fade_out_calculates_steps(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        svc.set_duration(5.0)
        player = _make_player()
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        expected_steps = int(5.0 * 1000 / FADE_STEP_INTERVAL_MS)
        assert svc._fade_total_steps == expected_steps

    def test_fade_out_with_callback(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player()
        callback = MagicMock()
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player, callback_on_complete=callback)
        assert svc._fade_callback is callback


# =====================================================================
# Fade-In
# =====================================================================


class TestFadeIn:
    """start_fade_in behaviour."""

    def test_fade_in_does_nothing_when_disabled(self) -> None:
        svc = _make_service()
        svc.set_enabled(False)
        player = _make_player()
        svc.start_fade_in(player, 0.7)
        assert svc.is_fading is False

    def test_fade_in_sets_state_when_enabled(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player()
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_in(player, 0.7)
        assert svc.is_fading is True
        assert svc.fade_direction == "in"

    def test_fade_in_starts_at_zero(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_in(player, 0.7)
        assert svc._fade_start_volume == 0.0
        # Player volume should be set to 0 immediately.
        assert player.volume == 0.0

    def test_fade_in_target_volume(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player()
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_in(player, 0.85)
        assert svc._fade_target_volume == 0.85

    def test_fade_in_clamps_target_volume(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player()
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_in(player, 1.5)
        assert svc._fade_target_volume == 1.0


# =====================================================================
# Fade Step Logic
# =====================================================================


class TestFadeStep:
    """Internal _on_fade_step logic."""

    def test_step_increments_count(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        svc._on_fade_step()
        assert svc._fade_step_count == 1

    def test_step_returns_true_while_fading(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        result = svc._on_fade_step()
        assert result is True

    def test_step_returns_false_when_not_fading(self) -> None:
        svc = _make_service()
        result = svc._on_fade_step()
        assert result is False

    def test_step_completes_at_total_steps(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        # Simulate reaching the last step.
        svc._fade_step_count = svc._fade_total_steps - 1
        result = svc._on_fade_step()
        assert result is False
        assert svc.is_fading is False

    def test_fade_out_step_decreases_volume(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(1.0)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        svc._on_fade_step()
        # After one step, volume should be less than 1.0.
        assert player.volume < 1.0

    def test_fade_in_step_increases_volume(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.0)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_in(player, 1.0)
        svc._on_fade_step()
        # After one step, volume should be greater than 0.
        assert player.volume > 0.0

    def test_completion_callback_invoked(self) -> None:
        callback = MagicMock()
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player, callback_on_complete=callback)
        # Jump to the last step.
        svc._fade_step_count = svc._fade_total_steps - 1
        svc._on_fade_step()
        callback.assert_called_once()

    def test_no_callback_on_incomplete_step(self) -> None:
        callback = MagicMock()
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.7)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player, callback_on_complete=callback)
        svc._on_fade_step()
        callback.assert_not_called()

    def test_volume_reaches_zero_on_fade_out_complete(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(1.0)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_out(player)
        # Jump to the last step.
        svc._fade_step_count = svc._fade_total_steps - 1
        svc._on_fade_step()
        assert player.volume == pytest.approx(0.0, abs=0.01)

    def test_volume_reaches_target_on_fade_in_complete(self) -> None:
        svc = _make_service()
        svc.set_enabled(True)
        player = _make_player(0.0)
        with patch.object(svc, "_schedule_fade"):
            svc.start_fade_in(player, 0.7)
        # Jump to the last step.
        svc._fade_step_count = svc._fade_total_steps - 1
        svc._on_fade_step()
        assert player.volume == pytest.approx(0.7, abs=0.01)
