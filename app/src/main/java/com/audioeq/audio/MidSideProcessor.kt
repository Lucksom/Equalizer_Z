package com.audioeq.audio

import com.audioeq.model.EqConstants
import com.audioeq.model.FilterType
import com.audioeq.model.SectionWidths
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Root DSP & Reference Monitor Processor updated for Audio EQ Deep Optimization v4 (Section 12):
 * - Fixed input gain (`inputGainDb`, normally -3.0 dB, -4.0 dB on Bluetooth) + frozen `makeupDb`
 * - 2nd-order Butterworth High-Pass Filter (Q = 0.707) at preset/route HPF frequency (140 Hz on Speaker route)
 * - 10 Biquad bands using v4 Section 12.1 Q values (31 Hz Low-shelf Q 0.9, 62 Hz Q 1.4, 125–4k Q 1.6, 8k Q 1.4, 16k High-shelf Q 0.7)
 *   plus Percussion Clarity overrides (250 Hz Q 1.0, 4 kHz Q 2.0)
 * - Bass Harmonic Enhancer (v4 Section 12.2): extracts 40–100 Hz, soft-saturates for 2nd & 3rd harmonics,
 *   high-passes at 100 Hz, and mixes back at the preset's `bassHarmonicMixDb` (+3 dB on Speaker route)
 * - 4-section Linkwitz-Riley crossovers at v4 Section 2 & 12.1 category edges (`354 Hz`, `2828 Hz`, `11314 Hz`)
 * - Output Limiter backstop at -1.0 dBFS (-1.5 dBFS on Bluetooth)
 */
class MidSideProcessor(private val sampleRate: Double = 48000.0) {

    private val bandFilters = Array(EqConstants.MASTER_BANDS.size) { BiquadFilter() }
    private val highPassFilter = BiquadFilter()
    private var highPassEnabled = false

    // Bass Harmonic Enhancer filters (40-100 Hz band-pass extraction + 100 Hz post-saturation HPF)
    private val harmHp40 = BiquadFilter()
    private val harmLp100 = BiquadFilter()
    private val harmPostHp100 = BiquadFilter()

    // Linkwitz-Riley 4th order crossovers at v4 category boundaries: 354 Hz, 2828 Hz, 11314 Hz
    private val lp354A = BiquadFilter()
    private val lp354B = BiquadFilter()
    private val hp354A = BiquadFilter()
    private val hp354B = BiquadFilter()

    private val lp2828A = BiquadFilter()
    private val lp2828B = BiquadFilter()
    private val hp2828A = BiquadFilter()
    private val hp2828B = BiquadFilter()

    private val lp11314A = BiquadFilter()
    private val lp11314B = BiquadFilter()
    private val hp11314A = BiquadFilter()
    private val hp11314B = BiquadFilter()

    @Volatile
    private var preAndMakeupLinear: Double = 1.0

    @Volatile
    private var harmonicMixLinear: Double = 0.0

    @Volatile
    private var limiterCeilingLinear: Double = 0.891 // -1.0 dBFS

    @Volatile
    private var isBypassed: Boolean = false

    @Volatile
    private var isAbCompare: Boolean = false

    init {
        setupCrossoversAndHarmonicFilters()
        updateParameters(
            gainsDb = List(10) { 0f },
            preampDb = -3f,
            makeupDb = 0f,
            highPassHz = 0f,
            bassHarmonicMixDb = null,
            limiterThresholdDb = -1.0f,
            widths = SectionWidths(),
            qOverrides = emptyMap(),
            bypassed = false,
            abCompare = false
        )
    }

