"""Play queue with repeat and shuffle support for the Auxen music player."""

from __future__ import annotations

import random
from enum import Enum
from typing import Optional

from auxen.models import Track


class RepeatMode(Enum):
    """Repeat behaviour for the play queue."""

    OFF = "off"
    TRACK = "track"
    QUEUE = "queue"


class PlayQueue:
    """Ordered list of tracks with navigation, repeat, and shuffle."""

    def __init__(self) -> None:
        self._tracks: list[Track] = []
        self._position: int = 0
        self.repeat_mode: RepeatMode = RepeatMode.OFF
        self._shuffled: bool = False
        self._original_tracks: list[Track] | None = None

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def current(self) -> Optional[Track]:
        """Return the track at the current position, or None if empty."""
        if not self._tracks:
            return None
        return self._tracks[self._position]

    @property
    def tracks(self) -> list[Track]:
        """Return a shallow copy of the internal track list."""
        return list(self._tracks)

    @property
    def position(self) -> int:
        """Return the current position index."""
        return self._position

    def __len__(self) -> int:
        return len(self._tracks)

    # ------------------------------------------------------------------
    # Mutators
    # ------------------------------------------------------------------

    def add(self, track: Track) -> None:
        """Append *track* to the end of the queue."""
        self._tracks.append(track)

    def replace(self, tracks: list[Track]) -> None:
        """Replace the entire queue with *tracks* and reset position to 0."""
        self._tracks = list(tracks)
        self._position = 0
        self._shuffled = False
        self._original_tracks = None

    def remove(self, index: int) -> bool:
        """Remove the track at *index*.

        Adjusts the current position so the same track stays current
        (unless the removed track *is* the current one, in which case
        position stays and shifts to the next track, or decrements if
        the removed track was the last).

        Returns ``True`` if removed, ``False`` for invalid index.
        """
        if index < 0 or index >= len(self._tracks):
            return False

        self._tracks.pop(index)

        if not self._tracks:
            self._position = 0
        elif index < self._position:
            self._position -= 1
        elif index == self._position:
            # If we removed the last element, step back
            if self._position >= len(self._tracks):
                self._position = max(0, len(self._tracks) - 1)

        return True

    def insert_after_current(self, track: Track) -> None:
        """Insert *track* immediately after the current position.

        If the queue is empty, the track becomes the only item and
        position stays at 0.
        """
        if not self._tracks:
            self._tracks.append(track)
            return
        insert_pos = self._position + 1
        self._tracks.insert(insert_pos, track)

    def clear(self) -> None:
        """Remove all tracks and reset position."""
        self._tracks.clear()
        self._position = 0

    # ------------------------------------------------------------------
    # Navigation
    # ------------------------------------------------------------------

    def next(self) -> Optional[Track]:
        """Advance to the next track and return it.

        Behaviour depends on :attr:`repeat_mode`:
        - TRACK: return the current track without advancing.
        - QUEUE: wrap to position 0 when the end is reached.
        - OFF: return ``None`` when the end is reached.
        """
        if not self._tracks:
            return None

        if self.repeat_mode == RepeatMode.TRACK:
            return self.current

        if self._position + 1 < len(self._tracks):
            self._position += 1
            return self.current

        if self.repeat_mode == RepeatMode.QUEUE:
            self._position = 0
            return self.current

        return None

    def previous(self) -> Optional[Track]:
        """Move to the previous track (minimum position 0) and return it."""
        if not self._tracks:
            return None
        self._position = max(0, self._position - 1)
        return self.current

    def jump_to(self, index: int) -> Optional[Track]:
        """Jump to *index* if valid; return the track or ``None``."""
        if index < 0 or index >= len(self._tracks):
            return None
        self._position = index
        return self.current

    # ------------------------------------------------------------------
    # Shuffle
    # ------------------------------------------------------------------

    @property
    def shuffled(self) -> bool:
        """Return True if the queue is currently shuffled."""
        return self._shuffled

    def shuffle(self) -> None:
        """Fisher-Yates shuffle the queue, keeping current track at index 0."""
        if len(self._tracks) <= 1:
            return

        # Store original order for unshuffle
        if not self._shuffled:
            self._original_tracks = list(self._tracks)

        current_track = self._tracks[self._position]

        # Remove the current track, shuffle the rest, then prepend it.
        remaining = [t for i, t in enumerate(self._tracks) if i != self._position]
        for i in range(len(remaining) - 1, 0, -1):
            j = random.randint(0, i)
            remaining[i], remaining[j] = remaining[j], remaining[i]

        self._tracks = [current_track] + remaining
        self._position = 0
        self._shuffled = True

    def unshuffle(self) -> None:
        """Restore the original queue order from before shuffle."""
        if not self._shuffled or self._original_tracks is None:
            return

        current_track = self.current
        self._tracks = list(self._original_tracks)
        self._original_tracks = None
        self._shuffled = False

        # Restore position to the current track in the original order
        if current_track is not None:
            try:
                self._position = self._tracks.index(current_track)
            except ValueError:
                self._position = 0
