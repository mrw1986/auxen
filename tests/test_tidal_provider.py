"""Tests for the Tidal streaming provider."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from auxen.models import Source, Track
from auxen.providers.base import ContentProvider
from auxen.providers.tidal import TidalProvider


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------


def _mock_tidal_track(
    track_id: int = 123,
    name: str = "Reckoner",
    artist_name: str = "Radiohead",
    album_name: str = "In Rainbows",
    duration: int = 290,
) -> MagicMock:
    """Return a MagicMock mimicking a ``tidalapi.Track``."""
    track = MagicMock()
    track.id = track_id
    track.name = name
    track.duration = duration
    track.artist = MagicMock()
    track.artist.name = artist_name
    track.artists = [track.artist]
    track.album = MagicMock()
    track.album.name = album_name
    track.album.cover = "abc-def-123"
    track.get_url.return_value = "https://stream.tidal.com/track/123"
    return track


# ------------------------------------------------------------------
# Tests
# ------------------------------------------------------------------


class TestTidalTrackToModel:
    """Verify _tidal_track_to_model converts tidalapi tracks correctly."""

    @patch("auxen.providers.tidal.tidalapi")
    def test_track_to_model(self, mock_tidalapi: MagicMock) -> None:
        provider = TidalProvider()
        tidal_track = _mock_tidal_track()

        result = provider._tidal_track_to_model(tidal_track)

        assert isinstance(result, Track)
        assert result.title == "Reckoner"
        assert result.artist == "Radiohead"
        assert result.album == "In Rainbows"
        assert result.source == Source.TIDAL
        assert result.source_id == "123"
        assert result.duration == 290
        assert result.format == "FLAC"

    @patch("auxen.providers.tidal.tidalapi")
    def test_cover_url_construction(self, mock_tidalapi: MagicMock) -> None:
        provider = TidalProvider()
        tidal_track = _mock_tidal_track()

        result = provider._tidal_track_to_model(tidal_track)

        expected_url = "https://resources.tidal.com/images/abc/def/123/640x640.jpg"
        assert result.album_art_url == expected_url


class TestTidalGetStreamUri:
    """Verify get_stream_uri resolves a stream URL from Tidal."""

    @patch("auxen.providers.tidal.tidalapi")
    def test_get_stream_uri(self, mock_tidalapi: MagicMock) -> None:
        provider = TidalProvider()
        tidal_track = _mock_tidal_track()
        provider._session.track.return_value = tidal_track

        track = Track(
            title="Reckoner",
            artist="Radiohead",
            source=Source.TIDAL,
            source_id="123",
        )

        url = provider.get_stream_uri(track)

        provider._session.track.assert_called_once_with(123)
        assert url == "https://stream.tidal.com/track/123"


class TestTidalSearch:
    """Verify search delegates to the Tidal API and converts results."""

    @patch("auxen.providers.tidal.tidalapi")
    def test_search(self, mock_tidalapi: MagicMock) -> None:
        provider = TidalProvider()
        tidal_track = _mock_tidal_track()
        provider._session.search.return_value = {"tracks": [tidal_track]}

        results = provider.search("Reckoner")

        provider._session.search.assert_called_once()
        assert len(results) == 1
        assert results[0].title == "Reckoner"
        assert results[0].source == Source.TIDAL


class TestTidalIsContentProvider:
    """Verify TidalProvider satisfies the ContentProvider interface."""

    @patch("auxen.providers.tidal.tidalapi")
    def test_is_content_provider(self, mock_tidalapi: MagicMock) -> None:
        provider = TidalProvider()
        assert isinstance(provider, ContentProvider)
