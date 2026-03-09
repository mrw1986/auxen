"""View mode toggle and types for list/compact/grid display modes."""

from __future__ import annotations

from enum import Enum
from typing import Callable

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import Gtk


class ViewMode(Enum):
    """Display modes for track lists."""

    LIST = "list"  # Full rows with all metadata
    COMPACT_LIST = "compact"  # Smaller rows, less spacing
    GRID = "grid"  # Album art grid (FlowBox)


def make_view_mode_toggle(
    on_mode_changed: Callable[[ViewMode], None] | None = None,
    initial_mode: ViewMode = ViewMode.LIST,
    include_grid: bool = True,
) -> Gtk.Box:
    """Create a view mode toggle widget with icon buttons.

    Returns a Gtk.Box with radio-button-style toggle buttons for
    List, Compact List, and optionally Grid modes.

    Parameters
    ----------
    on_mode_changed:
        Callback invoked with the new ViewMode when selection changes.
    initial_mode:
        Which mode to start with (default: LIST).
    include_grid:
        Whether to include the Grid mode button (default: True).
    """
    toggle_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=0,
    )
    toggle_box.add_css_class("view-mode-toggle")

    buttons: list[Gtk.ToggleButton] = []

    mode_configs = [
        (ViewMode.LIST, "view-list-symbolic", "List view"),
        (ViewMode.COMPACT_LIST, "view-list-bullet-symbolic", "Compact list"),
    ]
    if include_grid:
        mode_configs.append(
            (ViewMode.GRID, "view-grid-symbolic", "Grid view")
        )

    for mode, icon_name, tooltip in mode_configs:
        btn = Gtk.ToggleButton()
        btn.set_icon_name(icon_name)
        btn.add_css_class("flat")
        btn.add_css_class("view-mode-btn")
        btn.set_tooltip_text(tooltip)
        btn.set_active(mode == initial_mode)

        # Store mode on the button for lookup
        btn._view_mode = mode  # type: ignore[attr-defined]

        def _on_toggled(
            toggled_btn: Gtk.ToggleButton,
            all_buttons: list[Gtk.ToggleButton] = buttons,
            callback: Callable[[ViewMode], None] | None = on_mode_changed,
        ) -> None:
            if not toggled_btn.get_active():
                # Don't allow deactivating the current button
                any_active = any(b.get_active() for b in all_buttons)
                if not any_active:
                    toggled_btn.set_active(True)
                return

            # Deactivate all other buttons (radio behavior)
            for b in all_buttons:
                if b is not toggled_btn and b.get_active():
                    b.set_active(False)

            if callback is not None:
                new_mode = getattr(toggled_btn, "_view_mode", ViewMode.LIST)
                callback(new_mode)

        btn.connect("toggled", _on_toggled)
        toggle_box.append(btn)
        buttons.append(btn)

    # Store buttons list on the box for external access
    toggle_box._mode_buttons = buttons  # type: ignore[attr-defined]

    return toggle_box


def get_active_mode(toggle_box: Gtk.Box) -> ViewMode:
    """Return the currently active ViewMode from a toggle box."""
    buttons = getattr(toggle_box, "_mode_buttons", [])
    for btn in buttons:
        if btn.get_active():
            return getattr(btn, "_view_mode", ViewMode.LIST)
    return ViewMode.LIST


def set_active_mode(toggle_box: Gtk.Box, mode: ViewMode) -> None:
    """Programmatically set the active mode on a toggle box."""
    buttons = getattr(toggle_box, "_mode_buttons", [])
    for btn in buttons:
        btn_mode = getattr(btn, "_view_mode", None)
        if btn_mode == mode:
            btn.set_active(True)
            break
