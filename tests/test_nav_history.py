"""Tests for the navigation history stack logic in AuxenWindow."""

import pytest


class FakeStack:
    """Minimal stand-in for Gtk.Stack to track visible child changes."""

    def __init__(self) -> None:
        self._visible: str = "home"
        self._children: set[str] = {
            "home",
            "search",
            "library",
            "explore",
            "mixes",
            "favorites",
            "stats",
            "album-detail",
            "artist-detail",
            "playlist-detail",
            "smart-playlist-detail",
        }

    def set_visible_child_name(self, name: str) -> None:
        self._visible = name

    def get_visible_child_name(self) -> str:
        return self._visible

    def get_child_by_name(self, name: str):
        return name if name in self._children else None


class NavHost:
    """Minimal host that replicates the navigation history logic from
    AuxenWindow without requiring GTK to be initialised.
    """

    def __init__(self) -> None:
        self._stack = FakeStack()
        self._nav_history: list[str] = ["home"]
        self._nav_index: int = 0
        self._nav_programmatic: bool = False

    # ---- Core nav methods (mirrors window.py logic) ----

    def _push_nav(self, page_name: str, detail_key: str = "") -> None:
        if self._nav_programmatic:
            return
        entry = f"{page_name}:{detail_key}" if detail_key else page_name
        if (
            self._nav_history
            and self._nav_history[self._nav_index] == entry
        ):
            return
        self._nav_history = self._nav_history[: self._nav_index + 1]
        self._nav_history.append(entry)
        self._nav_index = len(self._nav_history) - 1

    def _nav_back(self) -> bool:
        if self._nav_index > 0:
            self._nav_index -= 1
            entry = self._nav_history[self._nav_index]
            page = entry.split(":")[0]
            self._nav_programmatic = True
            try:
                self._stack.set_visible_child_name(page)
            finally:
                self._nav_programmatic = False
            return True
        return False

    def _nav_forward(self) -> bool:
        if self._nav_index < len(self._nav_history) - 1:
            self._nav_index += 1
            entry = self._nav_history[self._nav_index]
            page = entry.split(":")[0]
            self._nav_programmatic = True
            try:
                self._stack.set_visible_child_name(page)
            finally:
                self._nav_programmatic = False
            return True
        return False

    # ---- Helpers to simulate user navigation ----

    def navigate_to(self, page_name: str, detail_key: str = "") -> None:
        """Simulate a sidebar click or similar navigation."""
        page = page_name.split(":")[0] if ":" in page_name else page_name
        self._stack.set_visible_child_name(page)
        self._push_nav(page_name, detail_key)


@pytest.fixture
def nav() -> NavHost:
    """Create a fresh NavHost for each test."""
    return NavHost()


# ======================================================================
# Initial state
# ======================================================================


class TestInitialState:
    def test_starts_on_home(self, nav: NavHost) -> None:
        assert nav._stack.get_visible_child_name() == "home"
        assert nav._nav_history == ["home"]
        assert nav._nav_index == 0

    def test_back_does_nothing_at_start(self, nav: NavHost) -> None:
        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "home"
        assert nav._nav_index == 0

    def test_forward_does_nothing_at_start(self, nav: NavHost) -> None:
        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "home"
        assert nav._nav_index == 0


# ======================================================================
# Push navigation
# ======================================================================


