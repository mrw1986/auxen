"""Tests for auxen.queue — PlayQueue and RepeatMode."""

from auxen.models import Source, Track
from auxen.queue import PlayQueue, RepeatMode


def _make_track(title: str) -> Track:
    """Helper to create a minimal Track for testing."""
    return Track(
        title=title,
        artist="Test Artist",
        source=Source.LOCAL,
        source_id=f"/music/{title.lower()}.flac",
    )


class TestRepeatMode:
    def test_off_value(self) -> None:
        assert RepeatMode.OFF.value == "off"

    def test_track_value(self) -> None:
        assert RepeatMode.TRACK.value == "track"

    def test_queue_value(self) -> None:
        assert RepeatMode.QUEUE.value == "queue"


class TestPlayQueue:
    def test_add_and_get_current(self) -> None:
        """Adding tracks makes the first one current."""
        q = PlayQueue()
        a = _make_track("A")
        b = _make_track("B")
        q.add(a)
        q.add(b)
        assert q.current is a
        assert len(q) == 2

    def test_next_advances(self) -> None:
        """next() advances A -> B -> C."""
        q = PlayQueue()
        a = _make_track("A")
        b = _make_track("B")
        c = _make_track("C")
        q.add(a)
        q.add(b)
        q.add(c)

        assert q.current is a
        result = q.next()
        assert result is b
        assert q.current is b
        result = q.next()
        assert result is c
        assert q.current is c

    def test_previous_goes_back(self) -> None:
        """Advance then go back returns previous track."""
        q = PlayQueue()
        a = _make_track("A")
        b = _make_track("B")
        c = _make_track("C")
        q.add(a)
        q.add(b)
        q.add(c)

        q.next()  # -> B
        q.next()  # -> C
        result = q.previous()
        assert result is b
        result = q.previous()
        assert result is a
        # Going back from position 0 stays at 0
        result = q.previous()
        assert result is a
        assert q.position == 0

    def test_next_at_end_returns_none(self) -> None:
        """Single track, next() returns None (no repeat)."""
        q = PlayQueue()
        q.add(_make_track("Solo"))
        result = q.next()
        assert result is None

    def test_clear(self) -> None:
        """clear() empties the queue and resets position."""
        q = PlayQueue()
        q.add(_make_track("A"))
        q.add(_make_track("B"))
        q.next()
        q.clear()
        assert len(q) == 0
        assert q.current is None
        assert q.position == 0

    def test_shuffle(self) -> None:
        """Shuffle reorders tracks but keeps current track at position 0."""
        q = PlayQueue()
        titles = [f"Track{i}" for i in range(20)]
        tracks = [_make_track(t) for t in titles]
        for t in tracks:
            q.add(t)

        current_before = q.current
        original_order = [t.title for t in q.tracks]
        q.shuffle()
        shuffled_order = [t.title for t in q.tracks]

        # Current track stays at position 0
        assert q.current is current_before
        assert q.position == 0
        # Order should differ (with 20 tracks, astronomically unlikely to
        # remain the same after Fisher-Yates shuffle)
        assert shuffled_order != original_order
        # All tracks still present
        assert sorted(shuffled_order) == sorted(original_order)

    def test_replace_queue(self) -> None:
        """replace() swaps out all tracks and resets position."""
        q = PlayQueue()
        q.add(_make_track("Old1"))
        q.add(_make_track("Old2"))
        q.next()  # position = 1

        new_tracks = [_make_track("New1"), _make_track("New2"), _make_track("New3")]
        q.replace(new_tracks)

        assert len(q) == 3
        assert q.position == 0
        assert q.current is new_tracks[0]

    def test_repeat_track(self) -> None:
        """With TRACK repeat, next() returns the same track without advancing."""
        q = PlayQueue()
        a = _make_track("A")
        b = _make_track("B")
        q.add(a)
        q.add(b)
        q.repeat_mode = RepeatMode.TRACK

        result = q.next()
        assert result is a
        assert q.position == 0
        # Still the same
        result = q.next()
        assert result is a
        assert q.position == 0

    def test_repeat_queue(self) -> None:
        """With QUEUE repeat, wraps to track 0 at end."""
        q = PlayQueue()
        a = _make_track("A")
        b = _make_track("B")
        q.add(a)
        q.add(b)
        q.repeat_mode = RepeatMode.QUEUE

        q.next()  # -> B
        result = q.next()  # at end, should wrap
        assert result is a
        assert q.position == 0

    def test_jump_to(self) -> None:
        """jump_to() sets position if valid, returns None for invalid."""
        q = PlayQueue()
        a = _make_track("A")
        b = _make_track("B")
        c = _make_track("C")
        q.add(a)
        q.add(b)
        q.add(c)

        result = q.jump_to(2)
        assert result is c
        assert q.position == 2

        result = q.jump_to(0)
        assert result is a
        assert q.position == 0

        # Invalid indices
        result = q.jump_to(-1)
        assert result is None

        result = q.jump_to(10)
        assert result is None

    def test_tracks_returns_copy(self) -> None:
        """The tracks property returns a copy, not the internal list."""
        q = PlayQueue()
        q.add(_make_track("A"))
        tracks_copy = q.tracks
        tracks_copy.append(_make_track("B"))
        assert len(q) == 1  # Internal list unchanged

    def test_empty_queue_current_is_none(self) -> None:
        """An empty queue has current == None."""
        q = PlayQueue()
        assert q.current is None
        assert len(q) == 0

    def test_next_on_empty_queue(self) -> None:
        """next() on empty queue returns None."""
        q = PlayQueue()
        assert q.next() is None

    def test_previous_on_empty_queue(self) -> None:
        """previous() on empty queue returns None."""
        q = PlayQueue()
        assert q.previous() is None
