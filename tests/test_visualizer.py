"""Tests for auxen.views.visualizer -- SpectrumVisualizer widget."""

from unittest.mock import MagicMock, patch

import pytest

from auxen.views.visualizer import SpectrumVisualizer


# ---- Helpers ----


def _make_visualizer(**kwargs) -> SpectrumVisualizer:
    """Create a SpectrumVisualizer with GTK internals mocked."""
    with patch.object(
        SpectrumVisualizer, "__init__", lambda self, **kw: None
    ):
        viz = SpectrumVisualizer.__new__(SpectrumVisualizer)

    # Manually set attributes that __init__ would set
    bar_count = kwargs.get("bar_count", 16)
    viz._bar_count = bar_count
    viz._bar_width = kwargs.get("bar_width", 4)
    viz._bar_gap = kwargs.get("bar_gap", 2)
    viz._bar_color = kwargs.get("bar_color", "#d4a039")
    viz._max_height = kwargs.get("max_height", 32)
    viz._levels = [0.0] * bar_count
    viz._target_levels = [0.0] * bar_count
    viz._active = False
    viz._timer_id = None
    viz._ease_up = 0.35
    viz._ease_down = 0.15
    viz._r, viz._g, viz._b = SpectrumVisualizer._parse_hex_color(
        viz._bar_color
    )
    return viz


# ═══════════════════════════════════════════════════════════
# Initialization defaults
# ═══════════════════════════════════════════════════════════


class TestInitDefaults:
    """Verify default property values after construction."""

    def test_default_bar_count(self) -> None:
        viz = _make_visualizer()
        assert viz.bar_count == 16

    def test_default_bar_width(self) -> None:
        viz = _make_visualizer()
        assert viz.bar_width == 4

    def test_default_bar_gap(self) -> None:
        viz = _make_visualizer()
        assert viz.bar_gap == 2

    def test_default_bar_color(self) -> None:
        viz = _make_visualizer()
        assert viz.bar_color == "#d4a039"

    def test_default_max_height(self) -> None:
        viz = _make_visualizer()
        assert viz.max_height == 32

    def test_default_levels_are_zero(self) -> None:
        viz = _make_visualizer()
        assert all(level == 0.0 for level in viz.levels)

    def test_default_not_active(self) -> None:
        viz = _make_visualizer()
        assert viz.active is False

    def test_custom_bar_count(self) -> None:
        viz = _make_visualizer(bar_count=8)
        assert viz.bar_count == 8
        assert len(viz.levels) == 8


# ═══════════════════════════════════════════════════════════
# update_spectrum
# ═══════════════════════════════════════════════════════════


class TestUpdateSpectrum:
    """Test update_spectrum sets target levels correctly."""

    def test_valid_data(self) -> None:
        viz = _make_visualizer(bar_count=4)
        viz.update_spectrum([0.5, 0.8, 0.2, 1.0])
        assert viz._target_levels == [0.5, 0.8, 0.2, 1.0]

    def test_empty_list_zeroes_targets(self) -> None:
        viz = _make_visualizer(bar_count=4)
        viz.update_spectrum([0.5, 0.8, 0.2, 1.0])
        viz.update_spectrum([])
        assert viz._target_levels == [0.0, 0.0, 0.0, 0.0]

    def test_short_list_zeroes_remaining(self) -> None:
        viz = _make_visualizer(bar_count=4)
        viz.update_spectrum([0.5, 0.3])
        assert viz._target_levels == [0.5, 0.3, 0.0, 0.0]

    def test_long_list_truncates(self) -> None:
        viz = _make_visualizer(bar_count=3)
        viz.update_spectrum([0.1, 0.2, 0.3, 0.4, 0.5])
        assert viz._target_levels == [0.1, 0.2, 0.3]

    def test_clamps_values_above_one(self) -> None:
        viz = _make_visualizer(bar_count=2)
        viz.update_spectrum([1.5, 2.0])
        assert viz._target_levels == [1.0, 1.0]

    def test_clamps_values_below_zero(self) -> None:
        viz = _make_visualizer(bar_count=2)
        viz.update_spectrum([-0.5, -1.0])
        assert viz._target_levels == [0.0, 0.0]

    def test_mixed_clamping(self) -> None:
        viz = _make_visualizer(bar_count=4)
        viz.update_spectrum([-0.1, 0.5, 1.5, 0.0])
        assert viz._target_levels == [0.0, 0.5, 1.0, 0.0]


# ═══════════════════════════════════════════════════════════
# set_active
# ═══════════════════════════════════════════════════════════


