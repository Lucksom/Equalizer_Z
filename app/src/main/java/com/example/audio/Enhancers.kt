package com.example.audio

import android.media.AudioFormat
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.os.Build
import com.example.model.MbcConfig
import com.example.model.OutputRoute
import java.util.Arrays
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Fixed index order for the four enhancers:
 * 0 = DEEP_BASS, 1 = SURROUND, 2 = BEATS, 3 = VOCAL.
 * Used across UI, DataStore persistence, mixer arrays, and MBC blending.
 */
enum class Enhancer(
    val id: String,
    val title: String,
    val subtitle: String
) {
    DEEP_BASS(
        id = "deep_bass",
        title = "Deeper Bass",
        subtitle = "More weight and depth, cleaner low end"
    ),
    SURROUND(
        id = "surround",
        title = "Surround Audio",
        subtitle = "Wider, more spacious stereo image"
    ),
    BEATS(
        id = "beats",
        title = "Enhance Beats",
        subtitle = "Punchier kick, snare and percussion transients"
    ),
    VOCAL(
        id = "vocal",
        title = "Enhance Vocal",
        subtitle = "Clearer, more forward voices & de-harsh sibilance"
    )
}

/**
 * UI state for a single enhancer bar row:
 * - `enabled`: on/off switch (defaults to false)
 * - `sliderPercent`: 0..100% (defaults to 60% when first switched on)
 */
data class EnhancerItemState(
    val enhancer: Enhancer,
    val enabled: Boolean = false,
    val sliderPercent: Float = 0f
) {
    /** Effective 0..1 amount sent to EnhancerMixer and EnhancerController. */
    val effectiveAmount01: Double
        get() = if (enabled) (sliderPercent / 100.0).coerceIn(0.0, 1.0) else 0.0
}

/**
 * Curve definitions for the four enhancers at 100 %, built once at class load.
 * Each curve is 31 values (the render bands from v5 Section 5.1: 20 * 2^(i/3) Hz), in dB.
 * B = gaussian bump in log2(f), L = low shelf (half the gain at its corner).
 * Two variants: FULL (0: wired headphones, USB, Bluetooth) and SMALL (1: built-in speaker).
 */
object EnhancerCurves {
    private class Term(val kind: Char, val hz: Double, val db: Double, val width: Double)

    private fun b(hz: Double, db: Double, oct: Double) = Term('B', hz, db, oct)
    private fun l(hz: Double, db: Double, w: Double) = Term('L', hz, db, w)

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    // [enhancer ordinal][0 = full, 1 = small]
    private val SPEC: Array<Array<List<Term>>> = arrayOf(
        // DEEP_BASS
        arrayOf(
            listOf(b(55.0, 4.5, 0.55), b(140.0, 1.5, 0.40), b(300.0, -1.0, 0.50), l(24.0, -4.0, 0.25)),
            listOf(b(110.0, 3.5, 0.45), b(170.0, 1.5, 0.40), b(300.0, -1.0, 0.50))
        ),
        // SURROUND (tonal part only; the spatial part is the Virtualizer)
        arrayOf(
            listOf(b(10000.0, 1.0, 0.60)),
            listOf(b(10000.0, 1.0, 0.60))
        ),
        // BEATS
        arrayOf(
            listOf(b(70.0, 2.5, 0.45), b(200.0, 1.5, 0.35), b(320.0, -1.5, 0.50), b(4200.0, 2.5, 0.50), b(9000.0, 1.0, 0.50)),
            listOf(b(120.0, 2.5, 0.45), b(200.0, 1.5, 0.35), b(320.0, -1.5, 0.50), b(4200.0, 2.5, 0.50), b(9000.0, 1.0, 0.50))
        ),
        // VOCAL
        arrayOf(
            listOf(b(300.0, -1.5, 0.50), b(1000.0, 1.0, 0.50), b(2800.0, 3.0, 0.45), b(5000.0, 1.0, 0.40), b(7000.0, -1.5, 0.30), b(11000.0, 1.0, 0.50)),
            listOf(b(300.0, -1.5, 0.50), b(1000.0, 1.0, 0.50), b(2800.0, 3.0, 0.45), b(5000.0, 1.0, 0.40), b(7000.0, -1.5, 0.30), b(11000.0, 1.0, 0.50))
        )
    )

    /** Same grid as Dsp.RENDER_HZ: 20 * 2^(i/3). Kept local so this file has no startup-order dependency. */
    private val LOG_F = DoubleArray(31) { log2(20.0 * 2.0.pow(it / 3.0)) }

