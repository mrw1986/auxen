import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk

from auxen import __version__


class AuxenWindow(Adw.ApplicationWindow):
    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_title("Auxen")
        self.set_default_size(960, 600)

        split_view = Adw.NavigationSplitView()

        # Sidebar
        sidebar_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        sidebar_box.set_margin_top(12)
        sidebar_box.set_margin_bottom(12)
        sidebar_box.set_margin_start(12)
        sidebar_box.set_margin_end(12)

        sidebar_label = Gtk.Label(label=f"Auxen v{__version__}")
        sidebar_box.append(sidebar_label)

        sidebar_page = Adw.NavigationPage.new(sidebar_box, "Sidebar")
        split_view.set_sidebar(sidebar_page)

        # Content
        content_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            valign=Gtk.Align.CENTER,
            halign=Gtk.Align.CENTER,
        )

        welcome_label = Gtk.Label(label="Welcome to Auxen")
        welcome_label.add_css_class("title-1")
        content_box.append(welcome_label)

        content_page = Adw.NavigationPage.new(content_box, "Content")
        split_view.set_content(content_page)

        self.set_content(split_view)
