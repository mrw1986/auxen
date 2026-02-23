"""Sidebar navigation for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gdk, GObject, Gtk

from auxen.views.playlist_view import PLAYLIST_COLORS

logger = logging.getLogger(__name__)


# Navigation items: (page_name, icon_name, display_label, badge_text, badge_class)
_BROWSE_ITEMS: list[tuple[str, str, str, str | None, str | None]] = [
    ("home", "go-home-symbolic", "Home", None, None),
    ("search", "system-search-symbolic", "Search", None, None),
    ("library", "folder-music-symbolic", "Library", "Local", "nav-badge-local"),
    ("stats", "utilities-system-monitor-symbolic", "Stats", None, None),
]

_TIDAL_ITEMS: list[tuple[str, str, str, str | None, str | None]] = [
    ("explore", "compass-symbolic", "Explore", "Tidal", "nav-badge-tidal"),
    ("mixes", "media-playlist-shuffle-symbolic", "Mixes", None, None),
    ("favorites", "starred-symbolic", "Favorites", None, None),
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
        badge = Gtk.Label(label=badge_text)
        badge.add_css_class(badge_class)
        row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
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
    icon.set_opacity(0.5)
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
        self._page_names: list[str] = []
        self._db = None
        self._playlist_ids: list[int] = []
        self._smart_playlist_ids: list[str] = []
        self._smart_playlist_service = None

        # ---- Brand section ----
        brand_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        brand_box.add_css_class("sidebar-brand")

        brand_icon = Gtk.Image.new_from_icon_name("audio-headphones-symbolic")
        brand_icon.set_pixel_size(32)
        brand_icon.add_css_class("accent")
        brand_box.append(brand_icon)

        brand_text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        brand_title = Gtk.Label(label="Auxen")
        brand_title.set_xalign(0)
        brand_title.add_css_class("title-3")
        brand_text_box.append(brand_title)

        brand_subtitle = Gtk.Label(label="FEED THE OX")
        brand_subtitle.set_xalign(0)
        brand_subtitle.add_css_class("caption")
        brand_subtitle.add_css_class("sidebar-brand-subtitle")
        brand_text_box.append(brand_subtitle)

        brand_box.append(brand_text_box)
        self.append(brand_box)

        # ---- Scrollable middle area ----
        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        middle_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=0,
        )

        # ---- Browse section ----
        middle_box.append(_make_section_label("BROWSE"))

        self._browse_list = Gtk.ListBox()
        self._browse_list.add_css_class("navigation-sidebar")
        self._browse_list.set_selection_mode(Gtk.SelectionMode.SINGLE)

        for page_name, icon, label, badge, badge_cls in _BROWSE_ITEMS:
            row = _make_nav_row(icon, label, badge, badge_cls)
            self._browse_list.append(row)
            self._page_names.append(page_name)

        self._browse_list.connect("row-selected", self._on_row_selected, 0)
        middle_box.append(self._browse_list)

        # ---- Tidal section ----
        middle_box.append(_make_section_label("TIDAL"))

        self._tidal_list = Gtk.ListBox()
        self._tidal_list.add_css_class("navigation-sidebar")
        self._tidal_list.set_selection_mode(Gtk.SelectionMode.SINGLE)

        for page_name, icon, label, badge, badge_cls in _TIDAL_ITEMS:
            row = _make_nav_row(icon, label, badge, badge_cls)
            self._tidal_list.append(row)
            self._page_names.append(page_name)

        self._tidal_list.connect(
            "row-selected", self._on_row_selected, len(_BROWSE_ITEMS)
        )
        middle_box.append(self._tidal_list)

        # ---- Playlists section ----
        middle_box.append(_make_section_label("PLAYLISTS"))

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
        middle_box.append(_make_section_label("SMART PLAYLISTS"))

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

        # ---- Account section (pinned at bottom) ----
        account_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=10,
        )
        account_box.add_css_class("sidebar-account")

        avatar = Gtk.Label(label="M")
        avatar.add_css_class("sidebar-avatar")
        account_box.append(avatar)

        account_text = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        account_text.set_hexpand(True)

        username = Gtk.Label(label="mrw1986")
        username.set_xalign(0)
        username.add_css_class("body")
        account_text.append(username)

        plan_label = Gtk.Label(label="Tidal HiFi Plus")
        plan_label.set_xalign(0)
        plan_label.add_css_class("caption")
        plan_label.add_css_class("sidebar-tidal-plan")
        account_text.append(plan_label)

        account_box.append(account_text)

        settings_btn = Gtk.Button.new_from_icon_name(
            "emblem-system-symbolic"
        )
        settings_btn.add_css_class("flat")
        settings_btn.set_valign(Gtk.Align.CENTER)
        if self._on_settings:
            settings_btn.connect("clicked", lambda *_: self._on_settings())
        account_box.append(settings_btn)

        about_btn = Gtk.Button.new_from_icon_name(
            "help-about-symbolic"
        )
        about_btn.add_css_class("flat")
        about_btn.set_valign(Gtk.Align.CENTER)
        about_btn.set_tooltip_text("About Auxen")
        about_btn.connect("clicked", self._on_about_clicked)
        account_box.append(about_btn)

        self.append(account_box)

        # ---- Select "Home" by default ----
        first_row = self._browse_list.get_row_at_index(0)
        if first_row:
            self._browse_list.select_row(first_row)

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
        """Create a new playlist via the database."""
        if self._db is None:
            return
        try:
            playlist_id = self._db.create_playlist("New Playlist")
            self.refresh_playlists()
            # Optionally navigate to the new playlist
            if self._on_playlist_selected:
                self._on_playlist_selected(playlist_id)
        except Exception:
            logger.warning(
                "Failed to create new playlist", exc_info=True
            )

    def _show_playlist_context_menu(
        self, playlist_id: int, x: float, y: float
    ) -> None:
        """Show a context menu for a playlist row."""
        if self._db is None:
            return

        menu = Gtk.PopoverMenu()
        menu_model = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=4,
        )
        menu_model.set_margin_top(4)
        menu_model.set_margin_bottom(4)
        menu_model.set_margin_start(4)
        menu_model.set_margin_end(4)

        rename_btn = Gtk.Button(label="Rename")
        rename_btn.add_css_class("flat")
        rename_btn.connect(
            "clicked",
            lambda _b, pid=playlist_id: self._rename_playlist(pid),
        )
        menu_model.append(rename_btn)

        delete_btn = Gtk.Button(label="Delete")
        delete_btn.add_css_class("flat")
        delete_btn.add_css_class("destructive-action")
        delete_btn.connect(
            "clicked",
            lambda _b, pid=playlist_id: self._delete_playlist(pid),
        )
        menu_model.append(delete_btn)

    def _rename_playlist(self, playlist_id: int) -> None:
        """Rename a playlist (placeholder for dialog)."""
        if self._db is None:
            return
        # Simple rename for now
        self._db.rename_playlist(playlist_id, "Renamed Playlist")
        self.refresh_playlists()

    def _delete_playlist(self, playlist_id: int) -> None:
        """Delete a playlist."""
        if self._db is None:
            return
        self._db.delete_playlist(playlist_id)
        self.refresh_playlists()

    def _on_about_clicked(self, _button: Gtk.Button) -> None:
        """Open the About dialog."""
        from auxen.views.about_dialog import show_about_dialog

        root = self.get_root()
        show_about_dialog(root)
