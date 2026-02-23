"""Tests for search history tracking in auxen.db."""

import os
import tempfile

import pytest

from auxen.db import Database


@pytest.fixture
def db():
    """Create a temporary database, yield it, then clean up."""
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    database = Database(db_path=path)
    yield database
    database.close()
    if os.path.exists(path):
        os.unlink(path)


# ------------------------------------------------------------------
# add_search_history
# ------------------------------------------------------------------


class TestAddSearchHistory:
    def test_inserts_query(self, db: Database) -> None:
        db.add_search_history("pink floyd")
        history = db.get_search_history()
        assert "pink floyd" in history

    def test_inserts_multiple_queries(self, db: Database) -> None:
        db.add_search_history("radiohead")
        db.add_search_history("massive attack")
        history = db.get_search_history()
        assert len(history) == 2
        assert "radiohead" in history
        assert "massive attack" in history

    def test_duplicate_query_updates_timestamp_not_duplicate(
        self, db: Database
    ) -> None:
        """Re-inserting the same query should update its timestamp,
        not create a second row."""
        db.add_search_history("bjork")
        db.add_search_history("aphex twin")
        db.add_search_history("bjork")  # re-search

        history = db.get_search_history()
        assert len(history) == 2
        # "bjork" should now be first (most recent)
        assert history[0] == "bjork"
        assert history[1] == "aphex twin"

    def test_duplicate_query_moves_to_top(self, db: Database) -> None:
        """An older query that is re-searched should appear first."""
        db.add_search_history("first")
        db.add_search_history("second")
        db.add_search_history("third")

        # Re-search the first query
        db.add_search_history("first")

        history = db.get_search_history()
        assert history[0] == "first"

    def test_empty_string_query_is_stored(self, db: Database) -> None:
        """The DB layer itself should not filter; the UI filters
        queries under 2 chars."""
        db.add_search_history("")
        history = db.get_search_history()
        assert "" in history

    def test_unicode_query(self, db: Database) -> None:
        db.add_search_history("bjork")
        history = db.get_search_history()
        assert "bjork" in history

    def test_special_characters_query(self, db: Database) -> None:
        db.add_search_history("AC/DC")
        db.add_search_history("guns n' roses")
        history = db.get_search_history()
        assert "AC/DC" in history
        assert "guns n' roses" in history


# ------------------------------------------------------------------
# get_search_history
# ------------------------------------------------------------------


class TestGetSearchHistory:
    def test_returns_newest_first(self, db: Database) -> None:
        db.add_search_history("oldest")
        db.add_search_history("middle")
        db.add_search_history("newest")

        history = db.get_search_history()
        assert history == ["newest", "middle", "oldest"]

    def test_respects_limit(self, db: Database) -> None:
        for i in range(20):
            db.add_search_history(f"query_{i}")

        history = db.get_search_history(limit=5)
        assert len(history) == 5

    def test_default_limit_is_10(self, db: Database) -> None:
        for i in range(15):
            db.add_search_history(f"query_{i}")

        history = db.get_search_history()
        assert len(history) == 10

    def test_empty_history_returns_empty_list(self, db: Database) -> None:
        history = db.get_search_history()
        assert history == []

    def test_returns_strings(self, db: Database) -> None:
        db.add_search_history("test query")
        history = db.get_search_history()
        assert isinstance(history[0], str)

    def test_limit_larger_than_history(self, db: Database) -> None:
        db.add_search_history("only one")
        history = db.get_search_history(limit=100)
        assert len(history) == 1
        assert history[0] == "only one"


# ------------------------------------------------------------------
# clear_search_history
# ------------------------------------------------------------------


class TestClearSearchHistory:
    def test_removes_all_entries(self, db: Database) -> None:
        db.add_search_history("query a")
        db.add_search_history("query b")
        db.add_search_history("query c")

        db.clear_search_history()
        history = db.get_search_history()
        assert history == []

    def test_clear_empty_history_is_safe(self, db: Database) -> None:
        """Clearing an already-empty history should not raise."""
        db.clear_search_history()
        assert db.get_search_history() == []

    def test_can_add_after_clear(self, db: Database) -> None:
        db.add_search_history("before clear")
        db.clear_search_history()
        db.add_search_history("after clear")

        history = db.get_search_history()
        assert history == ["after clear"]
