package com.audioeq.audio

import com.audioeq.model.EqConstants
import com.audioeq.model.FilterType
import com.audioeq.model.SectionWidths
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Root DSP & Reference Monitor Processor updated with:
 * - Volume-preserving unity input gain (0.0 dB) + upward makeup gain
 * - 2nd-order Butterworth High-Pass Filter (Q = 0.707)
 * - 10 Biquad bands using v4 Section 12.1 Q values
 * - Bass Harmonic Enhancer (extracts 40–100 Hz, soft-saturates for 2nd & 3rd harmonics, high-passes at 100 Hz, and mixes back)
 * - Meizu Square Sound 3D Virtualizer (Section 4):
 *   1. Woodworth Contralateral Delay (ITD): r = 0.0875 m, c = 343 m/s, θ = 35° (0.6109 rad) -> τ ≈ 302.2 µs (D = 15 samples @ 48 kHz)
 *   2. Head-Shadow 1-Pole Low-Pass Filter H_shadow(z) at fc = 850 Hz: s[n] = (1 - α) * x[n - D] + α * s[n - 1]
 *   3. Wet/Dry Spatial Blending at S = 650 / 1000 (65% strength)
 * - Meizu Square Sound Live Music EnvironmentalReverb (Section 5):
 *   Early reflections (22 ms) + stereo diffuse reverberant tail (RT60 = 1850 ms, HF damping = 720 permille)
 * - Output Limiter backstop at -0.5 dBFS
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

    // --- Section 4: 3D Sound Effects (VirtualizerMz) State ---
    // Woodworth ITD: τ = (r / c) * (sin(θ) + θ) = (0.0875 / 343) * (0.5736 + 0.6109) = 0.0003022 s (302.2 µs)
    // At fs = 48000 Hz: D = τ * fs = 0.0003022 * 48000 ≈ 14.5 samples
    private val woodworthDelayExact: Double = DolbyBoseDspMath.computeWoodworthDelaySamplesExact(sampleRate)
    private val woodworthDelaySamples: Int = DolbyBoseDspMath.computeWoodworthDelaySamples(sampleRate)
    private val itdRingSize = 64
    private val itdDelayLineL = DoubleArray(itdRingSize)
    private val itdDelayLineR = DoubleArray(itdRingSize)
    private var itdWriteIdx = 0

    // Head-Shadow 1-pole low-pass filter at fc = 850 Hz: α = exp(-2π * 850 / fs)
    private val headShadowAlpha: Double = DolbyBoseDspMath.computeHeadShadowAlpha(850.0, sampleRate)
    private var shadowStateL: Double = 0.0
    private var shadowStateR: Double = 0.0

    // --- Section 5: Live Music (EnvironmentalReverbMz) State ---
    private val reverbPreDelaySamples = ((22.0 / 1000.0) * sampleRate).roundToInt().coerceIn(64, 2048)
    private val comb1DelaySamples = ((38.0 / 1000.0) * sampleRate).roundToInt().coerceIn(128, 4096)
    private val comb2DelaySamples = ((53.0 / 1000.0) * sampleRate).roundToInt().coerceIn(128, 4096)
    private val reverbBufSize = 4096
    private val revBufL1 = DoubleArray(reverbBufSize)
    private val revBufR1 = DoubleArray(reverbBufSize)
    private val revBufL2 = DoubleArray(reverbBufSize)
    private val revBufR2 = DoubleArray(reverbBufSize)
    private var revWriteIdx = 0
    private var revHfStateL = 0.0
    private var revHfStateR = 0.0

    @Volatile
    private var activeBoseProfile: BoseProfile = BoseProfile.OFF

    @Volatile
    private var preAndMakeupLinear: Double = 1.0

    @Volatile
    private var harmonicMixLinear: Double = 0.0

    @Volatile
    private var limiterCeilingLinear: Double = 0.944 // -0.5 dBFS

    @Volatile
    private var isBypassed: Boolean = false

    @Volatile
    private var isAbCompare: Boolean = false

    init {
        setupCrossoversAndHarmonicFilters()
        updateParameters(
            gainsDb = List(10) { 0f },
            preampDb = 0f,
            makeupDb = 0f,
            highPassHz = 0f,
            bassHarmonicMixDb = null,
            limiterThresholdDb = -0.5f,
            widths = SectionWidths(),
            qOverrides = emptyMap(),
            boseProfile = BoseProfile.OFF,
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
        limiterThresholdDb: Float = -0.5f,
        widths: SectionWidths = SectionWidths(),
        qOverrides: Map<Int, Float> = emptyMap(),
        boseProfile: BoseProfile = BoseProfile.OFF,
        bypassed: Boolean = false,
        abCompare: Boolean = false
    ) {
        isBypassed = bypassed
        isAbCompare = abCompare
        activeBoseProfile = boseProfile
        // Never allow preamp + makeup to attenuate below unity (1.0) so bass boosts never drop volume
        val netGainDb = (preampDb + makeupDb).coerceAtLeast(0.0f)
        preAndMakeupLinear = 10.0.pow(netGainDb.toDouble() / 20.0)
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
        val profile = activeBoseProfile

        // Section 4: 3D Virtualizer constants (Strength S = 650 / 1000 = 0.65)
        val virtStrength = 0.65 // 650 / 1000
        val gDirect = 1.0 + 0.12 * virtStrength
        val gCross = 0.38 * virtStrength
        val alphaShadow = headShadowAlpha
        val oneMinusAlpha = 1.0 - alphaShadow
        // Exact Woodworth fractional delay D = τ * fs = 0.0003022 * 48000 ≈ 14.5 samples
        val dClamped = woodworthDelayExact.coerceIn(1.0, (itdRingSize - 2).toDouble())
        val dInt = dClamped.toInt()
        val dFrac = dClamped - dInt

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

                // Bass Harmonic Enhancer (40-100 Hz -> 2nd/3rd harmonic soft saturation -> 100 Hz HPF -> mix)
                if (harmScale > 0.001) {
                    val subBandL = harmLp100.processLeft(harmHp40.processLeft(left))
                    val subBandR = harmLp100.processRight(harmHp40.processRight(right))
                    val satL = tanh(subBandL * 2.4) + 0.35 * (subBandL * subBandL)
                    val satR = tanh(subBandR * 2.4) + 0.35 * (subBandR * subBandR)
                    val harmOutL = harmPostHp100.processLeft(satL) * harmScale
                    val harmOutR = harmPostHp100.processRight(satR) * harmScale
                    left += harmOutL
                    right += harmOutR
                }

                // Section 4: 3D Sound Effects (Woodworth Contralateral ITD D=14.5 samples + 850 Hz Head-Shadow LPF + 65% Wet/Dry Matrix)
                if (profile == BoseProfile.EFFECT_3D) {
                    itdDelayLineL[itdWriteIdx] = left
                    itdDelayLineR[itdWriteIdx] = right

                    // Exact Woodworth fractional delay D = τ * fs = 0.0003022 * 48000 ≈ 14.5 samples
                    val dClamped = woodworthDelayExact.coerceIn(1.0, (itdRingSize - 2).toDouble())
                    val dInt = dClamped.toInt()
                    val frac = dClamped - dInt
                    val readIdx0 = (itdWriteIdx - dInt + itdRingSize) and (itdRingSize - 1)
                    val readIdx1 = (itdWriteIdx - dInt - 1 + itdRingSize) and (itdRingSize - 1)
                    val delayedL = (1.0 - frac) * itdDelayLineL[readIdx0] + frac * itdDelayLineL[readIdx1]
                    val delayedR = (1.0 - frac) * itdDelayLineR[readIdx0] + frac * itdDelayLineR[readIdx1]
                    itdWriteIdx = (itdWriteIdx + 1) and (itdRingSize - 1)

                    // Time-domain difference equation: s[n] = (1 - α) * x[n - D] + α * s[n - 1]
                    shadowStateL = oneMinusAlpha * delayedL + alphaShadow * shadowStateL
                    shadowStateR = oneMinusAlpha * delayedR + alphaShadow * shadowStateR

                    // Wet/Dry spatial width expansion matrix at S = 650/1000
                    val outL = gDirect * left - gCross * shadowStateR
                    val outR = gDirect * right - gCross * shadowStateL
                    left = outL
                    right = outR
                } else if (profile == BoseProfile.LIVE_MUSIC) {
                    // Section 5: Live Music EnvironmentalReverb (Early reflections + diffuse hall tail)
                    val preIdx = (revWriteIdx - reverbPreDelaySamples + reverbBufSize) and (reverbBufSize - 1)
                    val c1Idx = (revWriteIdx - comb1DelaySamples + reverbBufSize) and (reverbBufSize - 1)
                    val c2Idx = (revWriteIdx - comb2DelaySamples + reverbBufSize) and (reverbBufSize - 1)

                    val earlyL = revBufL1[preIdx]
                    val earlyR = revBufR1[preIdx]
                    val tailL = 0.6 * revBufL1[c1Idx] + 0.4 * revBufR2[c2Idx]
                    val tailR = 0.6 * revBufR1[c1Idx] + 0.4 * revBufL2[c2Idx]

                    // HF damping (decayHfRatioPermille = 720)
                    revHfStateL = 0.72 * tailL + 0.28 * revHfStateL
                    revHfStateR = 0.72 * tailR + 0.28 * revHfStateR

                    revBufL1[revWriteIdx] = left + 0.48 * revHfStateL
                    revBufR1[revWriteIdx] = right + 0.48 * revHfStateR
                    revBufL2[revWriteIdx] = right + 0.42 * revHfStateL
                    revBufR2[revWriteIdx] = left + 0.42 * revHfStateR
                    revWriteIdx = (revWriteIdx + 1) and (reverbBufSize - 1)

                    left += 0.18 * earlyL + 0.22 * revHfStateL
                    right += 0.18 * earlyR + 0.22 * revHfStateR
                }
            }

            // Linked stereo limiter backstop at ceiling (-0.5 dB)
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
