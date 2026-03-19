"""Shared reusable widgets for Auxen views."""

from __future__ import annotations

from typing import Callable

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import GLib, Gtk, Pango

# Shared quality tooltip map used across all views.
QUALITY_TOOLTIPS: dict[str, str] = {
    "FLAC": "FLAC Lossless Audio",
    "Hi-Res": "Hi-Res Lossless Audio (up to 24-bit/192kHz)",
    "MQA": "MQA Master Quality Audio",
    "AAC": "AAC Compressed Audio",
    "MP3": "MP3 Compressed Audio",
    "WAV": "WAV Uncompressed Audio",
    "ALAC": "ALAC Apple Lossless Audio",
    "OGG": "OGG Vorbis Compressed Audio",
    "OPUS": "Opus Compressed Audio",
}


def make_tidal_source_badge(
    label_text: str = "Tidal",
    css_class: str = "source-badge-tidal",
    icon_size: int = 12,
) -> Gtk.Box:
    """Create a Tidal source badge with icon + text in a pill shape.

    Returns a Gtk.Box that looks like the old Gtk.Label badge but with a
    tidal-symbolic icon prepended.

    Parameters
    ----------
    label_text:
        Text to display (e.g. "Tidal", "TIDAL", "Playlist").
    css_class:
        CSS class for styling (e.g. "source-badge-tidal", "nav-badge-tidal").
    icon_size:
        Pixel size for the Tidal icon.
    """
    box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
    box.add_css_class(css_class)

    icon = Gtk.Image.new_from_icon_name("tidal-symbolic")
    icon.set_pixel_size(icon_size)
    icon.set_valign(Gtk.Align.CENTER)
    box.append(icon)

    label = Gtk.Label(label=label_text)
    label.set_valign(Gtk.Align.CENTER)
    box.append(label)

    return box


def make_tidal_connect_prompt(
    css_class: str,
    icon_name: str = "tidal-symbolic",
    heading_text: str = "Connect to Tidal",
    description_text: str = (
        "Log in to your Tidal account to access\nthis feature."
    ),
    button_text: str = "Log In to Tidal",
    on_login_clicked: Callable[[], None] | None = None,
) -> Gtk.Box:
    """Build a standardized "Connect to Tidal" prompt card.

    Returns a centered Gtk.Box with an icon, heading, description, and
    a login button.  Use this across all views that need a Tidal login
    prompt so they share the same styling and structure.

    Parameters
    ----------
    css_class:
        CSS class applied to the container (e.g. "explore-login-prompt",
        "mixes-login-prompt").
    icon_name:
        Icon to display at the top of the card.
    heading_text:
        Large heading text.
    description_text:
        Descriptive text below the heading.
    button_text:
        Label for the login button.
    on_login_clicked:
        Callback invoked when the login button is clicked.
    """
    prompt = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=16,
        halign=Gtk.Align.CENTER,
        valign=Gtk.Align.CENTER,
    )
    prompt.add_css_class(css_class)
    prompt.set_margin_top(48)
    prompt.set_margin_bottom(48)

    icon = Gtk.Image.new_from_icon_name(icon_name)
    icon.set_pixel_size(64)
    icon.set_opacity(0.4)
    prompt.append(icon)

    heading = Gtk.Label(label=heading_text)
    heading.add_css_class("title-1")
    prompt.append(heading)

    description = Gtk.Label(label=description_text)
    description.add_css_class("dim-label")
    description.set_justify(Gtk.Justification.CENTER)
    prompt.append(description)

    login_btn = Gtk.Button(label=button_text)
    login_btn.add_css_class("suggested-action")
    login_btn.add_css_class("pill")
    login_btn.set_halign(Gtk.Align.CENTER)

    if on_login_clicked is not None:
        login_btn.connect("clicked", lambda _btn: on_login_clicked())

    prompt.append(login_btn)

    return prompt