    val CURVE: Array<Array<DoubleArray>> = Array(4) { e ->
        Array(2) { v ->
            DoubleArray(31) { i ->
                val x = LOG_F[i]
                var sum = 0.0
                for (t in SPEC[e][v]) {
                    val c = log2(t.hz)
                    sum += if (t.kind == 'B') {
                        val z = (x - c) / t.width
                        t.db * exp(-0.5 * z * z)
                    } else {
                        t.db / (1.0 + exp((x - c) / t.width))
                    }
                }
                sum
            }
        }
    }
}

/** Result of an enhancer mix, so the UI can show "Enhancer budget: X of 6 dB" and "Limited by headroom budget". */
data class MixResult(
    val peakDb: Double,
    val scale: Double
) {
    val isBudgetLimited: Boolean
        get() = scale < 0.999
}

object EnhancerMixer {
    /** Total boost the four enhancers may add together. Above this, all four are scaled by the same factor. */
    const val BUDGET_DB = 6.0

    /**
     * amounts: 4 values in 0..1, indexed by Enhancer ordinal. out: 31 values, overwritten. No allocation.
     * The scale is continuous in the amounts, so there is no jump when the budget starts to apply.
     */
    fun mix(amounts: DoubleArray, smallDriver: Boolean, out: DoubleArray): MixResult {
        val v = if (smallDriver) 1 else 0
        Arrays.fill(out, 0.0)
        for (e in 0 until 4) {
            val a = amounts.getOrElse(e) { 0.0 }
            if (a <= 0.0) continue
            val c = EnhancerCurves.CURVE[e][v]
            for (i in 0 until 31) out[i] += a * c[i]
        }
        var peak = 0.0
        for (i in 0 until 31) peak = max(peak, out[i])
        var scale = 1.0
        if (peak > BUDGET_DB) {
            scale = BUDGET_DB / peak
            for (i in 0 until 31) out[i] *= scale
            peak = BUDGET_DB
        }
        return MixResult(peakDb = peak, scale = scale)
    }

    /** Convenience helper returning a newly allocated 31-band array + MixResult for tests and UI previews. */
    fun mixToNewArray(amounts: DoubleArray, smallDriver: Boolean): Pair<DoubleArray, MixResult> {
        val out = DoubleArray(31)
        val res = mix(amounts, smallDriver, out)
        return out to res
    }

    /** True when any enhancer is active. Flat + enhancers must NOT bypass the chain. */
    fun anyActive(amounts: DoubleArray): Boolean = amounts.any { it > 0.0005 }
}

/**
 * MBC changes that go with the enhancers. Applied on commit only, never while a slider is dragged.
 */
object EnhancerMbc {
    private val BEATS_ATT = floatArrayOf(50f, 30f, 25f, 12f, 4f, 5f)
    private val BEATS_REL = floatArrayOf(250f, 150f, 120f, 80f, 50f, 50f)
    private val BEATS_RAT = floatArrayOf(3.0f, 2.8f, 2.2f, 2.5f, 2.8f, 2.0f)

    private fun geo(a: Float, b: Float, t: Double): Float =
        exp(ln(a.toDouble()) * (1.0 - t) + ln(b.toDouble()) * t).toFloat()

    fun blend(base: MbcConfig, amounts: DoubleArray): MbcConfig {
        val bass = amounts.getOrElse(Enhancer.DEEP_BASS.ordinal) { 0.0 }.coerceIn(0.0, 1.0)
        val beats = amounts.getOrElse(Enhancer.BEATS.ordinal) { 0.0 }.coerceIn(0.0, 1.0)
        val vocal = amounts.getOrElse(Enhancer.VOCAL.ordinal) { 0.0 }.coerceIn(0.0, 1.0)
        val att = FloatArray(6) { geo(base.attackMs[it], BEATS_ATT[it], beats) }
        val rel = FloatArray(6) { geo(base.releaseMs[it], BEATS_REL[it], beats) }
        val rat = FloatArray(6) { (base.ratio[it] * (1.0 - beats) + BEATS_RAT[it] * beats).toFloat() }
        val thr = base.baseThresholdDb.copyOf()
        rel[0] += (70.0 * bass).toFloat()   // Sub: longer release, steadier sustained bass
        rel[1] += (40.0 * bass).toFloat()   // Punch: longer release
        thr[4] -= (2.0 * vocal).toFloat()   // Sizzle band (5.7-11.5 kHz): tighter, de-harsh sibilance
        rat[4] += (1.0 * vocal).toFloat()
        return MbcConfig(
            attackMs = att,
            releaseMs = rel,
            ratio = rat,
            baseThresholdDb = thr,
            kneeDb = base.kneeDb.copyOf()
        )
    }
}

