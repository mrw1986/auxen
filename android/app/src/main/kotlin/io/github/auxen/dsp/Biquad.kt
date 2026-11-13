package io.github.auxen.dsp

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single biquad IIR filter section with per-channel state.
 *
 * Coefficients follow the Audio EQ Cookbook (Robert Bristow-Johnson).
 * Processing uses transposed direct form II with double-precision state,
 * which keeps low-frequency filters numerically stable — this matters for
 * AutoEq profiles that place high-Q filters below 100 Hz.
 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
    channelCount: Int,
) {
    private val z1 = DoubleArray(channelCount)
    private val z2 = DoubleArray(channelCount)

    fun processSample(channel: Int, input: Float): Float {
        val x = input.toDouble()
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y.toFloat()
    }

    companion object {
        /** Peaking (bell) EQ filter. */
        fun peaking(sampleRate: Int, freqHz: Double, q: Double, gainDb: Double, channels: Int): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * freqHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha / a
            return Biquad(
                b0 = (1.0 + alpha * a) / a0,
                b1 = (-2.0 * cosW0) / a0,
                b2 = (1.0 - alpha * a) / a0,
                a1 = (-2.0 * cosW0) / a0,
                a2 = (1.0 - alpha / a) / a0,
                channelCount = channels,
            )
        }

        /** Low shelf filter. */
        fun lowShelf(sampleRate: Int, freqHz: Double, q: Double, gainDb: Double, channels: Int): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * freqHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)
            val sqrtA2Alpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1.0) + (a - 1.0) * cosW0 + sqrtA2Alpha
            return Biquad(
                b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + sqrtA2Alpha)) / a0,
                b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0,
                b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - sqrtA2Alpha)) / a0,
                a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0,
                a2 = ((a + 1.0) + (a - 1.0) * cosW0 - sqrtA2Alpha) / a0,
                channelCount = channels,
            )
        }

        /** High shelf filter. */
        fun highShelf(sampleRate: Int, freqHz: Double, q: Double, gainDb: Double, channels: Int): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * freqHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)
            val sqrtA2Alpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1.0) - (a - 1.0) * cosW0 + sqrtA2Alpha
            return Biquad(
                b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + sqrtA2Alpha)) / a0,
                b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0,
                b2 = (a * ((a + 1.0) + (a - 1.0) * cosW0 - sqrtA2Alpha)) / a0,
                a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0,
                a2 = ((a + 1.0) - (a - 1.0) * cosW0 - sqrtA2Alpha) / a0,
                channelCount = channels,
            )
        }
    }
}
