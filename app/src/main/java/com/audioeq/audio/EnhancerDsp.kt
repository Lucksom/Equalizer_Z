package com.audioeq.audio

import kotlin.math.PI
import kotlin.math.abs
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
    BALANCED("ieq_balanced", 2, "Balanced", "20-band Q4 reference target (μ_hp=0.375, μ_spk=0.250)", 6),
    WARM("ieq_warm", 3, "Warm", "Rich low-mid body & smooth highs (+2.9 dB)", 10),
    CUSTOM("ieq_custom", 4, "Customized", "User-editable 10-band target curve", 10);

    companion object {
        fun fromId(id: String?): IeqPreset = entries.find { it.id == id } ?: OFF
        fun fromIndex(idx: Int): IeqPreset = entries.find { it.index == idx } ?: OFF
    }
}

enum class ThreeDAudioMode(
    val id: String,
    val index: Int,
    val modeName: String,
    val azimuthDeg: Int,
    val gDirectQ15: Int,
    val gDirectLinear: Double,
    val gCrossQ15: Int,
    val gCrossLinear: Double,
    val gReflQ15: Int,
    val gReflLinear: Double,
    val itdDelaySamples48k: Int,
    val itdDelayMs: Double,
    val shadowCutoffHz: Int,
    val allpassFilterDelay: Int
) {
    NO_EFFECT(
        id = "3d_off",
        index = 0,
        modeName = "No effect",
        azimuthDeg = 0,
        gDirectQ15 = 32768,
        gDirectLinear = 1.0000,
        gCrossQ15 = 0,
        gCrossLinear = 0.0000,
        gReflQ15 = 0,
        gReflLinear = 0.0000,
        itdDelaySamples48k = 0,
        itdDelayMs = 0.000,
        shadowCutoffHz = 0,
        allpassFilterDelay = 0
    ),
    NEAR(
        id = "3d_near",
        index = 1,
        modeName = "Near",
        azimuthDeg = 16,
        gDirectQ15 = 32568,
        gDirectLinear = 0.9939,
        gCrossQ15 = 8192,
        gCrossLinear = 0.2500,
        gReflQ15 = 3276,
        gReflLinear = 0.1000,
        itdDelaySamples48k = 7,
        itdDelayMs = 0.141,
        shadowCutoffHz = 850,
        allpassFilterDelay = 10
    ),
    MIDDLE(
        id = "3d_middle",
        index = 2,
        modeName = "Middle (Movie)",
        azimuthDeg = 35,
        gDirectQ15 = 32568,
        gDirectLinear = 0.9939,
        gCrossQ15 = 11164,
        gCrossLinear = 0.3407,
        gReflQ15 = 5090,
        gReflLinear = 0.1553,
        itdDelaySamples48k = 14,
        itdDelayMs = 0.302,
        shadowCutoffHz = 850,
        allpassFilterDelay = 22
    ),
    FAR(
        id = "3d_far",
        index = 3,
        modeName = "Far (Music/Custom)",
        azimuthDeg = 103,
        gDirectQ15 = 32568,
        gDirectLinear = 0.9939,
        gCrossQ15 = 11164,
        gCrossLinear = 0.3407,
        gReflQ15 = 5090,
        gReflLinear = 0.1553,
        itdDelaySamples48k = 34,
        itdDelayMs = 0.707,
        shadowCutoffHz = 850,
        allpassFilterDelay = 36
    );

    companion object {
        fun fromId(id: String?): ThreeDAudioMode = entries.find { it.id == id } ?: NO_EFFECT
        fun fromModeName(name: String?): ThreeDAudioMode = entries.find { it.modeName == name } ?: NO_EFFECT
    }
}

