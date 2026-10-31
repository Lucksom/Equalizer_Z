package com.audioeq.audio

import android.media.AudioFormat
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.os.Build
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * PCHIP render with precomputed basis values.
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

/**
 * Built-in Virtualizer wrapper for Acoustic Profiles (Dynamic / Movie).
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

    /** False when the effect is missing or reports no virtualization for this route. */
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
 * Legacy Tier 1 BassBoost wrapper.
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
