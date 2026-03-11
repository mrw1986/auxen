"""About dialog for the Auxen music player."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gtk

import auxen

# --- Constants exposed for testing and reuse ---

APPLICATION_NAME = "Auxen"
APPLICATION_ID = "io.github.auxen.Auxen"
DEVELOPER_NAME = "Matt Warner"
DEVELOPERS = ["Matt Warner", "Claude (AI Assistant)"]
ARTISTS: list[str] = []
COPYRIGHT = "\u00a9 2026 Matt Warner"
LICENSE_TYPE = Gtk.License.GPL_3_0
COMMENTS = (
    "Unorthodox Audio. A premium music player for Tidal streaming "
    "and local media."
)
WEBSITE = ""
ISSUE_URL = ""
RELEASE_NOTES = (
    "<p>Auxen v0.1.0 - Initial Release</p>"
    "<ul>"
    "<li>Local music library scanning and playback</li>"
    "<li>Tidal streaming integration with HiFi support</li>"
    "<li>10-band graphic equalizer with presets</li>"
    "<li>ReplayGain normalization</li>"
    "<li>Crossfade transitions between tracks</li>"
    "<li>MPRIS media controls integration</li>"
    "<li>Sleep timer with fade-out</li>"
    "<li>Last.fm scrobbling</li>"
    "<li>Smart playlists and favorites sync</li>"
    "<li>Desktop notifications for track changes</li>"
    "</ul>"
)

# Icon names registered via data/icons/hicolor/scalable/apps/
# "dark" wordmark has light/cream fills — for dark backgrounds
# "light" wordmark has dark/brown fills — for light backgrounds
_ICON_FOR_DARK_THEME = "auxen-wordmark-dark"
_ICON_FOR_LIGHT_THEME = "auxen-wordmark-light"


def get_version() -> str:
    """Return the current Auxen version string."""
    return auxen.__version__


def _is_dark_theme() -> bool:
    """Check if the current Adw theme is dark."""
    return Adw.StyleManager.get_default().get_dark()


def _get_wordmark_icon_name() -> str:
    """Return the appropriate wordmark icon name for the current theme."""
    return _ICON_FOR_DARK_THEME if _is_dark_theme() else _ICON_FOR_LIGHT_THEME


def show_about_dialog(parent_window) -> Adw.AboutDialog:
    """Create and present the Auxen About dialog.

    Parameters
    ----------
    parent_window:
        The parent window to present the dialog from.

    Returns
    -------
    Adw.AboutDialog
        The created dialog instance.
    """
    dialog = Adw.AboutDialog(
        application_name="",
        application_icon=_get_wordmark_icon_name(),
        version=get_version(),
        developer_name="",
        license_type=LICENSE_TYPE,
        comments=COMMENTS,
        developers=DEVELOPERS,
        artists=ARTISTS,
        copyright=COPYRIGHT,
        release_notes=RELEASE_NOTES,
    )

    if WEBSITE:
        dialog.set_website(WEBSITE)
    if ISSUE_URL:
        dialog.set_issue_url(ISSUE_URL)

    dialog.present(parent_window)
    return dialog
