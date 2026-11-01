package com.audioeq.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object EqMath {
    fun formatCustomizedSoundStepsCsv(offsets: List<Float>): String {
        return offsets.joinToString(", ") { db ->
            val step = ((db + 42f) / 3f).roundToInt().coerceIn(0, 28)
            step.toString()
        }
    }
}

enum class IeqPreset(
    val id: String,
    val index: Int,
    val title: String,
    val subtitle: String,
    val defaultAmount: Int
) {
    OFF("ieq_off", 0, "No effect", "Bypass intelligent target matching", 0),
    DETAILED("ieq_detailed", 1, "Detailed", "Open treble & upper-mid clarity (+3.7 dB peak)", 10),
    BALANCED("ieq_balanced", 2, "Balanced", "Natural reference target (+3.4 dB warmth)", 10),
    WARM("ieq_warm", 3, "Warm", "Rich low-mid body & smooth highs (+2.9 dB)", 10),
    CUSTOM("ieq_custom", 4, "Customized", "User-editable 10-band target curve", 10);

    companion object {
        fun fromId(id: String?): IeqPreset = entries.find { it.id == id } ?: OFF
        fun fromIndex(idx: Int): IeqPreset = entries.find { it.index == idx } ?: OFF
    }
}

enum class AudioProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String,
    val virtualizerStrengthPermille: Int,
    val bassBoostStrengthPermille: Int
) {
    OFF(
        id = "profile_off",
        title = "Off",
        subtitle = "Pure 10-band EQ & enhancer chain without profile shaping",
        badgeLabel = "Direct",
        virtualizerStrengthPermille = 0,
        bassBoostStrengthPermille = 0
    ),
    DYNAMIC(
        id = "profile_dynamic",
        title = "Dynamic",
        subtitle = "Adaptive loudness contour, punchy bass & crisp presence",
        badgeLabel = "Dynamic",
        virtualizerStrengthPermille = 350,
        bassBoostStrengthPermille = 400
    ),
    BALANCED(
        id = "profile_balanced",
        title = "Balanced",
        subtitle = "Neutral studio balance with gentle vocal focus",
        badgeLabel = "Balanced",
        virtualizerStrengthPermille = 150,
        bassBoostStrengthPermille = 150
    ),
    EFFECT_3D(
        id = "profile_3d",
        title = "3D",
        subtitle = "Wide binaural crossfeed stage & spatial air",
        badgeLabel = "3D Spatial",
        virtualizerStrengthPermille = 850,
        bassBoostStrengthPermille = 300
    ),
    LIVE_MUSIC(
        id = "profile_live_music",
        title = "Live Music",
        subtitle = "Concert hall presence, stage depth & upper-harmonic air",
        badgeLabel = "Live Stage",
        virtualizerStrengthPermille = 650,
        bassBoostStrengthPermille = 350
    ),
    MOVIE(
        id = "profile_movie",
        title = "Movie",
        subtitle = "Binaural HRTF virtualizer (±35°), surround decoder, +2.5 dB dialogue & −12 dB de-reverb",
        badgeLabel = "Movie · HRTF",
        virtualizerStrengthPermille = 650,
        bassBoostStrengthPermille = 0
    );

    companion object {
        fun fromId(id: String?): AudioProfile = entries.find { it.id == id } ?: OFF
    }
}

enum class StereoWideningMode(
    val id: String,
    val index: Int,
    val title: String,
    val subtitle: String,
    val amount: Int,
    val azimuthDeg: Int,
    val widthScalar: Float,
    val sideGainDb: Float,
    val groupDelayMs: Float,
    val delaySamples48k: Int
) {
    NO_EFFECT(
        id = "widening_off",
        index = 0,
        title = "No effect",
        subtitle = "0° · W 1.000 · 0.00 dB",
        amount = 0,
        azimuthDeg = 0,
        widthScalar = 1.000f,
        sideGainDb = 0.00f,
        groupDelayMs = 0.00f,
        delaySamples48k = 0
    ),
    NEAR(
        id = "widening_near",
        index = 1,
        title = "Near",
        subtitle = "±16° · W 1.250 · +1.94 dB · 10 smp",
        amount = 16,
        azimuthDeg = 16,
        widthScalar = 1.250f,
        sideGainDb = 1.94f,
        groupDelayMs = 0.20f,
        delaySamples48k = 10
    ),
    MIDDLE(
        id = "widening_middle",
        index = 2,
        title = "Middle",
        subtitle = "±35° · W 1.547 · +3.79 dB · 22 smp",
        amount = 35,
        azimuthDeg = 35,
        widthScalar = 1.547f,
        sideGainDb = 3.79f,
        groupDelayMs = 0.45f,
        delaySamples48k = 22
    ),
    FAR(
        id = "widening_far",
        index = 3,
        title = "Far",
        subtitle = "±103° · W 2.609 · +8.33 dB · 36 smp",
        amount = 103,
        azimuthDeg = 103,
        widthScalar = 2.609f,
        sideGainDb = 8.33f,
        groupDelayMs = 0.75f,
        delaySamples48k = 36
    );

    val dapAmount: Int get() = amount

    companion object {
        fun fromId(id: String?): StereoWideningMode = entries.find { it.id == id } ?: NO_EFFECT
        fun fromIndex(idx: Int): StereoWideningMode = entries.find { it.index == idx } ?: NO_EFFECT
    }
}

