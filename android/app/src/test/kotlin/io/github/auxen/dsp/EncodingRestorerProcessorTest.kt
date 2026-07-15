package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EncodingRestorerProcessorTest {

    @Test
    fun floatInputConvertsToSixteenBitWithClampAndRoundTrip() {
        val processor = EncodingRestorerProcessor()
        val out = processor.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_FLOAT))
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        assertEquals(44_100, out.sampleRate)
        assertEquals(1, out.channelCount)
        assertTrue("float input must make the restorer active", processor.isActive)
        processor.flush()

        // Normal-range samples round-trip via the same symmetric *32768 scale
        // ParametricEqProcessor used to apply itself; out-of-range samples
        // (chain headroom from an upstream boost) clamp here instead, since
        // this processor is now the one that owns the sink's 16-bit ceiling.
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        floatArrayOf(0.5f, -0.5f, 1.5f, -2.0f).forEach { input.putFloat(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = ShortArray(4) { output.short }
        assertEquals(listOf<Short>(16384, -16384, 32767, -32768), result.toList())
    }

    @Test
    fun sixteenBitInputIsInactivePassThrough() {
        // No conversion needed -- input is already the sink's required
        // encoding -- so this declares itself inactive per BaseAudioProcessor's
        // contract (configure() returns AudioFormat.NOT_SET whenever
        // onConfigure() does, verified by reading the real Media3 1.5.1
        // source before relying on this). A real AudioProcessingPipeline
        // skips queueInput/getOutput entirely for an inactive processor and
        // passes the upstream buffer straight through -- zero cost, not a
        // buffer copy.
        val processor = EncodingRestorerProcessor()
        val out = processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        assertEquals(AudioFormat.NOT_SET, out)
        assertFalse("16-bit input needs no restoration; must be inactive", processor.isActive)
    }
}
