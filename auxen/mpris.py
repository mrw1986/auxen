"""MPRIS2 D-Bus service for system media controls.

Exposes org.mpris.MediaPlayer2 and org.mpris.MediaPlayer2.Player
interfaces so that desktop environments, lock screens, and media keys
can control Auxen playback.

Uses gi.repository.Gio (GDBus) -- no extra dependencies beyond GTK4.
"""

from __future__ import annotations

from typing import Any, Callable, Optional

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

# ---------------------------------------------------------------------------
# MPRIS2 introspection XML
# ---------------------------------------------------------------------------

_MPRIS_XML = """\
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>

  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek">
      <arg direction="in" name="Offset" type="x"/>
    </method>
    <method name="SetPosition">
      <arg direction="in" name="TrackId" type="o"/>
      <arg direction="in" name="Position" type="x"/>
    </method>
    <method name="OpenUri">
      <arg direction="in" name="Uri" type="s"/>
    </method>

    <signal name="Seeked">
      <arg name="Position" type="x"/>
    </signal>

    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="LoopStatus" type="s" access="readwrite"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Shuffle" type="b" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>
"""

_IFACE_ROOT = "org.mpris.MediaPlayer2"
_IFACE_PLAYER = "org.mpris.MediaPlayer2.Player"
_OBJECT_PATH = "/org/mpris/MediaPlayer2"


