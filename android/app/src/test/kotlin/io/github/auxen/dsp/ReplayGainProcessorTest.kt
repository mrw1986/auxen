package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayGainProcessorTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 1) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    private fun floatFormat(sampleRate: Int = 44_100, channels: Int = 1) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT)

    private fun feedFloats(processor: ReplayGainProcessor, samples: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return FloatArray(samples.size) { output.float }
    }

    @Test
    fun sixteenBitInputYieldsFloatOutput() {
        val processor = ReplayGainProcessor()
        val out = processor.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
    }

    @Test
    fun rejectsUnsupportedEncodings() {
        val processor = ReplayGainProcessor()
        assertThrows(UnhandledAudioFormatException::class.java) {
            processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test
    fun disabledPassthroughExact() {
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = false))
        processor.setTrackGains(trackGainDb = -6.0, albumGainDb = -4.0)
        processor.configure(floatFormat())
        processor.flush()
        val samples = floatArrayOf(0.5f, -0.3f, 1.5f)
        val out = feedFloats(processor, samples)
        out.forEachIndexed { i, v -> assertEquals(samples[i], v, 0f) }
    }

    @Test
    fun trackModeUsesTrackGainWithPreamp() {
        // trackGain=-6.0, preamp=3.0 -> effective -3.0dB, linear ~= 0.7079457843841379
        // (verified in Python before writing this assertion).
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = true, albumMode = false, preampDb = 3.0, fallbackDb = 0.0))
        processor.setTrackGains(trackGainDb = -6.0, albumGainDb = -4.0)
        processor.configure(floatFormat())
        processor.flush()
        val out = feedFloats(processor, floatArrayOf(0.5f))
        assertEquals(0.35397289219206896, out[0].toDouble(), 1e-6)
    }

    @Test
    fun albumModeUsesAlbumGainNoPreamp() {
        // albumGain=-4.0, preamp=0.0 -> linear = 10^(-4/20) ~= 0.6309573444801932
        // (verified in Python).
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = true, albumMode = true, preampDb = 0.0, fallbackDb = 0.0))
        processor.setTrackGains(trackGainDb = -6.0, albumGainDb = -4.0)
        processor.configure(floatFormat())
        processor.flush()
        val out = feedFloats(processor, floatArrayOf(0.4f))
        assertEquals(0.2523829377920773, out[0].toDouble(), 1e-6)
    }

    @Test
    fun albumModeFallsBackToTrackGainWhenAlbumGainMissing() {
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = true, albumMode = true, preampDb = 0.0, fallbackDb = 0.0))
        processor.setTrackGains(trackGainDb = -6.0, albumGainDb = null)
        processor.configure(floatFormat())
        processor.flush()
        // Falls back to trackGain=-6.0, same as track mode with that gain and no preamp.
        val out = feedFloats(processor, floatArrayOf(1.0f))
        assertEquals(Math.pow(10.0, -6.0 / 20.0), out[0].toDouble(), 1e-6)
    }

    @Test
    fun trackModeFallsBackToAlbumGainWhenTrackGainMissing() {
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = true, albumMode = false, preampDb = 0.0, fallbackDb = 0.0))
        processor.setTrackGains(trackGainDb = null, albumGainDb = -4.0)
        processor.configure(floatFormat())
        processor.flush()
        val out = feedFloats(processor, floatArrayOf(1.0f))
        assertEquals(Math.pow(10.0, -4.0 / 20.0), out[0].toDouble(), 1e-6)
    }

    @Test
    fun neitherGainPresentUsesFallbackDbPlusPreamp() {
        // fallback=-2.0, preamp=1.0 -> effective -1.0dB, linear ~= 0.8912509381337456
        // (verified in Python).
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = true, albumMode = false, preampDb = 1.0, fallbackDb = -2.0))
        processor.setTrackGains(trackGainDb = null, albumGainDb = null)
        processor.configure(floatFormat())
        processor.flush()
        val out = feedFloats(processor, floatArrayOf(1.0f))
        assertEquals(0.8912509381337456, out[0].toDouble(), 1e-6)
    }

    @Test
    fun defaultStateHasNoTrackGainsSetIsSilentlyUnityAtZeroFallback() {
        // No setTrackGains call at all -- both null, default state has
        // fallbackDb=0.0 and preampDb=0.0, so gain is 10^(0/20) = 1.0 exactly.
        val processor = ReplayGainProcessor()
        processor.updateState(ReplayGainState(enabled = true))
        processor.configure(floatFormat())
        processor.flush()
        val out = feedFloats(processor, floatArrayOf(0.5f))
        assertEquals(0.5f, out[0], 0f)
    }
}
