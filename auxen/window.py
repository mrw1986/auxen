"""Main application window for Auxen."""

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk

from auxen.views.sidebar import AuxenSidebar


# Page definitions: (name, display_title)
_PAGES: list[tuple[str, str]] = [
    ("home", "Home"),
    ("search", "Search"),
    ("library", "Library"),
    ("explore", "Explore"),
    ("mixes", "Mixes"),
    ("favorites", "Favorites"),
]


class AuxenWindow(Adw.ApplicationWindow):
    """Top-level window containing the sidebar and content stack."""

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)

        self.set_title("Auxen")
        self.set_default_size(1100, 700)

        split_view = Adw.NavigationSplitView()

        # ---- Sidebar ----
        self._sidebar = AuxenSidebar(on_navigate=self._switch_page)
        sidebar_page = Adw.NavigationPage.new(self._sidebar, "Sidebar")
        split_view.set_sidebar(sidebar_page)

        # ---- Content stack ----
        content_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)

        self._stack = Gtk.Stack()
        self._stack.set_transition_type(
            Gtk.StackTransitionType.CROSSFADE
        )
        self._stack.set_transition_duration(200)
        self._stack.set_vexpand(True)
        self._stack.set_hexpand(True)

        for name, title in _PAGES:
            placeholder = Gtk.Box(
                orientation=Gtk.Orientation.VERTICAL,
                valign=Gtk.Align.CENTER,
                halign=Gtk.Align.CENTER,
                spacing=8,
            )
            heading = Gtk.Label(label=f"{title} Page")
            heading.add_css_class("title-1")
            placeholder.append(heading)

            subtitle = Gtk.Label(label=f"This is the {title.lower()} view")
            subtitle.add_css_class("dim-label")
            placeholder.append(subtitle)

            self._stack.add_named(placeholder, name)

        content_box.append(self._stack)

        content_page = Adw.NavigationPage.new(content_box, "Content")
        split_view.set_content(content_page)

        self.set_content(split_view)

        # Show home by default
        self._stack.set_visible_child_name("home")

    def _switch_page(self, page_name: str) -> None:
        """Switch the content stack to the requested page."""
        child = self._stack.get_child_by_name(page_name)
        if child:
            self._stack.set_visible_child_name(page_name)
