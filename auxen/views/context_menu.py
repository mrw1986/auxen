"""Reusable right-click context menus for the Auxen music player.

Provides context menus for tracks, albums, and artists that can be
attached to any widget via GestureClick.
"""

from __future__ import annotations

import logging
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gio, GLib, Gtk

logger = logging.getLogger(__name__)


# ------------------------------------------------------------------
# Shared helpers
# ------------------------------------------------------------------


def Gdk_Rectangle(x: float, y: float):  # noqa: N802
    """Create a Gdk.Rectangle at (x, y) with 1x1 size for popover pointing."""
    from gi.repository import Gdk

    rect = Gdk.Rectangle()
    rect.x = int(x)
    rect.y = int(y)
    rect.width = 1
    rect.height = 1
    return rect


class _BaseContextMenu:
    """Shared popover lifecycle management for all context menus."""

    def __init__(self) -> None:
        self._popover: Optional[Gtk.PopoverMenu] = None
        self._action_group: Optional[Gio.SimpleActionGroup] = None
        self._popover_parent: Optional[Gtk.Widget] = None
        self._popover_prefix: str = "ctx"

    def _show_popover(
        self,
        widget: Gtk.Widget,
        x: float,
        y: float,
        menu_model: Gio.Menu,
        action_group: Gio.SimpleActionGroup,
        prefix: str = "ctx",
    ) -> None:
        """Create and display a PopoverMenu at the click coordinates."""
        if self._popover is not None:
            # Remove old action group before unparenting
            if self._popover_parent is not None:
                self._popover_parent.insert_action_group(
                    self._popover_prefix, None
                )
            self._popover.unparent()
            self._popover = None

        self._popover = Gtk.PopoverMenu.new_from_model(menu_model)
        self._popover.set_parent(widget)
        self._popover.set_has_arrow(False)
        self._popover.add_css_class("context-menu")

        rect = Gdk_Rectangle(x, y)
        self._popover.set_pointing_to(rect)

        self._action_group = action_group
        self._popover_parent = widget
        self._popover_prefix = prefix
        widget.insert_action_group(prefix, self._action_group)

        self._popover.connect(
            "closed", self._on_popover_closed, widget, prefix
        )
        self._popover.popup()

    def _on_popover_closed(
        self, popover, widget: Gtk.Widget, prefix: str
    ) -> None:
        """Clean up the action group and popover when the menu closes."""
        # Only remove the action group if this closing popover is still
        # the active one; otherwise a newer popover owns the group.
        if popover is self._popover:
            widget.insert_action_group(prefix, None)
        GLib.idle_add(self._cleanup_popover, popover)

    def _cleanup_popover(self, popover: Gtk.PopoverMenu) -> bool:
        """Unparent the popover on idle (only if it still has a parent)."""
        if popover is self._popover:
            self._popover.unparent()
            self._popover = None
        elif popover is not None and popover.get_parent() is not None:
            popover.unparent()
        return GLib.SOURCE_REMOVE

    @staticmethod
    def _add_action(
        group: Gio.SimpleActionGroup,
        name: str,
        handler: Callable,
    ) -> None:
        """Helper to add a parameterless action to a group."""
        action = Gio.SimpleAction.new(name, None)
        action.connect("activate", handler)
        group.add_action(action)


# ------------------------------------------------------------------
# Track context menu
# ------------------------------------------------------------------


