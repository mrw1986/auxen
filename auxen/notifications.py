"""Desktop notification service for the Auxen music player."""

from __future__ import annotations

import logging

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import Gio, Gtk

logger = logging.getLogger(__name__)

# Stable notification ID so new track notifications replace previous ones.
_NOTIFICATION_ID = "track-change"


class NotificationService:
    """Show system notifications when the current track changes.

    Uses ``Gio.Notification`` (the standard GTK4 API) so that the
    desktop environment's notification daemon handles presentation.

    Parameters
    ----------
    app:
        The ``Gio.Application`` instance, needed for
        ``app.send_notification()``.
    """

    def __init__(self, app: Gio.Application) -> None:
        self._app = app
        self._enabled: bool = True

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        """Whether notifications are currently enabled."""
        return self._enabled

    def set_enabled(self, enabled: bool) -> None:
        """Toggle notifications on or off."""
        self._enabled = bool(enabled)

    def notify_track_change(
        self,
        title: str,
        artist: str,
        album: str = "",
    ) -> None:
        """Show a notification for a track change.

        The notification is suppressed when:
        - Notifications are disabled (``enabled is False``).
        - The application window is currently focused.

        Parameters
        ----------
        title:
            Track title shown as the notification title.
        artist:
            Artist name included in the body text.
        album:
            Optional album name appended to the body.
        """
        if not self._enabled:
            return

        # Don't notify when the app window is focused.
        if self._is_window_active():
            return

        body = f"by {artist}"
        if album:
            body = f"by {artist} \u2014 {album}"

        notification = Gio.Notification.new(title)
        notification.set_body(body)
        notification.set_priority(Gio.NotificationPriority.LOW)

        try:
            icon = Gio.ThemedIcon.new("audio-x-generic")
            notification.set_icon(icon)
        except Exception:
            # Icon is optional; some environments may not have it.
            pass

        try:
            self._app.send_notification(_NOTIFICATION_ID, notification)
        except Exception:
            logger.warning(
                "Failed to send track-change notification", exc_info=True
            )

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _is_window_active(self) -> bool:
        """Return True if the application's active window has focus."""
        try:
            win = self._app.get_active_window()
            if win is not None and win.is_active():
                return True
        except Exception:
            pass
        return False
