package com.audioeq.audio

import com.audioeq.model.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadFilter {
    var b0: Double = 1.0
        private set
    var b1: Double = 0.0
        private set
    var b2: Double = 0.0
        private set
    var a1: Double = 0.0
        private set
    var a2: Double = 0.0
        private set

    private var z1L: Double = 0.0
    private var z2L: Double = 0.0
    private var z1R: Double = 0.0
    private var z2R: Double = 0.0

    fun resetState() {
        z1L = 0.0
        z2L = 0.0
        z1R = 0.0
        z2R = 0.0
    }

    fun configurePeaking(
        frequencyHz: Double,
        sampleRate: Double,
        gainDb: Double,
        q: Double
    ) {
        configure(FilterType.PEAKING, frequencyHz, sampleRate, gainDb, q)
    }

    fun processSample(sample: Double): Double = processLeft(sample)
    fun processSample(sample: Float): Float = processLeft(sample.toDouble()).toFloat()

    fun configure(
        type: FilterType,
        frequencyHz: Double,
        sampleRate: Double,
        gainDb: Double,
        q: Double
    ) {
        val nyquist = sampleRate * 0.49
        val f0 = frequencyHz.coerceIn(10.0, nyquist)
        val safeQ = q.coerceIn(0.1, 20.0)
        val w0 = 2.0 * PI * f0 / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10.0.pow(gainDb / 40.0)
        val alpha = sinW0 / (2.0 * safeQ)

        var rawB0 = 1.0
        var rawB1 = 0.0
        var rawB2 = 0.0
        var rawA0 = 1.0
        var rawA1 = 0.0
        var rawA2 = 0.0

        when (type) {
            FilterType.PEAKING -> {
                rawB0 = 1.0 + alpha * a
                rawB1 = -2.0 * cosW0
                rawB2 = 1.0 - alpha * a
                rawA0 = 1.0 + alpha / a
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                rawB0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                rawB1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                rawB2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                rawA0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                rawA1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                rawA2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            FilterType.HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                rawB0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                rawB1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                rawB2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                rawA0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                rawA1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                rawA2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            FilterType.HIGH_PASS -> {
                rawB0 = (1.0 + cosW0) / 2.0
                rawB1 = -(1.0 + cosW0)
                rawB2 = (1.0 + cosW0) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha
            }
        }

        b0 = rawB0 / rawA0
        b1 = rawB1 / rawA0
        b2 = rawB2 / rawA0
        a1 = rawA1 / rawA0
        a2 = rawA2 / rawA0
    }

    fun configureLowPass(frequencyHz: Double, sampleRate: Double, q: Double = 0.7071) {
        val f0 = frequencyHz.coerceIn(10.0, sampleRate * 0.49)
        val w0 = 2.0 * PI * f0 / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        val rawB0 = (1.0 - cosW0) / 2.0
        val rawB1 = 1.0 - cosW0
        val rawB2 = (1.0 - cosW0) / 2.0
        val rawA0 = 1.0 + alpha
        val rawA1 = -2.0 * cosW0
        val rawA2 = 1.0 - alpha

        b0 = rawB0 / rawA0
        b1 = rawB1 / rawA0
        b2 = rawB2 / rawA0
        a1 = rawA1 / rawA0
        a2 = rawA2 / rawA0
    }

    fun processLeft(input: Double): Double {
        val out = b0 * input + z1L
        z1L = b1 * input - a1 * out + z2L
        z2L = b2 * input - a2 * out
        return out
    }

    fun processRight(input: Double): Double {
        val out = b0 * input + z1R
        z1R = b1 * input - a1 * out + z2R
        z2R = b2 * input - a2 * out
        return out
    }

    fun magnitudeDbAt(fHz: Double, sampleRate: Double = 48000.0): Double {
        val w = 2.0 * PI * fHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numReal = b0 + b1 * cosW + b2 * cos2W
        val numImag = -(b1 * sinW + b2 * sin2W)
        val denReal = 1.0 + a1 * cosW + a2 * cos2W
        val denImag = -(a1 * sinW + a2 * sin2W)

        val numMagSq = numReal * numReal + numImag * numImag
        val denMagSq = (denReal * denReal + denImag * denImag).coerceAtLeast(1e-18)
        val mag = sqrt(numMagSq / denMagSq).coerceAtLeast(1e-9)
        return 20.0 * log10(mag)
    }
}