def format_duration(seconds: float | None) -> str:
    """Format seconds as M:SS.

    Shared utility so every view uses the same formatting.
    """
    if seconds is None or seconds <= 0:
        return "0:00"
    total = int(seconds)
    minutes = total // 60
    secs = total % 60
    return f"{minutes}:{secs:02d}"


def make_source_badge(source: str) -> Gtk.Widget:
    """Create a small pill badge indicating the track source.

    Parameters
    ----------
    source:
        Source string, e.g. "tidal" or "local".
    """
    if source == "tidal":
        badge = make_tidal_source_badge(
            label_text=source.capitalize(),
            css_class="source-badge-tidal",
            icon_size=10,
        )
        badge.set_tooltip_text("Streaming from Tidal")
    else:
        badge = Gtk.Label(label=source.capitalize())
        badge.add_css_class("source-badge-local")
        badge.set_tooltip_text("Local library file")
    badge.set_valign(Gtk.Align.CENTER)
    return badge


def make_quality_badge(
    quality: str, track=None
) -> Gtk.Label | None:
    """Create a quality badge label with tooltip if quality is valid.

    Returns None if quality is empty or "Unknown".
    If *track* is provided, the tooltip includes bitrate, sample rate,
    and bit depth when available.
    """
    if not quality or quality == "Unknown":
        return None
    badge = Gtk.Label(label=quality)
    badge.add_css_class("collection-quality-badge")
    badge.set_valign(Gtk.Align.CENTER)

    # Build detailed tooltip
    parts: list[str] = [QUALITY_TOOLTIPS.get(quality, f"{quality} Audio")]
    if track is not None:
        bitrate = _get_attr(track, "bitrate", None)
        sample_rate = _get_attr(track, "sample_rate", None)
        bit_depth = _get_attr(track, "bit_depth", None)
        details: list[str] = []
        if bitrate and bitrate > 0:
            details.append(f"{bitrate} kbps")
        if sample_rate and sample_rate > 0:
            sr = sample_rate / 1000 if sample_rate >= 1000 else sample_rate
            details.append(f"{sr:g} kHz")
        if bit_depth and bit_depth > 0:
            details.append(f"{bit_depth}-bit")
        if details:
            parts.append(" · ".join(details))
    badge.set_tooltip_text("\n".join(parts))
    return badge