class TrackContextMenu(_BaseContextMenu):
    """Build and show a GTK4 PopoverMenu for a track.

    Parameters
    ----------
    track_data:
        Dict with keys: id, title, artist, album, source, is_favorite.
    callbacks:
        Dict with keys:
            on_play, on_play_next, on_add_to_queue,
            on_add_to_playlist(playlist_id), on_new_playlist,
            on_toggle_favorite, on_go_to_album, on_go_to_artist.
    playlists:
        List of dicts with keys: id, name (user playlists for the submenu).
    """

    def __init__(
        self,
        track_data: dict,
        callbacks: dict,
        playlists: Optional[list[dict]] = None,
    ) -> None:
        super().__init__()
        self._track_data = track_data
        self._callbacks = callbacks
        self._playlists = playlists or []

    def show(self, widget: Gtk.Widget, x: float, y: float) -> None:
        """Show the context menu at the given position relative to *widget*."""
        menu_model = self._build_menu_model()
        action_group = self._build_action_group()
        self._show_popover(widget, x, y, menu_model, action_group)

    # ------------------------------------------------------------------
    # Menu model construction
    # ------------------------------------------------------------------

    def _build_menu_model(self) -> Gio.Menu:
        """Construct the full Gio.Menu model for the context menu."""
        menu = Gio.Menu()

        # Section 1: Playback actions
        playback_section = Gio.Menu()
        playback_section.append("Play", "ctx.play")
        playback_section.append("Play Next", "ctx.play-next")
        playback_section.append("Add to Queue", "ctx.add-to-queue")
        menu.append_section(None, playback_section)

        # Section 2: Playlist + Favorite actions
        organize_section = Gio.Menu()

        # Build playlist submenu
        playlist_submenu = Gio.Menu()
        for playlist in self._playlists:
            playlist_submenu.append(
                playlist["name"],
                f"ctx.add-to-playlist-{playlist['id']}",
            )
        # "New Playlist..." entry
        playlist_submenu.append("New Playlist\u2026", "ctx.new-playlist")

        organize_section.append_submenu(
            "Add to Playlist", playlist_submenu
        )

        # Favorite toggle
        is_fav = self._track_data.get("is_favorite", False)
        fav_label = (
            "Remove from Favorites" if is_fav else "Add to Favorites"
        )
        organize_section.append(fav_label, "ctx.toggle-favorite")
        menu.append_section(None, organize_section)

        # Section 3: Navigation
        nav_section = Gio.Menu()
        if self._track_data.get("album") and self._track_data.get("artist"):
            nav_section.append("Go to Album", "ctx.go-to-album")
        if self._track_data.get("artist"):
            nav_section.append("Go to Artist", "ctx.go-to-artist")
        menu.append_section(None, nav_section)

        return menu

    # ------------------------------------------------------------------
    # Action group construction
    # ------------------------------------------------------------------

    def _build_action_group(self) -> Gio.SimpleActionGroup:
        """Create a Gio.SimpleActionGroup with all context menu actions."""
        group = Gio.SimpleActionGroup()

        self._add_action(group, "play", self._on_play)
        self._add_action(group, "play-next", self._on_play_next)
        self._add_action(group, "add-to-queue", self._on_add_to_queue)
        self._add_action(
            group, "toggle-favorite", self._on_toggle_favorite
        )
        self._add_action(group, "go-to-album", self._on_go_to_album)
        self._add_action(group, "go-to-artist", self._on_go_to_artist)
        self._add_action(group, "new-playlist", self._on_new_playlist)

        # Per-playlist actions
        for playlist in self._playlists:
            pid = playlist["id"]
            action_name = f"add-to-playlist-{pid}"

            def _make_handler(playlist_id):
                def handler(_action, _param):
                    cb = self._callbacks.get("on_add_to_playlist")
                    if cb is not None:
                        cb(playlist_id)

                return handler

            action = Gio.SimpleAction.new(action_name, None)
            action.connect("activate", _make_handler(pid))
            group.add_action(action)

        return group

    # ------------------------------------------------------------------
    # Action handlers
    # ------------------------------------------------------------------

    def _on_play(self, _action, _param) -> None:
        cb = self._callbacks.get("on_play")
        if cb is not None:
            cb()

    def _on_play_next(self, _action, _param) -> None:
        cb = self._callbacks.get("on_play_next")
        if cb is not None:
            cb()

    def _on_add_to_queue(self, _action, _param) -> None:
        cb = self._callbacks.get("on_add_to_queue")
        if cb is not None:
            cb()

    def _on_toggle_favorite(self, _action, _param) -> None:
        cb = self._callbacks.get("on_toggle_favorite")
        if cb is not None:
            cb()

    def _on_go_to_album(self, _action, _param) -> None:
        cb = self._callbacks.get("on_go_to_album")
        if cb is not None:
            cb()

    def _on_go_to_artist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_go_to_artist")
        if cb is not None:
            cb()

    def _on_new_playlist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_new_playlist")
        if cb is not None:
            cb()


# ------------------------------------------------------------------
# Album context menu
# ------------------------------------------------------------------