/**
 * PCHIP render with precomputed basis values (Section 5 of the Enhancer spec).
 * Mathematically identical to `Dsp.renderCurve` (max difference < 1e-12 dB over 2000 random slider sets),
 * with zero per-band slope array allocations.
 */
object FastRender {
    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    private val X = DoubleArray(10) { log2(Dsp.SLIDER_HZ[it]) }
    private val H = DoubleArray(9) { X[it + 1] - X[it] }
    private val SEG = IntArray(31) // -1 below first slider, -2 above last, else segment index 0..8
    private val B = Array(31) { DoubleArray(4) }

    init {
        for (i in 0 until 31) {
            val x = log2(Dsp.RENDER_HZ[i])
            when {
                x <= X[0] -> SEG[i] = -1
                x >= X[9] -> SEG[i] = -2
                else -> {
                    var k = 0
                    while (x > X[k + 1]) k++
                    val t = (x - X[k]) / H[k]
                    val t2 = t * t
                    val t3 = t2 * t
                    SEG[i] = k
                    B[i][0] = 2 * t3 - 3 * t2 + 1
                    B[i][1] = t3 - 2 * t2 + t
                    B[i][2] = -2 * t3 + 3 * t2
                    B[i][3] = t3 - t2
                }
            }
        }
    }

    /** Thread-safe zero-allocation render using caller-provided or local scratch buffers. */
    @Synchronized
    fun render(y: DoubleArray, out: DoubleArray) {
        val d0 = (y[1] - y[0]) / H[0]
        val d1 = (y[2] - y[1]) / H[1]
        val d2 = (y[3] - y[2]) / H[2]
        val d3 = (y[4] - y[3]) / H[3]
        val d4 = (y[5] - y[4]) / H[4]
        val d5 = (y[6] - y[5]) / H[5]
        val d6 = (y[7] - y[6]) / H[6]
        val d7 = (y[8] - y[7]) / H[7]
        val d8 = (y[9] - y[8]) / H[8]

        val dArr = doubleArrayOf(d0, d1, d2, d3, d4, d5, d6, d7, d8)
        val m = DoubleArray(10)
        for (i in 1 until 9) {
            m[i] = if (dArr[i - 1] * dArr[i] <= 0.0) {
                0.0
            } else {
                val w1 = 2 * H[i] + H[i - 1]
                val w2 = H[i] + 2 * H[i - 1]
                (w1 + w2) / (w1 / dArr[i - 1] + w2 / dArr[i])
            }
        }
        for (i in 0 until 31) {
            val k = SEG[i]
            out[i] = when (k) {
                -1 -> y[0]
                -2 -> y[9]
                else -> B[i][0] * y[k] + B[i][1] * H[k] * m[k] + B[i][2] * y[k + 1] + B[i][3] * H[k] * m[k + 1]
            }
        }
    }

    fun renderToNewArray(y: DoubleArray): DoubleArray {
        val out = DoubleArray(31)
        render(y, out)
        return out
    }
}

/** What EnhancerController needs from the current preset, route and profile. */
class PresetCtx(
    val mbc: MbcConfig,
    val routeBaseDb: Float,
    val limiterDb: Float,
    val isFlat: Boolean
)

/** Runs a shell command as shell (Shizuku/ADB) or root (su). Must NOT be called on the main thread. */
fun interface ShellRunner {
    fun run(cmd: String): String?
}

/**
 * Built-in Virtualizer wrapper for Surround Audio (Section 4.1 of the Enhancer spec).
 * Debounces binder calls (moves >= 20 or 0) and selects BINAURAL on headphones vs TRANSAURAL on speaker.
 */
@Suppress("DEPRECATION")
class VirtualizerFx(sessionId: Int) {
    private var fx: Virtualizer? = try {
        Virtualizer(0, sessionId)
    } catch (_: Throwable) {
        null
    }
    private var lastStrength = -1
    private var lastMode = Int.MIN_VALUE