def make_standard_track_row(
    track,
    index: int | None = None,
    show_art: bool = True,
    show_play_btn: bool = False,
    show_source_badge: bool = True,
    show_quality_badge: bool = True,
    show_duration: bool = True,
    show_subtitle: bool = True,
    art_size: int = 48,
    css_class: str = "standard-track-row",
    on_play_clicked: Callable | None = None,
    on_artist_clicked: Callable | None = None,
    on_album_clicked: Callable | None = None,
    extra_widgets_before: list[Gtk.Widget] | None = None,
    extra_widgets_after: list[Gtk.Widget] | None = None,
) -> Gtk.ListBoxRow:
    """Build a standardized track row used across views.

    Layout: [extras_before] [#] [Play] [Art] [Title + Subtitle] [Duration] [Source] [Quality] [extras_after]

    Parameters
    ----------
    track:
        Track object (dataclass) or dict with keys: title, artist, album,
        source, quality/quality_label, duration.
    index:
        Optional 0-based index for track numbering (displayed as index+1).
    show_art:
        Whether to show the album art placeholder.
    show_play_btn:
        Whether to show a play button.
    show_source_badge:
        Whether to show the source badge (Tidal/Local).
    show_quality_badge:
        Whether to show the quality badge.
    show_duration:
        Whether to show the duration label.
    show_subtitle:
        Whether to show the artist/album subtitle line below the title.
        Set to False for views like album detail where the context is
        already clear.
    art_size:
        Size of the album art placeholder in pixels.
    css_class:
        CSS class for the row box.
    on_play_clicked:
        Callback when the play button is clicked; receives the track.
    on_artist_clicked:
        Callback for clickable artist name; receives artist string.
    on_album_clicked:
        Callback for clickable album name; receives (album, artist).
    extra_widgets_before:
        Widgets to prepend to the row (e.g., drag handle).
    extra_widgets_after:
        Widgets to append to the row (e.g., heart button, remove button).
    """
    # Extract data from track (supports both Track objects and dicts)
    title = _get_attr(track, "title", "")
    artist = _get_attr(track, "artist", "")
    album = _get_attr(track, "album", "")
    duration = _get_attr(track, "duration", None)
    source = _get_source_str(track)
    quality = _get_quality_str(track)

    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=12,
    )
    row_box.add_css_class(css_class)

    # -- Extra widgets before (e.g., drag handle) --
    if extra_widgets_before:
        for w in extra_widgets_before:
            row_box.append(w)

    # -- Track number --
    if index is not None:
        num_label = Gtk.Label(label=str(index + 1))
        num_label.add_css_class("caption")
        num_label.add_css_class("dim-label")
        num_label.set_size_request(28, -1)
        num_label.set_xalign(1)
        num_label.set_valign(Gtk.Align.CENTER)
        row_box.append(num_label)

    # -- Play button --
    if show_play_btn:
        play_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        play_btn.add_css_class("flat")
        play_btn.add_css_class("now-playing-control-btn")
        play_btn.set_valign(Gtk.Align.CENTER)
        play_btn.set_tooltip_text("Play")
        if on_play_clicked is not None:
            play_btn.connect(
                "clicked",
                lambda _btn, t=track: on_play_clicked(t),
            )
        row_box.append(play_btn)

    # -- Album art placeholder (with play overlay) --
    if show_art:
        art_box = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            halign=Gtk.Align.CENTER,
            valign=Gtk.Align.CENTER,
        )
        art_box.add_css_class("album-art-placeholder")
        art_box.add_css_class("album-art-mini")
        art_box.set_size_request(art_size, art_size)
        art_box.set_vexpand(False)

        icon_px = max(18, art_size // 2 - 4)
        art_icon = Gtk.Image.new_from_icon_name(
            "audio-x-generic-symbolic"
        )
        art_icon.set_pixel_size(icon_px)
        art_icon.set_opacity(0.4)
        art_icon.set_halign(Gtk.Align.CENTER)
        art_icon.set_valign(Gtk.Align.CENTER)
        art_icon.set_vexpand(True)
        art_box.append(art_icon)

        # Hidden image widget for async art loading
        art_image = Gtk.Image()
        art_image.set_pixel_size(art_size)
        art_image.set_size_request(art_size, art_size)
        art_image.set_halign(Gtk.Align.FILL)
        art_image.set_valign(Gtk.Align.FILL)
        art_image.add_css_class("album-card-art-image")
        art_image.set_visible(False)
        art_box.append(art_image)

        # Wrap in overlay with play button
        art_overlay = Gtk.Overlay()
        art_overlay.set_child(art_box)

        play_overlay_btn = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        play_overlay_btn.add_css_class("track-art-play-overlay")
        play_overlay_btn.set_halign(Gtk.Align.CENTER)
        play_overlay_btn.set_valign(Gtk.Align.CENTER)
        play_overlay_btn.set_visible(False)
        if on_play_clicked is not None:
            play_overlay_btn.connect(
                "clicked",
                lambda _btn, t=track: on_play_clicked(t),
            )
        art_overlay.add_overlay(play_overlay_btn)
        row_box.append(art_overlay)

    # -- Title + Subtitle column --
    text_box = Gtk.Box(
        orientation=Gtk.Orientation.VERTICAL,
        spacing=2,
    )
    text_box.set_hexpand(True)
    text_box.set_valign(Gtk.Align.CENTER)

    title_label = Gtk.Label()
    title_label.set_xalign(0)
    title_label.set_ellipsize(Pango.EllipsizeMode.END)
    title_label.set_max_width_chars(40)
    title_label.add_css_class("body")
    title_label.set_markup(
        f"<b>{GLib.markup_escape_text(str(title))}</b>"
    )
    text_box.append(title_label)

    # Subtitle: Artist -- Album (with optional click navigation)
    if not show_subtitle:
        pass  # Skip subtitle entirely
    elif on_artist_clicked is not None or on_album_clicked is not None:
        # Clickable subtitle labels
        subtitle_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL, spacing=0
        )
        subtitle_box.append(
            _make_nav_label(
                str(artist), on_artist_clicked, str(artist)
            )
        )
        if album:
            sep = Gtk.Label(label=" \u2014 ")
            sep.add_css_class("track-row-subtitle")
            subtitle_box.append(sep)
            subtitle_box.append(
                _make_nav_label(
                    str(album),
                    on_album_clicked,
                    str(album),
                    str(artist),
                )
            )
        text_box.append(subtitle_box)
    else:
        # Simple text subtitle
        subtitle_parts = [str(artist)]
        if album:
            subtitle_parts.append(str(album))
        subtitle_text = " \u2014 ".join(subtitle_parts)

        subtitle_label = Gtk.Label(label=subtitle_text)
        subtitle_label.set_xalign(0)
        subtitle_label.set_ellipsize(Pango.EllipsizeMode.END)
        subtitle_label.set_max_width_chars(50)
        subtitle_label.add_css_class("track-row-subtitle")
        text_box.append(subtitle_label)

    row_box.append(text_box)

    # -- Duration --
    if show_duration:
        if isinstance(duration, str):
            dur_text = duration
        else:
            dur_text = format_duration(duration)
        dur_label = Gtk.Label(label=dur_text)
        dur_label.add_css_class("caption")
        dur_label.add_css_class("dim-label")
        dur_label.set_valign(Gtk.Align.CENTER)
        dur_label.set_margin_start(4)
        row_box.append(dur_label)

    # -- Source badge --
    if show_source_badge and source:
        badge = make_source_badge(source)
        row_box.append(badge)

    # -- Quality badge --
    if show_quality_badge and quality:
        q_badge = make_quality_badge(quality, track=track)
        if q_badge is not None:
            row_box.append(q_badge)

    # -- Extra widgets after (e.g., heart, reorder, remove) --
    if extra_widgets_after:
        for w in extra_widgets_after:
            row_box.append(w)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)

    # Store track reference for external use
    row._track_data = track  # type: ignore[attr-defined]

    # Store art widget references for async art loading
    if show_art:
        row._art_icon = art_icon  # type: ignore[attr-defined]
        row._art_image = art_image  # type: ignore[attr-defined]
        row._art_box = art_box  # type: ignore[attr-defined]
        row._play_overlay_btn = play_overlay_btn  # type: ignore[attr-defined]

        # Hover detection to show/hide play overlay
        motion = Gtk.EventControllerMotion.new()

        def _on_enter(*_args, btn=play_overlay_btn):
            btn.set_visible(True)

        def _on_leave(*_args, btn=play_overlay_btn):
            btn.set_visible(False)

        motion.connect("enter", _on_enter)
        motion.connect("leave", _on_leave)
        row.add_controller(motion)

    return row


