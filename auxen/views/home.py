"""Home page view for the Auxen music player."""

from __future__ import annotations

from datetime import datetime

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gtk, Pango


# Placeholder album data: (title, artist, source)
_SAMPLE_ALBUMS: list[tuple[str, str, str]] = [
    ("Mezzanine", "Massive Attack", "tidal"),
    ("Dummy", "Portishead", "local"),
    ("Selected Ambient Works 85-92", "Aphex Twin", "tidal"),
    ("Homogenic", "Bjork", "local"),
    ("The Downward Spiral", "Nine Inch Nails", "tidal"),
    ("Disintegration", "The Cure", "local"),
]

# Placeholder recently-played data: (title, artist, duration, source)
_SAMPLE_RECENT: list[tuple[str, str, str, str]] = [
    ("Teardrop", "Massive Attack", "5:29", "tidal"),
    ("Glory Box", "Portishead", "5:01", "local"),
    ("Xtal", "Aphex Twin", "4:54", "tidal"),
    ("Hunter", "Bjork", "4:12", "local"),
]


def _get_greeting() -> str:
    """Return a time-of-day greeting string."""
    hour = datetime.now().hour
    if hour < 12:
        return "Good Morning"
    if hour < 18:
        return "Good Afternoon"
    return "Good Evening"


def _make_source_badge(source: str) -> Gtk.Label:
    """Create a small pill badge indicating the track source."""
    badge = Gtk.Label(label=source.capitalize())
    css_class = (
        "source-badge-tidal" if source == "tidal" else "source-badge-local"
    )
    badge.add_css_class(css_class)
    badge.set_halign(Gtk.Align.START)
    badge.set_valign(Gtk.Align.START)
    badge.set_margin_top(8)
    badge.set_margin_start(8)
    return badge


def _make_album_card(title: str, artist: str, source: str) -> Gtk.FlowBoxChild:
    """Build a single album card for the 'Recently Added' grid."""
    card = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=6,
    )
    card.add_css_class("album-card")

    # -- Album art with overlay badge --
    overlay = Gtk.Overlay()

    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.set_size_request(160, 160)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(48)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_vexpand(True)
    art_box.append(art_icon)

    overlay.set_child(art_box)

    badge = _make_source_badge(source)
    overlay.add_overlay(badge)

    card.append(overlay)

    # -- Title --
    title_label = Gtk.Label(label=title)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(18)
    title_label.add_css_class("body")
    title_label.set_margin_start(4)
    title_label.set_margin_end(4)
    card.append(title_label)

    # -- Artist --
    artist_label = Gtk.Label(label=artist)
    artist_label.set_xalign(0)
    artist_label.set_ellipsize(Pango.EllipsizeMode.END)
    artist_label.set_max_width_chars(18)
    artist_label.add_css_class("caption")
    artist_label.add_css_class("dim-label")
    artist_label.set_margin_start(4)
    artist_label.set_margin_end(4)
    card.append(artist_label)

    child = Gtk.FlowBoxChild()
    child.set_child(card)
    return child


