package com.yourapp.eq

import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/*
 * EqEngine v5: replaces the EqEngine class from section 9. Dsp, Route, Mbc and currentRoute() stay as in v4.
 * Changes against v4:
 *  1. Own HandlerThread: every binder call leaves the UI thread.
 *  2. Layers carries enhancer31 (section 20 of the enhancer spec).
 *  3. The per-tick path allocates nothing except the EqBand objects the API needs.
 *  4. PCHIP slopes are computed once per change, and the basis values per band are precomputed.
 *  5. The tick interval adapts to the measured cost of the writes.
 *  6. Fix: setEnabled(false) was undone by the enable-status listener in v4.
 * Not compiled here.
 */

class Layers(
    userDb: DoubleArray, val intensity: Double,
    routeDb: DoubleArray, contourDb: DoubleArray,
    correction31: DoubleArray = DoubleArray(31),
    enhancer31: DoubleArray = DoubleArray(31)
) {
    // Defensive copies: the UI may reuse its arrays, and the engine reads these on another thread.
    val userDb: DoubleArray = userDb.copyOf()
    val routeDb: DoubleArray = routeDb.copyOf()
    val contourDb: DoubleArray = contourDb.copyOf()
    val correction31: DoubleArray = correction31.copyOf()
    val enhancer31: DoubleArray = enhancer31.copyOf()
}

