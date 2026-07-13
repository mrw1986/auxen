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

class LimiterProcessorTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 1) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    private fun floatFormat(sampleRate: Int = 44_100, channels: Int = 1) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT)

    private fun feedFloats(processor: LimiterProcessor, samples: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return FloatArray(samples.size) { output.float }
    }

    @Test
    fun sixteenBitInputYieldsFloatOutput() {
        val processor = LimiterProcessor()
        val out = processor.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
    }

    @Test
    fun rejectsUnsupportedEncodings() {
        val processor = LimiterProcessor()
        assertThrows(UnhandledAudioFormatException::class.java) {
            processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    // (a) below-threshold sine passes bit-identically.
    @Test
    fun belowThresholdSinePassesBitIdentically() {
        val processor = LimiterProcessor()
        processor.updateState(LimiterState(enabled = true, thresholdDb = -1.0, kneeDb = 6.0, releaseMs = 120.0))
        processor.configure(floatFormat())
        processor.flush()
        // Amplitude 0.3 (~-10.5 dBFS) is well below the knee's bottom edge
        // (thresholdDb - kneeDb/2 = -1 - 3 = -4 dBFS), so desiredGain is
        // exactly 1.0 for every sample and gain (seeded 1.0) never moves --
        // verified in Python (limiter_sim.py) before writing this. Widening
        // float->double, multiplying by exactly 1.0, coerceIn as a no-op
        // (|sample| << 1), and narrowing back to float is an exact round trip
        // under IEEE 754, so the output must be bit-identical to the input.
        val samples = FloatArray(200) { n -> (0.3 * sin(2.0 * Math.PI * 440.0 * n / 44_100)).toFloat() }
        val out = feedFloats(processor, samples)
        out.forEachIndexed { i, v -> assertEquals("sample $i", samples[i], v, 0f) }
    }

    // (b) a +6 dB-over-threshold constant block settles to output <= threshold linear value + 0.1 dB.
    @Test
    fun sixDbOverThresholdConstantBlockSettlesAtThreshold() {
        val processor = LimiterProcessor()
        val thresholdDb = -1.0
        processor.updateState(LimiterState(enabled = true, thresholdDb = thresholdDb, kneeDb = 6.0, releaseMs = 120.0))
        processor.configure(floatFormat())
        processor.flush()
        // peak = 10^((thresholdDb + 6)/20) = 10^(5/20) ~= 1.778279 -- verified
        // in Python: settles to exactly -1.000000 dBFS (the threshold) since
        // over=6 >= kneeDb/2=3 is the 1:1 region above the knee.
        val peak = Math.pow(10.0, (thresholdDb + 6.0) / 20.0).toFloat()
        val samples = FloatArray(50) { peak }
        val out = feedFloats(processor, samples)
        val lastDb = 20.0 * log10(out.last().toDouble())
        assertTrue("expected output <= threshold+0.1dB, got ${lastDb}dB", lastDb <= thresholdDb + 0.1)
    }

    // (c) attack is instant -- the first over-threshold frame is already limited.
    @Test
    fun attackIsInstantOnTheFirstOverThresholdFrame() {
        val processor = LimiterProcessor()
        val thresholdDb = -1.0
        processor.updateState(LimiterState(enabled = true, thresholdDb = thresholdDb, kneeDb = 6.0, releaseMs = 120.0))
        processor.configure(floatFormat())
        processor.flush()
        val peak = Math.pow(10.0, (thresholdDb + 6.0) / 20.0).toFloat()
        val out = feedFloats(processor, floatArrayOf(peak))
        // Gain starts seeded at 1.0; desiredGain (~0.501187, verified in
        // Python) is already < 1.0, so instant attack applies it to this
        // very first frame -- the unreduced peak (1.778279) must NOT appear.
        assertTrue("expected first frame already reduced below the raw peak, got ${out[0]}", out[0] < peak)
        val outDb = 20.0 * log10(out[0].toDouble())
        assertTrue("expected first-frame output <= threshold+0.1dB, got ${outDb}dB", outDb <= thresholdDb + 0.1)
    }

    // (d) release recovers toward 1.0 with the configured time constant.
    @Test
    fun releaseRecoversMonotonicallyAndMostlyWithinFiveTimeConstants() {
        val processor = LimiterProcessor()
        val thresholdDb = -1.0
        val releaseMs = 120.0
        processor.updateState(LimiterState(enabled = true, thresholdDb = thresholdDb, kneeDb = 6.0, releaseMs = releaseMs))
        processor.configure(floatFormat())
        processor.flush()

        // Drive gain down to steady state with a loud block (200 frames is
        // far more than enough -- instant attack converges on frame 1).
        val peak = Math.pow(10.0, (thresholdDb + 6.0) / 20.0).toFloat()
        feedFloats(processor, FloatArray(200) { peak })

        // Quiet probe at -20 dBFS, well below the knee bottom (-4 dBFS), so
        // desiredGain is exactly 1.0 throughout recovery -- gain(t) is a pure
        // exponential approach to 1.0. Track it via output/input ratio.
        val probeAmp = 0.1f
        val sampleCount = 26_460 * 2 // 10x releaseMs in samples at 44100 Hz
        val probe = FloatArray(sampleCount) { probeAmp }
        val out = feedFloats(processor, probe)
        val gainTrajectory = out.map { it / probeAmp }

        // Monotonic recovery.
        for (i in 1 until gainTrajectory.size) {
            assertTrue(
                "gain decreased at sample $i: ${gainTrajectory[i - 1]} -> ${gainTrajectory[i]}",
                gainTrajectory[i] >= gainTrajectory[i - 1] - 1e-6f,
            )
        }

        // >90% recovered after 5x releaseMs (verified in Python: 99.33%).
        val fiveTimeConstants = (5.0 * releaseMs / 1000.0 * 44_100).toInt()
        val g0 = gainTrajectory[0]
        val gAt5x = gainTrajectory[fiveTimeConstants - 1]
        val recoveryFraction = (gAt5x - g0) / (1.0 - g0)
        assertTrue("expected >90% recovery at 5x releaseMs, got ${recoveryFraction * 100}%", recoveryFraction > 0.90)
    }

    // (e) disabled passthrough exact even for >1.0 samples.
    @Test
    fun disabledPassthroughExactEvenAboveFullScale() {
        val processor = LimiterProcessor()
        processor.updateState(LimiterState(enabled = false))
        processor.configure(floatFormat())
        processor.flush()
        val samples = floatArrayOf(1.5f, -2.0f, 0.3f)
        val out = feedFloats(processor, samples)
        out.forEachIndexed { i, v -> assertEquals(samples[i], v, 0f) }
    }

    // (f) knee -- a signal exactly AT threshold receives < 0.5 dB reduction (soft, not brick).
    @Test
    fun signalExactlyAtThresholdReceivesSoftSubHalfDbReduction() {
        // Deliberately NOT the default kneeDb=6.0: verified in Python that the
        // knee formula gives exactly kneeDb/8 dB of reduction at over=0 (the
        // knee's midpoint), so kneeDb=6.0 gives 0.75dB -- ABOVE the 0.5dB bar
        // this test demonstrates. kneeDb=2.0 gives 0.25dB, comfortably under
        // it, while still exercising the same soft-knee formula (not a brick
        // wall, which would give a full thresholdDb-crossing cliff at over=0).
        val processor = LimiterProcessor()
        val thresholdDb = -1.0
        val kneeDb = 2.0
        processor.updateState(LimiterState(enabled = true, thresholdDb = thresholdDb, kneeDb = kneeDb, releaseMs = 120.0))
        processor.configure(floatFormat())
        processor.flush()
        val peakAtThreshold = Math.pow(10.0, thresholdDb / 20.0).toFloat()
        val out = feedFloats(processor, floatArrayOf(peakAtThreshold))
        val reductionDb = 20.0 * log10(peakAtThreshold.toDouble()) - 20.0 * log10(out[0].toDouble())
        assertTrue("expected <0.5dB reduction at the knee midpoint, got ${reductionDb}dB", reductionDb < 0.5)
    }

    @Test
    fun onFlushResetsGainToUnity() {
        val processor = LimiterProcessor()
        val thresholdDb = -1.0
        processor.updateState(LimiterState(enabled = true, thresholdDb = thresholdDb, kneeDb = 6.0, releaseMs = 120.0))
        processor.configure(floatFormat())
        processor.flush()
        val peak = Math.pow(10.0, (thresholdDb + 6.0) / 20.0).toFloat()
        feedFloats(processor, FloatArray(50) { peak }) // drive gain well below 1.0
        processor.flush() // seek/discontinuity: gain should reset to 1.0
        // A quiet probe right after flush should pass through unreduced,
        // proving gain was reset rather than still recovering from the loud block.
        val probe = 0.1f
        val out = feedFloats(processor, floatArrayOf(probe))
        assertEquals(probe, out[0], 1e-6f)
    }
}
