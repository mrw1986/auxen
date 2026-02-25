"""Smart auto-playlists based on listening patterns and rules."""

from __future__ import annotations

from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from auxen.db import Database
    from auxen.models import Track


class SmartPlaylistType(Enum):
    """Identifiers for each built-in smart playlist."""

    MOST_PLAYED = "most_played"
    RECENTLY_ADDED = "recently_added"
    RECENTLY_PLAYED = "recently_played"
    HEAVY_ROTATION = "heavy_rotation"
    FORGOTTEN_GEMS = "forgotten_gems"
    NEVER_PLAYED = "never_played"


# Each definition carries the metadata shown in the sidebar and view header.
_DEFINITIONS: list[dict] = [
    {
        "id": SmartPlaylistType.MOST_PLAYED.value,
        "name": "Most Played",
        "icon": "starred-symbolic",
        "description": "Your top 50 most-played tracks of all time.",
    },
    {
        "id": SmartPlaylistType.RECENTLY_ADDED.value,
        "name": "Recently Added",
        "icon": "list-add-symbolic",
        "description": "The last 50 tracks added to your library.",
    },
    {
        "id": SmartPlaylistType.RECENTLY_PLAYED.value,
        "name": "Recently Played",
        "icon": "document-open-recent-symbolic",
        "description": "The last 30 unique tracks you listened to.",
    },
    {
        "id": SmartPlaylistType.HEAVY_ROTATION.value,
        "name": "Heavy Rotation",
        "icon": "media-playlist-repeat-symbolic",
        "description": "Most played in the last 7 days.",
    },
    {
        "id": SmartPlaylistType.FORGOTTEN_GEMS.value,
        "name": "Forgotten Gems",
        "icon": "non-starred-symbolic",
        "description": (
            "Tracks you loved (5+ plays) but haven't "
            "listened to in over 30 days."
        ),
    },
    {
        "id": SmartPlaylistType.NEVER_PLAYED.value,
        "name": "Never Played",
        "icon": "media-playback-start-symbolic",
        "description": "Tracks added to your library but never played.",
    },
]


class SmartPlaylistService:
    """Generates track lists for smart auto-playlists."""

    def __init__(self, db: Database) -> None:
        self._db = db

    def get_definitions(self) -> list[dict]:
        """Return the list of all smart playlist definitions.

        Each dict has keys: id, name, icon, description.
        """
        return list(_DEFINITIONS)

    def get_definition(self, playlist_id: str) -> dict | None:
        """Return a single definition by its id string, or None."""
        for defn in _DEFINITIONS:
            if defn["id"] == playlist_id:
                return dict(defn)
        return None

    def get_tracks(self, playlist_id: str) -> list[Track]:
        """Generate and return the tracks for the given smart playlist."""
        dispatch = {
            SmartPlaylistType.MOST_PLAYED.value: self._most_played,
            SmartPlaylistType.RECENTLY_ADDED.value: self._recently_added,
            SmartPlaylistType.RECENTLY_PLAYED.value: self._recently_played,
            SmartPlaylistType.HEAVY_ROTATION.value: self._heavy_rotation,
            SmartPlaylistType.FORGOTTEN_GEMS.value: self._forgotten_gems,
            SmartPlaylistType.NEVER_PLAYED.value: self._never_played,
        }
        handler = dispatch.get(playlist_id)
        if handler is None:
            return []
        return handler()

    # ---- Private dispatch targets ----

    def _most_played(self) -> list[Track]:
        return self._db.get_most_played_tracks(limit=50)

    def _recently_added(self) -> list[Track]:
        return self._db.get_recently_added_tracks(limit=50)

    def _recently_played(self) -> list[Track]:
        return self._db.get_recently_played_history(limit=30)

    def _heavy_rotation(self) -> list[Track]:
        return self._db.get_heavy_rotation_tracks(days=7, limit=30)

    def _forgotten_gems(self) -> list[Track]:
        return self._db.get_forgotten_gems(
            min_plays=5, inactive_days=30, limit=30
        )

    def _never_played(self) -> list[Track]:
        return self._db.get_never_played_tracks(limit=50)