class MprisService:
    """MPRIS2 D-Bus service exposing media player controls.

    Parameters
    ----------
    app_id:
        Application ID, e.g. ``"io.github.auxen.Auxen"``.
    app:
        Reference to the ``Adw.Application`` instance.
    """

    def __init__(self, app_id: str, app: Any) -> None:
        self._app = app
        self._app_id = app_id

        # Internal state ------------------------------------------------
        self._playback_status: str = "Stopped"
        self._loop_status: str = "None"
        self._shuffle: bool = False
        self._volume: float = 1.0
        self._position_us: int = 0  # microseconds
        self._metadata: dict[str, GLib.Variant] = {
            "mpris:trackid": GLib.Variant(
                "o", "/org/mpris/MediaPlayer2/TrackList/NoTrack"
            ),
        }

        # Callback hooks (set by the caller) ----------------------------
        self.on_play: Optional[Callable[[], None]] = None
        self.on_pause: Optional[Callable[[], None]] = None
        self.on_stop: Optional[Callable[[], None]] = None
        self.on_next: Optional[Callable[[], None]] = None
        self.on_previous: Optional[Callable[[], None]] = None
        self.on_seek: Optional[Callable[[int], None]] = None  # microseconds
        self.on_raise: Optional[Callable[[], None]] = None

        # Parse introspection XML ---------------------------------------
        self._node_info = Gio.DBusNodeInfo.new_for_xml(_MPRIS_XML)

        # D-Bus registration IDs ----------------------------------------
        self._registration_ids: list[int] = []
        self._bus_name_id: int = 0
        self._connection: Optional[Gio.DBusConnection] = None

        # Own the bus name ----------------------------------------------
        bus_name = f"org.mpris.MediaPlayer2.{app_id}"
        self._bus_name_id = Gio.bus_own_name(
            Gio.BusType.SESSION,
            bus_name,
            Gio.BusNameOwnerFlags.NONE,
            self._on_bus_acquired,
            None,  # name_acquired_handler
            None,  # name_lost_handler
        )

    # ------------------------------------------------------------------
    # Bus lifecycle
    # ------------------------------------------------------------------

    def _on_bus_acquired(
        self,
        connection: Gio.DBusConnection,
        _name: str,
    ) -> None:
        """Register both MPRIS interfaces on the session bus."""
        self._connection = connection

        for iface_info in self._node_info.interfaces:
            reg_id = connection.register_object(
                _OBJECT_PATH,
                iface_info,
                self._handle_method_call,
                self._handle_get_property,
                self._handle_set_property,
            )
            self._registration_ids.append(reg_id)

    # ------------------------------------------------------------------
    # Method call dispatcher
    # ------------------------------------------------------------------

    def _handle_method_call(
        self,
        _connection: Gio.DBusConnection,
        _sender: str,
        _object_path: str,
        interface_name: str,
        method_name: str,
        parameters: GLib.Variant,
        invocation: Gio.DBusMethodInvocation,
    ) -> None:
        """Dispatch incoming D-Bus method calls to local callbacks."""
        if interface_name == _IFACE_ROOT:
            if method_name == "Raise":
                if self.on_raise is not None:
                    self.on_raise()
            elif method_name == "Quit":
                self._app.quit()
            invocation.return_value(None)
            return

        if interface_name == _IFACE_PLAYER:
            if method_name == "Play":
                if self.on_play is not None:
                    self.on_play()
            elif method_name == "Pause":
                if self.on_pause is not None:
                    self.on_pause()
            elif method_name == "PlayPause":
                if self._playback_status == "Playing":
                    if self.on_pause is not None:
                        self.on_pause()
                else:
                    if self.on_play is not None:
                        self.on_play()
            elif method_name == "Stop":
                if self.on_stop is not None:
                    self.on_stop()
            elif method_name == "Next":
                if self.on_next is not None:
                    self.on_next()
            elif method_name == "Previous":
                if self.on_previous is not None:
                    self.on_previous()
            elif method_name == "Seek":
                offset_us = parameters.unpack()[0]
                if self.on_seek is not None:
                    self.on_seek(self._position_us + offset_us)
            elif method_name == "SetPosition":
                _track_id, position_us = parameters.unpack()
                if self.on_seek is not None:
                    self.on_seek(position_us)
            elif method_name == "OpenUri":
                pass  # Not implemented
            invocation.return_value(None)
            return

        invocation.return_value(None)

    # ------------------------------------------------------------------
    # Property getter
    # ------------------------------------------------------------------

    def _handle_get_property(
        self,
        _connection: Gio.DBusConnection,
        _sender: str,
        _object_path: str,
        interface_name: str,
        property_name: str,
    ) -> GLib.Variant:
        """Return the requested property as a GLib.Variant."""
        if interface_name == _IFACE_ROOT:
            return self._get_root_property(property_name)
        if interface_name == _IFACE_PLAYER:
            return self._get_player_property(property_name)
        return GLib.Variant("b", False)

    def _get_root_property(self, name: str) -> GLib.Variant:
        """Return a property on the root MediaPlayer2 interface."""
        props: dict[str, GLib.Variant] = {
            "CanQuit": GLib.Variant("b", True),
            "CanRaise": GLib.Variant("b", True),
            "HasTrackList": GLib.Variant("b", False),
            "Identity": GLib.Variant("s", "Auxen"),
            "DesktopEntry": GLib.Variant("s", "io.github.auxen.Auxen"),
            "SupportedUriSchemes": GLib.Variant("as", ["https", "file"]),
            "SupportedMimeTypes": GLib.Variant(
                "as",
                [
                    "audio/flac",
                    "audio/mpeg",
                    "audio/aac",
                    "audio/ogg",
                    "audio/wav",
                ],
            ),
        }
        return props.get(name, GLib.Variant("b", False))

    def _get_player_property(self, name: str) -> GLib.Variant:
        """Return a property on the Player interface."""
        if name == "PlaybackStatus":
            return GLib.Variant("s", self._playback_status)
        if name == "LoopStatus":
            return GLib.Variant("s", self._loop_status)
        if name == "Rate":
            return GLib.Variant("d", 1.0)
        if name == "MinimumRate":
            return GLib.Variant("d", 1.0)
        if name == "MaximumRate":
            return GLib.Variant("d", 1.0)
        if name == "Shuffle":
            return GLib.Variant("b", self._shuffle)
        if name == "Metadata":
            return GLib.Variant("a{sv}", self._metadata)
        if name == "Volume":
            return GLib.Variant("d", self._volume)
        if name == "Position":
            return GLib.Variant("x", self._position_us)
        if name == "CanGoNext":
            return GLib.Variant("b", True)
        if name == "CanGoPrevious":
            return GLib.Variant("b", True)
        if name == "CanPlay":
            return GLib.Variant("b", True)
        if name == "CanPause":
            return GLib.Variant("b", True)
        if name == "CanSeek":
            return GLib.Variant("b", True)
        if name == "CanControl":
            return GLib.Variant("b", True)
        return GLib.Variant("b", False)

    # ------------------------------------------------------------------
    # Property setter
    # ------------------------------------------------------------------

    def _handle_set_property(
        self,
        _connection: Gio.DBusConnection,
        _sender: str,
        _object_path: str,
        interface_name: str,
        property_name: str,
        value: GLib.Variant,
    ) -> bool:
        """Handle writable property changes from D-Bus clients."""
        if interface_name == _IFACE_PLAYER:
            if property_name == "Volume":
                self._volume = value.get_double()
                self.emit_properties_changed(
                    _IFACE_PLAYER, {"Volume": GLib.Variant("d", self._volume)}
                )
                return True
            if property_name == "LoopStatus":
                self._loop_status = value.get_string()
                self.emit_properties_changed(
                    _IFACE_PLAYER,
                    {"LoopStatus": GLib.Variant("s", self._loop_status)},
                )
                return True
            if property_name == "Shuffle":
                self._shuffle = value.get_boolean()
                self.emit_properties_changed(
                    _IFACE_PLAYER,
                    {"Shuffle": GLib.Variant("b", self._shuffle)},
                )
                return True
            if property_name == "Rate":
                # Rate is fixed at 1.0; ignore writes.
                return True
        return False

    # ------------------------------------------------------------------
    # Signal emission
    # ------------------------------------------------------------------

    def emit_properties_changed(
        self,
        interface: str,
        changed_dict: dict[str, GLib.Variant],
    ) -> None:
        """Emit org.freedesktop.DBus.Properties.PropertiesChanged."""
        if self._connection is None:
            return

        changed_variant = GLib.Variant("a{sv}", changed_dict)
        invalidated_variant = GLib.Variant("as", [])

        body = GLib.Variant.new_tuple(
            GLib.Variant("s", interface),
            changed_variant,
            invalidated_variant,
        )

        self._connection.emit_signal(
            None,  # destination (broadcast)
            _OBJECT_PATH,
            "org.freedesktop.DBus.Properties",
            "PropertiesChanged",
            body,
        )

    # ------------------------------------------------------------------
    # State update helpers
    # ------------------------------------------------------------------

    def update_playback_status(self, status: str) -> None:
        """Update the playback status and notify D-Bus listeners.

        Parameters
        ----------
        status:
            One of ``"Playing"``, ``"Paused"``, or ``"Stopped"``.
        """
        if status not in ("Playing", "Paused", "Stopped"):
            return
        self._playback_status = status
        self.emit_properties_changed(
            _IFACE_PLAYER,
            {"PlaybackStatus": GLib.Variant("s", status)},
        )

    def update_metadata(
        self,
        track_id: str,
        title: str,
        artists: list[str],
        album: str,
        length_seconds: float,
        art_url: Optional[str] = None,
    ) -> None:
        """Update the current track metadata and notify D-Bus listeners.

        Parameters
        ----------
        track_id:
            A unique identifier used as a D-Bus object path fragment,
            e.g. ``"/org/mpris/MediaPlayer2/Track/42"``.
        title:
            Track title.
        artists:
            List of artist names.
        album:
            Album name.
        length_seconds:
            Track duration in seconds.
        art_url:
            Optional URI to album art (``file://`` or ``https://``).
        """
        length_us = int(length_seconds * 1_000_000)

        self._metadata = {
            "mpris:trackid": GLib.Variant("o", track_id),
            "xesam:title": GLib.Variant("s", title),
            "xesam:artist": GLib.Variant("as", artists),
            "xesam:album": GLib.Variant("s", album),
            "mpris:length": GLib.Variant("x", length_us),
        }
        if art_url is not None:
            self._metadata["mpris:artUrl"] = GLib.Variant("s", art_url)

        self.emit_properties_changed(
            _IFACE_PLAYER,
            {"Metadata": GLib.Variant("a{sv}", self._metadata)},
        )

    def update_position(self, position_us: int) -> None:
        """Update the cached position (microseconds).

        This does **not** emit a PropertiesChanged signal because MPRIS
        specifies that Position is obtained by polling (GetProperty), not
        by change notification.  Only ``Seeked`` signals should be used
        when the position jumps discontinuously.
        """
        self._position_us = position_us

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    def unregister(self) -> None:
        """Release the D-Bus name and unregister objects."""
        if self._connection is not None:
            for reg_id in self._registration_ids:
                self._connection.unregister_object(reg_id)
            self._registration_ids.clear()

        if self._bus_name_id:
            Gio.bus_unown_name(self._bus_name_id)
            self._bus_name_id = 0
