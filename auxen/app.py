import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from pathlib import Path

from gi.repository import Adw, Gio, Gtk

from auxen.window import AuxenWindow


class AuxenApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id="io.github.auxen.Auxen")

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)

        quit_action = Gio.SimpleAction.new("quit", None)
        quit_action.connect("activate", lambda *_: self.quit())
        self.add_action(quit_action)
        self.set_accels_for_action("app.quit", ["<Control>q"])

        css_provider = Gtk.CssProvider()
        css_path = Path(__file__).resolve().parent.parent / "data" / "style.css"
        css_provider.load_from_path(str(css_path))
        Gtk.StyleContext.add_provider_for_display(
            self.get_style_manager().get_display(),
            css_provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

    def do_activate(self) -> None:
        win = self.get_active_window()
        if not win:
            win = AuxenWindow(application=self)
        win.present()
