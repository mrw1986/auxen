package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chain-level invariant for DSP-a Task 2's float-through headroom design:
 * `ParametricEqProcessor` now always emits unclamped float, so the sink-compat
 * 16-bit contract (`DefaultAudioSink`'s built-in processors only accept
 * [C.ENCODING_PCM_16BIT]) that used to live inside the EQ itself now has to
 * hold at the CHAIN level instead, enforced by `EncodingRestorerProcessor` at
 * the tail. These tests wire only EQ → Restorer manually (the full
 * `ReplayGain → EQ → BassBoost → Balance → Limiter → Restorer` chain and its
 * real `AudioProcessingPipeline` wiring are later DSP-a tasks) — each
 * processor's output format is fed directly into the next's `configure`, and
 * a buffer is pushed through both `queueInput`/`output` hops by hand.
 */
class ProcessorChainTest {

    private fun pcm16Format(sampleRate: Int = 44_100, channels: Int = 1) =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    @Test
    fun chainRestoresSixteenBitForTheSink() {
        // Both EQ's own filtering and the restorer are pure plumbing here --
        // EQ's default state is disabled, and the restorer has no
        // enabled/disabled toggle at all -- so this proves the round-trip
        // through two encoding conversions (16-bit -> float -> 16-bit)
        // preserves every sample value exactly (verified bit-exact for
        // integer 16-bit values through float32 before writing this
        // assertion) and lands back on 16-bit for the sink, with nothing in
        // between actually altering the audio.
        val eq = ParametricEqProcessor()
        val restorer = EncodingRestorerProcessor()

        val eqOutFormat = eq.configure(pcm16Format())
        assertEquals(C.ENCODING_PCM_FLOAT, eqOutFormat.encoding)
        val restorerOutFormat = restorer.configure(eqOutFormat)
        assertEquals(C.ENCODING_PCM_16BIT, restorerOutFormat.encoding)
        eq.flush()
        restorer.flush()

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        shortArrayOf(1000, -2000, 32767, -32768).forEach { input.putShort(it) }
        input.flip()

        eq.queueInput(input)
        val eqOutput = eq.output.order(ByteOrder.nativeOrder())
        restorer.queueInput(eqOutput)
        val finalOutput = restorer.output.order(ByteOrder.nativeOrder())

        val result = ShortArray(4) { finalOutput.short }
        assertEquals(listOf<Short>(1000, -2000, 32767, -32768), result.toList())
    }

    @Test
    fun boostedSampleClampsOnlyAtTheRestorer() {
        // The EQ stage itself must NOT clamp (that's the whole point of
        // Task 2's headroom change) -- the restorer, and only the restorer,
        // enforces the sink's 16-bit ceiling.
        val eq = ParametricEqProcessor()
        val restorer = EncodingRestorerProcessor()
        eq.updateState(
            EqState(
                enabled = true,
                filters = listOf(FilterSpec(FilterType.LOW_SHELF, freqHz = 80.0, q = 1.0, gainDb = 24.0)),
            ),
        )

        val eqOutFormat = eq.configure(pcm16Format())
        restorer.configure(eqOutFormat)
        eq.flush()
        restorer.flush()

        val input = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        repeat(3) { input.putShort(32767) }
        input.flip()

        eq.queueInput(input)
        val eqOutput = eq.output.order(ByteOrder.nativeOrder())
        // Peek at EQ's own float output with absolute gets (doesn't move the
        // buffer's position) so the same buffer can still be handed to the
        // restorer afterward, unconsumed.
        val eqSamples = FloatArray(3) { eqOutput.getFloat(it * 4) }
        assertTrue(
            "EQ itself must not clamp mid-chain: $eqSamples",
            eqSamples.any { it > 1.0f },
        )

        restorer.queueInput(eqOutput)
        val finalOutput = restorer.output.order(ByteOrder.nativeOrder())
        val restoredSamples = ShortArray(3) { finalOutput.short }
        assertTrue(
            "Restorer must clamp the boosted sample to the max short: $restoredSamples",
            restoredSamples.any { it == Short.MAX_VALUE },
        )
    }

    @Test
    fun floatSourceBypassesEqEncodingChangeAndRestorerStaysInactiveForSixteenBit() {
        // Float-source edge case: EQ receiving float input passes the same
        // encoding through unchanged (it already did before this task; this
        // reaffirms it still holds now that 16-bit input ALSO yields float).
        val eq = ParametricEqProcessor()
        val floatFormat = AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
        val eqOutFormat = eq.configure(floatFormat)
        assertEquals(C.ENCODING_PCM_FLOAT, eqOutFormat.encoding)

        // Restorer reaffirmed at the chain level (see its own isolated test
        // in EncodingRestorerProcessorTest): direct 16-bit input needs no
        // restoration, so it's inactive -- zero-cost pass-through, not
        // exercised by queueInput/output at all in a real pipeline.
        val restorer = EncodingRestorerProcessor()
        val restorerOutFormat = restorer.configure(pcm16Format())
        assertEquals(AudioFormat.NOT_SET, restorerOutFormat)
        assertFalse(restorer.isActive)
    }
}