enum class DialogueMode {
    DEFAULT,
    BROADCAST,
    CINEMA,
    PODCAST
}

enum class BassContour {
    NATURAL,
    TIGHT_PUNCH,
    SUB_BASS,
    CLUB
}

data class EnhancerConfig(
    val ieqPreset: IeqPreset = IeqPreset.OFF,
    val ieqAmount: Int = 10,
    val ieqIntensity: Float = 1.0f,
    val ieqCustom10Db: List<Float> = List(10) { 0f },
    val dialogueEnhancerEnabled: Boolean = false,
    val dialogueAmount: Int = 8,
    val dialogueGain: Int = 4,
    val dialogueDucking: Int = 2,
    val dialogueMode: DialogueMode = DialogueMode.BROADCAST,
    val bassEnhancerEnabled: Boolean = false,
    val bassEnhancerStrength: Int = 80,
    val bassBoostLevel: Int = 0,
    val bassContour: BassContour = BassContour.TIGHT_PUNCH,
    val virtualBassEnabled: Boolean = false,
    val profile: AudioProfile = AudioProfile.OFF,
    val stereoWideningMode: StereoWideningMode = StereoWideningMode.NO_EFFECT,
    val reduceReverbAmount: Int = 0
) {
    fun isAnyActive(): Boolean =
        ieqPreset != IeqPreset.OFF ||
            dialogueEnhancerEnabled ||
            bassEnhancerEnabled ||
            virtualBassEnabled ||
            profile != AudioProfile.OFF ||
            stereoWideningMode != StereoWideningMode.NO_EFFECT ||
            reduceReverbAmount > 0
}

object EnhancerMath {
    val IEQ_FREQS_20: IntArray get() = EnhancerDsp.IEQ_FREQS_20
    val IEQ_DETAILED_20_RAW: IntArray get() = EnhancerDsp.IEQ_DETAILED_Q4
    val IEQ_BALANCED_20_RAW: IntArray get() = EnhancerDsp.IEQ_BALANCED_Q4
    val IEQ_WARM_20_RAW: IntArray get() = EnhancerDsp.IEQ_WARM_Q4

    fun computeStereoWidthScalar(dapAmount: Int): Double =
        1.0 + (dapAmount / 64.0)

    fun computeStereoSideGainDb(dapAmount: Int): Double =
        if (dapAmount == 0) 0.0 else 20.0 * log10(computeStereoWidthScalar(dapAmount))

    fun reduceReverbGamma(amount: Int): Double =
        if (amount == 0) 0.0 else 0.5 + 0.35 * amount

    fun reduceReverbSuppressionFloorDb(amount: Int): Double =
        if (amount == 0) 0.0 else -6.0 * amount

    fun computeWienerReverbGain(amount: Int, noiseRatio: Double, signalRatio: Double): Double {
        if (amount == 0) return 1.0
        val gamma = reduceReverbGamma(amount)
        val snrFactor = sqrt(noiseRatio / signalRatio)
        val gain = 1.0 - gamma * snrFactor
        return gain.coerceAtLeast(0.0)
    }

    fun computeTotalEnhancerOffsetDb(frequencyHz: Double, config: EnhancerConfig): Float {
        val combined31 = EnhancerDsp.computeCombinedEnhancer31(config)
        var offset = EnhancerDsp.interpolate31AtFrequency(combined31, frequencyHz).toFloat()
        if (config.dialogueEnhancerEnabled && config.dialogueAmount == 0 && config.dialogueGain > 0 && frequencyHz in 1000.0..4000.0) {
            offset += (config.dialogueGain * 0.75f).coerceAtLeast(2.2f)
        }
        return offset
    }

    fun computeCurveWithAutoHeadroom(
        freqs: List<Float>,
        baseGains: List<Float>,
        config: EnhancerConfig,
        ceilingDb: Float
    ): Pair<List<Float>, Float> {
        val raw = freqs.indices.map { i ->
            val f = freqs[i].toDouble()
            val off = computeTotalEnhancerOffsetDb(f, config)
            baseGains.getOrElse(i) { 0f } + off
        }
        val maxVal = raw.maxOrNull() ?: 0f
        val trim = if (maxVal > ceilingDb) maxVal - ceilingDb else 0f
        val effective = raw.map { (it - trim).coerceIn(-15.0f, ceilingDb + 0.5f) }
        return effective to trim
    }
}

