package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParametricEqProcessorTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 2) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    @Test
    fun sixteenBitInputYieldsSixteenBitOutput() {
        // Regression: DefaultAudioSink's built-in SilenceSkipping/Sonic
        // processors run after ours and only accept 16-bit PCM — emitting
        // float here kills sink configuration (AUDIO_TRACK_INIT_FAILED).
        val processor = ParametricEqProcessor()
        val out = processor.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        assertEquals(44_100, out.sampleRate)
        assertEquals(2, out.channelCount)
    }

    @Test
    fun floatInputYieldsFloatOutput() {
        val processor = ParametricEqProcessor()
        val out = processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
    }

    @Test
    fun rejectsUnsupportedEncodings() {
        val processor = ParametricEqProcessor()
        assertThrows(UnhandledAudioFormatException::class.java) {
            processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test
    fun disabledStatePassesSixteenBitSamplesThrough() {
        val processor = ParametricEqProcessor()
        processor.configure(pcm16Format(channels = 1))
        processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        shortArrayOf(1000, -2000, 32767, -32768).forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = ShortArray(4) { output.short }
        assertEquals(listOf<Short>(1000, -2000, 32767, -32768), result.toList())
    }

    @Test
    fun preampAttenuatesSixteenBitSamples() {
        val processor = ParametricEqProcessor()
        // enabled + one flat filter so the active path runs; -6.0206 dB = x0.5
        processor.updateState(
            EqState(
                enabled = true,
                preampDb = -6.0206,
                filters = listOf(FilterSpec(FilterType.PEAKING, freqHz = 1_000.0, q = 1.0, gainDb = 0.0)),
            ),
        )
        processor.configure(pcm16Format(channels = 1))
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(20_000).putShort(-20_000)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val a = output.short.toInt()
        val b = output.short.toInt()
        assertTrue("expected ~10000, got $a", a in 9_500..10_500)
        assertTrue("expected ~-10000, got $b", b in -10_500..-9_500)
    }
}
