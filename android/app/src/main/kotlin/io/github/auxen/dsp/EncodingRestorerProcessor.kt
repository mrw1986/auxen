package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Converts float PCM back to 16-bit at the tail of Auxen's processor chain
 * (`ReplayGain → ParametricEq → BassBoost → Balance → Limiter →
 * EncodingRestorer`, DSP-a Task 2) so [androidx.media3.exoplayer.audio.DefaultAudioSink]'s
 * own built-in processors (silence skipping, speed/pitch), which run *after*
 * any user processor and only accept [C.ENCODING_PCM_16BIT], keep working.
 *
 * Every processor ahead of this one emits unclamped float — a mid-chain boost
 * (e.g. the EQ's low shelf) may legitimately exceed +/-1.0f, since the whole
 * point of running the chain in float is giving the limiter real headroom to
 * work with. This processor is where that ceiling finally gets enforced: the
 * conversion is a plain `*32768` scale with `coerceIn(-32768, 32767)`, no
 * dithering — the same simplification `ParametricEqProcessor` used to apply
 * itself before this task moved the sink-compat contract to the chain level.
 *
 * ### Pure plumbing, not an effect
 * No state, no enabled/disabled toggle, no persisted settings — it always
 * does exactly one thing to float input. For 16-bit input (nothing to
 * restore — already the sink's required encoding) it declares itself
 * inactive by returning [AudioFormat.NOT_SET] from [onConfigure], per
 * [BaseAudioProcessor]'s contract (`configure()` mirrors `onConfigure()`'s
 * result through `isActive()`, verified by reading the real Media3 1.5.1
 * source rather than assumed): a real `AudioProcessingPipeline` skips
 * `queueInput`/`getOutput` entirely for an inactive processor and passes the
 * upstream buffer straight through, at zero cost — not a buffer copy.
 *
 * Always the LAST processor in the chain.
 */
@UnstableApi
class EncodingRestorerProcessor : BaseAudioProcessor() {

    private var isFloatInput: Boolean = false
    private var active: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        isFloatInput = encoding == C.ENCODING_PCM_FLOAT
        active = isFloatInput
        if (!active) return AudioFormat.NOT_SET
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Only reached with float input: 16-bit input made this inactive, so
        // a real pipeline never calls queueInput on it (see class KDoc).
        val sampleCount = inputBuffer.remaining() / 4
        val output = replaceOutputBuffer(sampleCount * 2)
        var i = 0
        while (i < sampleCount) {
            val sample = inputBuffer.float
            // Symmetric with the /32768f promotions upstream; coerceIn is the
            // ceiling this task moved here from ParametricEqProcessor.
            output.putShort((sample * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            i++
        }
        output.flip()
    }
}
