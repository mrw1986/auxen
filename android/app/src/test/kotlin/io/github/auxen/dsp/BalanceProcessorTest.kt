package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BalanceProcessorTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 2) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    @Test
    fun sixteenBitInputYieldsFloatOutput() {
        val processor = BalanceProcessor()
        val out = processor.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
        assertEquals(44_100, out.sampleRate)
        assertEquals(2, out.channelCount)
    }

    @Test
    fun rejectsUnsupportedEncodings() {
        val processor = BalanceProcessor()
        assertThrows(UnhandledAudioFormatException::class.java) {
            processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test
    fun disabledStatePassesSixteenBitSamplesThroughExactly() {
        val processor = BalanceProcessor()
        // Default BalanceState() is disabled.
        processor.configure(pcm16Format())
        processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        shortArrayOf(10_000, -20_000, 32_767, -32_768).forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = FloatArray(4) { output.float }
        val expected = floatArrayOf(10_000 / 32_768f, -20_000 / 32_768f, 32_767 / 32_768f, -32_768 / 32_768f)
        result.forEachIndexed { i, v -> assertEquals(expected[i], v, 1e-6f) }
    }

    @Test
    fun fullLeftZeroesRightChannelAndLeavesLeftUntouched() {
        val processor = BalanceProcessor()
        processor.updateState(BalanceState(enabled = true, balance = -1f))
        processor.configure(pcm16Format())
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(10_000) // left
        input.putShort(20_000) // right
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val left = output.float
        val right = output.float
        assertEquals(10_000 / 32_768f, left, 1e-6f)
        assertEquals(0f, right, 0f) // exact: rightGain = min(1f, 1f + -1f) = 0f
    }

    @Test
    fun halfRightAttenuatesLeftByHalfAndLeavesRightAtUnityCeiling() {
        val processor = BalanceProcessor()
        processor.updateState(BalanceState(enabled = true, balance = 0.5f))
        processor.configure(pcm16Format())
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(10_000) // left
        input.putShort(20_000) // right
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val left = output.float
        val right = output.float
        // leftGain = min(1f, 1f - 0.5f) = 0.5f -- exact, 0.5 is a power of two
        assertEquals((10_000 / 32_768f) * 0.5f, left, 0f)
        // rightGain = min(1f, 1f + 0.5f) = 1f -- capped, not boosted
        assertEquals(20_000 / 32_768f, right, 1e-6f)
    }

    @Test
    fun monoInputUnaffectedByDesignEvenAtFullBalance() {
        val processor = BalanceProcessor()
        processor.updateState(BalanceState(enabled = true, balance = -1f))
        processor.configure(pcm16Format(channels = 1))
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        shortArrayOf(10_000, -20_000).forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = FloatArray(2) { output.float }
        val expected = floatArrayOf(10_000 / 32_768f, -20_000 / 32_768f)
        result.forEachIndexed { i, v -> assertEquals(expected[i], v, 1e-6f) }
    }

    @Test
    fun channelsBeyondStereoPassThroughUntouched() {
        val processor = BalanceProcessor()
        processor.updateState(BalanceState(enabled = true, balance = -1f))
        processor.configure(pcm16Format(channels = 3))
        processor.flush()
        val input = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        input.putShort(10_000) // left, channel 0
        input.putShort(20_000) // right, channel 1
        input.putShort(30_000) // channel 2, untouched
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val left = output.float
        val right = output.float
        val third = output.float
        assertEquals(10_000 / 32_768f, left, 1e-6f)
        assertEquals(0f, right, 0f)
        assertEquals(30_000 / 32_768f, third, 1e-6f)
    }
}
