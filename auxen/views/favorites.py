"""Favorites view for the Auxen music player."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import GLib, Gtk, Pango


# Placeholder favorite tracks: (title, artist, album, source, quality, duration, date_added_sort_key)
_SAMPLE_FAVORITES: list[dict[str, str | int]] = [
    {
        "title": "Teardrop",
        "artist": "Massive Attack",
        "album": "Mezzanine",
        "source": "tidal",
        "quality": "Hi-Res",
        "duration": "5:29",
        "date_added": 8,
    },
    {
        "title": "Glory Box",
        "artist": "Portishead",
        "album": "Dummy",
        "source": "local",
        "quality": "FLAC",
        "duration": "5:01",
        "date_added": 7,
    },
    {
        "title": "Xtal",
        "artist": "Aphex Twin",
        "album": "Selected Ambient Works 85-92",
        "source": "tidal",
        "quality": "Hi-Res",
        "duration": "4:54",
        "date_added": 6,
    },
    {
        "title": "Hunter",
        "artist": "Bjork",
        "album": "Homogenic",
        "source": "local",
        "quality": "MP3",
        "duration": "4:12",
        "date_added": 5,
    },
    {
        "title": "Closer",
        "artist": "Nine Inch Nails",
        "album": "The Downward Spiral",
        "source": "tidal",
        "quality": "FLAC",
        "duration": "6:13",
        "date_added": 4,
    },
    {
        "title": "Lovesong",
        "artist": "The Cure",
        "album": "Disintegration",
        "source": "local",
        "quality": "FLAC",
        "duration": "3:29",
        "date_added": 3,
    },
    {
        "title": "Everything In Its Right Place",
        "artist": "Radiohead",
        "album": "Kid A",
        "source": "tidal",
        "quality": "Hi-Res",
        "duration": "4:11",
        "date_added": 2,
    },
    {
        "title": "Roygbiv",
        "artist": "Boards of Canada",
        "album": "Music Has the Right to Children",
        "source": "local",
        "quality": "MP3",
        "duration": "2:32",
        "date_added": 1,
    },
]

# Sort key functions keyed by dropdown label.
_SORT_KEYS: dict[str, str] = {
    "Date Added": "date_added",
    "Artist": "artist",
    "Album": "album",
    "Title": "title",
}

_SORT_OPTIONS = list(_SORT_KEYS.keys())


def _make_favorite_row(track: dict[str, str | int]) -> Gtk.ListBoxRow:
    """Build a single row for the favorites list."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("favorites-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    # -- Album art placeholder (48x48) --
    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.add_css_class("album-art-mini")
    art_box.set_size_request(48, 48)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(20)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_vexpand(True)
    art_box.append(art_icon)
    row_box.append(art_box)

    # -- Title + Artist + Album column --
    text_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=2,
    )
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_label = Gtk.Label()
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(40)
    title_label.add_css_class("body")
    title_label.set_markup(
        f"<b>{GLib.markup_escape_text(str(track['title']))}</b>"
    )
    text_box.append(title_label)

    subtitle_parts = [str(track["artist"])]
    if track.get("album"):
        subtitle_parts.append(str(track["album"]))
    subtitle_text = " \u2014 ".join(subtitle_parts)

    subtitle_label = Gtk.Label(label=subtitle_text)
    subtitle_label.set_xalign(0)
    subtitle_label.set_ellipsize(Pango.EllipsizeMode.END)
    subtitle_label.set_max_width_chars(50)
    subtitle_label.add_css_class("caption")
    subtitle_label.add_css_class("dim-label")
    text_box.append(subtitle_label)

    row_box.append(text_box)

    # -- Source badge --
    source = str(track["source"])
    source_badge = Gtk.Label(label=source.capitalize())
    css_class = (
        "source-badge-tidal" if source == "tidal" else "source-badge-local"
    )
    source_badge.add_css_class(css_class)
    source_badge.set_valign(Gtk.Align.CENTER)
    row_box.append(source_badge)

    # -- Quality badge --
    quality = str(track.get("quality", ""))
    if quality:
        quality_badge = Gtk.Label(label=quality)
        quality_badge.add_css_class("favorites-quality-badge")
        quality_badge.set_valign(Gtk.Align.CENTER)
        row_box.append(quality_badge)

    # -- Duration --
    dur_label = Gtk.Label(label=str(track.get("duration", "")))
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    dur_label.set_margin_start(4)
    row_box.append(dur_label)

    # -- Heart toggle button (filled, amber when favorited) --
    heart_btn = Gtk.ToggleButton()
    heart_btn.set_icon_name("emblem-favorite-symbolic")
    heart_btn.set_active(True)
    heart_btn.add_css_class("flat")
    heart_btn.add_css_class("favorites-heart-btn")
    heart_btn.set_valign(Gtk.Align.CENTER)
    heart_btn.set_tooltip_text("Remove from favorites")
    row_box.append(heart_btn)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    return row


