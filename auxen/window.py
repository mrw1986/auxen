"""Main application window for Auxen."""

from __future__ import annotations

import logging

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, GLib, Gtk

from auxen.album_art import AlbumArtService
from auxen.equalizer import Equalizer
from auxen.lyrics import LyricsService
from auxen.views.album_detail import AlbumDetailView
from auxen.views.artist_detail import ArtistDetailView
from auxen.views.equalizer_dialog import EqualizerDialog
from auxen.views.explore import ExploreView
from auxen.views.favorites import FavoritesView
from auxen.views.home import HomePage
from auxen.views.library import LibraryView
from auxen.views.lyrics_panel import LyricsPanel
from auxen.views.mixes import MixesView
from auxen.views.now_playing import NowPlayingBar
from auxen.views.queue_panel import QueuePanel
from auxen.views.playlist_view import PlaylistView
from auxen.views.search import SearchView
from auxen.views.settings import AuxenSettings
from auxen.views.sidebar import AuxenSidebar
from auxen.views.sleep_timer_dialog import SleepTimerDialog
from auxen.views.mini_player import MiniPlayerWindow
from auxen.views.smart_playlist_view import SmartPlaylistView
from auxen.views.stats import StatsView

logger = logging.getLogger(__name__)

# Page definitions: (name, display_title)
_PAGES: list[tuple[str, str]] = [
    ("home", "Home"),
    ("search", "Search"),
    ("library", "Library"),
    ("explore", "Explore"),
    ("mixes", "Mixes"),
    ("favorites", "Favorites"),
    ("stats", "Stats"),
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
        self._album_art_service = AlbumArtService()
        self._current_track = None
        self._equalizer: Equalizer | None = None
        self._mini_player: MiniPlayerWindow | None = None

        # Navigation history stack for back/forward mouse buttons.
        # Each entry is a page name string. Detail pages include a suffix
        # (e.g. "album-detail:AlbumName|Artist") to distinguish different
        # entities on the same view type.
        self._nav_history: list[str] = ["home"]
        self._nav_index: int = 0
        self._nav_programmatic: bool = False

        split_view = Adw.NavigationSplitView()

        self._smart_playlist_service = None

        # ---- Sidebar ----
        self._sidebar = AuxenSidebar(
            on_navigate=self._switch_page,
            on_settings=self._open_settings,
            on_playlist_selected=self._on_playlist_selected,
            on_smart_playlist_selected=self._on_smart_playlist_selected,
        )
        self._sidebar.on_tidal_login = self._start_tidal_login
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

            if name == "mixes":
                self._mixes_view = MixesView()
                self._stack.add_named(self._mixes_view, name)
                continue

            if name == "favorites":
                self._favorites_view = FavoritesView()
                self._stack.add_named(self._favorites_view, name)
                continue

            if name == "stats":
                self._stats_view = StatsView()
                self._stack.add_named(self._stats_view, name)
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
            on_artist_navigate=self._navigate_to_artist,
        )
        self._stack.add_named(self._album_detail, "album-detail")

        # ---- Artist Detail Page (programmatic navigation only) ----
        self._artist_detail = ArtistDetailView()
        self._artist_detail.set_callbacks(
            on_play_track=self._on_artist_play_track,
            on_play_all=self._on_artist_play_all,
            on_back=self._on_artist_back,
            on_album_clicked=self._on_album_clicked,
        )
        self._stack.add_named(self._artist_detail, "artist-detail")

        # ---- Playlist Detail Page (programmatic navigation only) ----
        self._playlist_view = PlaylistView()
        self._playlist_view.on_play_track = self._on_playlist_play_track
        self._playlist_view.on_play_all = self._on_playlist_play_all
        self._playlist_view.on_back = self._on_playlist_back
        self._stack.add_named(self._playlist_view, "playlist-detail")

        # ---- Smart Playlist Detail Page (programmatic navigation only) ----
        self._smart_playlist_view = SmartPlaylistView()
        self._smart_playlist_view.set_callbacks(
            on_play_track=self._on_smart_playlist_play_track,
            on_play_all=self._on_smart_playlist_play_all,
            on_back=self._on_smart_playlist_back,
            on_refresh=self._on_smart_playlist_refresh,
        )
        self._stack.add_named(
            self._smart_playlist_view, "smart-playlist-detail"
        )

        # Wire home page album and artist click callbacks
        self._home_page.set_callbacks(
            on_album_clicked=self._on_album_clicked,
            on_artist_clicked=self._navigate_to_artist,
        )

        # ---- Lyrics Panel (right side, hidden by default) ----
        self._lyrics_panel = LyricsPanel(
            on_close=self._on_lyrics_panel_close,
        )
        self._lyrics_panel.set_visible(False)

        # ---- Queue Panel (right side, hidden by default) ----
        self._queue_panel = QueuePanel(
            on_close=self._on_queue_panel_close,
        )
        self._queue_panel.set_visible(False)
        self._queue_panel.set_callbacks(
            on_jump_to=self._on_queue_jump_to,
            on_remove=self._on_queue_remove,
            on_clear=self._on_queue_clear,
            on_move=self._on_queue_move,
        )

        # Horizontal box: content stack + side panels (only one visible)
        content_hbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        content_hbox.set_vexpand(True)
        self._stack.set_hexpand(True)
        content_hbox.append(self._stack)
        content_hbox.append(self._lyrics_panel)
        content_hbox.append(self._queue_panel)

        content_box.append(content_hbox)

        # ---- Now Playing Bar (pinned at bottom of content area) ----
        self._now_playing = NowPlayingBar(
            on_lyrics_toggle=self._on_lyrics_toggle,
            on_queue_toggle=self._on_queue_toggle,
            on_favorite=self._on_now_playing_favorite_toggled,
        )
        self._now_playing.on_artist_clicked = self._navigate_to_artist
        self._now_playing.on_album_clicked = self._navigate_to_album
        content_box.append(self._now_playing)

        self._toast_overlay = Adw.ToastOverlay()
        self._toast_overlay.set_child(content_box)

        content_page = Adw.NavigationPage.new(
            self._toast_overlay, "Content"
        )
        split_view.set_content(content_page)

        self.set_content(split_view)

        # Wire context menu callbacks for all views that support them
        context_callbacks = {
            "on_play": self._on_context_play,
            "on_play_next": self._on_context_play_next,
            "on_add_to_queue": self._on_context_add_to_queue,
            "on_add_to_playlist": self._on_context_add_to_playlist,
            "on_new_playlist": self._on_context_new_playlist_with_track,
            "on_toggle_favorite": self._on_context_toggle_favorite,
            "on_go_to_album": self._on_context_go_to_album,
            "on_go_to_artist": self._on_context_go_to_artist,
        }

        self._home_page.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._library_view.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._search_view.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._favorites_view.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._album_detail.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._artist_detail.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._playlist_view.set_context_callbacks(
            context_callbacks, self._get_playlists
        )
        self._smart_playlist_view.set_context_callbacks(
            context_callbacks, self._get_playlists
        )

        # Wire album context menu callbacks for views with album cards
        album_context_callbacks = {
            "on_play_album": self._on_context_play_album,
            "on_play_album_next": self._on_context_play_album_next,
            "on_add_album_to_queue": self._on_context_add_album_to_queue,
            "on_add_to_playlist": self._on_context_add_album_to_playlist,
            "on_new_playlist": self._on_context_new_playlist_with_album,
            "on_add_to_favorites": self._on_context_add_album_to_favorites,
            "on_go_to_artist": self._on_context_album_go_to_artist,
        }
        self._home_page.set_album_context_callbacks(
            album_context_callbacks, self._get_playlists
        )
        self._library_view.set_album_context_callbacks(
            album_context_callbacks, self._get_playlists
        )

        # Wire artist context menu callbacks for library artist rows
        artist_context_callbacks = {
            "on_play_all": self._on_context_play_all_by_artist,
            "on_add_all_to_queue": self._on_context_add_all_by_artist,
            "on_view_artist": self._on_context_view_artist,
        }
        self._library_view.set_artist_context_callbacks(
            artist_context_callbacks
        )

        # ---- Mouse back/forward button controller ----
        mouse_click = Gtk.GestureClick.new()
        mouse_click.set_button(0)  # Listen to ALL buttons
        mouse_click.connect("pressed", self._on_mouse_button)
        self.add_controller(mouse_click)

        # ---- Keyboard shortcuts: Alt+Left/Right for back/forward ----
        back_shortcut = Gtk.Shortcut.new(
            Gtk.ShortcutTrigger.parse_string("<Alt>Left"),
            Gtk.CallbackAction.new(lambda _w, _d: self._nav_back()),
        )
        self.add_shortcut(back_shortcut)

        forward_shortcut = Gtk.Shortcut.new(
            Gtk.ShortcutTrigger.parse_string("<Alt>Right"),
            Gtk.CallbackAction.new(lambda _w, _d: self._nav_forward()),
        )
        self.add_shortcut(forward_shortcut)

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

        # --- Equalizer ---
        if app.player is not None:
            self._equalizer = Equalizer(
                on_band_changed=app.player.set_eq_band
            )

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
            app.player.connect(
                "spectrum-data", self._on_spectrum_data
            )

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

        # --- Favorites View -> Database + Tidal ---
        if app.db is not None:
            self._favorites_view.set_database(app.db)
        if app.tidal_provider is not None:
            self._favorites_view.set_tidal_provider(app.tidal_provider)
        self._favorites_view.on_favorite_changed = self._on_favorites_view_changed

        # --- Library View -> Database + callbacks ---
        if app.db is not None:
            self._library_view.set_database(app.db)
            self._library_view.set_callbacks(
                on_album_clicked=self._on_album_clicked,
                on_play_track=self._on_library_play_track,
                on_artist_clicked=self._on_artist_clicked,
            )

        # --- Explore View -> Tidal Provider ---
        if app.tidal_provider is not None:
            self._explore_view.set_tidal_provider(app.tidal_provider)
            self._explore_view.set_callbacks(
                on_album_clicked=self._on_album_clicked,
                on_play_track=self._on_explore_play_track,
                on_login=self._on_explore_login,
            )

        # --- Mixes View -> Tidal Provider ---
        if app.tidal_provider is not None:
            self._mixes_view.set_tidal_provider(app.tidal_provider)
            self._mixes_view.set_callbacks(
                on_play_mix=self._on_mixes_play_mix,
                on_login=self._on_mixes_login,
            )

        # --- Sidebar -> Database (playlists) ---
        if app.db is not None:
            self._sidebar.set_database(app.db)

        # --- Stats View -> Database ---
        if app.db is not None:
            self._stats_view.set_database(app.db)

        # --- Home Page -> Album Art Service ---
        self._home_page.set_album_art_service(self._album_art_service)

        # --- Smart Playlists -> Service ---
        if hasattr(app, "smart_playlist_service") and app.smart_playlist_service is not None:
            self._smart_playlist_service = app.smart_playlist_service
            self._sidebar.set_smart_playlist_service(
                app.smart_playlist_service
            )

        # --- Sidebar account info from Tidal ---
        self._update_sidebar_account()

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
    # Navigation history
    # ------------------------------------------------------------------

    def _push_nav(self, page_name: str, detail_key: str = "") -> None:
        """Push a page onto the navigation history.

        *detail_key* distinguishes different entities on the same view type
        (e.g. different album names on the album-detail page).
        """
        if self._nav_programmatic:
            return  # Don't push during back/forward navigation
        entry = f"{page_name}:{detail_key}" if detail_key else page_name
        # Don't push duplicates
        if (
            self._nav_history
            and self._nav_history[self._nav_index] == entry
        ):
            return
        # Trim forward history
        self._nav_history = self._nav_history[: self._nav_index + 1]
        self._nav_history.append(entry)
        self._nav_index = len(self._nav_history) - 1

    def _on_mouse_button(self, gesture, n_press, x, y) -> None:
        """Handle mouse back/forward buttons for navigation."""
        if n_press != 1:
            return
        button = gesture.get_current_button()
        if button == 8:  # Back button
            if self._nav_back():
                gesture.set_state(Gtk.EventSequenceState.CLAIMED)
        elif button == 9:  # Forward button
            if self._nav_forward():
                gesture.set_state(Gtk.EventSequenceState.CLAIMED)

    def _nav_back(self) -> bool:
        """Navigate back in the history stack. Returns True if navigated."""
        if self._nav_index > 0:
            self._nav_index -= 1
            entry = self._nav_history[self._nav_index]
            page = entry.split(":")[0]
            self._nav_programmatic = True
            try:
                self._stack.set_visible_child_name(page)
            finally:
                self._nav_programmatic = False
            self._restore_nav_entry(entry)
            return True
        return False

    def _nav_forward(self) -> bool:
        """Navigate forward in the history stack. Returns True if navigated."""
        if self._nav_index < len(self._nav_history) - 1:
            self._nav_index += 1
            entry = self._nav_history[self._nav_index]
            page = entry.split(":")[0]
            self._nav_programmatic = True
            try:
                self._stack.set_visible_child_name(page)
            finally:
                self._nav_programmatic = False
            self._restore_nav_entry(entry)
            return True
        return False

    def _restore_nav_entry(self, entry: str) -> None:
        """Refresh or reload the page for a history *entry*.

        For top-level pages this triggers a data refresh.  For detail
        pages the stored detail key is used to reload the correct entity.
        """
        page = entry.split(":")[0]
        detail_key = entry.split(":", 1)[1] if ":" in entry else ""

        # Detail pages — reload the entity from the detail key.
        if detail_key:
            try:
                self._reload_detail_page(page, detail_key)
            except Exception:
                logger.warning(
                    "Failed to restore detail page %s:%s",
                    page,
                    detail_key,
                    exc_info=True,
                )
            return

        # Top-level pages — refresh data.
        self._refresh_page(page)

    def _reload_detail_page(self, page: str, detail_key: str) -> None:
        """Reload a detail page from its stored key.

        Album detail keys use a null-byte separator between album name
        and artist.  Other detail keys are plain strings (artist name,
        playlist ID, or smart playlist ID).
        """
        db = self._app_ref.db if self._app_ref else None

        if page == "album-detail" and db is not None:
            parts = detail_key.split("\x00", 1)
            album_name = parts[0]
            artist = parts[1] if len(parts) > 1 else ""
            if album_name:
                tracks = db.get_tracks_by_album(album_name, artist=artist)
                source = tracks[0].source.value if tracks else "local"
                self._album_detail.show_album(
                    album_name=album_name,
                    artist=artist,
                    tracks=tracks,
                    source=source,
                )
                # Reload album art (mirrors _on_album_clicked logic).
                if tracks:
                    expected = (album_name, artist)
                    scale = self.get_scale_factor() or 1
                    detail_px = 200 * scale

                    def _on_art(pixbuf, _key=expected):
                        current = (
                            self._album_detail._title_label.get_label(),
                            self._album_detail._artist_label.get_label(),
                        )
                        if current == _key:
                            self._album_detail.set_album_art(pixbuf)

                    self._album_art_service.get_art_async(
                        tracks[0], _on_art, width=detail_px, height=detail_px
                    )

        elif page == "artist-detail" and db is not None:
            if detail_key:
                albums = db.get_artist_albums(detail_key)
                tracks = db.get_artist_tracks(detail_key)
                source = tracks[0].source.value if tracks else "local"
                self._artist_detail.show_artist(
                    artist_name=detail_key,
                    albums=albums,
                    tracks=tracks,
                    source=source,
                )

        elif page == "playlist-detail" and db is not None:
            try:
                playlist_id = int(detail_key)
                # Check existence first to avoid re-entrant on_back()
                # callback if the playlist was deleted.
                if db.get_playlist(playlist_id) is not None:
                    self._playlist_view.show_playlist(playlist_id, db)
                else:
                    # Playlist was deleted — fall back to library.
                    self._stack.set_visible_child_name("library")
                    self._refresh_page("library")
            except (ValueError, TypeError):
                pass

        elif page == "smart-playlist-detail":
            sps = getattr(self, "_smart_playlist_service", None)
            if sps is not None and detail_key:
                definition = sps.get_definition(detail_key)
                tracks = sps.get_tracks(detail_key)
                if definition is not None:
                    self._smart_playlist_view.show_playlist(
                        detail_key, tracks, definition
                    )

    # ------------------------------------------------------------------
    # Player signal handlers
    # ------------------------------------------------------------------

    def _on_track_changed(self, _player, track) -> None:
        """Update the now-playing bar when the current track changes."""
        if track is None:
            self._current_track = None
            self._now_playing.update_track(title="", artist="", album="")
            self._now_playing.set_album_art(None)
            self._now_playing.set_favorite_active(False)
            self._album_detail.set_current_track(None)
            if self._mini_player is not None:
                self._mini_player.update_track(title="", artist="")
                self._mini_player.set_album_art(None)
            return
        if track is not None:
            self._current_track = track
            self._now_playing.update_track(
                title=track.title,
                artist=track.artist,
                quality_label=track.quality_label,
                source=track.source.value,
                album=track.album or "",
            )
            # Load album art asynchronously for the now-playing bar.
            # Wrap callbacks to discard stale results for a different track.
            self._now_playing.set_album_art(None)  # clear while loading

            def _on_art(pixbuf, _track=track):
                if self._current_track is _track:
                    self._now_playing.set_album_art(pixbuf)

            # Fetch at logical_size * scale_factor for HiDPI crispness
            scale = self.get_scale_factor() or 1
            art_48 = 48 * scale
            self._album_art_service.get_art_async(
                track,
                _on_art,
                width=art_48,
                height=art_48,
            )
            # Update the favorite button state for the new track
            if self._app_ref and self._app_ref.db is not None and track.id is not None:
                try:
                    is_fav = self._app_ref.db.is_favorite(track.id)
                    self._now_playing.set_favorite_active(is_fav)
                except Exception:
                    logger.warning(
                        "Failed to check favorite state for track",
                        exc_info=True,
                    )
            else:
                self._now_playing.set_favorite_active(False)
            # Highlight the current track in album detail if visible
            self._album_detail.set_current_track(track.id)
            # Update lyrics panel if it is visible
            if self._lyrics_panel.get_visible():
                self._fetch_and_show_lyrics(track)
            # Update queue panel if it is visible
            if self._queue_panel.get_visible():
                self._refresh_queue_panel()
            # Update mini player if it is visible
            if (
                self._mini_player is not None
                and self._mini_player.get_visible()
            ):
                self._mini_player.update_track(
                    title=track.title,
                    artist=track.artist,
                )
                self._mini_player.set_album_art(None)

                def _on_mini_art(pixbuf, _track=track):
                    if self._current_track is _track:
                        self._mini_player.set_album_art(pixbuf)

                self._album_art_service.get_art_async(
                    track,
                    _on_mini_art,
                    width=art_48,
                    height=art_48,
                )

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
        # Update mini player progress if visible
        if (
            self._mini_player is not None
            and self._mini_player.get_visible()
        ):
            self._mini_player.update_position(position, duration)

    def _on_state_changed(self, _player, state) -> None:
        """Update the now-playing bar play/pause icon."""
        self._now_playing.set_playing(state == "playing")
        # Toggle spectrum visualizer based on playback state
        self._now_playing.set_visualizer_active(state == "playing")
        # Update mini player play state if visible
        if (
            self._mini_player is not None
            and self._mini_player.get_visible()
        ):
            self._mini_player.set_playing(state == "playing")

    def _on_now_playing_favorite_toggled(self, is_favorite: bool) -> None:
        """Handle favorite toggle from the now-playing bar heart button."""
        track = self._current_track
        if track is None or track.id is None:
            return
        if self._app_ref is None or self._app_ref.db is None:
            return

        try:
            self._app_ref.db.set_favorite(track.id, is_favorite)

            # Sync to Tidal in a background thread if applicable
            if (
                getattr(track, "is_tidal", False)
                and self._app_ref.tidal_provider is not None
            ):
                import threading

                tidal = self._app_ref.tidal_provider
                sid = track.source_id

                def _sync_tidal():
                    try:
                        if is_favorite:
                            tidal.add_favorite(sid)
                        else:
                            tidal.remove_favorite(sid)
                    except Exception:
                        logger.warning(
                            "Failed to sync favorite to Tidal",
                            exc_info=True,
                        )

                threading.Thread(
                    target=_sync_tidal, daemon=True
                ).start()

            # Refresh the favorites view if it is currently visible
            visible = self._stack.get_visible_child_name()
            if visible == "favorites":
                self._favorites_view.refresh()
        except Exception:
            logger.warning(
                "Failed to toggle favorite from now-playing bar",
                exc_info=True,
            )

    def _on_favorites_view_changed(
        self, track_id: int, is_favorite: bool
    ) -> None:
        """Sync now-playing heart when favorites view unfavorites a track."""
        if (
            self._current_track is not None
            and self._current_track.id == track_id
        ):
            self._now_playing.set_favorite_active(is_favorite)

    def _on_spectrum_data(self, _player, levels) -> None:
        """Forward spectrum data to the now-playing visualizer."""
        self._now_playing.visualizer.update_spectrum(levels)

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

        # Load album art for the detail header (use first track).
        # Guard against stale callbacks from a previous album request.
        if tracks:
            expected_album = (album_name, artist)
            scale = self.get_scale_factor() or 1
            detail_px = 200 * scale

            def _on_detail_art(pixbuf, _key=expected_album):
                current = (
                    self._album_detail._title_label.get_label(),
                    self._album_detail._artist_label.get_label(),
                )
                if current == _key:
                    self._album_detail.set_album_art(pixbuf)

            self._album_art_service.get_art_async(
                tracks[0],
                _on_detail_art,
                width=detail_px,
                height=detail_px,
            )

        self._stack.set_visible_child_name("album-detail")
        self._push_nav("album-detail", f"{album_name}\x00{artist}")

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
        self._nav_back()

    # ------------------------------------------------------------------
    # Clickable name navigation helpers
    # ------------------------------------------------------------------

    def _navigate_to_artist(self, artist_name: str) -> None:
        """Navigate to artist detail view (used by clickable artist names)."""
        self._on_artist_clicked(artist_name)

    def _navigate_to_album(self, album_name: str, artist: str) -> None:
        """Navigate to album detail view (used by clickable album/title names)."""
        self._on_album_clicked(album_name, artist)

    # ------------------------------------------------------------------
    # Artist detail navigation
    # ------------------------------------------------------------------

    def _on_artist_clicked(self, artist_name: str) -> None:
        """Handle an artist row click from the library view."""
        # Store current page for back navigation
        visible = self._stack.get_visible_child_name()
        if visible and visible != "artist-detail":
            self._previous_page = visible

        albums: list[dict] = []
        tracks: list = []
        source = "local"

        if self._app_ref and self._app_ref.db is not None:
            try:
                albums = self._app_ref.db.get_artist_albums(artist_name)
                tracks = self._app_ref.db.get_artist_tracks(artist_name)
            except Exception:
                logger.warning(
                    "Failed to fetch artist data", exc_info=True
                )

        if tracks:
            source = tracks[0].source.value

        self._artist_detail.show_artist(
            artist_name=artist_name,
            albums=albums,
            tracks=tracks,
            source=source,
        )
        self._stack.set_visible_child_name("artist-detail")
        self._push_nav("artist-detail", artist_name)

    def _on_artist_play_track(self, track) -> None:
        """Play a single track from the artist detail view."""
        if self._app_ref and self._app_ref.player is not None:
            # Load the artist tracks into the queue starting from this track
            tracks = self._artist_detail._tracks
            try:
                index = tracks.index(track)
            except ValueError:
                index = 0
            self._app_ref.player.play_queue(tracks, start_index=index)

    def _on_artist_play_all(self, tracks) -> None:
        """Play all tracks from the artist detail view."""
        if self._app_ref and self._app_ref.player is not None and tracks:
            self._app_ref.player.play_queue(tracks, start_index=0)

    def _on_artist_back(self) -> None:
        """Navigate back from the artist detail view."""
        self._nav_back()

    # ------------------------------------------------------------------
    # Explore page callbacks
    # ------------------------------------------------------------------

    def _on_explore_play_track(self, track) -> None:
        """Play a single track from the explore view."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_queue([track], start_index=0)

    def _on_explore_login(self) -> None:
        """Handle login request from the explore page."""
        self._start_tidal_login()

    # ------------------------------------------------------------------
    # Mixes page callbacks
    # ------------------------------------------------------------------

    def _on_mixes_play_mix(self, tidal_id: str, name: str) -> None:
        """Handle a mix/playlist card click from the mixes view."""
        if (
            self._app_ref
            and self._app_ref.tidal_provider is not None
            and self._app_ref.player is not None
        ):
            import threading

            def _load_and_play() -> None:
                try:
                    tracks = self._app_ref.tidal_provider.get_playlist_tracks(
                        tidal_id
                    )
                    if tracks:
                        GLib.idle_add(
                            self._app_ref.player.play_queue,
                            tracks, 0,
                        )
                except Exception:
                    logger.warning(
                        "Failed to load mix tracks for %s", name,
                        exc_info=True,
                    )

            threading.Thread(target=_load_and_play, daemon=True).start()

    def _on_mixes_login(self) -> None:
        """Handle login request from the mixes page."""
        self._start_tidal_login()

    # ------------------------------------------------------------------
    # Direct Tidal login flow
    # ------------------------------------------------------------------

    def _start_tidal_login(self) -> None:
        """Start the Tidal OAuth login flow with a proper dialog and browser."""
        import threading

        if self._app_ref is None or self._app_ref.tidal_provider is None:
            toast = Adw.Toast.new("Tidal provider not available")
            toast.set_timeout(3)
            self._show_toast(toast)
            return

        provider = self._app_ref.tidal_provider
        if provider.is_logged_in:
            toast = Adw.Toast.new("Already logged in to Tidal")
            toast.set_timeout(3)
            self._show_toast(toast)
            return

        # Build a dialog showing the login progress
        dialog = Adw.AlertDialog()
        dialog.set_heading("Log In to Tidal")
        dialog.set_body(
            "Starting Tidal authentication...\n"
            "Your browser will open shortly."
        )
        dialog.add_response("cancel", "Cancel")
        dialog.set_response_appearance(
            "cancel", Adw.ResponseAppearance.DESTRUCTIVE
        )

        cancelled = threading.Event()

        def _on_dialog_response(_dialog, response):
            if response == "cancel":
                cancelled.set()

        dialog.connect("response", _on_dialog_response)

        def _url_callback(url: str) -> None:
            """Open the browser and update the dialog with the URL."""
            def _show_url():
                dialog.set_body(
                    "A browser window has been opened.\n"
                    "Complete the login there, then return here.\n\n"
                    f"If the browser didn't open, visit:\n{url}"
                )
                # Open URL in the default browser
                launcher = Gtk.UriLauncher.new(url)
                launcher.launch(self, None, None, None)
                return False

            GLib.idle_add(_show_url)

        def _login_thread() -> None:
            try:
                success = provider.login(url_callback=_url_callback)
                if cancelled.is_set():
                    return

                def _on_complete():
                    dialog.force_close()
                    if success:
                        toast = Adw.Toast.new("Logged in to Tidal")
                        toast.set_timeout(3)
                        self._show_toast(toast)
                        # Update sidebar account info
                        self._update_sidebar_account()
                        # Refresh the current view
                        self._refresh_tidal_views()
                    else:
                        toast = Adw.Toast.new("Tidal login failed")
                        toast.set_timeout(5)
                        self._show_toast(toast)
                    return False

                GLib.idle_add(_on_complete)
            except Exception:
                logger.warning("Tidal login failed", exc_info=True)
                if not cancelled.is_set():
                    def _on_error():
                        dialog.force_close()
                        toast = Adw.Toast.new("Tidal login failed")
                        toast.set_timeout(5)
                        self._show_toast(toast)
                        return False

                    GLib.idle_add(_on_error)

        thread = threading.Thread(target=_login_thread, daemon=True)
        thread.start()

        dialog.choose(self, None, None, None)

    def _show_toast(self, toast: Adw.Toast) -> None:
        """Show a toast notification via the content toast overlay."""
        if hasattr(self, "_toast_overlay"):
            self._toast_overlay.add_toast(toast)
        else:
            logger.info("Toast: %s", toast.get_title())

    def _update_sidebar_account(self) -> None:
        """Update sidebar account info from Tidal provider state."""
        if self._app_ref is None or self._app_ref.tidal_provider is None:
            self._sidebar.update_account()
            return

        provider = self._app_ref.tidal_provider
        if not provider.is_logged_in:
            self._sidebar.update_account()
            return

        try:
            session = provider._session
            user = session.user
            username = getattr(user, "name", None) or str(getattr(user, "id", "User"))
            sub = getattr(session, "subscription", None)
            plan = getattr(sub, "subscription", {}).get("type", "Tidal") if sub and hasattr(sub, "subscription") else "Tidal"
            if plan == "Tidal":
                # Try alternate attribute path
                plan = getattr(sub, "type", "Tidal") if sub else "Tidal"
            self._sidebar.update_account(username=username, plan=plan)
        except Exception:
            logger.warning("Failed to fetch Tidal account info", exc_info=True)
            self._sidebar.update_account(username="Tidal User", plan="Connected")

    def _refresh_tidal_views(self) -> None:
        """Refresh Explore, Mixes, and Favorites views after login."""
        visible = self._stack.get_visible_child_name()
        if visible == "explore":
            self._explore_view.refresh()
        elif visible == "mixes":
            self._mixes_view.refresh()
        elif visible == "favorites":
            self._favorites_view.refresh()
        else:
            # Refresh whichever one the user navigates to next
            # by triggering a refresh on all tidal views
            try:
                self._explore_view.refresh()
            except Exception:
                pass
            try:
                self._mixes_view.refresh()
            except Exception:
                pass

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
        self._push_nav("playlist-detail", str(playlist_id))

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
        self._nav_back()

    # ------------------------------------------------------------------
    # Smart playlist detail navigation
    # ------------------------------------------------------------------

    def _on_smart_playlist_selected(self, smart_id: str) -> None:
        """Handle a smart playlist selection from the sidebar."""
        visible = self._stack.get_visible_child_name()
        if visible and visible != "smart-playlist-detail":
            self._previous_page = visible

        if self._smart_playlist_service is not None:
            try:
                definition = (
                    self._smart_playlist_service.get_definition(smart_id)
                )
                tracks = (
                    self._smart_playlist_service.get_tracks(smart_id)
                )
                if definition is not None:
                    self._smart_playlist_view.show_playlist(
                        smart_id, tracks, definition
                    )
            except Exception:
                logger.warning(
                    "Failed to load smart playlist %s",
                    smart_id,
                    exc_info=True,
                )
        self._stack.set_visible_child_name("smart-playlist-detail")
        self._push_nav("smart-playlist-detail", str(smart_id))

    def _on_smart_playlist_play_track(self, track) -> None:
        """Play a single track from the smart playlist view."""
        if self._app_ref and self._app_ref.player is not None:
            tracks = self._smart_playlist_view._tracks
            try:
                index = tracks.index(track)
            except ValueError:
                index = 0
            self._app_ref.player.play_queue(tracks, start_index=index)

    def _on_smart_playlist_play_all(self, tracks) -> None:
        """Play all tracks from the smart playlist view."""
        if (
            self._app_ref
            and self._app_ref.player is not None
            and tracks
        ):
            self._app_ref.player.play_queue(tracks, start_index=0)

    def _on_smart_playlist_back(self) -> None:
        """Navigate back from the smart playlist detail view."""
        self._nav_back()

    def _on_smart_playlist_refresh(self, smart_id: str) -> None:
        """Re-generate the smart playlist tracks and redisplay."""
        self._on_smart_playlist_selected(smart_id)

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
                player=self._app_ref.player,
                notification_service=self._app_ref.notification_service,
                favorites_sync=self._app_ref.favorites_sync,
                crossfade_service=self._app_ref.crossfade_service,
                lastfm_service=self._app_ref.lastfm_service,
            )
        settings.present()

    # ------------------------------------------------------------------
    # Equalizer
    # ------------------------------------------------------------------

    def open_equalizer(self) -> None:
        """Create and present the equalizer dialog."""
        if self._equalizer is None:
            logger.warning(
                "Equalizer not available (player may not be initialised)"
            )
            return
        dialog = EqualizerDialog(
            equalizer=self._equalizer,
            transient_for=self,
        )
        dialog.present()

    # ------------------------------------------------------------------
    # Lyrics panel
    # ------------------------------------------------------------------

    def _on_lyrics_toggle(self, active: bool) -> None:
        """Show or hide the lyrics panel from the now-playing bar button."""
        if active:
            # Hide queue panel if visible (mutual exclusion)
            if self._queue_panel.get_visible():
                self._queue_panel.set_visible(False)
                self._now_playing.set_queue_active(False)
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

    # ------------------------------------------------------------------
    # Queue panel
    # ------------------------------------------------------------------

    def _on_queue_toggle(self, active: bool) -> None:
        """Show or hide the queue panel from the now-playing bar button."""
        if active:
            # Hide lyrics panel if visible (mutual exclusion)
            if self._lyrics_panel.get_visible():
                self._lyrics_panel.set_visible(False)
                self._now_playing.set_lyrics_active(False)
            self._refresh_queue_panel()
        self._queue_panel.set_visible(active)

    def _on_queue_panel_close(self) -> None:
        """Handle the queue panel close button."""
        self._queue_panel.set_visible(False)
        self._now_playing.set_queue_active(False)

    def _refresh_queue_panel(self) -> None:
        """Refresh the queue panel with the current player queue state."""
        if self._app_ref and self._app_ref.player is not None:
            player = self._app_ref.player
            tracks = player.queue.tracks
            position = player.queue.position
            self._queue_panel.update_queue(tracks, position)
        else:
            self._queue_panel.update_queue([], -1)

    def _on_queue_jump_to(self, index: int) -> None:
        """Jump to a specific track in the queue."""
        if self._app_ref and self._app_ref.player is not None:
            player = self._app_ref.player
            track = player.queue.jump_to(index)
            if track is not None:
                player.play_track(track)
            self._refresh_queue_panel()

    def _on_queue_remove(self, index: int) -> None:
        """Remove a track from the queue by index."""
        if self._app_ref and self._app_ref.player is not None:
            player = self._app_ref.player
            was_current = index == player.queue.position
            player.queue.remove(index)
            if was_current:
                # Play the track that is now at the current position
                current = player.queue.current
                if current is not None:
                    player.play_track(current)
                else:
                    player.stop()
            self._refresh_queue_panel()

    def _on_queue_clear(self) -> None:
        """Clear the entire play queue."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.queue.clear()
            self._app_ref.player.stop()
            self._refresh_queue_panel()

    def _on_queue_move(self, from_index: int, to_index: int) -> None:
        """Reorder a track in the queue."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.queue.move(from_index, to_index)
            self._refresh_queue_panel()

    def _switch_page(self, page_name: str) -> None:
        """Switch the content stack to the requested page."""
        if not hasattr(self, "_stack"):
            return
        child = self._stack.get_child_by_name(page_name)
        if child:
            self._stack.set_visible_child_name(page_name)
            self._push_nav(page_name)

        self._refresh_page(page_name)

    def _refresh_page(self, page_name: str) -> None:
        """Refresh the data for *page_name* if it has a refresh method."""
        db = self._app_ref.db if self._app_ref else None
        refresh_map = {
            "home": lambda: (
                self._home_page.refresh(db) if db is not None else None
            ),
            "library": lambda: (
                self._library_view.refresh() if db is not None else None
            ),
            "favorites": lambda: (
                self._favorites_view.refresh() if db is not None else None
            ),
            "explore": lambda: self._explore_view.refresh(),
            "mixes": lambda: self._mixes_view.refresh(),
            "stats": lambda: (
                self._stats_view.refresh() if db is not None else None
            ),
        }
        refresher = refresh_map.get(page_name)
        if refresher is not None:
            try:
                refresher()
            except Exception:
                logger.warning(
                    "Failed to refresh %s page", page_name, exc_info=True
                )

    # ------------------------------------------------------------------
    # Keyboard shortcut helpers (called from app actions)
    # ------------------------------------------------------------------

    def navigate_to(self, page_name: str) -> None:
        """Switch to a named page (e.g. 'home', 'search', 'library')."""
        self._switch_page(page_name)

    def focus_search(self) -> None:
        """Navigate to the search page and focus the search entry."""
        self._switch_page("search")
        self._search_view.focus_entry()

    def toggle_lyrics_panel(self) -> None:
        """Toggle the lyrics side-panel visibility."""
        currently_visible = self._lyrics_panel.get_visible()
        # Toggle through the same path as the now-playing bar button
        self._on_lyrics_toggle(not currently_visible)
        self._now_playing.set_lyrics_active(not currently_visible)

    def toggle_queue_panel(self) -> None:
        """Toggle the queue side-panel visibility."""
        currently_visible = self._queue_panel.get_visible()
        self._on_queue_toggle(not currently_visible)
        self._now_playing.set_queue_active(not currently_visible)

    # ------------------------------------------------------------------
    # Mini player
    # ------------------------------------------------------------------

    def toggle_mini_player(self) -> None:
        """Toggle between the main window and the mini player."""
        if self._mini_player is not None and self._mini_player.get_visible():
            # Return to main window
            self._exit_mini_player()
        else:
            self._enter_mini_player()

    def _enter_mini_player(self) -> None:
        """Hide the main window and show the mini player."""
        if self._mini_player is None:
            self._mini_player = MiniPlayerWindow(
                on_play_pause=self._mini_play_pause,
                on_next=self._mini_next,
                on_close_request=self._exit_mini_player,
                on_maximize_request=self._exit_mini_player,
            )
            app = self.get_application()
            if app is not None:
                self._mini_player.set_application(app)

        # Sync current state to mini player
        if self._current_track is not None:
            self._mini_player.update_track(
                title=self._current_track.title,
                artist=self._current_track.artist,
            )
            # Load album art for the mini player (guarded against stale)
            _track = self._current_track

            def _on_mini_entry_art(pixbuf, _t=_track):
                if self._current_track is _t:
                    self._mini_player.set_album_art(pixbuf)

            scale = self.get_scale_factor() or 1
            art_48 = 48 * scale
            self._album_art_service.get_art_async(
                self._current_track,
                _on_mini_entry_art,
                width=art_48,
                height=art_48,
            )

        # Sync play state
        if self._app_ref and self._app_ref.player is not None:
            state = self._app_ref.player.state
            self._mini_player.set_playing(state == "playing")

        self.set_visible(False)
        self._mini_player.set_visible(True)
        self._mini_player.present()

    def _exit_mini_player(self) -> None:
        """Hide the mini player and show the main window."""
        if self._mini_player is not None:
            self._mini_player.set_visible(False)
        self.set_visible(True)
        self.present()

    def _mini_play_pause(self) -> None:
        """Handle play/pause from the mini player."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_pause()

    def _mini_next(self) -> None:
        """Handle next track from the mini player."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.next_track()

    # ------------------------------------------------------------------
    # Sleep timer
    # ------------------------------------------------------------------

    def open_sleep_timer(self) -> None:
        """Create and present the sleep timer dialog."""
        if self._app_ref and self._app_ref.sleep_timer is not None:
            dialog = SleepTimerDialog(
                sleep_timer=self._app_ref.sleep_timer,
                transient_for=self,
            )
            dialog.present()
            self._sleep_timer_dialog = dialog

    def set_sleep_timer_active(self, active: bool) -> None:
        """Update the now-playing bar sleep timer indicator."""
        self._now_playing.set_sleep_timer_active(active)

    def on_sleep_timer_tick(self, remaining_seconds: int) -> None:
        """Handle a sleep timer tick — update the indicator and dialog."""
        self._now_playing.set_sleep_timer_active(remaining_seconds > 0)
        # Update the dialog if it exists and is still open.
        dialog = getattr(self, "_sleep_timer_dialog", None)
        if dialog is not None:
            try:
                dialog.update_countdown(remaining_seconds)
                if remaining_seconds <= 0:
                    dialog.sync_active_state()
            except Exception:
                pass

    def adjust_volume(self, delta: float) -> None:
        """Adjust the volume slider by *delta* (e.g. +5.0 or -5.0).

        The value is clamped to [0, 100].  This updates both the UI slider
        and the player backend.
        """
        current = self._now_playing._volume_scale.get_value()
        new_val = max(0.0, min(100.0, current + delta))
        self._now_playing._volume_scale.set_value(new_val)
        # The volume-changed signal handler will propagate to the player.

    def toggle_mute(self) -> None:
        """Toggle mute/unmute.  Muting sets volume to 0; unmuting restores."""
        current = self._now_playing._volume_scale.get_value()
        if current > 0:
            self._pre_mute_volume = current
            self._now_playing._volume_scale.set_value(0)
        else:
            restored = getattr(self, "_pre_mute_volume", 70.0)
            self._now_playing._volume_scale.set_value(restored)

    # ------------------------------------------------------------------
    # Context menu handlers
    # ------------------------------------------------------------------

    def _get_playlists(self) -> list[dict]:
        """Return the current list of user playlists for context menus."""
        if self._app_ref and self._app_ref.db is not None:
            try:
                return self._app_ref.db.get_playlists()
            except Exception:
                logger.warning(
                    "Failed to get playlists for context menu",
                    exc_info=True,
                )
        return []

    def _on_context_play(self, track) -> None:
        """Play the given track immediately via context menu."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_queue([track], start_index=0)

    def _on_context_play_next(self, track) -> None:
        """Insert a track into the queue after the currently playing track."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.queue.insert_after_current(track)
            if self._queue_panel.get_visible():
                self._refresh_queue_panel()

    def _on_context_add_to_queue(self, track) -> None:
        """Append a track to the end of the play queue."""
        if self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.queue.add(track)
            if self._queue_panel.get_visible():
                self._refresh_queue_panel()

    def _on_context_add_to_playlist(self, track, playlist_id: int) -> None:
        """Add a track to the specified playlist."""
        if self._app_ref and self._app_ref.db is not None:
            try:
                if track.id is not None:
                    self._app_ref.db.add_track_to_playlist(
                        playlist_id, track.id
                    )
                    # Refresh sidebar playlist counts
                    self._sidebar.refresh_playlists()
            except Exception:
                logger.warning(
                    "Failed to add track to playlist", exc_info=True
                )

    def _on_context_new_playlist_with_track(self, track) -> None:
        """Create a new playlist and add the given track to it."""
        if self._app_ref and self._app_ref.db is not None:
            try:
                playlist_id = self._app_ref.db.create_playlist(
                    f"Playlist"
                )
                if track.id is not None:
                    self._app_ref.db.add_track_to_playlist(
                        playlist_id, track.id
                    )
                self._sidebar.refresh_playlists()
            except Exception:
                logger.warning(
                    "Failed to create playlist from context menu",
                    exc_info=True,
                )

    def _on_context_toggle_favorite(self, track) -> None:
        """Toggle the favorite state of a track.

        Also updates Tidal favourites when the track is a Tidal track
        and the Tidal provider is available.
        """
        if self._app_ref and self._app_ref.db is not None:
            try:
                if track.id is not None:
                    is_fav = self._app_ref.db.is_favorite(track.id)
                    new_state = not is_fav
                    self._app_ref.db.set_favorite(track.id, new_state)

                    # Update the now-playing bar heart if this is the current track
                    if (
                        self._current_track is not None
                        and self._current_track.id == track.id
                    ):
                        self._now_playing.set_favorite_active(new_state)

                    # Refresh the favorites view if visible
                    visible = self._stack.get_visible_child_name()
                    if visible == "favorites":
                        self._favorites_view.refresh()

                    # Sync the toggle to Tidal in a background thread
                    if (
                        getattr(track, "is_tidal", False)
                        and self._app_ref.tidal_provider is not None
                    ):
                        import threading

                        tidal = self._app_ref.tidal_provider
                        sid = track.source_id

                        def _sync_tidal(remove=is_fav):
                            try:
                                if remove:
                                    tidal.remove_favorite(sid)
                                else:
                                    tidal.add_favorite(sid)
                            except Exception:
                                logger.warning(
                                    "Failed to sync favorite toggle to Tidal",
                                    exc_info=True,
                                )

                        threading.Thread(
                            target=_sync_tidal, daemon=True
                        ).start()
            except Exception:
                logger.warning(
                    "Failed to toggle favorite from context menu",
                    exc_info=True,
                )

    def _on_context_go_to_album(self, track) -> None:
        """Navigate to the album detail view for the track's album."""
        if track.album:
            self._on_album_clicked(track.album, track.artist)

    def _on_context_go_to_artist(self, track) -> None:
        """Navigate to the artist detail view for the track's artist."""
        if track.artist:
            self._navigate_to_artist(track.artist)

    # ------------------------------------------------------------------
    # Album context menu handlers
    # ------------------------------------------------------------------

    def _get_album_tracks(
        self, album_name: str, artist: str
    ) -> list:
        """Fetch tracks for an album from the database."""
        if self._app_ref and self._app_ref.db is not None:
            try:
                return self._app_ref.db.get_tracks_by_album(
                    album_name, artist=artist
                )
            except Exception:
                logger.warning(
                    "Failed to fetch album tracks for context menu",
                    exc_info=True,
                )
        return []

    def _on_context_play_album(
        self, album_name: str, artist: str
    ) -> None:
        """Play all tracks in the album."""
        tracks = self._get_album_tracks(album_name, artist)
        if tracks and self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_queue(tracks, start_index=0)

    def _on_context_play_album_next(
        self, album_name: str, artist: str
    ) -> None:
        """Insert all album tracks into the queue after the current track."""
        tracks = self._get_album_tracks(album_name, artist)
        if tracks and self._app_ref and self._app_ref.player is not None:
            for track in reversed(tracks):
                self._app_ref.player.queue.insert_after_current(track)
            if self._queue_panel.get_visible():
                self._refresh_queue_panel()

    def _on_context_add_album_to_queue(
        self, album_name: str, artist: str
    ) -> None:
        """Append all album tracks to the end of the play queue."""
        tracks = self._get_album_tracks(album_name, artist)
        if tracks and self._app_ref and self._app_ref.player is not None:
            for track in tracks:
                self._app_ref.player.queue.add(track)
            if self._queue_panel.get_visible():
                self._refresh_queue_panel()

    def _on_context_add_album_to_playlist(
        self, album_name: str, artist: str, playlist_id: int
    ) -> None:
        """Add all tracks from an album to a playlist."""
        tracks = self._get_album_tracks(album_name, artist)
        if tracks and self._app_ref and self._app_ref.db is not None:
            try:
                for track in tracks:
                    if track.id is not None:
                        self._app_ref.db.add_track_to_playlist(
                            playlist_id, track.id
                        )
                self._sidebar.refresh_playlists()
            except Exception:
                logger.warning(
                    "Failed to add album to playlist", exc_info=True
                )

    def _on_context_new_playlist_with_album(
        self, album_name: str, artist: str
    ) -> None:
        """Create a new playlist and add all album tracks to it."""
        tracks = self._get_album_tracks(album_name, artist)
        if tracks and self._app_ref and self._app_ref.db is not None:
            try:
                playlist_id = self._app_ref.db.create_playlist(
                    album_name
                )
                for track in tracks:
                    if track.id is not None:
                        self._app_ref.db.add_track_to_playlist(
                            playlist_id, track.id
                        )
                self._sidebar.refresh_playlists()
            except Exception:
                logger.warning(
                    "Failed to create playlist from album context menu",
                    exc_info=True,
                )

    def _on_context_add_album_to_favorites(
        self, album_name: str, artist: str
    ) -> None:
        """Add all tracks from an album to favorites."""
        tracks = self._get_album_tracks(album_name, artist)
        if tracks and self._app_ref and self._app_ref.db is not None:
            try:
                for track in tracks:
                    if track.id is not None:
                        if not self._app_ref.db.is_favorite(track.id):
                            self._app_ref.db.set_favorite(
                                track.id, True
                            )
                # Update now-playing heart if current track is in this album
                if (
                    self._current_track is not None
                    and self._current_track.id is not None
                ):
                    is_fav = self._app_ref.db.is_favorite(
                        self._current_track.id
                    )
                    self._now_playing.set_favorite_active(is_fav)
                # Refresh favorites view if visible
                visible = self._stack.get_visible_child_name()
                if visible == "favorites":
                    self._favorites_view.refresh()
            except Exception:
                logger.warning(
                    "Failed to add album to favorites", exc_info=True
                )

    def _on_context_album_go_to_artist(
        self, _album_name: str, artist: str
    ) -> None:
        """Navigate to artist detail from an album context menu."""
        if artist:
            self._navigate_to_artist(artist)

    # ------------------------------------------------------------------
    # Artist context menu handlers
    # ------------------------------------------------------------------

    def _get_artist_tracks(self, artist_name: str) -> list:
        """Fetch all tracks by an artist from the database."""
        if self._app_ref and self._app_ref.db is not None:
            try:
                return self._app_ref.db.get_artist_tracks(artist_name)
            except Exception:
                logger.warning(
                    "Failed to fetch artist tracks for context menu",
                    exc_info=True,
                )
        return []

    def _on_context_play_all_by_artist(
        self, artist_name: str
    ) -> None:
        """Play all tracks by the artist."""
        tracks = self._get_artist_tracks(artist_name)
        if tracks and self._app_ref and self._app_ref.player is not None:
            self._app_ref.player.play_queue(tracks, start_index=0)

    def _on_context_add_all_by_artist(
        self, artist_name: str
    ) -> None:
        """Add all tracks by the artist to the queue."""
        tracks = self._get_artist_tracks(artist_name)
        if tracks and self._app_ref and self._app_ref.player is not None:
            for track in tracks:
                self._app_ref.player.queue.add(track)
            if self._queue_panel.get_visible():
                self._refresh_queue_panel()

    def _on_context_view_artist(self, artist_name: str) -> None:
        """Navigate to the artist detail page."""
        self._navigate_to_artist(artist_name)
