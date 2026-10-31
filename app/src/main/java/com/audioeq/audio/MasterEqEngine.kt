package com.audioeq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 1. OPERATING MODES & AUDIO ROUTES (Section 1 & Section 9)
// ============================================================================

enum class OperatingMode {
    ROOT_GLOBAL,        // su daemon -> Global Session 0 DynamicsProcessing + Optional OEM Hardware Bridge
    SHIZUKU_GLOBAL,     // Shell UID 2000 -> Global Session 0 DynamicsProcessing + audio_flinger dumpsys
    LEGACY_TIER2_DP,    // Per-app audioSessionId -> 31-band DynamicsProcessing + 6-band MBC + Limiter
    LEGACY_TIER1_NATIVE // Fallback -> 5-band Equalizer (capped +4 dB) + BassBoost + Virtualizer
}

enum class AudioRoute(
    val id: String,
    val baseInputGainDb: Float,
    val limiterThresholdDb: Float,
    val offsets10Db: DoubleArray
) {
    SPEAKER(
        id = "speaker",
        baseInputGainDb = -3.0f,
        limiterThresholdDb = -1.0f,
        offsets10Db = doubleArrayOf(-9.0, -7.0, -2.0, 2.0, 1.0, 0.0, -1.0, -1.0, 0.0, 0.0)
    ),
    WIRED(
        id = "wired",
        baseInputGainDb = -3.0f,
        limiterThresholdDb = -1.0f,
        offsets10Db = DoubleArray(10)
    ),
    BLUETOOTH(
        id = "bluetooth",
        baseInputGainDb = -4.0f,
        limiterThresholdDb = -1.5f,
        offsets10Db = DoubleArray(10)
    ),
    USB(
        id = "usb",
        baseInputGainDb = -3.0f,
        limiterThresholdDb = -1.0f,
        offsets10Db = DoubleArray(10)
    )
}

// ============================================================================
// 2. THE 4 REVERSE-ENGINEERED ACOUSTIC PROFILES (Section 4 & Section 9)
// ============================================================================

enum class DaxProfile(
    val id: Int,
    val displayName: String,
    val ieqTargetId: String?,
    val ieqAmountSpk: Double,
    val ieqAmountHp: Double,
    val dialogStepsSpk: Int,
    val dialogStepsHp: Int,
    val levelerOn: Boolean,
    val virtualBassSpk: Boolean,
    val virtualizerStrength: Short
) {
    DYNAMIC(
        id = 0,
        displayName = "Dynamic",
        ieqTargetId = "ieq_balanced",
        ieqAmountSpk = 6.0 / 16.0,   // 0.375
        ieqAmountHp = 6.0 / 16.0,    // 0.375
        dialogStepsSpk = 0,
        dialogStepsHp = 0,
        levelerOn = true,
        virtualBassSpk = true,
        virtualizerStrength = 150
    ),
    MOVIE(
        id = 1,
        displayName = "Video / Movie",
        ieqTargetId = "ieq_balanced",
        ieqAmountSpk = 12.0 / 16.0,  // 0.750
        ieqAmountHp = 13.0 / 16.0,   // 0.8125
        dialogStepsSpk = 5,          // +2.25 dB Hann bell at 800–4500 Hz on Speaker
        dialogStepsHp = 4,           // +1.80 dB Hann bell at 800–4500 Hz on Headphones
        levelerOn = true,
        virtualBassSpk = true,
        virtualizerStrength = 250
    ),
    MUSIC(
        id = 2,
        displayName = "Music",
        ieqTargetId = null,          // Off (Pure user curve)
        ieqAmountSpk = 0.0,
        ieqAmountHp = 0.0,
        dialogStepsSpk = 0,
        dialogStepsHp = 0,
        levelerOn = false,
        virtualBassSpk = true,
        virtualizerStrength = 0
    ),
    VOICE(
        id = 3,
        displayName = "Voice",
        ieqTargetId = "ieq_warm",
        ieqAmountSpk = 6.0 / 16.0,   // 0.375
        ieqAmountHp = 6.0 / 16.0,    // 0.375
        dialogStepsSpk = 6,          // +2.70 dB Hann bell at 800–4500 Hz
        dialogStepsHp = 6,
        levelerOn = true,
        virtualBassSpk = false,      // Sub-80 Hz cut (-4.0 dB) for speech clarity
        virtualizerStrength = 0
    );

    val ieqCurveDb: DoubleArray?
        get() = when (ieqTargetId) {
            "ieq_balanced" -> EqMath.IEQ_BALANCED_DB
            "ieq_warm" -> EqMath.IEQ_WARM_DB
            "ieq_detailed" -> EqMath.IEQ_DETAILED_DB
            else -> null
        }

    val dialogSteps: Int
        get() = dialogStepsSpk

    fun dialogStepsFor(route: AudioRoute): Int =
        if (route == AudioRoute.SPEAKER) dialogStepsSpk else dialogStepsHp

    companion object {
        fun fromId(id: Int): DaxProfile = entries.find { it.id == id } ?: MUSIC
    }
}

// ============================================================================
// 3. DATA MODELS FOR FILTERS, MBC, AND ENGINE STATE (Section 5 & Section 9)
// ============================================================================
// Note: PeqFilter(type, fc, gainDb, q) is shared across com.audioeq.audio (defined in AutoEq.kt)

data class MbcSettings(
    val attackMs: FloatArray,
    val releaseMs: FloatArray,
    val ratio: FloatArray,
    val baseThresholdDb: FloatArray = floatArrayOf(-6f, -6f, -6f, -7f, -9f, -8f),
    val kneeDb: FloatArray = floatArrayOf(6f, 6f, 6f, 6f, 4f, 6f)
)

data class EngineState(
    val enabled: Boolean = true,
    val flatBypass: Boolean = false,
    val mode: OperatingMode = OperatingMode.LEGACY_TIER2_DP,
    val route: AudioRoute = AudioRoute.SPEAKER,
    val profile: DaxProfile = DaxProfile.MUSIC,
    val userSliders10Db: DoubleArray = DoubleArray(10),
    val intensity: Double = 1.0, // 0.5 .. 1.5
    val volumeContourOn: Boolean = true,
    val volumeRatio: Double = 0.6, // STREAM_MUSIC current / max
    val correction31Db: DoubleArray = DoubleArray(31), // AutoEq or Speaker Cal
    val mbc: MbcSettings = EqMath.DEFAULT_MBC,
    val tier1Native5Db: FloatArray = FloatArray(5),
    val tier1BassBoost: Short = 0,
    val hearing31Db: DoubleArray = DoubleArray(31), // Personal Hearing Compensation (CustomizedSoundModel)
    val dolbyBoseConfig: DolbyBoseDspConfig = DolbyBoseDspConfig()
)

