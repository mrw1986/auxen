"""Search view for the Auxen music player."""

from __future__ import annotations

import logging
import random
import threading

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import GLib, Gtk, Pango

from auxen.views.context_menu import TrackContextMenu

logger = logging.getLogger(__name__)

# Placeholder data pools for generating fake search results.
_SAMPLE_ARTISTS = [
    "Massive Attack",
    "Portishead",
    "Aphex Twin",
    "Bjork",
    "Nine Inch Nails",
    "The Cure",
    "Radiohead",
    "Boards of Canada",
]

_SAMPLE_ALBUMS = [
    "Mezzanine",
    "Dummy",
    "Selected Ambient Works",
    "Homogenic",
    "The Downward Spiral",
    "Disintegration",
    "OK Computer",
    "Music Has the Right to Children",
]

_SAMPLE_DURATIONS = [
    "3:24",
    "4:12",
    "5:01",
    "3:58",
    "6:33",
    "4:47",
    "5:29",
    "2:55",
]


def _format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS."""
    if seconds is None or seconds <= 0:
        return "0:00"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


def _make_result_row(
    title: str,
    artist: str,
    album: str,
    source: str,
    duration: str,
    track=None,
) -> Gtk.ListBoxRow:
    """Build a single search result row."""
    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class("search-result-row")
    row_box.set_margin_top(4)
    row_box.set_margin_bottom(4)
    row_box.set_margin_start(8)
    row_box.set_margin_end(8)

    # -- Album art placeholder (40x40) --
    art_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    art_box.add_css_class("album-art-placeholder")
    art_box.add_css_class("album-art-mini")
    art_box.set_size_request(40, 40)

    art_icon = Gtk.Image.new_from_icon_name("audio-x-generic-symbolic")
    art_icon.set_pixel_size(18)
    art_icon.set_opacity(0.4)
    art_icon.set_halign(Gtk.Align.CENTER)
    art_icon.set_valign(Gtk.Align.CENTER)
    art_icon.set_vexpand(True)
    art_box.append(art_icon)
    row_box.append(art_box)

    # -- Title + Artist column --
    text_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=2,
    )
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_label = Gtk.Label(label=title)
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(40)
    title_label.add_css_class("body")
    title_label.set_markup(f"<b>{GLib.markup_escape_text(title)}</b>")
    text_box.append(title_label)

    artist_label = Gtk.Label(label=artist)
    artist_label.set_xalign(0)
    artist_label.set_ellipsize(Pango.EllipsizeMode.END)
    artist_label.set_max_width_chars(30)
    artist_label.add_css_class("caption")
    artist_label.add_css_class("dim-label")
    text_box.append(artist_label)

    row_box.append(text_box)

    # -- Album name (right-aligned, dim) --
    album_label = Gtk.Label(label=album)
    album_label.set_ellipsize(Pango.EllipsizeMode.END)
    album_label.set_max_width_chars(25)
    album_label.add_css_class("caption")
    album_label.add_css_class("search-album-label")
    album_label.set_valign(Gtk.Align.CENTER)
    album_label.set_halign(Gtk.Align.END)
    row_box.append(album_label)

    # -- Source badge --
    badge = Gtk.Label(label=source.capitalize())
    css_class = (
        "source-badge-tidal" if source == "tidal" else "source-badge-local"
    )
    badge.add_css_class(css_class)
    badge.set_valign(Gtk.Align.CENTER)
    row_box.append(badge)

    # -- Duration --
    dur_label = Gtk.Label(label=duration)
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    row_box.append(dur_label)

    row = Gtk.ListBoxRow()
    row.set_child(row_box)
    # Store track reference for context menu
    row._track_data = track  # type: ignore[attr-defined]
    return row


class SearchView(Gtk.Box):
    """Search view with debounced input and scrollable result list."""

    __gtype_name__ = "SearchView"

    # Debounce delay in milliseconds.
    DEBOUNCE_MS = 300

    def __init__(self, **kwargs) -> None:
        super().__init__(
            orientation=Gtk.Orientation.VERTICAL,
            **kwargs,
        )

        self._debounce_id: int | None = None
        self._db = None
        self._tidal_provider = None

        # Context menu callbacks
        self._context_callbacks: dict | None = None
        self._get_playlists: object = None

        # ---- Search entry ----
        entry_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
        )
        entry_box.set_margin_top(24)
        entry_box.set_margin_bottom(16)
        entry_box.set_margin_start(32)
        entry_box.set_margin_end(32)

        self._search_entry = Gtk.SearchEntry()
        self._search_entry.set_placeholder_text(
            "Search tracks, albums, artists..."
        )
        self._search_entry.add_css_class("search-entry")
        self._search_entry.set_hexpand(True)
        self._search_entry.connect("search-changed", self._on_search_changed)

        # Track focus to show/hide history
        focus_controller = Gtk.EventControllerFocus()
        focus_controller.connect("enter", self._on_entry_focus_enter)
        self._search_entry.add_controller(focus_controller)

        entry_box.append(self._search_entry)

        self.append(entry_box)

        # ---- Scrollable results area ----
        self._scroll = Gtk.ScrolledWindow()
        self._scroll.set_policy(
            Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC
        )
        self._scroll.set_vexpand(True)

        # Container that switches between results list and empty state.
        self._results_stack = Gtk.Stack()
        self._results_stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._results_stack.set_transition_duration(150)

        # -- Results list --
        results_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
        )
        results_box.set_margin_start(32)
        results_box.set_margin_end(32)
        results_box.set_margin_bottom(24)

        self._results_list = Gtk.ListBox()
        self._results_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._results_list.add_css_class("boxed-list")
        results_box.append(self._results_list)

        self._results_stack.add_named(results_box, "results")

        # -- Search history section --
        self._history_box = self._build_history_section()
        self._results_stack.add_named(self._history_box, "history")

        # -- Empty state: initial (before search) --
        self._empty_initial = self._build_empty_state(
            icon_name="system-search-symbolic",
            message="Search your library",
        )
        self._results_stack.add_named(self._empty_initial, "empty-initial")

        # -- Empty state: no results --
        self._empty_no_results = self._build_empty_state(
            icon_name="edit-find-symbolic",
            message="No results found",
        )
        self._results_stack.add_named(
            self._empty_no_results, "empty-no-results"
        )

        # Start with initial empty state.
        self._results_stack.set_visible_child_name("empty-initial")

        self._scroll.set_child(self._results_stack)
        self.append(self._scroll)

    # ---- Public API ----

    def focus_entry(self) -> None:
        """Focus the search text input so the user can start typing."""
        self._search_entry.grab_focus()

    def set_providers(self, db=None, tidal_provider=None) -> None:
        """Wire the search view to real data providers.

        Parameters
        ----------
        db:
            Database instance for searching local tracks.
        tidal_provider:
            TidalProvider instance for searching Tidal.
        """
        self._db = db
        self._tidal_provider = tidal_provider

    def set_context_callbacks(
        self,
        callbacks: dict,
        get_playlists,
    ) -> None:
        """Set callback functions for the right-click context menu."""
        self._context_callbacks = callbacks
        self._get_playlists = get_playlists

    # ---- Context menu helpers ----

    def _attach_context_gesture(
        self, row: Gtk.ListBoxRow, track
    ) -> None:
        """Attach a right-click gesture to a search result row."""
        if self._context_callbacks is None or track is None:
            return

        gesture = Gtk.GestureClick(button=3)

        def _on_right_click(_gesture, _n_press, x, y, trk=track):
            self._show_track_context_menu(row, x, y, trk)

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

        callbacks = {
            "on_play": lambda t=track: self._context_callbacks["on_play"](t),
            "on_play_next": lambda t=track: self._context_callbacks["on_play_next"](t),
            "on_add_to_queue": lambda t=track: self._context_callbacks["on_add_to_queue"](t),
            "on_add_to_playlist": lambda pid, t=track: self._context_callbacks["on_add_to_playlist"](t, pid),
            "on_new_playlist": lambda t=track: self._context_callbacks["on_new_playlist"](t),
            "on_toggle_favorite": lambda t=track: self._context_callbacks["on_toggle_favorite"](t),
            "on_go_to_album": lambda t=track: self._context_callbacks["on_go_to_album"](t),
        }

        track_data = {
            "id": getattr(track, "id", None),
            "title": getattr(track, "title", ""),
            "artist": getattr(track, "artist", ""),
            "album": getattr(track, "album", ""),
            "source": getattr(track, "source", None),
            "is_favorite": False,
        }

        menu = TrackContextMenu(
            track_data=track_data,
            callbacks=callbacks,
            playlists=playlists,
        )
        menu.show(widget, x, y)

    # ---- Empty state builder ----

    @staticmethod
    def _build_empty_state(icon_name: str, message: str) -> Gtk.Box:
        """Create a centered empty-state widget with icon and message."""
        box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=16,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        box.add_css_class("search-empty-state")
        box.set_vexpand(True)
        box.set_margin_top(80)

        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(64)
        icon.set_opacity(0.3)
        box.append(icon)

        label = Gtk.Label(label=message)
        label.add_css_class("title-3")
        label.add_css_class("dim-label")
        box.append(label)

        return box

    # ---- Search history ----

    def _build_history_section(self) -> Gtk.Box:
        """Build the recent searches section container."""
        section = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
        )
        section.add_css_class("search-history-section")
        section.set_margin_start(32)
        section.set_margin_end(32)
        section.set_margin_top(8)
        section.set_margin_bottom(24)

        # Header
        header_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        header_box.set_margin_bottom(4)

        header_icon = Gtk.Image.new_from_icon_name(
            "document-open-recent-symbolic"
        )
        header_icon.set_pixel_size(16)
        header_icon.add_css_class("dim-label")
        header_box.append(header_icon)

        header_label = Gtk.Label(label="Recent Searches")
        header_label.set_xalign(0)
        header_label.add_css_class("caption")
        header_label.add_css_class("dim-label")
        header_label.set_hexpand(True)
        header_box.append(header_label)

        section.append(header_box)

        # History list
        self._history_list = Gtk.ListBox()
        self._history_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self._history_list.add_css_class("boxed-list")
        section.append(self._history_list)

        # Clear history button
        self._clear_history_btn = Gtk.Button(label="Clear History")
        self._clear_history_btn.add_css_class("search-history-clear")
        self._clear_history_btn.add_css_class("flat")
        self._clear_history_btn.set_halign(Gtk.Align.CENTER)
        self._clear_history_btn.set_margin_top(8)
        self._clear_history_btn.connect(
            "clicked", self._on_clear_history_clicked
        )
        section.append(self._clear_history_btn)

        return section

    def _make_history_row(self, query: str) -> Gtk.ListBoxRow:
        """Build a single search history row."""
        row_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=12,
        )
        row_box.add_css_class("search-history-row")
        row_box.set_margin_top(2)
        row_box.set_margin_bottom(2)
        row_box.set_margin_start(8)
        row_box.set_margin_end(8)

        # Clock icon
        clock_icon = Gtk.Image.new_from_icon_name(
            "document-open-recent-symbolic"
        )
        clock_icon.set_pixel_size(16)
        clock_icon.add_css_class("dim-label")
        clock_icon.set_valign(Gtk.Align.CENTER)
        row_box.append(clock_icon)

        # Query text (clickable area)
        label = Gtk.Label(label=query)
        label.set_xalign(0)
        label.set_hexpand(True)
        label.set_ellipsize(Pango.EllipsizeMode.END)
        label.set_max_width_chars(50)
        label.add_css_class("body")
        label.set_valign(Gtk.Align.CENTER)
        row_box.append(label)

        # Remove button (x)
        remove_btn = Gtk.Button.new_from_icon_name(
            "window-close-symbolic"
        )
        remove_btn.add_css_class("flat")
        remove_btn.add_css_class("circular")
        remove_btn.set_valign(Gtk.Align.CENTER)
        remove_btn.set_tooltip_text("Remove from history")
        remove_btn.connect(
            "clicked",
            self._on_remove_history_item,
            query,
        )
        row_box.append(remove_btn)

        row = Gtk.ListBoxRow()
        row.set_child(row_box)

        # Click gesture for the row to trigger search
        gesture = Gtk.GestureClick(button=1)
        gesture.connect("pressed", self._on_history_row_clicked, query)
        row.add_controller(gesture)

        return row

    def _refresh_history(self) -> None:
        """Reload history items from the database and display them."""
        # Clear existing rows
        while True:
            row = self._history_list.get_row_at_index(0)
            if row is None:
                break
            self._history_list.remove(row)

        if self._db is None:
            return

        queries = self._db.get_search_history(limit=10)
        if not queries:
            # No history — show the initial empty state instead
            self._results_stack.set_visible_child_name("empty-initial")
            return

        for query in queries:
            row = self._make_history_row(query)
            self._history_list.append(row)

        self._results_stack.set_visible_child_name("history")

    def _on_entry_focus_enter(
        self, controller: Gtk.EventControllerFocus
    ) -> None:
        """When the search entry gains focus, show history if empty."""
        query = self._search_entry.get_text().strip()
        if not query:
            self._refresh_history()

    def _on_history_row_clicked(
        self,
        gesture: Gtk.GestureClick,
        n_press: int,
        x: float,
        y: float,
        query: str,
    ) -> None:
        """Handle clicking a history row — populate and search."""
        self._search_entry.set_text(query)

    def _on_remove_history_item(
        self, button: Gtk.Button, query: str
    ) -> None:
        """Remove a single item from search history."""
        if self._db is not None:
            self._db.delete_search_history_item(query)
        self._refresh_history()

    def _on_clear_history_clicked(self, button: Gtk.Button) -> None:
        """Handle the Clear History button click."""
        if self._db is not None:
            self._db.clear_search_history()
        self._refresh_history()

    # ---- Debounced search ----

    def _on_search_changed(self, entry: Gtk.SearchEntry) -> None:
        """Handle search entry text changes with debounce."""
        # Cancel any pending debounce timeout.
        if self._debounce_id is not None:
            GLib.source_remove(self._debounce_id)
            self._debounce_id = None

        query = entry.get_text().strip()

        if not query:
            self._clear_results()
            self._refresh_history()
            return

        # Schedule the actual search after the debounce delay.
        self._debounce_id = GLib.timeout_add(
            self.DEBOUNCE_MS,
            self._on_debounce_expired,
            query,
        )

    def _on_debounce_expired(self, query: str) -> bool:
        """Execute the search after debounce period expires."""
        self._debounce_id = None

        # Save queries with 2+ characters to history
        if len(query) >= 2 and self._db is not None:
            try:
                self._db.add_search_history(query)
            except Exception:
                logger.warning("Failed to save search history", exc_info=True)

        # Run the search in a background thread to avoid blocking the UI
        # (especially Tidal network calls).
        def _search_thread() -> None:
            results = self._do_search(query)
            GLib.idle_add(self._populate_results, results)

        thread = threading.Thread(target=_search_thread, daemon=True)
        thread.start()
        # Return False to prevent GLib from repeating the timeout.
        return GLib.SOURCE_REMOVE

    # ---- Result population ----

    def _clear_results(self) -> None:
        """Remove all rows from the result list."""
        while True:
            row = self._results_list.get_row_at_index(0)
            if row is None:
                break
            self._results_list.remove(row)

    def _populate_results(
        self,
        results: list[dict[str, str]],
    ) -> None:
        """Fill the result list with search result rows."""
        self._clear_results()

        if not results:
            self._results_stack.set_visible_child_name("empty-no-results")
            return

        for result in results:
            track = result.get("_track")
            row = _make_result_row(
                title=result["title"],
                artist=result["artist"],
                album=result["album"],
                source=result["source"],
                duration=result["duration"],
                track=track,
            )
            self._attach_context_gesture(row, track)
            self._results_list.append(row)

        self._results_stack.set_visible_child_name("results")

    # ---- Search logic ----

    def _do_search(self, query: str) -> list[dict[str, str]]:
        """Search the database and optionally Tidal for matching tracks.

        Falls back to placeholder results if no providers are wired.
        """
        if not query:
            return []

        # If we have real providers, use them
        if self._db is not None:
            return self._do_real_search(query)

        # Fallback: placeholder search
        return self._do_placeholder_search(query)

    def _do_real_search(self, query: str) -> list[dict[str, str]]:
        """Perform a real search using the database and Tidal."""
        results: list[dict[str, str]] = []

        # Search local database
        try:
            db_tracks = self._db.search(query)
            for track in db_tracks:
                results.append({
                    "title": track.title,
                    "artist": track.artist,
                    "album": track.album or "",
                    "source": track.source.value,
                    "duration": _format_duration(track.duration),
                    "_track": track,
                    "_source_id": track.source_id,
                })
        except Exception:
            logger.warning("Database search failed", exc_info=True)

        # Search Tidal if available and logged in
        if self._tidal_provider is not None:
            try:
                if self._tidal_provider.is_logged_in:
                    tidal_tracks = self._tidal_provider.search(query, limit=10)
                    # Track source_ids already seen to avoid duplicates
                    seen_ids = {r["_source_id"] for r in results if r.get("_source_id")}
                    for track in tidal_tracks:
                        if track.source_id not in seen_ids:
                            seen_ids.add(track.source_id)
                            results.append({
                                "title": track.title,
                                "artist": track.artist,
                                "album": track.album or "",
                                "source": track.source.value,
                                "duration": _format_duration(track.duration),
                                "_track": track,
                                "_source_id": track.source_id,
                            })
            except Exception:
                logger.warning("Tidal search failed", exc_info=True)

        return results

    @staticmethod
    def _do_placeholder_search(query: str) -> list[dict[str, str]]:
        """Generate placeholder search results based on the query.

        This method returns fake results that incorporate the query text
        in the track titles.  It is designed to be replaced later with
        real database / provider calls.
        """
        if not query:
            return []

        # Use the query length as a seed so repeated identical queries
        # produce the same results within a session.
        rng = random.Random(query.lower())  # noqa: S311
        count = rng.randint(3, 5)

        results: list[dict[str, str]] = []
        for i in range(count):
            artist = rng.choice(_SAMPLE_ARTISTS)
            album = rng.choice(_SAMPLE_ALBUMS)
            source = rng.choice(["tidal", "local"])
            duration = rng.choice(_SAMPLE_DURATIONS)
            title = f"{query.title()} (Track {i + 1})"

            results.append(
                {
                    "title": title,
                    "artist": artist,
                    "album": album,
                    "source": source,
                    "duration": duration,
                }
            )

        return results
