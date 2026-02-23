"""Tests for auxen.equalizer — Equalizer service, presets, and persistence."""

import pytest

from auxen.equalizer import (
    DEFAULT_GAIN_DB,
    MAX_GAIN_DB,
    MIN_GAIN_DB,
    NUM_BANDS,
    PRESETS,
    Equalizer,
)


class TestEqualizerDefaults:
    """Verify initial / default state."""

    def test_default_bands_are_zero(self) -> None:
        eq = Equalizer()
        assert eq.get_bands() == [0.0] * NUM_BANDS

    def test_default_enabled(self) -> None:
        eq = Equalizer()
        assert eq.is_enabled() is True

    def test_get_bands_returns_copy(self) -> None:
        eq = Equalizer()
        bands = eq.get_bands()
        bands[0] = 99.0
        assert eq.get_bands()[0] == 0.0


class TestSetGetBands:
    """Individual and bulk band manipulation."""

    def test_set_band_and_get(self) -> None:
        eq = Equalizer()
        eq.set_band(0, 5.5)
        assert eq.get_bands()[0] == 5.5

    def test_set_band_all_indices(self) -> None:
        eq = Equalizer()
        for i in range(NUM_BANDS):
            eq.set_band(i, float(i))
        expected = [float(i) for i in range(NUM_BANDS)]
        assert eq.get_bands() == expected

    def test_set_band_negative_index_raises(self) -> None:
        eq = Equalizer()
        with pytest.raises(IndexError):
            eq.set_band(-1, 3.0)

    def test_set_band_out_of_range_raises(self) -> None:
        eq = Equalizer()
        with pytest.raises(IndexError):
            eq.set_band(10, 3.0)

    def test_set_bands_bulk(self) -> None:
        eq = Equalizer()
        values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
        eq.set_bands(values)
        assert eq.get_bands() == values

    def test_set_bands_wrong_length_raises(self) -> None:
        eq = Equalizer()
        with pytest.raises(ValueError):
            eq.set_bands([1.0, 2.0, 3.0])


class TestBandClamping:
    """Gain values must be clamped to [-12, +12] dB."""

    def test_clamp_above_max(self) -> None:
        eq = Equalizer()
        eq.set_band(0, 20.0)
        assert eq.get_bands()[0] == MAX_GAIN_DB

    def test_clamp_below_min(self) -> None:
        eq = Equalizer()
        eq.set_band(0, -20.0)
        assert eq.get_bands()[0] == MIN_GAIN_DB

    def test_exact_max_not_clamped(self) -> None:
        eq = Equalizer()
        eq.set_band(0, MAX_GAIN_DB)
        assert eq.get_bands()[0] == MAX_GAIN_DB

    def test_exact_min_not_clamped(self) -> None:
        eq = Equalizer()
        eq.set_band(0, MIN_GAIN_DB)
        assert eq.get_bands()[0] == MIN_GAIN_DB

    def test_clamp_via_set_bands(self) -> None:
        eq = Equalizer()
        values = [50.0] * NUM_BANDS
        eq.set_bands(values)
        assert all(v == MAX_GAIN_DB for v in eq.get_bands())


class TestPresets:
    """Preset application and listing."""

    def test_apply_flat_preset(self) -> None:
        eq = Equalizer()
        eq.set_band(0, 6.0)
        eq.apply_preset("Flat")
        assert eq.get_bands() == [0.0] * NUM_BANDS

    def test_apply_bass_boost(self) -> None:
        eq = Equalizer()
        eq.apply_preset("Bass Boost")
        assert eq.get_bands() == PRESETS["Bass Boost"]

    def test_apply_all_presets(self) -> None:
        eq = Equalizer()
        for name, expected in PRESETS.items():
            eq.apply_preset(name)
            assert eq.get_bands() == [float(v) for v in expected], (
                f"Preset {name!r} mismatch"
            )

    def test_unknown_preset_raises(self) -> None:
        eq = Equalizer()
        with pytest.raises(KeyError):
            eq.apply_preset("NoSuchPreset")

    def test_get_preset_names(self) -> None:
        eq = Equalizer()
        names = eq.get_preset_names()
        assert isinstance(names, list)
        assert len(names) == len(PRESETS)
        assert "Flat" in names
        assert "Bass Boost" in names
        assert "Rock" in names

    def test_preset_names_order(self) -> None:
        eq = Equalizer()
        assert eq.get_preset_names() == list(PRESETS.keys())


