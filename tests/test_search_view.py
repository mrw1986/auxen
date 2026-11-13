"""Tests for auxen.views.search — result clicks, queueing, history."""

from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from auxen.views.search import _TYPE_ALBUM, _TYPE_ARTIST, _TYPE_TRACK, SearchView


def _make_view(on_play=None, on_play_queue=None, db=None, query="") -> SearchView:
    """A SearchView with __init__ skipped and only what the handlers need."""
    with patch.object(SearchView, "__init__", lambda self, **kw: None):
        view = SearchView.__new__(SearchView)
    view._context_callbacks = {"on_play": on_play} if on_play else None
    view._on_play_queue = on_play_queue
    view._db = db
    view._cached_results = []
    view._active_filter = "all"
    view._search_entry = MagicMock()
    view._search_entry.get_text.return_value = query
    return view


def _row(result_type, track=None):
    return SimpleNamespace(_result_type=result_type, _track_data=track)


def _track_result(track):
    return {"_result_type": _TYPE_TRACK, "_track": track}


class TestResultRowActivated:
    def test_track_row_queues_visible_tracks_from_clicked_one(self) -> None:
        on_play_queue = MagicMock()
        view = _make_view(on_play_queue=on_play_queue)
        a, b, c = object(), object(), object()
        view._cached_results = [
            _track_result(a),
            {"_result_type": _TYPE_ALBUM},
            _track_result(b),
            {"_result_type": _TYPE_ARTIST},
            _track_result(c),
        ]

        view._on_result_row_activated(None, _row(_TYPE_TRACK, b))

        on_play_queue.assert_called_once_with([a, b, c], 1)

    def test_queue_respects_active_filter(self) -> None:
        on_play_queue = MagicMock()
        view = _make_view(on_play_queue=on_play_queue)
        a, b = object(), object()
        view._cached_results = [_track_result(a), _track_result(b)]
        view._active_filter = _TYPE_TRACK

        view._on_result_row_activated(None, _row(_TYPE_TRACK, a))

        on_play_queue.assert_called_once_with([a, b], 0)

    def test_falls_back_to_single_play_without_queue_callback(self) -> None:
        on_play = MagicMock()
        view = _make_view(on_play=on_play)
        track = object()
        view._cached_results = [_track_result(track)]

        view._on_result_row_activated(None, _row(_TYPE_TRACK, track))

        on_play.assert_called_once_with(track)

    @pytest.mark.parametrize("result_type", [_TYPE_ALBUM, _TYPE_ARTIST])
    def test_album_and_artist_rows_do_not_play(self, result_type) -> None:
        # Those rows navigate via their own click gesture.
        on_play, on_play_queue = MagicMock(), MagicMock()
        view = _make_view(on_play=on_play, on_play_queue=on_play_queue)

        view._on_result_row_activated(None, _row(result_type, object()))

        on_play.assert_not_called()
        on_play_queue.assert_not_called()

    def test_track_row_without_track_is_ignored(self) -> None:
        on_play_queue = MagicMock()
        view = _make_view(on_play_queue=on_play_queue)

        view._on_result_row_activated(None, _row(_TYPE_TRACK, None))

        on_play_queue.assert_not_called()

    def test_no_callbacks_wired_is_a_noop(self) -> None:
        view = _make_view()
        track = object()
        view._cached_results = [_track_result(track)]

        view._on_result_row_activated(None, _row(_TYPE_TRACK, track))

    def test_playing_a_result_saves_the_query_to_history(self) -> None:
        db = MagicMock()
        view = _make_view(on_play_queue=MagicMock(), db=db, query=" bjork ")
        track = object()
        view._cached_results = [_track_result(track)]

        view._on_result_row_activated(None, _row(_TYPE_TRACK, track))

        db.add_search_history.assert_called_once_with("bjork")


class TestSearchHistoryCommit:
    def test_debounce_does_not_save_history(self) -> None:
        # Saving on every debounce stored each typing pause ("pi", "pink"...).
        db = MagicMock()
        view = _make_view(db=db)
        view._debounce_id = 1
        view._search_generation = 0
        view._do_search = MagicMock(return_value=[])

        with patch("auxen.views.search.threading.Thread"):
            view._on_debounce_expired("pink floyd")

        db.add_search_history.assert_not_called()

    def test_enter_saves_history(self) -> None:
        db = MagicMock()
        view = _make_view(db=db, query="pink floyd")

        view._on_entry_activate(view._search_entry)

        db.add_search_history.assert_called_once_with("pink floyd")

    def test_single_character_query_is_not_saved(self) -> None:
        db = MagicMock()
        view = _make_view(db=db, query="p")

        view._commit_history()

        db.add_search_history.assert_not_called()


class TestDragSource:
    def test_prepare_persists_tidal_track_without_id(self) -> None:
        db = MagicMock()
        db.ensure_track_id.return_value = 42
        view = _make_view(db=db)
        track = SimpleNamespace(id=None)

        assert view._drag_track_id(track) == "42"
        db.ensure_track_id.assert_called_once_with(track)

    def test_no_db_and_no_id_gives_none(self) -> None:
        view = _make_view()

        assert view._drag_track_id(SimpleNamespace(id=None)) is None


class TestRowActivatedWiring:
    def test_results_list_connects_row_activated(self) -> None:
        gi = pytest.importorskip("gi")
        gi.require_version("Gtk", "4.0")
        gi.require_version("Adw", "1")
        from gi.repository import Adw, GObject, Gtk

        if not Gtk.init_check():
            pytest.skip("no display available")
        Adw.init()

        view = SearchView()
        signal_id = GObject.signal_lookup("row-activated", Gtk.ListBox)

        assert GObject.signal_has_handler_pending(
            view._results_list, signal_id, 0, False
        )
