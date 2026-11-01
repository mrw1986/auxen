package io.github.auxen.playback.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.CapturingRenderersFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spike for the predictive track cache (Task 1): can a cached Hi-Res song be
 * stored as a local DASH folder (option 1) or one concatenated fMP4 (option 2)
 * and still play and seek on Media3 1.5.1?
 *
 * The fixture mirrors a real Tidal Hi-Res MPD: one FLAC-in-fMP4 representation,
 * SegmentTemplate + SegmentTimeline with `$Number$`, an init segment, and media
 * segments that are bare moof+mdat (no sidx), so the concatenated file behaves
 * like a concatenated Tidal song would.
 *
 * One deliberate difference: Tidal writes 0 in the fLaC sample entry's 16-bit
 * rate field (96000 doesn't fit), and the fixture keeps ffmpeg's 48000. With 0,
 * Robolectric's pass-through codec hands 0 Hz to the AudioSink and play() throws.
 * A real FLAC decoder reads 96 kHz from the dfLa STREAMINFO, which both carry.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDashPlaybackTest {

    @get:Rule val tmp = TemporaryFolder()

    // Robolectric has no real codecs; this registers pass-through ones so the
    // audio renderer can consume FLAC samples.
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.forAllSupportedMimeTypes()

    private lateinit var player: ExoPlayer
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = copyFixture()
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = ExoPlayer.Builder(context, CapturingRenderersFactory(context))
            .setClock(FakeClock(/* isAutoAdvancing= */ true))
            .build()
    }

    @After
    fun tearDown() = player.release()

    @Test
    fun `local DASH folder prepares with the full duration`() {
        player.setMediaItem(localDashItem())
        player.prepare()
        run(player).untilState(Player.STATE_READY)

        assertTrue("duration ${player.duration}", player.duration in 5_800L..6_200L)
        assertTrue(player.isCurrentMediaItemSeekable)
    }

    @Test
    fun `local DASH folder seeks to the middle`() {
        player.setMediaItem(localDashItem())
        player.prepare()
        run(player).untilState(Player.STATE_READY)

        player.seekTo(4_000)
        run(player).untilPendingCommandsAreFullyHandled()
        run(player).untilState(Player.STATE_READY)
        assertTrue("position ${player.currentPosition}", player.currentPosition in 4_000L..4_300L)

        // Plays the rest of the song through the audio renderer.
        player.play()
        run(player).untilState(Player.STATE_ENDED)
    }

    @Test
    fun `concatenated fMP4 reports whether it is seekable`() {
        val joined = File(tmp.root, "audio.mp4")
        joined.outputStream().use { out ->
            (listOf("init.mp4") + SEGMENTS).forEach { name -> File(dir, name).inputStream().use { it.copyTo(out) } }
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(joined)))
        player.prepare()
        run(player).untilState(Player.STATE_READY)

        // Option 2 record: it prepares and knows its duration (from mehd), but
        // can't seek, because Tidal's segments carry no sidx for the extractor
        // to index.
        assertTrue("duration ${player.duration}", player.duration in 5_800L..6_200L)
        assertFalse(player.isCurrentMediaItemSeekable)
    }

    private fun localDashItem(): MediaItem = MediaItem.Builder()
        .setUri(Uri.fromFile(File(dir, "manifest.mpd")))
        .setMimeType(MimeTypes.APPLICATION_MPD)
        .build()

    /** Copies the fixture out of the classpath so segment URIs are real file:// paths. */
    private fun copyFixture(): File {
        val target = tmp.newFolder("local_flac")
        (listOf("manifest.mpd", "init.mp4") + SEGMENTS).forEach { name ->
            val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("dash/local_flac/$name")) {
                "missing fixture dash/local_flac/$name"
            }
            stream.use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private companion object {
        val SEGMENTS = listOf("seg_1.mp4", "seg_2.mp4", "seg_3.mp4")
    }
}