class FavoritesView(Gtk.ScrolledWindow):
    """Scrollable favorites view with filters, sorting, and track list."""

    __gtype_name__ = "FavoritesView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        self._all_favorites: list[dict[str, str | int]] = list(
            _SAMPLE_FAVORITES
        )
        self._active_filter: str = "All"
        self._active_sort: str = "Date Added"

        # Root container
        root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=20,
        )
        root.set_margin_top(24)
        root.set_margin_bottom(24)
        root.set_margin_start(32)
        root.set_margin_end(32)

        # ---- 1. Header section ----
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header_box.add_css_class("favorites-header")

        heart_icon = Gtk.Image.new_from_icon_name(
            "emblem-favorite-symbolic"
        )
        heart_icon.set_pixel_size(28)
        heart_icon.add_css_class("favorites-header-icon")
        header_box.append(heart_icon)

        title_label = Gtk.Label(label="Favorites")
        title_label.set_xalign(0)
        title_label.add_css_class("greeting-label")
        header_box.append(title_label)

        self._count_label = Gtk.Label()
        self._count_label.set_xalign(0)
        self._count_label.add_css_class("caption")
        self._count_label.add_css_class("dim-label")
        self._count_label.set_valign(Gtk.Align.END)
        self._count_label.set_margin_bottom(6)
        header_box.append(self._count_label)

        root.append(header_box)

        # ---- 2. Filter toggle buttons ----
        controls_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
        )

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

        controls_box.append(filter_box)

        # Spacer
        spacer = Gtk.Box()
        spacer.set_hexpand(True)
        controls_box.append(spacer)

        # ---- 3. Sort controls ----
        sort_label = Gtk.Label(label="Sort by:")
        sort_label.add_css_class("caption")
        sort_label.add_css_class("dim-label")
        sort_label.set_valign(Gtk.Align.CENTER)
        controls_box.append(sort_label)

        sort_model = Gtk.StringList.new(_SORT_OPTIONS)
        self._sort_dropdown = Gtk.DropDown(model=sort_model)
        self._sort_dropdown.set_selected(0)
        self._sort_dropdown.add_css_class("favorites-sort-dropdown")
        self._sort_dropdown.set_valign(Gtk.Align.CENTER)
        self._sort_dropdown.connect(
            "notify::selected", self._on_sort_changed
        )
        controls_box.append(self._sort_dropdown)

        root.append(controls_box)

        # ---- 4. Content stack (list vs empty state) ----
        self._content_stack = Gtk.Stack()
        self._content_stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._content_stack.set_transition_duration(150)
        self._content_stack.set_vexpand(True)

        # -- Favorites list --
        self._favorites_list = Gtk.ListBox()
        self._favorites_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._favorites_list.add_css_class("boxed-list")
        self._content_stack.add_named(self._favorites_list, "list")

        # -- Empty state --
        empty_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        empty_box.add_css_class("favorites-empty-state")
        empty_box.set_vexpand(True)
        empty_box.set_margin_top(80)

        empty_icon = Gtk.Image.new_from_icon_name(
            "emblem-favorite-symbolic"
        )
        empty_icon.set_pixel_size(64)
        empty_box.append(empty_icon)

        empty_title = Gtk.Label(label="No favorites yet")
        empty_title.add_css_class("title-3")
        empty_box.append(empty_title)

        empty_subtitle = Gtk.Label(label="Heart any track to add it here")
        empty_subtitle.add_css_class("caption")
        empty_box.append(empty_subtitle)

        self._content_stack.add_named(empty_box, "empty")

        root.append(self._content_stack)

        self.set_child(root)

        # Populate initial view
        self._refresh_list()

    # ---- Internal helpers ----

    def _get_filtered_tracks(self) -> list[dict[str, str | int]]:
        """Return favorites filtered by the active source filter."""
        if self._active_filter == "All":
            tracks = list(self._all_favorites)
        else:
            source_key = self._active_filter.lower()
            tracks = [
                t
                for t in self._all_favorites
                if t["source"] == source_key
            ]
        return tracks

    def _get_sorted_tracks(
        self, tracks: list[dict[str, str | int]]
    ) -> list[dict[str, str | int]]:
        """Sort tracks by the active sort criterion."""
        sort_field = _SORT_KEYS.get(self._active_sort, "date_added")
        reverse = sort_field == "date_added"
        return sorted(
            tracks, key=lambda t: str(t.get(sort_field, "")), reverse=reverse
        )

    def _refresh_list(self) -> None:
        """Rebuild the favorites list from current filter/sort state."""
        # Clear existing rows
        while True:
            row = self._favorites_list.get_row_at_index(0)
            if row is None:
                break
            self._favorites_list.remove(row)

        filtered = self._get_filtered_tracks()
        sorted_tracks = self._get_sorted_tracks(filtered)

        # Update count label
        count = len(sorted_tracks)
        track_word = "track" if count == 1 else "tracks"
        self._count_label.set_label(f"{count} {track_word}")

        if not sorted_tracks:
            self._content_stack.set_visible_child_name("empty")
            return

        for track in sorted_tracks:
            self._favorites_list.append(_make_favorite_row(track))

        self._content_stack.set_visible_child_name("list")

    # ---- Signal handlers ----

    def _on_filter_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior: only one filter active at a time."""
        if not toggled_btn.get_active():
            any_active = any(b.get_active() for b in self._filter_buttons)
            if not any_active:
                toggled_btn.set_active(True)
            return

        # Deactivate all other buttons
        for btn in self._filter_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)

        self._active_filter = toggled_btn.get_label() or "All"
        self._refresh_list()

    def _on_sort_changed(
        self, dropdown: Gtk.DropDown, _pspec: object
    ) -> None:
        """Handle sort dropdown selection changes."""
        idx = dropdown.get_selected()
        if 0 <= idx < len(_SORT_OPTIONS):
            self._active_sort = _SORT_OPTIONS[idx]
            self._refresh_list()
