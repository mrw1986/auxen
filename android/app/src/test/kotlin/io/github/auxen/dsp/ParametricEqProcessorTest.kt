package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParametricEqProcessorTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 2) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    @Test
    fun sixteenBitInputYieldsFloatForChainHeadroom() {
        // Supersedes sixteenBitInputYieldsSixteenBitOutput (DSP-a Task 2): the
        // sink-compat invariant -- DefaultAudioSink's built-in
        // SilenceSkipping/Sonic processors run after ours and only accept
        // 16-bit PCM -- moved to the CHAIN level (see
        // ProcessorChainTest.chainRestoresSixteenBitForTheSink), where a final
        // EncodingRestorerProcessor converts back to 16-bit. This processor now
        // always emits float, unclamped, so downstream processors (bass boost,
        // balance, limiter) have real headroom to work with instead of each
        // clamping independently. This is an intentional, documented revisit
        // of the old per-processor mirroring behavior, not a regression.
        val processor = ParametricEqProcessor()
        val out = processor.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
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
        val result = FloatArray(4) { output.float }
        // Exact /32768f promotions -- disabled EQ still converts encoding
        // (that conversion is unconditional now), it just skips filtering.
        val expected = floatArrayOf(1000 / 32768f, -2000 / 32768f, 32767 / 32768f, -32768 / 32768f)
        result.forEachIndexed { i, v -> assertEquals(expected[i], v, 1e-6f) }
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
        val a = output.float
        val b = output.float
        // ~20000/32768 * 0.5 = ~0.3052
        assertTrue("expected ~0.3052, got $a", a in 0.29f..0.32f)
        assertTrue("expected ~-0.3052, got $b", b in -0.32f..-0.29f)
    }

    @Test
    fun boostBeyondFullScaleIsNotClampedMidChain() {
        // Chain-headroom proof: a big low-shelf boost on a full-scale 16-bit
        // input must be able to exceed +/-1.0f in the float output -- the old
        // per-processor clamp block is gone, so the limiter/restorer further
        // down the chain (not this processor) own the ceiling.
        val processor = ParametricEqProcessor()
        processor.updateState(
            EqState(
                enabled = true,
                filters = listOf(FilterSpec(FilterType.LOW_SHELF, freqHz = 80.0, q = 1.0, gainDb = 24.0)),
            ),
        )
        processor.configure(pcm16Format(channels = 1))
        processor.flush()
        // Three repeated full-scale samples let the shelf's boost ring up
        // past its transient onset (verified numerically before writing this:
        // sample index 2 clears 1.0f with a comfortable margin at these
        // parameters, well outside float32 precision noise).
        val input = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        repeat(3) { input.putShort(32767) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val samples = FloatArray(3) { output.float }
        assertTrue(
            "Expected at least one sample to exceed 1.0f (chain headroom, no mid-chain clamp): $samples",
            samples.any { it > 1.0f },
        )
    }

    @Test
    fun filterWithNonPositiveQIsSkippedNotAppliedAsNaN() {
        // Q=0 makes Biquad.peaking's alpha = sin(w0)/(2*0) divide by zero,
        // producing Infinity; that then collides into an Infinity/Infinity
        // division inside the coefficient formulas, yielding NaN
        // coefficients that would corrupt every sample forever (the biquad's
        // z1/z2 state never recovers from a NaN once it's in). Verified in
        // Python that Q=0 alone (freq valid, nonzero gain) produces NaN
        // b0/b2/a2 coefficients and a NaN first output sample, before writing
        // this assertion (final-review fix round, Important #1). An imported
        // AutoEq profile with a "Q 0" line would otherwise leave this NaN
        // state persisted across restarts as a "successful" import.
        val processor = ParametricEqProcessor()
        processor.updateState(
            EqState(
                enabled = true,
                filters = listOf(FilterSpec(FilterType.PEAKING, freqHz = 1_000.0, q = 0.0, gainDb = 6.0)),
            ),
        )
        processor.configure(pcm16Format(channels = 1))
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(10_000).putShort(-10_000)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val a = output.float
        val b = output.float
        assertFalse("expected non-NaN output, got $a", a.isNaN())
        assertFalse("expected non-NaN output, got $b", b.isNaN())
        // The invalid filter is skipped entirely, not just "not NaN" --
        // output equals the plain /32768f promotion, same as no filters at all.
        assertEquals(10_000 / 32768f, a, 1e-6f)
        assertEquals(-10_000 / 32768f, b, 1e-6f)
    }
}