/** PCHIP render with precomputed basis values. Equal to Dsp.renderCurve (checked in the reference script). */
internal object FastRender {
    private val X = DoubleArray(10) { log2(Dsp.SLIDER_HZ[it]) }
    private val H = DoubleArray(9) { X[it + 1] - X[it] }
    private val SEG = IntArray(31)             // -1 below the first slider, -2 above the last, else segment index
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
                    val t = (x - X[k]) / H[k]; val t2 = t * t; val t3 = t2 * t
                    SEG[i] = k
                    B[i][0] = 2 * t3 - 3 * t2 + 1
                    B[i][1] = t3 - 2 * t2 + t
                    B[i][2] = -2 * t3 + 3 * t2
                    B[i][3] = t3 - t2
                }
            }
        }
    }

    // Scratch buffers: only touched from the engine thread.
    private val d = DoubleArray(9)
    private val m = DoubleArray(10)

    fun render(y: DoubleArray, out: DoubleArray) {
        for (i in 0 until 9) d[i] = (y[i + 1] - y[i]) / H[i]
        m[0] = 0.0; m[9] = 0.0
        for (i in 1 until 9) {
            m[i] = if (d[i - 1] * d[i] <= 0.0) 0.0 else {
                val w1 = 2 * H[i] + H[i - 1]; val w2 = H[i] + 2 * H[i - 1]
                (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
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
}

class EqEngine(private val sessionId: Int) {
    private val thread = HandlerThread("eq-writer", Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
    val handler = Handler(thread.looper)

    // ---- state below is touched only on `handler`'s thread ----
    private var dp: DynamicsProcessing? = null
    private var wantEnabled = true
    private val cur = DoubleArray(31)
    private val goal = DoubleArray(31)
    private val slider = DoubleArray(10)
    private val taste = DoubleArray(31)
    private var tauMs = 60.0
    private var mbc: Mbc? = null
    private var inputDb = -3f
    private var makeupDb = 0f
    private var limiterDb = -1f
    private val sentEq = DoubleArray(31) { Double.NaN }
    private val sentThr = DoubleArray(6) { Double.NaN }
    private var fixedDirty = true
    private var mbcDirty = true
    private var tickMs = 30L
    private var writeEmaMs = 0.0

    /** Call once. False means this session cannot host DynamicsProcessing: fall back through the mode order. */
    fun start(): Boolean = try {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, 31, true, 6, false, 0, true
        ).build()
        dp = DynamicsProcessing(0, sessionId, cfg).also { d ->
            d.setControlStatusListener { _, granted ->
                if (granted) handler.post { invalidate(); writeNow() }
            }
            d.setEnableStatusListener { _, on ->
                if (!on && wantEnabled) handler.post { try { dp?.enabled = true } catch (_: Exception) {} }
            }
            d.enabled = true
        }
        true
    } catch (e: Exception) { dp = null; false }

    /** Fills `out` with the 31 target gains. No allocation. */
    private fun effectiveInto(l: Layers, withContour: Boolean, out: DoubleArray) {
        for (i in 0 until 10) {
            slider[i] = l.userDb[i] * l.intensity + l.routeDb[i] + (if (withContour) l.contourDb[i] else 0.0)
        }
        FastRender.render(slider, taste)
        for (i in 0 until 31) out[i] = (taste[i] + l.correction31[i] + l.enhancer31[i]).coerceIn(-15.0, 10.0)
    }

    /**
     * Preset, route, profile, intensity or enhancer COMMIT. Never while a slider is being dragged.
     * `m` already includes the enhancer MBC blend (EnhancerMbc.blend).
     */
    fun load(l: Layers, m: Mbc, routeBaseDb: Float, limiter: Float, glideMs: Double = 250.0) {
        handler.post {
            val base = DoubleArray(31)
            effectiveInto(l, false, base)
            var corrMax = 0.0
            for (v in l.correction31) corrMax = max(corrMax, v)
            inputDb = routeBaseDb - max(0.0, corrMax - 3.0).toFloat()
            makeupDb = (-inputDb - Dsp.pinkGainDb(base)).coerceIn(0.0, 4.0).toFloat()
            limiterDb = limiter; mbc = m
            fixedDirty = true; mbcDirty = true
            sentThr.fill(Double.NaN)
            effectiveInto(l, true, goal); tauMs = glideMs
            scheduleTick()
        }
    }

    /** Slider drags, enhancer drags and volume changes. Never touches input gain, makeup or MBC parameters. */
    fun update(l: Layers, glideMs: Double = 60.0) {
        handler.post {
            effectiveInto(l, true, goal); tauMs = glideMs
            scheduleTick()
        }
    }

    fun setEnabled(on: Boolean) {
        handler.post { wantEnabled = on; try { dp?.enabled = on } catch (_: Exception) {} }
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(tick)
            try { dp?.release() } catch (_: Exception) {}
            dp = null
            thread.quitSafely()
        }
    }

    private fun invalidate() { sentEq.fill(Double.NaN); sentThr.fill(Double.NaN); fixedDirty = true; mbcDirty = true }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val k = 1 - exp(-tickMs.toDouble() / tauMs)
            var settled = true
            for (i in 0 until 31) {
                cur[i] += (goal[i] - cur[i]) * k
                if (abs(goal[i] - cur[i]) < 0.03) cur[i] = goal[i] else settled = false
            }
            val t0 = System.nanoTime()
            writeNow()
            val ms = (System.nanoTime() - t0) / 1e6
            writeEmaMs = if (writeEmaMs == 0.0) ms else writeEmaMs * 0.8 + ms * 0.2
            // If writes are slow, back off instead of queueing more binder calls. 30 ms floor, 100 ms ceiling.
            tickMs = (writeEmaMs * 2.5).toLong().coerceIn(30L, 100L)
            if (!settled) handler.postDelayed(this, tickMs)
        }
    }

    /** Average write cost in ms, for a debug overlay. */
    fun writeCostMs(): Double = writeEmaMs

    private fun writeNow() {
        val d = dp ?: return
        val m = mbc ?: return
        try {
            if (fixedDirty) {
                d.setInputGainAllChannelsTo(inputDb)
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 50f, 20f, limiterDb, 0f))
                fixedDirty = false
            }
            for (i in 0 until 31) {
                if (sentEq[i].isNaN() || abs(cur[i] - sentEq[i]) > 0.05) {
                    d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, Dsp.RENDER_CUT[i], cur[i].toFloat()))
                    sentEq[i] = cur[i]
                }
            }
            for (b in 0 until 6) {
                val r = Dsp.MBC_RANGE[b]
                var s = 0.0
                for (i in r) s += cur[i]
                val thr = m.baseThresholdDb[b] + max(0.0, s / (r.last - r.first + 1))
                if (mbcDirty || sentThr[b].isNaN() || abs(thr - sentThr[b]) > 0.1) {
                    d.setMbcBandAllChannelsTo(b, DynamicsProcessing.MbcBand(
                        true, Dsp.MBC_CUT[b], m.attackMs[b], m.releaseMs[b], m.ratio[b],
                        thr.toFloat(), m.kneeDb[b], -90f, 1f, 0f, makeupDb))
                    sentThr[b] = thr
                }
            }
            mbcDirty = false
        } catch (e: Exception) { /* log it; the caller falls back through the mode order */ }
    }
}
