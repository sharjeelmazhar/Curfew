#!/usr/bin/python3
"""A stand-in for gnome-settings-daemon's rfkill service on a test session bus: says airplane
mode is available (so GNOME Shell would show its button) and records any attempt to switch it."""
import sys
import gi
gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib

XML = """<node><interface name="org.gnome.SettingsDaemon.Rfkill">
<property name="AirplaneMode" type="b" access="readwrite"/><property name="HasAirplaneMode" type="b" access="read"/>
<property name="HardwareAirplaneMode" type="b" access="read"/><property name="BluetoothAirplaneMode" type="b" access="readwrite"/>
<property name="BluetoothHasAirplaneMode" type="b" access="read"/><property name="BluetoothHardwareAirplaneMode" type="b" access="readwrite"/>
<property name="ShouldShowAirplaneMode" type="b" access="read"/></interface></node>"""
props = {"AirplaneMode": False, "HasAirplaneMode": True, "HardwareAirplaneMode": False, "BluetoothAirplaneMode": False,
         "BluetoothHasAirplaneMode": True, "BluetoothHardwareAirplaneMode": False, "ShouldShowAirplaneMode": True}
info = Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0]


def get(conn, sender, path, iface, name):
    return GLib.Variant("b", props[name])


def set_(conn, sender, path, iface, name, value):
    props[name] = value.unpack()
    print("SET %s=%s" % (name, props[name]), flush=True)
    return True


def acquired(conn, name):
    conn.register_object("/org/gnome/SettingsDaemon/Rfkill", info, None, get, set_)


Gio.bus_own_name(Gio.BusType.SESSION, "org.gnome.SettingsDaemon.Rfkill", 0, acquired, None, lambda *a: sys.exit(1))
GLib.MainLoop().run()
