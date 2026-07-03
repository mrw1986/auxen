package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Parametric EQ as a Media3 [androidx.media3.common.audio.AudioProcessor].
 *
 * This is the "Wavelet built in" part of Auxen: instead of attaching an
 * effect to the device's global audio session (Wavelet's approach, which is
 * at the mercy of the OEM's effect implementation), the EQ runs inside
 * ExoPlayer's audio pipeline:
 *
 *  - all processing happens in 32-bit float regardless of source bit depth
 *    (16-bit PCM input is promoted to float and *stays* float to the sink,
 *    so there is no intermediate re-quantisation);
 *  - filters are double-precision biquads, stable at low frequencies;
 *  - the preamp is applied in the same pass, so headroom management is exact.
 *
 * Settings changes are applied atomically between buffers via [updateState];
 * filters are only rebuilt when the state generation or audio format changes.
 */
@UnstableApi
class ParametricEqProcessor : BaseAudioProcessor() {

    @Volatile
    private var state: EqState = EqState()

    @Volatile
    private var stateGeneration: Long = 0

    private var builtGeneration: Long = -1
    private var filters: List<Biquad> = emptyList()
    private var preampGain: Float = 1f
    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var inputEncoding: Int = C.ENCODING_INVALID

    /** Replace the active EQ state; takes effect on the next audio buffer. */
    fun updateState(newState: EqState) {
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
        // Always emit float PCM so the sink never re-quantises after the EQ.
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        rebuildFiltersIfNeeded()

        val bytesPerSample = if (inputEncoding == C.ENCODING_PCM_16BIT) 2 else 4
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * 4)

        val active = state.enabled && filters.isNotEmpty()
        var i = 0
        while (i < sampleCount) {
            val channel = i % channelCount
            var sample = if (inputEncoding == C.ENCODING_PCM_16BIT) {
                inputBuffer.short / 32768f
            } else {
                inputBuffer.float
            }
            if (active) {
                sample *= preampGain
                for (filter in filters) {
                    sample = filter.processSample(channel, sample)
                }
                // Float sinks clip anything beyond full scale at the HAL; do it
                // here deterministically instead.
                if (sample > 1f) sample = 1f else if (sample < -1f) sample = -1f
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
        filters = emptyList()
    }

    private fun rebuildFiltersIfNeeded() {
        val generation = stateGeneration
        if (generation == builtGeneration || sampleRate <= 0) return
        val s = state
        preampGain = 10.0.pow(s.preampDb / 20.0).toFloat()
        // Skip filters at/above Nyquist — an AutoEq 10 kHz shelf is fine at
        // 44.1 kHz, but a 16 kHz graphic band is not representable at 24 kHz.
        filters = s.filters.filter { it.freqHz < sampleRate / 2.0 }.map { spec ->
            when (spec.type) {
                FilterType.PEAKING -> Biquad.peaking(sampleRate, spec.freqHz, spec.q, spec.gainDb, channelCount)
                FilterType.LOW_SHELF -> Biquad.lowShelf(sampleRate, spec.freqHz, spec.q, spec.gainDb, channelCount)
                FilterType.HIGH_SHELF -> Biquad.highShelf(sampleRate, spec.freqHz, spec.q, spec.gainDb, channelCount)
            }
        }
        builtGeneration = generation
    }
}