object EnhancerDsp {

    val IEQ_FREQS_20: IntArray = intArrayOf(
        47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
        2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
    )

    val IEQ_DETAILED_Q4: IntArray = intArrayOf(
        150, 142, 188, 216, 189, 195, 202, 199, 210, 225,
        230, 236, 235, 235, 214, 165, 112, 49, -24, -217
    )

    val IEQ_BALANCED_Q4: IntArray = intArrayOf(
        157, 167, 218, 218, 203, 188, 192, 192, 205, 213,
        218, 209, 193, 159, 134, 85, 20, -63, -161, -378
    )

    val IEQ_WARM_Q4: IntArray = intArrayOf(
        114, 146, 183, 169, 170, 128, 103, 90, 98, 126,
        127, 140, 96, 85, 80, 66, 38, -32, -132, -275
    )

    val IEQ_OFF_Q4: IntArray = IntArray(20) { 0 }

    const val VIRTUAL_BASS_SRC_LOW_HZ: Double = 30.0
    const val VIRTUAL_BASS_SRC_HIGH_HZ: Double = 90.0
    const val VIRTUAL_BASS_DST_LOW_HZ: Double = 75.0
    const val VIRTUAL_BASS_DST_HIGH_HZ: Double = 270.0
    const val VIRTUAL_BASS_OVERALL_GAIN_DB: Double = -4.5
    const val VIRTUAL_BASS_SLOPE_GAIN_DB_PER_OCT: Double = -1.5
    val VIRTUAL_BASS_MIX_FREQS_HZ: Pair<Double, Double> = 90.0 to 469.0
    val VIRTUAL_BASS_HARMONIC_GAINS_DB: Triple<Double, Double, Double> = Triple(-6.0, -3.5, -5.0)

    const val MOVIE_REVERB_SUPPRESSION_AMOUNT: Int = 2
    const val MOVIE_VIRTUALIZER_AZIMUTH_DEG: Int = 35
    const val MOVIE_VIRTUALIZER_DIRECT_Q15: Int = 32568
    const val MOVIE_VIRTUALIZER_CROSS_Q15: Int = 11164
    const val MOVIE_VIRTUALIZER_REFL_Q15: Int = 5090
    const val MOVIE_VIRTUALIZER_ITD_MS: Double = 0.3022
    const val MOVIE_VIRTUALIZER_ITD_SAMPLES_48K: Int = 14
    const val MOVIE_VIRTUALIZER_SHADOW_LPF_HZ: Double = 850.0
    const val MOVIE_DIALOGUE_AMOUNT: Int = 4
    const val MOVIE_DIALOGUE_BOOST_DB: Double = 2.50
    const val MOVIE_VOLUME_LEVELER_IN_LUFS: Double = -16.0
    const val MOVIE_VOLUME_LEVELER_OUT_LUFS: Double = -16.0
    const val MOVIE_VOLMAX_BOOST_HEADPHONE_DB: Double = 2.0
    const val MOVIE_VOLMAX_BOOST_SPEAKER_DB: Double = 4.0

    fun ieqTarget20Db(
        preset: IeqPreset,
        custom10Db: List<Float> = List(10) { 0f }
    ): DoubleArray {
        return when (preset) {
            IeqPreset.OFF -> DoubleArray(20) { 0.0 }
            IeqPreset.DETAILED -> DoubleArray(20) { i -> IEQ_DETAILED_Q4[i] / 16.0 }
            IeqPreset.BALANCED -> DoubleArray(20) { i -> IEQ_BALANCED_Q4[i] / 16.0 }
            IeqPreset.WARM -> DoubleArray(20) { i -> IEQ_WARM_Q4[i] / 16.0 }
            IeqPreset.CUSTOM -> {
                val safe10 = DoubleArray(10) { idx ->
                    custom10Db.getOrElse(idx) { 0f }.toDouble().coerceIn(-12.0, 12.0)
                }
                interpolate10To20Bands(safe10)
            }
        }
    }

    fun ieqDefaultCustom10Db(preset: IeqPreset = IeqPreset.BALANCED): List<Float> {
        val basePreset = if (preset == IeqPreset.OFF || preset == IeqPreset.CUSTOM) {
            IeqPreset.BALANCED
        } else {
            preset
        }
        val target20 = ieqTarget20Db(basePreset)
        val freqs20 = DoubleArray(20) { IEQ_FREQS_20[it].toDouble() }
        return Dsp.F_USER.map { fUser ->
            val v = logLinearInterp(freqs20, target20, fUser)
            ((v * 10.0).roundToInt() / 10.0).toFloat()
        }
    }