def _make_recently_played_row(
    title: str, artist: str, duration: str, source: str
) -> Gtk.ListBoxRow:
    """Build a row for the 'Recently Played' list."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("recently-played-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(4)
    row_box.set_margin_end(4)

    # -- Small album art placeholder --
    art_mini = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_mini.add_css_class("album-art-placeholder")
    art_mini.add_css_class("album-art-mini")
    art_mini.set_size_request(48, 48)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(20)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_vexpand(True)
    art_mini.append(art_icon)
    row_box.append(art_mini)

    # -- Title + Artist column --
    text_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=2,
    )
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_lbl = Gtk.Label(label=title)
    title_lbl.set_xalign(0)
    title_lbl.set_ellipsize(Pango.EllipsizeMode.END)
    title_lbl.add_css_class("body")
    text_box.append(title_lbl)

    artist_lbl = Gtk.Label(label=artist)
    artist_lbl.set_xalign(0)
    artist_lbl.set_ellipsize(Pango.EllipsizeMode.END)
    artist_lbl.add_css_class("caption")
    artist_lbl.add_css_class("dim-label")
    text_box.append(artist_lbl)

    row_box.append(text_box)

    # -- Duration --
    dur_label = Gtk.Label(label=duration)
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    row_box.append(dur_label)

    # -- Source badge --
    badge = Gtk.Label(label=source.capitalize())
    css_class = (
        "source-badge-tidal" if source == "tidal" else "source-badge-local"
    )
    badge.add_css_class(css_class)
    badge.set_valign(Gtk.Align.CENTER)
    row_box.append(badge)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    return row


class HomePage(Gtk.ScrolledWindow):
    """Scrollable home page with greeting, filters, stats, and content grids."""

    __gtype_name__ = "HomePage"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        # Root container
        root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        root.set_margin_top(24)
        root.set_margin_bottom(24)
        root.set_margin_start(32)
        root.set_margin_end(32)

        # ---- 1. Greeting header ----
        self._greeting = Gtk.Label(label=_get_greeting())
        self._greeting.set_xalign(0)
        self._greeting.add_css_class("greeting-label")
        root.append(self._greeting)

        # ---- 2. Filter toggle buttons ----
        filter_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )

        self._filter_buttons: list[Gtk.ToggleButton] = []
        for label_text in ("All", "Tidal", "Local"):
            btn = Gtk.ToggleButton(label=label_text)
            btn.add_css_class("filter-btn")
            btn.connect("toggled", self._on_filter_toggled)
            filter_box.append(btn)
            self._filter_buttons.append(btn)

        # Activate "All" by default
        self._filter_buttons[0].set_active(True)

        root.append(filter_box)

        # ---- 3. Stats row ----
        stats_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
            homogeneous=True,
        )

        stats_box.append(
            self._make_stat_card(
                icon_name="media-optical-symbolic",
                value="0",
                label="Total Tracks",
                accent_class=None,
            )
        )
        stats_box.append(
            self._make_stat_card(
                icon_name="network-wireless-symbolic",
                value="0",
                label="Tidal Tracks",
                accent_class="stat-accent-tidal",
            )
        )
        stats_box.append(
            self._make_stat_card(
                icon_name="folder-music-symbolic",
                value="0",
                label="Local Files",
                accent_class="stat-accent-local",
            )
        )
        root.append(stats_box)

        # ---- 4. Recently Added section ----
        recently_added_header = Gtk.Label(label="Recently Added")
        recently_added_header.set_xalign(0)
        recently_added_header.add_css_class("section-header")
        root.append(recently_added_header)

        self._album_grid = Gtk.FlowBox()
        self._album_grid.set_homogeneous(True)
        self._album_grid.set_min_children_per_line(2)
        self._album_grid.set_max_children_per_line(6)
        self._album_grid.set_column_spacing(16)
        self._album_grid.set_row_spacing(16)
        self._album_grid.set_selection_mode(Gtk.SelectionMode.NONE)

        for title, artist, source in _SAMPLE_ALBUMS:
            self._album_grid.append(_make_album_card(title, artist, source))

        root.append(self._album_grid)

        # ---- 5. Recently Played section ----
        recently_played_header = Gtk.Label(label="Recently Played")
        recently_played_header.set_xalign(0)
        recently_played_header.add_css_class("section-header")
        root.append(recently_played_header)

        self._recent_list = Gtk.ListBox()
        self._recent_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._recent_list.add_css_class("boxed-list")

        for title, artist, duration, source in _SAMPLE_RECENT:
            self._recent_list.append(
                _make_recently_played_row(title, artist, duration, source)
            )

        root.append(self._recent_list)

        self.set_child(root)

    # ---- Internal helpers ----

    @staticmethod
    def _make_stat_card(
        icon_name: str,
        value: str,
        label: str,
        accent_class: str | None,
    ) -> Gtk.Box:
        """Build a stat card widget."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        card.add_css_class("stat-card")
        card.set_margin_top(4)
        card.set_margin_bottom(4)

        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(24)
        if accent_class:
            icon.add_css_class(accent_class)
        card.append(icon)

        value_label = Gtk.Label(label=value)
        value_label.add_css_class("stat-card-value")
        card.append(value_label)

        text_label = Gtk.Label(label=label)
        text_label.add_css_class("stat-card-label")
        card.append(text_label)

        return card

    def _on_filter_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior: only one filter active at a time."""
        if not toggled_btn.get_active():
            # If the user tries to deactivate the only active button,
            # re-activate it so there is always one selected.
            any_active = any(b.get_active() for b in self._filter_buttons)
            if not any_active:
                toggled_btn.set_active(True)
            return

        # Deactivate all other buttons
        for btn in self._filter_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)