// ============================================================================
// 4. COMPLETE MATHEMATICAL ENGINE (EqMath — Sections 2, 3, 4, 5, 6, 9)
// ============================================================================

object EqMath {
    // 2.1 User-Facing 10-Band Octave Centers (Hz)
    val SLIDER_10_HZ = doubleArrayOf(
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    // 2.2 Reverse-Engineered 20-Band Bark/ERB Centers (Hz) from dax-default.xml
    val DAX_20_HZ = doubleArrayOf(
        47.0, 141.0, 234.0, 328.0, 469.0, 656.0, 844.0, 1031.0, 1313.0, 1688.0,
        2250.0, 3000.0, 3750.0, 4688.0, 5813.0, 7125.0, 9000.0, 11250.0, 13875.0, 19688.0
    )

    // 2.3 Internal 31-Band Third-Octave Render Engine (DynamicsProcessing Pre-EQ)
    val RENDER_31_HZ = DoubleArray(31) { i -> 20.0 * 2.0.pow(i / 3.0) }
    val RENDER_31_CUTOFF_HZ = FloatArray(31) { i ->
        min(RENDER_31_HZ[i] * 2.0.pow(1.0 / 6.0), 20000.0).toFloat()
    }

    // 3.4 7-Band Cascaded High-Shelf Biquad Transition Frequencies (EqualizerView)
    val CASCADE_6_FC_HZ = doubleArrayOf(75.0, 175.0, 350.0, 900.0, 1750.0, 3500.0)

    // 3.5 Personal Hearing Compensation (CustomizedSoundModel)
    const val INIT_VOLUME_DB = -42
    const val VOLUME_DIV_DB = 3
    val HEARING_6_HZ = doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)

    // 5.1 6-Band MBC Groupings & Upper Cutoffs (90, 359, 1437, 5747, 11494, 20000 Hz)
    val MBC_RANGES = arrayOf(0..6, 7..12, 13..18, 19..24, 25..27, 28..30)
    val MBC_CUTOFF_HZ = floatArrayOf(
        RENDER_31_CUTOFF_HZ[6],
        RENDER_31_CUTOFF_HZ[12],
        RENDER_31_CUTOFF_HZ[18],
        RENDER_31_CUTOFF_HZ[24],
        RENDER_31_CUTOFF_HZ[27],
        20000.0f
    )

    val DEFAULT_MBC = MbcSettings(
        attackMs = floatArrayOf(40f, 15f, 12f, 6f, 1.5f, 2f),
        releaseMs = floatArrayOf(250f, 160f, 130f, 90f, 45f, 50f),
        ratio = floatArrayOf(4.0f, 3.5f, 2.5f, 3.0f, 3.5f, 2.5f)
    )

    val PERCUSSION_MBC = MbcSettings(
        attackMs = floatArrayOf(50f, 30f, 25f, 12f, 4f, 5f),
        releaseMs = floatArrayOf(250f, 150f, 120f, 80f, 50f, 50f),
        ratio = floatArrayOf(3.0f, 2.8f, 2.2f, 2.5f, 2.8f, 2.0f)
    )

    // 4.1 Raw IEQ Target Curves from dax-default.xml (in 1/16 dB units)
    val IEQ_BALANCED_RAW = intArrayOf(
        20, 42, 69, 89, 98, 92, 92, 108, 121, 137,
        155, 166, 168, 177, 169, 133, 98, 63, 18, -62
    )
    val IEQ_DETAILED_RAW = intArrayOf(
        52, 74, 96, 107, 114, 120, 125, 141, 154, 169,
        187, 196, 197, 209, 208, 195, 178, 142, 87, 7
    )
    val IEQ_WARM_RAW = intArrayOf(
        112, 137, 152, 157, 153, 138, 129, 139, 146, 153,
        163, 164, 159, 162, 146, 86, 48, -1, -55, -135
    )

    val IEQ_BALANCED_DB: DoubleArray = normalizeIeq20(IEQ_BALANCED_RAW)
    val IEQ_DETAILED_DB: DoubleArray = normalizeIeq20(IEQ_DETAILED_RAW)
    val IEQ_WARM_DB: DoubleArray = normalizeIeq20(IEQ_WARM_RAW)

