"""Home page view for the Auxen music player."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, Gtk, Pango

from auxen.views.context_menu import TrackContextMenu

logger = logging.getLogger(__name__)

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


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    if seconds is None or seconds <= 0:
        return "0:00"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


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

    # Placeholder icon (shown when no art is available)
    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(48)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_vexpand(True)
    art_box.append(art_icon)

    # Album art image (hidden until loaded).
    # Use Gtk.Image + set_pixel_size + set_from_paintable (texture) so
    # the image renders at 160 CSS pixels (2x asset fetched for HiDPI).
    art_image = Gtk.Image()
    art_image.set_pixel_size(160)
    art_image.set_halign(Gtk.Align.CENTER)
    art_image.set_valign(Gtk.Align.CENTER)
    art_image.add_css_class("album-card-art-image")
    art_image.set_visible(False)
    art_box.append(art_image)

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
    artist_label.add_css_class("clickable-link")
    artist_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))
    artist_label.set_margin_start(4)
    artist_label.set_margin_end(4)
    card.append(artist_label)

    child = Gtk.FlowBoxChild()
    child.set_child(card)
    # Store album/artist data for click handling
    child._album_title = title  # type: ignore[attr-defined]
    child._album_artist = artist  # type: ignore[attr-defined]
    # Store references to art widgets for async loading
    child._art_icon = art_icon  # type: ignore[attr-defined]
    child._art_image = art_image  # type: ignore[attr-defined]
    # Store artist label for click gesture attachment
    child._artist_label = artist_label  # type: ignore[attr-defined]
    return child


def _make_recently_played_row(
    title: str, artist: str, duration: str, source: str,
    track=None,
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
    # Store track reference for context menu
    row._track_data = track  # type: ignore[attr-defined]
    return row


class HomePage(Gtk.ScrolledWindow):
    """Scrollable home page with greeting, filters, stats, and content grids."""

    __gtype_name__ = "HomePage"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        # Album click callback
        self._on_album_clicked: Optional[
            Callable[[str, str], None]
        ] = None

        # Artist click callback
        self._on_artist_clicked: Optional[
            Callable[[str], None]
        ] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None

        # Root container
        root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        root.add_css_class("content-view-box")
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

        self._total_value_label: Gtk.Label | None = None
        self._tidal_value_label: Gtk.Label | None = None
        self._local_value_label: Gtk.Label | None = None

        total_card, self._total_value_label = self._make_stat_card(
            icon_name="media-optical-symbolic",
            value="0",
            label="Total Tracks",
            accent_class=None,
        )
        stats_box.append(total_card)

        tidal_card, self._tidal_value_label = self._make_stat_card(
            icon_name="network-wireless-symbolic",
            value="0",
            label="Tidal Tracks",
            accent_class="stat-accent-tidal",
        )
        stats_box.append(tidal_card)

        local_card, self._local_value_label = self._make_stat_card(
            icon_name="folder-music-symbolic",
            value="0",
            label="Local Files",
            accent_class="stat-accent-local",
        )
        stats_box.append(local_card)

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
        self._album_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._album_grid.connect(
            "child-activated", self._on_album_card_activated
        )

        for title, artist, source in _SAMPLE_ALBUMS:
            card = _make_album_card(title, artist, source)
            self._attach_artist_click_gesture(card)
            self._album_grid.append(card)

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

    # ---- Public API ----

    def refresh(self, db) -> None:
        """Refresh the home page using real data from the database.

        Falls back to keeping placeholder data if the database returns
        empty results.
        """
        try:
            from auxen.models import Source

            all_tracks = db.get_all_tracks()
            local_tracks = db.get_tracks_by_source(Source.LOCAL)
            tidal_tracks = db.get_tracks_by_source(Source.TIDAL)

            total = len(all_tracks)
            local_count = len(local_tracks)
            tidal_count = len(tidal_tracks)

            self.update_stats(total, tidal_count, local_count)

            # Recently added — update the grid if we have data
            recently_added = db.get_recently_added(limit=12)
            if recently_added:
                self._clear_flow_box(self._album_grid)
                for track in recently_added:
                    card = _make_album_card(
                        title=track.album or track.title,
                        artist=track.artist,
                        source=track.source.value,
                    )
                    self._attach_artist_click_gesture(card)
                    self._album_grid.append(card)
                    # Load album art asynchronously
                    self.load_album_art_for_card(card, track)

            # Recently played — update the list if we have data
            recently_played = db.get_recently_played(limit=8)
            if recently_played:
                self._clear_list_box(self._recent_list)
                for track in recently_played:
                    row = _make_recently_played_row(
                        title=track.title,
                        artist=track.artist,
                        duration=_format_duration(track.duration),
                        source=track.source.value,
                        track=track,
                    )
                    self._attach_context_gesture(row, track)
                    self._recent_list.append(row)
        except Exception:
            logger.warning("Failed to refresh home page", exc_info=True)

    def update_stats(self, total: int, tidal: int, local: int) -> None:
        """Update the stat card values."""
        if self._total_value_label is not None:
            self._total_value_label.set_label(str(total))
        if self._tidal_value_label is not None:
            self._tidal_value_label.set_label(str(tidal))
        if self._local_value_label is not None:
            self._local_value_label.set_label(str(local))

    def set_album_art_service(self, art_service) -> None:
        """Set the AlbumArtService instance for loading album art."""
        self._album_art_service = art_service

    def load_album_art_for_card(
        self, child: Gtk.FlowBoxChild, track
    ) -> None:
        """Load album art asynchronously for an album card.

        *child* is a FlowBoxChild created by ``_make_album_card`` and
        *track* is a Track object whose art should be loaded.
        """
        art_service = getattr(self, "_album_art_service", None)
        if art_service is None or track is None:
            return

        art_icon = getattr(child, "_art_icon", None)
        art_image = getattr(child, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)

        # Fetch at logical_size * scale_factor for crisp rendering on HiDPI.
        scale = child.get_scale_factor() or 1
        art_px = 160 * scale
        art_service.get_art_async(track, _on_art_loaded, width=art_px, height=art_px)

    # ---- Internal helpers ----

    @staticmethod
    def _make_stat_card(
        icon_name: str,
        value: str,
        label: str,
        accent_class: str | None,
    ) -> tuple[Gtk.Box, Gtk.Label]:
        """Build a stat card widget and return (card, value_label)."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=14,
        )
        card.add_css_class("stat-card")
        card.set_margin_top(4)
        card.set_margin_bottom(4)

        # Icon in a tinted background box
        icon_box = Gtk.Box(
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(20)
        icon_box.append(icon)

        # Apply icon background class based on accent
        if accent_class == "stat-accent-tidal":
            icon_box.add_css_class("stat-icon-tidal")
        elif accent_class == "stat-accent-local":
            icon_box.add_css_class("stat-icon-local")
        else:
            icon_box.add_css_class("stat-icon-amber")

        card.append(icon_box)

        # Value + label text column
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_valign(Gtk.Align.CENTER)

        value_label = Gtk.Label(label=value)
        value_label.set_xalign(0)
        value_label.add_css_class("stat-card-value")
        text_box.append(value_label)

        text_label = Gtk.Label(label=label)
        text_label.set_xalign(0)
        text_label.add_css_class("stat-card-label")
        text_box.append(text_label)

        card.append(text_box)

        return card, value_label

    @staticmethod
    def _clear_flow_box(flow_box: Gtk.FlowBox) -> None:
        """Remove all children from a FlowBox."""
        while True:
            child = flow_box.get_child_at_index(0)
            if child is None:
                break
            flow_box.remove(child)

    @staticmethod
    def _clear_list_box(list_box: Gtk.ListBox) -> None:
        """Remove all rows from a ListBox."""
        while True:
            row = list_box.get_row_at_index(0)
            if row is None:
                break
            list_box.remove(row)

    def set_callbacks(
        self,
        on_album_clicked: Callable[[str, str], None] | None = None,
        on_artist_clicked: Callable[[str], None] | None = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_album_clicked:
            Called with (album_name, artist) when an album card is clicked.
        on_artist_clicked:
            Called with (artist_name) when an artist label is clicked.
        """
        self._on_album_clicked = on_album_clicked
        self._on_artist_clicked = on_artist_clicked

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the right-click context menu.

        Parameters
        ----------
        callbacks:
            Dict of context menu action callbacks (on_play, etc.).
        get_playlists:
            Callable returning current list of user playlists.
        """
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def _attach_artist_click_gesture(self, child: Gtk.FlowBoxChild) -> None:
        """Attach a click gesture to the artist label on an album card."""
        artist_label = getattr(child, "_artist_label", None)
        album_artist = getattr(child, "_album_artist", None)
        if artist_label is None or album_artist is None:
            return

        gesture = Gtk.GestureClick.new()

        def _on_artist_clicked(
            _gesture, _n_press, _x, _y, artist=album_artist
        ):
            if self._on_artist_clicked is not None:
                self._on_artist_clicked(artist)

        gesture.connect("released", _on_artist_clicked)
        artist_label.add_controller(gesture)

    def _on_album_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle an album card being clicked in the grid."""
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if (
            album_title is not None
            and album_artist is not None
            and self._on_album_clicked is not None
        ):
            self._on_album_clicked(album_title, album_artist)

    def _attach_context_gesture(self, row: Gtk.ListBoxRow, track) -> None:
        """Attach a right-click gesture to a row for the context menu."""
        if self._context_callbacks is None or track is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(_gesture, _n_press, x, y, trk=track):
            self._show_track_context_menu(row, x, y, trk)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_track_context_menu(
        self, widget: Gtk.Widget, x: float, y: float, track
    ) -> None:
        """Create and display a context menu for a track."""
        if self._context_callbacks is None:
            return

        from auxen.views.context_menu import TrackContextMenu

        is_favorite = False
        playlists = []
        if self._get_playlists is not None:
            playlists = self._get_playlists()

        # Build track-specific callbacks that capture the track reference
        callbacks = {
            "on_play": lambda t=track: self._context_callbacks["on_play"](t),
            "on_play_next": lambda t=track: self._context_callbacks["on_play_next"](t),
            "on_add_to_queue": lambda t=track: self._context_callbacks["on_add_to_queue"](t),
            "on_add_to_playlist": lambda pid, t=track: self._context_callbacks["on_add_to_playlist"](t, pid),
            "on_new_playlist": lambda t=track: self._context_callbacks["on_new_playlist"](t),
            "on_toggle_favorite": lambda t=track: self._context_callbacks["on_toggle_favorite"](t),
            "on_go_to_album": lambda t=track: self._context_callbacks["on_go_to_album"](t),
        }

        track_data = {
            "id": getattr(track, "id", None),
            "title": getattr(track, "title", ""),
            "artist": getattr(track, "artist", ""),
            "album": getattr(track, "album", ""),
            "source": getattr(track, "source", None),
            "is_favorite": is_favorite,
        }

        menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        menu.show(widget, x, y)

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