def make_compact_track_row(
    track,
    index: int | None = None,
    show_source_badge: bool = True,
    show_quality_badge: bool = False,
    css_class: str = "compact-track-row",
    on_artist_clicked: Callable | None = None,
    on_album_clicked: Callable | None = None,
    extra_widgets_after: list[Gtk.Widget] | None = None,
) -> Gtk.ListBoxRow:
    """Build a compact track row with reduced spacing and no album art.

    Layout: [#] [Title -- Artist] [Duration] [Source] [extras_after]
    """
    title = _get_attr(track, "title", "")
    artist = _get_attr(track, "artist", "")
    album = _get_attr(track, "album", "")
    duration = _get_attr(track, "duration", None)
    source = _get_source_str(track)
    quality = _get_quality_str(track)

    row_box = Gtk.Box(
        orientation=Gtk.Orientation.HORIZONTAL,
        spacing=8,
    )
    row_box.add_css_class(css_class)

    # -- Track number / play button stack --
    _compact_play_btn = None
    _compact_num_label = None
    if index is not None:
        num_label = Gtk.Label(label=str(index + 1))
        num_label.add_css_class("caption")
        num_label.add_css_class("dim-label")
        num_label.set_size_request(24, -1)
        num_label.set_xalign(1)
        num_label.set_valign(Gtk.Align.CENTER)

        compact_play = Gtk.Button.new_from_icon_name(
            "media-playback-start-symbolic"
        )
        compact_play.add_css_class("flat")
        compact_play.add_css_class("compact-row-play-btn")
        compact_play.set_size_request(24, -1)
        compact_play.set_valign(Gtk.Align.CENTER)
        compact_play.set_visible(False)

        index_stack = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        index_stack.set_size_request(24, -1)
        index_stack.append(num_label)
        index_stack.append(compact_play)
        row_box.append(index_stack)

        _compact_play_btn = compact_play
        _compact_num_label = num_label

    # -- Single-line: Title -- Artist --
    if on_artist_clicked is not None or on_album_clicked is not None:
        # Use a box with separate labels so artist/album are clickable
        text_box = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL, spacing=0
        )
        text_box.set_hexpand(True)
        text_box.set_valign(Gtk.Align.CENTER)

        title_label = Gtk.Label()
        title_label.set_ellipsize(Pango.EllipsizeMode.END)
        title_label.set_max_width_chars(30)
        title_label.add_css_class("body")
        title_label.set_markup(
            f"<b>{GLib.markup_escape_text(str(title))}</b>"
        )
        text_box.append(title_label)

        if artist:
            sep = Gtk.Label(label="  ")
            text_box.append(sep)
            artist_label = _make_nav_label(
                str(artist), on_artist_clicked, str(artist)
            )
            artist_label.set_max_width_chars(25)
            # Style as dimmed to match the non-clickable version
            artist_label.add_css_class("dim-label")
            text_box.append(artist_label)

        row_box.append(text_box)
    else:
        text_label = Gtk.Label()
        text_label.set_xalign(0)
        text_label.set_hexpand(True)
        text_label.set_ellipsize(Pango.EllipsizeMode.END)
        text_label.set_max_width_chars(60)
        text_label.add_css_class("body")
        text_label.set_markup(
            f"<b>{GLib.markup_escape_text(str(title))}</b>"
            f"  <span alpha='60%'>"
            f"{GLib.markup_escape_text(str(artist))}</span>"
        )
        text_label.set_valign(Gtk.Align.CENTER)
        row_box.append(text_label)

    # -- Duration --
    if isinstance(duration, str):
        dur_text = duration
    else:
        dur_text = format_duration(duration)
    dur_label = Gtk.Label(label=dur_text)
    dur_label.add_css_class("caption")
    dur_label.add_css_class("dim-label")
    dur_label.set_valign(Gtk.Align.CENTER)
    row_box.append(dur_label)

    # -- Source badge --
    if show_source_badge and source:
        badge = make_source_badge(source)
        row_box.append(badge)

    # -- Quality badge --
    if show_quality_badge and quality:
        q_badge = make_quality_badge(quality, track=track)
        if q_badge is not None:
            row_box.append(q_badge)

    # -- Extra widgets --
    if extra_widgets_after:
        for w in extra_widgets_after:
            row_box.append(w)

    row = Gtk.ListBoxRow()
    row.add_css_class("track-row-hover")
    row.set_child(row_box)
    row._track_data = track  # type: ignore[attr-defined]

    # Hover detection for compact play button
    if _compact_play_btn is not None and _compact_num_label is not None:
        motion = Gtk.EventControllerMotion.new()

        def _on_enter(*_args, pb=_compact_play_btn, nl=_compact_num_label):
            nl.set_visible(False)
            pb.set_visible(True)

        def _on_leave(*_args, pb=_compact_play_btn, nl=_compact_num_label):
            pb.set_visible(False)
            nl.set_visible(True)

        motion.connect("enter", _on_enter)
        motion.connect("leave", _on_leave)
        row.add_controller(motion)

    return row


# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------


def _get_attr(obj, key: str, default=None):
    """Get attribute from a Track object or dict."""
    if isinstance(obj, dict):
        return obj.get(key, default)
    return getattr(obj, key, default)


def _get_source_str(track) -> str:
    """Extract source as a plain string from Track or dict."""
    source = _get_attr(track, "source", "")
    if source is None:
        return ""
    # Handle Source enum
    if hasattr(source, "value"):
        return source.value
    return str(source)


def _get_quality_str(track) -> str:
    """Extract quality label as a plain string from Track or dict."""
    # Try quality_label first (Track objects), then quality (dicts)
    quality = _get_attr(track, "quality_label", None)
    if quality is None:
        quality = _get_attr(track, "quality", "")
    return str(quality) if quality else ""


def _make_nav_label(
    text: str, callback: Callable | None, *cb_args
) -> Gtk.Label:
    """Create a clickable navigation label for artist/album subtitles."""
    lbl = Gtk.Label(label=text)
    lbl.set_ellipsize(Pango.EllipsizeMode.END)
    lbl.set_max_width_chars(25)
    lbl.add_css_class("track-row-subtitle")
    if callback is not None:
        lbl.add_css_class("track-nav-link")
        g = Gtk.GestureClick.new()
        g.set_button(1)

        def _on_click(
            gest, n_press, _x, _y, _cb=callback, _args=cb_args
        ):
            if n_press != 1:
                return
            gest.set_state(Gtk.EventSequenceState.CLAIMED)
            _cb(*_args)

        g.connect("released", _on_click)
        lbl.add_controller(g)
    return lbl


