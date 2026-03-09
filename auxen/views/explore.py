"""Tidal Explore page — discover new releases, top tracks, and genres."""

from __future__ import annotations

import logging
import threading
from typing import Any, Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gdk, GLib, Gtk, Pango

from auxen.views.context_menu import AlbumContextMenu, TrackContextMenu
from auxen.views.widgets import (
    DragScrollHelper,
    make_standard_track_row,
    make_tidal_connect_prompt,
    make_tidal_source_badge,
)

logger = logging.getLogger(__name__)


class ExploreView(Gtk.ScrolledWindow):
    """Scrollable Tidal discovery page with new releases, top tracks, and genres."""

    __gtype_name__ = "ExploreView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        self._tidal_provider: Any = None
        self._on_album_clicked: Optional[Callable] = None
        self._on_play_track: Optional[Callable] = None
        self._on_login: Optional[Callable] = None
        self._refresh_generation: int = 0

        # Context menu state
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None
        self._album_context_callbacks: Optional[dict] = None
        self._get_album_playlists: Optional[Callable] = None
        self._current_menu: object = None

        # Genre filter state
        self._active_genre: str | None = None
        self._genre_filter_generation: int = 0
        self._unfiltered_releases: list[dict] = []
        self._unfiltered_tracks: list = []

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

        tidal_badge = make_tidal_source_badge(
            label_text="TIDAL",
            css_class="nav-badge-tidal",
            icon_size=12,
        )
        tidal_badge.set_valign(Gtk.Align.CENTER)
        tidal_badge.set_tooltip_text("Streaming from Tidal")
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
        self._releases_grid.set_min_children_per_line(1)
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

        # ---- 4. Error state (shown on fetch failure) ----
        self._error_state = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._error_state.set_visible(False)
        self._error_state.set_margin_top(48)
        self._error_state.set_margin_bottom(48)

        error_icon = Gtk.Image.new_from_icon_name(
            "dialog-warning-symbolic"
        )
        error_icon.set_pixel_size(64)
        error_icon.set_opacity(0.4)
        self._error_state.append(error_icon)

        self._error_heading = Gtk.Label(
            label="Unable to load content"
        )
        self._error_heading.add_css_class("title-2")
        self._error_state.append(self._error_heading)

        self._error_desc = Gtk.Label(
            label="A network or Tidal API error occurred.\n"
            "Check your connection and try again."
        )
        self._error_desc.add_css_class("dim-label")
        self._error_desc.set_justify(Gtk.Justification.CENTER)
        self._error_state.append(self._error_desc)

        retry_btn = Gtk.Button(label="Retry")
        retry_btn.set_halign(Gtk.Align.CENTER)
        retry_btn.add_css_class("suggested-action")
        retry_btn.connect("clicked", lambda _btn: self.refresh())
        self._error_state.append(retry_btn)

        self._root.append(self._error_state)

        # ---- 5. Loading spinner (shown during refresh) ----
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

    def set_album_art_service(self, art_service: Any) -> None:
        """Set the AlbumArtService instance for loading album art."""
        self._album_art_service = art_service

    def set_content_width(self, width: int) -> None:
        """Adjust margins based on available content width."""
        if width < 500:
            self._root.set_margin_start(12)
            self._root.set_margin_end(12)
            self._root.set_margin_top(12)
            self._root.set_spacing(16)
        else:
            self._root.set_margin_start(32)
            self._root.set_margin_end(32)
            self._root.set_margin_top(24)
            self._root.set_spacing(24)

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

    def set_context_callbacks(
        self, callbacks: dict, get_playlists: Callable,
    ) -> None:
        """Set callback functions for the track right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def set_album_context_callbacks(
        self, callbacks: dict, get_playlists: Callable,
    ) -> None:
        """Set callback functions for the album right-click context menu."""
        self._album_context_callbacks = callbacks
        self._get_album_playlists = get_playlists

    def refresh(self) -> None:
        """Reload content from Tidal.

        Shows the login prompt if not connected, otherwise fetches
        new releases, top tracks, and genres.
        """
        # Bump both generations before auth check to invalidate any
        # in-flight fetch (main or genre) from a previous refresh.
        self._refresh_generation += 1
        self._genre_filter_generation += 1

        if self._tidal_provider is None or not self._tidal_provider.is_logged_in:
            self._show_login_state()
            return

        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._error_state.set_visible(False)
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
        return make_tidal_connect_prompt(
            css_class="explore-login-prompt",
            icon_name="tidal-symbolic",
            heading_text="Connect to Tidal",
            description_text=(
                "Log in to your Tidal account to discover new releases,\n"
                "top tracks, and explore genres."
            ),
            button_text="Log In to Tidal",
            on_login_clicked=self._on_login_clicked_cb,
        )

    def _make_album_card(
        self, title: str, artist: str, tidal_id: str, cover_url: str = ""
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
        art_box.set_vexpand(False)

        # Placeholder icon (shown when no art is available)
        art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
        art_icon.set_pixel_size(48)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        # Album art image (hidden until loaded)
        art_image = Gtk.Image()
        art_image.set_pixel_size(160)
        art_image.set_halign(Gtk.Align.CENTER)
        art_image.set_valign(Gtk.Align.CENTER)
        art_image.add_css_class("album-card-art-image")
        art_image.set_visible(False)
        art_box.append(art_image)

        overlay.set_child(art_box)

        badge = make_tidal_source_badge(
            label_text="Tidal",
            css_class="source-badge-tidal",
            icon_size=10,
        )
        badge.set_halign(Gtk.Align.END)
        badge.set_valign(Gtk.Align.START)
        badge.set_margin_top(8)
        badge.set_margin_end(8)
        badge.set_tooltip_text("Streaming from Tidal")
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
        play_btn.connect(
            "clicked", self._on_card_play_btn_clicked, title, artist
        )
        overlay.add_overlay(play_btn)

        card.append(overlay)

        # Title
        title_label = Gtk.Label(label=title)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(18)
        title_label.add_css_class("body")
        title_label.set_margin_start(6)
        title_label.set_margin_end(6)
        card.append(title_label)

        # Artist
        artist_label = Gtk.Label(label=artist)
        artist_label.set_xalign(0)
        artist_label.set_ellipsize(Pango.EllipsizeMode.END)
        artist_label.set_max_width_chars(18)
        artist_label.add_css_class("caption")
        artist_label.add_css_class("dim-label")
        artist_label.set_margin_start(6)
        artist_label.set_margin_end(6)
        card.append(artist_label)

        child = Gtk.FlowBoxChild()
        child.set_child(card)
        # Store data for click handling
        child._album_title = title  # type: ignore[attr-defined]
        child._album_artist = artist  # type: ignore[attr-defined]
        child._tidal_id = tidal_id  # type: ignore[attr-defined]
        # Store art widget references for async loading
        child._art_icon = art_icon  # type: ignore[attr-defined]
        child._art_image = art_image  # type: ignore[attr-defined]
        child._cover_url = cover_url  # type: ignore[attr-defined]
        return child

    def _make_track_row(self, track: Any, index: int) -> Gtk.ListBoxRow:
        """Build a row for the top tracks list using the shared widget."""
        row = make_standard_track_row(
            track,
            index=index,
            show_art=True,
            show_play_btn=True,
            show_source_badge=False,
            show_quality_badge=True,
            show_duration=True,
            art_size=48,
            css_class="explore-track-row",
            on_play_clicked=lambda t: self._on_track_play_clicked(None, t),
        )
        row._track = track  # type: ignore[attr-defined]
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
        self._error_state.set_visible(False)

    def _show_content_state(self) -> None:
        """Show the content area and hide the login prompt."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(True)
        self._spinner_box.set_visible(False)
        self._error_state.set_visible(False)

    def _show_error_state(self) -> None:
        """Show an error state with retry button (not login prompt)."""
        self._login_prompt.set_visible(False)
        self._content_box.set_visible(False)
        self._spinner_box.set_visible(False)
        self._error_state.set_visible(True)

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
                # Store unfiltered data for genre filtering
                self._unfiltered_releases = releases
                self._unfiltered_tracks = tracks
                self._active_genre = None

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
                GLib.idle_add(self._show_error_state)

    def _populate_genres(self, genres: list[str]) -> None:
        """Fill the genre flow box with pill buttons.

        Prepends an "All" pill that clears the genre filter.  The
        currently active genre pill (if any) is given the
        ``explore-genre-pill-active`` CSS class.
        """
        self._clear_flow_box(self._genre_flow)

        # "All" pill to clear the filter
        all_pill = self._make_genre_pill("All")
        all_btn = all_pill.get_child()
        if self._active_genre is None and all_btn is not None:
            all_btn.add_css_class("explore-genre-pill-active")
        self._genre_flow.append(all_pill)

        for genre in genres:
            pill = self._make_genre_pill(genre)
            if self._active_genre == genre:
                btn = pill.get_child()
                if btn is not None:
                    btn.add_css_class("explore-genre-pill-active")
            self._genre_flow.append(pill)

    def _populate_releases(self, releases: list[dict]) -> None:
        """Fill the new releases grid with album cards."""
        self._clear_flow_box(self._releases_grid)
        for album in releases:
            cover_url = album.get("cover_url", "") or ""
            child = self._make_album_card(
                title=album.get("title", "Unknown Album"),
                artist=album.get("artist", "Unknown Artist"),
                tidal_id=album.get("tidal_id", ""),
                cover_url=cover_url,
            )
            self._attach_album_context_gesture(child)
            self._releases_grid.append(child)
            self._load_card_art(child, cover_url)

    def _populate_tracks(self, tracks: list) -> None:
        """Fill the top tracks list with track rows."""
        self._clear_list_box(self._tracks_list)
        for i, track in enumerate(tracks):
            row = self._make_track_row(track, i)
            self._attach_context_gesture(row, track)
            self._tracks_list.append(row)
            # Load track art from the Track's album_art_url
            art_url = getattr(track, "album_art_url", None) or ""
            self._load_row_art(row, art_url)

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _on_login_clicked_cb(self) -> None:
        """Handle login button click (no-arg callback for shared prompt)."""
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

    def _on_card_play_btn_clicked(
        self, _btn: Gtk.Button, album_title: str, artist: str
    ) -> None:
        """Handle play button click on an album card hover overlay."""
        if self._on_album_clicked is not None:
            self._on_album_clicked(album_title, artist)

    def _on_track_play_clicked(self, _btn: Gtk.Button, track: Any) -> None:
        """Handle play button click on a track row."""
        if self._on_play_track is not None:
            self._on_play_track(track)

    # ------------------------------------------------------------------
    # Context menu helpers
    # ------------------------------------------------------------------

    def _attach_context_gesture(self, row: Gtk.ListBoxRow, track) -> None:
        """Attach a right-click gesture to a track row."""
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

        playlists = []
        if self._get_playlists is not None:
            playlists = self._get_playlists()

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
            "is_favorite": False,
        }

        self._current_menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    def _attach_album_context_gesture(self, child: Gtk.FlowBoxChild) -> None:
        """Attach a right-click gesture to an album card."""
        if self._album_context_callbacks is None:
            return

        album_title = getattr(child, "_album_title", None)
        album_artist = getattr(child, "_album_artist", None)
        if album_title is None or album_artist is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(g, n_press, x, y, a=album_title, ar=album_artist):
            if n_press != 1:
                return
            self._show_album_context_menu(child, x, y, a, ar)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        child.add_controller(gesture)

    def _show_album_context_menu(
        self, widget: Gtk.Widget, x: float, y: float,
        album_name: str, artist: str,
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

        album_data = {"album": album_name, "artist": artist}

        self._current_menu = AlbumContextMenu(
            album_data=album_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    def _on_genre_clicked(self, _btn: Gtk.Button, genre_name: str) -> None:
        """Handle genre pill click — filter displayed content by genre.

        Clicking the active genre or "All" clears the filter and
        restores the original unfiltered content.  Clicking a new
        genre searches Tidal for genre-specific content in a
        background thread.
        """
        # Toggle off: clicking the already-active genre or "All"
        if genre_name == "All" or genre_name == self._active_genre:
            self._active_genre = None
            # Invalidate any in-flight genre fetch so it won't overwrite
            self._genre_filter_generation += 1
            self._update_genre_pill_styles()
            self._spinner_box.set_visible(False)
            # Restore unfiltered content
            self._populate_releases(self._unfiltered_releases)
            self._populate_tracks(self._unfiltered_tracks)
            logger.debug("Genre filter cleared")
            return

        # Activate the new genre
        self._active_genre = genre_name
        self._update_genre_pill_styles()

        # Show loading state in the content area
        self._clear_flow_box(self._releases_grid)
        self._clear_list_box(self._tracks_list)
        self._spinner_box.set_visible(True)

        # Fetch genre-specific content in a background thread
        self._genre_filter_generation += 1
        gen = self._genre_filter_generation

        def _fetch_genre_content() -> None:
            if self._tidal_provider is None:
                def _restore_no_provider() -> bool:
                    self._spinner_box.set_visible(False)
                    self._active_genre = None
                    self._update_genre_pill_styles()
                    self._populate_releases(self._unfiltered_releases)
                    self._populate_tracks(self._unfiltered_tracks)
                    return False
                GLib.idle_add(_restore_no_provider)
                return

            try:
                # Search Tidal for the genre name to get matching content
                genre_tracks = self._tidal_provider.search(
                    genre_name, limit=20
                )

                # Search for albums matching the genre via public API
                genre_albums = self._tidal_provider.search_albums(
                    genre_name, limit=12
                )

                if gen != self._genre_filter_generation:
                    return

                def _apply_genre_results() -> bool:
                    if gen != self._genre_filter_generation:
                        return False
                    self._spinner_box.set_visible(False)
                    self._populate_releases(genre_albums)
                    self._populate_tracks(genre_tracks)
                    return False

                GLib.idle_add(_apply_genre_results)

            except Exception:
                logger.warning(
                    "Genre content fetch failed for %s",
                    genre_name,
                    exc_info=True,
                )
                if gen == self._genre_filter_generation:
                    def _restore_on_genre_error() -> bool:
                        if gen != self._genre_filter_generation:
                            return False
                        self._spinner_box.set_visible(False)
                        # Clear the failed genre filter and restore content
                        self._active_genre = None
                        self._update_genre_pill_styles()
                        self._populate_releases(self._unfiltered_releases)
                        self._populate_tracks(self._unfiltered_tracks)
                        return False
                    GLib.idle_add(_restore_on_genre_error)

        thread = threading.Thread(
            target=_fetch_genre_content, daemon=True
        )
        thread.start()

    def _update_genre_pill_styles(self) -> None:
        """Update CSS classes on genre pills to reflect the active filter."""
        idx = 0
        while True:
            child = self._genre_flow.get_child_at_index(idx)
            if child is None:
                break
            btn = child.get_child()
            if btn is not None:
                btn.remove_css_class("explore-genre-pill-active")
                pill_label = btn.get_label()
                if self._active_genre is None and pill_label == "All":
                    btn.add_css_class("explore-genre-pill-active")
                elif pill_label == self._active_genre:
                    btn.add_css_class("explore-genre-pill-active")
            idx += 1

    # ------------------------------------------------------------------
    # Art loading helpers
    # ------------------------------------------------------------------

    def _load_card_art(
        self, child: Gtk.FlowBoxChild, cover_url: str
    ) -> None:
        """Asynchronously load album art for a release card.

        Gracefully does nothing when no art service is set or the URL
        is empty, leaving the placeholder icon visible.
        """
        art_service = getattr(self, "_album_art_service", None)
        if art_service is None or not cover_url:
            return

        art_icon = getattr(child, "_art_icon", None)
        art_image = getattr(child, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        # Token guards against a stale callback updating the wrong card
        # if the grid is repopulated while a fetch is still in-flight.
        request_token = object()
        child._art_request_token = request_token  # type: ignore[attr-defined]

        def _on_art(pixbuf, _url=cover_url):
            if getattr(child, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)

        scale = child.get_scale_factor() or 1
        art_px = 160 * scale
        art_service.get_art_by_url_async(
            cover_url, _on_art, width=art_px, height=art_px
        )

    def _load_row_art(self, row: Gtk.ListBoxRow, cover_url: str) -> None:
        """Asynchronously load album art for a track row.

        Gracefully does nothing when no art service is set or the URL
        is empty, leaving the placeholder icon visible.
        """
        art_service = getattr(self, "_album_art_service", None)
        if art_service is None or not cover_url:
            return

        art_icon = getattr(row, "_art_icon", None)
        art_image = getattr(row, "_art_image", None)
        if art_icon is None or art_image is None:
            return

        request_token = object()
        row._art_request_token = request_token  # type: ignore[attr-defined]

        def _on_art(pixbuf, _url=cover_url):
            if getattr(row, "_art_request_token", None) is not request_token:
                return
            if pixbuf is not None:
                texture = Gdk.Texture.new_for_pixbuf(pixbuf)
                art_image.set_from_paintable(texture)
                art_image.set_visible(True)
                art_icon.set_visible(False)

        scale = row.get_scale_factor() or 1
        art_px = 48 * scale
        art_service.get_art_by_url_async(
            cover_url, _on_art, width=art_px, height=art_px
        )

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
