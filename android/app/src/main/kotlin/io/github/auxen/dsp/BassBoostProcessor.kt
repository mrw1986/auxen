package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Bass boost: a single low-shelf lift, as a Media3
 * [androidx.media3.common.audio.AudioProcessor].
 *
 * One link in the chain (`ReplayGain -> ParametricEq -> BassBoost -> Balance
 * -> Limiter -> EncodingRestorer`, see [ParametricEqProcessor]): runs
 * entirely in float, unclamped -- the limiter and `EncodingRestorerProcessor`
 * further down the chain own the ceiling, not this processor. 16-bit input is
 * promoted (`/32768f`) for standalone safety (the chain normally hands this
 * processor float already); float input passes through the same filtering,
 * also unclamped.
 *
 * Filter Q is fixed at 0.707 -- only [BassBoostState.freqHz] and
 * [BassBoostState.gainDb] are user-facing. The filter is rebuilt only when
 * the state generation or audio format changes, mirroring
 * [ParametricEqProcessor]'s `rebuildFiltersIfNeeded`.
 */
@UnstableApi
class BassBoostProcessor : BaseAudioProcessor() {

    @Volatile
    private var state: BassBoostState = BassBoostState()

    @Volatile
    private var stateGeneration: Long = 0

    private var builtGeneration: Long = -1
    private var filter: Biquad? = null
    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var inputEncoding: Int = C.ENCODING_INVALID

    /** Replace the active bass-boost state; takes effect on the next audio buffer. */
    fun updateState(newState: BassBoostState) {
        state = newState
        stateGeneration++
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        inputEncoding = encoding
        builtGeneration = -1 // force filter rebuild for the new format
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        rebuildFilterIfNeeded()

        val is16Bit = inputEncoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * 4)

        val f = filter
        val active = state.enabled && f != null
        var i = 0
        while (i < sampleCount) {
            val channel = i % channelCount
            var sample = if (is16Bit) inputBuffer.short / 32768f else inputBuffer.float
            if (active) {
                sample = f!!.processSample(channel, sample)
            }
            output.putFloat(sample)
            i++
        }
        output.flip()
    }

    override fun onFlush() {
        // Seeks/discontinuities: drop filter state so stale samples don't ring.
        builtGeneration = -1
    }

    override fun onReset() {
        builtGeneration = -1
        filter = null
    }

    private fun rebuildFilterIfNeeded() {
        val generation = stateGeneration
        if (generation == builtGeneration || sampleRate <= 0) return
        val s = state
        // Skip below Nyquist the same way ParametricEqProcessor does.
        filter = if (s.freqHz < sampleRate / 2.0) {
            Biquad.lowShelf(sampleRate, s.freqHz, BASS_BOOST_Q, s.gainDb, channelCount)
        } else {
            null
        }
        builtGeneration = generation
    }

    private companion object {
        const val BASS_BOOST_Q = 0.707
    }
}
