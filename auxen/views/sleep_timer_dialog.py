"""Sleep timer dialog for the Auxen music player.

Presents preset duration buttons, a custom time entry, a fade-out
toggle, an "end of current track" option, and a live countdown
display when the timer is running.
"""

from __future__ import annotations

import logging
from typing import Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk  # noqa: E402

from auxen.sleep_timer import PRESET_DURATIONS, SleepTimer  # noqa: E402

logger = logging.getLogger(__name__)


def _label_for_minutes(minutes: int) -> str:
    """Return a human-friendly label for a preset duration."""
    if minutes >= 60 and minutes % 60 == 0:
        hours = minutes // 60
        return f"{hours}h"
    return f"{minutes}m"


class SleepTimerDialog(Adw.Window):
    """Modal dialog for configuring and monitoring the sleep timer."""

    __gtype_name__ = "SleepTimerDialog"

    def __init__(
        self,
        sleep_timer: SleepTimer,
        transient_for: Optional[Gtk.Window] = None,
        **kwargs,
    ) -> None:
        super().__init__(
            title="Sleep Timer",
            modal=True,
            resizable=False,
            **kwargs,
        )
        if transient_for is not None:
            self.set_transient_for(transient_for)

        self._timer = sleep_timer
        self.set_default_size(380, -1)
        self.add_css_class("sleep-timer-dialog")

        # ---- Toolbar view with header bar for close button ----
        toolbar_view = Adw.ToolbarView()
        header_bar = Adw.HeaderBar()
        toolbar_view.add_top_bar(header_bar)

        # ---- Root layout ----
        root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
        )
        root.set_margin_top(12)
        root.set_margin_bottom(24)
        root.set_margin_start(24)
        root.set_margin_end(24)

        # ---- Title ----
        title_label = Gtk.Label(label="Sleep Timer")
        title_label.add_css_class("title-3")
        title_label.set_halign(Gtk.Align.CENTER)
        root.append(title_label)

        # ---- Countdown display (visible when timer is active) ----
        self._countdown_label = Gtk.Label(label="00:00")
        self._countdown_label.add_css_class("sleep-timer-countdown")
        self._countdown_label.set_halign(Gtk.Align.CENTER)
        self._countdown_label.set_visible(sleep_timer.is_active)
        root.append(self._countdown_label)

        # ---- Preset buttons grid ----
        presets_label = Gtk.Label(label="Quick presets")
        presets_label.add_css_class("dim-label")
        presets_label.add_css_class("caption")
        presets_label.set_xalign(0)
        root.append(presets_label)

        presets_grid = Gtk.FlowBox()
        presets_grid.set_selection_mode(Gtk.SelectionMode.NONE)
        presets_grid.set_max_children_per_line(3)
        presets_grid.set_min_children_per_line(3)
        presets_grid.set_column_spacing(8)
        presets_grid.set_row_spacing(8)
        presets_grid.set_homogeneous(True)

        for minutes in PRESET_DURATIONS:
            btn = Gtk.Button(label=_label_for_minutes(minutes))
            btn.add_css_class("sleep-timer-preset-btn")
            btn.connect("clicked", self._on_preset_clicked, minutes)
            presets_grid.insert(btn, -1)

        root.append(presets_grid)

        # ---- Separator ----
        sep1 = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        root.append(sep1)

        # ---- Custom time entry ----
        custom_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        custom_box.set_valign(Gtk.Align.CENTER)

        custom_label = Gtk.Label(label="Custom (minutes)")
        custom_label.add_css_class("dim-label")
        custom_label.set_hexpand(True)
        custom_label.set_xalign(0)
        custom_box.append(custom_label)

        self._custom_spin = Gtk.SpinButton.new_with_range(1, 240, 1)
        self._custom_spin.set_value(30)
        self._custom_spin.set_valign(Gtk.Align.CENTER)
        custom_box.append(self._custom_spin)

        custom_start_btn = Gtk.Button(label="Start")
        custom_start_btn.add_css_class("suggested-action")
        custom_start_btn.set_valign(Gtk.Align.CENTER)
        custom_start_btn.connect("clicked", self._on_custom_start)
        custom_box.append(custom_start_btn)

        root.append(custom_box)

        # ---- Separator ----
        sep2 = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        root.append(sep2)

        # ---- Options ----
        options_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
        )

        # Fade out toggle
        fade_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        fade_label = Gtk.Label(label="Fade out volume")
        fade_label.set_hexpand(True)
        fade_label.set_xalign(0)
        fade_row.append(fade_label)

        self._fade_switch = Gtk.Switch()
        self._fade_switch.set_active(sleep_timer.fade_out_enabled)
        self._fade_switch.set_valign(Gtk.Align.CENTER)
        self._fade_switch.connect("state-set", self._on_fade_toggled)
        fade_row.append(self._fade_switch)

        options_box.append(fade_row)

        # End of current track button
        self._eot_btn = Gtk.Button(label="End of current track")
        self._eot_btn.add_css_class("flat")
        self._eot_btn.connect("clicked", self._on_end_of_track)
        options_box.append(self._eot_btn)

        root.append(options_box)

        # ---- Separator ----
        sep3 = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        root.append(sep3)

        # ---- Cancel button (only visible when active) ----
        self._cancel_btn = Gtk.Button(label="Cancel Timer")
        self._cancel_btn.add_css_class("destructive-action")
        self._cancel_btn.set_halign(Gtk.Align.CENTER)
        self._cancel_btn.connect("clicked", self._on_cancel)
        self._cancel_btn.set_visible(sleep_timer.is_active)
        root.append(self._cancel_btn)

        toolbar_view.set_content(root)
        self.set_content(toolbar_view)

        # Sync display if timer is already running.
        self._sync_active_state()

    # ------------------------------------------------------------------
    # Public API for external tick updates
    # ------------------------------------------------------------------

    def update_countdown(self, remaining_seconds: int) -> None:
        """Update the countdown label from an external tick callback."""
        text = SleepTimer.format_remaining(remaining_seconds)
        self._countdown_label.set_label(text)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _sync_active_state(self) -> None:
        """Sync visibility based on whether the timer is active."""
        active = self._timer.is_active
        self._countdown_label.set_visible(active)
        self._cancel_btn.set_visible(active)
        if active:
            remaining = self._timer.get_remaining()
            self._countdown_label.set_label(
                SleepTimer.format_remaining(remaining)
            )

    def _start_timer(self, minutes: int) -> None:
        """Start the timer and update the UI."""
        self._timer.start(minutes)
        self._sync_active_state()

    # ------------------------------------------------------------------
    # Signal handlers
    # ------------------------------------------------------------------

    def _on_preset_clicked(
        self, _btn: Gtk.Button, minutes: int
    ) -> None:
        self._start_timer(minutes)

    def _on_custom_start(self, _btn: Gtk.Button) -> None:
        minutes = int(self._custom_spin.get_value())
        self._start_timer(minutes)

    def _on_fade_toggled(
        self, _switch: Gtk.Switch, state: bool
    ) -> bool:
        self._timer.fade_out_enabled = state
        return False

    def _on_end_of_track(self, _btn: Gtk.Button) -> None:
        self._timer.start_end_of_track()
        self._sync_active_state()

    def _on_cancel(self, _btn: Gtk.Button) -> None:
        self._timer.cancel()
        self._sync_active_state()
