"""Tests for auxen.views.about_dialog — About dialog constants and helpers."""

import re

import pytest

from auxen.views.about_dialog import (
    APPLICATION_ID,
    APPLICATION_NAME,
    ARTISTS,
    COMMENTS,
    COPYRIGHT,
    DEVELOPER_NAME,
    DEVELOPERS,
    ISSUE_URL,
    LICENSE_TYPE,
    RELEASE_NOTES,
    WEBSITE,
    get_version,
    show_about_dialog,
)


class TestShowAboutDialog:
    """Verify show_about_dialog function exists and is callable."""

    def test_show_about_dialog_is_callable(self) -> None:
        assert callable(show_about_dialog)

    def test_show_about_dialog_accepts_parent_arg(self) -> None:
        """The function signature accepts a parent_window parameter."""
        import inspect

        sig = inspect.signature(show_about_dialog)
        params = list(sig.parameters.keys())
        assert "parent_window" in params


class TestVersionString:
    """Verify version string format."""

    def test_get_version_returns_string(self) -> None:
        version = get_version()
        assert isinstance(version, str)

    def test_version_is_semver_format(self) -> None:
        version = get_version()
        assert re.match(r"^\d+\.\d+\.\d+", version) is not None

    def test_version_matches_package_version(self) -> None:
        import auxen

        assert get_version() == auxen.__version__

    def test_version_is_not_empty(self) -> None:
        assert len(get_version()) > 0


class TestLicenseType:
    """Verify license type constant."""

    def test_license_type_is_gpl3(self) -> None:
        import gi

        gi.require_version("Gtk", "4.0")
        from gi.repository import Gtk

        assert LICENSE_TYPE == Gtk.License.GPL_3_0

    def test_license_type_is_not_none(self) -> None:
        assert LICENSE_TYPE is not None


class TestApplicationConstants:
    """Verify application metadata constants."""

    def test_application_name(self) -> None:
        assert APPLICATION_NAME == "Auxen"

    def test_application_id(self) -> None:
        assert APPLICATION_ID == "io.github.auxen.Auxen"

    def test_developer_name(self) -> None:
        assert DEVELOPER_NAME == "mrw1986"

    def test_developers_list(self) -> None:
        assert isinstance(DEVELOPERS, list)
        assert len(DEVELOPERS) >= 2
        assert "mrw1986" in DEVELOPERS

    def test_artists_is_list(self) -> None:
        assert isinstance(ARTISTS, list)

    def test_copyright_contains_year(self) -> None:
        assert "2026" in COPYRIGHT

    def test_copyright_contains_author(self) -> None:
        assert "mrw1986" in COPYRIGHT

    def test_comments_not_empty(self) -> None:
        assert len(COMMENTS) > 0

    def test_comments_mentions_tidal(self) -> None:
        assert "Tidal" in COMMENTS

    def test_comments_mentions_local(self) -> None:
        assert "local" in COMMENTS.lower()

    def test_website_is_string(self) -> None:
        assert isinstance(WEBSITE, str)

    def test_issue_url_is_string(self) -> None:
        assert isinstance(ISSUE_URL, str)


class TestReleaseNotes:
    """Verify release notes content."""

    def test_release_notes_not_empty(self) -> None:
        assert len(RELEASE_NOTES) > 0

    def test_release_notes_contains_version(self) -> None:
        assert "0.1.0" in RELEASE_NOTES

    def test_release_notes_is_html(self) -> None:
        assert "<p>" in RELEASE_NOTES
        assert "<ul>" in RELEASE_NOTES
        assert "<li>" in RELEASE_NOTES
