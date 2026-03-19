"""Listening statistics view for the Auxen music player."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Callable, Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gtk, Pango

from auxen.views.widgets import DragScrollHelper

logger = logging.getLogger(__name__)


def _format_hour(hour: int | None) -> str:
    """Format an hour (0-23) as a human-readable time string."""
    if hour is None:
        return "--"
    if hour == 0:
        return "12 AM"
    if hour < 12:
        return f"{hour} AM"
    if hour == 12:
        return "12 PM"
    return f"{hour - 12} PM"


class StatsView(Gtk.ScrolledWindow):
    """Scrollable page showing listening statistics and play history."""

    __gtype_name__ = "StatsView"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._drag_scroll = DragScrollHelper(self)

        self._db = None

        # Callbacks for click navigation
        self._on_artist_clicked: Optional[Callable[[str], None]] = None
        self._on_track_clicked: Optional[Callable] = None

        # Context menu callbacks
        self._context_callbacks: Optional[dict] = None
        self._get_playlists: Optional[Callable] = None
        self._current_menu = None

        # Store raw data for click handlers
        self._top_artists_data: list[tuple[str, int]] = []
        self._top_tracks_data: list[tuple[int, str, str, int]] = []
        self._daily_chart_data: list[tuple[str, int]] = []

        # Root container
        root = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=24,
        )
        root.set_margin_top(24)
        root.set_margin_bottom(24)
        root.set_margin_start(32)
        root.set_margin_end(32)

        # ---- 1. Header ----
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        header_box.add_css_class("stats-header")

        header_icon = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        header_icon.set_pixel_size(36)
        header_icon.add_css_class("stats-header-icon")
        header_box.append(header_icon)

        header_label = Gtk.Label(label="Your Listening")
        header_label.set_xalign(0)
        header_label.add_css_class("greeting-label")
        header_box.append(header_label)

        root.append(header_box)

        # ---- 2. Stat cards (two rows) ----
        stats_row_1 = Gtk.FlowBox()
        stats_row_1.set_homogeneous(True)
        stats_row_1.set_min_children_per_line(1)
        stats_row_1.set_max_children_per_line(4)
        stats_row_1.set_column_spacing(16)
        stats_row_1.set_row_spacing(8)
        stats_row_1.set_selection_mode(Gtk.SelectionMode.NONE)

        (
            total_card,
            self._total_played_label,
        ) = self._make_stat_card(
            icon_name="media-playback-start-symbolic",
            value="0",
            label="Total Played",
            accent_class="stats-accent-amber",
        )
        stats_row_1.append(total_card)

        (
            time_card,
            self._listen_time_label,
        ) = self._make_stat_card(
            icon_name="preferences-system-time-symbolic",
            value="0h",
            label="Listen Time",
            accent_class="stats-accent-amber",
        )
        stats_row_1.append(time_card)

        (
            daily_card,
            self._daily_avg_label,
        ) = self._make_stat_card(
            icon_name="x-office-calendar-symbolic",
            value="0",
            label="Daily Average",
            accent_class="stats-accent-amber",
        )
        stats_row_1.append(daily_card)

        (
            streak_card,
            self._streak_label,
        ) = self._make_stat_card(
            icon_name="emblem-ok-symbolic",
            value="0",
            label="Day Streak",
            accent_class="stats-accent-amber",
        )
        stats_row_1.append(streak_card)

        root.append(stats_row_1)

        stats_row_2 = Gtk.FlowBox()
        stats_row_2.set_homogeneous(True)
        stats_row_2.set_min_children_per_line(1)
        stats_row_2.set_max_children_per_line(5)
        stats_row_2.set_column_spacing(16)
        stats_row_2.set_row_spacing(8)
        stats_row_2.set_selection_mode(Gtk.SelectionMode.NONE)

        (
            artists_card,
            self._unique_artists_label,
        ) = self._make_stat_card(
            icon_name="system-users-symbolic",
            value="0",
            label="Unique Artists",
            accent_class="stats-accent-amber",
        )
        stats_row_2.append(artists_card)

        (
            albums_card,
            self._unique_albums_label,
        ) = self._make_stat_card(
            icon_name="media-optical-symbolic",
            value="0",
            label="Unique Albums",
            accent_class="stats-accent-amber",
        )
        stats_row_2.append(albums_card)

        (
            avg_len_card,
            self._avg_track_len_label,
        ) = self._make_stat_card(
            icon_name="document-open-recent-symbolic",
            value="0:00",
            label="Avg Track Length",
            accent_class="stats-accent-amber",
        )
        stats_row_2.append(avg_len_card)

        (
            source_card,
            self._source_split_label,
        ) = self._make_stat_card(
            icon_name="network-server-symbolic",
            value="--",
            label="Tidal / Local",
            accent_class="stats-accent-amber",
        )
        stats_row_2.append(source_card)

        # Most Active Hour — inline as a regular stat card
        (
            active_hour_card,
            self._active_hour_label,
        ) = self._make_stat_card(
            icon_name="appointment-symbolic",
            value="--",
            label="Most Active Hour",
            accent_class="stats-accent-amber",
        )
        stats_row_2.append(active_hour_card)

        root.append(stats_row_2)

        # ---- 3. 7-Day Listening Activity chart (full width) ----
        chart_card = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        chart_card.add_css_class("stats-card")
        chart_card.set_margin_top(4)
        chart_card.set_margin_bottom(4)

        chart_title = Gtk.Label(label="7-Day Activity")
        chart_title.set_xalign(0)
        chart_title.add_css_class("body")
        chart_card.append(chart_title)

        self._chart_area = Gtk.DrawingArea()
        self._chart_area.set_size_request(-1, 120)
        self._chart_area.set_hexpand(True)
        self._chart_area.set_vexpand(True)
        self._chart_area.set_draw_func(self._draw_daily_chart)
        chart_card.append(self._chart_area)

        root.append(chart_card)

        # ---- 4. Top Artists ----
        artists_header = Gtk.Label(label="Top Artists")
        artists_header.set_xalign(0)
        artists_header.add_css_class("section-header")
        root.append(artists_header)

        self._artists_list = Gtk.ListBox()
        self._artists_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._artists_list.add_css_class("boxed-list")
        root.append(self._artists_list)

        # ---- 5. Top Tracks ----
        tracks_header = Gtk.Label(label="Top Tracks")
        tracks_header.set_xalign(0)
        tracks_header.add_css_class("section-header")
        root.append(tracks_header)

        self._tracks_list = Gtk.ListBox()
        self._tracks_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._tracks_list.add_css_class("boxed-list")
        root.append(self._tracks_list)

        # ---- Empty state (shown when no data) ----
        self._empty_state = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        self._empty_state.add_css_class("stats-empty-state")
        self._empty_state.set_margin_top(48)

        empty_icon = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        empty_icon.set_pixel_size(64)
        self._empty_state.append(empty_icon)

        empty_label = Gtk.Label(
            label="No listening history yet"
        )
        empty_label.add_css_class("title-3")
        self._empty_state.append(empty_label)

        empty_sub = Gtk.Label(
            label="Play some tracks and your stats will appear here"
        )
        empty_sub.add_css_class("dim-label")
        self._empty_state.append(empty_sub)

        self._empty_state.set_visible(False)
        root.append(self._empty_state)

        self.set_child(root)

    # ---- Public API ----

    def set_database(self, db) -> None:
        """Wire the view to a database instance."""
        self._db = db

    def set_callbacks(
        self,
        on_artist_clicked: Callable[[str], None],
        on_track_clicked: Callable,
    ) -> None:
        """Set navigation callbacks for clickable items.

        Parameters
        ----------
        on_artist_clicked:
            Called with (artist_name) when an artist row is left-clicked.
        on_track_clicked:
            Called with (track_id, title, artist) when a track row is
            left-clicked.  ``track_id`` is the database primary key.
        """
        self._on_artist_clicked = on_artist_clicked
        self._on_track_clicked = on_track_clicked

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists: Callable,
    ) -> None:
        """Set callback functions for right-click context menus.

        Parameters
        ----------
        callbacks:
            Dict with keys: on_play, on_play_next, on_add_to_queue,
            on_add_to_playlist, on_new_playlist, on_toggle_favorite,
            on_go_to_album, on_go_to_artist.
        get_playlists:
            Callable that returns list of playlist dicts for context menu.
        """
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    def refresh(self) -> None:
        """Reload stats from the database."""
        if self._db is None:
            return

        try:
            stats = self._db.get_listening_stats()
        except Exception:
            logger.warning(
                "Failed to load listening stats", exc_info=True
            )
            return

        total = stats.get("total_tracks_played", 0)
        hours = stats.get("total_listen_time_hours", 0)
        avg = stats.get("avg_tracks_per_day", 0)
        active_hour = stats.get("most_active_hour")
        top_artists = stats.get("top_artists", [])
        top_tracks = stats.get("top_tracks", [])
        unique_artists = stats.get("unique_artists", 0)
        unique_albums = stats.get("unique_albums", 0)
        avg_track_secs = stats.get("avg_track_seconds", 0)
        current_streak = stats.get("current_streak", 0)
        source_counts = stats.get("source_counts", {})

        # Store raw data for click handlers
        self._top_artists_data = top_artists
        self._top_tracks_data = top_tracks

        # Update stat cards — row 1
        self._total_played_label.set_label(str(total))
        self._listen_time_label.set_label(f"{hours}h")
        self._daily_avg_label.set_label(str(avg))
        self._streak_label.set_label(
            f"{current_streak}d" if current_streak else "0"
        )
        self._active_hour_label.set_label(
            _format_hour(active_hour)
        )

        # Update stat cards — row 2
        self._unique_artists_label.set_label(str(unique_artists))
        self._unique_albums_label.set_label(str(unique_albums))
        mins = avg_track_secs // 60
        secs = avg_track_secs % 60
        self._avg_track_len_label.set_label(f"{mins}:{secs:02d}")
        tidal_ct = source_counts.get("tidal", 0)
        local_ct = source_counts.get("local", 0)
        self._source_split_label.set_label(f"{tidal_ct} / {local_ct}")

        # Load 7-day chart data
        try:
            self._daily_chart_data = self._db.get_daily_listening_stats(
                days=7
            )
        except Exception:
            logger.warning(
                "Failed to load daily listening stats", exc_info=True
            )
            self._daily_chart_data = []

        # Redraw the chart
        self._chart_area.queue_draw()

        # Toggle empty state
        has_data = total > 0
        self._empty_state.set_visible(not has_data)

        # Rebuild top artists
        self._clear_list_box(self._artists_list)
        if top_artists:
            max_count = top_artists[0][1] if top_artists else 1
            for _idx, (artist, count) in enumerate(top_artists):
                row = self._make_artist_row(
                    artist, count, max_count
                )
                self._attach_artist_click(row, artist)
                self._attach_artist_context_gesture(row, artist)
                self._artists_list.append(row)

        # Rebuild top tracks
        self._clear_list_box(self._tracks_list)
        if top_tracks:
            max_count = top_tracks[0][3] if top_tracks else 1
            for _idx, (track_id, title, artist, count) in enumerate(
                top_tracks
            ):
                row = self._make_track_row(
                    title, artist, count, max_count
                )
                self._attach_track_click(row, track_id, title, artist)
                self._attach_track_context_gesture(
                    row, track_id, title, artist
                )
                self._tracks_list.append(row)

    # ---- Click handlers ----

    def _attach_artist_click(
        self, row: Gtk.ListBoxRow, artist: str
    ) -> None:
        """Attach a left-click gesture to navigate to an artist."""
        if self._on_artist_clicked is None:
            return

        gesture = Gtk.GestureClick(button=1)

        def _on_click(_g, _n_press, _x, _y, a=artist):
            if self._on_artist_clicked is not None:
                self._on_artist_clicked(a)

        gesture.connect("pressed", _on_click)
        row.add_controller(gesture)
        # Visual hint that the row is clickable
        row.set_cursor_from_name("pointer")

    def _attach_track_click(
        self,
        row: Gtk.ListBoxRow,
        track_id: int,
        title: str,
        artist: str,
    ) -> None:
        """Attach a left-click gesture to play a track."""
        if self._on_track_clicked is None:
            return

        gesture = Gtk.GestureClick(button=1)

        def _on_click(
            _g, _n_press, _x, _y, tid=track_id, t=title, a=artist
        ):
            if self._on_track_clicked is not None:
                self._on_track_clicked(tid, t, a)

        gesture.connect("pressed", _on_click)
        row.add_controller(gesture)
        row.set_cursor_from_name("pointer")

    # ---- Context menu handlers ----

    def _attach_artist_context_gesture(
        self, row: Gtk.ListBoxRow, artist: str
    ) -> None:
        """Attach a right-click gesture for artist context menu."""
        if self._context_callbacks is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(g, n_press, x, y, a=artist):
            if n_press != 1:
                return
            self._show_artist_context_menu(row, x, y, a)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_artist_context_menu(
        self,
        widget: Gtk.Widget,
        x: float,
        y: float,
        artist: str,
    ) -> None:
        """Create and display a context menu for an artist."""
        if self._context_callbacks is None:
            return

        from auxen.views.context_menu import ArtistContextMenu

        _noop = lambda *_args: None
        callbacks = {
            "on_play_all": lambda a=artist: (
                self._play_all_by_artist(a)
            ),
            "on_add_all_to_queue": lambda a=artist: (
                self._add_all_by_artist_to_queue(a)
            ),
            "on_view_artist": lambda a=artist: (
                self._on_artist_clicked(a)
                if self._on_artist_clicked is not None
                else None
            ),
            "on_artist_radio": lambda a=artist: self._context_callbacks.get("on_artist_radio", _noop)(a),
            "on_artist_mix": lambda a=artist: self._context_callbacks.get("on_artist_mix", _noop)(a),
            "on_follow_artist": lambda a=artist: self._context_callbacks.get("on_follow_artist", _noop)(a),
            "on_unfollow_artist": lambda a=artist: self._context_callbacks.get("on_unfollow_artist", _noop)(a),
            "on_shuffle_artist": lambda a=artist: self._context_callbacks.get("on_shuffle_artist", _noop)(a),
            "on_properties": lambda a=artist: self._context_callbacks.get("on_artist_properties", _noop)(a),
        }

        self._current_menu = ArtistContextMenu(
            artist_data={"artist": artist},
            callbacks=callbacks,
        )
        self._current_menu.show(widget, x, y)

    def _attach_track_context_gesture(
        self,
        row: Gtk.ListBoxRow,
        track_id: int,
        title: str,
        artist: str,
    ) -> None:
        """Attach a right-click gesture for track context menu."""
        if self._context_callbacks is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(
            g, n_press, x, y, tid=track_id, t=title, a=artist
        ):
            if n_press != 1:
                return
            self._show_track_context_menu(row, x, y, tid, t, a)
            g.set_state(Gtk.EventSequenceState.CLAIMED)

        gesture.connect("pressed", _on_right_click)
        row.add_controller(gesture)

    def _show_track_context_menu(
        self,
        widget: Gtk.Widget,
        x: float,
        y: float,
        track_id: int,
        title: str,
        artist: str,
    ) -> None:
        """Create and display a context menu for a track."""
        if self._context_callbacks is None:
            return

        from auxen.views.context_menu import TrackContextMenu

        # Lookup the actual track object by ID first, fall back to
        # title/artist matching
        track = self._find_track_by_id(track_id)
        if track is None:
            track = self._find_track(title, artist)
        if track is None:
            return

        playlists = []
        if self._get_playlists is not None:
            playlists = self._get_playlists()

        _noop = lambda *_args: None
        callbacks = {
            "on_play": lambda t=track: self._context_callbacks.get(
                "on_play", _noop
            )(t),
            "on_play_next": lambda t=track: self._context_callbacks.get(
                "on_play_next", _noop
            )(t),
            "on_add_to_queue": lambda t=track: self._context_callbacks.get(
                "on_add_to_queue", _noop
            )(t),
            "on_add_to_playlist": lambda pid, t=track: (
                self._context_callbacks.get(
                    "on_add_to_playlist", _noop
                )(t, pid)
            ),
            "on_new_playlist": lambda t=track: (
                self._context_callbacks.get(
                    "on_new_playlist", _noop
                )(t)
            ),
            "on_toggle_favorite": lambda t=track: (
                self._context_callbacks.get(
                    "on_toggle_favorite", _noop
                )(t)
            ),
            "on_go_to_album": lambda t=track: (
                self._context_callbacks.get(
                    "on_go_to_album", _noop
                )(t)
            ),
            "on_go_to_artist": lambda t=track: (
                self._context_callbacks.get(
                    "on_go_to_artist", _noop
                )(t)
            ),
            "on_track_radio": lambda t=track: (
                self._context_callbacks.get(
                    "on_track_radio", _noop
                )(t)
            ),
            "on_track_mix": lambda t=track: (
                self._context_callbacks.get(
                    "on_track_mix", _noop
                )(t)
            ),
            "on_view_lyrics": lambda t=track: (
                self._context_callbacks.get(
                    "on_view_lyrics", _noop
                )(t)
            ),
            "on_credits": lambda t=track: (
                self._context_callbacks.get(
                    "on_credits", _noop
                )(t)
            ),
        }

        is_fav = False
        if self._db is not None and track.id is not None:
            try:
                is_fav = self._db.is_favorite(track.id)
            except Exception:
                pass

        track_data = {
            "id": getattr(track, "id", None),
            "title": getattr(track, "title", ""),
            "artist": getattr(track, "artist", ""),
            "album": getattr(track, "album", ""),
            "source": getattr(track, "source", None),
            "source_id": getattr(track, "source_id", None),
            "is_favorite": is_fav,
        }

        self._current_menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        self._current_menu.show(widget, x, y)

    def _play_all_by_artist(self, artist: str) -> None:
        """Play all tracks by the given artist using the DB."""
        if self._db is None:
            return
        try:
            tracks = self._db.get_artist_tracks(artist)
            if not tracks:
                return
            # Walk up the widget tree to find the window with player
            widget = self
            while widget is not None:
                if hasattr(widget, "_app_ref"):
                    app_ref = widget._app_ref
                    if app_ref and app_ref.player is not None:
                        app_ref.player.play_queue(
                            tracks, start_index=0
                        )
                    return
                widget = widget.get_parent()
        except Exception:
            logger.warning(
                "Failed to play all tracks by %s", artist,
                exc_info=True,
            )

    def _add_all_by_artist_to_queue(self, artist: str) -> None:
        """Add all tracks by the given artist to the play queue."""
        if self._db is None:
            return
        try:
            tracks = self._db.get_artist_tracks(artist)
            if not tracks:
                return
            # Walk up the widget tree to find the window with player
            widget = self
            while widget is not None:
                if hasattr(widget, "_app_ref"):
                    app_ref = widget._app_ref
                    if app_ref and app_ref.player is not None:
                        for track in tracks:
                            app_ref.player.queue.add(track)
                    return
                widget = widget.get_parent()
        except Exception:
            logger.warning(
                "Failed to add all tracks by %s to queue", artist,
                exc_info=True,
            )

    def _find_track_by_id(self, track_id: int):
        """Look up a Track object from the database by its primary key."""
        if self._db is None:
            return None
        try:
            return self._db.get_track(track_id)
        except Exception:
            logger.warning(
                "Failed to find track by id %s", track_id,
                exc_info=True,
            )
            return None

    def _find_track(self, title: str, artist: str):
        """Look up a Track object from the database by title and artist."""
        if self._db is None:
            return None
        try:
            tracks = self._db.get_artist_tracks(artist)
            for t in tracks:
                if t.title == title:
                    return t
            # Fallback: return first track by that artist if exact
            # title match fails (e.g. truncation differences)
            return tracks[0] if tracks else None
        except Exception:
            logger.warning(
                "Failed to find track %s by %s", title, artist,
                exc_info=True,
            )
            return None

    # ---- 7-Day Chart Drawing ----

    def _draw_daily_chart(
        self, area: Gtk.DrawingArea, cr, width: int, height: int
    ) -> None:
        """Draw the 7-day listening activity bar chart using Cairo."""
        data = self._daily_chart_data
        if not data or width <= 0 or height <= 0:
            # Draw placeholder text
            cr.set_source_rgba(0.6, 0.6, 0.6, 0.5)
            cr.select_font_face(
                "sans-serif", 0, 0  # NORMAL, NORMAL
            )
            cr.set_font_size(12)
            text = "No listening data"
            extents = cr.text_extents(text)
            cr.move_to(
                (width - extents.width) / 2,
                (height + extents.height) / 2,
            )
            cr.show_text(text)
            return

        n_bars = len(data)
        max_count = max((c for _, c in data), default=1)
        if max_count == 0:
            max_count = 1

        # Layout constants
        top_margin = 20  # space for count labels above bars
        bottom_margin = 20  # space for day labels below bars
        side_margin = 10
        bar_spacing = 8
        available_width = width - 2 * side_margin
        available_height = height - top_margin - bottom_margin
        bar_width = max(
            8, (available_width - (n_bars - 1) * bar_spacing) / n_bars
        )

        # Amber color #d4a039
        r, g, b = 0xD4 / 255, 0xA0 / 255, 0x39 / 255

        for i, (date_str, count) in enumerate(data):
            bar_x = side_margin + i * (bar_width + bar_spacing)
            bar_height = (
                (count / max_count) * available_height
                if max_count > 0
                else 0
            )
            bar_y = top_margin + available_height - bar_height

            # Draw bar
            cr.set_source_rgba(r, g, b, 0.85)
            cr.rectangle(
                bar_x,
                bar_y,
                bar_width,
                bar_height,
            )
            cr.fill()

            # Draw count label above bar
            cr.set_source_rgba(0.83, 0.83, 0.83, 1.0)
            cr.select_font_face("sans-serif", 0, 0)
            cr.set_font_size(10)
            count_text = str(count)
            extents = cr.text_extents(count_text)
            cr.move_to(
                bar_x + (bar_width - extents.width) / 2,
                bar_y - 4,
            )
            cr.show_text(count_text)

            # Draw day label below bar
            # Parse the date string to get day abbreviation
            try:
                dt = datetime.strptime(date_str, "%Y-%m-%d")
                day_label = dt.strftime("%a")
            except (ValueError, TypeError):
                day_label = date_str[-2:] if date_str else "?"

            cr.set_source_rgba(0.6, 0.6, 0.6, 1.0)
            cr.set_font_size(9)
            extents = cr.text_extents(day_label)
            cr.move_to(
                bar_x + (bar_width - extents.width) / 2,
                height - 4,
            )
            cr.show_text(day_label)

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
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        card.add_css_class("stats-card")
        card.set_margin_top(4)
        card.set_margin_bottom(4)

        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(24)
        if accent_class:
            icon.add_css_class(accent_class)
        card.append(icon)

        value_label = Gtk.Label(label=value)
        value_label.add_css_class("stat-card-value")
        card.append(value_label)

        text_label = Gtk.Label(label=label)
        text_label.add_css_class("stat-card-label")
        card.append(text_label)

        return card, value_label

    @staticmethod
    def _make_artist_row(
        artist: str, count: int, max_count: int
    ) -> Gtk.ListBoxRow:
        """Build a row for the Top Artists list with a progress bar."""
        row_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        row_box.add_css_class("stats-list-row")

        # Artist name
        name_label = Gtk.Label(label=artist)
        name_label.set_xalign(0)
        name_label.set_hexpand(True)
        name_label.set_ellipsize(Pango.EllipsizeMode.END)
        name_label.add_css_class("body")
        row_box.append(name_label)

        # Bar + count
        bar_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )

        bar = Gtk.ProgressBar()
        bar.add_css_class("stats-bar")
        fraction = count / max_count if max_count > 0 else 0
        bar.set_fraction(fraction)
        bar.set_size_request(120, -1)
        bar.set_valign(Gtk.Align.CENTER)
        bar_box.append(bar)

        count_label = Gtk.Label(label=str(count))
        count_label.add_css_class("caption")
        count_label.add_css_class("dim-label")
        count_label.set_width_chars(4)
        count_label.set_xalign(1)
        bar_box.append(count_label)

        row_box.append(bar_box)

        row = Gtk.ListBoxRow()
        row.add_css_class("track-row-hover")
        row.set_child(row_box)
        return row

    @staticmethod
    def _make_track_row(
        title: str, artist: str, count: int, max_count: int
    ) -> Gtk.ListBoxRow:
        """Build a row for the Top Tracks list with a progress bar."""
        row_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        row_box.add_css_class("stats-list-row")

        # Track info column
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        text_box.set_hexpand(True)

        title_label = Gtk.Label(label=title)
        title_label.set_xalign(0)
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.add_css_class("body")
        text_box.append(title_label)

        artist_label = Gtk.Label(label=artist)
        artist_label.set_xalign(0)
        artist_label.set_ellipsize(Pango.EllipsizeMode.END)
        artist_label.add_css_class("caption")
        artist_label.add_css_class("dim-label")
        text_box.append(artist_label)

        row_box.append(text_box)

        # Bar + count
        bar_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )

        bar = Gtk.ProgressBar()
        bar.add_css_class("stats-bar")
        fraction = count / max_count if max_count > 0 else 0
        bar.set_fraction(fraction)
        bar.set_size_request(120, -1)
        bar.set_valign(Gtk.Align.CENTER)
        bar_box.append(bar)

        count_label = Gtk.Label(label=str(count))
        count_label.add_css_class("caption")
        count_label.add_css_class("dim-label")
        count_label.set_width_chars(4)
        count_label.set_xalign(1)
        bar_box.append(count_label)

        row_box.append(bar_box)

        row = Gtk.ListBoxRow()
        row.add_css_class("track-row-hover")
        row.set_child(row_box)
        return row

    @staticmethod
    def _clear_list_box(list_box: Gtk.ListBox) -> None:
        """Remove all rows from a ListBox."""
        while True:
            row = list_box.get_row_at_index(0)
            if row is None:
                break
            list_box.remove(row)
