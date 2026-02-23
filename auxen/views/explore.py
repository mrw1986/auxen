"""Tidal Explore page — discover new releases, top tracks, and genres."""

from __future__ import annotations

import logging
import threading
from typing import Any, Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import GLib, Gtk, Pango

logger = logging.getLogger(__name__)


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    if seconds is None or seconds <= 0:
        return "0:00"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


class ExploreView(Gtk.ScrolledWindow):
    """Scrollable Tidal discovery page with new releases, top tracks, and genres."""

    __gtype_name__ = "ExploreView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        self._tidal_provider: Any = None
        self._on_album_clicked: Optional[Callable] = None
        self._on_play_track: Optional[Callable] = None
        self._on_login: Optional[Callable] = None
        self._refresh_generation: int = 0

        # Root container
        self._root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        self._root.set_margin_top(24)
        self._root.set_margin_bottom(24)
        self._root.set_margin_start(32)
        self._root.set_margin_end(32)

        # ---- 1. Header ----
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header_box.add_css_class("explore-header")

        title_label = Gtk.Label(label="Explore Tidal")
        title_label.set_xalign(0)
        title_label.add_css_class("greeting-label")
        header_box.append(title_label)

        tidal_badge = Gtk.Label(label="TIDAL")
        tidal_badge.add_css_class("nav-badge-tidal")
        tidal_badge.set_valign(Gtk.Align.CENTER)
        header_box.append(tidal_badge)

        self._root.append(header_box)

        # ---- 2. Login prompt (shown when not connected) ----
        self._login_prompt = self._build_login_prompt()
        self._root.append(self._login_prompt)

        # ---- 3. Content area (shown when connected) ----
        self._content_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        self._content_box.set_visible(False)

        # -- Genre pills --
        self._genre_header = Gtk.Label(label="Genres")
        self._genre_header.set_xalign(0)
        self._genre_header.add_css_class("section-header")
        self._content_box.append(self._genre_header)

        self._genre_flow = Gtk.FlowBox()
        self._genre_flow.set_homogeneous(False)
        self._genre_flow.set_min_children_per_line(3)
        self._genre_flow.set_max_children_per_line(10)
        self._genre_flow.set_column_spacing(8)
        self._genre_flow.set_row_spacing(8)
        self._genre_flow.set_selection_mode(Gtk.SelectionMode.NONE)
        self._content_box.append(self._genre_flow)

        # -- New Releases section --
        releases_header = Gtk.Label(label="New Releases")
        releases_header.set_xalign(0)
        releases_header.add_css_class("section-header")
        self._content_box.append(releases_header)

        self._releases_grid = Gtk.FlowBox()
        self._releases_grid.set_homogeneous(True)
        self._releases_grid.set_min_children_per_line(2)
        self._releases_grid.set_max_children_per_line(6)
        self._releases_grid.set_column_spacing(16)
        self._releases_grid.set_row_spacing(16)
        self._releases_grid.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self._releases_grid.connect(
            "child-activated", self._on_release_card_activated
        )
        self._content_box.append(self._releases_grid)

        # -- Top Tracks section --
        tracks_header = Gtk.Label(label="Top Tracks")
        tracks_header.set_xalign(0)
        tracks_header.add_css_class("section-header")
        self._content_box.append(tracks_header)

        self._tracks_list = Gtk.ListBox()
        self._tracks_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._tracks_list.add_css_class("boxed-list")
        self._content_box.append(self._tracks_list)

        self._root.append(self._content_box)

        # ---- 4. Loading spinner (shown during refresh) ----
        self._spinner_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._spinner_box.set_visible(False)
        self._spinner_box.set_margin_top(48)

        spinner = Gtk.Spinner()
        spinner.set_size_request(32, 32)
        spinner.start()
        self._spinner_box.append(spinner)

        loading_label = Gtk.Label(label="Loading content from Tidal...")
        loading_label.add_css_class("dim-label")
        self._spinner_box.append(loading_label)

        self._root.append(self._spinner_box)

        self.set_child(self._root)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_tidal_provider(self, tidal_provider: Any) -> None:
        """Wire the Tidal provider for fetching explore content."""
        self._tidal_provider = tidal_provider

    def set_callbacks(
        self,
        on_album_clicked: Optional[Callable] = None,
        on_play_track: Optional[Callable] = None,
        on_login: Optional[Callable] = None,
    ) -> None:
        """Set callback functions for user actions.

        Parameters
        ----------
        on_album_clicked:
            Called with (album_name, artist) when an album card is clicked.
        on_play_track:
            Called with a Track when a play button is clicked.
        on_login:
            Called when the user clicks the login button.
        """
        self._on_album_clicked = on_album_clicked
        self._on_play_track = on_play_track
        self._on_login = on_login

    def refresh(self) -> None:
        """Reload content from Tidal.

        Shows the login prompt if not connected, otherwise fetches
        new releases, top tracks, and genres.
        """
        # Bump generation before auth check to invalidate any in-flight
        # fetch that may still be pending from a previous refresh.
        self._refresh_generation += 1

        if self._tidal_provider is None or not self._tidal_provider.is_logged_in:
            self._show_login_state()
            return

        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(True)

        gen = self._refresh_generation
        thread = threading.Thread(
            target=self._fetch_content_thread, args=(gen,), daemon=True
        )
        thread.start()

    # ------------------------------------------------------------------
    # Internal: build widgets
    # ------------------------------------------------------------------

    def _build_login_prompt(self) -> Gtk.Box:
        """Build the login prompt card shown when Tidal is not connected."""
        prompt = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        prompt.add_css_class("explore-login-prompt")
        prompt.set_margin_top(48)
        prompt.set_margin_bottom(48)

        icon = Gtk.Image.new_from_icon_name("network-wireless-symbolic")
        icon.set_pixel_size(64)
        icon.set_opacity(0.4)
        prompt.append(icon)

        heading = Gtk.Label(label="Connect to Tidal")
        heading.add_css_class("title-1")
        prompt.append(heading)

        description = Gtk.Label(
            label="Log in to your Tidal account to discover new releases,\n"
            "top tracks, and explore genres."
        )
        description.add_css_class("dim-label")
        description.set_justify(Gtk.Justification.CENTER)
        prompt.append(description)

        login_btn = Gtk.Button(label="Log In to Tidal")
        login_btn.add_css_class("suggested-action")
        login_btn.add_css_class("pill")
        login_btn.set_halign(Gtk.Align.CENTER)
        login_btn.connect("clicked", self._on_login_clicked)
        prompt.append(login_btn)

        return prompt

    def _make_album_card(
        self, title: str, artist: str, tidal_id: str
    ) -> Gtk.FlowBoxChild:
        """Build a single album card for the new releases grid."""
        card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=6,
        )
        card.add_css_class("album-card")

        # Album art placeholder with overlay badge
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

        badge = Gtk.Label(label="Tidal")
        badge.add_css_class("source-badge-tidal")
        badge.set_halign(Gtk.Align.START)
        badge.set_valign(Gtk.Align.START)
        badge.set_margin_top(8)
        badge.set_margin_start(8)
        overlay.add_overlay(badge)

        card.append(overlay)

        # Title
        title_label = Gtk.Label(label=title)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(4)
        title_label.set_margin_end(4)
        card.append(title_label)

        # Artist
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
        # Store data for click handling
        child._album_title = title  # type: ignore[attr-defined]
        child._album_artist = artist  # type: ignore[attr-defined]
        child._tidal_id = tidal_id  # type: ignore[attr-defined]
        return child

    def _make_track_row(self, track: Any, index: int) -> Gtk.ListBoxRow:
        """Build a row for the top tracks list."""
        row_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        row_box.add_css_class("explore-track-row")
        row_box.set_margin_top(4)
        row_box.set_margin_bottom(4)
        row_box.set_margin_start(4)
        row_box.set_margin_end(4)

        # Track number
        num_label = Gtk.Label(label=str(index + 1))
        num_label.add_css_class("album-detail-track-number")
        num_label.set_valign(Gtk.Align.CENTER)
        row_box.append(num_label)

        # Play button
        play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        play_btn.add_css_class("flat")
        play_btn.add_css_class("now-playing-control-btn")
        play_btn.set_valign(Gtk.Align.CENTER)
        play_btn.connect("clicked", self._on_track_play_clicked, track)
        row_box.append(play_btn)

        # Small album art placeholder
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

        # Title + Artist column
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_hexpand(True)
        text_box.set_valign(Gtk.Align.CENTER)

        title_lbl = Gtk.Label(label=track.title)
        title_lbl.set_xalign(0)
        title_lbl.set_ellipsize(Pango.EllipsizeMode.END)
        title_lbl.add_css_class("body")
        text_box.append(title_lbl)

        artist_lbl = Gtk.Label(label=track.artist)
        artist_lbl.set_xalign(0)
        artist_lbl.set_ellipsize(Pango.EllipsizeMode.END)
        artist_lbl.add_css_class("caption")
        artist_lbl.add_css_class("dim-label")
        text_box.append(artist_lbl)

        row_box.append(text_box)

        # Duration
        dur_label = Gtk.Label(label=_format_duration(track.duration))
        dur_label.add_css_class("caption")
        dur_label.add_css_class("dim-label")
        dur_label.set_valign(Gtk.Align.CENTER)
        row_box.append(dur_label)

        # Quality badge
        quality_badge = Gtk.Label(label=track.quality_label)
        quality_badge.add_css_class("now-playing-quality-badge")
        quality_badge.set_valign(Gtk.Align.CENTER)
        row_box.append(quality_badge)

        row = Gtk.ListBoxRow()
        row.set_child(row_box)
        return row

    def _make_genre_pill(self, genre_name: str) -> Gtk.FlowBoxChild:
        """Build a genre pill button."""
        btn = Gtk.Button(label=genre_name)
        btn.add_css_class("explore-genre-pill")
        btn.connect("clicked", self._on_genre_clicked, genre_name)

        child = Gtk.FlowBoxChild()
        child.set_child(btn)
        return child

    # ------------------------------------------------------------------
    # Internal: state management
    # ------------------------------------------------------------------

    def _show_login_state(self) -> None:
        """Show the login prompt and hide content."""
        self._login_prompt.set_visible(True)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(False)

    def _show_content_state(self) -> None:
        """Show the content area and hide the login prompt."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(True)
        self._spinner_box.set_visible(False)

    def _fetch_content_thread(self, gen: int) -> None:
        """Fetch explore content from Tidal in a background thread."""
        if self._tidal_provider is None:
            GLib.idle_add(self._show_login_state)
            return

        try:
            genres = self._tidal_provider.get_genres()
            releases = self._tidal_provider.get_new_releases(limit=12)
            if not releases:
                releases = self._tidal_provider.get_featured_albums(limit=12)
            tracks = self._tidal_provider.get_top_tracks(limit=20)

            if gen != self._refresh_generation:
                return

            def _apply_results() -> bool:
                if gen != self._refresh_generation:
                    return False
                self._populate_genres(genres)
                self._populate_releases(releases)
                self._populate_tracks(tracks)
                self._show_content_state()
                has_genres = len(genres) > 0
                self._genre_header.set_visible(has_genres)
                self._genre_flow.set_visible(has_genres)
                return False

            GLib.idle_add(_apply_results)

        except Exception:
            logger.warning("Failed to fetch explore content", exc_info=True)
            if gen == self._refresh_generation:
                GLib.idle_add(self._show_login_state)

    def _populate_genres(self, genres: list[str]) -> None:
        """Fill the genre flow box with pill buttons."""
        self._clear_flow_box(self._genre_flow)
        for genre in genres:
            self._genre_flow.append(self._make_genre_pill(genre))

    def _populate_releases(self, releases: list[dict]) -> None:
        """Fill the new releases grid with album cards."""
        self._clear_flow_box(self._releases_grid)
        for album in releases:
            self._releases_grid.append(
                self._make_album_card(
                    title=album.get("title", "Unknown Album"),
                    artist=album.get("artist", "Unknown Artist"),
                    tidal_id=album.get("tidal_id", ""),
                )
            )

    def _populate_tracks(self, tracks: list) -> None:
        """Fill the top tracks list with track rows."""
        self._clear_list_box(self._tracks_list)
        for i, track in enumerate(tracks):
            self._tracks_list.append(self._make_track_row(track, i))

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_login_clicked(self, _btn: Gtk.Button) -> None:
        """Handle login button click."""
        if self._on_login is not None:
            self._on_login()

    def _on_release_card_activated(
        self,
        _flow_box: Gtk.FlowBox,
        child: Gtk.FlowBoxChild,
    ) -> None:
        """Handle an album card click in the new releases grid."""
        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if (
            album_title is not None
            and album_artist is not None
            and self._on_album_clicked is not None
        ):
            self._on_album_clicked(album_title, album_artist)

    def _on_track_play_clicked(self, _btn: Gtk.Button, track: Any) -> None:
        """Handle play button click on a track row."""
        if self._on_play_track is not None:
            self._on_play_track(track)

    def _on_genre_clicked(self, _btn: Gtk.Button, genre_name: str) -> None:
        """Handle genre pill click (placeholder for future filtering)."""
        logger.debug("Genre clicked: %s", genre_name)

    # ------------------------------------------------------------------
    # Utility
    # ------------------------------------------------------------------

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
