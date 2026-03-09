"""Two-way favourites sync between the local database and Tidal."""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
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
    removed_local: int = 0
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

        # Auto-sync polling state
        self._poll_timer_id: Optional[int] = None
        self._last_known_count: Optional[int] = None
        self._auto_sync_enabled: bool = True
        self._on_auto_sync_complete: Optional[
            Callable[[SyncResult], None]
        ] = None
        self._sync_lock = threading.Lock()
        self._sync_in_progress: bool = False

        # Load auto-sync preference and last known count from DB
        try:
            raw = self._db.get_setting("auto_sync_favorites", "1")
            self._auto_sync_enabled = raw != "0"
            count_raw = self._db.get_setting(
                "tidal_favorites_last_count"
            )
            if count_raw is not None:
                self._last_known_count = int(count_raw)
        except Exception:
            logger.debug(
                "Failed to load auto-sync settings", exc_info=True
            )

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def auto_sync_enabled(self) -> bool:
        """Whether automatic polling sync is enabled."""
        return self._auto_sync_enabled

    @auto_sync_enabled.setter
    def auto_sync_enabled(self, value: bool) -> None:
        """Enable or disable automatic polling sync."""
        self._auto_sync_enabled = value
        try:
            self._db.set_setting(
                "auto_sync_favorites", "1" if value else "0"
            )
        except Exception:
            logger.debug(
                "Failed to persist auto_sync_favorites", exc_info=True
            )
        if value:
            self.start_polling()
        else:
            self.stop_polling()

    @property
    def last_sync_time(self) -> Optional[str]:
        """Return the last sync timestamp as an ISO string, or None."""
        try:
            return self._db.get_setting("tidal_last_sync_time")
        except Exception:
            return None

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

        # 5. Remove stale: local Tidal favourites no longer on Tidal
        for track in local_favorites:
            if not track.is_tidal:
                continue
            if track.source_id in tidal_fav_ids:
                continue
            try:
                self._db.set_favorite(track.id, False)
                result.removed_local += 1
            except Exception as exc:
                if result.errors is not None:
                    result.errors.append(
                        f"Failed to remove stale track {track.source_id}: {exc}"
                    )

        # Strip errors list when empty for cleaner results
        if not result.errors:
            result.errors = None

        # Update last known count and sync timestamp
        try:
            self._last_known_count = len(tidal_favorites)
            self._db.set_setting(
                "tidal_favorites_last_count",
                str(self._last_known_count),
            )
            self._db.set_setting(
                "tidal_last_sync_time",
                datetime.now(timezone.utc).isoformat(),
            )
        except Exception:
            logger.debug(
                "Failed to persist sync metadata", exc_info=True
            )

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

    # ------------------------------------------------------------------
    # Auto-sync polling
    # ------------------------------------------------------------------

    def set_on_auto_sync_complete(
        self, callback: Optional[Callable[[SyncResult], None]]
    ) -> None:
        """Set a callback invoked on the main thread when auto-sync finishes."""
        self._on_auto_sync_complete = callback

    def start_polling(self, interval_seconds: int = 300) -> None:
        """Start the background polling timer.

        Polls every *interval_seconds* (default 5 minutes). If a timer
        is already running it is stopped first.
        """
        if not self._auto_sync_enabled:
            return

        self.stop_polling()

        try:
            from gi.repository import GLib

            self._poll_timer_id = GLib.timeout_add_seconds(
                interval_seconds, self._poll_tidal_favorites
            )
            logger.info(
                "Tidal favorites auto-sync polling started "
                "(every %ds)",
                interval_seconds,
            )
        except Exception:
            logger.debug(
                "Failed to start auto-sync polling", exc_info=True
            )

    def stop_polling(self) -> None:
        """Stop the background polling timer if running."""
        if self._poll_timer_id is not None:
            try:
                from gi.repository import GLib

                GLib.source_remove(self._poll_timer_id)
            except Exception:
                pass
            self._poll_timer_id = None
            logger.info("Tidal favorites auto-sync polling stopped")

    def trigger_initial_sync(self) -> None:
        """Trigger an initial sync and start polling (called on login)."""
        if not self._auto_sync_enabled:
            return

        def _on_result(result: SyncResult) -> None:
            with self._sync_lock:
                self._sync_in_progress = False
            logger.info(
                "Initial Tidal favorites sync: added_local=%d, "
                "added_tidal=%d, removed_local=%d, already_synced=%d",
                result.added_local,
                result.added_tidal,
                result.removed_local,
                result.already_synced,
            )
            if self._on_auto_sync_complete is not None:
                self._on_auto_sync_complete(result)

        with self._sync_lock:
            if self._sync_in_progress:
                logger.debug(
                    "Skipping initial sync: sync already in progress"
                )
                self.start_polling()
                return
            self._sync_in_progress = True

        self.sync_async(_on_result)
        self.start_polling()

    def _poll_tidal_favorites(self) -> bool:
        """Poll Tidal favorites count and sync if changed.

        Returns True to keep the timer running, False to stop.
        """
        if not self._auto_sync_enabled:
            return False

        try:
            if not self._tidal.is_logged_in:
                return True  # Keep polling, might login later
        except Exception:
            return True

        # Skip this poll tick if a sync is already running
        with self._sync_lock:
            if self._sync_in_progress:
                logger.debug(
                    "Skipping poll: sync already in progress"
                )
                return True
            self._sync_in_progress = True

        def _check_and_sync() -> None:
            try:
                # Get current favorites to check count
                current_favorites = self._tidal.get_favorites()
                current_count = len(current_favorites)

                if (
                    self._last_known_count is not None
                    and current_count == self._last_known_count
                ):
                    return  # No change, skip sync

                logger.info(
                    "Tidal favorites count changed: %s -> %d, syncing",
                    self._last_known_count,
                    current_count,
                )
                result = self.sync()

                if self._on_auto_sync_complete is not None:
                    try:
                        from gi.repository import GLib

                        GLib.idle_add(
                            lambda r=result: (
                                self._on_auto_sync_complete(r),
                                False,
                            )[-1]
                        )
                    except Exception:
                        self._on_auto_sync_complete(result)
            except Exception:
                logger.debug(
                    "Auto-sync poll check failed", exc_info=True
                )
            finally:
                with self._sync_lock:
                    self._sync_in_progress = False

        thread = threading.Thread(
            target=_check_and_sync, daemon=True
        )
        thread.start()
        return True  # Keep polling
