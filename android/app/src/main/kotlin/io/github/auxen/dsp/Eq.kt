package io.github.auxen.dsp

import kotlinx.serialization.Serializable
import kotlin.math.max

/** Filter types supported by the EQ engine (matches AutoEq's parametric export). */
enum class FilterType {
    /** Peaking / bell ("PK" in AutoEq exports). */
    PEAKING,

    /** Low shelf ("LSC"). */
    LOW_SHELF,

    /** High shelf ("HSC"). */
    HIGH_SHELF,
}

/** One parametric filter stage. */
@Serializable
data class FilterSpec(
    val type: FilterType,
    val freqHz: Double,
    val q: Double,
    val gainDb: Double,
)

/**
 * Complete EQ state: a preamp plus an ordered list of filter stages.
 *
 * Two ways to produce one:
 *  - [fromBands]: the desktop app's 10-band graphic EQ, mapped onto peaking
 *    filters at the ISO centre frequencies.
 *  - [AutoEqParser.parse]: a headphone-correction profile in AutoEq's
 *    ParametricEq text format (the Wavelet-style feature).
 */
@Serializable
data class EqState(
    val enabled: Boolean = false,
    val preampDb: Double = 0.0,
    val filters: List<FilterSpec> = emptyList(),
    /** Display name of the active preset / AutoEq profile, if any. */
    val presetName: String? = null,
    /** Graphic-EQ band gains when this state came from the 10-band UI. */
    val bands: List<Double>? = null,
) {
    companion object {
        /** Standard 10-band ISO centre frequencies (Hz) — same as the desktop app. */
        val BAND_FREQUENCIES = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

        const val NUM_BANDS = 10
        const val MIN_GAIN_DB = -12.0
        const val MAX_GAIN_DB = 12.0

        /** Q for graphic-EQ bands: ~sqrt(2) gives adjacent-band crossover near -3 dB. */
        private const val GRAPHIC_BAND_Q = 1.41

        /** Built-in presets ported from the desktop app's `equalizer.py`. */
        val PRESETS: Map<String, List<Double>> = linkedMapOf(
            "Flat" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            "Bass Boost" to listOf(6.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            "Treble Boost" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 4.0, 5.0, 6.0),
            "Vocal" to listOf(-2.0, -1.0, 0.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -2.0),
            "Rock" to listOf(4.0, 3.0, 1.0, 0.0, -1.0, -1.0, 0.0, 2.0, 3.0, 4.0),
            "Pop" to listOf(-1.0, 0.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -2.0, -2.0),
            "Jazz" to listOf(3.0, 2.0, 1.0, 2.0, -1.0, -1.0, 0.0, 1.0, 2.0, 3.0),
            "Classical" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -2.0, -2.0, -4.0),
            "Electronic" to listOf(5.0, 4.0, 2.0, 0.0, -1.0, 0.0, 1.0, 3.0, 4.0, 5.0),
            "Hip-Hop" to listOf(5.0, 4.0, 2.0, 0.0, -1.0, 0.0, 1.0, 0.0, 2.0, 3.0),
        )

        /**
         * Build an [EqState] from 10 graphic-EQ band gains.
         *
         * The preamp is set automatically to offset the largest boost so the
         * filter chain cannot push full-scale material into clipping.
         */
        fun fromBands(gainsDb: List<Double>, enabled: Boolean = true, presetName: String? = null): EqState {
            require(gainsDb.size == NUM_BANDS) { "Expected $NUM_BANDS gain values, got ${gainsDb.size}" }
            val clamped = gainsDb.map { it.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
            val filters = BAND_FREQUENCIES.zip(clamped).map { (freq, gain) ->
                FilterSpec(FilterType.PEAKING, freq, GRAPHIC_BAND_Q, gain)
            }
            return EqState(
                enabled = enabled,
                preampDb = -max(0.0, clamped.max()),
                filters = filters,
                presetName = presetName,
                bands = clamped,
            )
        }
    }
}

/**
 * Parser for AutoEq's ParametricEq text export — the format Wavelet consumes.
 *
 * Example input:
 * ```
 * Preamp: -6.4 dB
 * Filter 1: ON PK Fc 105 Hz Gain -2.4 dB Q 0.70
 * Filter 2: ON LSC Fc 105 Hz Gain 2.0 dB Q 0.71
 * Filter 10: ON HSC Fc 10000 Hz Gain -4.0 dB Q 0.70
 * ```
 * Profiles for ~5000 headphone models are published at
 * https://github.com/jaakkopasanen/AutoEq/tree/master/results
 */
object AutoEqParser {
    private val PREAMP_RE = Regex("""^\s*Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER_RE = Regex(
        """^\s*Filter\s*\d+:\s*(ON|OFF)\s+(PK|LSC?|HSC?)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Default Q for shelf filters when the profile omits one. */
    private const val DEFAULT_SHELF_Q = 0.71

    fun parse(text: String, profileName: String? = null): EqState {
        var preamp = 0.0
        val filters = mutableListOf<FilterSpec>()

        for (line in text.lineSequence()) {
            PREAMP_RE.find(line)?.let { m ->
                preamp = m.groupValues[1].toDouble()
                return@let
            }
            val m = FILTER_RE.find(line) ?: continue
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK" -> FilterType.PEAKING
                "LS", "LSC" -> FilterType.LOW_SHELF
                "HS", "HSC" -> FilterType.HIGH_SHELF
                else -> continue
            }
            val freq = m.groupValues[3].toDouble()
            val gain = m.groupValues[4].toDouble()
            val q = m.groupValues[5].takeIf { it.isNotEmpty() }?.toDouble() ?: DEFAULT_SHELF_Q
            filters += FilterSpec(type, freq, q, gain)
        }

        require(filters.isNotEmpty()) { "No filters found — is this an AutoEq ParametricEq file?" }
        return EqState(enabled = true, preampDb = preamp, filters = filters, presetName = profileName)
    }
}
