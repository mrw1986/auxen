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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class BassBoostProcessorTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 1) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    @Test
    fun sixteenBitInputYieldsFloatOutput() {
        val processor = BassBoostProcessor()
        val out = processor.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
        assertEquals(44_100, out.sampleRate)
        assertEquals(1, out.channelCount)
    }

    @Test
    fun floatInputYieldsFloatOutput() {
        val processor = BassBoostProcessor()
        val out = processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
    }

    @Test
    fun rejectsUnsupportedEncodings() {
        val processor = BassBoostProcessor()
        assertThrows(UnhandledAudioFormatException::class.java) {
            processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test
    fun disabledStatePassesSixteenBitSamplesThroughExactly() {
        val processor = BassBoostProcessor()
        // Default BassBoostState() is disabled.
        processor.configure(pcm16Format())
        processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        shortArrayOf(1000, -2000, 32767, -32768).forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = FloatArray(4) { output.float }
        val expected = floatArrayOf(1000 / 32768f, -2000 / 32768f, 32767 / 32768f, -32768 / 32768f)
        result.forEachIndexed { i, v -> assertEquals(expected[i], v, 1e-6f) }
    }

    /**
     * Feeds continuous sine tones directly as float samples (not quantized
     * 16-bit) to keep the RMS measurement clean, matching a numeric
     * pre-verification of the exact biquad response run in Python before this
     * test was written: at freqHz=80, gainDb=6, q=0.707, sampleRate=44100, a
     * 50 Hz tone's steady-state RMS gain is ~5.16 dB (well inside 4.5..6.0)
     * and a 5 kHz tone's is ~0.0 dB (well inside the <0.5 dB budget) -- stable
     * across several buffer/settle-window sizes tried.
     */
    private fun sineToneRmsBoostDb(processor: BassBoostProcessor, freqHz: Double, sampleCount: Int, settle: Int): Double {
        processor.configure(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()
        val samples = DoubleArray(sampleCount) { n -> sin(2.0 * Math.PI * freqHz * n / SAMPLE_RATE) }
        val input = ByteBuffer.allocateDirect(sampleCount * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it.toFloat()) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val out = FloatArray(sampleCount) { output.float }

        fun rms(values: DoubleArray, from: Int) =
            sqrt(values.drop(from).sumOf { it * it } / (values.size - from))
        fun rmsF(values: FloatArray, from: Int) =
            sqrt(values.drop(from).sumOf { (it.toDouble()) * it } / (values.size - from))

        val rmsIn = rms(samples, settle)
        val rmsOut = rmsF(out, settle)
        return 20.0 * log10(rmsOut / rmsIn)
    }

    @Test
    fun enabledBoostsLowFrequencyRmsWhileLeavingHighFrequencyEssentiallyUnchanged() {
        val bassProcessor = BassBoostProcessor()
        bassProcessor.updateState(BassBoostState(enabled = true, freqHz = 80.0, gainDb = 6.0))
        val lowBoostDb = sineToneRmsBoostDb(bassProcessor, freqHz = 50.0, sampleCount = 8_820, settle = 4_000)
        assertTrue("expected ~5.16 dB boost at 50 Hz, got $lowBoostDb", lowBoostDb in 4.5..6.0)

        val highProcessor = BassBoostProcessor()
        highProcessor.updateState(BassBoostState(enabled = true, freqHz = 80.0, gainDb = 6.0))
        val highBoostDb = sineToneRmsBoostDb(highProcessor, freqHz = 5_000.0, sampleCount = 8_820, settle = 4_000)
        assertTrue("expected ~0 dB change at 5 kHz, got $highBoostDb", kotlin.math.abs(highBoostDb) < 0.5)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
