"""Main application window for Auxen."""

from __future__ import annotations

import logging

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk

from auxen.lyrics import LyricsService
from auxen.views.album_detail import AlbumDetailView
from auxen.views.explore import ExploreView
from auxen.views.favorites import FavoritesView
from auxen.views.home import HomePage
from auxen.views.library import LibraryView
from auxen.views.lyrics_panel import LyricsPanel
from auxen.views.now_playing import NowPlayingBar
from auxen.views.playlist_view import PlaylistView
from auxen.views.search import SearchView
from auxen.views.settings import AuxenSettings
from auxen.views.sidebar import AuxenSidebar

logger = logging.getLogger(__name__)

# Page definitions: (name, display_title)
_PAGES: list[tuple[str, str]] = [
    ("home", "Home"),
    ("search", "Search"),
    ("library", "Library"),
    ("explore", "Explore"),
    ("mixes", "Mixes"),
    ("favorites", "Favorites"),
]


class AuxenWindow(Adw.ApplicationWindow):
    """Top-level window containing the sidebar and content stack."""

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_title("Auxen")
        self.set_default_size(1100, 700)

        self._app_ref = None
        self._previous_page: str = "home"
        self._lyrics_service = LyricsService()
        self._current_track = None

        split_view = Adw.NavigationSplitView()

        # ---- Sidebar ----
        self._sidebar = AuxenSidebar(
            on_navigate=self._switch_page,
            on_settings=self._open_settings,
            on_playlist_selected=self._on_playlist_selected,
        )
        sidebar_page = Adw.NavigationPage.new(self._sidebar, "Sidebar")
        split_view.set_sidebar(sidebar_page)

        # ---- Content stack ----
        content_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)

        self._stack = Gtk.Stack()
        self._stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._stack.set_transition_duration(200)
        self._stack.set_vexpand(True)
        self._stack.set_hexpand(True)

        for name, title in _PAGES:
            if name == "home":
                self._home_page = HomePage()
                self._stack.add_named(self._home_page, name)
                continue

            if name == "search":
                self._search_view = SearchView()
                self._stack.add_named(self._search_view, name)
                continue

            if name == "library":
                self._library_view = LibraryView()
                self._stack.add_named(self._library_view, name)
                continue

            if name == "explore":
                self._explore_view = ExploreView()
                self._stack.add_named(self._explore_view, name)
                continue

            if name == "favorites":
                self._favorites_view = FavoritesView()
                self._stack.add_named(self._favorites_view, name)
                continue

            placeholder = Gtk.Box(
                orientation=Gtk.Orientation.VERTICAL,
                valign=Gtk.Align.CENTER,
                halign=Gtk.Align.CENTER,
                spacing=8,
            )
            heading = Gtk.Label(label=f"{title} Page")
            heading.add_css_class("title-1")
            placeholder.append(heading)

            subtitle = Gtk.Label(label=f"This is the {title.lower()} view")
            subtitle.add_css_class("dim-label")
            placeholder.append(subtitle)

            self._stack.add_named(placeholder, name)

        # ---- Album Detail Page (programmatic navigation only) ----
        self._album_detail = AlbumDetailView()
        self._album_detail.set_callbacks(
            on_play_track=self._on_album_play_track,
            on_play_all=self._on_album_play_all,
            on_back=self._on_album_back,
        )
        self._stack.add_named(self._album_detail, "album-detail")

        # ---- Playlist Detail Page (programmatic navigation only) ----
        self._playlist_view = PlaylistView()
        self._playlist_view.on_play_track = self._on_playlist_play_track
        self._playlist_view.on_play_all = self._on_playlist_play_all
        self._playlist_view.on_back = self._on_playlist_back
        self._stack.add_named(self._playlist_view, "playlist-detail")

        # Wire home page album click callback
        self._home_page.set_callbacks(
            on_album_clicked=self._on_album_clicked,
        )

        # ---- Lyrics Panel (right side, hidden by default) ----
        self._lyrics_panel = LyricsPanel(
            on_close=self._on_lyrics_panel_close,
        )
        self._lyrics_panel.set_visible(False)

        # Horizontal box: content stack + lyrics panel side by side
        content_hbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        content_hbox.set_vexpand(True)
        self._stack.set_hexpand(True)
        content_hbox.append(self._stack)
        content_hbox.append(self._lyrics_panel)

        content_box.append(content_hbox)

        # ---- Now Playing Bar (pinned at bottom of content area) ----
        self._now_playing = NowPlayingBar(
            on_lyrics_toggle=self._on_lyrics_toggle,
        )
        content_box.append(self._now_playing)

        content_page = Adw.NavigationPage.new(content_box, "Content")
        split_view.set_content(content_page)

        self.set_content(split_view)

        # Show home by default
        self._stack.set_visible_child_name("home")

    # ------------------------------------------------------------------
    # Service wiring
    # ------------------------------------------------------------------

    def wire_services(self, app) -> None:
        """Connect backend services to UI views.

        Called once from do_activate after the window is created.
        """
        self._app_ref = app

        # --- Now-Playing Bar -> Player ---
        if app.player is not None:
            self._now_playing._on_play_pause = app.player.play_pause
            self._now_playing._on_next = app.player.next_track
            self._now_playing._on_previous = app.player.previous_track

            # Player signals -> Now-Playing Bar updates
            app.player.connect("track-changed", self._on_track_changed)
            app.player.connect(
                "position-updated", self._on_position_updated
            )
            app.player.connect("state-changed", self._on_state_changed)

            # Volume slider -> Player volume
            self._now_playing._volume_scale.connect(
                "value-changed", self._on_volume_changed
            )

            # Progress bar seek
            self._now_playing._progress_scale.connect(
                "value-changed", self._on_progress_seek
            )

        # --- Search View -> Providers ---
        if app.db is not None or app.tidal_provider is not None:
            self._search_view.set_providers(
                db=app.db,
                tidal_provider=app.tidal_provider,
            )

        # --- Favorites View -> Database ---
        if app.db is not None:
            self._favorites_view.set_database(app.db)

        # --- Library View -> Database + callbacks ---
        if app.db is not None:
            self._library_view.set_database(app.db)
            self._library_view.set_callbacks(
                on_album_clicked=self._on_album_clicked,
                on_play_track=self._on_library_play_track,
            )

        # --- Explore View -> Tidal Provider ---
        if app.tidal_provider is not None:
            self._explore_view.set_tidal_provider(app.tidal_provider)
            self._explore_view.set_callbacks(
                on_album_clicked=self._on_album_clicked,
                on_play_track=self._on_explore_play_track,
                on_login=self._on_explore_login,
            )

        # --- Sidebar -> Database (playlists) ---
        if app.db is not None:
            self._sidebar.set_database(app.db)

        # --- Home Page initial refresh ---
        if app.db is not None:
            try:
                self._home_page.refresh(app.db)
            except Exception:
                logger.warning(
                    "Failed initial home page refresh", exc_info=True
                )

    def refresh_home(self, db) -> None:
        """Refresh the home page with data from the given database."""
        try:
            self._home_page.refresh(db)
        except Exception:
            logger.warning("Failed to refresh home page", exc_info=True)

    # ------------------------------------------------------------------
    # Player signal handlers
    # ------------------------------------------------------------------

    def _on_track_changed(self, _player, track) -> None:
        """Update the now-playing bar when the current track changes."""
        if track is not None:
            self._current_track = track
            self._now_playing.update_track(
                title=track.title,
                artist=track.artist,
                quality_label=track.quality_label,
                source=track.source.value,
            )
            # Highlight the current track in album detail if visible
            self._album_detail.set_current_track(track.id)
            # Update lyrics panel if it is visible
            if self._lyrics_panel.get_visible():
                self._fetch_and_show_lyrics(track)

    def _on_position_updated(self, _player, position, duration) -> None:
        """Update the now-playing bar progress."""
        # Temporarily disconnect the value-changed signal to prevent
        # seek feedback loop.
        self._now_playing._progress_scale.handler_block_by_func(
            self._on_progress_seek
        )
        self._now_playing.update_position(position, duration)
        self._now_playing._progress_scale.handler_unblock_by_func(
            self._on_progress_seek
        )

    def _on_state_changed(self, _player, state) -> None:
        """Update the now-playing bar play/pause icon."""
        self._now_playing.set_playing(state == "playing")

    def _on_volume_changed(self, scale) -> None:
        """Set the player volume from the volume slider."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.volume = scale.get_value() / 100.0

    def _on_progress_seek(self, scale) -> None:
        """Seek the player when the user drags the progress bar."""
        if self._app_ref and self._app_ref.player is not None:
            duration = self._app_ref.player.get_duration()
            if duration is not None and duration > 0:
                fraction = scale.get_value() / 100.0
                self._app_ref.player.seek(fraction * duration)

    # ------------------------------------------------------------------
    # Album detail navigation
    # ------------------------------------------------------------------

    def _on_album_clicked(self, album_name: str, artist: str) -> None:
        """Handle an album card click from the home page."""
        # Store current page for back navigation
        visible = self._stack.get_visible_child_name()
        if visible and visible != "album-detail":
            self._previous_page = visible

        tracks: list = []
        source = "local"

        if self._app_ref and self._app_ref.db is not None:
            try:
                tracks = self._app_ref.db.get_tracks_by_album(
                    album_name, artist=artist
                )
            except Exception:
                logger.warning(
                    "Failed to fetch album tracks", exc_info=True
                )

        if tracks:
            source = tracks[0].source.value

        self._album_detail.show_album(
            album_name=album_name,
            artist=artist,
            tracks=tracks,
            source=source,
        )
        self._stack.set_visible_child_name("album-detail")

    def _on_album_play_track(self, track) -> None:
        """Play a single track from the album detail view."""
        if self._app_ref and self._app_ref.player is not None:
            # Load the album tracks into the queue starting from this track
            tracks = self._album_detail._tracks
            try:
                index = tracks.index(track)
            except ValueError:
                index = 0
            self._app_ref.player.play_queue(tracks, start_index=index)

    def _on_album_play_all(self, tracks) -> None:
        """Play all tracks from the album detail view."""
        if self._app_ref and self._app_ref.player is not None and tracks:
            self._app_ref.player.play_queue(tracks, start_index=0)

    def _on_library_play_track(self, track) -> None:
        """Play a single track from the library view."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_queue([track], start_index=0)

    def _on_album_back(self) -> None:
        """Navigate back from the album detail view."""
        self._stack.set_visible_child_name(self._previous_page)

    # ------------------------------------------------------------------
    # Explore page callbacks
    # ------------------------------------------------------------------

    def _on_explore_play_track(self, track) -> None:
        """Play a single track from the explore view."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_queue([track], start_index=0)

    def _on_explore_login(self) -> None:
        """Handle login request from the explore page."""
        self._open_settings()

    # ------------------------------------------------------------------
    # Playlist detail navigation
    # ------------------------------------------------------------------

    def _on_playlist_selected(self, playlist_id: int) -> None:
        """Handle a playlist selection from the sidebar."""
        visible = self._stack.get_visible_child_name()
        if visible and visible != "playlist-detail":
            self._previous_page = visible

        if self._app_ref and self._app_ref.db is not None:
            self._playlist_view.show_playlist(
                playlist_id, self._app_ref.db
            )
        self._stack.set_visible_child_name("playlist-detail")

    def _on_playlist_play_track(self, track) -> None:
        """Play a single track from the playlist detail view."""
        if self._app_ref and self._app_ref.player is not None:
            tracks = self._playlist_view._tracks
            try:
                index = tracks.index(track)
            except ValueError:
                index = 0
            self._app_ref.player.play_queue(tracks, start_index=index)

    def _on_playlist_play_all(self, tracks) -> None:
        """Play all tracks from the playlist view."""
        if (
            self._app_ref
            and self._app_ref.player is not None
            and tracks
        ):
            self._app_ref.player.play_queue(tracks, start_index=0)

    def _on_playlist_back(self) -> None:
        """Navigate back from the playlist detail view."""
        # Refresh sidebar playlists in case anything changed
        if self._app_ref and self._app_ref.db is not None:
            self._sidebar.refresh_playlists()
        self._stack.set_visible_child_name(self._previous_page)

    # ------------------------------------------------------------------
    # Settings
    # ------------------------------------------------------------------

    def _open_settings(self) -> None:
        """Create and present the settings dialog."""
        settings = AuxenSettings(transient_for=self)
        if self._app_ref is not None:
            settings.set_services(
                db=self._app_ref.db,
                tidal_provider=self._app_ref.tidal_provider,
                local_provider=self._app_ref.local_provider,
            )
        settings.present()

    # ------------------------------------------------------------------
    # Lyrics panel
    # ------------------------------------------------------------------

    def _on_lyrics_toggle(self, active: bool) -> None:
        """Show or hide the lyrics panel from the now-playing bar button."""
        self._lyrics_panel.set_visible(active)
        if active and self._current_track is not None:
            self._fetch_and_show_lyrics(self._current_track)

    def _on_lyrics_panel_close(self) -> None:
        """Handle the lyrics panel close button."""
        self._lyrics_panel.set_visible(False)
        self._now_playing.set_lyrics_active(False)

    def _fetch_and_show_lyrics(self, track) -> None:
        """Fetch lyrics asynchronously and update the panel."""
        title = track.title
        artist = track.artist

        def _on_lyrics_result(lyrics_text):
            # Verify the track hasn't changed while we were fetching
            if self._current_track is not track:
                return
            if lyrics_text:
                self._lyrics_panel.show_lyrics(title, artist, lyrics_text)
            else:
                self._lyrics_panel.show_no_lyrics(title, artist)

        self._lyrics_service.get_lyrics_async(track, _on_lyrics_result)

    def _switch_page(self, page_name: str) -> None:
        """Switch the content stack to the requested page."""
        child = self._stack.get_child_by_name(page_name)
        if child:
            self._stack.set_visible_child_name(page_name)

        # Refresh library when switching to that page
        if page_name == "library" and self._app_ref and self._app_ref.db:
            try:
                self._library_view.refresh()
            except Exception:
                logger.warning(
                    "Failed to refresh library", exc_info=True
                )

        # Refresh favorites when switching to that page
        if page_name == "favorites" and self._app_ref and self._app_ref.db:
            try:
                self._favorites_view.refresh()
            except Exception:
                logger.warning(
                    "Failed to refresh favorites", exc_info=True
                )

        # Refresh explore page when switching to it
        if page_name == "explore":
            try:
                self._explore_view.refresh()
            except Exception:
                logger.warning(
                    "Failed to refresh explore page", exc_info=True
                )
