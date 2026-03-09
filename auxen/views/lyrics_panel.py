"""Lyrics side-panel widget for the Auxen music player."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Gtk, Pango

from auxen.views.widgets import DragScrollHelper

class LyricsPanel(Gtk.Box):
    """Right-side panel displaying lyrics for the currently playing track.

    Layout (left to right):
        - Thin resize handle (drag to resize)
        - Panel content (header, track info, scrollable lyrics)
    """

    __gtype_name__ = "LyricsPanel"

    def __init__(
        self,
        on_close: callable | None = None,
        **kwargs,
    ) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            **kwargs,
        )

        self._on_close = on_close

        self.add_css_class("lyrics-panel")
        self.set_overflow(Gtk.Overflow.HIDDEN)

        # ---- Header ----
        header = self._build_header()
        self.append(header)

        # ---- Track info ----
        self._track_info = self._build_track_info()
        self.append(self._track_info)

        # ---- Lyrics content (scrolled) ----
        self._scrolled = Gtk.ScrolledWindow()
        self._scrolled.set_policy(
            Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC
        )
        self._scrolled.set_vexpand(True)
        self._scrolled.set_hexpand(True)
        self._drag_scroll = DragScrollHelper(self._scrolled)

        self._lyrics_label = Gtk.Label()
        self._lyrics_label.set_wrap(True)
        self._lyrics_label.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
        self._lyrics_label.set_selectable(True)
        self._lyrics_label.set_xalign(0.5)
        self._lyrics_label.set_yalign(0)
        self._lyrics_label.set_valign(Gtk.Align.START)
        self._lyrics_label.set_margin_start(24)
        self._lyrics_label.set_margin_end(24)
        self._lyrics_label.set_margin_top(16)
        self._lyrics_label.set_margin_bottom(24)
        self._lyrics_label.add_css_class("lyrics-text")

        self._scrolled.set_child(self._lyrics_label)
        self.append(self._scrolled)

        # ---- Empty state ----
        self._empty_state = self._build_empty_state()
        self.append(self._empty_state)

        # Start with empty state visible
        self._scrolled.set_visible(False)
        self._empty_state.set_visible(True)

    # ------------------------------------------------------------------
    # Builder helpers
    # ------------------------------------------------------------------

    def _build_header(self) -> Gtk.Box:
        """Build the panel header with title and close button."""
        header = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        header.add_css_class("lyrics-header")

        title_label = Gtk.Label(label="Lyrics")
        title_label.set_xalign(0)
        title_label.set_hexpand(True)
        title_label.add_css_class("title-3")
        header.append(title_label)

        close_btn = Gtk.Button.new_from_icon_name("window-close-symbolic")
        close_btn.add_css_class("flat")
        close_btn.add_css_class("lyrics-close-btn")
        close_btn.set_valign(Gtk.Align.CENTER)
        close_btn.connect("clicked", self._on_close_clicked)
        header.append(close_btn)

        return header

    def _build_track_info(self) -> Gtk.Box:
        """Build the small track-info section below the header."""
        info_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=2,
        )
        info_box.add_css_class("lyrics-track-info")

        self._info_title = Gtk.Label(label="")
        self._info_title.set_xalign(0.5)
        self._info_title.set_ellipsize(Pango.EllipsizeMode.END)
        self._info_title.set_hexpand(True)
        self._info_title.set_width_chars(6)
        self._info_title.add_css_class("lyrics-info-title")
        info_box.append(self._info_title)

        self._info_artist = Gtk.Label(label="")
        self._info_artist.set_xalign(0.5)
        self._info_artist.set_ellipsize(Pango.EllipsizeMode.END)
        self._info_artist.set_hexpand(True)
        self._info_artist.set_width_chars(6)
        self._info_artist.add_css_class("lyrics-info-artist")
        info_box.append(self._info_artist)

        return info_box

    def _build_empty_state(self) -> Gtk.Box:
        """Build the centred empty-state placeholder."""
        empty = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=12,
            halign=Gtk.Align.FILL,
            valign=Gtk.Align.CENTER,
        )
        empty.set_vexpand(True)
        empty.add_css_class("lyrics-empty-state")

        icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
        icon.set_pixel_size(48)
        icon.set_halign(Gtk.Align.CENTER)
        empty.append(icon)

        label = Gtk.Label(label="No lyrics available")
        label.add_css_class("dim-label")
        label.set_wrap(True)
        label.set_justify(Gtk.Justification.CENTER)
        label.set_halign(Gtk.Align.CENTER)
        empty.append(label)

        return empty

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def show_lyrics(self, title: str, artist: str, lyrics_text: str) -> None:
        """Display lyrics for the given track."""
        self._info_title.set_label(title)
        self._info_artist.set_label(artist)
        self._lyrics_label.set_label(lyrics_text)

        self._scrolled.set_visible(True)
        self._empty_state.set_visible(False)

        # Scroll to top
        adj = self._scrolled.get_vadjustment()
        if adj is not None:
            adj.set_value(0)

    def show_no_lyrics(self, title: str, artist: str) -> None:
        """Show the empty state for a track that has no lyrics."""
        self._info_title.set_label(title)
        self._info_artist.set_label(artist)
        self._lyrics_label.set_label("")

        self._scrolled.set_visible(False)
        self._empty_state.set_visible(True)

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _on_close_clicked(self, _btn: Gtk.Button) -> None:
        if self._on_close is not None:
            self._on_close()
        else:
            self.set_visible(False)