class AlbumContextMenu(_BaseContextMenu):
    """Build and show a GTK4 PopoverMenu for an album card.

    Parameters
    ----------
    album_data:
        Dict with keys: album, artist, source.
    callbacks:
        Dict with keys:
            on_play_album, on_play_album_next, on_add_album_to_queue,
            on_add_to_playlist(playlist_id), on_new_playlist,
            on_add_to_favorites, on_go_to_artist.
    playlists:
        List of dicts with keys: id, name (user playlists for the submenu).
    """

    def __init__(
        self,
        album_data: dict,
        callbacks: dict,
        playlists: Optional[list[dict]] = None,
    ) -> None:
        super().__init__()
        self._album_data = album_data
        self._callbacks = callbacks
        self._playlists = playlists or []

    def show(self, widget: Gtk.Widget, x: float, y: float) -> None:
        """Show the album context menu at the click position."""
        menu_model = self._build_menu_model()
        action_group = self._build_action_group()
        self._show_popover(widget, x, y, menu_model, action_group)

    def _build_menu_model(self) -> Gio.Menu:
        """Construct the Gio.Menu model for the album context menu."""
        menu = Gio.Menu()

        # Section 1: Playback actions
        playback_section = Gio.Menu()
        playback_section.append("Play Album", "ctx.play-album")
        playback_section.append("Play Album Next", "ctx.play-album-next")
        playback_section.append(
            "Add Album to Queue", "ctx.add-album-to-queue"
        )
        menu.append_section(None, playback_section)

        # Section 2: Organize actions
        organize_section = Gio.Menu()

        # Playlist submenu
        playlist_submenu = Gio.Menu()
        for playlist in self._playlists:
            playlist_submenu.append(
                playlist["name"],
                f"ctx.add-to-playlist-{playlist['id']}",
            )
        playlist_submenu.append("New Playlist\u2026", "ctx.new-playlist")
        organize_section.append_submenu(
            "Add to Playlist", playlist_submenu
        )

        organize_section.append(
            "Add to Favorites", "ctx.add-to-favorites"
        )
        menu.append_section(None, organize_section)

        # Section 3: Navigation
        if self._album_data.get("artist"):
            nav_section = Gio.Menu()
            nav_section.append("Go to Artist", "ctx.go-to-artist")
            menu.append_section(None, nav_section)

        return menu

    def _build_action_group(self) -> Gio.SimpleActionGroup:
        """Create a Gio.SimpleActionGroup with album context actions."""
        group = Gio.SimpleActionGroup()

        self._add_action(group, "play-album", self._on_play_album)
        self._add_action(
            group, "play-album-next", self._on_play_album_next
        )
        self._add_action(
            group, "add-album-to-queue", self._on_add_album_to_queue
        )
        self._add_action(
            group, "add-to-favorites", self._on_add_to_favorites
        )
        self._add_action(group, "go-to-artist", self._on_go_to_artist)
        self._add_action(group, "new-playlist", self._on_new_playlist)

        # Per-playlist actions
        for playlist in self._playlists:
            pid = playlist["id"]
            action_name = f"add-to-playlist-{pid}"

            def _make_handler(playlist_id):
                def handler(_action, _param):
                    cb = self._callbacks.get("on_add_to_playlist")
                    if cb is not None:
                        cb(playlist_id)

                return handler

            action = Gio.SimpleAction.new(action_name, None)
            action.connect("activate", _make_handler(pid))
            group.add_action(action)

        return group

    def _on_play_album(self, _action, _param) -> None:
        cb = self._callbacks.get("on_play_album")
        if cb is not None:
            cb()

    def _on_play_album_next(self, _action, _param) -> None:
        cb = self._callbacks.get("on_play_album_next")
        if cb is not None:
            cb()

    def _on_add_album_to_queue(self, _action, _param) -> None:
        cb = self._callbacks.get("on_add_album_to_queue")
        if cb is not None:
            cb()

    def _on_add_to_favorites(self, _action, _param) -> None:
        cb = self._callbacks.get("on_add_to_favorites")
        if cb is not None:
            cb()

    def _on_go_to_artist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_go_to_artist")
        if cb is not None:
            cb()

    def _on_new_playlist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_new_playlist")
        if cb is not None:
            cb()


# ------------------------------------------------------------------
# Artist context menu
# ------------------------------------------------------------------


class ArtistContextMenu(_BaseContextMenu):
    """Build and show a GTK4 PopoverMenu for an artist.

    Parameters
    ----------
    artist_data:
        Dict with keys: artist.
    callbacks:
        Dict with keys:
            on_play_all, on_add_all_to_queue, on_view_artist.
    """

    def __init__(
        self,
        artist_data: dict,
        callbacks: dict,
    ) -> None:
        super().__init__()
        self._artist_data = artist_data
        self._callbacks = callbacks

    def show(self, widget: Gtk.Widget, x: float, y: float) -> None:
        """Show the artist context menu at the click position."""
        menu_model = self._build_menu_model()
        action_group = self._build_action_group()
        self._show_popover(widget, x, y, menu_model, action_group)

    def _build_menu_model(self) -> Gio.Menu:
        """Construct the Gio.Menu model for the artist context menu."""
        menu = Gio.Menu()

        # Section 1: Playback actions
        playback_section = Gio.Menu()
        playback_section.append(
            "Play All by Artist", "ctx.play-all"
        )
        playback_section.append(
            "Add All to Queue", "ctx.add-all-to-queue"
        )
        menu.append_section(None, playback_section)

        # Section 2: Navigation
        nav_section = Gio.Menu()
        nav_section.append(
            "View Artist Details", "ctx.view-artist"
        )
        menu.append_section(None, nav_section)

        return menu

    def _build_action_group(self) -> Gio.SimpleActionGroup:
        """Create a Gio.SimpleActionGroup with artist context actions."""
        group = Gio.SimpleActionGroup()

        self._add_action(group, "play-all", self._on_play_all)
        self._add_action(
            group, "add-all-to-queue", self._on_add_all_to_queue
        )
        self._add_action(group, "view-artist", self._on_view_artist)

        return group

    def _on_play_all(self, _action, _param) -> None:
        cb = self._callbacks.get("on_play_all")
        if cb is not None:
            cb()

    def _on_add_all_to_queue(self, _action, _param) -> None:
        cb = self._callbacks.get("on_add_all_to_queue")
        if cb is not None:
            cb()

    def _on_view_artist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_view_artist")
        if cb is not None:
            cb()
