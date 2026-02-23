"""Tests for auxen.matching — normalize, match, and source-priority logic."""

from auxen.matching import normalize_for_matching, pick_preferred_track, tracks_match
from auxen.models import Source, SourcePriority, Track


class TestNormalize:
    def test_normalize_strips_and_lowercases(self) -> None:
        assert normalize_for_matching("  Hello World  ") == "hello world"

    def test_normalize_handles_feat_variations(self) -> None:
        assert normalize_for_matching(
            "Song (feat. Artist)"
        ) == normalize_for_matching("Song (ft. Artist)")

    def test_normalize_featuring_keyword(self) -> None:
        assert normalize_for_matching(
            "Song (featuring Artist)"
        ) == normalize_for_matching("Song (ft. Artist)")

    def test_normalize_removes_non_alphanumeric(self) -> None:
        assert normalize_for_matching("Hello! @World#") == "hello world"

    def test_normalize_collapses_multiple_spaces(self) -> None:
        assert normalize_for_matching("hello   world") == "hello world"

    def test_normalize_empty_string(self) -> None:
        assert normalize_for_matching("") == ""


class TestTracksMatch:
    def test_tracks_match_same_song(self) -> None:
        a = Track(
            title="Reckoner",
            artist="Radiohead",
            source=Source.LOCAL,
            source_id="a",
        )
        b = Track(
            title="Reckoner",
            artist="Radiohead",
            source=Source.TIDAL,
            source_id="b",
        )
        assert tracks_match(a, b)

    def test_tracks_match_case_insensitive(self) -> None:
        a = Track(
            title="reckoner",
            artist="radiohead",
            source=Source.LOCAL,
            source_id="a",
        )
        b = Track(
            title="Reckoner",
            artist="Radiohead",
            source=Source.TIDAL,
            source_id="b",
        )
        assert tracks_match(a, b)

    def test_tracks_no_match_different_song(self) -> None:
        a = Track(
            title="Reckoner",
            artist="Radiohead",
            source=Source.LOCAL,
            source_id="a",
        )
        b = Track(
            title="Creep",
            artist="Radiohead",
            source=Source.TIDAL,
            source_id="b",
        )
        assert not tracks_match(a, b)

    def test_tracks_match_with_feat_variation(self) -> None:
        a = Track(
            title="Song (feat. Someone)",
            artist="Artist",
            source=Source.LOCAL,
            source_id="a",
        )
        b = Track(
            title="Song (ft. Someone)",
            artist="Artist",
            source=Source.TIDAL,
            source_id="b",
        )
        assert tracks_match(a, b)

    def test_tracks_match_fuzzy_near_threshold(self) -> None:
        """Slightly different titles that exceed a lowered threshold."""
        a = Track(
            title="Paranoid Android",
            artist="Radiohead",
            source=Source.LOCAL,
            source_id="a",
        )
        b = Track(
            title="Paranoid Android (Remaster)",
            artist="Radiohead",
            source=Source.TIDAL,
            source_id="b",
        )
        # Normalized fuzzy ratio is 78, so the default 85 rejects it.
        assert not tracks_match(a, b)
        # But a caller can lower the threshold to accept remasters.
        assert tracks_match(a, b, threshold=75)

    def test_tracks_no_match_different_artist(self) -> None:
        a = Track(
            title="Reckoner",
            artist="Radiohead",
            source=Source.LOCAL,
            source_id="a",
        )
        b = Track(
            title="Reckoner",
            artist="Beyonce",
            source=Source.TIDAL,
            source_id="b",
        )
        assert not tracks_match(a, b)


class TestPickPreferredTrack:
    def test_prefer_local(self) -> None:
        local = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
            format="FLAC",
            bit_depth=16,
            sample_rate=44100,
        )
        tidal = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
            format="FLAC",
            bit_depth=16,
            sample_rate=44100,
        )
        result = pick_preferred_track(local, tidal, SourcePriority.PREFER_LOCAL)
        assert result.source == Source.LOCAL

    def test_prefer_local_when_tidal_first(self) -> None:
        local = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
        )
        tidal = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
        )
        result = pick_preferred_track(tidal, local, SourcePriority.PREFER_LOCAL)
        assert result.source == Source.LOCAL

    def test_prefer_tidal(self) -> None:
        local = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
        )
        tidal = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
        )
        result = pick_preferred_track(local, tidal, SourcePriority.PREFER_TIDAL)
        assert result.source == Source.TIDAL

    def test_prefer_tidal_when_tidal_first(self) -> None:
        local = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
        )
        tidal = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
        )
        result = pick_preferred_track(tidal, local, SourcePriority.PREFER_TIDAL)
        assert result.source == Source.TIDAL

    def test_prefer_quality_picks_hires(self) -> None:
        local_mp3 = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
            format="MP3",
            bitrate=320,
        )
        tidal_hires = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
            format="FLAC",
            bit_depth=24,
            sample_rate=96000,
        )
        result = pick_preferred_track(
            local_mp3, tidal_hires, SourcePriority.PREFER_QUALITY
        )
        assert result.source == Source.TIDAL

    def test_prefer_quality_picks_local_flac(self) -> None:
        local_flac = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
            format="FLAC",
            bit_depth=16,
            sample_rate=44100,
        )
        tidal_high = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
            format="AAC",
            bitrate=320,
        )
        result = pick_preferred_track(
            local_flac, tidal_high, SourcePriority.PREFER_QUALITY
        )
        assert result.source == Source.LOCAL

    def test_prefer_quality_equal_scores_returns_first(self) -> None:
        """When quality scores are equal, return the first track."""
        a = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
            format="FLAC",
            bit_depth=16,
            sample_rate=44100,
        )
        b = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
            format="FLAC",
            bit_depth=16,
            sample_rate=44100,
        )
        result = pick_preferred_track(a, b, SourcePriority.PREFER_QUALITY)
        assert result.source == Source.LOCAL

    def test_always_ask_returns_local(self) -> None:
        local = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
        )
        tidal = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
        )
        result = pick_preferred_track(local, tidal, SourcePriority.ALWAYS_ASK)
        assert result.source == Source.LOCAL

    def test_always_ask_returns_local_when_tidal_first(self) -> None:
        local = Track(
            title="A",
            artist="B",
            source=Source.LOCAL,
            source_id="a",
        )
        tidal = Track(
            title="A",
            artist="B",
            source=Source.TIDAL,
            source_id="b",
        )
        result = pick_preferred_track(tidal, local, SourcePriority.ALWAYS_ASK)
        assert result.source == Source.LOCAL
