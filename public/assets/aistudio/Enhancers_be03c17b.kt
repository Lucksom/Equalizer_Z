package com.yourapp.eq.enh

import com.yourapp.eq.Mbc
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max

/** Index order is fixed: the UI, storage and the arrays below all use it. */
enum class Enhancer { DEEP_BASS, SURROUND, BEATS, VOCAL }

/**
 * Curve definitions for the four enhancers at 100 %. Built once at class load.
 * Each curve is 31 values (the render bands from section 5.1), in dB.
 * B = gaussian bump in log2(f), L = low shelf (half the gain at its corner).
 * Two variants: FULL (headphones, USB, Bluetooth) and SMALL (built-in speaker).
 * The numbers are starting points from the reference script, not measurements.
 */
object EnhancerCurves {
    private class Term(val kind: Char, val hz: Double, val db: Double, val width: Double)

    private fun b(hz: Double, db: Double, oct: Double) = Term('B', hz, db, oct)
    private fun l(hz: Double, db: Double, w: Double) = Term('L', hz, db, w)

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
    private val LOG_F = DoubleArray(31) { log2(20.0 * Math.pow(2.0, it / 3.0)) }

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

/** Result of a mix, so the UI can show "limited by the 6 dB budget". */
class MixResult(val peakDb: Double, val scale: Double)

object EnhancerMixer {
    /** Total boost the four enhancers may add together. Above this, all four are scaled by the same factor. */
    const val BUDGET_DB = 6.0

    /**
     * amounts: 4 values in 0..1, indexed by Enhancer ordinal. out: 31 values, overwritten. No allocation.
     * The scale is continuous in the amounts, so there is no jump when the budget starts to apply.
     */
    fun mix(amounts: DoubleArray, smallDriver: Boolean, out: DoubleArray): MixResult {
        val v = if (smallDriver) 1 else 0
        java.util.Arrays.fill(out, 0.0)
        for (e in 0 until 4) {
            val a = amounts[e]
            if (a <= 0.0) continue
            val c = EnhancerCurves.CURVE[e][v]
            for (i in 0 until 31) out[i] += a * c[i]
        }
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0 until 31) peak = max(peak, out[i])
        var scale = 1.0
        if (peak > BUDGET_DB) {
            scale = BUDGET_DB / peak
            for (i in 0 until 31) out[i] *= scale
            peak = BUDGET_DB
        }
        return MixResult(peak, scale)
    }

    /** True when any enhancer is active. Flat + enhancers must NOT bypass the chain. */
    fun anyActive(amounts: DoubleArray): Boolean = amounts.any { it > 0.0005 }
}

/** MBC changes that go with the enhancers. Applied on commit only, never while a slider is dragged. */
object EnhancerMbc {
    private val BEATS_ATT = floatArrayOf(50f, 30f, 25f, 12f, 4f, 5f)
    private val BEATS_REL = floatArrayOf(250f, 150f, 120f, 80f, 50f, 50f)
    private val BEATS_RAT = floatArrayOf(3.0f, 2.8f, 2.2f, 2.5f, 2.8f, 2.0f)

    private fun geo(a: Float, b: Float, t: Double): Float =
        exp(ln(a.toDouble()) * (1 - t) + ln(b.toDouble()) * t).toFloat()

    fun blend(base: Mbc, amounts: DoubleArray): Mbc {
        val bass = amounts[Enhancer.DEEP_BASS.ordinal]
        val beats = amounts[Enhancer.BEATS.ordinal]
        val vocal = amounts[Enhancer.VOCAL.ordinal]
        val att = FloatArray(6) { geo(base.attackMs[it], BEATS_ATT[it], beats) }
        val rel = FloatArray(6) { geo(base.releaseMs[it], BEATS_REL[it], beats) }
        val rat = FloatArray(6) { (base.ratio[it] * (1 - beats) + BEATS_RAT[it] * beats).toFloat() }
        val thr = base.baseThresholdDb.copyOf()
        rel[0] += (70.0 * bass).toFloat()          // Sub: longer release, steadier sustained bass
        rel[1] += (40.0 * bass).toFloat()          // Punch
        thr[4] -= (2.0 * vocal).toFloat()          // Sizzle band: tighter, de-harsh the sibilance region
        rat[4] += (1.0 * vocal).toFloat()
        return Mbc(att, rel, rat, thr, base.kneeDb.copyOf())
    }
}
