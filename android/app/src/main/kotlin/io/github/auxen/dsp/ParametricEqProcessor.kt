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
 *  - all processing happens in 32-bit float with double-precision biquad
 *    state regardless of source bit depth (16-bit PCM is read up to float,
 *    filtered, then written back as 16-bit);
 *  - filters are double-precision biquads, stable at low frequencies;
 *  - the preamp is applied in the same pass, so headroom management is exact;
 *  - samples are clamped to full scale before any re-quantisation.
 *
 * ### Output encoding mirrors input
 * [androidx.media3.exoplayer.audio.DefaultAudioSink] appends its own built-in
 * processors (silence skipping, speed/pitch) *after* any user processor, and
 * those accept only [C.ENCODING_PCM_16BIT]. Emitting float from here made
 * sink configuration throw for every 16-bit source (`AUDIO_TRACK_INIT_FAILED`),
 * so this processor emits whatever encoding it was given: 16-bit stays 16-bit
 * through the rest of the chain, float stays float. The 16-bit output path is
 * a plain scale-and-clamp with no dithering — a deliberate simplification, as
 * the EQ's own gain changes dominate any truncation noise.
 *
 * Hi-res float sources currently bypass the processor chain at the sink level,
 * so the EQ is silently inactive on them today; routing the EQ into that path
 * needs a custom AudioProcessorChain and is a tracked follow-up.
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
        // Emit the same encoding we received: DefaultAudioSink appends its own
        // 16-bit-only processors after ours, so emitting float mid-chain fails
        // sink configuration for every 16-bit source (AUDIO_TRACK_INIT_FAILED).
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        rebuildFiltersIfNeeded()

        val is16Bit = inputEncoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * bytesPerSample)

        val active = state.enabled && filters.isNotEmpty()
        var i = 0
        while (i < sampleCount) {
            val channel = i % channelCount
            var sample = if (is16Bit) {
                inputBuffer.short / 32768f
            } else {
                inputBuffer.float
            }
            if (active) {
                sample *= preampGain
                for (filter in filters) {
                    sample = filter.processSample(channel, sample)
                }
                // Sinks clip anything beyond full scale at the HAL; do it here
                // deterministically instead.
                if (sample > 1f) sample = 1f else if (sample < -1f) sample = -1f
            }
            if (is16Bit) {
                // Symmetric inverse of the /32768 read; coerceIn clamps the
                // +1.0 case (1.0 * 32768 == 32768) into the signed-16 range.
                // No dithering — a deliberate simplification (see class KDoc).
                output.putShort((sample * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                output.putFloat(sample)
            }
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
