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
        """Clean up the action group and popover when the menu closes.

        The action group removal is deferred to an idle callback so that
        any action triggered by a menu-item click has a chance to fire
        before the group is removed.  In GTK4 PopoverMenu, the "closed"
        signal is emitted *before* the action's "activate" signal, so
        removing the group synchronously here would silently prevent the
        action from ever executing.
        """
        if popover is self._popover:
            GLib.idle_add(
                self._deferred_remove_action_group, widget, prefix, popover
            )
        GLib.idle_add(self._cleanup_popover, popover)

    def _deferred_remove_action_group(
        self, widget: Gtk.Widget, prefix: str, expected_popover
    ) -> bool:
        """Remove the action group on idle, after actions have fired.

        Only removes if the popover that triggered this is still the
        current one (prevents removing a newer menu's action group).
        """
        # A newer popover may have been opened; don't touch its group.
        if expected_popover is not self._popover and self._popover is not None:
            return GLib.SOURCE_REMOVE
        try:
            widget.insert_action_group(prefix, None)
        except Exception:
            pass
        return GLib.SOURCE_REMOVE

    def _cleanup_popover(self, popover: Gtk.PopoverMenu) -> bool:
        """Unparent the popover on idle (only if it still has a parent)."""
        if popover is self._popover:
            if self._popover.get_parent() is not None:
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
        Dict with keys: id, title, artist, album, source, is_favorite,
        source_id (optional — needed for Track Radio / Credits).
    callbacks:
        Dict with keys:
            on_play, on_play_next, on_add_to_queue,
            on_add_to_playlist(playlist_id), on_new_playlist,
            on_toggle_favorite, on_go_to_album, on_go_to_artist,
            on_track_radio, on_credits.
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
        src = self._track_data.get("source", "")
        # Source may be a Source enum or a string
        src_val = getattr(src, "value", src) if src else ""
        is_tidal = str(src_val).lower() == "tidal"

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
            "Remove from Collection" if is_fav else "Add to Collection"
        )
        organize_section.append(fav_label, "ctx.toggle-favorite")
        menu.append_section(None, organize_section)

        # Section 3: Navigation
        nav_section = Gio.Menu()
        if self._track_data.get("album") and self._track_data.get("artist"):
            nav_section.append("Go to Album", "ctx.go-to-album")
        if self._track_data.get("artist"):
            nav_section.append("Go to Artist", "ctx.go-to-artist")
        # Track Radio (Tidal only)
        if is_tidal and self._callbacks.get("on_track_radio"):
            nav_section.append("Go to Track Radio", "ctx.track-radio")
        # Start Mix (Tidal only)
        if is_tidal and self._callbacks.get("on_track_mix"):
            nav_section.append("Start Mix", "ctx.track-mix")
        menu.append_section(None, nav_section)

        # Section 4: View Lyrics + Tidal extras (Credits)
        extras_section = Gio.Menu()
        if self._callbacks.get("on_view_lyrics"):
            extras_section.append("View Lyrics", "ctx.view-lyrics")
        if is_tidal and self._callbacks.get("on_credits"):
            extras_section.append("Credits", "ctx.credits")
        if extras_section.get_n_items() > 0:
            menu.append_section(None, extras_section)

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
        self._add_action(group, "track-radio", self._on_track_radio)
        self._add_action(group, "track-mix", self._on_track_mix)
        self._add_action(group, "view-lyrics", self._on_view_lyrics)
        self._add_action(group, "credits", self._on_credits)

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

    def _on_track_radio(self, _action, _param) -> None:
        cb = self._callbacks.get("on_track_radio")
        if cb is not None:
            cb()

    def _on_track_mix(self, _action, _param) -> None:
        cb = self._callbacks.get("on_track_mix")
        if cb is not None:
            cb()

    def _on_view_lyrics(self, _action, _param) -> None:
        cb = self._callbacks.get("on_view_lyrics")
        if cb is not None:
            cb()

    def _on_credits(self, _action, _param) -> None:
        cb = self._callbacks.get("on_credits")
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
            on_add_to_favorites, on_go_to_artist, on_shuffle_album.
    playlists:
        List of dicts with keys: id, name (user playlists for the submenu).
    """

    def __init__(
        self,
        album_data: dict,
        callbacks: dict,
        playlists: Optional[list[dict]] = None,
        is_saved_in_collection: bool = False,
    ) -> None:
        super().__init__()
        self._album_data = album_data
        self._callbacks = callbacks
        self._playlists = playlists or []
        self._is_saved_in_collection = is_saved_in_collection

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
        playback_section.append("Shuffle Album", "ctx.shuffle-album")
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

        if self._is_saved_in_collection:
            organize_section.append(
                "Remove from Collection", "ctx.remove-from-collection"
            )
        else:
            organize_section.append(
                "Save to Collection", "ctx.add-to-favorites"
            )
        menu.append_section(None, organize_section)

        # Section 3: Navigation
        if self._album_data.get("artist"):
            nav_section = Gio.Menu()
            nav_section.append("Go to Artist", "ctx.go-to-artist")
            menu.append_section(None, nav_section)

        # Section 4: Properties
        if self._callbacks.get("on_properties"):
            props_section = Gio.Menu()
            props_section.append("Properties\u2026", "ctx.properties")
            menu.append_section(None, props_section)

        return menu

    def _build_action_group(self) -> Gio.SimpleActionGroup:
        """Create a Gio.SimpleActionGroup with album context actions."""
        group = Gio.SimpleActionGroup()

        self._add_action(group, "play-album", self._on_play_album)
        self._add_action(group, "shuffle-album", self._on_shuffle_album)
        self._add_action(
            group, "play-album-next", self._on_play_album_next
        )
        self._add_action(
            group, "add-album-to-queue", self._on_add_album_to_queue
        )
        self._add_action(
            group, "add-to-favorites", self._on_add_to_favorites
        )
        self._add_action(
            group,
            "remove-from-collection",
            self._on_remove_from_collection,
        )
        self._add_action(group, "go-to-artist", self._on_go_to_artist)
        self._add_action(group, "new-playlist", self._on_new_playlist)
        self._add_action(group, "properties", self._on_properties)

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

    def _on_shuffle_album(self, _action, _param) -> None:
        cb = self._callbacks.get("on_shuffle_album")
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

    def _on_remove_from_collection(self, _action, _param) -> None:
        cb = self._callbacks.get("on_remove_from_collection")
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

    def _on_properties(self, _action, _param) -> None:
        cb = self._callbacks.get("on_properties")
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
        Dict with keys: artist, is_followed (optional).
    callbacks:
        Dict with keys:
            on_play_all, on_add_all_to_queue, on_view_artist,
            on_artist_radio, on_follow_artist, on_unfollow_artist.
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

        # Shuffle
        if self._callbacks.get("on_shuffle_artist"):
            playback_section.append(
                "Shuffle Artist", "ctx.shuffle-artist"
            )

        # Section 2: Navigation + Radio
        nav_section = Gio.Menu()
        nav_section.append(
            "View Artist Details", "ctx.view-artist"
        )
        if self._callbacks.get("on_artist_radio"):
            nav_section.append(
                "Go to Artist Radio", "ctx.artist-radio"
            )
        if self._callbacks.get("on_artist_mix"):
            nav_section.append(
                "Start Mix", "ctx.artist-mix"
            )
        menu.append_section(None, nav_section)

        # Section 3: Follow / Unfollow (Tidal)
        if self._callbacks.get("on_follow_artist") or self._callbacks.get("on_unfollow_artist"):
            follow_section = Gio.Menu()
            is_followed = self._artist_data.get("is_followed", False)
            if is_followed:
                follow_section.append(
                    "Unfollow Artist", "ctx.unfollow-artist"
                )
            else:
                follow_section.append(
                    "Follow Artist", "ctx.follow-artist"
                )
            menu.append_section(None, follow_section)

        # Properties section
        if self._callbacks.get("on_properties"):
            props_section = Gio.Menu()
            props_section.append("Properties\u2026", "ctx.properties")
            menu.append_section(None, props_section)

        return menu

    def _build_action_group(self) -> Gio.SimpleActionGroup:
        """Create a Gio.SimpleActionGroup with artist context actions."""
        group = Gio.SimpleActionGroup()

        self._add_action(group, "play-all", self._on_play_all)
        self._add_action(
            group, "add-all-to-queue", self._on_add_all_to_queue
        )
        self._add_action(group, "view-artist", self._on_view_artist)
        self._add_action(group, "artist-radio", self._on_artist_radio)
        self._add_action(group, "artist-mix", self._on_artist_mix)
        self._add_action(group, "shuffle-artist", self._on_shuffle_artist)
        self._add_action(group, "follow-artist", self._on_follow_artist)
        self._add_action(group, "unfollow-artist", self._on_unfollow_artist)
        self._add_action(group, "properties", self._on_properties)

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

    def _on_artist_radio(self, _action, _param) -> None:
        cb = self._callbacks.get("on_artist_radio")
        if cb is not None:
            cb()

    def _on_artist_mix(self, _action, _param) -> None:
        cb = self._callbacks.get("on_artist_mix")
        if cb is not None:
            cb()

    def _on_shuffle_artist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_shuffle_artist")
        if cb is not None:
            cb()

    def _on_follow_artist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_follow_artist")
        if cb is not None:
            cb()

    def _on_unfollow_artist(self, _action, _param) -> None:
        cb = self._callbacks.get("on_unfollow_artist")
        if cb is not None:
            cb()

    def _on_properties(self, _action, _param) -> None:
        cb = self._callbacks.get("on_properties")
        if cb is not None:
            cb()
