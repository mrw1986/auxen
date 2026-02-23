"""Tests for auxen.notifications — NotificationService."""

from unittest.mock import MagicMock, patch

import pytest

from auxen.notifications import NotificationService, _NOTIFICATION_ID


# ---- Helpers ----


def _make_app(window_active: bool = False) -> MagicMock:
    """Create a mock Gio.Application with an optional active window."""
    app = MagicMock()
    win = MagicMock()
    win.is_active.return_value = window_active
    app.get_active_window.return_value = win
    return app


def _make_app_no_window() -> MagicMock:
    """Create a mock Gio.Application with no active window."""
    app = MagicMock()
    app.get_active_window.return_value = None
    return app


# ===================================================================
# Enabled / Disabled Toggle
# ===================================================================


class TestEnabledToggle:
    """Test the enabled property and set_enabled method."""

    def test_enabled_by_default(self) -> None:
        app = _make_app()
        svc = NotificationService(app)
        assert svc.enabled is True

    def test_set_enabled_false(self) -> None:
        app = _make_app()
        svc = NotificationService(app)
        svc.set_enabled(False)
        assert svc.enabled is False

    def test_set_enabled_true_after_false(self) -> None:
        app = _make_app()
        svc = NotificationService(app)
        svc.set_enabled(False)
        svc.set_enabled(True)
        assert svc.enabled is True

    def test_set_enabled_coerces_to_bool(self) -> None:
        app = _make_app()
        svc = NotificationService(app)
        svc.set_enabled(0)
        assert svc.enabled is False
        svc.set_enabled(1)
        assert svc.enabled is True


# ===================================================================
# Notification Suppression When Disabled
# ===================================================================


class TestDisabledNotifications:
    """Notifications should not be sent when disabled."""

    def test_no_notification_when_disabled(self) -> None:
        app = _make_app()
        svc = NotificationService(app)
        svc.set_enabled(False)
        svc.notify_track_change("Title", "Artist")
        app.send_notification.assert_not_called()

    def test_notify_does_not_crash_when_disabled(self) -> None:
        app = _make_app()
        svc = NotificationService(app)
        svc.set_enabled(False)
        # Should not raise any exception
        svc.notify_track_change("Title", "Artist", album="Album")


# ===================================================================
# Window Focus Suppression
# ===================================================================


class TestWindowFocusSuppression:
    """Notifications should not be sent when the window is focused."""

    def test_no_notification_when_window_active(self) -> None:
        app = _make_app(window_active=True)
        svc = NotificationService(app)
        svc.notify_track_change("Song", "Band")
        app.send_notification.assert_not_called()

    def test_notification_sent_when_window_not_active(self) -> None:
        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("Song", "Band")
        app.send_notification.assert_called_once()

    def test_notification_sent_when_no_window(self) -> None:
        app = _make_app_no_window()
        svc = NotificationService(app)
        svc.notify_track_change("Song", "Band")
        app.send_notification.assert_called_once()


# ===================================================================
# Notification Message Formatting
# ===================================================================


class TestMessageFormatting:
    """Verify the notification title, body, and ID."""

    @patch("auxen.notifications.Gio.Notification")
    @patch("auxen.notifications.Gio.ThemedIcon")
    def test_body_artist_only(self, _mock_icon_cls, mock_notif_cls) -> None:
        mock_notif = MagicMock()
        mock_notif_cls.new.return_value = mock_notif

        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("My Song", "The Artist")

        mock_notif_cls.new.assert_called_once_with("My Song")
        mock_notif.set_body.assert_called_once_with("by The Artist")
        call_args = app.send_notification.call_args
        assert call_args[0][0] == _NOTIFICATION_ID

    @patch("auxen.notifications.Gio.Notification")
    @patch("auxen.notifications.Gio.ThemedIcon")
    def test_body_artist_and_album(
        self, _mock_icon_cls, mock_notif_cls
    ) -> None:
        mock_notif = MagicMock()
        mock_notif_cls.new.return_value = mock_notif

        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("My Song", "The Artist", album="The Album")

        mock_notif.set_body.assert_called_once_with(
            "by The Artist \u2014 The Album"
        )

    @patch("auxen.notifications.Gio.Notification")
    @patch("auxen.notifications.Gio.ThemedIcon")
    def test_body_empty_album_uses_artist_only(
        self, _mock_icon_cls, mock_notif_cls
    ) -> None:
        mock_notif = MagicMock()
        mock_notif_cls.new.return_value = mock_notif

        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("Song", "Artist", album="")

        mock_notif.set_body.assert_called_once_with("by Artist")

    def test_notification_id_is_stable(self) -> None:
        """Multiple calls should use the same notification ID."""
        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("Song 1", "Artist 1")
        svc.notify_track_change("Song 2", "Artist 2")

        assert app.send_notification.call_count == 2
        first_id = app.send_notification.call_args_list[0][0][0]
        second_id = app.send_notification.call_args_list[1][0][0]
        assert first_id == second_id == "track-change"


# ===================================================================
# Error Handling
# ===================================================================


class TestErrorHandling:
    """Service should not crash even if send_notification fails."""

    def test_send_notification_exception_is_caught(self) -> None:
        app = _make_app(window_active=False)
        app.send_notification.side_effect = RuntimeError("dbus error")
        svc = NotificationService(app)
        # Should not raise
        svc.notify_track_change("Song", "Artist")

    def test_get_active_window_exception_is_caught(self) -> None:
        app = MagicMock()
        app.get_active_window.side_effect = RuntimeError("no display")
        svc = NotificationService(app)
        # Should not raise -- falls through to sending the notification
        svc.notify_track_change("Song", "Artist")
        app.send_notification.assert_called_once()


# ===================================================================
# Notification Priority & Icon
# ===================================================================


class TestNotificationAttributes:
    """Verify priority and icon are set correctly."""

    @patch("auxen.notifications.Gio.Notification")
    @patch("auxen.notifications.Gio.ThemedIcon")
    def test_priority_is_set_to_low(
        self, mock_icon_cls, mock_notif_cls
    ) -> None:
        mock_notif = MagicMock()
        mock_notif_cls.new.return_value = mock_notif

        from gi.repository import Gio

        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("Song", "Artist")

        mock_notif.set_priority.assert_called_once_with(
            Gio.NotificationPriority.LOW
        )

    @patch("auxen.notifications.Gio.Notification")
    @patch("auxen.notifications.Gio.ThemedIcon")
    def test_icon_is_set(self, mock_icon_cls, mock_notif_cls) -> None:
        mock_notif = MagicMock()
        mock_notif_cls.new.return_value = mock_notif
        mock_icon = MagicMock()
        mock_icon_cls.new.return_value = mock_icon

        app = _make_app(window_active=False)
        svc = NotificationService(app)
        svc.notify_track_change("Song", "Artist")

        mock_icon_cls.new.assert_called_once_with("audio-x-generic")
        mock_notif.set_icon.assert_called_once_with(mock_icon)
