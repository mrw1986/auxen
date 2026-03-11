"""Sidebar navigation for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gdk, Gio, GLib, GObject, Gtk

from auxen.views.playlist_view import PLAYLIST_COLORS
from auxen.views.widgets import DragScrollHelper, make_tidal_source_badge

logger = logging.getLogger(__name__)


# Navigation items: (page_name, icon_name, display_label, badge_text, badge_class, tooltip)
_BROWSE_ITEMS: list[tuple[str, str, str, str | None, str | None, str | None]] = [
    ("home", "go-home-symbolic", "Home", None, None, "Go to Home"),
    ("search", "system-search-symbolic", "Search", None, None, "Search music"),
    ("library", "folder-music-symbolic", "Library", "Local", "nav-badge-local", "Browse local music library"),
    ("collection", "emblem-favorite-symbolic", "Collection", None, None, "View your collection"),
    ("stats", "utilities-system-monitor-symbolic", "Stats", None, None, "Listening statistics"),
]

_TIDAL_ITEMS: list[tuple[str, str, str, str | None, str | None, str | None]] = [
    ("explore", "compass-symbolic", "Explore", "Tidal", "nav-badge-tidal", "Explore Tidal music"),
    ("mixes", "media-playlist-shuffle-symbolic", "Mixes", None, None, "Tidal curated mixes"),
]

# Fallback playlists shown when no database is connected
_FALLBACK_PLAYLISTS: list[tuple[str, str]] = [
    ("Late Night Vibes", "#d4a039"),
    ("Tidal Discovery Mix", "#00c4cc"),
    ("FLAC Collection", "#7cb87a"),
    ("Workout Beats", "#9b59b6"),
]


def _make_nav_row(
    icon_name: str,
    label_text: str,
    badge_text: str | None,
    badge_class: str | None,
    tooltip: str | None = None,
) -> Gtk.ListBoxRow:
    """Build a single navigation row with icon, label, and optional badge."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.set_margin_top(6)
    row_box.set_margin_bottom(6)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    icon = Gtk.Image.new_from_icon_name(icon_name)
    icon.set_pixel_size(20)
    row_box.append(icon)

    label = Gtk.Label(label=label_text)
    label.set_xalign(0)
    label.set_hexpand(True)
    row_box.append(label)

    if badge_text and badge_class:
        if badge_class == "nav-badge-tidal":
            badge = make_tidal_source_badge(
                label_text=badge_text,
                css_class=badge_class,
                icon_size=10,
            )
        else:
            badge = Gtk.Label(label=badge_text)
            badge.add_css_class(badge_class)
        row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    if tooltip:
        row.set_tooltip_text(tooltip)
    return row


def _make_section_label(text: str) -> Gtk.Label:
    """Create an uppercase, dim section header label."""
    label = Gtk.Label(label=text)
    label.set_xalign(0)
    label.set_margin_start(16)
    label.set_margin_end(16)
    label.set_margin_top(16)
    label.set_margin_bottom(4)
    label.add_css_class("caption")
    label.add_css_class("dim-label")
    label.add_css_class("sidebar-section-label")
    return label