# ---------------------------------------------------------------------------
# Drag-to-scroll with kinetic momentum
# ---------------------------------------------------------------------------

try:
    from gi.repository import Gdk as _Gdk
except ImportError:
    _Gdk = None  # type: ignore[assignment]


class DragScrollHelper:
    """Adds mouse drag-to-scroll with kinetic momentum to a ScrolledWindow.

    Supports horizontal, vertical, or auto-detected scroll direction.
    Attach to any ScrolledWindow to let users click-drag to scroll
    and flick-release for momentum scrolling.

    Parameters
    ----------
    scroll_window:
        The ``Gtk.ScrolledWindow`` to enhance.
    axis:
        ``"horizontal"``, ``"vertical"``, or ``"auto"`` (detect from policy).
    friction:
        Deceleration factor applied each frame (0-1). Lower = more friction.
    velocity_scale:
        Multiplier for the initial flick velocity.
    grab_cursor:
        Show grab/grabbing cursors. Best for horizontal card rows;
        set ``False`` for vertical page scrolling to keep the default cursor.
    """

    _DRAG_THRESHOLD = 8  # px before recognising a drag
    _TICK_MS = 16  # ~60 fps
    _MIN_VELOCITY = 0.5  # px/tick — stop threshold

    def __init__(
        self,
        scroll_window: Gtk.ScrolledWindow,
        axis: str = "auto",
        friction: float = 0.92,
        velocity_scale: float = 1.0,
        grab_cursor: bool | None = None,
    ) -> None:
        self._sw = scroll_window
        self._friction = friction
        self._velocity_scale = velocity_scale

        # Determine scroll axis
        if axis == "auto":
            h_pol = scroll_window.get_policy()[0]  # type: ignore[index]
            # Horizontal-only if h is scrollable and v is NEVER
            if h_pol != Gtk.PolicyType.NEVER:
                self._horizontal = True
            else:
                self._horizontal = False
        else:
            self._horizontal = axis == "horizontal"

        # Default: show grab cursor for horizontal rows, not for vertical
        if grab_cursor is None:
            grab_cursor = self._horizontal
        self._grab_cursor = grab_cursor

        self._start_value: float = 0.0
        self._dragging: bool = False
        self._velocity: float = 0.0
        self._prev_offset: float = 0.0
        self._tick_id: int = 0

        gesture = Gtk.GestureDrag(button=1)
        gesture.connect("drag-begin", self._on_begin)
        gesture.connect("drag-update", self._on_update)
        gesture.connect("drag-end", self._on_end)
        scroll_window.add_controller(gesture)
        self._gesture = gesture

        if self._grab_cursor and _Gdk is not None:
            scroll_window.set_cursor(_Gdk.Cursor.new_from_name("grab"))

    # -- public query --

    @property
    def is_dragging(self) -> bool:
        """True if the user is actively dragging (past threshold)."""
        return self._dragging

    # -- internal helpers --

    def _get_adj(self):
        if self._horizontal:
            return self._sw.get_hadjustment()
        return self._sw.get_vadjustment()

    def _offset(self, offset_x: float, offset_y: float) -> float:
        return offset_x if self._horizontal else offset_y

    # -- gesture callbacks --

    def _on_begin(self, gesture, start_x, start_y) -> None:
        self._stop_kinetic()
        adj = self._get_adj()
        self._start_value = adj.get_value()
        self._dragging = False
        self._velocity = 0.0
        self._prev_offset = 0.0
        if self._grab_cursor and _Gdk is not None:
            self._sw.set_cursor(_Gdk.Cursor.new_from_name("grabbing"))

    def _on_update(self, gesture, offset_x, offset_y) -> None:
        off = self._offset(offset_x, offset_y)
        if abs(off) > self._DRAG_THRESHOLD:
            self._dragging = True
        # Track per-frame velocity
        self._velocity = self._prev_offset - off
        self._prev_offset = off
        adj = self._get_adj()
        new_val = self._start_value - off
        lo, hi = adj.get_lower(), adj.get_upper() - adj.get_page_size()
        adj.set_value(max(lo, min(new_val, hi)))

    def _on_end(self, gesture, offset_x, offset_y) -> None:
        if self._grab_cursor and _Gdk is not None:
            self._sw.set_cursor(_Gdk.Cursor.new_from_name("grab"))
        # Launch kinetic coast if fast enough
        v = self._velocity * self._velocity_scale
        if abs(v) > self._MIN_VELOCITY:
            self._velocity = v
            self._tick_id = GLib.timeout_add(self._TICK_MS, self._kinetic_tick)
        # Clear dragging flag after a short delay so child click handlers
        # can still check it (click fires before drag-end sometimes).
        GLib.timeout_add(50, self._reset_dragging)

    # -- kinetic animation --

    def _kinetic_tick(self) -> bool:
        adj = self._get_adj()
        lo, hi = adj.get_lower(), adj.get_upper() - adj.get_page_size()
        new_val = adj.get_value() + self._velocity
        new_val = max(lo, min(new_val, hi))
        adj.set_value(new_val)
        self._velocity *= self._friction
        if abs(self._velocity) < self._MIN_VELOCITY or new_val <= lo or new_val >= hi:
            self._tick_id = 0
            return False  # stop
        return True  # continue

    def _stop_kinetic(self) -> None:
        if self._tick_id:
            GLib.source_remove(self._tick_id)
            self._tick_id = 0
        self._velocity = 0.0

    def _reset_dragging(self) -> bool:
        self._dragging = False
        return False


