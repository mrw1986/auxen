"""Listening statistics view for the Auxen music player."""

from __future__ import annotations

import logging
from typing import Optional

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gtk, Pango

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

        self._db = None

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

        # ---- 2. Stat cards row ----
        stats_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
            homogeneous=True,
        )

        (
            total_card,
            self._total_played_label,
        ) = self._make_stat_card(
            icon_name="media-playback-start-symbolic",
            value="0",
            label="Total Played",
            accent_class="stats-accent-amber",
        )
        stats_box.append(total_card)

        (
            time_card,
            self._listen_time_label,
        ) = self._make_stat_card(
            icon_name="preferences-system-time-symbolic",
            value="0h",
            label="Listen Time",
            accent_class="stats-accent-amber",
        )
        stats_box.append(time_card)

        (
            daily_card,
            self._daily_avg_label,
        ) = self._make_stat_card(
            icon_name="x-office-calendar-symbolic",
            value="0",
            label="Daily Average",
            accent_class="stats-accent-amber",
        )
        stats_box.append(daily_card)

        root.append(stats_box)

        # ---- 3. Most Active Hour ----
        active_hour_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        active_hour_box.add_css_class("stats-card")
        active_hour_box.set_margin_top(4)
        active_hour_box.set_margin_bottom(4)

        ah_icon = Gtk.Image.new_from_icon_name(
            "appointment-symbolic"
        )
        ah_icon.set_pixel_size(24)
        ah_icon.add_css_class("stats-accent-amber")
        active_hour_box.append(ah_icon)

        ah_text_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        ah_text_box.set_hexpand(True)

        ah_title = Gtk.Label(label="Most Active Hour")
        ah_title.set_xalign(0)
        ah_title.add_css_class("body")
        ah_text_box.append(ah_title)

        self._active_hour_label = Gtk.Label(label="--")
        self._active_hour_label.set_xalign(0)
        self._active_hour_label.add_css_class("stats-card-value")
        ah_text_box.append(self._active_hour_label)

        active_hour_box.append(ah_text_box)
        root.append(active_hour_box)

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

        # Update stat cards
        self._total_played_label.set_label(str(total))
        self._listen_time_label.set_label(f"{hours}h")
        self._daily_avg_label.set_label(str(avg))
        self._active_hour_label.set_label(
            _format_hour(active_hour)
        )

        # Toggle empty state
        has_data = total > 0
        self._empty_state.set_visible(not has_data)

        # Rebuild top artists
        self._clear_list_box(self._artists_list)
        if top_artists:
            max_count = top_artists[0][1] if top_artists else 1
            for artist, count in top_artists:
                row = self._make_artist_row(
                    artist, count, max_count
                )
                self._artists_list.append(row)

        # Rebuild top tracks
        self._clear_list_box(self._tracks_list)
        if top_tracks:
            max_count = top_tracks[0][2] if top_tracks else 1
            for title, artist, count in top_tracks:
                row = self._make_track_row(
                    title, artist, count, max_count
                )
                self._tracks_list.append(row)

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
        row_box.set_margin_top(8)
        row_box.set_margin_bottom(8)
        row_box.set_margin_start(12)
        row_box.set_margin_end(12)

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
        row_box.set_margin_top(8)
        row_box.set_margin_bottom(8)
        row_box.set_margin_start(12)
        row_box.set_margin_end(12)

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
