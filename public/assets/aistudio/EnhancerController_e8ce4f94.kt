package com.yourapp.eq.enh

import android.media.AudioFormat
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.os.Build
import com.yourapp.eq.EqEngine
import com.yourapp.eq.Layers
import com.yourapp.eq.Mbc
import com.yourapp.eq.Route
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the controller needs from the current preset, route and profile. Rebuilt by the app on those changes. */
class PresetCtx(val mbc: Mbc, val routeBaseDb: Float, val limiterDb: Float, val isFlat: Boolean)

/** Runs a shell command as shell (Shizuku) or root (su). Implemented by the app's helper. Must NOT be called on the main thread. */
fun interface ShellRunner { fun run(cmd: String): String? }

// ------------------------------------------------------------------------------------------------
// Built-in effects. Strength setters are binder calls, so they are debounced and skipped when unchanged.
// ------------------------------------------------------------------------------------------------

class VirtualizerFx(sessionId: Int) {
    private var fx: Virtualizer? = try { Virtualizer(0, sessionId) } catch (e: Exception) { null }
    private var lastStrength = -1
    private var lastMode = Int.MIN_VALUE

    /** False when the effect is missing or reports no virtualization for this route. Show "may not apply". */
    fun supported(small: Boolean): Boolean {
        val f = fx ?: return false
        if (Build.VERSION.SDK_INT < 28) return true       // cannot ask; assume yes and let the user judge by ear
        return try {
            f.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, if (small) Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL else Virtualizer.VIRTUALIZATION_MODE_BINAURAL)
        } catch (e: Exception) { false }
    }

    /** a: 0..1. small: built-in speaker (transaural) versus headphones (binaural). */
    fun apply(a: Double, small: Boolean) {
        val f = fx ?: return
        try {
            val maxS = if (small) 0.6 else 0.8
            val strength = (1000.0 * maxS * a.pow(1.3)).roundToInt().coerceIn(0, 1000)
            if (Build.VERSION.SDK_INT >= 28) {
                val mode = if (small) Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL else Virtualizer.VIRTUALIZATION_MODE_BINAURAL
                if (mode != lastMode) { f.forceVirtualizationMode(mode); lastMode = mode }
            }
            if (!f.strengthSupported) {                  // on/off only
                val on = a >= 0.5
                if ((if (on) 1 else 0) != lastStrength) { f.setStrength((if (on) 1000 else 0).toShort()); lastStrength = if (on) 1 else 0 }
                f.enabled = on
                return
            }
            if (abs(strength - lastStrength) >= 20 || strength == 0 || lastStrength < 0) {
                f.setStrength(strength.toShort()); lastStrength = strength
            }
            f.enabled = strength > 0
        } catch (e: Exception) { /* effect lost: the next apply() retries */ }
    }

    private fun abs(x: Int) = if (x < 0) -x else x
    fun release() { try { fx?.release() } catch (_: Exception) {}; fx = null }
}

/** Legacy tier 1 only. When DynamicsProcessing is active this must be 0 (section 13). */
class BassBoostFx(sessionId: Int) {
    private var fx: BassBoost? = try { BassBoost(0, sessionId) } catch (e: Exception) { null }
    private var last = -1
    fun apply(a: Double) {
        val f = fx ?: return
        try {
            val s = (600.0 * a).roundToInt().coerceIn(0, 1000)
            if (s != last) { f.setStrength(s.toShort()); f.enabled = s > 0; last = s }
        } catch (_: Exception) {}
    }
    fun release() { try { fx?.release() } catch (_: Exception) {}; fx = null }
}

// ------------------------------------------------------------------------------------------------
// Optional Dolby-port properties (dolby.deepbass, dolby.bass). Only for devices that have them.
// ------------------------------------------------------------------------------------------------