    // 6.2 ISO-226 Volume Contour Table
    private val CONTOUR_VS = doubleArrayOf(0.6, 0.4, 0.25, 0.1)
    private val CONTOUR_ROWS = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        doubleArrayOf(1.5, 1.5, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5),
        doubleArrayOf(3.0, 3.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.5),
        doubleArrayOf(4.5, 4.5, 3.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.5, 2.0)
    )

    // 6.4 Speaker Self-Calibration Target Curve Anchors
    private val SPK_ANCHORS = arrayOf(
        200.0 to -2.0,
        250.0 to -1.0,
        315.0 to 0.0,
        2000.0 to 0.0,
        4000.0 to -1.5,
        8000.0 to -3.0,
        10000.0 to -3.5
    )

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    /**
     * Section 4.1 Normalization Rule:
     * Divide each raw value by 16.0 to get dB, subtract the midrange mean (indices 5..10, 656–2250 Hz)
     * so 1 kHz sits at 0.0 dB, and clamp to [-12.0, +6.0] dB.
     */
    fun normalizeIeq20(raw16: IntArray): DoubleArray {
        val db = DoubleArray(20) { raw16[it] / 16.0 }
        val midMean = (5..10).map { db[it] }.average()
        return DoubleArray(20) { (db[it] - midMean).coerceIn(-12.0, 6.0) }
    }

    /**
     * Section 3.2 Step 1 — Quarter-dB Quantization (GeqView):
     * - rem < 0.25 -> fl
     * - 0.25 <= rem <= 0.75 -> fl + 0.5
     * - rem > 0.75 -> fl + 1.0
     */
    fun quantizeQuarterDb(rawDb: Double): Double {
        val fl = floor(rawDb)
        val rem = abs(rawDb - fl)
        return when {
            rem < 0.25 -> fl
            rem <= 0.75 -> fl + 0.5
            else -> fl + 1.0
        }
    }

    /**
     * Section 3.2 Step 2 — 10-to-20 Band Midpoint Mapping (GeqView):
     * Maps 10 quantized UI slider gains Q[0..9] to 20 Bark-spaced bands B[0..19] in dB.
     */
    fun sliders10ToDax20Db(sliders10Db: DoubleArray): DoubleArray {
        val q = DoubleArray(10) { i ->
            quantizeQuarterDb(sliders10Db.getOrElse(i) { 0.0 }.coerceIn(-12.0, 12.0))
        }
        val b = DoubleArray(20)
        for (k in 0..9) {
            b[2 * k] = q[k]
            if (k < 9) {
                b[2 * k + 1] = 0.5 * (q[k] + q[k + 1])
            } else {
                b[19] = q[9]
            }
        }
        return b
    }

    /**
     * Section 3.2 Step 3 — Fixed-Point Integer Scaling (dax-default.xml & OEM Bridge):
     * Multiplies 20-band dB gains by 16 ([-192, +192] for ±12 dB; hardware bound [-576, +576]).
     */
    fun sliders10ToDax20Int(sliders10Db: DoubleArray): IntArray {
        val b = sliders10ToDax20Db(sliders10Db)
        return IntArray(20) { k -> (b[k] * 16.0).roundToInt().coerceIn(-576, 576) }
    }

    /**
     * Section 3.3 Monotone Cubic Hermite Interpolation (PCHIP) on log2(f) with zero end slopes.
     */
    fun pchip(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = DoubleArray(n) // end slopes = 0 (flat beyond ends)
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0.0) {
                0.0
            } else {
                val w1 = 2.0 * h[i] + h[i - 1]
                val w2 = h[i] + 2.0 * h[i - 1]
                (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        val k = (0 until n - 1).first { x <= xs[it + 1] }
        val t = (x - xs[k]) / h[k]
        val t2 = t * t
        val t3 = t2 * t
        return (2.0 * t3 - 3.0 * t2 + 1.0) * ys[k] +
            (t3 - 2.0 * t2 + t) * h[k] * m[k] +
            (-2.0 * t3 + 3.0 * t2) * ys[k + 1] +
            (t3 - t2) * h[k] * m[k + 1]
    }

    fun interp10To31(gains10: DoubleArray): DoubleArray {
        val xs = DoubleArray(10) { log2(SLIDER_10_HZ[it]) }
        return DoubleArray(31) { pchip(xs, gains10, log2(RENDER_31_HZ[it])) }
    }

    fun interp20To31(gains20: DoubleArray): DoubleArray {
        val xs = DoubleArray(20) { log2(DAX_20_HZ[it]) }
        return DoubleArray(31) { pchip(xs, gains20, log2(RENDER_31_HZ[it])) }
    }

    /**
     * Section 3.4 Reverse-Engineered 7-Band Cascaded High-Shelf Biquad Math (EqualizerView):
     * Given 7 band levels L[0..6] in [-12, +12] dB at transition frequencies
     * fc = [75, 175, 350, 900, 1750, 3500] Hz, Q = 1.0, fs = 44100 Hz:
     * Base linear gain g_base = 10^(L[0]/20) cascaded with 6 2nd-order High-Shelf biquads.
     */
    fun evaluateCascaded7BandDb(
        levels7Db: DoubleArray,
        atHz: Double,
        fs: Double = 44100.0
    ): Double {
        if (levels7Db.size < 7) return 0.0
        val l0 = levels7Db[0].coerceIn(-12.0, 12.0)
        val gBase = 10.0.pow(l0 / 20.0)
        var totalMag2 = gBase * gBase
        val q = 1.0
        val slope = 1.0
        val w = 2.0 * PI * atHz / fs
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)
        val sinW = sin(w)
        val sin2W = sin(2.0 * w)

        for (i in 0..5) {
            val lCurr = levels7Db[i].coerceIn(-12.0, 12.0)
            val lNext = levels7Db[i + 1].coerceIn(-12.0, 12.0)
            val deltaL = lNext - lCurr
            if (abs(deltaL) < 1e-6) continue

            val a = 10.0.pow(deltaL / 40.0)
            val w0 = 2.0 * PI * CASCADE_6_FC_HZ[i] / fs
            val cw0 = cos(w0)
            val sw0 = sin(w0)
            val alpha = (sw0 / (2.0 * q)) * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

            val b0 = a * ((a + 1.0) + (a - 1.0) * cw0 + twoSqrtAAlpha)
            val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw0)
            val b2 = a * ((a + 1.0) + (a - 1.0) * cw0 - twoSqrtAAlpha)
            val a0 = (a + 1.0) - (a - 1.0) * cw0 + twoSqrtAAlpha
            val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw0)
            val a2 = (a + 1.0) - (a - 1.0) * cw0 - twoSqrtAAlpha

            val bn0 = b0 / a0
            val bn1 = b1 / a0
            val bn2 = b2 / a0
            val an1 = a1 / a0
            val an2 = a2 / a0

            val numRe = bn0 + bn1 * cosW + bn2 * cos2W
            val numIm = -(bn1 * sinW + bn2 * sin2W)
            val denRe = 1.0 + an1 * cosW + an2 * cos2W
            val denIm = -(an1 * sinW + an2 * sin2W)

            val numMag2 = numRe * numRe + numIm * numIm
            val denMag2 = (denRe * denRe + denIm * denIm).coerceAtLeast(1e-18)
            totalMag2 *= (numMag2 / denMag2)
        }
        return 10.0 * log10(totalMag2.coerceAtLeast(1e-18))
    }

    /**
     * Section 3.5 Reverse-Engineered Personal Hearing Compensation (CustomizedSoundModel):
     * Converts 6-frequency audiogram dB thresholds at [250, 500, 1000, 2000, 4000, 8000] Hz
     * using INIT_VOLUME_DB = -42 and VOLUME_DIV_DB = 3:
     * step[k] = round((earDb[k] + 42.0) / 3.0)
     */
    fun earDbToCustomizedSoundSteps(earDb6: List<Float>): IntArray {
        return IntArray(6) { k ->
            val db = earDb6.getOrElse(k) { 0f }.toDouble()
            ((db - INIT_VOLUME_DB) / VOLUME_DIV_DB).roundToInt()
        }
    }

    fun formatCustomizedSoundStepsCsv(earDb6: List<Float>): String =
        earDbToCustomizedSoundSteps(earDb6).joinToString(",")

    /**
     * Evaluates personal hearing compensation across the 31 render bands (mean of Left & Right ear offsets,
     * scaled by strength and tapered above 10 kHz).
     */
    fun computePersonalHearing31(
        leftEar6Db: List<Float>,
        rightEar6Db: List<Float>,
        strength: Double = 0.5
    ): DoubleArray {
        if (leftEar6Db.size < 6 && rightEar6Db.size < 6) return DoubleArray(31)
        val avg6 = DoubleArray(6) { k ->
            val l = leftEar6Db.getOrElse(k) { 0f }.toDouble().coerceIn(-6.0, 6.0)
            val r = rightEar6Db.getOrElse(k) { 0f }.toDouble().coerceIn(-6.0, 6.0)
            0.5 * (l + r)
        }
        if (avg6.all { abs(it) < 0.05 }) return DoubleArray(31)
        val xs = DoubleArray(6) { log2(HEARING_6_HZ[it]) }
        val s = strength.coerceIn(0.0, 1.0)
        return DoubleArray(31) { i ->
            val f = RENDER_31_HZ[i]
            val t = when {
                f <= 10000.0 -> 1.0
                f >= 14000.0 -> 0.0
                else -> 1.0 - log2(f / 10000.0) / log2(1.4)
            }
            (pchip(xs, avg6, log2(f)) * s * t).coerceIn(-6.0, 6.0)
        }
    }

    /**
     * Section 4.3 Dialog Enhancer Bell Curve:
     * Smooth Hann-window bell over 800 Hz..4500 Hz (+0.45 dB per step).
     */
    fun dialogEnhancerDb(freqHz: Double, steps: Int): Double {
        if (steps <= 0 || freqHz < 800.0 || freqHz > 4500.0) return 0.0
        val pos = (log2(freqHz) - log2(800.0)) / (log2(4500.0) - log2(800.0))
        return (steps * 0.45) * 0.5 * (1.0 - cos(2.0 * PI * pos))
    }

    /**
     * Section 6.2 ISO-226 Equal-Loudness Volume Contour interpolation.
     */
    fun volumeContour10(v: Double): DoubleArray {
        if (v >= CONTOUR_VS[0]) return CONTOUR_ROWS[0].clone()
        if (v <= CONTOUR_VS.last()) return CONTOUR_ROWS.last().clone()
        val k = (0 until CONTOUR_VS.size - 1).first { v >= CONTOUR_VS[it + 1] }
        val t = (CONTOUR_VS[k] - v) / (CONTOUR_VS[k] - CONTOUR_VS[k + 1])
        return DoubleArray(10) { CONTOUR_ROWS[k][it] + (CONTOUR_ROWS[k + 1][it] - CONTOUR_ROWS[k][it]) * t }
    }

    /**
     * Section 5.3 Pink-Noise Weighted Power Mean (dB); bands below 100 Hz count half weight.
     */
    fun pinkGainDb(curve31: DoubleArray): Double {
        var sum = 0.0
        var weight = 0.0
        for (i in 0 until 31) {
            val w = if (RENDER_31_HZ[i] < 100.0) 0.5 else 1.0
            sum += w * 10.0.pow(curve31[i] / 10.0)
            weight += w
        }
        return 10.0 * log10(sum / weight)
    }

    /**
     * Section 6.3 AutoEq Parametric Biquad Evaluation (RBJ Cookbook at 44100 Hz).
     */
    fun biquadDb(f: PeqFilter, atHz: Double, fs: Double = 44100.0): Double {
        val a = 10.0.pow(f.gainDb / 40.0)
        val w0 = 2.0 * PI * f.fc / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * f.q)
        val s = 2.0 * sqrt(a) * alpha
        val b: DoubleArray
        val d: DoubleArray
        when (f.type.uppercase(Locale.US)) {
            "PK" -> {
                b = doubleArrayOf(1.0 + alpha * a, -2.0 * cw, 1.0 - alpha * a)
                d = doubleArrayOf(1.0 + alpha / a, -2.0 * cw, 1.0 - alpha / a)
            }
            "LSC" -> {
                b = doubleArrayOf(
                    a * ((a + 1.0) - (a - 1.0) * cw + s),
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cw),
                    a * ((a + 1.0) - (a - 1.0) * cw - s)
                )
                d = doubleArrayOf(
                    (a + 1.0) + (a - 1.0) * cw + s,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cw),
                    (a + 1.0) + (a - 1.0) * cw - s
                )
            }
            else -> { // HSC
                b = doubleArrayOf(
                    a * ((a + 1.0) + (a - 1.0) * cw + s),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cw),
                    a * ((a + 1.0) + (a - 1.0) * cw - s)
                )
                d = doubleArrayOf(
                    (a + 1.0) - (a - 1.0) * cw + s,
                    2.0 * ((a - 1.0) - (a + 1.0) * cw),
                    (a + 1.0) - (a - 1.0) * cw - s
                )
            }
        }
        val w = 2.0 * PI * atHz / fs
        fun mag2(c: DoubleArray): Double {
            val re = c[0] + c[1] * cos(w) + c[2] * cos(2.0 * w)
            val im = -(c[1] * sin(w) + c[2] * sin(2.0 * w))
            return re * re + im * im
        }
        return 10.0 * log10(mag2(b) / mag2(d))
    }

    /**
     * Section 6.3 AutoEq 31-Band Headphone Correction Curve with high-frequency taper (10..14 kHz)
     * and clamping to [-12.0, +6.0] dB (+3.0 dB above 6 kHz).
     */
    fun evaluateAutoEq31(
        filters: List<PeqFilter>,
        strength: Double = 0.8,
        fs: Double = 44100.0
    ): DoubleArray = DoubleArray(31) { i ->
        val f = RENDER_31_HZ[i]
        val raw = filters.sumOf { biquadDb(it, f, fs) }
        val taper = when {
            f <= 10000.0 -> 1.0
            f >= 14000.0 -> 0.0
            else -> 1.0 - log2(f / 10000.0) / log2(1.4)
        }
        val hiCap = if (f > 6000.0) 3.0 else 6.0
        (raw * taper).coerceIn(-12.0, hiCap) * strength
    }

    /**
     * Section 6.4 Speaker Self-Calibration Target Curve & Correction Math.
     */
    fun speakerTargetDb(f: Double): Double {
        val a = SPK_ANCHORS
        if (f <= a.first().first) return a.first().second
        if (f >= a.last().first) return a.last().second
        val k = (0 until a.size - 1).first { f <= a[it + 1].first }
        val t = (log2(f) - log2(a[k].first)) / (log2(a[k + 1].first) - log2(a[k].first))
        return a[k].second + (a[k + 1].second - a[k].second) * t
    }

    fun computeSpeakerCalibration31(
        measDb: DoubleArray,
        valid: BooleanArray,
        strength: Double = 0.7
    ): DoubleArray {
        val refIndices = (0 until 31).filter { RENDER_31_HZ[it] in 500.0..2000.0 }
        val ref = if (refIndices.isNotEmpty()) refIndices.map { measDb[it] }.average() else 0.0
        val raw = DoubleArray(31) { speakerTargetDb(RENDER_31_HZ[it]) - (measDb[it] - ref) }
        return DoubleArray(31) { i ->
            val f = RENDER_31_HZ[i]
            val spkTaper = when {
                f < 200.0 -> max(0.0, 1.0 + log2(f / 200.0)) // zero at 100 Hz
                f > 10000.0 -> max(0.0, 1.0 - log2(f / 10000.0)) // zero at 20 kHz
                else -> 1.0
            }
            val narrowDip = raw[i] > 0.0 && i in 1..29 && !(raw[i - 1] > 0.0 && raw[i + 1] > 0.0)
            val v = if (narrowDip) min(raw[i], 1.5) else raw[i] // cap single-band narrow dips at +1.5 dB
            if (!valid.getOrElse(i) { false }) {
                0.0
            } else {
                (v * strength * spkTaper).coerceIn(-12.0, if (f > 6000.0) 3.0 else 6.0)
            }
        }
    }

    /**
     * Section 3.1 & Section 9 Master Signal Chain + Dolby DAP & Bose MiSound DSP:
     * Combines User 10-band Sliders (x Intensity), Dolby IEQ (ieq_detailed, ieq_balanced, ieq_warm, ieq_custom),
     * Bass Enhancer (+ Virtual Bass Harmonic Synthesis), Dialogue Enhancer (1-4 kHz Formant Peaking + <=180 Hz Ambient Ducking),
     * Bose Profile (Dynamic & Balanced), Route Offsets, ISO-226 Volume Contour,
     * Correction31 (AutoEq / Speaker Cal), and Personal Hearing, clamped to [-15.0, +15.0] dB.
     */
    fun computeEffective31(s: EngineState, includeContour: Boolean): DoubleArray {
        if (s.flatBypass) return DoubleArray(31)

        val contour10 = if (includeContour && s.volumeContourOn) {
            volumeContour10(s.volumeRatio)
        } else {
            DoubleArray(10)
        }
        val route10 = if (s.route == AudioRoute.SPEAKER && s.correction31Db.any { it != 0.0 }) {
            DoubleArray(10) // Speaker calibration replaces static speaker route offsets
        } else {
            s.route.offsets10Db
        }

        // Composite 10-band delta from Dolby IEQ + Bass Enhancer (Virtual Bass) + Dialogue Enhancer + Bose Profile
        val dolbyBose10 = DolbyBoseDspMath.computeComposite10BandDeltaDb(
            config = s.dolbyBoseConfig,
            userCustom10Db = s.userSliders10Db
        )

        val combined10 = DoubleArray(10) { i ->
            s.userSliders10Db.getOrElse(i) { 0.0 } * s.intensity +
                dolbyBose10[i] +
                route10[i] +
                contour10[i]
        }
        val userAndDsp31 = interp10To31(combined10)

        return DoubleArray(31) { i ->
            val f = RENDER_31_HZ[i]
            val voiceLowCut = if (!s.profile.virtualBassSpk && !s.dolbyBoseConfig.bassEnhancerEnabled && f < 80.0) -4.0 else 0.0
            val taste = userAndDsp31[i] + voiceLowCut + s.hearing31Db.getOrElse(i) { 0.0 }
            (taste + s.correction31Db.getOrElse(i) { 0.0 }).coerceIn(-15.0, 15.0)
        }
    }

    /**
     * Section 3.1 Rule 3 — UI Headroom Meter (ceilingLoadDb):
     * ceilingLoadDb = max(0.0, max(effective[i]) - 3.0)
     * Warn in UI when ceilingLoadDb > 4.0 dB.
     */
    fun computeCeilingLoadDb(effective31: DoubleArray): Float {
        val peak = effective31.maxOrNull() ?: 0.0
        return max(0.0, peak - 3.0).toFloat()
    }
}

