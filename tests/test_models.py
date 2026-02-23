"""Tests for auxen.models — Track, Source, SourcePriority."""

from auxen.models import Source, SourcePriority, Track


class TestSourceEnum:
    def test_local_value(self) -> None:
        assert Source.LOCAL.value == "local"

    def test_tidal_value(self) -> None:
        assert Source.TIDAL.value == "tidal"


class TestSourcePriorityEnum:
    def test_prefer_local(self) -> None:
        assert SourcePriority.PREFER_LOCAL.value == "prefer_local"

    def test_prefer_tidal(self) -> None:
        assert SourcePriority.PREFER_TIDAL.value == "prefer_tidal"

    def test_prefer_quality(self) -> None:
        assert SourcePriority.PREFER_QUALITY.value == "prefer_quality"

    def test_always_ask(self) -> None:
        assert SourcePriority.ALWAYS_ASK.value == "always_ask"


class TestTrackCreation:
    def test_required_fields(self) -> None:
        track = Track(
            title="Echoes",
            artist="Pink Floyd",
            source=Source.LOCAL,
            source_id="/music/echoes.flac",
        )
        assert track.title == "Echoes"
        assert track.artist == "Pink Floyd"
        assert track.source == Source.LOCAL
        assert track.source_id == "/music/echoes.flac"

    def test_optional_fields_default_none(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.TIDAL,
            source_id="12345",
        )
        assert track.album is None
        assert track.album_artist is None
        assert track.genre is None
        assert track.year is None
        assert track.duration is None
        assert track.track_number is None
        assert track.disc_number is None
        assert track.bitrate is None
        assert track.format is None
        assert track.sample_rate is None
        assert track.bit_depth is None
        assert track.album_art_url is None
        assert track.match_group_id is None
        assert track.id is None
        assert track.added_at is None
        assert track.last_played_at is None
        assert track.play_count == 0

    def test_is_local_property(self) -> None:
        track = Track(
            title="T", artist="A", source=Source.LOCAL, source_id="p"
        )
        assert track.is_local is True
        assert track.is_tidal is False

    def test_is_tidal_property(self) -> None:
        track = Track(
            title="T", artist="A", source=Source.TIDAL, source_id="123"
        )
        assert track.is_tidal is True
        assert track.is_local is False


class TestTrackQualityScore:
    def test_hires_flac(self) -> None:
        """Hi-Res FLAC: 24-bit, 96kHz+ -> 1000."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="FLAC",
            bit_depth=24,
            sample_rate=96000,
        )
        assert track.quality_score == 1000

    def test_hires_flac_192khz(self) -> None:
        """Hi-Res FLAC at 192kHz should also score 1000."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="FLAC",
            bit_depth=24,
            sample_rate=192000,
        )
        assert track.quality_score == 1000

    def test_flac_16bit(self) -> None:
        """FLAC 16-bit -> 500."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="FLAC",
            bit_depth=16,
            sample_rate=44100,
        )
        assert track.quality_score == 500

    def test_flac_no_bitdepth(self) -> None:
        """FLAC with no bit_depth info -> 500 (assume CD quality)."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="FLAC",
        )
        assert track.quality_score == 500

    def test_wav_score(self) -> None:
        """WAV 16-bit -> 500."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="WAV",
            bit_depth=16,
        )
        assert track.quality_score == 500

    def test_alac_score(self) -> None:
        """ALAC 16-bit -> 500."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="ALAC",
            bit_depth=16,
        )
        assert track.quality_score == 500

    def test_aac_320(self) -> None:
        """AAC 320kbps -> 300."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="AAC",
            bitrate=320,
        )
        assert track.quality_score == 300

    def test_ogg_320(self) -> None:
        """OGG 320kbps -> 300."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="OGG",
            bitrate=320,
        )
        assert track.quality_score == 300

    def test_opus_320(self) -> None:
        """OPUS 320kbps -> 300."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="OPUS",
            bitrate=320,
        )
        assert track.quality_score == 300

    def test_aac_256(self) -> None:
        """AAC 256kbps -> 200."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="AAC",
            bitrate=256,
        )
        assert track.quality_score == 200

    def test_mp3_320(self) -> None:
        """MP3 320kbps -> 250."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="MP3",
            bitrate=320,
        )
        assert track.quality_score == 250

    def test_mp3_128(self) -> None:
        """MP3 128kbps -> 100."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="MP3",
            bitrate=128,
        )
        assert track.quality_score == 100

    def test_mp3_no_bitrate(self) -> None:
        """MP3 with no bitrate info -> 100 (worst case)."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="MP3",
        )
        assert track.quality_score == 100

    def test_unknown_format(self) -> None:
        """Unknown format -> 0."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="WMA",
        )
        assert track.quality_score == 0

    def test_no_format(self) -> None:
        """No format at all -> 0."""
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
        )
        assert track.quality_score == 0

    def test_quality_ordering(self) -> None:
        """Hi-Res > FLAC > AAC 320 > MP3 320 > AAC 256 > MP3 128."""
        hires = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="1",
            format="FLAC",
            bit_depth=24,
            sample_rate=96000,
        )
        flac = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="2",
            format="FLAC",
            bit_depth=16,
        )
        aac320 = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="3",
            format="AAC",
            bitrate=320,
        )
        mp3_320 = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="4",
            format="MP3",
            bitrate=320,
        )
        aac256 = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="5",
            format="AAC",
            bitrate=256,
        )
        mp3_128 = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="6",
            format="MP3",
            bitrate=128,
        )
        assert (
            hires.quality_score
            > flac.quality_score
            > aac320.quality_score
            > mp3_320.quality_score
            > aac256.quality_score
            > mp3_128.quality_score
        )


class TestTrackQualityLabel:
    def test_hires_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="FLAC",
            bit_depth=24,
            sample_rate=96000,
        )
        assert track.quality_label == "Hi-Res"

    def test_flac_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="FLAC",
            bit_depth=16,
        )
        assert track.quality_label == "FLAC"

    def test_wav_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="WAV",
        )
        assert track.quality_label == "WAV"

    def test_alac_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="ALAC",
        )
        assert track.quality_label == "ALAC"

    def test_mp3_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="MP3",
            bitrate=320,
        )
        assert track.quality_label == "MP3"

    def test_aac_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
            format="AAC",
            bitrate=256,
        )
        assert track.quality_label == "AAC"

    def test_unknown_label(self) -> None:
        track = Track(
            title="T",
            artist="A",
            source=Source.LOCAL,
            source_id="p",
        )
        assert track.quality_label == "Unknown"