# ---------------------------------------------------------------------------
# Horizontal carousel — scrollable card row with section title
# ---------------------------------------------------------------------------

try:
    from gi.repository import Gdk as _GdkCarousel
except ImportError:
    _GdkCarousel = None  # type: ignore[assignment]


class HorizontalCarousel(Gtk.Box):
    """A section with a title and horizontally scrollable content."""

    _SCROLL_STEP = 300  # px scrolled per arrow click

    def __init__(
        self,
        title: str,
        view_all_callback: Callable | None = None,
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)

        # --- Header row ---
        header = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=8,
        )
        header.set_margin_start(8)
        header.set_margin_end(8)

        title_label = Gtk.Label(label=title)
        title_label.set_xalign(0)
        title_label.set_hexpand(True)
        title_label.add_css_class("section-header")
        header.append(title_label)

        if view_all_callback is not None:
            view_all_btn = Gtk.Button(label="View all")
            view_all_btn.add_css_class("flat")
            view_all_btn.add_css_class("dim-label")
            view_all_btn.set_valign(Gtk.Align.CENTER)
            view_all_btn.connect(
                "clicked", lambda _btn: view_all_callback()
            )
            header.append(view_all_btn)

        self.append(header)

        # --- Scroll area with overlay for arrows ---
        overlay = Gtk.Overlay()

        self._scroll = Gtk.ScrolledWindow()
        self._scroll.set_policy(
            Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER,
        )
        self._scroll.add_css_class("horizontal-carousel")
        self._scroll.set_min_content_height(200)

        self._inner = Gtk.Box(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=16,
        )
        self._inner.set_margin_start(8)
        self._inner.set_margin_end(8)
        self._inner.set_margin_top(4)
        self._inner.set_margin_bottom(4)
        self._scroll.set_child(self._inner)
        overlay.set_child(self._scroll)

        # Drag-to-scroll
        self._drag_helper = DragScrollHelper(
            self._scroll, axis="horizontal",
        )

        # Left arrow
        self._left_btn = Gtk.Button.new_from_icon_name(
            "go-previous-symbolic",
        )
        self._left_btn.add_css_class("osd")
        self._left_btn.add_css_class("circular")
        self._left_btn.set_halign(Gtk.Align.START)
        self._left_btn.set_valign(Gtk.Align.CENTER)
        self._left_btn.set_margin_start(4)
        self._left_btn.set_visible(False)
        self._left_btn.connect("clicked", self._on_scroll_left)
        overlay.add_overlay(self._left_btn)

        # Right arrow
        self._right_btn = Gtk.Button.new_from_icon_name(
            "go-next-symbolic",
        )
        self._right_btn.add_css_class("osd")
        self._right_btn.add_css_class("circular")
        self._right_btn.set_halign(Gtk.Align.END)
        self._right_btn.set_valign(Gtk.Align.CENTER)
        self._right_btn.set_margin_end(4)
        self._right_btn.set_visible(False)
        self._right_btn.connect("clicked", self._on_scroll_right)
        overlay.add_overlay(self._right_btn)

        self.append(overlay)

        # Update arrow visibility on scroll
        hadj = self._scroll.get_hadjustment()
        hadj.connect("value-changed", self._update_arrows)
        hadj.connect("notify::upper", self._update_arrows)

    # --- Public API ---

    def append_card(self, widget: Gtk.Widget) -> None:
        """Add a card widget to the inner horizontal box."""
        self._inner.append(widget)
        # Schedule arrow update after layout settles
        GLib.idle_add(self._update_arrows)

    def clear(self) -> None:
        """Remove all cards from the carousel."""
        child = self._inner.get_first_child()
        while child is not None:
            next_child = child.get_next_sibling()
            self._inner.remove(child)
            child = next_child
        self._update_arrows()

    # --- Arrow behaviour ---

    def _on_scroll_left(self, _btn) -> None:
        hadj = self._scroll.get_hadjustment()
        new_val = max(hadj.get_lower(), hadj.get_value() - self._SCROLL_STEP)
        hadj.set_value(new_val)

    def _on_scroll_right(self, _btn) -> None:
        hadj = self._scroll.get_hadjustment()
        max_val = hadj.get_upper() - hadj.get_page_size()
        new_val = min(max_val, hadj.get_value() + self._SCROLL_STEP)
        hadj.set_value(new_val)

    def _update_arrows(self, *_args) -> None:
        hadj = self._scroll.get_hadjustment()
        val = hadj.get_value()
        upper = hadj.get_upper()
        page = hadj.get_page_size()
        # Show left arrow if not at start
        self._left_btn.set_visible(val > hadj.get_lower() + 1)
        # Show right arrow if not at end
        self._right_btn.set_visible(val < upper - page - 1)
