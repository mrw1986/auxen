"""Reusable right-click context menu for track rows in the Auxen music player."""

from __future__ import annotations

import logging
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gio, GLib, Gtk

logger = logging.getLogger(__name__)


class TrackContextMenu:
    """Build and show a GTK4 PopoverMenu for a track.

    Parameters
    ----------
    track_data:
        Dict with keys: id, title, artist, album, source, is_favorite.
    callbacks:
        Dict with keys:
            on_play, on_play_next, on_add_to_queue,
            on_add_to_playlist(playlist_id), on_new_playlist,
            on_toggle_favorite, on_go_to_album.
    playlists:
        List of dicts with keys: id, name (user playlists for the submenu).
    """

    def __init__(
        self,
        track_data: dict,
        callbacks: dict,
        playlists: Optional[list[dict]] = None,
    ) -> None:
        self._track_data = track_data
        self._callbacks = callbacks
        self._playlists = playlists or []
        self._popover: Optional[Gtk.PopoverMenu] = None
        self._action_group: Optional[Gio.SimpleActionGroup] = None

    def show(self, widget: Gtk.Widget, x: float, y: float) -> None:
        """Show the context menu at the given position relative to *widget*."""
        # Clean up any previous popover
        if self._popover is not None:
            self._popover.unparent()
            self._popover = None

        menu_model = self._build_menu_model()
        self._popover = Gtk.PopoverMenu.new_from_model(menu_model)
        self._popover.set_parent(widget)
        self._popover.set_has_arrow(False)
        self._popover.add_css_class("context-menu")

        # Position the popover at the click coordinates
        rect = Gdk_Rectangle(x, y)
        self._popover.set_pointing_to(rect)

        # Register action group on the widget so menu items resolve
        self._action_group = self._build_action_group()
        widget.insert_action_group("ctx", self._action_group)

        # Clean up action group when popover closes
        self._popover.connect("closed", self._on_popover_closed, widget)

        self._popover.popup()

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
        if self._track_data.get("album"):
            nav_section.append("Go to Album", "ctx.go-to-album")
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
        # Placeholder for future artist page navigation
        pass

    def _on_new_playlist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_new_playlist")
        if cb is not None:
            cb()

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    def _on_popover_closed(self, popover, widget) -> None:
        """Clean up the action group and popover when the menu closes."""
        widget.insert_action_group("ctx", None)
        # Schedule unparent to avoid destroying during signal emission
        GLib.idle_add(self._cleanup_popover)

    def _cleanup_popover(self) -> bool:
        """Unparent the popover on idle."""
        if self._popover is not None:
            self._popover.unparent()
            self._popover = None
        return GLib.SOURCE_REMOVE


def Gdk_Rectangle(x: float, y: float):  # noqa: N802
    """Create a Gdk.Rectangle at (x, y) with 1x1 size for popover pointing."""
    from gi.repository import Gdk

    rect = Gdk.Rectangle()
    rect.x = int(x)
    rect.y = int(y)
    rect.width = 1
    rect.height = 1
    return rect
