"""Home page view for the Auxen music player."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("GdkPixbuf", "2.0")

from gi.repository import Gdk, GdkPixbuf, GObject, Gtk, Pango

from auxen.views.context_menu import AlbumContextMenu, TrackContextMenu
from auxen.views.view_mode import ViewMode, make_view_mode_toggle
from auxen.views.widgets import (
    DragScrollHelper,
    make_compact_track_row,
    make_standard_track_row,
    make_tidal_source_badge,
)

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


def _make_source_badge(source: str) -> Gtk.Widget:
    """Create a small pill badge indicating the track source."""
    if source == "tidal":
        badge = make_tidal_source_badge(
            label_text=source.capitalize(),
            css_class="source-badge-tidal",
            icon_size=10,
        )
    else:
        badge = Gtk.Label(label=source.capitalize())
        badge.add_css_class("source-badge-local")
    badge.set_halign(Gtk.Align.END)
    badge.set_valign(Gtk.Align.START)
    badge.set_margin_top(8)
    badge.set_margin_end(8)
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
    overlay.add_css_class("album-card-art-container")

    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.set_size_request(160, 160)
    art_box.set_vexpand(False)

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

    # -- Hover overlay (darkens art) --
    hover_overlay = Gtk.Box()
    hover_overlay.add_css_class("album-card-hover-overlay")
    hover_overlay.set_halign(Gtk.Align.FILL)
    hover_overlay.set_valign(Gtk.Align.FILL)
    overlay.add_overlay(hover_overlay)

    # -- Play button (centered, revealed on hover) --
    play_btn = Gtk.Button.new_from_icon_name(
        "media-playback-start-symbolic"
    )
    play_btn.add_css_class("album-card-play-btn")
    play_btn.set_halign(Gtk.Align.CENTER)
    play_btn.set_valign(Gtk.Align.CENTER)
    play_btn.set_tooltip_text(f"Play {title}")
    overlay.add_overlay(play_btn)

    card.append(overlay)

    # -- Title --
    title_label = Gtk.Label(label=title)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(18)
    title_label.add_css_class("body")
    title_label.set_margin_start(6)
    title_label.set_margin_end(6)
    card.append(title_label)

    # -- Artist --
    artist_label = Gtk.Label(label=artist)
    artist_label.set_xalign(0)
    artist_label.set_ellipsize(Pango.EllipsizeMode.END)
    artist_label.set_max_width_chars(18)
    artist_label.add_css_class("caption")
    artist_label.add_css_class("clickable-link")
    artist_label.set_cursor(Gdk.Cursor.new_from_name("pointer"))
    artist_label.set_margin_start(6)
    artist_label.set_margin_end(6)
    card.append(artist_label)

    child = Gtk.FlowBoxChild()
    child.set_child(card)
    # Store album/artist data for click handling
    child._album_title = title  # type: ignore[attr-defined]
    child._album_artist = artist  # type: ignore[attr-defined]
    child._source = source  # type: ignore[attr-defined]
    # Store references to art widgets for async loading
    child._art_icon = art_icon  # type: ignore[attr-defined]
    child._art_image = art_image  # type: ignore[attr-defined]
    # Store artist label for click gesture attachment
    child._artist_label = artist_label  # type: ignore[attr-defined]
    # Store play button for wiring callbacks
    child._play_btn = play_btn  # type: ignore[attr-defined]
    return child


def _make_recently_played_row(
    title: str, artist: str, duration: str, source: str,
    track=None, on_play_clicked=None,
    on_artist_clicked=None, on_album_clicked=None,
) -> Gtk.ListBoxRow:
    """Build a row for the 'Recently Played' list using the shared widget."""
    track_dict = {
        "title": title,
        "artist": artist,
        "source": source,
        "duration": duration,
    }
    row = make_standard_track_row(
        track_dict,
        show_art=True,
        show_source_badge=True,
        show_quality_badge=False,
        show_duration=True,
        art_size=48,
        css_class="recently-played-row",
        on_play_clicked=on_play_clicked,
        on_artist_clicked=on_artist_clicked,
        on_album_clicked=on_album_clicked,
    )
    row._track_data = track  # type: ignore[attr-defined]
    return row


class HomePage(Gtk.ScrolledWindow):
    """Scrollable home page with greeting, filters, stats, and content grids."""

    __gtype_name__ = "HomePage"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        # Album click callback
        self._on_album_clicked: Optional[
            Callable[[str, str], None]
        ] = None

        # Artist click callback
        self._on_artist_clicked: Optional[
            Callable[[str], None]
        ] = None

        # Album play callback (play button on hover)
        self._on_play_album: Optional[
            Callable[[str, str], None]
        ] = None

        # Track play callback (play button on track rows)
        self._on_play_track: Optional[Callable] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None

        # Album context menu callbacks
        self._album_context_callbacks: Optional[dict] = None
        self._get_album_playlists: Optional[Callable] = None
        self._current_menu: object = None

        self._content_width = 0

        # Root container
        self._root = root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        root.add_css_class("content-view-box")
        root.set_margin_top(24)
        root.set_margin_bottom(24)
        root.set_margin_start(16)
        root.set_margin_end(16)

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
        stats_box = Gtk.FlowBox()
        stats_box.set_homogeneous(True)
        stats_box.set_min_children_per_line(1)
        stats_box.set_max_children_per_line(3)
        stats_box.set_column_spacing(16)
        stats_box.set_row_spacing(8)
        stats_box.set_selection_mode(Gtk.SelectionMode.NONE)

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
            icon_name="tidal-symbolic",
            value="0",
            label="Tidal Collection",
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
        self._album_grid.set_min_children_per_line(1)
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
            self._attach_play_button(card)
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
        self._home_db = db
        # Restore persisted filter on first load
        if not getattr(self, "_filter_restored", False):
            self._filter_restored = True
            try:
                saved = db.get_setting("home_filter")
                if saved and saved in ("All", "Tidal", "Local"):
                    for btn in self._filter_buttons:
                        btn.handler_block_by_func(self._on_filter_toggled)
                        btn.set_active(btn.get_label() == saved)
                        btn.handler_unblock_by_func(self._on_filter_toggled)
            except Exception:
                pass
        try:
            from auxen.models import Source

            all_tracks = db.get_all_tracks()
            local_tracks = db.get_tracks_by_source(Source.LOCAL)
            tidal_tracks = db.get_tracks_by_source(Source.TIDAL)

            total = len(all_tracks)
            local_count = len(local_tracks)
            tidal_count = len(tidal_tracks)

            self.update_stats(total, tidal_count, local_count)

            # Recently added — update the grid if we have data.
            # Fetch more tracks than needed so deduplication still fills 12.
            recently_added = db.get_recently_added(limit=120)
            if recently_added:
                seen: set[tuple[str, str, str]] = set()
                deduped = []
                for track in recently_added:
                    key = (track.album or track.title, track.artist, track.source.value)
                    if key not in seen:
                        seen.add(key)
                        deduped.append(track)
                    if len(deduped) == 24:
                        break

                self._clear_flow_box(self._album_grid)
                for track in deduped:
                    card = _make_album_card(
                        title=track.album or track.title,
                        artist=track.artist,
                        source=track.source.value,
                    )
                    self._attach_artist_click_gesture(card)
                    self._attach_album_context_gesture(card)
                    self._attach_play_button(card)
                    self._attach_drag_source_to_album_card(card)
                    self._album_grid.append(card)
                    # Load album art asynchronously
                    self.load_album_art_for_card(card, track)

            # Recently played — update the list if we have data
            recently_played = db.get_recently_played(limit=8)
            if recently_played:
                self._clear_list_box(self._recent_list)
                for track in recently_played:
                    play_cb = None
                    if self._on_play_track is not None:
                        play_cb = lambda _td, t=track: self._on_play_track(t)
                    artist_cb = None
                    if self._on_artist_clicked is not None:
                        artist_cb = lambda _a, a=track.artist: self._on_artist_clicked(a)
                    row = _make_recently_played_row(
                        title=track.title,
                        artist=track.artist,
                        duration=_format_duration(track.duration),
                        source=track.source.value,
                        track=track,
                        on_play_clicked=play_cb,
                        on_artist_clicked=artist_cb,
                    )
                    self._attach_context_gesture(row, track)
                    self._attach_drag_source_to_row(row, track)
                    self._recent_list.append(row)
                    self._load_row_art(row, track)
            # Re-apply the active filter after rebuilding content
            self._reapply_active_filter()
        except Exception:
            logger.warning("Failed to refresh home page", exc_info=True)

    def _reapply_active_filter(self) -> None:
        """Re-apply the currently active filter pill."""
        for btn in self._filter_buttons:
            if btn.get_active():
                self._apply_filter(btn.get_label())
                return

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

        request_token = object()
        child._art_request_token = request_token  # type: ignore[attr-defined]

        # Fetch at logical_size * scale_factor for crisp rendering on HiDPI.
        scale = child.get_scale_factor() or 1
        art_px = 160 * scale

        # Fast path: use cached texture immediately (avoids flicker on sort change)
        cached_texture = art_service.get_texture_for_track(track, art_px, art_px)
        if cached_texture is not None:
            art_image.set_from_paintable(cached_texture)
            art_image.set_visible(True)
            art_icon.set_visible(False)
            return

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            # Guard: skip if the card was recycled during async fetch
            if getattr(child, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = art_service.get_or_create_texture(track, pixbuf, art_px, art_px)
                if texture is not None:
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)

        art_service.get_art_async(track, _on_art_loaded, width=art_px, height=art_px)

    def _load_row_art(self, row: Gtk.ListBoxRow, track) -> None:
        """Load album art asynchronously for a recently-played row."""
        art_service = getattr(self, "_album_art_service", None)
        if art_service is None or track is None:
            return

        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        scale = row.get_scale_factor() or 1
        art_px = 48 * scale

        # Fast path: use cached texture immediately
        cached_texture = art_service.get_texture_for_track(track, art_px, art_px)
        if cached_texture is not None:
            art_image.set_from_paintable(cached_texture)
            art_image.set_visible(True)
            art_icon.set_visible(False)
            art_box = getattr(row, "_art_box", None)
            if art_box is not None:
                art_box.remove_css_class("album-art-placeholder")
            return

        def _on_art_loaded(pixbuf: GdkPixbuf.Pixbuf | None) -> None:
            if getattr(row, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = art_service.get_or_create_texture(track, pixbuf, art_px, art_px)
                if texture is not None:
                    art_image.set_from_paintable(texture)
                    art_image.set_visible(True)
                    art_icon.set_visible(False)
                    art_box = getattr(row, "_art_box", None)
                    if art_box is not None:
                        art_box.remove_css_class("album-art-placeholder")

        art_service.get_art_async(
            track, _on_art_loaded, width=art_px, height=art_px
        )

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
            halign=Gtk.Align.START,
            valign=Gtk.Align.CENTER,
        )
        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(20)
        icon.set_halign(Gtk.Align.CENTER)
        icon.set_valign(Gtk.Align.CENTER)
        icon.set_hexpand(True)
        icon.set_vexpand(True)
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

    def set_content_width(self, width: int) -> None:
        """Adjust margins and card sizing based on available content width."""
        if width == self._content_width:
            return
        self._content_width = width
        if width < 500:
            self._root.set_margin_start(8)
            self._root.set_margin_end(8)
            self._root.set_margin_top(12)
            self._root.set_spacing(16)
        else:
            self._root.set_margin_start(16)
            self._root.set_margin_end(16)
            self._root.set_margin_top(24)
            self._root.set_spacing(24)

    def set_callbacks(
        self,
        on_album_clicked: Callable[[str, str], None] | None = None,
        on_artist_clicked: Callable[[str], None] | None = None,
        on_play_album: Callable[[str, str], None] | None = None,
        on_play_track: Callable | None = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_album_clicked:
            Called with (album_name, artist) when an album card is clicked.
        on_artist_clicked:
            Called with (artist_name) when an artist label is clicked.
        on_play_album:
            Called with (album_name, artist) when the play button on an
            album card is clicked.
        on_play_track:
            Called with (track) when a track play button is clicked.
        """
        self._on_album_clicked = on_album_clicked
        self._on_artist_clicked = on_artist_clicked
        self._on_play_album = on_play_album
        self._on_play_track = on_play_track

    # ------------------------------------------------------------------
    # Scroll position persistence
    # ------------------------------------------------------------------

    def get_scroll_position(self) -> float:
        """Return the current vertical scroll position."""
        return self.get_vadjustment().get_value()

    def set_scroll_position(self, value: float) -> None:
        """Restore a previously saved scroll position."""
        self.get_vadjustment().set_value(value)

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

    def set_album_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for the album right-click context menu.

        Parameters
        ----------
        callbacks:
            Dict of album context menu action callbacks.
        get_playlists:
            Callable returning current list of user playlists.
        """
        self._album_context_callbacks = callbacks
        self._get_album_playlists = get_playlists

    def _attach_drag_source_to_row(
        self, row: Gtk.ListBoxRow, track
    ) -> None:
        """Attach a DragSource to a track row for drag-to-playlist."""
        if track is None:
            return
        track_id = getattr(track, "id", None)
        if track_id is None:
            return

        drag_source = Gtk.DragSource.new()
        drag_source.set_actions(Gdk.DragAction.COPY)

        def _on_prepare(_src, _x, _y, tid=track_id):
            value = GObject.Value(GObject.TYPE_STRING, str(tid))
            return Gdk.ContentProvider.new_for_value(value)

        drag_source.connect("prepare", _on_prepare)
        row.add_controller(drag_source)

    def _attach_drag_source_to_album_card(
        self, child: Gtk.FlowBoxChild
    ) -> None:
        """Attach a DragSource to an album card for drag-to-playlist.

        Dragging an album card serializes all track IDs from that album
        as a comma-separated string. The track IDs are looked up from
        the database when the drag begins.
        """
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if album_title is None or album_artist is None:
            return

        drag_source = Gtk.DragSource.new()
        drag_source.set_actions(Gdk.DragAction.COPY)

        def _on_prepare(
            _src, _x, _y, a=album_title, ar=album_artist
        ):
            # Look up track IDs from the database
            db = getattr(self, "_home_db", None)
            if db is None:
                return None
            try:
                tracks = db.get_tracks_by_album(a, ar)
                if not tracks:
                    return None
                ids_str = ",".join(
                    str(t.id) for t in tracks if t.id is not None
                )
                if not ids_str:
                    return None
                value = GObject.Value(GObject.TYPE_STRING, ids_str)
                return Gdk.ContentProvider.new_for_value(value)
            except Exception:
                logger.warning(
                    "Failed to get album tracks for drag",
                    exc_info=True,
                )
                return None

        drag_source.connect("prepare", _on_prepare)
        child.add_controller(drag_source)

    def _attach_play_button(self, child: Gtk.FlowBoxChild) -> None:
        """Wire the play button on an album card to play the album."""
        play_btn = getattr(child, "_play_btn", None)
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if play_btn is None or album_title is None or album_artist is None:
            return

        def _on_play_clicked(
            _btn, a=album_title, ar=album_artist
        ):
            if self._on_play_album is not None:
                self._on_play_album(a, ar)

        play_btn.connect("clicked", _on_play_clicked)

    def _attach_artist_click_gesture(self, child: Gtk.FlowBoxChild) -> None:
        """Attach a click gesture to the artist label on an album card."""
        artist_label = getattr(child, "_artist_label", None)
        album_artist = getattr(child, "_album_artist", None)
        if artist_label is None or album_artist is None:
            return

        gesture = Gtk.GestureClick.new()
        gesture.set_button(1)  # primary button only

        def _on_artist_clicked(
            g, n_press, _x, _y, artist=album_artist
        ):
            if n_press != 1:
                return
            # Claim the gesture so the FlowBox child-activated signal
            # doesn't also fire (prevents double-navigation).
            g.set_state(Gtk.EventSequenceState.CLAIMED)
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

        def _on_right_click(g, n_press, x, y, trk=track):
            if n_press != 1:
                return
            self._show_track_context_menu(row, x, y, trk)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_track_context_menu(
        self, widget: Gtk.Widget, x: float, y: float, track
    ) -> None:
        """Create and display a context menu for a track."""
        if self._context_callbacks is None:
            return

        is_favorite = False
        playlists = []
        if self._get_playlists is not None:
            playlists = self._get_playlists()

        # Build track-specific callbacks that capture the track reference
        _noop = lambda *_args: None
        callbacks = {
            "on_play": lambda t=track: self._context_callbacks.get("on_play", _noop)(t),
            "on_play_next": lambda t=track: self._context_callbacks.get("on_play_next", _noop)(t),
            "on_add_to_queue": lambda t=track: self._context_callbacks.get("on_add_to_queue", _noop)(t),
            "on_add_to_playlist": lambda pid, t=track: self._context_callbacks.get("on_add_to_playlist", _noop)(t, pid),
            "on_new_playlist": lambda t=track: self._context_callbacks.get("on_new_playlist", _noop)(t),
            "on_toggle_favorite": lambda t=track: self._context_callbacks.get("on_toggle_favorite", _noop)(t),
            "on_go_to_album": lambda t=track: self._context_callbacks.get("on_go_to_album", _noop)(t),
            "on_go_to_artist": lambda t=track: self._context_callbacks.get("on_go_to_artist", _noop)(t),
            "on_track_radio": lambda t=track: self._context_callbacks.get("on_track_radio", _noop)(t),
            "on_view_lyrics": lambda t=track: self._context_callbacks.get("on_view_lyrics", _noop)(t),
            "on_credits": lambda t=track: self._context_callbacks.get("on_credits", _noop)(t),
        }

        track_data = {
            "id": getattr(track, "id", None),
            "title": getattr(track, "title", ""),
            "artist": getattr(track, "artist", ""),
            "album": getattr(track, "album", ""),
            "source": getattr(track, "source", None),
            "source_id": getattr(track, "source_id", None),
            "is_favorite": is_favorite,
        }

        self._current_menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    # ------------------------------------------------------------------
    # Album context menu helpers
    # ------------------------------------------------------------------

    def _attach_album_context_gesture(
        self, child: Gtk.FlowBoxChild
    ) -> None:
        """Attach a right-click gesture to an album card."""
        if self._album_context_callbacks is None:
            return

        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if album_title is None or album_artist is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(
            g, n_press, x, y,
            a=album_title, ar=album_artist
        ):
            if n_press != 1:
                return
            self._show_album_context_menu(child, x, y, a, ar)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        child.add_controller(gesture)

    def _show_album_context_menu(
        self,
        widget: Gtk.Widget,
        x: float,
        y: float,
        album_name: str,
        artist: str,
    ) -> None:
        """Create and display a context menu for an album card."""
        if self._album_context_callbacks is None:
            return

        playlists = []
        if self._get_album_playlists is not None:
            playlists = self._get_album_playlists()

        cbs = self._album_context_callbacks
        _noop = lambda *_args: None
        callbacks = {
            "on_play_album": lambda a=album_name, ar=artist: cbs.get("on_play_album", _noop)(a, ar),
            "on_play_album_next": lambda a=album_name, ar=artist: cbs.get("on_play_album_next", _noop)(a, ar),
            "on_add_album_to_queue": lambda a=album_name, ar=artist: cbs.get("on_add_album_to_queue", _noop)(a, ar),
            "on_add_to_playlist": lambda pid, a=album_name, ar=artist: cbs.get("on_add_to_playlist", _noop)(a, ar, pid),
            "on_new_playlist": lambda a=album_name, ar=artist: cbs.get("on_new_playlist", _noop)(a, ar),
            "on_add_to_favorites": lambda a=album_name, ar=artist: cbs.get("on_add_to_favorites", _noop)(a, ar),
            "on_go_to_artist": lambda a=album_name, ar=artist: cbs.get("on_go_to_artist", _noop)(a, ar),
            "on_shuffle_album": lambda a=album_name, ar=artist: cbs.get("on_shuffle_album", _noop)(a, ar),
        }

        album_data = {
            "album": album_name,
            "artist": artist,
        }

        self._current_menu = AlbumContextMenu(
            album_data=album_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    def _on_filter_toggled(self, toggled_btn: Gtk.ToggleButton) -> None:
        """Enforce radio-button behavior and apply source filter."""
        if not toggled_btn.get_active():
            any_active = any(b.get_active() for b in self._filter_buttons)
            if not any_active:
                toggled_btn.set_active(True)
            return

        # Deactivate all other buttons
        for btn in self._filter_buttons:
            if btn is not toggled_btn and btn.get_active():
                btn.set_active(False)

        label = toggled_btn.get_label()
        self._apply_filter(label)
        # Persist active filter to DB
        db = getattr(self, "_home_db", None)
        if db is not None:
            try:
                db.set_setting("home_filter", label)
            except Exception:
                pass

    def _apply_filter(self, filter_label: str) -> None:
        """Filter album grid and recently played list by source."""
        if not hasattr(self, "_album_grid"):
            return
        source_key = filter_label.lower()  # "all", "tidal", or "local"

        # Filter album grid via FlowBox filter function
        if source_key == "all":
            self._album_grid.set_filter_func(None)
        else:
            self._album_grid.set_filter_func(
                lambda child, s=source_key: getattr(child, "_source", "").lower() == s
            )

        # Filter recently played rows by visibility
        row = self._recent_list.get_first_child()
        while row is not None:
            next_row = row.get_next_sibling()
            track = getattr(row, "_track_data", None)
            if source_key == "all" or track is None:
                row.set_visible(True)
            else:
                track_source = getattr(track, "source", None)
                if track_source is not None:
                    row.set_visible(track_source.value.lower() == source_key)
                else:
                    row.set_visible(True)
            row = next_row
