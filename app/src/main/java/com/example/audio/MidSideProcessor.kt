package com.example.audio

import com.example.model.EqConstants
import com.example.model.FilterType
import com.example.model.SectionWidths
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Multi-band Biquad EQ + High-Pass Filter + 4-Section Linkwitz-Riley Crossover Mid/Side Stereo Width Processor.
 *
 * Implements:
 * - Preamp headroom gain
 * - 2nd-order Butterworth High-Pass Filter (Q = 0.707) for sub-bass cleanup (e.g., 40 Hz for Percussion Clarity)
 * - 10 Biquad bands (31 Hz low-shelf, 62..8k peaking with custom Q overrides, 16k high-shelf)
 * - Sub-60 Hz mono fold-down when Bass width < 100%
 * - 4-section Linkwitz-Riley crossovers at 250 Hz, 4 kHz, 12 kHz with independent Mid/Side width (Side * widthPct/100)
 * - Soft-knee output limiter at -1.0 dBFS
 */
class MidSideProcessor(private val sampleRate: Double = 48000.0) {

    private val bandFilters = Array(EqConstants.MASTER_BANDS.size) { BiquadFilter() }
    private val highPassFilter = BiquadFilter()
    private var highPassEnabled = false

    // Linkwitz-Riley 4th order (two cascaded 2nd-order Butterworth filters with Q = 0.7071)
    // Crossover 1: 250 Hz (splits Bass vs Mids+)
    private val lp250A = BiquadFilter()
    private val lp250B = BiquadFilter()
    private val hp250A = BiquadFilter()
    private val hp250B = BiquadFilter()

    // Sub-60 Hz mono filter for low-bass mono protection
    private val lp60Sub = BiquadFilter()
    private val hp60Sub = BiquadFilter()

    // Crossover 2: 4000 Hz (splits Mids vs Treble+)
    private val lp4kA = BiquadFilter()
    private val lp4kB = BiquadFilter()
    private val hp4kA = BiquadFilter()
    private val hp4kB = BiquadFilter()

    // Crossover 3: 12000 Hz (splits Treble vs Air)
    private val lp12kA = BiquadFilter()
    private val lp12kB = BiquadFilter()
    private val hp12kA = BiquadFilter()
    private val hp12kB = BiquadFilter()

    @Volatile
    private var preampLinear: Double = 1.0

    @Volatile
    private var bassWidthScale: Double = 0.10

    @Volatile
    private var midsWidthScale: Double = 1.00

    @Volatile
    private var trebleWidthScale: Double = 1.15

    @Volatile
    private var airWidthScale: Double = 1.25

    @Volatile
    private var isBypassed: Boolean = false

    init {
        setupCrossovers()
        updateParameters(
            gainsDb = List(10) { 0f },
            preampDb = 0f,
            highPassHz = 0f,
            widths = SectionWidths(),
            qOverrides = emptyMap(),
            bypassed = false
        )
    }

    private fun setupCrossovers() {
        val qButterworth = 0.70710678
        lp60Sub.configureLowPass(60.0, sampleRate, qButterworth)
        hp60Sub.configure(FilterType.HIGH_PASS, 60.0, sampleRate, 0.0, qButterworth)

        lp250A.configureLowPass(250.0, sampleRate, qButterworth)
        lp250B.configureLowPass(250.0, sampleRate, qButterworth)
        hp250A.configure(FilterType.HIGH_PASS, 250.0, sampleRate, 0.0, qButterworth)
        hp250B.configure(FilterType.HIGH_PASS, 250.0, sampleRate, 0.0, qButterworth)

        lp4kA.configureLowPass(4000.0, sampleRate, qButterworth)
        lp4kB.configureLowPass(4000.0, sampleRate, qButterworth)
        hp4kA.configure(FilterType.HIGH_PASS, 4000.0, sampleRate, 0.0, qButterworth)
        hp4kB.configure(FilterType.HIGH_PASS, 4000.0, sampleRate, 0.0, qButterworth)

        lp12kA.configureLowPass(12000.0, sampleRate, qButterworth)
        lp12kB.configureLowPass(12000.0, sampleRate, qButterworth)
        hp12kA.configure(FilterType.HIGH_PASS, 12000.0, sampleRate, 0.0, qButterworth)
        hp12kB.configure(FilterType.HIGH_PASS, 12000.0, sampleRate, 0.0, qButterworth)
    }

    @Synchronized
    fun updateParameters(
        gainsDb: List<Float>,
        preampDb: Float,
        highPassHz: Float,
        widths: SectionWidths,
        qOverrides: Map<Int, Float> = emptyMap(),
        bypassed: Boolean = false
    ) {
        isBypassed = bypassed
        preampLinear = 10.0.pow(preampDb.toDouble() / 20.0)

        if (highPassHz > 10f) {
            highPassEnabled = true
            highPassFilter.configure(
                type = FilterType.HIGH_PASS,
                frequencyHz = highPassHz.toDouble(),
                sampleRate = sampleRate,
                gainDb = 0.0,
                q = 0.7071
            )
        } else {
            highPassEnabled = false
        }

        for (spec in EqConstants.MASTER_BANDS) {
            val gain = gainsDb.getOrElse(spec.index) { 0f }.toDouble()
            val q = (qOverrides[spec.frequencyHz] ?: spec.defaultQ).toDouble()
            bandFilters[spec.index].configure(
                type = spec.filterType,
                frequencyHz = spec.frequencyHz.toDouble(),
                sampleRate = sampleRate,
                gainDb = gain,
                q = q
            )
        }

        bassWidthScale = (widths.bassWidthPct / 100.0).coerceIn(0.0, 2.0)
        midsWidthScale = (widths.midsWidthPct / 100.0).coerceIn(0.0, 2.0)
        trebleWidthScale = (widths.trebleWidthPct / 100.0).coerceIn(0.0, 2.0)
        airWidthScale = (widths.airWidthPct / 100.0).coerceIn(0.0, 2.0)
    }