    private fun setupCrossoversAndHarmonicFilters() {
        val qButterworth = 0.70710678
        harmHp40.configure(FilterType.HIGH_PASS, 40.0, sampleRate, 0.0, qButterworth)
        harmLp100.configureLowPass(100.0, sampleRate, qButterworth)
        harmPostHp100.configure(FilterType.HIGH_PASS, 100.0, sampleRate, 0.0, qButterworth)

        lp354A.configureLowPass(354.0, sampleRate, qButterworth)
        lp354B.configureLowPass(354.0, sampleRate, qButterworth)
        hp354A.configure(FilterType.HIGH_PASS, 354.0, sampleRate, 0.0, qButterworth)
        hp354B.configure(FilterType.HIGH_PASS, 354.0, sampleRate, 0.0, qButterworth)

        lp2828A.configureLowPass(2828.0, sampleRate, qButterworth)
        lp2828B.configureLowPass(2828.0, sampleRate, qButterworth)
        hp2828A.configure(FilterType.HIGH_PASS, 2828.0, sampleRate, 0.0, qButterworth)
        hp2828B.configure(FilterType.HIGH_PASS, 2828.0, sampleRate, 0.0, qButterworth)

        lp11314A.configureLowPass(11314.0, sampleRate, qButterworth)
        lp11314B.configureLowPass(11314.0, sampleRate, qButterworth)
        hp11314A.configure(FilterType.HIGH_PASS, 11314.0, sampleRate, 0.0, qButterworth)
        hp11314B.configure(FilterType.HIGH_PASS, 11314.0, sampleRate, 0.0, qButterworth)
    }

    @Synchronized
    fun updateParameters(
        gainsDb: List<Float>,
        preampDb: Float,
        makeupDb: Float = 0f,
        highPassHz: Float = 0f,
        bassHarmonicMixDb: Float? = null,
        limiterThresholdDb: Float = -1.0f,
        widths: SectionWidths = SectionWidths(),
        qOverrides: Map<Int, Float> = emptyMap(),
        bypassed: Boolean = false,
        abCompare: Boolean = false
    ) {
        isBypassed = bypassed
        isAbCompare = abCompare
        preAndMakeupLinear = 10.0.pow((preampDb + makeupDb).toDouble() / 20.0)
        limiterCeilingLinear = 10.0.pow(limiterThresholdDb.toDouble() / 20.0).coerceIn(0.5, 0.98)

        harmonicMixLinear = if (bassHarmonicMixDb != null) {
            10.0.pow(bassHarmonicMixDb.toDouble() / 20.0)
        } else {
            0.0
        }

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
            // Do not exceed Q 2.0 on neighbouring bands (v4 Section 12.1)
            val q = (qOverrides[spec.frequencyHz] ?: spec.defaultQ).toDouble().coerceAtMost(2.0)
            bandFilters[spec.index].configure(
                type = spec.filterType,
                frequencyHz = spec.frequencyHz.toDouble(),
                sampleRate = sampleRate,
                gainDb = gain,
                q = q
            )
        }
    }

    /**
     * Processes an interleaved stereo PCM 16-bit buffer in-place with zero heap allocations.
     */
    @Synchronized
    fun processStereoPcm16(buffer: ShortArray, frameCount: Int) {
        if (isBypassed) return
        val gainScale = preAndMakeupLinear
        val harmScale = harmonicMixLinear
        val ceiling = limiterCeilingLinear
        val abBypass = isAbCompare

        var idx = 0
        for (frame in 0 until frameCount) {
            var left = (buffer[idx].toDouble() / 32768.0) * gainScale
            var right = (buffer[idx + 1].toDouble() / 32768.0) * gainScale

            if (!abBypass) {
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

                // Bass Harmonic Enhancer (v4 Section 12.2): 40-100 Hz -> 2nd/3rd harmonic soft saturation -> 100 Hz HPF -> mix
                if (harmScale > 0.001) {
                    val subBandL = harmLp100.processLeft(harmHp40.processLeft(left))
                    val subBandR = harmLp100.processRight(harmHp40.processRight(right))
                    // Generate 2nd (even) and 3rd (odd) harmonics via asymmetric soft-clipping polynomial + tanh
                    val satL = tanh(subBandL * 2.4) + 0.35 * (subBandL * subBandL)
                    val satR = tanh(subBandR * 2.4) + 0.35 * (subBandR * subBandR)
                    val harmOutL = harmPostHp100.processLeft(satL) * harmScale
                    val harmOutR = harmPostHp100.processRight(satR) * harmScale
                    left += harmOutL
                    right += harmOutR
                }
            }

            // Linked stereo limiter backstop at ceiling (-1.0 dB or -1.5 dB on Bluetooth)
            val limL = ceiling * tanh(left / ceiling)
            val limR = ceiling * tanh(right / ceiling)

            buffer[idx] = (limL * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            buffer[idx + 1] = (limR * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            idx += 2
        }
    }

    @Synchronized
    fun computeFrequencyResponseCurve(numPoints: Int = 96): List<Pair<Float, Float>> {
        val minLog = log10(20.0)
        val maxLog = log10(20000.0)
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
