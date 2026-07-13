package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Stereo balance: pans between channel 0 (left) and channel 1 (right), as a
 * Media3 [androidx.media3.common.audio.AudioProcessor].
 *
 * One link in the chain (`ReplayGain -> ParametricEq -> BassBoost -> Balance
 * -> Limiter -> EncodingRestorer`, see [ParametricEqProcessor]): runs
 * entirely in float. The applied gains are always <= 1 by construction (see
 * below), so this processor never needs to clamp. 16-bit input is promoted
 * (`/32768f`) for standalone safety; float input passes through unchanged
 * aside from the per-channel gain.
 *
 * Gains: `left = min(1f, 1f - balance)`, `right = min(1f, 1f + balance)`,
 * where `balance` is first clamped to its documented -1..1 range -- an
 * out-of-spec caller value beyond that would otherwise send one gain
 * negative, phase-inverting a channel instead of silencing it. Moving toward
 * one side attenuates the OTHER side rather than boosting the favored one,
 * so balance never adds headroom pressure to the chain.
 * Channels beyond index 1 (anything past stereo) pass through untouched --
 * there is no well-defined left/right beyond a stereo pair. Mono (1-channel)
 * input is always passed through untouched, by design: with only one channel
 * there is nothing to pan between.
 */
@UnstableApi
class BalanceProcessor : BaseAudioProcessor() {

    @Volatile
    private var state: BalanceState = BalanceState()

    private var channelCount: Int = 0
    private var inputEncoding: Int = C.ENCODING_INVALID

    /** Replace the active balance state; takes effect on the next audio buffer. */
    fun updateState(newState: BalanceState) {
        state = newState
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        inputEncoding = encoding
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val s = state
        // Clamp first: an out-of-spec balance beyond +/-1 (BalanceState.balance
        // is documented -1..1) would otherwise make min(1f, 1f -/+ balance) go
        // negative, phase-inverting a channel at full volume instead of
        // silencing it.
        val balance = s.balance.coerceIn(-1f, 1f)
        val active = s.enabled && balance != 0f && channelCount >= 2
        val leftGain = min(1f, 1f - balance)
        val rightGain = min(1f, 1f + balance)

        val is16Bit = inputEncoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * 4)

        var i = 0
        while (i < sampleCount) {
            val channel = i % channelCount
            var sample = if (is16Bit) inputBuffer.short / 32768f else inputBuffer.float
            if (active) {
                sample *= when (channel) {
                    0 -> leftGain
                    1 -> rightGain
                    else -> 1f
                }
            }
            output.putFloat(sample)
            i++
        }
        output.flip()
    }
}