class TestPushNav:
    def test_push_adds_to_history(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        assert nav._nav_history == ["home", "library"]
        assert nav._nav_index == 1

    def test_push_multiple_pages(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")
        nav.navigate_to("album-detail")
        assert nav._nav_history == [
            "home",
            "library",
            "search",
            "album-detail",
        ]
        assert nav._nav_index == 3

    def test_push_duplicate_is_ignored(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("library")
        assert nav._nav_history == ["home", "library"]
        assert nav._nav_index == 1

    def test_push_home_duplicate_at_start(self, nav: NavHost) -> None:
        nav.navigate_to("home")
        assert nav._nav_history == ["home"]
        assert nav._nav_index == 0


# ======================================================================
# Back navigation
# ======================================================================


class TestNavBack:
    def test_back_goes_to_previous(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "home"
        assert nav._nav_index == 0

    def test_back_multiple_steps(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")
        nav.navigate_to("album-detail")

        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "search"

        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "library"

        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "home"

    def test_back_stops_at_beginning(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav._nav_back()
        nav._nav_back()
        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "home"
        assert nav._nav_index == 0


# ======================================================================
# Forward navigation
# ======================================================================


class TestNavForward:
    def test_forward_after_back(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")

        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "library"

        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "search"
        assert nav._nav_index == 2

    def test_forward_stops_at_end(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "library"
        assert nav._nav_index == 1

    def test_forward_multiple_steps(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")
        nav.navigate_to("album-detail")

        nav._nav_back()
        nav._nav_back()
        nav._nav_back()

        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "library"

        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "search"

        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "album-detail"


# ======================================================================
# Forward history trimming
# ======================================================================


class TestForwardTrimming:
    def test_new_nav_after_back_trims_forward(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")
        nav.navigate_to("album-detail")

        nav._nav_back()
        nav._nav_back()
        # Now at "library", forward history is "search", "album-detail"

        nav.navigate_to("favorites")
        # Forward history should be trimmed
        assert nav._nav_history == ["home", "library", "favorites"]
        assert nav._nav_index == 2

        # Forward should now do nothing
        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "favorites"
        assert nav._nav_index == 2

    def test_trim_at_home(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")

        nav._nav_back()
        nav._nav_back()
        # At home, forward history is library, search

        nav.navigate_to("explore")
        assert nav._nav_history == ["home", "explore"]
        assert nav._nav_index == 1


# ======================================================================
# Programmatic flag
# ======================================================================


class TestProgrammaticFlag:
    def test_back_does_not_push_to_history(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")
        history_before_back = list(nav._nav_history)

        nav._nav_back()
        assert nav._nav_history == history_before_back
        assert nav._nav_index == 1

    def test_forward_does_not_push_to_history(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("search")
        nav._nav_back()
        history_before_forward = list(nav._nav_history)

        nav._nav_forward()
        assert nav._nav_history == history_before_forward
        assert nav._nav_index == 2


# ======================================================================
# Roundtrip scenarios
# ======================================================================


class TestRoundtrip:
    def test_back_then_forward_returns_to_same(self, nav: NavHost) -> None:
        nav.navigate_to("library")
        nav.navigate_to("album-detail")

        nav._nav_back()
        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "album-detail"
        assert nav._nav_index == 2

    def test_full_cycle(self, nav: NavHost) -> None:
        """Navigate several pages, go back to start, forward to end."""
        pages = ["library", "search", "explore", "mixes"]
        for page in pages:
            nav.navigate_to(page)

        # Back to home
        for _ in range(4):
            nav._nav_back()
        assert nav._stack.get_visible_child_name() == "home"

        # Forward to mixes
        for _ in range(4):
            nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "mixes"

    def test_interleaved_back_forward_and_new_nav(
        self, nav: NavHost
    ) -> None:
        """Complex scenario: back, forward, then new navigation."""
        nav.navigate_to("library")
        nav.navigate_to("search")
        nav.navigate_to("album-detail")

        nav._nav_back()  # -> search
        nav._nav_back()  # -> library
        nav._nav_forward()  # -> search

        nav.navigate_to("artist-detail")
        # History: home, library, search, artist-detail
        assert nav._nav_history == [
            "home",
            "library",
            "search",
            "artist-detail",
        ]
        assert nav._nav_index == 3

        nav._nav_back()
        assert nav._stack.get_visible_child_name() == "search"

        nav._nav_forward()
        assert nav._stack.get_visible_child_name() == "artist-detail"
