#!/bin/bash
cd "$(dirname "$0")"
python3 -c "
import sys, signal, gi
gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import GLib
from auxen.app import AuxenApp

signal.signal(signal.SIGINT, signal.SIG_DFL)
app = AuxenApp()
sys.exit(app.run(sys.argv))
" "$@"
