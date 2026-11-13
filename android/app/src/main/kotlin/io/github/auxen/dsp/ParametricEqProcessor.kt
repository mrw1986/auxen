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
 *    state regardless of source bit depth (16-bit PCM is promoted to float,
 *    filtered, and stays float on the way out — see below);
 *  - filters are double-precision biquads, stable at low frequencies;
 *  - the preamp is applied in the same pass, so headroom management is exact;
 *
 * ### Chain-level float headroom, not per-processor mirroring
 * This processor is one link in a chain (`ReplayGain → ParametricEq →
 * BassBoost → Balance → Limiter → EncodingRestorer`, DSP-a Task 2 onward)
 * that runs entirely in float between our own processors, so a boost in one
 * stage has real headroom to be tamed by a later stage (the limiter) instead
 * of each processor independently clamping and destroying that information.
 * Earlier, this processor mirrored its input encoding (16-bit in, 16-bit out)
 * because [androidx.media3.exoplayer.audio.DefaultAudioSink] appends its own
 * built-in processors (silence skipping, speed/pitch) *after* any user
 * processor, and those accept only [C.ENCODING_PCM_16BIT] — emitting float
 * for a 16-bit source broke sink configuration (`AUDIO_TRACK_INIT_FAILED`).
 * That sink-compat contract still holds, but it now lives at the END of the
 * chain: a final `EncodingRestorerProcessor` converts back to 16-bit right
 * before the sink's own processors, so every processor in between — this one
 * included — can emit unclamped float regardless of what it was given. 16-bit
 * input is promoted (`/32768f`) and never clamped back down here; float input
 * passes through the same filtering, also unclamped.
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
        // Always emit float: sink-compat 16-bit conversion now happens once,
        // at the chain's tail, in EncodingRestorerProcessor (see class KDoc).
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        rebuildFiltersIfNeeded()

        val is16Bit = inputEncoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        // Output is always float now (see class KDoc): 4 bytes/sample
        // regardless of the input encoding.
        val output = replaceOutputBuffer(sampleCount * 4)

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
                // No clamp: the limiter and EncodingRestorerProcessor further
                // down the chain own the ceiling, not this processor.
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
        // Skip freqHz<=0 and q<=0 too: Q=0 makes Biquad.peaking's alpha
        // divide by zero (sin(w0)/(2*0)), which cascades into an
        // Infinity/Infinity division inside the coefficient formulas and
        // yields NaN coefficients that corrupt every sample forever -- the
        // biquad's z1/z2 state never recovers from a NaN once it's in
        // (final-review fix round, Important #1). AutoEqParser now rejects
        // these values at import time too (see its own validation), so this
        // is belt-and-suspenders for any EqState built another way (the
        // 10-band graphic UI, direct API use, ...), not just imports.
        filters = s.filters.filter { it.freqHz > 0 && it.freqHz < sampleRate / 2.0 && it.q > 0 }.map { spec ->
            when (spec.type) {
                FilterType.PEAKING -> Biquad.peaking(sampleRate, spec.freqHz, spec.q, spec.gainDb, channelCount)
                FilterType.LOW_SHELF -> Biquad.lowShelf(sampleRate, spec.freqHz, spec.q, spec.gainDb, channelCount)
                FilterType.HIGH_SHELF -> Biquad.highShelf(sampleRate, spec.freqHz, spec.q, spec.gainDb, channelCount)
            }
        }
        builtGeneration = generation
    }
}