// ============================================================================
// 5. OPTIONAL ROOT / SHELL OEM HARDWARE BRIDGE PROTOCOL (Section 7)
// ============================================================================

object OemDaxBridge {
    val EFFECT_TYPE_DAX: UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
    val EFFECT_UUID_DAX: UUID = UUID.fromString("9d4921da-8225-4f29-aefa-5f7279756b69")

    private const val PARAM_CPDP_VALUES = 5
    private const val EFFECT_TYPE_GEQ = 0
    private const val DAX_PARAM_ID_GEQ_BAND_GAINS = 110

    /**
     * Queries AudioEffect descriptors to see if the OEM hardware effect UUID is present on this device.
     */
    fun isHardwareDaxSupported(): Boolean {
        return try {
            val descriptors = AudioEffect.queryEffects() ?: return false
            descriptors.any { d ->
                d.type == EFFECT_TYPE_DAX || d.uuid == EFFECT_UUID_DAX
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Section 7.2 Binary Packet Layout for 20-Band GEQ (`setParameter(5, byte[96])` Little-Endian):
     * - Offset 0: int32 = 0 (EFFECT_TYPE_GEQ)
     * - Offset 4: int32 = 21 (1 parameter ID + 20 band values)
     * - Offset 8: int32 = 110 (DAX_PARAM_ID_GEQ_BAND_GAINS)
     * - Offset 12..91: int32[20] = round(bandDb[k] * 16.0)
     * - Offset 92: int32 = 0 (trailing alignment padding)
     */
    fun buildGeq96BytePacket(band20Fixed16: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(EFFECT_TYPE_GEQ)
        buf.putInt(21)
        buf.putInt(DAX_PARAM_ID_GEQ_BAND_GAINS)
        for (k in 0 until 20) {
            val v = band20Fixed16.getOrElse(k) { 0 }.coerceIn(-576, 576)
            buf.putInt(v)
        }
        buf.putInt(0)
        return buf.array()
    }

    /**
     * Section 7.3 Binary Packet for Profile Switch (12 Bytes Little-Endian):
     * - Offset 0: int32 = 0
     * - Offset 4: int32 = 1
     * - Offset 8: int32 = profileId (0=Dynamic, 1=Movie, 2=Music, 3=Voice)
     */
    fun buildProfile12BytePacket(profileId: Int): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)
        buf.putInt(1)
        buf.putInt(profileId.coerceIn(0, 3))
        return buf.array()
    }

    /**
     * Instantiates the hidden AudioEffect(UUID, UUID, priority, audioSession) via reflection
     * when running in Root or Shell/Shizuku mode and sends the binary parameter packets.
     */
    class SessionHandle(sessionId: Int = 0) {
        private var effectInstance: AudioEffect? = null
        private var setParamMethod: java.lang.reflect.Method? = null

        val isConnected: Boolean
            get() = effectInstance != null && setParamMethod != null

        init {
            if (isHardwareDaxSupported()) {
                try {
                    val ctor = AudioEffect::class.java.getConstructor(
                        UUID::class.java,
                        UUID::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    ctor.isAccessible = true
                    val fx = ctor.newInstance(EFFECT_TYPE_DAX, EFFECT_UUID_DAX, 0, sessionId)
                    val m = AudioEffect::class.java.getMethod(
                        "setParameter",
                        Int::class.javaPrimitiveType,
                        ByteArray::class.java
                    )
                    m.isAccessible = true
                    fx.enabled = true
                    effectInstance = fx
                    setParamMethod = m
                } catch (_: Throwable) {
                    effectInstance = null
                    setParamMethod = null
                }
            }
        }

        fun applyProfileAndGeq(profile: DaxProfile, sliders10Db: DoubleArray): Boolean {
            val fx = effectInstance ?: return false
            val m = setParamMethod ?: return false
            return try {
                val profilePacket = buildProfile12BytePacket(profile.id)
                val geqPacket = buildGeq96BytePacket(EqMath.sliders10ToDax20Int(sliders10Db))
                m.invoke(fx, PARAM_CPDP_VALUES, profilePacket)
                m.invoke(fx, PARAM_CPDP_VALUES, geqPacket)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun release() {
            try {
                effectInstance?.release()
            } catch (_: Throwable) {
            }
            effectInstance = null
            setParamMethod = null
        }
    }
}

// ============================================================================
// 6. MASTER EQ CONTROLLER (Section 9 & Section 10)
// ============================================================================

class MasterEqController(private val sessionId: Int) {
    private val thread = HandlerThread("MasterEq-$sessionId", Process.THREAD_PRIORITY_AUDIO).apply {
        start()
    }
    private val handler = Handler(thread.looper)

    private var dp: DynamicsProcessing? = null
    private var preEq: DynamicsProcessing.Eq? = null
    private var mbc: DynamicsProcessing.Mbc? = null

    private var nativeEq: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var oemBridge: OemDaxBridge.SessionHandle? = null

    private val current31 = FloatArray(31)
    private val target31 = FloatArray(31)
    private val written31 = FloatArray(31) { Float.NaN }
    private val writtenMbc = FloatArray(6) { Float.NaN }

    @Volatile
    private var hasEffectControl: Boolean = true

    @Volatile
    var inputGainDb: Float = -3.0f
        private set

    @Volatile
    var makeupDb: Float = 0.0f
        private set

    @Volatile
    var ceilingLoadDb: Float = 0.0f
        private set

    @Volatile
    var activeMode: OperatingMode = OperatingMode.LEGACY_TIER2_DP
        private set

    @Volatile
    var oemBridgeActive: Boolean = false
        private set

    private var activeState = EngineState()
    private var tauSec = 0.06 / 3.0
    private var lastTickNs = 0L

    fun start(preferredMode: OperatingMode = OperatingMode.LEGACY_TIER2_DP): OperatingMode {
        if (preferredMode == OperatingMode.ROOT_GLOBAL || preferredMode == OperatingMode.SHIZUKU_GLOBAL) {
            val bridge = OemDaxBridge.SessionHandle(sessionId)
            if (bridge.isConnected) {
                oemBridge = bridge
                oemBridgeActive = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            preferredMode != OperatingMode.LEGACY_TIER1_NATIVE
        ) {
            var candidateEngine: DynamicsProcessing? = null
            try {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, 31,
                    true, 6,
                    false, 0,
                    true
                ).build()
                val engine = DynamicsProcessing(0, sessionId, cfg)
                candidateEngine = engine
                val initPreEq = DynamicsProcessing.Eq(true, true, 31).apply {
                    for (i in 0 until 31) {
                        val b = getBand(i)
                        b.isEnabled = true
                        b.cutoffFrequency = EqMath.RENDER_31_CUTOFF_HZ[i]
                        b.gain = 0f
                        setBand(i, b)
                    }
                }
                val initMbc = DynamicsProcessing.Mbc(true, true, 6).apply {
                    for (i in 0 until 6) {
                        val b = getBand(i)
                        b.isEnabled = true
                        b.cutoffFrequency = EqMath.MBC_CUTOFF_HZ[i]
                        b.noiseGateThreshold = -90f
                        b.expanderRatio = 1f
                        b.preGain = 0f
                        setBand(i, b)
                    }
                }
                engine.setPreEqAllChannelsTo(initPreEq)
                engine.setMbcAllChannelsTo(initMbc)
                // Probe single-band parameter updates so any HAL rejection on sessionId (e.g. session 0) is caught immediately
                engine.setPreEqBandAllChannelsTo(0, initPreEq.getBand(0))
                engine.setMbcBandAllChannelsTo(0, initMbc.getBand(0))
                engine.setInputGainAllChannelsTo(inputGainDb)
                engine.enabled = true

                try {
                    engine.setControlStatusListener { _, controlGranted ->
                        hasEffectControl = controlGranted
                        if (controlGranted) {
                            handler.post {
                                written31.fill(Float.NaN)
                                writtenMbc.fill(Float.NaN)
                                applyTargetsAndSchedule(activeState, recomputeMbc = true, glideMs = 60.0)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }

                preEq = initPreEq
                mbc = initMbc
                dp = engine
                hasEffectControl = true
                initVirtualizerOnly()
                activeMode = preferredMode
                return activeMode
            } catch (_: Throwable) {
                try {
                    candidateEngine?.release()
                } catch (_: Throwable) {
                }
                dp = null
                preEq = null
                mbc = null
                // Fall through to Legacy Tier 1
            }
        }

        initTier1Fallback()
        activeMode = OperatingMode.LEGACY_TIER1_NATIVE
        return activeMode
    }

    private fun initVirtualizerOnly() {
        if (virtualizer == null) {
            try {
                virtualizer = Virtualizer(0, sessionId)
            } catch (_: Throwable) {
                virtualizer = null
            }
        }
    }

    private fun initTier1Fallback() {
        if (nativeEq == null) {
            try {
                nativeEq = Equalizer(0, sessionId)?.apply { enabled = true }
            } catch (_: Throwable) {
                nativeEq = null
            }
        }
        if (bassBoost == null) {
            try {
                bassBoost = BassBoost(0, sessionId)?.apply { enabled = true }
            } catch (_: Throwable) {
                bassBoost = null
            }
        }
        if (virtualizer == null) {
            try {
                virtualizer = Virtualizer(0, sessionId)?.apply { enabled = true }
            } catch (_: Throwable) {
                virtualizer = null
            }
        }
    }

    private fun fallbackToTier1OnError(state: EngineState) {
        handler.removeCallbacks(tickRunnable)
        try {
            dp?.release()
        } catch (_: Throwable) {
        }
        dp = null
        preEq = null
        mbc = null
        activeMode = OperatingMode.LEGACY_TIER1_NATIVE
        initTier1Fallback()
        applyTier1Fallback(state)
    }

    /**
     * Call on Preset, Profile, Route, AutoEq, or Intensity change:
     * Recomputes inputGainDb and makeupDb (Section 3.1 Rule 1 & Rule 2) and glides over 250 ms.
     */
    fun loadFullState(state: EngineState, glideMs: Double = 250.0) {
        handler.post {
            activeState = state
            val noContour31 = EqMath.computeEffective31(state, includeContour = false)
            val maxPosGain = (noContour31.maxOrNull() ?: 0.0).toFloat()
            val antiClipHeadroomDb = DolbyBoseDspMath.computeSafeHeadroomDb(maxPosGain)
            val corrPeak = state.correction31Db.maxOrNull() ?: 0.0
            val routeInDb = state.route.baseInputGainDb - max(0.0, corrPeak - 3.0).toFloat()
            inputGainDb = min(routeInDb, antiClipHeadroomDb).coerceAtLeast(-11.0f)

            val levelerBonus = if (state.profile.levelerOn) 1.0 else 0.0
            makeupDb = if (state.flatBypass) {
                0f
            } else {
                (-inputGainDb - EqMath.pinkGainDb(noContour31) + levelerBonus)
                    .coerceIn(0.0, 4.0)
                    .toFloat()
            }

            applyTargetsAndSchedule(state, recomputeMbc = true, glideMs = glideMs)
        }
    }

    /**
     * Call while dragging sliders or changing system volume:
     * Freezes makeupDb and inputGainDb so slider boosts/cuts are never fought (Section 3.1 Rule 2).
     */
    fun updateSlidersOrVolume(state: EngineState, glideMs: Double = 60.0) {
        handler.post {
            activeState = state
            applyTargetsAndSchedule(state, recomputeMbc = false, glideMs = glideMs)
        }
    }

    private fun applyTargetsAndSchedule(state: EngineState, recomputeMbc: Boolean, glideMs: Double) {
        val eff31 = EqMath.computeEffective31(state, includeContour = true)
        for (i in 0 until 31) target31[i] = eff31[i].toFloat()
        ceilingLoadDb = EqMath.computeCeilingLoadDb(eff31)

        // Optional OEM Hardware Bridge update when running in Root or Shizuku mode
        if (state.mode == OperatingMode.ROOT_GLOBAL || state.mode == OperatingMode.SHIZUKU_GLOBAL) {
            oemBridge?.applyProfileAndGeq(state.profile, state.userSliders10Db)
        }

        val engine = dp
        if (engine == null) {
            applyTier1Fallback(state)
            return
        }

        if (!hasEffectControl) {
            return
        }

        try {
            engine.enabled = state.enabled && !state.flatBypass
            if (recomputeMbc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                engine.setInputGainAllChannelsTo(if (state.flatBypass) 0f else inputGainDb)
                engine.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(
                        true,
                        true,
                        0,
                        1.0f,  // 1 ms instantaneous peak catch
                        60.0f, // 60 ms transparent recovery
                        10.0f, // 10:1 brickwall ceiling
                        min(state.route.limiterThresholdDb, -0.8f),
                        0f
                    )
                )
                val m = mbc
                if (m != null) {
                    val cfg = state.dolbyBoseConfig
                    val ieqStrength = cfg.ieqAmount.coerceIn(0, 16) / 16.0f
                    val deFactor = if (cfg.dialogueEnhancerEnabled) {
                        cfg.dialogueAmount.coerceIn(0, 16) / 16.0f
                    } else 0f
                    val levelerShift = if (state.profile.levelerOn) 1.5f else 0.0f

                    for (i in 0 until 6) {
                        val meanBoost = max(0.0, EqMath.MBC_RANGES[i].map { eff31[it] }.average()).toFloat()
                        val b = m.getBand(i)
                        when {
                            // Stage 2: Virtual Bass Mix Window (90-359 Hz -> MBC band 1): Fast harmonic density compression
                            cfg.bassEnhancerEnabled && cfg.virtualBassEnabled && i == 1 -> {
                                b.attackTime = 4.0f
                                b.releaseTime = 75.0f
                                b.ratio = 2.8f
                                b.threshold = -20.0f + meanBoost * 0.5f
                                b.kneeWidth = 6.0f
                                b.noiseGateThreshold = -70.0f
                                b.expanderRatio = 1.0f
                                b.preGain = 1.5f
                                b.postGain = 2.2f + makeupDb * 0.5f
                            }
                            // Stage 2: Dialogue Formant Band (359-5747 Hz -> MBC bands 2 & 3): Upward vocal expansion
                            deFactor > 0f && (i == 2 || i == 3) -> {
                                b.attackTime = 2.5f
                                b.releaseTime = 55.0f
                                b.ratio = 1.0f + (0.8f * deFactor)
                                b.threshold = -26.0f
                                b.kneeWidth = 8.0f
                                b.noiseGateThreshold = -65.0f
                                b.expanderRatio = 1.0f
                                b.preGain = 0.0f
                                b.postGain = (3.2f * deFactor) + makeupDb
                            }
                            else -> {
                                b.attackTime = state.mbc.attackMs[i]
                                b.releaseTime = state.mbc.releaseMs[i]
                                b.ratio = max(state.mbc.ratio[i], 1.0f + 0.4f * ieqStrength)
                                b.threshold = state.mbc.baseThresholdDb[i] + meanBoost - levelerShift
                                b.kneeWidth = state.mbc.kneeDb[i]
                                b.noiseGateThreshold = -80.0f
                                b.expanderRatio = 1.0f
                                b.preGain = 0.0f
                                b.postGain = makeupDb
                            }
                        }
                        m.setBand(i, b)
                        writtenMbc[i] = b.threshold
                    }
                    engine.setMbcAllChannelsTo(m)
                }
            }
        } catch (_: Throwable) {
            fallbackToTier1OnError(state)
            return
        }

        if (recomputeMbc) {
            try {
                virtualizer?.let { v ->
                    v.enabled = state.enabled && state.profile.virtualizerStrength > 0
                    if (v.strengthSupported) {
                        v.setStrength(state.profile.virtualizerStrength)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        tauSec = max(glideMs, 10.0) / 3000.0
        lastTickNs = System.nanoTime()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val engine = dp ?: return
            val eq = preEq ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            if (!hasEffectControl) return

            val now = System.nanoTime()
            val dt = ((now - lastTickNs) / 1e9).coerceIn(0.001, 0.1)
            lastTickNs = now
            val alpha = 1.0f - exp(-dt / tauSec).toFloat()

            var moving = false
            var anyEqBandChanged = false
            try {
                for (i in 0 until 31) {
                    val diff = target31[i] - current31[i]
                    if (abs(diff) > 0.03f) {
                        current31[i] += diff * alpha
                        moving = true
                    } else {
                        current31[i] = target31[i]
                    }

                    if (written31[i].isNaN() ||
                        abs(current31[i] - written31[i]) > 0.08f ||
                        (!moving && current31[i] != written31[i])
                    ) {
                        val b = eq.getBand(i).apply { gain = current31[i] }
                        eq.setBand(i, b)
                        try {
                            engine.setPreEqBandAllChannelsTo(i, b)
                        } catch (_: UnsupportedOperationException) {
                            anyEqBandChanged = true
                        }
                        written31[i] = current31[i]
                    }
                }
                if (anyEqBandChanged) {
                    engine.setPreEqAllChannelsTo(eq)
                }

                // Update MBC thresholds continuously if slider boost changed by > 0.25 dB
                val m = mbc
                if (m != null) {
                    var anyMbcBandChanged = false
                    val levelerShift = if (activeState.profile.levelerOn) 1.5f else 0.0f
                    for (i in 0 until 6) {
                        val meanBoost = max(0.0f, EqMath.MBC_RANGES[i].map { current31[it] }.average().toFloat())
                        val thr = activeState.mbc.baseThresholdDb[i] + meanBoost - levelerShift
                        if (writtenMbc[i].isNaN() || abs(thr - writtenMbc[i]) > 0.25f) {
                            val b = m.getBand(i).apply {
                                threshold = thr
                                postGain = makeupDb
                            }
                            m.setBand(i, b)
                            try {
                                engine.setMbcBandAllChannelsTo(i, b)
                            } catch (_: UnsupportedOperationException) {
                                anyMbcBandChanged = true
                            }
                            writtenMbc[i] = thr
                        }
                    }
                    if (anyMbcBandChanged) {
                        engine.setMbcAllChannelsTo(m)
                    }
                }
            } catch (_: Throwable) {
                fallbackToTier1OnError(activeState)
                return
            }

            if (moving && dp != null) {
                handler.postDelayed(this, 30L)
            }
        }
    }

    private fun applyTier1Fallback(s: EngineState) {
        try {
            nativeEq?.let { eq ->
                eq.enabled = s.enabled && !s.flatBypass
                val count = min(eq.numberOfBands.toInt(), 5)
                for (b in 0 until count) {
                    val mb = (s.tier1Native5Db[b].coerceIn(-12f, 4f) * 100f).toInt().toShort()
                    eq.setBandLevel(b.toShort(), mb)
                }
            }
        } catch (_: Throwable) {
        }
        try {
            bassBoost?.let { bb ->
                bb.enabled = s.enabled && !s.flatBypass && s.tier1BassBoost > 0
                if (bb.strengthSupported) bb.setStrength(s.tier1BassBoost)
            }
        } catch (_: Throwable) {
        }
        try {
            virtualizer?.let { v ->
                v.enabled = s.enabled && !s.flatBypass && s.profile.virtualizerStrength > 0
                if (v.strengthSupported) v.setStrength(s.profile.virtualizerStrength)
            }
        } catch (_: Throwable) {
        }
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(tickRunnable)
            try {
                dp?.release()
            } catch (_: Throwable) {
            }
            try {
                nativeEq?.release()
            } catch (_: Throwable) {
            }
            try {
                bassBoost?.release()
            } catch (_: Throwable) {
            }
            try {
                virtualizer?.release()
            } catch (_: Throwable) {
            }
            oemBridge?.release()
            dp = null
            nativeEq = null
            bassBoost = null
            virtualizer = null
            oemBridge = null
            thread.quitSafely()
        }
    }
}