class TestEnableDisable:
    """Enable / disable toggle."""

    def test_enable_toggle(self) -> None:
        eq = Equalizer()
        assert eq.is_enabled() is True
        eq.set_enabled(False)
        assert eq.is_enabled() is False
        eq.set_enabled(True)
        assert eq.is_enabled() is True

    def test_disable_does_not_alter_stored_bands(self) -> None:
        eq = Equalizer()
        eq.apply_preset("Rock")
        expected = eq.get_bands()
        eq.set_enabled(False)
        # Stored bands should remain the same
        assert eq.get_bands() == expected

    def test_callback_receives_zero_when_disabled(self) -> None:
        calls: list[tuple[int, float]] = []
        eq = Equalizer(on_band_changed=lambda i, g: calls.append((i, g)))
        eq.apply_preset("Bass Boost")
        calls.clear()

        eq.set_enabled(False)
        # All 10 bands should be pushed as 0.0
        assert len(calls) == NUM_BANDS
        assert all(g == DEFAULT_GAIN_DB for _, g in calls)

    def test_callback_receives_real_values_when_enabled(self) -> None:
        calls: list[tuple[int, float]] = []
        eq = Equalizer(on_band_changed=lambda i, g: calls.append((i, g)))
        eq.apply_preset("Bass Boost")
        eq.set_enabled(False)
        calls.clear()

        eq.set_enabled(True)
        assert len(calls) == NUM_BANDS
        for i, (idx, gain) in enumerate(calls):
            assert idx == i
            assert gain == PRESETS["Bass Boost"][i]


class TestCallback:
    """on_band_changed callback invocation."""

    def test_callback_on_set_band(self) -> None:
        calls: list[tuple[int, float]] = []
        eq = Equalizer(on_band_changed=lambda i, g: calls.append((i, g)))
        eq.set_band(3, 4.5)
        assert calls == [(3, 4.5)]

    def test_callback_not_fired_when_disabled(self) -> None:
        calls: list[tuple[int, float]] = []
        eq = Equalizer(on_band_changed=lambda i, g: calls.append((i, g)))
        eq.set_enabled(False)
        calls.clear()

        eq.set_band(0, 6.0)
        # Callback should NOT fire when equalizer is disabled
        assert len(calls) == 0

    def test_no_callback_when_none(self) -> None:
        eq = Equalizer(on_band_changed=None)
        # Should not raise
        eq.set_band(0, 5.0)
        eq.set_enabled(False)
        eq.set_enabled(True)


class TestPersistence:
    """to_dict / from_dict round-trip."""

    def test_to_dict_default(self) -> None:
        eq = Equalizer()
        data = eq.to_dict()
        assert data["enabled"] is True
        assert data["bands"] == [0.0] * NUM_BANDS

    def test_to_dict_custom_state(self) -> None:
        eq = Equalizer()
        eq.apply_preset("Jazz")
        eq.set_enabled(False)
        data = eq.to_dict()
        assert data["enabled"] is False
        assert data["bands"] == [float(v) for v in PRESETS["Jazz"]]

    def test_roundtrip(self) -> None:
        eq1 = Equalizer()
        eq1.apply_preset("Electronic")
        eq1.set_enabled(False)
        data = eq1.to_dict()

        eq2 = Equalizer()
        eq2.from_dict(data)
        assert eq2.get_bands() == eq1.get_bands()
        assert eq2.is_enabled() == eq1.is_enabled()

    def test_from_dict_partial(self) -> None:
        eq = Equalizer()
        eq.set_band(0, 3.0)
        eq.from_dict({"enabled": False})
        # Bands should stay as they were (from_dict only updates 'enabled')
        assert eq.is_enabled() is False
        assert eq.get_bands()[0] == 3.0

    def test_from_dict_empty(self) -> None:
        eq = Equalizer()
        eq.set_band(0, 5.0)
        eq.from_dict({})
        # Nothing should change
        assert eq.get_bands()[0] == 5.0
        assert eq.is_enabled() is True

    def test_from_dict_wrong_band_length_ignored(self) -> None:
        eq = Equalizer()
        eq.set_band(0, 5.0)
        eq.from_dict({"bands": [1.0, 2.0]})
        # Wrong length should be silently ignored
        assert eq.get_bands()[0] == 5.0
