package com.audioeq.audio

import com.audioeq.model.EqConstants
import com.audioeq.model.FilterType
import com.audioeq.model.SectionWidths
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

class MidSideProcessor(private val sampleRate: Double = 48000.0) {

    private val bandFilters = Array(EqConstants.MASTER_BANDS.size) { BiquadFilter() }
    private val highPassFilter = BiquadFilter()
    private var highPassEnabled = false

    private val harmHp40 = BiquadFilter()
    private val harmLp100 = BiquadFilter()
    private val harmPostHp100 = BiquadFilter()

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

    private val woodworthDelayExact: Double = EnhancerMath.computeWoodworthDelaySamplesExact(sampleRate)
    private val woodworthDelaySamples: Int = EnhancerMath.computeWoodworthDelaySamples(sampleRate)
    private val itdRingSize = 64
    private val itdDelayLineL = DoubleArray(itdRingSize)
    private val itdDelayLineR = DoubleArray(itdRingSize)
    private var itdWriteIdx = 0

    private val headShadowAlpha: Double = EnhancerMath.computeHeadShadowAlpha(850.0, sampleRate)
    private var shadowStateL: Double = 0.0
    private var shadowStateR: Double = 0.0

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
    private var activeProfile: AudioProfile = AudioProfile.OFF

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
            gainsDb = List(EqConstants.MASTER_BANDS.size) { 0f },
            preampDb = 0f,
            makeupDb = 0f,
            highPassHz = 0f,
            bassHarmonicMixDb = null,
            limiterThresholdDb = -0.5f,
            widths = SectionWidths(),
            qOverrides = emptyMap(),
            profile = AudioProfile.OFF,
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
        profile: AudioProfile = AudioProfile.OFF,
        bypassed: Boolean = false,
        abCompare: Boolean = false
    ) {
        isBypassed = bypassed
        isAbCompare = abCompare
        activeProfile = profile
        val netGainDb = (preampDb + makeupDb).coerceAtLeast(0.0f)
        preAndMakeupLinear = 10.0.pow(netGainDb.toDouble() / 20.0)
        limiterCeilingLinear = 10.0.pow(limiterThresholdDb.toDouble() / 20.0).coerceIn(0.5, 0.98)

        harmonicMixLinear = if (bassHarmonicMixDb != null) {
            10.0.pow(bassHarmonicMixDb.toDouble() / 20.0)
        } else {
            0.0
        }

        highPassEnabled = highPassHz > 5f
        if (highPassEnabled) {
            highPassFilter.configure(
                FilterType.HIGH_PASS,
                highPassHz.toDouble(),
                sampleRate,
                0.0,
                0.70710678
            )
        }

        for (i in EqConstants.MASTER_BANDS.indices) {
            val centerHz = EqConstants.MASTER_BANDS[i].frequencyHz.toDouble()
            val gainDb = gainsDb.getOrElse(i) { 0f }.toDouble()
            val q = qOverrides[i]?.toDouble() ?: when (i) {
                0 -> 1.41
                1 -> 1.41
                2 -> 1.41
                3 -> 1.41
                4 -> 1.41
                5 -> 1.41
                6 -> 1.41
                7 -> 1.41
                8 -> 1.41
                9 -> 1.41
                else -> 1.41
            }
            bandFilters[i].configure(FilterType.PEAKING, centerHz, sampleRate, gainDb, q)
        }
    }

    fun processStereoPcm16(buffer: ShortArray, frameCount: Int) {
        if (isBypassed) return

        val gainScale = preAndMakeupLinear
        val harmScale = harmonicMixLinear
        val ceiling = limiterCeilingLinear
        val abBypass = isAbCompare
        val prof = activeProfile

        val virtStrength = 0.65
        val gDirect = 1.0 + 0.12 * virtStrength
        val gCross = 0.38 * virtStrength
        val alphaShadow = headShadowAlpha
        val oneMinusAlpha = 1.0 - alphaShadow
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

                for (b in bandFilters.indices) {
                    val filter = bandFilters[b]
                    left = filter.processLeft(left)
                    right = filter.processRight(right)
                }

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

                if (prof == AudioProfile.EFFECT_3D) {
                    itdDelayLineL[itdWriteIdx] = left
                    itdDelayLineR[itdWriteIdx] = right

                    val readIdx0 = (itdWriteIdx - dInt + itdRingSize) and (itdRingSize - 1)
                    val readIdx1 = (itdWriteIdx - dInt - 1 + itdRingSize) and (itdRingSize - 1)
                    val delayedL = (1.0 - dFrac) * itdDelayLineL[readIdx0] + dFrac * itdDelayLineL[readIdx1]
                    val delayedR = (1.0 - dFrac) * itdDelayLineR[readIdx0] + dFrac * itdDelayLineR[readIdx1]
                    itdWriteIdx = (itdWriteIdx + 1) and (itdRingSize - 1)

                    shadowStateL = oneMinusAlpha * delayedL + alphaShadow * shadowStateL
                    shadowStateR = oneMinusAlpha * delayedR + alphaShadow * shadowStateR

                    val outL = gDirect * left - gCross * shadowStateR
                    val outR = gDirect * right - gCross * shadowStateL
                    left = outL
                    right = outR
                } else if (prof == AudioProfile.LIVE_MUSIC) {
                    val preIdx = (revWriteIdx - reverbPreDelaySamples + reverbBufSize) and (reverbBufSize - 1)
                    val c1Idx = (revWriteIdx - comb1DelaySamples + reverbBufSize) and (reverbBufSize - 1)
                    val c2Idx = (revWriteIdx - comb2DelaySamples + reverbBufSize) and (reverbBufSize - 1)

                    val earlyL = revBufL1[preIdx]
                    val earlyR = revBufR1[preIdx]
                    val tailL = 0.6 * revBufL1[c1Idx] + 0.4 * revBufR2[c2Idx]
                    val tailR = 0.6 * revBufR1[c1Idx] + 0.4 * revBufL2[c2Idx]

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
