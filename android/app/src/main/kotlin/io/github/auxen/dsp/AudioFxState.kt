package io.github.auxen.dsp

import kotlinx.serialization.Serializable

/**
 * Serializable state for each audio effect in the DSP chain.
 *
 * Every effect owns an independent [@Serializable] state that is persisted
 * under its own DataStore key by [AudioFxController]. Later tasks (the
 * processors and the UI) depend on these exact shapes and defaults, so keep
 * field names and default values stable.
 */

/**
 * Bass boost: a low-shelf lift centred at [freqHz] with [gainDb] of gain.
 *
 * The UI exposes [freqHz] over 40..200 Hz and [gainDb] over 0..12 dB.
 */
@Serializable
data class BassBoostState(
    val enabled: Boolean = false,
    val freqHz: Double = 80.0,
    val gainDb: Double = 6.0,
)

/**
 * Stereo balance: pans the mix left or right.
 *
 * [balance] runs -1 (full left) through 0 (centre) to +1 (full right).
 */
@Serializable
data class BalanceState(
    val enabled: Boolean = false,
    val balance: Float = 0f,
)

/**
 * Peak limiter: a safety net that tames inter-sample / gain-stacking peaks.
 *
 * Defaults ON because it protects downstream stages (EQ boosts, replay-gain
 * preamp) from clipping.
 */
@Serializable
data class LimiterState(
    val enabled: Boolean = true,
    val thresholdDb: Double = -1.0,
    val kneeDb: Double = 6.0,
    val releaseMs: Double = 120.0,
)

/**
 * ReplayGain: applies per-track or per-album loudness normalisation.
 *
 * [albumMode] false uses track gain, true uses album gain. [preampDb] runs
 * -12..+12 dB; [fallbackDb] is applied when a track carries no RG metadata.
 */
@Serializable
data class ReplayGainState(
    val enabled: Boolean = false,
    val albumMode: Boolean = false,
    val preampDb: Double = 0.0,
    val fallbackDb: Double = 0.0,
)
