"""Main application window for Auxen."""

from __future__ import annotations

import logging

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk

from auxen.views.favorites import FavoritesView
from auxen.views.home import HomePage
from auxen.views.now_playing import NowPlayingBar
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

        split_view = Adw.NavigationSplitView()

        # ---- Sidebar ----
        self._sidebar = AuxenSidebar(
            on_navigate=self._switch_page,
            on_settings=self._open_settings,
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

        content_box.append(self._stack)

        # ---- Now Playing Bar (pinned at bottom of content area) ----
        self._now_playing = NowPlayingBar()
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
            self._now_playing.update_track(
                title=track.title,
                artist=track.artist,
                quality_label=track.quality_label,
                source=track.source.value,
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

    def _switch_page(self, page_name: str) -> None:
        """Switch the content stack to the requested page."""
        child = self._stack.get_child_by_name(page_name)
        if child:
            self._stack.set_visible_child_name(page_name)

        # Refresh favorites when switching to that page
        if page_name == "favorites" and self._app_ref and self._app_ref.db:
            try:
                self._favorites_view.refresh()
            except Exception:
                logger.warning(
                    "Failed to refresh favorites", exc_info=True
                )