enum class AudioProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String,
    val virtualizerStrengthPermille: Int,
    val bassBoostStrengthPermille: Int,
    val threeDAudioMode: ThreeDAudioMode
) {
    OFF(
        id = "profile_off",
        title = "Off",
        subtitle = "Pure 10-band EQ & enhancer chain without profile shaping",
        badgeLabel = "Direct",
        virtualizerStrengthPermille = 0,
        bassBoostStrengthPermille = 0,
        threeDAudioMode = ThreeDAudioMode.NO_EFFECT
    ),
    DYNAMIC(
        id = "profile_dynamic",
        title = "Dynamic",
        subtitle = "Media Intelligence steering · Balanced IEQ (μ=0.375) · Dialogue (4/16) · Adaptive 35°",
        badgeLabel = "Dynamic",
        virtualizerStrengthPermille = 350,
        bassBoostStrengthPermille = 450,
        threeDAudioMode = ThreeDAudioMode.MIDDLE
    ),
    BALANCED(
        id = "profile_balanced",
        title = "Balanced",
        subtitle = "20-band Q4 reference curve (μ_hp=0.375, μ_spk=0.250, ±6 dB clamp)",
        badgeLabel = "Balanced",
        virtualizerStrengthPermille = 0,
        bassBoostStrengthPermille = 200,
        threeDAudioMode = ThreeDAudioMode.NO_EFFECT
    ),
    EFFECT_3D(
        id = "profile_3d",
        title = "3D",
        subtitle = "Binaural HRTF crossfeed (G_dir 0.9939, G_cross 0.3407) · Woodworth ITD · 850 Hz shadow",
        badgeLabel = "3D Spatial",
        virtualizerStrengthPermille = 850,
        bassBoostStrengthPermille = 400,
        threeDAudioMode = ThreeDAudioMode.FAR
    );

    companion object {
        fun fromId(id: String?): AudioProfile = entries.find { it.id == id } ?: OFF
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
    val profile: AudioProfile = AudioProfile.OFF
) {
    val bassBoostStrengthPermille: Short
        get() = (bassEnhancerStrength.coerceIn(0, 100) * 10).toShort()

    fun isAnyActive(): Boolean =
        ieqPreset != IeqPreset.OFF ||
            dialogueEnhancerEnabled ||
            bassEnhancerEnabled ||
            virtualBassEnabled ||
            profile != AudioProfile.OFF
}

data class DynamicProfileSubSystemSpec(
    val subSystem: String,
    val steeringTag: String,
    val enabled: Int,
    val classifierInput: String,
    val adaptationFormula: String
)

data class BalancedIeqBandSpec(
    val bandIndex: Int,
    val frequencyHz: Int,
    val rawQ4: Int,
    val targetGainDb: Double,
    val adaptationFactorHp: Double,
    val adaptationFactorSpk: Double
)

object EnhancerMath {
    val IEQ_FREQS_20: IntArray get() = EnhancerDsp.IEQ_FREQS_20
    val IEQ_DETAILED_20_RAW: IntArray get() = EnhancerDsp.IEQ_DETAILED_Q4
    val IEQ_BALANCED_20_RAW: IntArray get() = EnhancerDsp.IEQ_BALANCED_Q4
    val IEQ_WARM_20_RAW: IntArray get() = EnhancerDsp.IEQ_WARM_Q4

    val UUID_BASS_BOOST_MZ: java.util.UUID = java.util.UUID.fromString("42b10000-0010-0000-0000-0002a5d5c51b")

    const val BASS_MAX_31_DB = 6.0f
    const val BASS_MAX_62_DB = 6.0f
    const val BASS_MAX_125_DB = 4.5f
    const val BASS_MAX_250_DB = 3.0f

    const val BASS_ENHANCER_BOOST_RAW = 96
    const val BASS_ENHANCER_BOOST_DB = 96f / 16f
    const val BASS_ENHANCER_CUTOFF_HZ = 504f
    const val BASS_EXTRACTION_CUTOFF_HZ = 200f
    const val VIRTUAL_BASS_H2_DB = -2.0f
    const val VIRTUAL_BASS_H3_DB = -9.0f
    const val VIRTUAL_BASS_H4_DB = -12.0f

    fun bassEnhancerBandBoostDb(
        bandIndex: Int,
        strengthPct: Int,
        virtualBassEnabled: Boolean
    ): Float {
        val factor = (strengthPct.coerceIn(0, 100) / 100.0f)
        if (factor <= 0f) return 0f
        val raw = when (bandIndex) {
            0 -> BASS_MAX_31_DB * factor
            1 -> BASS_MAX_62_DB * factor
            2 -> (BASS_MAX_125_DB + (if (virtualBassEnabled) 1.5f else 0.0f)) * factor
            3 -> (BASS_MAX_250_DB + (if (virtualBassEnabled) 1.2f else 0.0f)) * factor
            else -> 0f
        }
        return raw.coerceIn(0f, 6.0f)
    }

    const val HEAD_RADIUS_METERS: Double = 0.0875
    const val SPEED_OF_SOUND_MPS: Double = 343.0
    const val CRANIAL_SHADOW_CUTOFF_HZ: Double = 850.0

    /**
     * Woodworth Interaural Time Difference (ITD) in milliseconds:
     * tau(theta) = (r / c) * (theta_rad + sin(theta_rad))
     */
    fun computeWoodworthItdMs(azimuthDeg: Int): Double {
        if (azimuthDeg <= 0) return 0.0
        val thetaRad = azimuthDeg.toDouble() * PI / 180.0
        val tauSec = (HEAD_RADIUS_METERS / SPEED_OF_SOUND_MPS) * (thetaRad + sin(thetaRad))
        return tauSec * 1000.0
    }

    /**
     * Discrete ITD delay samples D = round(tau * fs) at given sample rate (default 48000 Hz).
     */
    fun computeWoodworthItdSamples(azimuthDeg: Int, sampleRate: Int = 48000): Int {
        if (azimuthDeg <= 0) return 0
        val tauSec = computeWoodworthItdMs(azimuthDeg) / 1000.0
        return (tauSec * sampleRate).roundToInt()
    }

    /**
     * Recursive first-order low-pass filter coefficient beta for cranial shadowing (fc = 850 Hz):
     * beta = 1 - exp(-2 * pi * fc / fs)
     */
    fun computeCranialShadowBeta(cutoffHz: Double = CRANIAL_SHADOW_CUTOFF_HZ, sampleRate: Int = 48000): Double {
        if (cutoffHz <= 0.0 || sampleRate <= 0) return 1.0
        return 1.0 - exp(-2.0 * PI * cutoffHz / sampleRate.toDouble())
    }

    /**
     * Q15 to linear amplitude decoding: Linear Gain = Value / 32768.0
     */
    fun decodeQ15Linear(q15Value: Int): Double = q15Value / 32768.0

    /**
     * Q4 to decibel decoding: Gain_dB = Raw_Integer / 16.0
     */
    fun decodeQ4Db(rawQ4: Int): Double = rawQ4 / 16.0

    /**
     * Dynamic Profile (id="0") Media Intelligence Adaptive Virtualizer Steering:
     * Gain_virt = P_movie * 1.0 + P_music * 0.35 + P_speech * 0.0
     * Cuts to dry center (0.0) when speech dominant (P_speech > 0.7),
     * expands to full binaural depth (1.0) when movie/action dominant (P_movie > 0.6).
     */
    fun computeDynamicMiVirtualizerGain(
        pMovie: Double,
        pMusic: Double,
        pSpeech: Double
    ): Double {
        if (pSpeech > 0.7) return 0.0
        if (pMovie > 0.6) return 1.0
        return (pMovie * 1.0 + pMusic * 0.35 + pSpeech * 0.0).coerceIn(0.0, 1.0)
    }

    /**
     * Dynamic Profile (id="0") Media Intelligence Dynamic Leveler / DRC Steering:
     * Speech: tau_att = 20 ms, tau_rel = 200 ms
     * Music: tau_att = 150 ms, tau_rel = 800 ms
     * Formula: tau_att = P_speech * 20ms + (1 - P_speech) * 150ms
     */
    fun computeDynamicMiLevelerTimeConstantsMs(pSpeech: Double): Pair<Double, Double> {
        val ps = pSpeech.coerceIn(0.0, 1.0)
        val tauAttMs = ps * 20.0 + (1.0 - ps) * 150.0
        val tauRelMs = ps * 200.0 + (1.0 - ps) * 800.0
        return tauAttMs to tauRelMs
    }

    /**
     * Dynamic Profile (id="0") Media Intelligence Surround Compressor Steering:
     * Ratio = 1.0 + 1.5 * P_surround_energy
     */
    fun computeDynamicMiSurroundCompressorRatio(pSurroundEnergy: Double): Double {
        return 1.0 + 1.5 * pSurroundEnergy.coerceIn(0.0, 1.0)
    }

    /**
     * Balanced IEQ (<preset id="2" name="balanced" type="ieq">) error-feedback steering:
     * E(k, t) = T_bal(k) - S_bar_in(k, t)
     * DeltaG(k, t) = clamp(mu * E(k, t), -6.0 dB, +6.0 dB)
     * Headphone (ieq-amount = 6): mu = 6/16 = 0.375
     * Speaker (ieq-amount = 4): mu = 4/16 = 0.250
     */
    fun computeBalancedIeqSteeringGainDb(
        bandIndex: Int,
        inputSpectralDb: Double,
        isSpeaker: Boolean = false
    ): Double {
        val idx = bandIndex.coerceIn(0, 19)
        val targetDb = EnhancerDsp.IEQ_BALANCED_Q4[idx] / 16.0
        val errorDb = targetDb - inputSpectralDb
        val mu = if (isSpeaker) EnhancerDsp.BALANCED_ADAPTATION_FACTOR_SPK else EnhancerDsp.BALANCED_ADAPTATION_FACTOR_HP
        return (mu * errorDb).coerceIn(-6.0, 6.0)
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

    const val BALANCED_ADAPTATION_FACTOR_HP: Double = 0.375
    const val BALANCED_ADAPTATION_FACTOR_SPK: Double = 0.250
    const val BALANCED_DYNAMIC_CLAMP_DB: Double = 6.0

    const val DYNAMIC_PROFILE_ID: Int = 0
    const val DYNAMIC_IEQ_PRESET: Int = 2
    const val DYNAMIC_IEQ_AMOUNT_HP: Int = 6
    const val DYNAMIC_IEQ_AMOUNT_SPK: Int = 4
    const val DYNAMIC_DIALOGUE_AMOUNT: Int = 4
    const val DYNAMIC_DIALOGUE_DUCKING: Int = 0
    const val DYNAMIC_VOLUME_LEVELER_AMOUNT: Int = 0
    const val DYNAMIC_VOLUME_LEVELER_IN_TARGET_Q4: Int = -256
    const val DYNAMIC_VOLUME_LEVELER_OUT_TARGET_Q4: Int = -256
    val DYNAMIC_VIRTUALIZER_RENDERING_CONFIG: IntArray = intArrayOf(35, 32568, 11164, 5090, 0, 3, 3, 3)

    val THREE_D_MOVIE_RENDERING_CONFIG: IntArray = intArrayOf(35, 32568, 11164, 5090, 0, 3, 3, 3)
    val THREE_D_MUSIC_RENDERING_CONFIG: IntArray = intArrayOf(103, 32568, 11164, 5090, 0, 3, 3, 3)

    val DYNAMIC_PROFILE_SPECS: List<DynamicProfileSubSystemSpec> = listOf(
        DynamicProfileSubSystemSpec(
            subSystem = "Adaptive Virtualizer",
            steeringTag = "mi-adaptive-virtualizer-steering-enable",
            enabled = 1,
            classifierInput = "P_movie, P_music, P_speech",
            adaptationFormula = "Gain_virt = P_movie*1.0 + P_music*0.35 + P_speech*0.0"
        ),
        DynamicProfileSubSystemSpec(
            subSystem = "Intelligent Equalizer",
            steeringTag = "mi-ieq-steering-enable",
            enabled = 1,
            classifierInput = "P_speech, P_music",
            adaptationFormula = "Target = P_music*T_balanced + P_speech*T_dialogue"
        ),
        DynamicProfileSubSystemSpec(
            subSystem = "Dynamic Leveler / DRC",
            steeringTag = "mi-dv-leveler-steering-enable",
            enabled = 1,
            classifierInput = "P_speech, P_transient",
            adaptationFormula = "tau_att = P_speech*20ms + (1-P_speech)*150ms"
        ),
        DynamicProfileSubSystemSpec(
            subSystem = "Surround Compressor",
            steeringTag = "mi-surround-compressor-steering-enable",
            enabled = 1,
            classifierInput = "P_surround_energy",
            adaptationFormula = "Ratio = 1.0 + 1.5 * P_surround_energy"
        )
    )

    val BALANCED_IEQ_CURVE_SPECS: List<BalancedIeqBandSpec> = List(20) { idx ->
        val raw = IEQ_BALANCED_Q4[idx]
        BalancedIeqBandSpec(
            bandIndex = idx,
            frequencyHz = IEQ_FREQS_20[idx],
            rawQ4 = raw,
            targetGainDb = ((raw / 16.0) * 100.0).roundToInt() / 100.0,
            adaptationFactorHp = BALANCED_ADAPTATION_FACTOR_HP,
            adaptationFactorSpk = BALANCED_ADAPTATION_FACTOR_SPK
        )
    }

    const val VIRTUAL_BASS_SRC_LOW_HZ: Double = 30.0
    const val VIRTUAL_BASS_SRC_HIGH_HZ: Double = 90.0
    const val VIRTUAL_BASS_DST_LOW_HZ: Double = 75.0
    const val VIRTUAL_BASS_DST_HIGH_HZ: Double = 270.0
    const val VIRTUAL_BASS_OVERALL_GAIN_DB: Double = -4.5
    const val VIRTUAL_BASS_SLOPE_GAIN_DB_PER_OCT: Double = -1.5
    val VIRTUAL_BASS_MIX_FREQS_HZ: Pair<Double, Double> = 90.0 to 469.0
    val VIRTUAL_BASS_HARMONIC_GAINS_DB: Triple<Double, Double, Double> = Triple(-2.0, -9.0, -12.0)

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
        if (config.ieqPreset == IeqPreset.CUSTOM) {
            return DoubleArray(Dsp.F_DP.size) { k ->
                val f = Dsp.F_DP[k]
                (alpha * logLinearInterp(freqs20, target20, f)).coerceIn(-6.0, 6.0)
            }
        }
        val meanTarget = target20.TakeMidrangeMean()

        return DoubleArray(Dsp.F_DP.size) { k ->
            val f = Dsp.F_DP[k]
            val raw = logLinearInterp(freqs20, target20, f) - meanTarget * 0.65
            (alpha * raw).coerceIn(-0.8, 6.0)
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

        val fSibilance = 7500.0
        val sibilanceCutDb = -0.8 * amt
        val mudCutDb = -1.2 * amt * duck

        return DoubleArray(Dsp.F_DP.size) { k ->
            val f = Dsp.F_DP[k]
            val bodyPeak = AutoEq.biquadDb(
                type = "PK",
                fc = 1100.0,
                gain = 4.2 * amt,
                q = 1.05,
                f = f
            )
            val presencePeak = AutoEq.biquadDb(
                type = "PK",
                fc = 2550.0,
                gain = 6.0 * amt,
                q = 1.10,
                f = f
            )
            val chestClarity = AutoEq.biquadDb(
                type = "PK",
                fc = 420.0,
                gain = 2.2 * amt,
                q = 1.25,
                f = f
            )
            val lowMudCut = AutoEq.biquadDb(
                type = "LSC",
                fc = 130.0,
                gain = mudCutDb,
                q = 0.72,
                f = f
            )
            val deEsser = AutoEq.biquadDb(
                type = "PK",
                fc = fSibilance,
                gain = sibilanceCutDb,
                q = 2.4,
                f = f
            )
            (bodyPeak + presencePeak + chestClarity + lowMudCut + deEsser).coerceIn(-2.0, 8.5)
        }
    }

    fun computeBassEnhancer31(config: EnhancerConfig): DoubleArray {
        if (!config.bassEnhancerEnabled) return DoubleArray(Dsp.F_DP.size)
        val bass10 = DoubleArray(10) { i ->
            EnhancerMath.bassEnhancerBandBoostDb(
                bandIndex = i,
                strengthPct = config.bassEnhancerStrength,
                virtualBassEnabled = config.virtualBassEnabled
            ).toDouble()
        }
        val curve31 = Dsp.renderCurve(bass10)
        return DoubleArray(Dsp.F_DP.size) { k ->
            curve31[k].coerceIn(0.0, 6.0)
        }
    }

    fun interpolate31AtFrequency(curve31: DoubleArray, fHz: Double): Double {
        return logLinearInterp(Dsp.F_DP, curve31, fHz)
    }

    /**
     * Computes dichotic Left/Right spatial stage deltas for 3D Audio and Dynamic profiles
     * using the ThreeDAudioMode specification (Woodworth ITD comb + 850 Hz cranial shadow + pinna cues).
     */
    fun computeProfileSpatialLRDelta31(profile: AudioProfile): Pair<DoubleArray, DoubleArray> {
        val deltaL = DoubleArray(Dsp.F_DP.size)
        val deltaR = DoubleArray(Dsp.F_DP.size)
        val mode = profile.threeDAudioMode
        if (mode == ThreeDAudioMode.NO_EFFECT) return deltaL to deltaR

        val (strength, phaseOffset, compScale) = when (profile) {
            AudioProfile.OFF,
            AudioProfile.BALANCED -> return deltaL to deltaR
            AudioProfile.DYNAMIC -> Triple(0.35, 0.25, 0.62)
            AudioProfile.EFFECT_3D -> Triple(0.95, 0.55, 0.68)
        }
        val crossRatio = mode.gCrossLinear / mode.gDirectLinear.coerceAtLeast(0.1)
        val maxDichoticDb = (4.2 * strength * (0.75 + 0.75 * crossRatio)).coerceIn(0.0, 4.8)

        for (k in Dsp.F_DP.indices) {
            val f = Dsp.F_DP[k]
            if (f < 200.0) {
                deltaL[k] = 0.3 * strength
                deltaR[k] = 0.3 * strength
            } else {
                val shadowCutoff = mode.shadowCutoffHz.toDouble().coerceAtLeast(850.0)
                val xoverWeight = ((ln(f / 200.0) / ln(shadowCutoff / 200.0)).coerceIn(0.0, 1.0))
                val combWave = sin((k - 9) * 1.02 + phaseOffset)
                val pinnaAsym = AutoEq.biquadDb("PK", 1800.0, 1.8 * strength, 1.35, f) -
                    AutoEq.biquadDb("PK", 3800.0, 1.8 * strength, 1.35, f)
                val diff = (combWave * maxDichoticDb + pinnaAsym) * xoverWeight * AutoEq.taper(f)
                val energyComp = compScale * abs(diff)
                deltaL[k] = (diff + energyComp).coerceIn(-2.5, 6.5)
                deltaR[k] = (-diff + energyComp).coerceIn(-2.5, 6.5)
            }
        }
        return deltaL to deltaR
    }

    /**
     * Computes 31-band profile target curve based on the DAX3 specifications:
     * - DYNAMIC (id=0): Media Intelligence steering combining Balanced IEQ (ieq-amount=6, mu=0.375),
     *   Dialogue Enhancer (amount=4, 1-4 kHz presence), and adaptive virtualizer warmth.
     * - BALANCED (preset id=2): 20-band Balanced IEQ Q4 target matrix (mu_hp=0.375, clamped +-6 dB).
     * - EFFECT_3D: 3D Audio binaural HRTF crossfeed + early reflection + cranial shadow compensation.
     */
    fun computeProfile31(profile: AudioProfile, isSpeaker: Boolean = false): DoubleArray {
        if (profile == AudioProfile.OFF) return DoubleArray(Dsp.F_DP.size)
        val spkScale = if (isSpeaker) 0.82 else 1.0

        // Smooth 10-band reference targets rendered via PCHIP (Dsp.renderCurve) —
        // identical phase-coherent architecture to Equalizer & Bass Enhancer with neutral 500 Hz midrange
        // so broadband RMS never overloads or distorts DynamicsProcessing.
        val profile10 = when (profile) {
            AudioProfile.OFF -> DoubleArray(10) { 0.0 }
            AudioProfile.BALANCED -> doubleArrayOf(
                2.0 * spkScale,  // 31 Hz
                2.9 * spkScale,  // 62 Hz
                2.2 * spkScale,  // 125 Hz
                0.7 * spkScale,  // 250 Hz
                0.1 * spkScale,  // 500 Hz (un-muddy neutral center)
                0.5 * spkScale,  // 1 kHz
                1.8 * spkScale,  // 2 kHz
                2.7 * spkScale,  // 4 kHz
                2.2 * spkScale,  // 8 kHz
                1.5 * spkScale   // 16 kHz
            )
            AudioProfile.DYNAMIC -> doubleArrayOf(
                2.8 * spkScale,  // 31 Hz
                3.5 * spkScale,  // 62 Hz
                2.4 * spkScale,  // 125 Hz
                0.5 * spkScale,  // 250 Hz
                0.0,             // 500 Hz (clean separation)
                1.2 * spkScale,  // 1 kHz
                2.6 * spkScale,  // 2 kHz (dialogue clarity 4/16)
                3.2 * spkScale,  // 4 kHz (presence)
                3.0 * spkScale,  // 8 kHz (crisp air)
                2.2 * spkScale   // 16 kHz
            )
            AudioProfile.EFFECT_3D -> doubleArrayOf(
                2.2 * spkScale,  // 31 Hz
                2.8 * spkScale,  // 62 Hz
                2.0 * spkScale,  // 125 Hz (stage warmth)
                0.5 * spkScale,  // 250 Hz
                0.0,             // 500 Hz
                0.9 * spkScale,  // 1 kHz
                2.8 * spkScale,  // 2 kHz (pinna externalization)
                3.7 * spkScale,  // 4 kHz (Blauert spatial presence)
                3.4 * spkScale,  // 8 kHz (binaural air)
                2.6 * spkScale   // 16 kHz
            )
        }

        val rendered31 = Dsp.renderCurve(profile10)
        return DoubleArray(Dsp.F_DP.size) { k ->
            rendered31[k].coerceIn(0.0, 4.2)
        }
    }

    fun computeCombinedEnhancer31(config: EnhancerConfig, isSpeaker: Boolean = false): DoubleArray {
        val ieq = computeIeq31(config)
        val dlg = computeDialogueEnhancer31(config)
        val bass = computeBassEnhancer31(config)
        val prof = computeProfile31(config.profile, isSpeaker)
        return DoubleArray(Dsp.F_DP.size) { k ->
            // When stacking multiple enhancers with a profile, apply soft-knee ceiling at +8.0 dB
            val sum = ieq[k] + dlg[k] + bass[k] + prof[k]
            if (sum > 6.0) {
                (6.0 + (sum - 6.0) * 0.35).coerceAtMost(8.0)
            } else {
                sum.coerceIn(-12.0, 6.0)
            }
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

    fun configure(mode: ThreeDAudioMode, sampleRate: Int = 48000) {
        poleRadius = 0.65f
        val scaledDelay = ((mode.allpassFilterDelay.toDouble() * sampleRate) / 48000.0).roundToInt()
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

class BinauralHrtfVirtualizer(
    sampleRate: Int = 48000,
    mode: ThreeDAudioMode = ThreeDAudioMode.MIDDLE
) {
    private var gDirect: Float = (mode.gDirectQ15 / 32768.0).toFloat()
    private var gCross: Float = (mode.gCrossQ15 / 32768.0).toFloat()
    private var gRefl: Float = (mode.gReflQ15 / 32768.0).toFloat()
    private var itdSamples: Int = EnhancerMath.computeWoodworthItdSamples(mode.azimuthDeg, sampleRate).coerceIn(0, 63)
    private var shadowBeta: Float = EnhancerMath.computeCranialShadowBeta(
        cutoffHz = if (mode.shadowCutoffHz > 0) mode.shadowCutoffHz.toDouble() else 850.0,
        sampleRate = sampleRate
    ).toFloat()

    private val delayBufL = FloatArray(64)
    private val delayBufR = FloatArray(64)
    private var writeIdx = 0
    private var lpfStateL = 0f
    private var lpfStateR = 0f

    fun configure(mode: ThreeDAudioMode, sampleRate: Int = 48000) {
        gDirect = (mode.gDirectQ15 / 32768.0).toFloat()
        gCross = (mode.gCrossQ15 / 32768.0).toFloat()
        gRefl = (mode.gReflQ15 / 32768.0).toFloat()
        itdSamples = EnhancerMath.computeWoodworthItdSamples(mode.azimuthDeg, sampleRate).coerceIn(0, 63)
        shadowBeta = EnhancerMath.computeCranialShadowBeta(
            cutoffHz = if (mode.shadowCutoffHz > 0) mode.shadowCutoffHz.toDouble() else 850.0,
            sampleRate = sampleRate
        ).toFloat()
    }

    fun reset() {
        delayBufL.fill(0f)
        delayBufR.fill(0f)
        writeIdx = 0
        lpfStateL = 0f
        lpfStateR = 0f
    }

    fun processPair(leftIn: Float, rightIn: Float): Pair<Float, Float> {
        if (itdSamples <= 0 && gCross <= 0f) {
            return leftIn to rightIn
        }
        delayBufL[writeIdx] = leftIn
        delayBufR[writeIdx] = rightIn
        val readIdx = (writeIdx - itdSamples + 64) % 64
        val delayedL = delayBufL[readIdx]
        val delayedR = delayBufR[readIdx]
        writeIdx = (writeIdx + 1) % 64

        // Cranial Shadowing Filter H_c(z): y_shadow[n] = beta * x[n - D] + (1 - beta) * y_shadow[n - 1]
        lpfStateL = shadowBeta * delayedL + (1f - shadowBeta) * lpfStateL
        lpfStateR = shadowBeta * delayedR + (1f - shadowBeta) * lpfStateR

        val surroundS = (leftIn - rightIn) * 0.70710677f
        val outL = gDirect * leftIn + gCross * lpfStateR + gRefl * surroundS
        val outR = gDirect * rightIn + gCross * lpfStateL - gRefl * surroundS
        return outL to outR
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
