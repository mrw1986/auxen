package io.github.auxen.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepPauseTransitionTest {

    private val nextSong = listOf(
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
    )

    @Test fun `an armed finish-this-track pause fires when the next song starts`() {
        nextSong.forEach { assertTrue("reason $it", sleepPauseFiresOn(it, armed = true)) }
    }

    @Test fun `a swap of the playing item never fires it`() {
        assertFalse(sleepPauseFiresOn(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, armed = true))
    }

    @Test fun `nothing fires when unarmed`() {
        (nextSong + Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            .forEach { assertFalse("reason $it", sleepPauseFiresOn(it, armed = false)) }
    }
}