    /** False when the effect is missing or reports no virtualization for this route. Show "May not apply". */
    fun supported(small: Boolean): Boolean {
        val f = fx ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return try {
            val mode = if (small) {
                Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL
            } else {
                Virtualizer.VIRTUALIZATION_MODE_BINAURAL
            }
            f.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, mode)
        } catch (_: Throwable) {
            false
        }
    }

    /** a: 0..1. small: built-in speaker (transaural) versus headphones (binaural). */
    fun apply(a: Double, small: Boolean, enabledGate: Boolean = true) {
        val f = fx ?: return
        try {
            val effectiveA = if (enabledGate) a.coerceIn(0.0, 1.0) else 0.0
            val maxS = if (small) 0.6 else 0.8
            val strength = (1000.0 * maxS * effectiveA.pow(1.3)).roundToInt().coerceIn(0, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val mode = if (small) {
                    Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL
                } else {
                    Virtualizer.VIRTUALIZATION_MODE_BINAURAL
                }
                if (mode != lastMode) {
                    f.forceVirtualizationMode(mode)
                    lastMode = mode
                }
            }
            if (!f.strengthSupported) {
                val on = effectiveA >= 0.5
                val flag = if (on) 1 else 0
                if (flag != lastStrength) {
                    f.setStrength((if (on) 1000 else 0).toShort())
                    lastStrength = flag
                }
                f.enabled = on
                return
            }
            if (abs(strength - lastStrength) >= 20 || strength == 0 || lastStrength < 0) {
                f.setStrength(strength.toShort())
                lastStrength = strength
            }
            f.enabled = strength > 0
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            fx?.release()
        } catch (_: Throwable) {
        }
        fx = null
    }
}

/**
 * Legacy Tier 1 BassBoost wrapper (Section 4.2 of the Enhancer spec).
 * Only used when DynamicsProcessing is NOT active (when DynamicsProcessing is active, BassBoost is forced to 0).
 */
class BassBoostFx(sessionId: Int) {
    private var fx: BassBoost? = try {
        BassBoost(0, sessionId)
    } catch (_: Throwable) {
        null
    }
    private var last = -1

    fun apply(a: Double, enabledGate: Boolean = true) {
        val f = fx ?: return
        try {
            val effectiveA = if (enabledGate) a.coerceIn(0.0, 1.0) else 0.0
            val s = (600.0 * effectiveA).roundToInt().coerceIn(0, 1000)
            if (s != last) {
                if (f.strengthSupported) {
                    f.setStrength(s.toShort())
                }
                f.enabled = s > 0
                last = s
            }
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            fx?.release()
        } catch (_: Throwable) {
        }
        fx = null
    }
}

/**
 * Optional Dolby-port properties (`dolby.deepbass`, `dolby.bass`) for devices that expose them (Section 4.3).
 * Coalesces requests onto a single daemon thread and restores original values on Reset or exit.
 * Strictly RAM-only (`setprop`), never writes to `/system` or `/vendor`.
 */
class DolbyControl(private val shell: ShellRunner) {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dolby-props").apply { isDaemon = true }
    }

    @Volatile
    private var pending: Pair<Boolean, Double>? = null
    private var origDeep: String? = null
    private var origBass: String? = null
    private var saved = false

    private fun get(name: String): String = (shell.run("getprop $name") ?: "").trim()
    private fun set(name: String, v: Int) {
        shell.run("setprop $name $v")
    }

    /** True when either property already has a value on the device. */
    fun detect(): Boolean = try {
        get("dolby.deepbass").isNotEmpty() || get("dolby.bass").isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /**
     * deep: `dolby.deepbass` 1/0.
     * a: 0..1 -> `dolby.bass` 0..MAX_BASS (96, half of the 192 36-dB limit).
     */
    fun apply(deep: Boolean, a: Double) {
        pending = deep to a
        io.execute {
            val req = pending ?: return@execute
            pending = null
            try {
                if (!saved) {
                    origDeep = get("dolby.deepbass")
                    origBass = get("dolby.bass")
                    saved = true
                }
                set("dolby.deepbass", if (req.first) 1 else 0)
                val bassVal = if (req.first) (MAX_BASS * req.second).roundToInt().coerceIn(0, MAX_BASS) else 0
                set("dolby.bass", bassVal)
            } catch (_: Throwable) {
            }
        }
    }

    /** Restores original property values captured before the first apply (or 0 if unset). */
    fun restore() {
        io.execute {
            try {
                if (!saved) return@execute
                set("dolby.deepbass", origDeep?.toIntOrNull() ?: 0)
                set("dolby.bass", origBass?.toIntOrNull() ?: 0)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val MAX_BASS = 96
    }
}
