package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Soft-knee peak limiter -- as a Media3 [androidx.media3.common.audio.AudioProcessor].
 *
 * Last processing stage before [EncodingRestorerProcessor] in the chain
 * (`ReplayGain -> ParametricEq -> BassBoost -> Balance -> Limiter ->
 * EncodingRestorer`, see [ParametricEqProcessor]): the ONE processor allowed
 * to clamp mid-chain, because it IS the safety stage the rest of the chain
 * is designed to lean on for headroom management.
 *
 * ### Algorithm, verbatim from the design brief
 * Per FRAME (all channels of one sample instant):
 *  - `peak = max(|ch_i|)` across channels.
 *  - `over = 20*log10(peak) - thresholdDb` (guarded: `peak <= 0` -> desired
 *    gain 1, skipping the log of zero/negative).
 *  - Soft-knee reduction in dB, knee width `k = kneeDb`:
 *    - `0` when `over <= -k/2` (below the knee, no reduction)
 *    - `((over + k/2)^2) / (2k)` when `|over| < k/2` (inside the knee, a
 *      smooth quadratic ramp -- a signal exactly AT the threshold, `over=0`,
 *      lands at the knee's midpoint and gets `kneeDb/8` dB of reduction, not
 *      a brick-wall cliff)
 *    - `over` when `over >= k/2` (above the knee, 1:1 -- output settles
 *      exactly at the threshold, since `outputDb = inputDb - over =
 *      thresholdDb`)
 *  - `desiredGain = 10^(-reduction/20)`.
 *  - Envelope: instant attack (jump down immediately when desired < current
 *    gain), exponential release otherwise:
 *    `gain = if (desired < gain) desired else gain + (1 - releaseCoef) * (desired - gain)`,
 *    `releaseCoef = exp(-1.0 / (releaseMs/1000.0 * sampleRate))`. Gain state
 *    is seeded at 1.0 and reset to 1.0 in [onFlush] -- a seek/discontinuity
 *    shouldn't leave a still-recovering gain reduction carried over from
 *    unrelated audio.
 *  - Apply `gain` to every channel of the frame, THEN a final hard clamp at
 *    +/-1 -- belt-and-suspenders for pathological attacks the soft-knee math
 *    doesn't fully catch (e.g. several simultaneous full-scale channels at
 *    odd phase pushing a single-channel peak view past what the gain alone
 *    tames). This is the only clamp allowed mid-chain; every other processor
 *    in the chain runs unclamped float precisely so this stage has real
 *    material to work with.
 *
 * Disabled -> pass-through with NO clamp: [EncodingRestorerProcessor] still
 * clamps at the 16-bit conversion, so a disabled limiter doesn't leave the
 * signal unprotected on the way to the sink, it just removes this stage's
 * own ceiling from the middle of the chain.
 *
 * All gain math runs in double precision; only the final per-sample write is
 * narrowed to float, matching [ParametricEqProcessor]'s biquad state.
 */
@UnstableApi
class LimiterProcessor : BaseAudioProcessor() {

    @Volatile
    private var state: LimiterState = LimiterState()

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var inputEncoding: Int = C.ENCODING_INVALID

    /** Envelope gain, 0..1; seeded at unity, reset on [onFlush]. */
    private var gain: Double = 1.0

    /** Scratch frame buffer, reused across calls -- no per-frame allocation. */
    private var frame: DoubleArray = DoubleArray(0)

    /** Replace the active limiter state; takes effect on the next audio buffer. */
    fun updateState(newState: LimiterState) {
        state = newState
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        inputEncoding = encoding
        if (frame.size != channelCount) frame = DoubleArray(channelCount)
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val s = state
        val is16Bit = inputEncoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * 4)

        if (!s.enabled) {
            var i = 0
            while (i < sampleCount) {
                output.putFloat(if (is16Bit) inputBuffer.short / 32768f else inputBuffer.float)
                i++
            }
            output.flip()
            return
        }

        val releaseCoef = exp(-1.0 / (s.releaseMs / 1000.0 * sampleRate))
        val frameCount = sampleCount / channelCount
        var f = 0
        while (f < frameCount) {
            var peak = 0.0
            var ch = 0
            while (ch < channelCount) {
                val sample = if (is16Bit) inputBuffer.short / 32768f else inputBuffer.float
                val d = sample.toDouble()
                frame[ch] = d
                val a = abs(d)
                if (a > peak) peak = a
                ch++
            }

            val desiredGain = if (peak <= 0.0) {
                1.0
            } else {
                val over = 20.0 * log10(peak) - s.thresholdDb
                val k = s.kneeDb
                val reduction = when {
                    over <= -k / 2.0 -> 0.0
                    over >= k / 2.0 -> over
                    else -> (over + k / 2.0).pow(2) / (2.0 * k)
                }
                10.0.pow(-reduction / 20.0)
            }

            gain = if (desiredGain < gain) desiredGain else gain + (1.0 - releaseCoef) * (desiredGain - gain)

            ch = 0
            while (ch < channelCount) {
                val applied = (frame[ch] * gain).coerceIn(-1.0, 1.0)
                output.putFloat(applied.toFloat())
                ch++
            }
            f++
        }
        output.flip()
    }

    override fun onFlush() {
        gain = 1.0
    }
}