    private fun interpolate10To20Bands(gains10: DoubleArray): DoubleArray {
        val out = DoubleArray(20)
        for (i in 0 until 20) {
            out[i] = logLinearInterp(Dsp.F_USER, gains10, IEQ_FREQS_20[i].toDouble())
        }
        return out
    }

    private fun logLinearInterp(xsHz: DoubleArray, ysDb: DoubleArray, fHz: Double): Double {
        if (fHz <= xsHz.first()) return ysDb.first()
        if (fHz >= xsHz.last()) return ysDb.last()
        val logF = ln(fHz)
        var idx = 0
        for (i in 0 until xsHz.size - 1) {
            if (fHz >= xsHz[i] && fHz <= xsHz[i + 1]) {
                idx = i
                break
            }
        }
        val x0 = ln(xsHz[idx])
        val x1 = ln(xsHz[idx + 1])
        val t = if (abs(x1 - x0) < 1e-9) 0.0 else ((logF - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        return ysDb[idx] + t * (ysDb[idx + 1] - ysDb[idx])
    }

    fun computeIeq31(config: EnhancerConfig): DoubleArray {
        if (config.ieqPreset == IeqPreset.OFF) return DoubleArray(Dsp.F_DP.size)
        val alpha = (config.ieqAmount.coerceIn(0, 16) / 16.0)
        if (alpha <= 1e-6) return DoubleArray(Dsp.F_DP.size)

        val target20 = ieqTarget20Db(config.ieqPreset, config.ieqCustom10Db)
        val freqs20 = DoubleArray(20) { IEQ_FREQS_20[it].toDouble() }
        val meanTarget = target20.TakeMidrangeMean()

        return DoubleArray(Dsp.F_DP.size) { k ->
            val f = Dsp.F_DP[k]
            val raw = logLinearInterp(freqs20, target20, f) - meanTarget * 0.45
            (alpha * raw).coerceIn(-6.0, 6.0)
        }
    }

    private fun DoubleArray.TakeMidrangeMean(): Double {
        var sum = 0.0
        val start = 3
        val end = 13
        for (i in start..end) sum += this[i]
        return sum / (end - start + 1)
    }

    fun computeDialogueEnhancer31(config: EnhancerConfig): DoubleArray {
        if (!config.dialogueEnhancerEnabled) return DoubleArray(Dsp.F_DP.size)
        val amt = (config.dialogueAmount.coerceIn(0, 16) / 16.0)
        if (amt <= 1e-6) return DoubleArray(Dsp.F_DP.size)
        val duck = (config.dialogueDucking.coerceIn(0, 16) / 16.0)

        val fSibilance = 6800.0
        val sibilanceCutDb = -2.2 * amt
        val mudCutDb = -3.0 * amt * (0.45 + 0.55 * duck)

        return DoubleArray(Dsp.F_DP.size) { k ->
            val f = Dsp.F_DP[k]
            val bodyPeak = AutoEq.biquadDb(
                type = "PK",
                fc = 1150.0,
                gain = 3.2 * amt,
                q = 1.15,
                f = f
            )
            val presencePeak = AutoEq.biquadDb(
                type = "PK",
                fc = 2650.0,
                gain = 4.6 * amt,
                q = 1.25,
                f = f
            )
            val chestClarity = AutoEq.biquadDb(
                type = "PK",
                fc = 420.0,
                gain = 1.4 * amt,
                q = 1.40,
                f = f
            )
            val lowMudCut = AutoEq.biquadDb(
                type = "LSC",
                fc = 145.0,
                gain = mudCutDb,
                q = 0.75,
                f = f
            )
            val deEsser = AutoEq.biquadDb(
                type = "PK",
                fc = fSibilance,
                gain = sibilanceCutDb,
                q = 2.6,
                f = f
            )
            (bodyPeak + presencePeak + chestClarity + lowMudCut + deEsser).coerceIn(-6.0, 7.5)
        }
    }

    fun computeBassEnhancer31(config: EnhancerConfig): DoubleArray {
        val out = DoubleArray(Dsp.F_DP.size)
        if (!config.bassEnhancerEnabled && !config.virtualBassEnabled) return out

        if (config.bassEnhancerEnabled) {
            val strength01 = (config.bassEnhancerStrength.coerceIn(0, 100) / 100.0)
            val subGain = 6.2 * strength01
            val punchGain = 3.8 * strength01
            val mudTrim = -1.6 * strength01
            for (k in out.indices) {
                val f = Dsp.F_DP[k]
                out[k] += AutoEq.biquadDb("PK", 62.0, subGain, 0.85, f) +
                    AutoEq.biquadDb("PK", 112.0, punchGain, 1.10, f) +
                    AutoEq.biquadDb("PK", 260.0, mudTrim, 1.45, f)
            }
        }

        if (config.virtualBassEnabled) {
            val h2Lin = 10.0.pow(VIRTUAL_BASS_HARMONIC_GAINS_DB.first / 20.0)
            val h3Lin = 10.0.pow(VIRTUAL_BASS_HARMONIC_GAINS_DB.second / 20.0)
            val h4Lin = 10.0.pow(VIRTUAL_BASS_HARMONIC_GAINS_DB.third / 20.0)
            val overallScale = 10.0.pow(VIRTUAL_BASS_OVERALL_GAIN_DB / 20.0)

            val h2BoostDb = 6.8 * h2Lin * overallScale
            val h3BoostDb = 7.4 * h3Lin * overallScale
            val h4BoostDb = 5.6 * h4Lin * overallScale

            for (k in out.indices) {
                val f = Dsp.F_DP[k]
                val subProtect = AutoEq.biquadDb("LSC", 38.0, -2.2, 0.75, f)
                val h2Band = AutoEq.biquadDb("PK", 115.0, h2BoostDb, 1.20, f)
                val h3Band = AutoEq.biquadDb("PK", 175.0, h3BoostDb, 1.35, f)
                val h4Band = AutoEq.biquadDb("PK", 235.0, h4BoostDb, 1.55, f)
                val highRoll = AutoEq.biquadDb("PK", 469.0, -0.8, 1.4, f)
                out[k] += subProtect + h2Band + h3Band + h4Band + highRoll
            }
        }

        for (k in out.indices) {
            out[k] = out[k].coerceIn(-6.0, 9.5)
        }
        return out
    }

    fun stereoWideningWidthScalar(amount: Int): Double {
        if (amount <= 0) return 1.0
        return 1.0 + (amount.coerceIn(0, 103) / 64.0)
    }

    fun stereoWideningSideGainDb(amount: Int): Double {
        val w = stereoWideningWidthScalar(amount)
        return 20.0 * kotlin.math.log10(w)
    }

    fun interpolate31AtFrequency(curve31: DoubleArray, fHz: Double): Double {
        return logLinearInterp(Dsp.F_DP, curve31, fHz)
    }

    fun computeStereoWidening31(mode: StereoWideningMode): DoubleArray {
        val out = DoubleArray(Dsp.F_DP.size)
        if (mode == StereoWideningMode.NO_EFFECT) return out
        val stageScale = when (mode) {
            StereoWideningMode.NO_EFFECT -> 0.0
            StereoWideningMode.NEAR -> 0.48
            StereoWideningMode.MIDDLE -> 0.76
            StereoWideningMode.FAR -> 1.00
        }
        for (k in out.indices) {
            val f = Dsp.F_DP[k]
            val warmthStage = AutoEq.biquadDb("PK", 165.0, 1.35 * stageScale, 1.15, f)
            val headShadowCrossfeed = AutoEq.biquadDb("PK", 850.0, -1.65 * stageScale, 1.35, f)
            val pinnaWidth = AutoEq.biquadDb("PK", 3200.0, 2.75 * stageScale, 1.05, f)
            val spatialAir = AutoEq.biquadDb("HSC", 7800.0, 2.40 * stageScale, 0.72, f)
            out[k] = warmthStage + headShadowCrossfeed + pinnaWidth + spatialAir
        }
        return out
    }

    fun computeStereoWideningLRDelta31(
        config: EnhancerConfig,
        midSideWidth: Float = 1.0f
    ): Pair<DoubleArray, DoubleArray> {
        val deltaL = DoubleArray(Dsp.F_DP.size)
        val deltaR = DoubleArray(Dsp.F_DP.size)
        val modeStrength = when (config.stereoWideningMode) {
            StereoWideningMode.NO_EFFECT -> 0.0
            StereoWideningMode.NEAR -> 0.48
            StereoWideningMode.MIDDLE -> 0.78
            StereoWideningMode.FAR -> 1.00
        }
        val profileSpatialStrength = when (config.profile) {
            AudioProfile.EFFECT_3D -> 0.65
            AudioProfile.MOVIE -> 0.45
            AudioProfile.LIVE_MUSIC -> 0.50
            AudioProfile.DYNAMIC -> 0.20
            else -> 0.0
        }
        val manualWidthStrength = ((midSideWidth - 1.0f).coerceIn(0f, 1f)).toDouble() * 0.65
        val totalStrength = max(modeStrength, max(profileSpatialStrength, manualWidthStrength)).coerceIn(0.0, 1.0)
        if (totalStrength <= 1e-4) return deltaL to deltaR

        val maxDichoticDb = 4.4 * totalStrength
        for (k in Dsp.F_DP.indices) {
            val f = Dsp.F_DP[k]
            if (f < 200.0) {
                deltaL[k] = 0.0
                deltaR[k] = 0.0
            } else {
                val xoverWeight = ((ln(f / 200.0) / ln(400.0 / 200.0)).coerceIn(0.0, 1.0))
                val combWave = sin((k - 10) * 1.08 + 0.42)
                val pinnaAsym = AutoEq.biquadDb("PK", 1950.0, 1.8 * totalStrength, 1.6, f) -
                    AutoEq.biquadDb("PK", 3550.0, 1.8 * totalStrength, 1.6, f)
                val diff = (combWave * maxDichoticDb + pinnaAsym) * xoverWeight * AutoEq.taper(f)
                val energyComp = 0.35 * abs(diff)
                deltaL[k] = (diff + energyComp).coerceIn(-5.5, 6.0)
                deltaR[k] = (-diff + energyComp).coerceIn(-5.5, 6.0)
            }
        }
        return deltaL to deltaR
    }

    fun reverbOverSubtractionGamma(amount: Int): Double {
        val clamped = amount.coerceIn(0, 9)
        if (clamped == 0) return 0.0
        return 0.50 + 0.35 * clamped
    }

    fun reverbMinGainFloorDb(amount: Int): Double {
        val clamped = amount.coerceIn(0, 9)
        return -6.0 * clamped
    }

    fun reverbWienerGainLinear(
        amount: Int,
        lateReverbPsd: Double,
        noisyInputPsd: Double
    ): Double {
        val clamped = amount.coerceIn(0, 9)
        if (clamped == 0 || noisyInputPsd <= 1e-12) return 1.0
        val gamma = reverbOverSubtractionGamma(clamped)
        val gMinDb = reverbMinGainFloorDb(clamped)
        val gMinLin = 10.0.pow(gMinDb / 20.0)
        val ratio = (lateReverbPsd / max(1e-12, noisyInputPsd)).coerceIn(0.0, 1.0)
        val rawGain = 1.0 - gamma * sqrt(ratio)
        return max(gMinLin, rawGain).coerceIn(gMinLin, 1.0)
    }

    fun computeReduceReverb31(amount: Int): DoubleArray {
        val out = DoubleArray(Dsp.F_DP.size)
        val clamped = amount.coerceIn(0, 9)
        if (clamped == 0) return out
        val gamma = reverbOverSubtractionGamma(clamped)
        val norm = (clamped / 9.0).coerceIn(0.0, 1.0)
        for (k in out.indices) {
            val f = Dsp.F_DP[k]
            val muddyRoomCut = AutoEq.biquadDb("PK", 280.0, -2.4 * norm, 1.15, f)
            val boxyDecayCut = AutoEq.biquadDb("PK", 520.0, -1.8 * norm, 1.30, f)
            val directClarity = AutoEq.biquadDb("PK", 2150.0, 1.15 * (gamma / 3.65), 1.40, f)
            val tailDampShelf = AutoEq.biquadDb("HSC", 7500.0, -1.25 * norm, 0.75, f)
            out[k] = (muddyRoomCut + boxyDecayCut + directClarity + tailDampShelf).coerceIn(-5.5, 2.5)
        }
        return out
    }

    fun computeProfile31(profile: AudioProfile): DoubleArray {
        val out = DoubleArray(Dsp.F_DP.size)
        when (profile) {
            AudioProfile.OFF -> return out
            AudioProfile.DYNAMIC -> {
                for (k in out.indices) {
                    val f = Dsp.F_DP[k]
                    out[k] = AutoEq.biquadDb("LSC", 95.0, 3.2, 0.72, f) +
                        AutoEq.biquadDb("PK", 420.0, -1.4, 1.25, f) +
                        AutoEq.biquadDb("PK", 2800.0, 2.4, 1.15, f) +
                        AutoEq.biquadDb("HSC", 8500.0, 2.8, 0.72, f)
                }
            }
            AudioProfile.BALANCED -> {
                for (k in out.indices) {
                    val f = Dsp.F_DP[k]
                    out[k] = AutoEq.biquadDb("LSC", 105.0, 1.4, 0.70, f) +
                        AutoEq.biquadDb("PK", 1600.0, 1.6, 1.10, f) +
                        AutoEq.biquadDb("HSC", 10000.0, 1.0, 0.70, f)
                }
            }
            AudioProfile.EFFECT_3D -> {
                for (k in out.indices) {
                    val f = Dsp.F_DP[k]
                    out[k] = AutoEq.biquadDb("PK", 75.0, 2.6, 0.90, f) +
                        AutoEq.biquadDb("PK", 320.0, -1.8, 1.30, f) +
                        AutoEq.biquadDb("PK", 2200.0, -1.2, 1.50, f) +
                        AutoEq.biquadDb("PK", 4800.0, 3.4, 1.20, f) +
                        AutoEq.biquadDb("HSC", 10500.0, 3.8, 0.70, f)
                }
            }
            AudioProfile.LIVE_MUSIC -> {
                for (k in out.indices) {
                    val f = Dsp.F_DP[k]
                    out[k] = AutoEq.biquadDb("LSC", 85.0, 2.8, 0.75, f) +
                        AutoEq.biquadDb("PK", 220.0, 1.5, 1.15, f) +
                        AutoEq.biquadDb("PK", 1250.0, 2.2, 1.10, f) +
                        AutoEq.biquadDb("PK", 3600.0, 2.6, 1.25, f) +
                        AutoEq.biquadDb("HSC", 9500.0, 2.4, 0.72, f)
                }
            }
            AudioProfile.MOVIE -> {
                for (k in out.indices) {
                    val f = Dsp.F_DP[k]
                    val speechBand = AutoEq.biquadDb("PK", 1850.0, MOVIE_DIALOGUE_BOOST_DB, 0.92, f)
                    val articulationBand = AutoEq.biquadDb("PK", 3150.0, 2.15, 1.20, f)
                    val hrtfShadowComp = AutoEq.biquadDb("PK", 850.0, 0.85, 1.10, f)
                    val surroundAir = AutoEq.biquadDb("HSC", 7200.0, 1.65, 0.74, f)
                    val deReverbLowMid = AutoEq.biquadDb("PK", 290.0, -1.35, 1.25, f)
                    val subCinema = AutoEq.biquadDb("LSC", 72.0, 1.80, 0.72, f)
                    out[k] = speechBand + articulationBand + hrtfShadowComp + surroundAir + deReverbLowMid + subCinema
                }
            }
        }
        return out
    }

    fun computeCombinedEnhancer31(config: EnhancerConfig): DoubleArray {
        val ieq = computeIeq31(config)
        val dlg = computeDialogueEnhancer31(config)
        val bass = computeBassEnhancer31(config)
        val prof = computeProfile31(config.profile)
        val wide = computeStereoWidening31(config.stereoWideningMode)
        val deRev = computeReduceReverb31(config.reduceReverbAmount)
        return DoubleArray(Dsp.F_DP.size) { k ->
            (ieq[k] + dlg[k] + bass[k] + prof[k] + wide[k] + deRev[k]).coerceIn(-12.0, 12.0)
        }
    }

    fun buildHardwareDax96BytePayload(
        gains20Q4: IntArray,
        ieqPresetIndex: Int = 1
    ): ByteArray {
        val buf = ByteArray(96)
        fun writeInt32LE(offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        writeInt32LE(0, 1)
        writeInt32LE(4, 20)
        writeInt32LE(8, ieqPresetIndex)
        writeInt32LE(12, 0)
        for (i in 0 until 20) {
            val g = gains20Q4.getOrElse(i) { 0 }.coerceIn(-960, 384)
            writeInt32LE(16 + i * 4, g)
        }
        return buf
    }
}

class NestedAllPassDecorrelator(sampleRate: Int = 48000) {
    private var poleRadius: Float = 0.65f
    private var delaySamples: Int = 0
    private val maxDelaySamples: Int = 64
    private val delayLine = FloatArray(maxDelaySamples)
    private var writeIdx: Int = 0

    fun configure(mode: StereoWideningMode, sampleRate: Int = 48000) {
        poleRadius = 0.65f
        val scaledDelay = ((mode.delaySamples48k.toDouble() * sampleRate) / 48000.0).roundToInt()
        delaySamples = scaledDelay.coerceIn(0, maxDelaySamples - 1)
    }

    fun reset() {
        delayLine.fill(0f)
        writeIdx = 0
    }

    fun processSideSample(sideIn: Float): Float {
        val d = delaySamples
        if (d <= 0) return sideIn
        val readIdx = (writeIdx - d + maxDelaySamples) % maxDelaySamples
        val delayed = delayLine[readIdx]
        val v = sideIn + poleRadius * delayed
        val y = -poleRadius * v + delayed
        delayLine[writeIdx] = v
        writeIdx = (writeIdx + 1) % maxDelaySamples
        return y
    }
}

class BinauralHrtfVirtualizer(sampleRate: Int = 48000) {
    private val gDirect: Float = 32568f / 32768f
    private val gCross: Float = 11164f / 32768f
    private val gRefl: Float = 5090f / 32768f
    private var itdSamples: Int = ((0.3022 * sampleRate) / 1000.0).roundToInt().coerceIn(1, 32)
    private var shadowBeta: Float = (1.0 - exp(-2.0 * PI * 850.0 / sampleRate.toDouble())).toFloat()

    private val delayBufL = FloatArray(64)
    private val delayBufR = FloatArray(64)
    private var writeIdx = 0
    private var lpfStateL = 0f
    private var lpfStateR = 0f

    fun reset() {
        delayBufL.fill(0f)
        delayBufR.fill(0f)
        writeIdx = 0
        lpfStateL = 0f
        lpfStateR = 0f
    }

    fun processPair(leftIn: Float, rightIn: Float): Pair<Float, Float> {
        delayBufL[writeIdx] = leftIn
        delayBufR[writeIdx] = rightIn
        val readIdx = (writeIdx - itdSamples + 64) % 64
        val delayedL = delayBufL[readIdx]
        val delayedR = delayBufR[readIdx]
        writeIdx = (writeIdx + 1) % 64

        lpfStateL += shadowBeta * (delayedL - lpfStateL)
        lpfStateR += shadowBeta * (delayedR - lpfStateR)

        val surroundS = (leftIn - rightIn) * 0.70710677f
        val outL = gDirect * leftIn + gCross * lpfStateR + gRefl * surroundS
        val outR = gDirect * rightIn + gCross * lpfStateL - gRefl * surroundS
        return outL to outR
    }
}

class LateReverbSuppressor(
    private val delayFrames: Int = 12,
    private val earlyToLateScaleB: Float = 0.08f,
    private val rt60Sec: Float = 0.45f,
    private val hopTimeSec: Float = 0.00533f
) {
    private val reverbDecayA: Float = exp(-3.0 * ln(10.0) * hopTimeSec / max(0.05f, rt60Sec)).toFloat()
    private val frameHistory = FloatArray(delayFrames.coerceAtLeast(2))
    private var historyIdx = 0
    private var lateReverbPsd = 0f
    private var smoothedInputPsd = 1e-6f

    fun reset() {
        frameHistory.fill(0f)
        historyIdx = 0
        lateReverbPsd = 0f
        smoothedInputPsd = 1e-6f
    }

    fun computeFrameGain(framePower: Float, amount: Int): Float {
        val clamped = amount.coerceIn(0, 9)
        if (clamped == 0) return 1.0f
        val delayedPower = frameHistory[historyIdx]
        frameHistory[historyIdx] = framePower
        historyIdx = (historyIdx + 1) % frameHistory.size

        lateReverbPsd = reverbDecayA * lateReverbPsd + earlyToLateScaleB * delayedPower
        smoothedInputPsd = 0.85f * smoothedInputPsd + 0.15f * max(1e-9f, framePower)

        return EnhancerDsp.reverbWienerGainLinear(
            amount = clamped,
            lateReverbPsd = lateReverbPsd.toDouble(),
            noisyInputPsd = smoothedInputPsd.toDouble()
        ).toFloat()
    }
}

class HarmonicVirtualBassGenerator(sampleRate: Int = 48000) {
    private var lpState1 = 0.0
    private var lpState2 = 0.0
    private var hpState = 0.0
    private var prevSample = 0.0

    private val alphaLp = 1.0 - exp(-2.0 * PI * 90.0 / sampleRate.toDouble())
    private val alphaHp = exp(-2.0 * PI * 30.0 / sampleRate.toDouble())

    private val gH2 = 10.0.pow(EnhancerDsp.VIRTUAL_BASS_HARMONIC_GAINS_DB.first / 20.0)
    private val gH3 = 10.0.pow(EnhancerDsp.VIRTUAL_BASS_HARMONIC_GAINS_DB.second / 20.0)
    private val gH4 = 10.0.pow(EnhancerDsp.VIRTUAL_BASS_HARMONIC_GAINS_DB.third / 20.0)
    private val overallGain = 10.0.pow(EnhancerDsp.VIRTUAL_BASS_OVERALL_GAIN_DB / 20.0)

    fun processSample(x: Float): Float {
        val d = x.toDouble()
        hpState = alphaHp * (hpState + d - prevSample)
        prevSample = d
        lpState1 += alphaLp * (hpState - lpState1)
        lpState2 += alphaLp * (lpState1 - lpState2)

        val sub = lpState2.coerceIn(-1.0, 1.0)
        val h2 = (2.0 * sub * sub - 1.0) * abs(sub)
        val h3 = 4.0 * sub * sub * sub - 3.0 * sub
        val h4 = (8.0 * sub.pow(4.0) - 8.0 * sub * sub + 1.0) * abs(sub)

        val harmonics = overallGain * (gH2 * h2 + gH3 * h3 + gH4 * h4)
        return (d + 0.35 * harmonics).coerceIn(-0.99, 0.99).toFloat()
    }

    fun reset() {
        lpState1 = 0.0
        lpState2 = 0.0
        hpState = 0.0
        prevSample = 0.0
    }
}