class TestSetActive:
    """Test set_active toggles the active state."""

    def test_activate(self) -> None:
        viz = _make_visualizer()
        with patch.object(viz, "_start_timer"):
            viz.set_active(True)
        assert viz.active is True

    def test_deactivate_zeroes_targets(self) -> None:
        viz = _make_visualizer(bar_count=4)
        viz._target_levels = [0.5, 0.8, 0.2, 1.0]
        with patch.object(viz, "_start_timer"):
            viz.set_active(False)
        assert viz._target_levels == [0.0, 0.0, 0.0, 0.0]
        assert viz.active is False

    def test_toggle(self) -> None:
        viz = _make_visualizer()
        with patch.object(viz, "_start_timer"):
            viz.set_active(True)
            assert viz.active is True
            viz.set_active(False)
            assert viz.active is False


# ═══════════════════════════════════════════════════════════
# Tick / interpolation
# ═══════════════════════════════════════════════════════════


class TestTick:
    """Test the animation tick method."""

    def test_tick_moves_towards_target(self) -> None:
        viz = _make_visualizer(bar_count=2)
        viz._active = True
        viz._target_levels = [1.0, 0.0]
        viz._levels = [0.0, 1.0]

        # Mock queue_draw to avoid GTK calls
        viz.queue_draw = MagicMock()

        result = viz._tick()

        # Levels should have moved towards targets
        assert viz._levels[0] > 0.0  # moved up
        assert viz._levels[1] < 1.0  # moved down
        assert result is True  # timer should continue

    def test_tick_snaps_when_close(self) -> None:
        viz = _make_visualizer(bar_count=1)
        viz._active = True
        viz._target_levels = [0.5]
        viz._levels = [0.498]

        viz.queue_draw = MagicMock()

        viz._tick()

        # Should snap to target because diff < 0.005
        assert viz._levels[0] == 0.5

    def test_tick_stops_when_inactive_and_faded(self) -> None:
        viz = _make_visualizer(bar_count=2)
        viz._active = False
        viz._target_levels = [0.0, 0.0]
        viz._levels = [0.0, 0.0]

        viz.queue_draw = MagicMock()

        result = viz._tick()

        # Timer should stop
        assert result is False
        assert viz._timer_id is None

    def test_tick_continues_when_inactive_but_bars_visible(self) -> None:
        viz = _make_visualizer(bar_count=2)
        viz._active = False
        viz._target_levels = [0.0, 0.0]
        viz._levels = [0.5, 0.5]

        viz.queue_draw = MagicMock()

        result = viz._tick()

        # Should continue since bars are still visible
        assert result is True


# ═══════════════════════════════════════════════════════════
# Color parsing
# ═══════════════════════════════════════════════════════════


class TestParseHexColor:
    """Test the hex colour parsing helper."""

    def test_parse_valid_hex(self) -> None:
        r, g, b = SpectrumVisualizer._parse_hex_color("#ff0000")
        assert abs(r - 1.0) < 0.01
        assert abs(g - 0.0) < 0.01
        assert abs(b - 0.0) < 0.01

    def test_parse_without_hash(self) -> None:
        r, g, b = SpectrumVisualizer._parse_hex_color("00ff00")
        assert abs(r - 0.0) < 0.01
        assert abs(g - 1.0) < 0.01
        assert abs(b - 0.0) < 0.01

    def test_parse_invalid_falls_back(self) -> None:
        r, g, b = SpectrumVisualizer._parse_hex_color("#abc")
        # Falls back to amber
        assert abs(r - 0.83) < 0.02
        assert abs(g - 0.63) < 0.02

    def test_parse_amber_accent(self) -> None:
        r, g, b = SpectrumVisualizer._parse_hex_color("#d4a039")
        assert abs(r - 212 / 255) < 0.01
        assert abs(g - 160 / 255) < 0.01
        assert abs(b - 57 / 255) < 0.01


# ═══════════════════════════════════════════════════════════
# Levels property
# ═══════════════════════════════════════════════════════════


class TestLevelsProperty:
    """Test that the levels property returns a copy."""

    def test_levels_returns_copy(self) -> None:
        viz = _make_visualizer(bar_count=3)
        levels = viz.levels
        levels[0] = 999.0
        # Original should not be mutated
        assert viz._levels[0] == 0.0

    def test_levels_length_matches_bar_count(self) -> None:
        viz = _make_visualizer(bar_count=8)
        assert len(viz.levels) == 8
