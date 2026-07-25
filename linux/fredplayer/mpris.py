from __future__ import annotations

from typing import Any

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

from . import APP_NAME
from .store import track_info


BUS_NAME = "org.mpris.MediaPlayer2.fredplayer"
OBJECT_PATH = "/org/mpris/MediaPlayer2"
ROOT_IFACE = "org.mpris.MediaPlayer2"
PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
PROPERTIES_IFACE = "org.freedesktop.DBus.Properties"


INTROSPECTION_XML = """
<node>
  <interface name="org.freedesktop.DBus.Properties">
    <method name="Get">
      <arg direction="in" type="s" name="interface_name"/>
      <arg direction="in" type="s" name="property_name"/>
      <arg direction="out" type="v" name="value"/>
    </method>
    <method name="GetAll">
      <arg direction="in" type="s" name="interface_name"/>
      <arg direction="out" type="a{sv}" name="properties"/>
    </method>
    <method name="Set">
      <arg direction="in" type="s" name="interface_name"/>
      <arg direction="in" type="s" name="property_name"/>
      <arg direction="in" type="v" name="value"/>
    </method>
    <signal name="PropertiesChanged">
      <arg type="s" name="interface_name"/>
      <arg type="a{sv}" name="changed_properties"/>
      <arg type="as" name="invalidated_properties"/>
    </signal>
  </interface>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="Fullscreen" type="b" access="readwrite"/>
    <property name="CanSetFullscreen" type="b" access="read"/>
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
      <arg direction="in" type="x" name="Offset"/>
    </method>
    <method name="SetPosition">
      <arg direction="in" type="o" name="TrackId"/>
      <arg direction="in" type="x" name="Position"/>
    </method>
    <method name="OpenUri">
      <arg direction="in" type="s" name="Uri"/>
    </method>
    <signal name="Seeked">
      <arg type="x" name="Position"/>
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


class MprisServer:
    def __init__(self, controller: Any) -> None:
        self.controller = controller
        self.connection: Gio.DBusConnection | None = None
        self.registration_ids: list[int] = []
        self.node_info = Gio.DBusNodeInfo.new_for_xml(INTROSPECTION_XML)
        self.owner_id = Gio.bus_own_name(
            Gio.BusType.SESSION,
            BUS_NAME,
            Gio.BusNameOwnerFlags.NONE,
            self._on_bus_acquired,
            None,
            self._on_name_lost,
        )

    def close(self) -> None:
        if self.connection is not None:
            for registration_id in self.registration_ids:
                try:
                    self.connection.unregister_object(registration_id)
                except GLib.Error:
                    pass
        self.registration_ids = []
        self.connection = None
        if self.owner_id:
            Gio.bus_unown_name(self.owner_id)
            self.owner_id = 0

    def update(self) -> None:
        if self.connection is None:
            return
        self._emit_properties_changed(
            PLAYER_IFACE,
            {
                "PlaybackStatus": self._property_value(PLAYER_IFACE, "PlaybackStatus"),
                "Metadata": self._property_value(PLAYER_IFACE, "Metadata"),
                "Volume": self._property_value(PLAYER_IFACE, "Volume"),
                "CanGoNext": self._property_value(PLAYER_IFACE, "CanGoNext"),
                "CanGoPrevious": self._property_value(PLAYER_IFACE, "CanGoPrevious"),
                "CanPlay": self._property_value(PLAYER_IFACE, "CanPlay"),
                "CanSeek": self._property_value(PLAYER_IFACE, "CanSeek"),
            },
        )

    def seeked(self, position_ms: int) -> None:
        if self.connection is None:
            return
        self.connection.emit_signal(
            None,
            OBJECT_PATH,
            PLAYER_IFACE,
            "Seeked",
            GLib.Variant("(x)", (max(0, int(position_ms)) * 1000,)),
        )

    def _on_bus_acquired(self, connection: Gio.DBusConnection, _name: str) -> None:
        self.connection = connection
        self.registration_ids = []
        for interface_name in (PROPERTIES_IFACE, ROOT_IFACE, PLAYER_IFACE):
            interface = self.node_info.lookup_interface(interface_name)
            self.registration_ids.append(
                connection.register_object(OBJECT_PATH, interface, self._handle_method_call)
            )
        self.update()

    def _on_name_lost(self, _connection: Gio.DBusConnection | None, _name: str) -> None:
        self.connection = None
        self.registration_ids = []

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
        try:
            if interface_name == PROPERTIES_IFACE:
                self._handle_properties_call(method_name, parameters, invocation)
            elif interface_name == ROOT_IFACE:
                self._handle_root_call(method_name, invocation)
            elif interface_name == PLAYER_IFACE:
                self._handle_player_call(method_name, parameters, invocation)
            else:
                invocation.return_error_literal(Gio.dbus_error_quark(), 0, "Unsupported interface")
        except Exception as error:
            invocation.return_error_literal(Gio.dbus_error_quark(), 0, str(error))

    def _handle_properties_call(
        self,
        method_name: str,
        parameters: GLib.Variant,
        invocation: Gio.DBusMethodInvocation,
    ) -> None:
        if method_name == "Get":
            interface_name, property_name = parameters.unpack()
            invocation.return_value(GLib.Variant("(v)", (self._property_value(interface_name, property_name),)))
            return

        if method_name == "GetAll":
            (interface_name,) = parameters.unpack()
            invocation.return_value(GLib.Variant("(a{sv})", (self._all_properties(interface_name),)))
            return

        if method_name == "Set":
            interface_name, property_name, value = parameters.unpack()
            self._set_property(interface_name, property_name, value)
            invocation.return_value(None)
            return

        invocation.return_error_literal(Gio.dbus_error_quark(), 0, "Unsupported Properties method")

    def _handle_root_call(self, method_name: str, invocation: Gio.DBusMethodInvocation) -> None:
        if method_name == "Raise":
            GLib.idle_add(self.controller.media_raise)
            invocation.return_value(None)
            return
        if method_name == "Quit":
            GLib.idle_add(self.controller.media_quit)
            invocation.return_value(None)
            return
        invocation.return_error_literal(Gio.dbus_error_quark(), 0, "Unsupported root method")

    def _handle_player_call(
        self,
        method_name: str,
        parameters: GLib.Variant,
        invocation: Gio.DBusMethodInvocation,
    ) -> None:
        if method_name == "Seek":
            (offset_us,) = parameters.unpack()
            GLib.idle_add(self.controller.media_seek_relative, int(offset_us // 1000))
            invocation.return_value(None)
            return
        if method_name == "SetPosition":
            track_id, position_us = parameters.unpack()
            current_path = self.controller.current_path
            if current_path and track_id == self._track_id(current_path):
                GLib.idle_add(self.controller.media_seek, int(position_us // 1000))
            invocation.return_value(None)
            return

        commands = {
            "Next": self.controller.media_next,
            "Previous": self.controller.media_previous,
            "Pause": self.controller.media_pause,
            "PlayPause": self.controller.media_play_pause,
            "Stop": self.controller.media_stop,
            "Play": self.controller.media_play,
            "OpenUri": None,
        }
        if method_name not in commands:
            invocation.return_error_literal(Gio.dbus_error_quark(), 0, "Unsupported player method")
            return
        command = commands[method_name]
        if command is not None:
            GLib.idle_add(command)
        invocation.return_value(None)

    def _all_properties(self, interface_name: str) -> dict[str, GLib.Variant]:
        if interface_name == ROOT_IFACE:
            names = [
                "CanQuit",
                "Fullscreen",
                "CanSetFullscreen",
                "CanRaise",
                "HasTrackList",
                "Identity",
                "DesktopEntry",
                "SupportedUriSchemes",
                "SupportedMimeTypes",
            ]
        elif interface_name == PLAYER_IFACE:
            names = [
                "PlaybackStatus",
                "LoopStatus",
                "Rate",
                "Shuffle",
                "Metadata",
                "Volume",
                "Position",
                "MinimumRate",
                "MaximumRate",
                "CanGoNext",
                "CanGoPrevious",
                "CanPlay",
                "CanPause",
                "CanSeek",
                "CanControl",
            ]
        else:
            names = []
        return {name: self._property_value(interface_name, name) for name in names}

    def _property_value(self, interface_name: str, property_name: str) -> GLib.Variant:
        if interface_name == ROOT_IFACE:
            values = {
                "CanQuit": GLib.Variant("b", True),
                "Fullscreen": GLib.Variant("b", False),
                "CanSetFullscreen": GLib.Variant("b", False),
                "CanRaise": GLib.Variant("b", True),
                "HasTrackList": GLib.Variant("b", False),
                "Identity": GLib.Variant("s", APP_NAME),
                "DesktopEntry": GLib.Variant("s", "fredplayer"),
                "SupportedUriSchemes": GLib.Variant("as", ["file"]),
                "SupportedMimeTypes": GLib.Variant(
                    "as",
                    [
                        "audio/aac",
                        "audio/flac",
                        "audio/mpeg",
                        "audio/ogg",
                        "audio/opus",
                        "audio/wav",
                        "audio/x-flac",
                    ],
                ),
            }
            return values[property_name]

        if interface_name == PLAYER_IFACE:
            position_ms, duration_ms = self.controller.media_progress()
            values = {
                "PlaybackStatus": GLib.Variant("s", self._playback_status()),
                "LoopStatus": GLib.Variant("s", "Playlist"),
                "Rate": GLib.Variant("d", 1.0),
                "Shuffle": GLib.Variant("b", bool(self.controller.shuffle_enabled)),
                "Metadata": GLib.Variant("a{sv}", self._metadata()),
                "Volume": GLib.Variant("d", float(self.controller.output_level)),
                "Position": GLib.Variant("x", max(0, int(position_ms)) * 1000),
                "MinimumRate": GLib.Variant("d", 1.0),
                "MaximumRate": GLib.Variant("d", 1.0),
                "CanGoNext": GLib.Variant("b", bool(self.controller.playlist)),
                "CanGoPrevious": GLib.Variant(
                    "b",
                    bool(self.controller.track_history or self.controller.current_path),
                ),
                "CanPlay": GLib.Variant("b", bool(self.controller.playlist)),
                "CanPause": GLib.Variant("b", True),
                "CanSeek": GLib.Variant("b", bool(self.controller.current_path and duration_ms > 0)),
                "CanControl": GLib.Variant("b", True),
            }
            return values[property_name]

        raise KeyError(f"Unknown property: {interface_name}.{property_name}")

    def _set_property(self, interface_name: str, property_name: str, value: GLib.Variant) -> None:
        if interface_name != PLAYER_IFACE:
            return
        if property_name == "Volume":
            self.controller.media_set_volume(float(value.unpack()))
            self.update()
        elif property_name == "Shuffle":
            self.controller.media_set_shuffle(bool(value.unpack()))
            self.update()

    def _metadata(self) -> dict[str, GLib.Variant]:
        path = self.controller.current_path
        if not path:
            return {
                "mpris:trackid": GLib.Variant("o", "/org/mpris/MediaPlayer2/Track/None"),
                "xesam:title": GLib.Variant("s", APP_NAME),
            }
        info = track_info(path)
        metadata = {
            "mpris:trackid": GLib.Variant("o", self._track_id(path)),
            "xesam:title": GLib.Variant("s", info.display_title),
            "xesam:url": GLib.Variant("s", GLib.filename_to_uri(path, None)),
        }
        artist = info.display_artist
        if artist:
            metadata["xesam:artist"] = GLib.Variant("as", [artist])
        if info.album:
            metadata["xesam:album"] = GLib.Variant("s", info.album)
        _position_ms, duration_ms = self.controller.media_progress()
        if duration_ms > 0:
            metadata["mpris:length"] = GLib.Variant("x", int(duration_ms) * 1000)
        return metadata

    def _track_id(self, path: str) -> str:
        safe = str(abs(hash(path)))
        return f"/org/mpris/MediaPlayer2/Track/{safe}"

    def _playback_status(self) -> str:
        if self.controller.audio_actually_playing:
            return "Playing"
        if self.controller.playback_requested or self.controller.current_path:
            return "Paused"
        return "Stopped"

    def _emit_properties_changed(self, interface_name: str, changed: dict[str, GLib.Variant]) -> None:
        if self.connection is None:
            return
        self.connection.emit_signal(
            None,
            OBJECT_PATH,
            PROPERTIES_IFACE,
            "PropertiesChanged",
            GLib.Variant("(sa{sv}as)", (interface_name, changed, [])),
        )
