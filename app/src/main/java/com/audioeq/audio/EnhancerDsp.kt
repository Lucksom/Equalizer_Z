package com.audioeq.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

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
    DETAILED("ieq_detailed", 1, "Detailed", "Open treble & upper-mid clarity", 10),
    BALANCED("ieq_balanced", 2, "Balanced", "Reference target matching", 6),
    WARM("ieq_warm", 3, "Warm", "Rich low-mid body & smooth highs", 10),
    CUSTOM("ieq_custom", 4, "Customized", "User-editable 10-band target curve", 10);

    companion object {
        fun fromId(id: String?): IeqPreset = entries.find { it.id == id } ?: OFF
        fun fromIndex(idx: Int): IeqPreset = entries.find { it.index == idx } ?: OFF
    }
}

enum class AudioProfile(
    val id: String = "profile_off",
    val title: String = "Off",
    val subtitle: String = "Direct",
    val badgeLabel: String = "Direct"
) {
    OFF("profile_off", "Off", "Direct", "Direct");

    val virtualizerStrengthPermille: Int get() = 0
    val bassBoostStrengthPermille: Int get() = 0

    companion object {
        fun fromId(id: String?): AudioProfile = OFF
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
    val virtualBassStrength: Int = 75,
    val profile: AudioProfile = AudioProfile.OFF
) {
    val bassBoostStrengthPermille: Short
        get() = (bassEnhancerStrength.coerceIn(0, 100) * 10).toShort()

    fun isAnyActive(): Boolean =
        ieqPreset != IeqPreset.OFF ||
            dialogueEnhancerEnabled ||
            bassEnhancerEnabled ||
            virtualBassEnabled
}

object EnhancerMath {
    val IEQ_FREQS_20: IntArray get() = EnhancerDsp.IEQ_FREQS_20
    val UUID_BASS_BOOST_MZ: java.util.UUID = java.util.UUID.fromString("42b10000-0010-0000-0000-0002a5d5c51b")

    const val BASS_MAX_31_DB = 6.0f
    const val BASS_MAX_62_DB = 6.0f
    const val BASS_MAX_125_DB = 4.5f
    const val BASS_MAX_250_DB = 3.0f

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
        val meanTarget = target20.takeMidrangeMean()

        return DoubleArray(Dsp.F_DP.size) { k ->
            val f = Dsp.F_DP[k]
            val raw = logLinearInterp(freqs20, target20, f) - meanTarget * 0.65
            (alpha * raw).coerceIn(-0.8, 6.0)
        }
    }

    private fun DoubleArray.takeMidrangeMean(): Double {
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
                virtualBassEnabled = false
            ).toDouble()
        }
        val curve31 = Dsp.renderCurve(bass10)
        return DoubleArray(Dsp.F_DP.size) { k ->
            curve31[k].coerceIn(0.0, 6.0)
        }
    }

    fun computeVirtualBass31(config: EnhancerConfig): DoubleArray {
        if (!config.virtualBassEnabled) return DoubleArray(Dsp.F_DP.size)
        val strength = config.virtualBassStrength.coerceIn(0, 100) / 100.0
        return DoubleArray(Dsp.F_DP.size) { k ->
            val f = Dsp.F_DP[k]
            val h2 = AutoEq.biquadDb(type = "PK", fc = 80.0, gain = 4.5 * strength, q = 1.35, f = f)
            val h3 = AutoEq.biquadDb(type = "PK", fc = 125.0, gain = 3.6 * strength, q = 1.25, f = f)
            val h4 = AutoEq.biquadDb(type = "PK", fc = 200.0, gain = 2.0 * strength, q = 1.45, f = f)
            (h2 + h3 + h4).coerceIn(0.0, 5.5)
        }
    }

    fun interpolate31AtFrequency(curve31: DoubleArray, fHz: Double): Double {
        return logLinearInterp(Dsp.F_DP, curve31, fHz)
    }

    fun computeCombinedEnhancer31(config: EnhancerConfig, isSpeaker: Boolean = false): DoubleArray {
        val ieq = computeIeq31(config)
        val dlg = computeDialogueEnhancer31(config)
        val bass = computeBassEnhancer31(config)
        val vBass = computeVirtualBass31(config)
        return DoubleArray(Dsp.F_DP.size) { k ->
            val sum = ieq[k] + dlg[k] + bass[k] + vBass[k]
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