class DolbyControl(private val shell: ShellRunner) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dolby-props").apply { isDaemon = true } }
    @Volatile private var pending: Pair<Boolean, Double>? = null
    private var origDeep: String? = null
    private var origBass: String? = null
    private var saved = false

    /** Values are numbers only; nothing user-typed ever reaches the shell string. */
    private fun get(name: String): String = (shell.run("getprop $name") ?: "").trim()
    private fun set(name: String, v: Int) { shell.run("setprop $name $v") }

    /** True when either property already has a value. A device without them returns empty strings. */
    fun detect(): Boolean = try { get("dolby.deepbass").isNotEmpty() || get("dolby.bass").isNotEmpty() } catch (e: Exception) { false }

    /**
     * deep: dolby.deepbass 1/0. a: 0..1 -> dolby.bass 0..MAX (0 = unmodified).
     * MAX is 96 on purpose: the notes say values above 192 hit a 36 dB limit, and 96 is half of that.
     * Calls are coalesced: only the newest request is run, on a single background thread.
     */
    fun apply(deep: Boolean, a: Double) {
        pending = deep to a
        io.execute {
            val req = pending ?: return@execute
            pending = null
            try {
                if (!saved) { origDeep = get("dolby.deepbass"); origBass = get("dolby.bass"); saved = true }
                set("dolby.deepbass", if (req.first) 1 else 0)
                set("dolby.bass", (MAX_BASS * req.second).roundToInt().coerceIn(0, MAX_BASS))
            } catch (_: Exception) {}
        }
    }

    /** Put back what was there at the first apply (or 0 = unmodified when it was unset). Call on exit and on "Reset". */
    fun restore() {
        io.execute {
            try {
                if (!saved) return@execute
                set("dolby.deepbass", origDeep?.toIntOrNull() ?: 0)
                set("dolby.bass", origBass?.toIntOrNull() ?: 0)
            } catch (_: Exception) {}
        }
    }

    companion object { const val MAX_BASS = 96 }
}

// ------------------------------------------------------------------------------------------------
// Controller: one place that turns "amount changed" into engine and effect calls.
// ------------------------------------------------------------------------------------------------

class EnhancerController(
    private val engine: EqEngine,
    private val virt: VirtualizerFx?,
    private val bassBoost: BassBoostFx?,          // pass null unless Legacy tier 1 is the active mode
    private val dolby: DolbyControl?,             // pass null unless detect() was true or the user opted in
    private val buildLayers: (enhancer31: DoubleArray) -> Layers,
    private val ctx: () -> PresetCtx,
    private val route: () -> Route
) {
    val amounts = DoubleArray(4)                  // UI thread only
    private val enh31 = DoubleArray(31)
    private var dolbyDeep = false

    private fun small() = route() == Route.SPEAKER

    /** Slider is moving. EQ only; the engine glides 60 ms. No MBC, no makeup, no effect calls. */
    fun onDrag(e: Enhancer, value01: Double): MixResult {
        amounts[e.ordinal] = value01.coerceIn(0.0, 1.0)
        val r = EnhancerMixer.mix(amounts, small(), enh31)
        engine.update(buildLayers(enh31))
        return r
    }

    /** Finger lifted, toggle flipped, preset or route changed. Recomputes makeup and MBC, applies the effects. */
    fun onCommit() {
        EnhancerMixer.mix(amounts, small(), enh31)
        val c = ctx()
        engine.load(buildLayers(enh31), EnhancerMbc.blend(c.mbc, amounts), c.routeBaseDb, c.limiterDb)
        virt?.apply(amounts[Enhancer.SURROUND.ordinal], small())
        bassBoost?.apply(amounts[Enhancer.DEEP_BASS.ordinal])
        dolby?.apply(dolbyDeep && amounts[Enhancer.DEEP_BASS.ordinal] > 0, amounts[Enhancer.DEEP_BASS.ordinal])
        // Flat + enhancers must keep the chain on. The app decides bypass with this:
        engine.setEnabled(EnhancerMixer.anyActive(amounts) || !c.isFlat)
    }

    fun setDolbyDeepBass(on: Boolean) { dolbyDeep = on; onCommit() }

    fun reset() {
        amounts.fill(0.0)
        dolby?.restore()
        onCommit()
    }

    fun release() { virt?.release(); bassBoost?.release(); dolby?.restore() }
}
