"""Equalizer dialog for the Auxen music player.

Presents a 10-band graphic equalizer with vertical sliders, a preset
drop-down, an enable/disable toggle, and a *Reset to Flat* button.
"""

from __future__ import annotations

import logging
from typing import Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk  # noqa: E402

from auxen.equalizer import (  # noqa: E402
    BAND_FREQUENCIES,
    MAX_GAIN_DB,
    MIN_GAIN_DB,
    NUM_BANDS,
    Equalizer,
)

logger = logging.getLogger(__name__)


class EqualizerDialog(Adw.Window):
    """Modal-style window presenting a 10-band graphic equalizer."""

    __gtype_name__ = "EqualizerDialog"

    def __init__(
        self,
        equalizer: Equalizer,
        transient_for: Optional[Gtk.Window] = None,
        **kwargs,
    ) -> None:
        super().__init__(
            title="Equalizer",
            modal=True,
            resizable=False,
            **kwargs,
        )
        if transient_for is not None:
            self.set_transient_for(transient_for)

        self._equalizer = equalizer
        self._updating = False  # guard against signal loops

        self.set_default_size(620, 480)
        self.add_css_class("equalizer-dialog")

        # ---- Toolbar view with header bar for close button ----
        toolbar_view = Adw.ToolbarView()
        header_bar = Adw.HeaderBar()
        toolbar_view.add_top_bar(header_bar)

        # ---- Root layout ----
        root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
        )
        root.set_margin_top(8)
        root.set_margin_bottom(20)
        root.set_margin_start(24)
        root.set_margin_end(24)

        # ---- Header bar (enable switch + preset selector) ----
        header = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header.set_valign(Gtk.Align.CENTER)

        # Enable / disable switch
        enable_label = Gtk.Label(label="Equalizer")
        enable_label.add_css_class("title-4")
        header.append(enable_label)

        self._enable_switch = Gtk.Switch()
        self._enable_switch.set_active(equalizer.is_enabled())
        self._enable_switch.set_valign(Gtk.Align.CENTER)
        self._enable_switch.add_css_class("equalizer-enable-switch")
        self._enable_switch.connect("state-set", self._on_enable_toggled)
        header.append(self._enable_switch)

        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        header.append(spacer)

        # Preset dropdown
        preset_label = Gtk.Label(label="Preset:")
        preset_label.add_css_class("dim-label")
        preset_label.set_valign(Gtk.Align.CENTER)
        header.append(preset_label)

        preset_names = equalizer.get_preset_names()
        self._preset_model = Gtk.StringList.new(preset_names)
        self._preset_dropdown = Gtk.DropDown(model=self._preset_model)
        self._preset_dropdown.set_valign(Gtk.Align.CENTER)
        self._preset_dropdown.add_css_class("equalizer-preset-dropdown")
        self._preset_dropdown.connect("notify::selected", self._on_preset_changed)
        header.append(self._preset_dropdown)

        root.append(header)

        # ---- Separator ----
        sep = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        root.append(sep)

        # ---- Band sliders area ----
        sliders_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
            homogeneous=True,
        )
        sliders_box.set_vexpand(True)
        sliders_box.set_valign(Gtk.Align.FILL)

        self._band_scales: list[Gtk.Scale] = []
        self._band_value_labels: list[Gtk.Label] = []

        bands = equalizer.get_bands()
        for i in range(NUM_BANDS):
            band_box = Gtk.Box(
                orientation=Gtk.Orientation.VERTICAL,
                spacing=4,
            )
            band_box.set_halign(Gtk.Align.CENTER)

            # dB value label above slider
            value_label = Gtk.Label(label=self._format_db(bands[i]))
            value_label.add_css_class("equalizer-band-value")
            value_label.add_css_class("caption")
            band_box.append(value_label)
            self._band_value_labels.append(value_label)

            # Vertical scale (slider)
            scale = Gtk.Scale.new_with_range(
                Gtk.Orientation.VERTICAL,
                MIN_GAIN_DB,
                MAX_GAIN_DB,
                0.5,
            )
            scale.set_inverted(True)  # higher values at top
            scale.set_value(bands[i])
            scale.set_draw_value(False)
            scale.set_vexpand(True)
            scale.add_css_class("equalizer-band-slider")
            # Add marks at 0 dB
            scale.add_mark(0.0, Gtk.PositionType.RIGHT, None)
            scale.connect("value-changed", self._on_band_changed, i)
            band_box.append(scale)
            self._band_scales.append(scale)

            # Frequency label below slider
            freq_label = Gtk.Label(label=BAND_FREQUENCIES[i])
            freq_label.add_css_class("equalizer-band-label")
            freq_label.add_css_class("caption")
            freq_label.add_css_class("dim-label")
            band_box.append(freq_label)

            sliders_box.append(band_box)

        root.append(sliders_box)

        # ---- Footer (Reset button) ----
        footer = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        footer.set_halign(Gtk.Align.CENTER)

        reset_btn = Gtk.Button(label="Reset to Flat")
        reset_btn.add_css_class("flat")
        reset_btn.connect("clicked", self._on_reset)
        footer.append(reset_btn)

        root.append(footer)

        toolbar_view.set_content(root)
        self.set_content(toolbar_view)

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _format_db(value: float) -> str:
        """Format a dB value for display."""
        if value > 0:
            return f"+{value:.1f}"
        return f"{value:.1f}"

    def _sync_sliders_from_equalizer(self) -> None:
        """Push equalizer band values into the UI sliders."""
        self._updating = True
        try:
            bands = self._equalizer.get_bands()
            for i, val in enumerate(bands):
                self._band_scales[i].set_value(val)
                self._band_value_labels[i].set_label(self._format_db(val))
        finally:
            self._updating = False

    # ------------------------------------------------------------------
    # Signal handlers
    # ------------------------------------------------------------------

    def _on_enable_toggled(
        self, switch: Gtk.Switch, state: bool
    ) -> bool:
        self._equalizer.set_enabled(state)
        return False

    def _on_preset_changed(self, dropdown: Gtk.DropDown, _pspec) -> None:
        idx = dropdown.get_selected()
        names = self._equalizer.get_preset_names()
        if 0 <= idx < len(names):
            self._equalizer.apply_preset(names[idx])
            self._sync_sliders_from_equalizer()

    def _on_band_changed(self, scale: Gtk.Scale, band_index: int) -> None:
        if self._updating:
            return
        value = scale.get_value()
        self._equalizer.set_band(band_index, value)
        self._band_value_labels[band_index].set_label(
            self._format_db(value)
        )

    def _on_reset(self, _button: Gtk.Button) -> None:
        self._equalizer.apply_preset("Flat")
        self._sync_sliders_from_equalizer()
        # Select "Flat" in the dropdown (index 0)
        self._preset_dropdown.set_selected(0)