def _make_playlist_row(
    name: str, dot_color: str, playlist_id: int | None = None
) -> Gtk.ListBoxRow:
    """Build a playlist row with a colored dot and name."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    dot = Gtk.Label(label="")
    dot.add_css_class("playlist-dot")
    dot.set_size_request(8, 8)

    # Inline color via CSS provider for per-dot color
    css = Gtk.CssProvider()
    css.load_from_string(
        f".playlist-dot {{ background-color: {dot_color}; }}"
    )
    dot.get_style_context().add_provider(
        css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1
    )
    row_box.append(dot)

    label = Gtk.Label(label=name)
    label.set_xalign(0)
    label.set_hexpand(True)
    label.set_ellipsize(3)  # PANGO_ELLIPSIZE_END
    row_box.append(label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    return row


def _make_add_playlist_row() -> Gtk.ListBoxRow:
    """Build the 'New Playlist' row with a + icon."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("playlist-add-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    icon = Gtk.Image.new_from_icon_name("list-add-symbolic")
    icon.set_pixel_size(16)
    icon.set_opacity(0.7)
    row_box.append(icon)

    label = Gtk.Label(label="New Playlist")
    label.set_xalign(0)
    label.set_hexpand(True)
    label.add_css_class("dim-label")
    row_box.append(label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    return row


def _make_smart_playlist_row(
    icon_name: str, label_text: str
) -> Gtk.ListBoxRow:
    """Build a smart playlist row with an icon and name."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    icon = Gtk.Image.new_from_icon_name(icon_name)
    icon.set_pixel_size(16)
    icon.add_css_class("smart-playlist-sidebar-icon")
    row_box.append(icon)

    label = Gtk.Label(label=label_text)
    label.set_xalign(0)
    label.set_hexpand(True)
    label.set_ellipsize(3)  # PANGO_ELLIPSIZE_END
    row_box.append(label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    return row


class AuxenSidebar(Gtk.Box):
    """Left sidebar with brand, browse, tidal, playlists, smart playlists, and account sections."""

    __gtype_name__ = "AuxenSidebar"

    def __init__(
        self,
        on_navigate: Callable[[str], None] | None = None,
        on_settings: Callable[[], None] | None = None,
        on_playlist_selected: Callable[[int], None] | None = None,
        on_smart_playlist_selected: Callable[[str], None] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            **kwargs,
        )

        self._on_navigate = on_navigate
        self._on_settings = on_settings
        self._on_playlist_selected = on_playlist_selected
        self._on_smart_playlist_selected = on_smart_playlist_selected
        self.on_tidal_login: Callable[[], None] | None = None
        # Callback: on_drop_track_to_playlist(track_ids: list[int], playlist_id: int)
        self.on_drop_track_to_playlist: (
            Callable[[list[int], int], None] | None
        ) = None
        self._page_names: list[str] = []
        self._db = None
        self._playlist_ids: list[int] = []
        self._smart_playlist_ids: list[str] = []
        self._smart_playlist_service = None
        # Context menu state
        self._ctx_menu: Gtk.PopoverMenu | None = None
        self._ctx_action_group: Gio.SimpleActionGroup | None = None
        self._ctx_parent: Gtk.Widget | None = None
        # Sidebar playlist callbacks
        self.on_sidebar_play_playlist: Callable[[int], None] | None = None
        # Collapse callback: on_collapse_changed(collapsed: bool)
        self.on_collapse_changed: Callable[[bool], None] | None = None
        # Collapse state
        self._collapsed = False

        # ---- Brand section ----
        brand_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        brand_box.add_css_class("sidebar-brand")

        # Theme-aware ox logo (not wordmark)
        self._brand_icon = Gtk.Image()
        self._brand_icon.set_pixel_size(52)
        self._brand_icon.set_valign(Gtk.Align.CENTER)
        self._brand_icon.add_css_class("sidebar-brand-logo")
        self._update_brand_icon()
        brand_box.append(self._brand_icon)

        # Listen for theme changes to swap logo variant
        style_mgr = Adw.StyleManager.get_default()
        style_mgr.connect("notify::dark", lambda *_a: self._update_brand_icon())

        brand_text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
            valign=Gtk.Align.CENTER,
        )
        brand_title = Gtk.Label(label="AUXEN")
        brand_title.set_xalign(0)
        brand_title.add_css_class("sidebar-brand-title")
        brand_text_box.append(brand_title)

        brand_subtitle = Gtk.Label(label="UNORTHODOX AUDIO")
        brand_subtitle.set_xalign(0)
        brand_subtitle.add_css_class("caption")
        brand_subtitle.add_css_class("sidebar-brand-subtitle")
        brand_text_box.append(brand_subtitle)

        brand_box.append(brand_text_box)
        self._brand_text_box = brand_text_box
        self._brand_box = brand_box
        self.append(brand_box)

        # ---- Scrollable middle area ----
        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(scroll)

        middle_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )

        # ---- Browse section ----
        self._browse_label = _make_section_label("BROWSE")
        middle_box.append(self._browse_label)

        self._browse_list = Gtk.ListBox()
        self._browse_list.add_css_class("navigation-sidebar")
        self._browse_list.set_selection_mode(Gtk.SelectionMode.SINGLE)

        for page_name, icon, label, badge, badge_cls, tooltip in _BROWSE_ITEMS:
            row = _make_nav_row(icon, label, badge, badge_cls, tooltip)
            self._browse_list.append(row)
            self._page_names.append(page_name)

        self._browse_list.connect("row-selected", self._on_row_selected, 0)
        middle_box.append(self._browse_list)

        # ---- Collapsed-only separator between Browse and Tidal ----
        self._collapsed_sep_browse = Gtk.Separator(
            orientation=Gtk.Orientation.HORIZONTAL
        )
        self._collapsed_sep_browse.set_margin_top(6)
        self._collapsed_sep_browse.set_margin_bottom(6)
        self._collapsed_sep_browse.set_margin_start(8)
        self._collapsed_sep_browse.set_margin_end(8)
        self._collapsed_sep_browse.set_visible(False)
        middle_box.append(self._collapsed_sep_browse)

        # ---- Tidal section ----
        tidal_section_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=6,
        )
        tidal_section_box.set_margin_start(16)
        tidal_section_box.set_margin_end(16)
        tidal_section_box.set_margin_top(16)
        tidal_section_box.set_margin_bottom(4)

        tidal_section_icon = Gtk.Image.new_from_icon_name(
            "tidal-symbolic"
        )
        tidal_section_icon.set_pixel_size(12)
        tidal_section_icon.add_css_class("caption")
        tidal_section_icon.add_css_class("dim-label")
        tidal_section_box.append(tidal_section_icon)

        tidal_section_label = Gtk.Label(label="TIDAL")
        tidal_section_label.set_xalign(0)
        tidal_section_label.add_css_class("caption")
        tidal_section_label.add_css_class("dim-label")
        tidal_section_label.add_css_class("sidebar-section-label")
        tidal_section_box.append(tidal_section_label)

        self._tidal_section_box = tidal_section_box
        middle_box.append(tidal_section_box)

        self._tidal_list = Gtk.ListBox()
        self._tidal_list.add_css_class("navigation-sidebar")
        self._tidal_list.set_selection_mode(Gtk.SelectionMode.SINGLE)

        for page_name, icon, label, badge, badge_cls, tooltip in _TIDAL_ITEMS:
            row = _make_nav_row(icon, label, badge, badge_cls, tooltip)
            self._tidal_list.append(row)
            self._page_names.append(page_name)

        self._tidal_list.connect(
            "row-selected", self._on_row_selected, len(_BROWSE_ITEMS)
        )
        middle_box.append(self._tidal_list)

        # ---- Collapsed-only separator between Tidal and Playlists ----
        self._collapsed_sep_tidal = Gtk.Separator(
            orientation=Gtk.Orientation.HORIZONTAL
        )
        self._collapsed_sep_tidal.set_margin_top(6)
        self._collapsed_sep_tidal.set_margin_bottom(6)
        self._collapsed_sep_tidal.set_margin_start(8)
        self._collapsed_sep_tidal.set_margin_end(8)
        self._collapsed_sep_tidal.set_visible(False)
        middle_box.append(self._collapsed_sep_tidal)

        # ---- Collapsed playlists nav button (only visible when collapsed) ----
        self._collapsed_playlists_row = _make_nav_row(
            "playlist-symbolic", "Playlists", None, None, "View playlists"
        )
        self._collapsed_playlists_row.set_visible(False)  # hidden by default (expanded mode)
        self._collapsed_playlists_list = Gtk.ListBox()
        self._collapsed_playlists_list.add_css_class("navigation-sidebar")
        self._collapsed_playlists_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._collapsed_playlists_list.append(self._collapsed_playlists_row)
        self._collapsed_playlists_list.set_visible(False)
        self._collapsed_playlists_list.connect(
            "row-activated", self._on_collapsed_playlists_clicked
        )
        middle_box.append(self._collapsed_playlists_list)

        # ---- Playlists section ----
        self._playlists_label = _make_section_label("PLAYLISTS")
        middle_box.append(self._playlists_label)

        self._playlist_list = Gtk.ListBox()
        self._playlist_list.add_css_class("navigation-sidebar")
        self._playlist_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._playlist_list.connect(
            "row-activated", self._on_playlist_row_activated
        )

        # Show fallback playlists initially (before database is connected)
        for name, color in _FALLBACK_PLAYLISTS:
            row = _make_playlist_row(name, color)
            self._playlist_list.append(row)

        middle_box.append(self._playlist_list)

        # ---- Smart Playlists section ----
        self._smart_playlists_label = _make_section_label("SMART PLAYLISTS")
        middle_box.append(self._smart_playlists_label)

        self._smart_playlist_list = Gtk.ListBox()
        self._smart_playlist_list.add_css_class("navigation-sidebar")
        self._smart_playlist_list.set_selection_mode(
            Gtk.SelectionMode.NONE
        )
        self._smart_playlist_list.connect(
            "row-activated", self._on_smart_playlist_row_activated
        )

        middle_box.append(self._smart_playlist_list)

        scroll.set_child(middle_box)
        self.append(scroll)

        # ---- Bottom section (pinned at bottom): collapse btn + account ----
        bottom_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )

        # Collapse / expand toggle + theme toggle
        collapse_row = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
        )
        collapse_row.add_css_class("sidebar-collapse-row")

        # Theme toggle button (dark/light/system cycle)
        self._theme_mode = 0  # 0=system, 1=dark, 2=light
        self._theme_btn = Gtk.Button.new_from_icon_name(
            "display-brightness-symbolic"
        )
        self._theme_btn.add_css_class("flat")
        self._theme_btn.add_css_class("sidebar-collapse-btn")
        self._theme_btn.set_tooltip_text("Theme: System")
        self._theme_btn.set_halign(Gtk.Align.START)
        self._theme_btn.connect("clicked", self._on_theme_clicked)
        collapse_row.append(self._theme_btn)

        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        collapse_row.append(spacer)

        collapse_btn = Gtk.Button.new_from_icon_name("sidebar-show-symbolic")
        collapse_btn.add_css_class("flat")
        collapse_btn.add_css_class("sidebar-collapse-btn")
        collapse_btn.set_tooltip_text("Collapse sidebar")
        collapse_btn.set_halign(Gtk.Align.END)
        collapse_btn.connect("clicked", self._on_collapse_clicked)
        self._collapse_btn = collapse_btn
        collapse_row.append(collapse_btn)
        bottom_box.append(collapse_row)

        # Account section
        account_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=10,
        )
        account_box.add_css_class("sidebar-account")
        self._account_box = account_box

        self._avatar_label = Gtk.Label(label="?")
        self._avatar_label.add_css_class("sidebar-avatar")
        account_box.append(self._avatar_label)

        account_text = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        account_text.set_hexpand(True)

        self._username_label = Gtk.Label(label="Not connected")
        self._username_label.set_xalign(0)
        self._username_label.add_css_class("body")
        account_text.append(self._username_label)

        self._plan_label = Gtk.Label(label="Sign in to Tidal")
        self._plan_label.set_xalign(0)
        self._plan_label.add_css_class("caption")
        self._plan_label.add_css_class("sidebar-tidal-plan")
        self._plan_label.add_css_class("clickable-link")
        self._plan_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))

        plan_click = Gtk.GestureClick.new()
        plan_click.set_button(1)
        plan_click.connect("released", self._on_plan_label_clicked)
        self._plan_label.add_controller(plan_click)

        account_text.append(self._plan_label)

        self._account_text = account_text
        account_box.append(account_text)

        self._settings_btn = Gtk.Button.new_from_icon_name(
            "emblem-system-symbolic"
        )
        self._settings_btn.add_css_class("flat")
        self._settings_btn.set_valign(Gtk.Align.CENTER)
        self._settings_btn.set_tooltip_text("Settings")
        if self._on_settings:
            self._settings_btn.connect(
                "clicked", lambda *_: self._on_settings()
            )
        account_box.append(self._settings_btn)

        self._about_btn = Gtk.Button.new_from_icon_name(
            "help-about-symbolic"
        )
        self._about_btn.add_css_class("flat")
        self._about_btn.set_valign(Gtk.Align.CENTER)
        self._about_btn.set_tooltip_text("About Auxen")
        self._about_btn.connect("clicked", self._on_about_clicked)
        account_box.append(self._about_btn)

        bottom_box.append(account_box)
        self.append(bottom_box)

        # ---- Select "Home" by default ----
        first_row = self._browse_list.get_row_at_index(0)
        if first_row:
            self._browse_list.select_row(first_row)

    # ---- Brand icon helpers ----

    def _update_brand_icon(self) -> None:
        """Set the brand icon to the theme-appropriate ox logo."""
        is_dark = Adw.StyleManager.get_default().get_dark()
        icon_name = "auxen-logo-dark" if is_dark else "auxen-logo-light"
        self._brand_icon.set_from_icon_name(icon_name)

    # ---- Collapse / Expand ----

    def _on_theme_clicked(self, _btn: Gtk.Button) -> None:
        """Cycle theme: system → dark → light → system."""
        # 0=system, 1=dark, 2=light
        self._theme_mode = (self._theme_mode + 1) % 3
        self._apply_theme()
        # Persist to database
        if self._db is not None:
            scheme_names = {0: "system", 1: "dark", 2: "light"}
            self._db.set_setting(
                "color_scheme", scheme_names[self._theme_mode]
            )

    def _apply_theme(self) -> None:
        """Apply the current theme mode to the style manager and button."""
        style_mgr = Adw.StyleManager.get_default()
        if self._theme_mode == 0:  # System
            style_mgr.set_color_scheme(Adw.ColorScheme.DEFAULT)
            self._theme_btn.set_icon_name("display-brightness-symbolic")
            self._theme_btn.set_tooltip_text("Theme: System")
        elif self._theme_mode == 1:  # Dark
            style_mgr.set_color_scheme(Adw.ColorScheme.FORCE_DARK)
            self._theme_btn.set_icon_name("weather-clear-night-symbolic")
            self._theme_btn.set_tooltip_text("Theme: Dark")
        else:  # Light
            style_mgr.set_color_scheme(Adw.ColorScheme.FORCE_LIGHT)
            self._theme_btn.set_icon_name("weather-clear-symbolic")
            self._theme_btn.set_tooltip_text("Theme: Light")

    def restore_theme_from_db(self) -> None:
        """Restore the theme setting from the database (call after set_database)."""
        if self._db is None:
            return
        scheme = self._db.get_setting("color_scheme", "system")
        mapping = {"system": 0, "dark": 1, "light": 2}
        self._theme_mode = mapping.get(scheme, 0)
        self._apply_theme()

    def _on_collapse_clicked(self, _btn: Gtk.Button) -> None:
        """Toggle sidebar between expanded and collapsed (icon-only) mode."""
        self._collapsed = not self._collapsed
        self._apply_collapsed_state()

    def _apply_collapsed_state(self) -> None:
        """Show or hide text elements based on collapsed state."""
        visible = not self._collapsed

        # Brand text + icon centering
        self._brand_text_box.set_visible(visible)
        if self._collapsed:
            self._brand_icon.set_halign(Gtk.Align.CENTER)
            self._brand_icon.set_hexpand(True)
        else:
            self._brand_icon.set_halign(Gtk.Align.FILL)
            self._brand_icon.set_hexpand(False)

        # Section labels
        self._browse_label.set_visible(visible)
        self._tidal_section_box.set_visible(visible)
        self._playlists_label.set_visible(visible)
        self._smart_playlists_label.set_visible(visible)

        # Account text (username, plan)
        self._account_text.set_visible(visible)

        # Hide playlists and smart playlists sections entirely when collapsed
        # but show the collapsed playlists icon button instead
        self._playlist_list.set_visible(visible)
        self._smart_playlist_list.set_visible(visible)
        self._collapsed_playlists_list.set_visible(self._collapsed)
        self._collapsed_playlists_row.set_visible(self._collapsed)
        self._collapsed_sep_browse.set_visible(self._collapsed)
        self._collapsed_sep_tidal.set_visible(self._collapsed)

        # Hide labels and badges in nav rows, keep icons
        for listbox in (self._browse_list, self._tidal_list, self._collapsed_playlists_list):
            idx = 0
            while True:
                row = listbox.get_row_at_index(idx)
                if row is None:
                    break
                row_box = row.get_child()
                if row_box is not None:
                    # Children: icon, label, [badge]
                    child = row_box.get_first_child()
                    first = True
                    while child is not None:
                        nxt = child.get_next_sibling()
                        if first:
                            first = False  # keep the icon
                        else:
                            child.set_visible(visible)
                        child = nxt
                idx += 1

        # Switch account box orientation: vertical when collapsed, horizontal when expanded
        if self._collapsed:
            self._account_box.set_orientation(Gtk.Orientation.VERTICAL)
            self._account_box.set_spacing(6)
            self._avatar_label.set_halign(Gtk.Align.CENTER)
            self._settings_btn.set_halign(Gtk.Align.CENTER)
            self._about_btn.set_halign(Gtk.Align.CENTER)
        else:
            self._account_box.set_orientation(Gtk.Orientation.HORIZONTAL)
            self._account_box.set_spacing(10)
            self._avatar_label.set_halign(Gtk.Align.FILL)
            self._settings_btn.set_halign(Gtk.Align.FILL)
            self._about_btn.set_halign(Gtk.Align.FILL)

        # Toggle button icon and alignment
        if self._collapsed:
            self._collapse_btn.set_icon_name("sidebar-show-right-symbolic")
            self._collapse_btn.set_halign(Gtk.Align.CENTER)
        else:
            self._collapse_btn.set_icon_name("sidebar-show-symbolic")
            self._collapse_btn.set_halign(Gtk.Align.END)
        self._collapse_btn.set_tooltip_text(
            "Expand sidebar" if self._collapsed else "Collapse sidebar"
        )

        # Update CSS class and notify parent (window) to adjust split view
        if self._collapsed:
            self.add_css_class("sidebar-collapsed")
        else:
            self.remove_css_class("sidebar-collapsed")

        if self.on_collapse_changed is not None:
            self.on_collapse_changed(self._collapsed)

    # ---- Public API ----

    def set_smart_playlist_service(self, service) -> None:
        """Wire the sidebar to a SmartPlaylistService for smart playlists."""
        self._smart_playlist_service = service
        self.refresh_smart_playlists()

    def set_database(self, db) -> None:
        """Wire the sidebar to a real database for dynamic playlists."""
        self._db = db
        self.refresh_playlists()

    def refresh_playlists(self) -> None:
        """Reload playlists from the database and rebuild the sidebar list."""
        # Clear existing playlist rows
        while True:
            row = self._playlist_list.get_row_at_index(0)
            if row is None:
                break
            self._playlist_list.remove(row)

        self._playlist_ids = []

        if self._db is not None:
            try:
                playlists = self._db.get_playlists()
                for pl in playlists:
                    row = _make_playlist_row(
                        pl["name"],
                        pl["color"] or "#d4a039",
                        playlist_id=pl["id"],
                    )
                    self._attach_drop_target(row, pl["id"])
                    self._attach_playlist_context_gesture(
                        row, pl["id"], pl["name"]
                    )
                    self._playlist_list.append(row)
                    self._playlist_ids.append(pl["id"])
            except Exception:
                logger.warning(
                    "Failed to load playlists from database",
                    exc_info=True,
                )

        # Always add the "New Playlist" row at the bottom
        add_row = _make_add_playlist_row()
        self._playlist_list.append(add_row)

    def refresh_smart_playlists(self) -> None:
        """Reload smart playlists from the service and rebuild the list."""
        while True:
            row = self._smart_playlist_list.get_row_at_index(0)
            if row is None:
                break
            self._smart_playlist_list.remove(row)

        self._smart_playlist_ids = []

        if self._smart_playlist_service is not None:
            try:
                definitions = (
                    self._smart_playlist_service.get_definitions()
                )
                for defn in definitions:
                    row = _make_smart_playlist_row(
                        defn["icon"], defn["name"]
                    )
                    self._smart_playlist_list.append(row)
                    self._smart_playlist_ids.append(defn["id"])
            except Exception:
                logger.warning(
                    "Failed to load smart playlists",
                    exc_info=True,
                )

    # ---- Drag-and-drop support ----

    def _attach_drop_target(
        self, row: Gtk.ListBoxRow, playlist_id: int
    ) -> None:
        """Attach a DropTarget to a playlist row so tracks can be dropped.

        Accepts strings containing one or more comma-separated track IDs.
        When a drag hovers over the row, a highlight CSS class is applied.
        """
        drop_target = Gtk.DropTarget.new(GObject.TYPE_STRING, Gdk.DragAction.COPY)

        def _on_drop(_target, value: str, _x, _y, pid=playlist_id):
            if not value:
                return False
            try:
                track_ids = [
                    int(tid.strip())
                    for tid in value.split(",")
                    if tid.strip().isdigit()
                ]
            except (ValueError, AttributeError):
                return False
            if not track_ids:
                return False
            if self.on_drop_track_to_playlist is not None:
                self.on_drop_track_to_playlist(track_ids, pid)
            return True

        def _on_enter(_target, _x, _y):
            row_child = row.get_child()
            if row_child is not None:
                row_child.add_css_class("playlist-drop-highlight")
            return Gdk.DragAction.COPY

        def _on_leave(_target):
            row_child = row.get_child()
            if row_child is not None:
                row_child.remove_css_class("playlist-drop-highlight")

        drop_target.connect("drop", _on_drop)
        drop_target.connect("enter", _on_enter)
        drop_target.connect("leave", _on_leave)
        row.add_controller(drop_target)

    # ---- Internal handlers ----

    def _on_row_selected(
        self,
        listbox: Gtk.ListBox,
        row: Gtk.ListBoxRow | None,
        offset: int,
    ) -> None:
        """Handle navigation row selection."""
        if row is None:
            return

        # Deselect the *other* listbox so only one row is active
        if listbox is self._browse_list:
            self._tidal_list.unselect_all()
        else:
            self._browse_list.unselect_all()

        index = row.get_index() + offset
        if 0 <= index < len(self._page_names):
            page = self._page_names[index]
            if self._on_navigate:
                self._on_navigate(page)

    def _on_collapsed_playlists_clicked(
        self, _listbox: Gtk.ListBox, _row: Gtk.ListBoxRow
    ) -> None:
        """Expand the sidebar when the collapsed playlists button is clicked."""
        self._collapsed = False
        self._apply_collapsed_state()

    def _on_playlist_row_activated(
        self, _listbox: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle click on a playlist row."""
        idx = row.get_index()

        # Check if it's the "New Playlist" row (last row, after all playlists)
        if idx == len(self._playlist_ids):
            self._create_new_playlist()
            return

        # Otherwise it's a playlist row — navigate to it
        if 0 <= idx < len(self._playlist_ids):
            playlist_id = self._playlist_ids[idx]
            # Deselect other nav lists
            self._browse_list.unselect_all()
            self._tidal_list.unselect_all()
            if self._on_playlist_selected:
                self._on_playlist_selected(playlist_id)

    def _on_smart_playlist_row_activated(
        self, _listbox: Gtk.ListBox, row: Gtk.ListBoxRow
    ) -> None:
        """Handle click on a smart playlist row."""
        idx = row.get_index()
        if 0 <= idx < len(self._smart_playlist_ids):
            smart_id = self._smart_playlist_ids[idx]
            # Deselect other nav lists
            self._browse_list.unselect_all()
            self._tidal_list.unselect_all()
            if self._on_smart_playlist_selected:
                self._on_smart_playlist_selected(smart_id)

    def _create_new_playlist(self) -> None:
        """Show a name prompt dialog, then create the playlist."""
        if self._db is None:
            return

        dialog = Adw.AlertDialog()
        dialog.set_heading("New Playlist")
        dialog.set_body("Enter a name for the new playlist:")
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("create", "Create")
        dialog.set_response_appearance(
            "create", Adw.ResponseAppearance.SUGGESTED
        )
        dialog.set_default_response("create")
        dialog.set_close_response("cancel")

        entry = Gtk.Entry()
        entry.set_text("New Playlist")
        entry.set_activates_default(True)
        entry.select_region(0, -1)
        entry.set_margin_top(8)
        dialog.set_extra_child(entry)

        def _on_response(_dialog, response):
            if response != "create":
                return
            name = entry.get_text().strip()
            if not name:
                dialog.set_body("Please enter a playlist name:")
                entry.grab_focus()
                dialog.present(self.get_root())
                return
            try:
                playlist_id = self._db.create_playlist(name)
                self.refresh_playlists()
                if self._on_playlist_selected:
                    self._on_playlist_selected(playlist_id)
            except Exception:
                logger.warning(
                    "Failed to create new playlist", exc_info=True
                )

        dialog.connect("response", _on_response)
        dialog.present(self.get_root())

    def _attach_playlist_context_gesture(
        self,
        row: Gtk.ListBoxRow,
        playlist_id: int,
        playlist_name: str,
    ) -> None:
        """Attach a right-click gesture to a sidebar playlist row."""
        gesture = Gtk.GestureClick.new()
        gesture.set_button(3)
        gesture.connect(
            "pressed",
            self._on_playlist_right_click,
            row,
            playlist_id,
            playlist_name,
        )
        row.add_controller(gesture)

    def _cleanup_ctx_menu(self) -> None:
        """Clean up any active context menu."""
        if self._ctx_menu is not None:
            self._ctx_menu.popdown()
            self._ctx_menu.unparent()
            self._ctx_menu = None
        if self._ctx_parent is not None and self._ctx_action_group is not None:
            self._ctx_parent.insert_action_group("plctx", None)
        self._ctx_action_group = None
        self._ctx_parent = None

    def _on_playlist_right_click(
        self,
        _gesture: Gtk.GestureClick,
        _n_press: int,
        x: float,
        y: float,
        row: Gtk.ListBoxRow,
        playlist_id: int,
        playlist_name: str,
    ) -> None:
        """Show a context menu when right-clicking a playlist row."""
        if self._db is None:
            return

        self._cleanup_ctx_menu()

        menu = Gio.Menu()
        section = Gio.Menu()
        section.append("Play All", "plctx.play")
        section.append("Rename", "plctx.rename")
        section.append("Change Color", "plctx.color")
        menu.append_section(None, section)
        danger = Gio.Menu()
        danger.append("Delete", "plctx.delete")
        menu.append_section(None, danger)

        group = Gio.SimpleActionGroup()

        play_act = Gio.SimpleAction.new("play", None)
        play_act.connect(
            "activate",
            lambda *_: self._ctx_play_playlist(playlist_id),
        )
        group.add_action(play_act)

        rename_act = Gio.SimpleAction.new("rename", None)
        rename_act.connect(
            "activate",
            lambda *_: self._ctx_rename_playlist(playlist_id, playlist_name),
        )
        group.add_action(rename_act)

        color_act = Gio.SimpleAction.new("color", None)
        color_act.connect(
            "activate",
            lambda *_: self._ctx_change_color(row, playlist_id),
        )
        group.add_action(color_act)

        delete_act = Gio.SimpleAction.new("delete", None)
        delete_act.connect(
            "activate",
            lambda *_: self._ctx_delete_playlist(playlist_id, playlist_name),
        )
        group.add_action(delete_act)

        row.insert_action_group("plctx", group)
        self._ctx_action_group = group
        self._ctx_parent = row

        popover = Gtk.PopoverMenu.new_from_model(menu)
        popover.set_parent(row)
        popover.set_has_arrow(False)
        popover.add_css_class("context-menu")

        from auxen.views.context_menu import Gdk_Rectangle

        popover.set_pointing_to(Gdk_Rectangle(x, y))
        popover.connect("closed", self._on_ctx_menu_closed, row)
        self._ctx_menu = popover
        popover.popup()

    def _on_ctx_menu_closed(
        self, popover: Gtk.PopoverMenu, row: Gtk.Widget
    ) -> None:
        """Defer cleanup so action signals can fire first."""
        GLib.idle_add(self._deferred_ctx_cleanup, row, popover)

    def _deferred_ctx_cleanup(
        self, row: Gtk.Widget, popover: Gtk.PopoverMenu
    ) -> bool:
        if popover is self._ctx_menu:
            self._cleanup_ctx_menu()
        else:
            popover.unparent()
        return False

    def _ctx_play_playlist(self, playlist_id: int) -> None:
        """Play all tracks in a playlist."""
        if self.on_sidebar_play_playlist is not None:
            self.on_sidebar_play_playlist(playlist_id)

    def _ctx_rename_playlist(
        self, playlist_id: int, current_name: str
    ) -> None:
        """Show a rename dialog for a playlist."""
        if self._db is None:
            return

        root = self.get_root()
        dialog = Adw.AlertDialog.new("Rename Playlist", None)
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("rename", "Rename")
        dialog.set_response_appearance(
            "rename", Adw.ResponseAppearance.SUGGESTED
        )
        dialog.set_default_response("rename")
        dialog.set_close_response("cancel")

        entry = Gtk.Entry()
        entry.set_text(current_name)
        entry.set_activates_default(True)
        dialog.set_extra_child(entry)

        def _on_response(_dlg, response):
            if response == "rename":
                new_name = entry.get_text().strip()
                if new_name and new_name != current_name:
                    self._db.rename_playlist(playlist_id, new_name)
                    self.refresh_playlists()

        dialog.connect("response", _on_response)
        dialog.present(root)

    def _ctx_change_color(
        self, row: Gtk.ListBoxRow, playlist_id: int
    ) -> None:
        """Show a color picker popover anchored to the playlist row."""
        if self._db is None:
            return

        popover = Gtk.Popover()
        popover.set_parent(row)

        color_grid = Gtk.FlowBox()
        color_grid.set_max_children_per_line(4)
        color_grid.set_selection_mode(Gtk.SelectionMode.NONE)
        color_grid.set_margin_top(8)
        color_grid.set_margin_bottom(8)
        color_grid.set_margin_start(8)
        color_grid.set_margin_end(8)
        color_grid.set_column_spacing(8)
        color_grid.set_row_spacing(8)

        for color in PLAYLIST_COLORS:
            btn = Gtk.Button()
            btn.set_size_request(32, 32)
            css = Gtk.CssProvider()
            css.load_from_string(
                f"button {{ background-color: {color}; "
                f"border-radius: 9999px; min-width: 32px; "
                f"min-height: 32px; }}"
            )
            btn.get_style_context().add_provider(
                css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1
            )

            def _pick(_b, c=color, pop=popover):
                self._db.update_playlist_color(playlist_id, c)
                pop.popdown()
                pop.unparent()
                self.refresh_playlists()

            btn.connect("clicked", _pick)
            color_grid.insert(btn, -1)

        popover.set_child(color_grid)
        popover.popup()

    def _ctx_delete_playlist(
        self, playlist_id: int, playlist_name: str
    ) -> None:
        """Show a confirmation dialog before deleting a playlist."""
        if self._db is None:
            return

        root = self.get_root()
        dialog = Adw.AlertDialog.new(
            "Delete Playlist?",
            f'Permanently delete "{playlist_name}"? '
            "Your tracks will not be affected.",
        )
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("delete", "Delete")
        dialog.set_response_appearance(
            "delete", Adw.ResponseAppearance.DESTRUCTIVE
        )
        dialog.set_default_response("cancel")
        dialog.set_close_response("cancel")

        def _on_response(_dlg, response):
            if response == "delete":
                self._db.delete_playlist(playlist_id)
                self.refresh_playlists()
                # Navigate to Home so user isn't stuck on the deleted playlist
                if self._on_navigate:
                    self._on_navigate("home")

        dialog.connect("response", _on_response)
        dialog.present(root)

    def _on_plan_label_clicked(
        self,
        gesture: Gtk.GestureClick,
        n_press: int,
        _x: float,
        _y: float,
    ) -> None:
        """Handle click on the plan label — trigger Tidal login if not connected."""
        if n_press != 1:
            return
        if (
            self._plan_label.get_label() == "Sign in to Tidal"
            and self.on_tidal_login is not None
        ):
            self.on_tidal_login()

    def update_account(
        self,
        username: str | None = None,
        plan: str | None = None,
    ) -> None:
        """Update the account section with Tidal user info.

        Pass *None* for both to reset to the "not connected" state.
        """
        if username:
            self._avatar_label.set_label(username[0].upper())
            self._username_label.set_label(username)
            self._plan_label.set_label(plan or "Tidal")
            self._plan_label.remove_css_class("clickable-link")
            self._plan_label.set_cursor(None)
        else:
            self._avatar_label.set_label("?")
            self._username_label.set_label("Not connected")
            self._plan_label.set_label("Sign in to Tidal")
            self._plan_label.add_css_class("clickable-link")
            self._plan_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))

    def _on_about_clicked(self, _button: Gtk.Button) -> None:
        """Open the About dialog."""
        from auxen.views.about_dialog import show_about_dialog

        root = self.get_root()
        show_about_dialog(root)
