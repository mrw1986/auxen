"""Two-way favourites sync between the local database and Tidal."""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from typing import TYPE_CHECKING, Callable, Optional

if TYPE_CHECKING:
    from auxen.db import Database
    from auxen.providers.tidal import TidalProvider

logger = logging.getLogger(__name__)


@dataclass
class SyncResult:
    """Outcome of a favourites sync operation."""

    added_local: int = 0
    added_tidal: int = 0
    already_synced: int = 0
    errors: list[str] | None = None


class FavoritesSyncService:
    """Synchronise favourites between the local DB and Tidal.

    Parameters
    ----------
    db:
        The local ``Database`` instance.
    tidal_provider:
        The ``TidalProvider`` instance used to read/write Tidal favourites.
    """

    def __init__(
        self,
        db: Database,
        tidal_provider: TidalProvider,
    ) -> None:
        self._db = db
        self._tidal = tidal_provider

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def sync(self) -> SyncResult:
        """Run a two-way favourites sync (blocking).

        * Tidal tracks that are favourited on Tidal but not locally are
          inserted into the local database and marked as favourites.
        * Local Tidal-sourced favourites that are *not* favourited on
          Tidal are added to Tidal favourites.
        * Tracks that are already in sync on both sides are counted in
          ``already_synced``.

        Returns a ``SyncResult`` with counts of what changed.
        """
        result = SyncResult(errors=[])

        # Guard: Tidal must be logged in
        try:
            if not self._tidal.is_logged_in:
                result.errors.append("Tidal is not logged in")  # type: ignore[union-attr]
                return result
        except Exception as exc:
            result.errors.append(f"Login check failed: {exc}")  # type: ignore[union-attr]
            return result

        # 1. Fetch Tidal favourites
        try:
            tidal_favorites = self._tidal.get_favorites()
        except Exception as exc:
            result.errors.append(f"Failed to fetch Tidal favorites: {exc}")  # type: ignore[union-attr]
            return result

        # 2. Fetch local favourites (all sources, but we only sync Tidal tracks)
        try:
            local_favorites = self._db.get_favorites()
        except Exception as exc:
            result.errors.append(f"Failed to fetch local favorites: {exc}")  # type: ignore[union-attr]
            return result

        # Build lookup sets keyed by Tidal source_id
        tidal_fav_ids: set[str] = {t.source_id for t in tidal_favorites}
        local_tidal_fav_ids: set[str] = {
            t.source_id for t in local_favorites if t.is_tidal
        }

        # 3. Tidal -> Local: add tracks favourited on Tidal but missing locally
        for track in tidal_favorites:
            if track.source_id in local_tidal_fav_ids:
                result.already_synced += 1
                continue
            try:
                track_id = self._db.insert_track(track)
                self._db.set_favorite(track_id, True)
                result.added_local += 1
            except Exception as exc:
                if result.errors is not None:
                    result.errors.append(
                        f"Failed to add Tidal track {track.source_id} locally: {exc}"
                    )

        # 4. Local -> Tidal: add local Tidal favourites not on Tidal
        for track in local_favorites:
            if not track.is_tidal:
                continue
            if track.source_id in tidal_fav_ids:
                # Already counted in the Tidal->Local pass above
                continue
            try:
                self._tidal.add_favorite(track.source_id)
                result.added_tidal += 1
            except Exception as exc:
                if result.errors is not None:
                    result.errors.append(
                        f"Failed to add track {track.source_id} to Tidal: {exc}"
                    )

        # Strip errors list when empty for cleaner results
        if not result.errors:
            result.errors = None

        return result

    def sync_async(
        self,
        callback: Callable[[SyncResult], None],
    ) -> None:
        """Run :meth:`sync` in a background thread.

        *callback* is invoked on the **main GLib thread** with the
        ``SyncResult`` once the sync completes (or fails).
        """
        try:
            from gi.repository import GLib
            _idle_add: Optional[Callable] = GLib.idle_add
        except Exception:
            _idle_add = None

        def _worker() -> None:
            result = self.sync()
            if _idle_add is not None:
                _idle_add(lambda r=result: (callback(r), False)[-1])
            else:
                callback(result)

        thread = threading.Thread(target=_worker, daemon=True)
        thread.start()
