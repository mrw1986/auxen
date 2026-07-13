package io.github.auxen.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * ReplayGain loudness normalisation -- as a Media3 [androidx.media3.common.audio.AudioProcessor].
 *
 * First stage of the chain (`ReplayGain -> ParametricEq -> BassBoost ->
 * Balance -> Limiter -> EncodingRestorer`, see [ParametricEqProcessor]):
 * applies a per-track gain BEFORE the EQ/limiter see the signal, so a quiet
 * track's RG boost has the same downstream headroom as everything else.
 * Float in/out, unclamped -- the limiter and `EncodingRestorerProcessor`
 * further down the chain own the ceiling, not this processor. 16-bit input
 * is promoted (`/32768f`) for standalone safety, matching every other
 * processor in the chain.
 *
 * ### Gain math -- a correction to the design brief's formula
 * The brief's literal text, `10^((chosenGain ?: fallbackDb) + preampDb) / 20)`,
 * has a misplaced parenthesis (as written, `/20` lands outside the exponent).
 * The intended, standard ReplayGain formula -- dB to linear is
 * `10^(dB/20)` -- is used instead: `linearGain = 10^(((chosenGain ?:
 * fallbackDb) + preampDb) / 20)`, i.e. the whole `(gain + preamp)` sum
 * divides by 20 INSIDE the exponent.
 *
 * `chosenGain` honors [ReplayGainState.albumMode] with a symmetric
 * cross-fallback to the other tag before reaching the state's constant
 * [ReplayGainState.fallbackDb]: album mode prefers the album tag, falling
 * back to the track tag if the album tag is absent, and vice versa when
 * album mode is off. `fallbackDb` only applies when NEITHER tag is present
 * for the current track.
 *
 * Per-track tags arrive via [setTrackGains], called once per track (Task 6
 * wires this into the playback service's media-item-transition handling).
 * Gain is recomputed fresh at the top of every [queueInput] call from the
 * current state + track gains, so a change (a state toggle, or a new
 * track's [setTrackGains]) takes effect starting with the very next buffer.
 * There is no ramp/crossfade: a manual toggle mid-playback can click. That
 * is an accepted, documented limitation for this task, not an oversight.
 */
@UnstableApi
class ReplayGainProcessor : BaseAudioProcessor() {

    @Volatile
    private var state: ReplayGainState = ReplayGainState()

    @Volatile
    private var trackGainDb: Double? = null

    @Volatile
    private var albumGainDb: Double? = null

    private var inputEncoding: Int = C.ENCODING_INVALID

    /** Replace the active ReplayGain state; takes effect on the next audio buffer. */
    fun updateState(newState: ReplayGainState) {
        state = newState
    }

    /** Set the current track's tag-derived gains; takes effect on the next audio buffer. */
    fun setTrackGains(trackGainDb: Double?, albumGainDb: Double?) {
        this.trackGainDb = trackGainDb
        this.albumGainDb = albumGainDb
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputEncoding = encoding
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

        val chosenGain = if (s.albumMode) albumGainDb ?: trackGainDb else trackGainDb ?: albumGainDb
        val effectiveGainDb = (chosenGain ?: s.fallbackDb) + s.preampDb
        val linearGain = 10.0.pow(effectiveGainDb / 20.0).toFloat()

        var i = 0
        while (i < sampleCount) {
            val sample = if (is16Bit) inputBuffer.short / 32768f else inputBuffer.float
            output.putFloat(sample * linearGain)
            i++
        }
        output.flip()
    }
}