    /**
     * Processes an interleaved stereo PCM 16-bit buffer in-place with zero heap allocations.
     */
    @Synchronized
    fun processStereoPcm16(buffer: ShortArray, frameCount: Int) {
        if (isBypassed) return
        val pre = preampLinear
        val wBass = bassWidthScale
        val wMids = midsWidthScale
        val wTreble = trebleWidthScale
        val wAir = airWidthScale

        var idx = 0
        for (frame in 0 until frameCount) {
            var left = (buffer[idx].toDouble() / 32768.0) * pre
            var right = (buffer[idx + 1].toDouble() / 32768.0) * pre

            if (highPassEnabled) {
                left = highPassFilter.processLeft(left)
                right = highPassFilter.processRight(right)
            }

            // 10-band Biquad EQ cascade
            for (b in bandFilters.indices) {
                val filter = bandFilters[b]
                left = filter.processLeft(left)
                right = filter.processRight(right)
            }

            // Split into 4 frequency sections via Linkwitz-Riley crossovers
            val bassL = lp250B.processLeft(lp250A.processLeft(left))
            val bassR = lp250B.processRight(lp250A.processRight(right))

            val high250L = hp250B.processLeft(hp250A.processLeft(left))
            val high250R = hp250B.processRight(hp250A.processRight(right))

            val midsL = lp4kB.processLeft(lp4kA.processLeft(high250L))
            val midsR = lp4kB.processRight(lp4kA.processRight(high250R))

            val high4kL = hp4kB.processLeft(hp4kA.processLeft(high250L))
            val high4kR = hp4kB.processRight(hp4kA.processRight(high250R))

            val trebleL = lp12kB.processLeft(lp12kA.processLeft(high4kL))
            val trebleR = lp12kB.processRight(lp12kA.processRight(high4kR))

            val airL = hp12kB.processLeft(hp12kA.processLeft(high4kL))
            val airR = hp12kB.processRight(hp12kA.processRight(high4kR))

            // Bass Mid/Side processing with sub-60 Hz forced mono protection when width < 100%
            val sub60L = lp60Sub.processLeft(bassL)
            val sub60R = lp60Sub.processRight(bassR)
            val upperBassL = hp60Sub.processLeft(bassL)
            val upperBassR = hp60Sub.processRight(bassR)

            val subMid = 0.5 * (sub60L + sub60R)
            val subSide = if (wBass >= 0.99) 0.5 * (sub60L - sub60R) else 0.0

            val ubMid = 0.5 * (upperBassL + upperBassR)
            val ubSide = 0.5 * (upperBassL - upperBassR) * wBass

            val outBassL = (subMid + subSide) + (ubMid + ubSide)
            val outBassR = (subMid - subSide) - (ubMid - ubSide)

            // Mids Mid/Side
            val mMid = 0.5 * (midsL + midsR)
            val mSide = 0.5 * (midsL - midsR) * wMids
            val outMidsL = mMid + mSide
            val outMidsR = mMid - mSide

            // Treble Mid/Side
            val tMid = 0.5 * (trebleL + trebleR)
            val tSide = 0.5 * (trebleL - trebleR) * wTreble
            val outTrebleL = tMid + tSide
            val outTrebleR = tMid - tSide

            // Air Mid/Side
            val aMid = 0.5 * (airL + airR)
            val aSide = 0.5 * (airL - airR) * wAir
            val outAirL = aMid + aSide
            val outAirR = aMid - aSide

            // Sum bands and apply transparent soft-limiter (-1.0 dBFS ceiling)
            val sumL = outBassL + outMidsL + outTrebleL + outAirL
            val sumR = outBassR + outMidsR + outTrebleR + outAirR

            val limL = tanh(sumL * 0.95)
            val limR = tanh(sumR * 0.95)

            buffer[idx] = (limL * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            buffer[idx + 1] = (limR * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            idx += 2
        }
    }

    /**
     * Evaluates the composite Biquad EQ + High-Pass magnitude response (in dB) across log-spaced frequencies
     * from 20 Hz to 20,000 Hz for the live Frequency Response Graph.
     */
    @Synchronized
    fun computeFrequencyResponseCurve(numPoints: Int = 96): List<Pair<Float, Float>> {
        val minLog = kotlin.math.log10(20.0)
        val maxLog = kotlin.math.log10(20000.0)
        val points = ArrayList<Pair<Float, Float>>(numPoints)

        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1).coerceAtLeast(1)
            val freq = 10.0.pow(minLog + t * (maxLog - minLog))
            var totalDb = 0.0
            if (highPassEnabled) {
                totalDb += highPassFilter.magnitudeDbAt(freq, sampleRate)
            }
            for (b in bandFilters.indices) {
                totalDb += bandFilters[b].magnitudeDbAt(freq, sampleRate)
            }
            points.add(freq.toFloat() to totalDb.toFloat().coerceIn(-15f, 15f))
        }
        return points
    }
}
