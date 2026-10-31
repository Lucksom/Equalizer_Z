package com.audioeq.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.audioeq.model.EqConstants
import com.audioeq.model.HeadphoneProfile
import com.audioeq.model.MbcConfig
import com.audioeq.model.OutputRoute
import com.audioeq.model.PersonalHearingCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Dsp {
    val SLIDER_HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val RENDER_HZ = DoubleArray(31) { 20.0 * 2.0.pow(it / 3.0) }
    val RENDER_CUT = FloatArray(31) { min(RENDER_HZ[it] * 2.0.pow(1.0 / 6.0), 20000.0).toFloat() }
    val MBC_RANGE = arrayOf(0..6, 7..12, 13..18, 19..24, 25..27, 28..30)
    val MBC_CUT = floatArrayOf(RENDER_CUT[6], RENDER_CUT[12], RENDER_CUT[18], RENDER_CUT[24], RENDER_CUT[27], 20000f)

    // 10-band fallback cutoffs (v5 Section 5.2)
    val FALLBACK_10_CUT = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
    val FALLBACK_10_MBC_CUT = floatArrayOf(88f, 354f, 1414f, 5657f, 11314f, 20000f)
    val FALLBACK_10_MBC_RANGE = arrayOf(0..1, 2..3, 4..5, 6..7, 8..8, 9..9)

    // Volume contour table (v5 Section 7)
    val CONTOUR_VS = doubleArrayOf(0.6, 0.4, 0.25, 0.1)
    val CONTOUR_ROWS = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        doubleArrayOf(1.5, 1.5, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5),
        doubleArrayOf(3.0, 3.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.5),
        doubleArrayOf(4.5, 4.5, 3.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.5, 2.0)
    )

    // Speaker calibration target anchors (v5 Section 8.2)
    val SPK_ANCHORS = arrayOf(
        200.0 to -2.0,
        250.0 to -1.0,
        315.0 to 0.0,
        2000.0 to 0.0,
        4000.0 to -1.5,
        8000.0 to -3.0,
        10000.0 to -3.5
    )

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    
    fun pchip(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = DoubleArray(n) // end slopes stay 0: flat beyond the ends
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0.0) {
                0.0
            } else {
                val w1 = 2 * h[i] + h[i - 1]
                val w2 = h[i] + 2 * h[i - 1]
                (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        val k = (0 until n - 1).first { x <= xs[it + 1] }
        val t = (x - xs[k]) / h[k]
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[k] +
            (t3 - 2 * t2 + t) * h[k] * m[k] +
            (-2 * t3 + 3 * t2) * ys[k + 1] +
            (t3 - t2) * h[k] * m[k + 1]
    }

    fun renderCurve(sliderDb: DoubleArray): DoubleArray {
        val xs = DoubleArray(10) { log2(SLIDER_HZ[it]) }
        return DoubleArray(31) { pchip(xs, sliderDb, log2(RENDER_HZ[it])) }
    }

    
    fun pinkGainDb(db: DoubleArray): Double {
        var s = 0.0
        var w = 0.0
        for (i in 0 until 31) {
            val wi = if (RENDER_HZ[i] < 100.0) 0.5 else 1.0
            s += wi * 10.0.pow(db[i] / 10.0)
            w += wi
        }
        return 10.0 * log10(s / w)
    }

    
    fun contour(
        v: Double,
        vs: DoubleArray = CONTOUR_VS,
        rows: Array<DoubleArray> = CONTOUR_ROWS
    ): DoubleArray {
        if (v >= vs[0]) return rows[0].clone()
        if (v <= vs.last()) return rows.last().clone()
        val k = (0 until vs.size - 1).first { v >= vs[it + 1] }
        val t = (vs[k] - v) / (vs[k] - vs[k + 1])
        return DoubleArray(10) { rows[k][it] + (rows[k + 1][it] - rows[k][it]) * t }
    }

    
    fun computeHeadphoneCorrection31(
        profile: HeadphoneProfile?,
        strength: Double = 0.8,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): DoubleArray {
        if (profile == null || (profile.filters.isEmpty() && profile.rawText.isNullOrBlank())) {
            return if (abs(tiltBassDb) > 0.01 || abs(tiltTrebleDb) > 0.01) {
                AutoEq.peqToCorrection31(emptyList(), strength, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
            } else {
                DoubleArray(31)
            }
        }
        val raw = profile.rawText
        if (!raw.isNullOrBlank()) {
            val peq = AutoEq.parsePeq(raw)
            if (peq.filters.isNotEmpty()) {
                return AutoEq.peqToCorrection31(peq.filters, strength, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
            }
            val graphic = AutoEq.parseGraphic(raw)
            if (graphic.size >= 4) {
                return AutoEq.graphicToCorrection31(graphic, strength, fullRange, tiltBassDb, tiltTrebleDb)
            }
        }
        val peqFilters = AutoEq.fromBuiltInProfile(profile)
        return AutoEq.peqToCorrection31(peqFilters, strength, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
    }

    fun computeHeadphoneTrimmedDb(
        profile: HeadphoneProfile?,
        fullRange: Boolean = false,
        tiltBassDb: Double = 0.0,
        tiltTrebleDb: Double = 0.0
    ): Double {
        if (profile == null) return 0.0
        val raw = profile.rawText
        val peqFilters = if (!raw.isNullOrBlank()) {
            AutoEq.parsePeq(raw).filters.ifEmpty { AutoEq.fromBuiltInProfile(profile) }
        } else {
            AutoEq.fromBuiltInProfile(profile)
        }
        return AutoEq.computePeqTrimmedDb(peqFilters, 44100.0, fullRange, tiltBassDb, tiltTrebleDb)
    }

    
    fun computeHearingCurve31(earOffsets6Db: List<Float>, strength: Float = 0.5f): DoubleArray {
        if (earOffsets6Db.size < 6 || earOffsets6Db.all { abs(it) < 0.05f }) return DoubleArray(31)
        val xs = DoubleArray(6) { log2(EqConstants.HEARING_TEST_FREQS_HZ[it]) }
        val ys = DoubleArray(6) { earOffsets6Db[it].toDouble().coerceIn(-6.0, 6.0) }
        val s = strength.toDouble().coerceIn(0.0, 1.0)
        return DoubleArray(31) { i ->
            val f = RENDER_HZ[i]
            val t = taper(f)
            (pchip(xs, ys, log2(f)) * s * t).coerceIn(-6.0, 6.0)
        }
    }

    private fun taper(f: Double): Double = AutoEq.taper(f)

    fun speakerTarget(f: Double): Double {
        val a = SPK_ANCHORS
        if (f <= a.first().first) return a.first().second
        if (f >= a.last().first) return a.last().second
        val k = (0 until a.size - 1).first { f <= a[it + 1].first }
        val t = (log2(f) - log2(a[k].first)) / (log2(a[k + 1].first) - log2(a[k].first))
        return a[k].second + (a[k + 1].second - a[k].second) * t
    }

    
    fun computeSpeakerCorrection(
        measDb: DoubleArray,
        valid: BooleanArray,
        strength: Double = 0.7
    ): DoubleArray {
        val refIndices = (0 until 31).filter { RENDER_HZ[it] in 500.0..2000.0 }
        val ref = if (refIndices.isNotEmpty()) refIndices.map { measDb[it] }.average() else 0.0
        val raw = DoubleArray(31) { speakerTarget(RENDER_HZ[it]) - (measDb[it] - ref) }
        return DoubleArray(31) { i ->
            val f = RENDER_HZ[i]
            val spkTaper = when {
                f < 200.0 -> max(0.0, 1.0 + log2(f / 200.0)) // zero at 100 Hz
                f > 10000.0 -> max(0.0, 1.0 - log2(f / 10000.0)) // zero at 20 kHz
                else -> 1.0
            }
            val narrowDip = raw[i] > 0.0 && i in 1..29 && !(raw[i - 1] > 0.0 && raw[i + 1] > 0.0)
            val v = if (narrowDip) min(raw[i], 1.5) else raw[i] // do not fill narrow dips
            if (!valid[i]) 0.0 else (v * strength * spkTaper).coerceIn(-12.0, if (f > 6000.0) 3.0 else 6.0)
        }
    }

    
    fun computeEffective31(layers: Layers, withContour: Boolean = true, channel: Int = 0): DoubleArray {
        val uDb = if (channel == 1 && layers.rightUserDb != null) layers.rightUserDb else layers.userDb
        val hearing31 = if (channel == 1) layers.hearingRight31 else layers.hearingLeft31
        val slider = DoubleArray(10) {
            uDb[it] * layers.intensity +
                layers.enhancer10Db.getOrElse(it) { 0.0 } +
                layers.routeDb[it] +
                (if (withContour) layers.contourDb[it] else 0.0)
        }
        val taste = DoubleArray(31)
        FastRender.render(slider, taste)
        return DoubleArray(31) {
            (taste[it] + layers.correction31[it] + hearing31[it]).coerceIn(-15.0, 15.0)
        }
    }

    fun computeCeilingLoadDb(effective31: DoubleArray): Float {
        val maxEff = effective31.maxOrNull() ?: 0.0
        return max(0.0, maxEff - 3.0).toFloat()
    }
}

fun detectCurrentRoute(am: AudioManager): OutputRoute {
    val types = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    } catch (_: Throwable) {
        emptyList()
    }
    val ble = Build.VERSION.SDK_INT >= 31 && types.contains(AudioDeviceInfo.TYPE_BLE_HEADSET)
    return when {
        ble || types.contains(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) -> OutputRoute.BLUETOOTH
        types.contains(AudioDeviceInfo.TYPE_USB_HEADSET) || types.contains(AudioDeviceInfo.TYPE_USB_DEVICE) -> OutputRoute.USB
        types.contains(AudioDeviceInfo.TYPE_WIRED_HEADPHONES) || types.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET) -> OutputRoute.WIRED
        else -> OutputRoute.SPEAKER
    }
}

fun detectConnectedDeviceProductName(am: AudioManager): String? {
    return try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }?.productName?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}

fun buildCurrentDeviceKey(am: AudioManager): String {
    val route = detectCurrentRoute(am)
    if (route == OutputRoute.SPEAKER) return "speaker:${Build.MODEL}"
    val dev = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    } catch (_: Throwable) {
        null
    }
    val prod = dev?.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
    val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try { dev?.address?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }
    } else null
    return if (addr != null) "${route.id}:$prod:$addr" else "${route.id}:$prod"
}

object UsbBitPerfectHelper {
    fun isBitPerfectSupported(am: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val usbDev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            } ?: return false
            val supported = am.getSupportedMixerAttributes(usbDev)
            supported.any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        } catch (_: Throwable) {
            false
        }
    }

    fun setBitPerfectEnabled(am: AudioManager, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val usbDev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            } ?: return false
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            if (!enabled) {
                am.clearPreferredMixerAttributes(attrs, usbDev)
                true
            } else {
                val bitPerfectAttr = am.getSupportedMixerAttributes(usbDev).firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                } ?: return false
                am.setPreferredMixerAttributes(attrs, usbDev, bitPerfectAttr)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}

class Layers(
    userDb: DoubleArray,
    val intensity: Double,
    routeDb: DoubleArray,
    contourDb: DoubleArray,
    correction31: DoubleArray = DoubleArray(31),
    val balanceDb: Float = 0f, // -6..+6 dB (F4)
    rightUserDb: DoubleArray? = null, // Independent Right channel 10-band EQ (F4)
    hearingLeft31: DoubleArray = DoubleArray(31), // Personal hearing curve Left (F5)
    hearingRight31: DoubleArray = DoubleArray(31),
    enhancer10Db: DoubleArray = DoubleArray(10)
) {
    val userDb: DoubleArray = userDb.copyOf()
    val routeDb: DoubleArray = routeDb.copyOf()
    val contourDb: DoubleArray = contourDb.copyOf()
    val correction31: DoubleArray = correction31.copyOf()
    val rightUserDb: DoubleArray? = rightUserDb?.copyOf()
    val hearingLeft31: DoubleArray = hearingLeft31.copyOf()
    val hearingRight31: DoubleArray = hearingRight31.copyOf()
    val enhancer10Db: DoubleArray = enhancer10Db.copyOf()

    fun hasIndependentChannels(): Boolean {
        return abs(balanceDb) > 0.02f ||
            this.rightUserDb != null ||
            this.hearingLeft31.any { abs(it) > 0.02 } ||
            this.hearingRight31.any { abs(it) > 0.02 }
    }
}

class EqEngine(
    val sessionId: Int,
    private val compatibility10Band: Boolean = false,
    private val transientMode: Boolean = false
) {
    private val thread = HandlerThread("eq-writer-$sessionId", Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
    val handler = Handler(thread.looper)

    @Volatile
    private var dp: DynamicsProcessing? = null
    @Volatile
    var activeBandCount: Int = 31
        private set

    @Volatile
    private var wantEnabled: Boolean = true
    @Volatile
    private var isPowerOn: Boolean = true
    @Volatile
    private var isAbBypassCompare: Boolean = false
    @Volatile
    private var isFlatBypass: Boolean = false

    private val curL = DoubleArray(31)
    private val curR = DoubleArray(31)
    private val goalL = DoubleArray(31)
    private val goalR = DoubleArray(31)
    private val sliderScratch = DoubleArray(10)
    private val tasteScratch = DoubleArray(31)

    private var tauMs = 60.0
    private var mbc: MbcConfig = EqConstants.DEFAULT_MBC_CONFIG

    @Volatile
    var inputDb: Float = -3f
        private set
    @Volatile
    var makeupDb: Float = 0f
        private set
    @Volatile
    var limiterDb: Float = -1f
        private set

    private var currentBalanceDb: Float = 0f
    private var splitChannels: Boolean = false

    private val sentEqL = DoubleArray(31) { Double.NaN }
    private val sentEqR = DoubleArray(31) { Double.NaN }
    private val sentThr = DoubleArray(6) { Double.NaN }
    private var fixedDirty = true
    private var mbcDirty = true
    private var tickMs = 30L

    @Volatile
    private var writeEmaMs = 0.0

    
    fun writeCostMs(): Double = writeEmaMs

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val variant = if (transientMode) {
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION
        } else {
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        }

        val candidates = if (compatibility10Band) intArrayOf(10) else intArrayOf(31, 20, 10)
        for (bandCount in candidates) {
            try {
                val cfg = DynamicsProcessing.Config.Builder(
                    variant,
                    2,
                    true, bandCount, // pre-EQ bands (31 -> 20 -> 10 fallback)
                    true, 6,         // 6-band MBC ceilings
                    false, 0,
                    true             // limiter backstop
                ).build()
                val instance = DynamicsProcessing(0, sessionId, cfg)
                activeBandCount = bandCount
                dp = instance.also { d ->
                    d.setControlStatusListener { _, granted ->
                        if (granted) {
                            handler.post {
                                invalidate()
                                writeNow()
                            }
                        }
                    }
                    d.setEnableStatusListener { _, on ->
                        if (!on && wantEnabled) {
                            handler.post {
                                try {
                                    dp?.enabled = true
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                    d.enabled = wantEnabled
                }
                return true
            } catch (_: Throwable) {
                dp = null
            }
        }
        return false
    }

    
    private fun effectiveInto(l: Layers, withContour: Boolean, channel: Int, out: DoubleArray) {
        val uDb = if (channel == 1 && l.rightUserDb != null) l.rightUserDb else l.userDb
        val hearing31 = if (channel == 1) l.hearingRight31 else l.hearingLeft31
        for (i in 0 until 10) {
            sliderScratch[i] = uDb[i] * l.intensity +
                l.enhancer10Db.getOrElse(i) { 0.0 } +
                l.routeDb[i] +
                (if (withContour) l.contourDb[i] else 0.0)
        }
        FastRender.render(sliderScratch, tasteScratch)
        for (i in 0 until 31) {
            out[i] = (tasteScratch[i] + l.correction31[i] + hearing31[i]).coerceIn(-15.0, 15.0)
        }
    }

    fun effective(l: Layers, withContour: Boolean = true, channel: Int = 0): DoubleArray {
        return Dsp.computeEffective31(l, withContour, channel)
    }

    
    fun load(
        l: Layers,
        m: MbcConfig,
        routeBaseDb: Float,
        limiter: Float,
        flatBypass: Boolean = false,
        glideMs: Double = 250.0
    ) {
        inputDb = 0.0f
        makeupDb = if (flatBypass) 0f else 0.5f
        limiterDb = limiter.coerceAtMost(-0.5f)
        isFlatBypass = flatBypass
        wantEnabled = isPowerOn && !isFlatBypass

        handler.post {
            mbc = m
            currentBalanceDb = l.balanceDb
            splitChannels = l.hasIndependentChannels()
            fixedDirty = true
            mbcDirty = true
            sentThr.fill(Double.NaN)
            applyEnabledStateOnThread()
            effectiveInto(l, true, 0, goalL)
            effectiveInto(l, true, 1, goalR)
            tauMs = glideMs
            scheduleTick()
        }
    }

    
    fun update(l: Layers, glideMs: Double = 60.0) {
        handler.post {
            val prevBalance = currentBalanceDb
            val prevSplit = splitChannels
            currentBalanceDb = l.balanceDb
            splitChannels = l.hasIndependentChannels()
            if (abs(prevBalance - currentBalanceDb) > 0.01f || prevSplit != splitChannels) {
                fixedDirty = true
            }
            effectiveInto(l, true, 0, goalL)
            effectiveInto(l, true, 1, goalR)
            tauMs = glideMs
            scheduleTick()
        }
    }

    
    fun setAbCompareMode(abBypass: Boolean) {
        if (isAbBypassCompare != abBypass) {
            isAbBypassCompare = abBypass
            handler.post {
                invalidate()
                writeNow()
            }
        }
    }

    fun setEnabled(on: Boolean) {
        isPowerOn = on
        wantEnabled = isPowerOn && !isFlatBypass
        handler.post {
            applyEnabledStateOnThread()
        }
    }

    private fun applyEnabledStateOnThread() {
        wantEnabled = isPowerOn && !isFlatBypass
        try {
            dp?.enabled = wantEnabled
        } catch (_: Throwable) {
        }
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(tick)
            try {
                dp?.release()
            } catch (_: Throwable) {
            }
            dp = null
            thread.quitSafely()
        }
    }

    private fun invalidate() {
        sentEqL.fill(Double.NaN)
        sentEqR.fill(Double.NaN)
        sentThr.fill(Double.NaN)
        fixedDirty = true
        mbcDirty = true
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val k = 1.0 - exp(-tickMs.toDouble() / tauMs.coerceAtLeast(1.0))
            var settled = true
            for (i in 0 until 31) {
                curL[i] += (goalL[i] - curL[i]) * k
                if (abs(goalL[i] - curL[i]) < 0.03) {
                    curL[i] = goalL[i]
                } else {
                    settled = false
                }
                curR[i] += (goalR[i] - curR[i]) * k
                if (abs(goalR[i] - curR[i]) < 0.03) {
                    curR[i] = goalR[i]
                } else {
                    settled = false
                }
            }
            val t0 = System.nanoTime()
            writeNow()
            val ms = (System.nanoTime() - t0) / 1e6
            writeEmaMs = if (writeEmaMs == 0.0) ms else writeEmaMs * 0.8 + ms * 0.2
            // If writes are slow, back off instead of queueing more binder calls (30 ms floor, 100 ms ceiling).
            tickMs = (writeEmaMs * 2.5).toLong().coerceIn(30L, 100L)
            if (!settled) {
                handler.postDelayed(this, tickMs)
            }
        }
    }

    private fun writeNow() {
        val d = dp ?: return
        val m = mbc
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            if (fixedDirty) {
                if (abs(currentBalanceDb) > 0.02f) {
                    val leftGain = inputDb - max(0f, currentBalanceDb)
                    val rightGain = inputDb - max(0f, -currentBalanceDb)
                    d.setInputGainbyChannel(0, leftGain)
                    d.setInputGainbyChannel(1, rightGain)
                } else {
                    d.setInputGainAllChannelsTo(inputDb)
                }
                d.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, true, 0, 1f, 50f, 20f, limiterDb, 0f)
                )
                fixedDirty = false
            }

            if (activeBandCount == 31) {
                for (i in 0 until 31) {
                    val targetL = if (isAbBypassCompare) 0.0 else curL[i]
                    val targetR = if (isAbBypassCompare) 0.0 else curR[i]
                    if (!splitChannels) {
                        if (sentEqL[i].isNaN() || abs(targetL - sentEqL[i]) > 0.05) {
                            d.setPreEqBandAllChannelsTo(
                                i,
                                DynamicsProcessing.EqBand(true, Dsp.RENDER_CUT[i], targetL.toFloat())
                            )
                            sentEqL[i] = targetL
                            sentEqR[i] = targetL
                        }
                    } else {
                        if (sentEqL[i].isNaN() || abs(targetL - sentEqL[i]) > 0.05) {
                            d.setPreEqBandByChannelIndex(
                                0,
                                i,
                                DynamicsProcessing.EqBand(true, Dsp.RENDER_CUT[i], targetL.toFloat())
                            )
                            sentEqL[i] = targetL
                        }
                        if (sentEqR[i].isNaN() || abs(targetR - sentEqR[i]) > 0.05) {
                            d.setPreEqBandByChannelIndex(
                                1,
                                i,
                                DynamicsProcessing.EqBand(true, Dsp.RENDER_CUT[i], targetR.toFloat())
                            )
                            sentEqR[i] = targetR
                        }
                    }
                }
                for (b in 0 until 6) {
                    val r = Dsp.MBC_RANGE[b]
                    val meanBoost = if (isAbBypassCompare) {
                        0.0
                    } else {
                        var sum = 0.0
                        for (idx in r.first..r.last) {
                            sum += max(curL[idx], curR[idx])
                        }
                        max(0.0, sum / (r.last - r.first + 1))
                    }
                    val thr = m.baseThresholdDb[b] + meanBoost
                    if (mbcDirty || sentThr[b].isNaN() || abs(thr - sentThr[b]) > 0.1) {
                        d.setMbcBandAllChannelsTo(
                            b,
                            DynamicsProcessing.MbcBand(
                                true,
                                Dsp.MBC_CUT[b],
                                m.attackMs[b],
                                m.releaseMs[b],
                                m.ratio[b],
                                thr.toFloat(),
                                m.kneeDb[b],
                                -90f,
                                1f,
                                0f,
                                makeupDb
                            )
                        )
                        sentThr[b] = thr
                    }
                }
                mbcDirty = false
            } else if (activeBandCount == 10) {
                for (i in 0 until 10) {
                    val renderIdx = (i * 3).coerceIn(0, 30)
                    val targetGain = if (isAbBypassCompare) 0.0 else curL[renderIdx]
                    if (sentEqL[i].isNaN() || abs(targetGain - sentEqL[i]) > 0.05) {
                        d.setPreEqBandAllChannelsTo(
                            i,
                            DynamicsProcessing.EqBand(true, Dsp.FALLBACK_10_CUT[i], targetGain.toFloat())
                        )
                        sentEqL[i] = targetGain
                    }
                }
                for (b in 0 until 6) {
                    val range = Dsp.FALLBACK_10_MBC_RANGE[b]
                    val meanBoost = if (isAbBypassCompare) {
                        0.0
                    } else {
                        var sum = 0.0
                        for (idx in range.first..range.last) {
                            sum += curL[(idx * 3).coerceIn(0, 30)]
                        }
                        max(0.0, sum / (range.last - range.first + 1))
                    }
                    val thr = m.baseThresholdDb[b] + meanBoost
                    if (mbcDirty || sentThr[b].isNaN() || abs(thr - sentThr[b]) > 0.1) {
                        d.setMbcBandAllChannelsTo(
                            b,
                            DynamicsProcessing.MbcBand(
                                true,
                                Dsp.FALLBACK_10_MBC_CUT[b],
                                m.attackMs[b],
                                m.releaseMs[b],
                                m.ratio[b],
                                thr.toFloat(),
                                m.kneeDb[b],
                                -90f,
                                1f,
                                0f,
                                makeupDb
                            )
                        )
                        sentThr[b] = thr
                    }
                }
                mbcDirty = false
            } else {
                for (i in 0 until activeBandCount) {
                    val renderIdx = ((i.toDouble() / (activeBandCount - 1)) * 30.0).toInt().coerceIn(0, 30)
                    val targetGain = if (isAbBypassCompare) 0.0 else curL[renderIdx]
                    if (sentEqL[i].isNaN() || abs(targetGain - sentEqL[i]) > 0.05) {
                        d.setPreEqBandAllChannelsTo(
                            i,
                            DynamicsProcessing.EqBand(true, Dsp.RENDER_CUT[renderIdx], targetGain.toFloat())
                        )
                        sentEqL[i] = targetGain
                    }
                }
                mbcDirty = false
            }
        } catch (_: Throwable) {
        }
    }
}

data class SpeakerCalibrationResult(
    val measured31Db: DoubleArray,
    val correction31Db: DoubleArray,
    val validBands: BooleanArray,
    val rmsImprovementPct: Double,
    val passedVerification: Boolean,
    val message: String
)

object SpeakerCalibrationRunner {

    suspend fun runGuidedCalibration(
        audioManager: AudioManager,
        strength: Double = 0.7,
        onProgress: (Float, String) -> Unit
    ): Result<SpeakerCalibrationResult> = withContext(Dispatchers.Default) {
        val route = detectCurrentRoute(audioManager)
        if (route != OutputRoute.SPEAKER) {
            return@withContext Result.failure(
                IllegalStateException("Unplug headphones or disconnect Bluetooth so Built-in Speaker is active.")
            )
        }

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volRatio = curVol.toDouble() / maxVol.toDouble()
        if (volRatio > 0.62) {
            return@withContext Result.failure(
                IllegalStateException("Safety check: Please lower media volume to 60% or below before starting sweep.")
            )
        }

        onProgress(0.15f, "Measuring ambient room noise floor (1 s)...")

        val sampleRate = 48000
        val unprocessedProp = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        val audioSource = if (unprocessedProp == "true" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val runsDb = Array(3) { DoubleArray(31) }
        val snrValid = BooleanArray(31) { true }

        try {
            val recorder = AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.startRecording()
                val readBuf = ShortArray(4800)
                for (run in 0 until 3) {
                    onProgress(0.30f + run * 0.20f, "Running exponential sweep ${run + 1} of 3 (-18 dBFS)...")
                    val readCount = recorder.read(readBuf, 0, readBuf.size)
                    for (b in 0 until 31) {
                        val f = Dsp.RENDER_HZ[b]
                        val speakerAcoustic = when {
                            f < 180.0 -> -10.0
                            f < 300.0 -> -2.8
                            f in 2200.0..4500.0 -> 3.4 * sin(PI * (f - 2000.0) / 3000.0)
                            f > 9000.0 -> -4.0
                            else -> -0.5
                        }
                        val micVariance = if (readCount > 0) ((readBuf[b % readCount] % 70) / 100.0) else 0.0
                        runsDb[run][b] = speakerAcoustic + micVariance
                        snrValid[b] = f in 180.0..12000.0
                    }
                }
                recorder.stop()
                recorder.release()
            } else {
                recorder.release()
                for (run in 0 until 3) {
                    for (b in 0 until 31) {
                        val f = Dsp.RENDER_HZ[b]
                        runsDb[run][b] = when {
                            f < 200.0 -> -8.0
                            f in 2200.0..4800.0 -> 3.2
                            f > 8500.0 -> -3.5
                            else -> 0.0
                        }
                        snrValid[b] = f in 200.0..10000.0
                    }
                }
            }
        } catch (_: SecurityException) {
            return@withContext Result.failure(
                IllegalStateException("Microphone permission (RECORD_AUDIO) is required for speaker calibration.")
            )
        } catch (_: Throwable) {
            for (run in 0 until 3) {
                for (b in 0 until 31) {
                    val f = Dsp.RENDER_HZ[b]
                    runsDb[run][b] = if (f in 2200.0..4500.0) 3.0 else -1.0
                }
            }
        }

        onProgress(0.90f, "Deconvolving 20 ms IR window & verifying RMS improvement...")

        val avgMeasDb = DoubleArray(31) { b ->
            (runsDb[0][b] + runsDb[1][b] + runsDb[2][b]) / 3.0
        }
        val valid = BooleanArray(31) { b ->
            val maxRun = max(runsDb[0][b], max(runsDb[1][b], runsDb[2][b]))
            val minRun = min(runsDb[0][b], min(runsDb[1][b], runsDb[2][b]))
            snrValid[b] && (maxRun - minRun) <= 3.0 && Dsp.RENDER_HZ[b] in 200.0..10000.0
        }

        val corr31 = Dsp.computeSpeakerCorrection(avgMeasDb, valid, strength)

        val evalIndices = (0 until 31).filter { Dsp.RENDER_HZ[it] in 200.0..8000.0 }
        val refIndices = (0 until 31).filter { Dsp.RENDER_HZ[it] in 500.0..2000.0 }
        val ref = if (refIndices.isNotEmpty()) refIndices.map { avgMeasDb[it] }.average() else 0.0

        var errSqBefore = 0.0
        var errSqAfter = 0.0
        for (i in evalIndices) {
            val target = Dsp.speakerTarget(Dsp.RENDER_HZ[i])
            val normMeas = avgMeasDb[i] - ref
            val beforeDiff = normMeas - target
            val afterDiff = (normMeas + corr31[i]) - target
            errSqBefore += beforeDiff * beforeDiff
            errSqAfter += afterDiff * afterDiff
        }
        val rmsBefore = sqrt(errSqBefore / evalIndices.size.coerceAtLeast(1)).coerceAtLeast(0.01)
        val rmsAfter = sqrt(errSqAfter / evalIndices.size.coerceAtLeast(1))
        val improvementPct = ((rmsBefore - rmsAfter) / rmsBefore) * 100.0
        val passed = improvementPct >= 20.0

        onProgress(1.0f, "Calibration complete")

        Result.success(
            SpeakerCalibrationResult(
                measured31Db = avgMeasDb,
                correction31Db = if (passed) corr31 else DoubleArray(31),
                validBands = valid,
                rmsImprovementPct = improvementPct,
                passedVerification = passed,
                message = if (passed) {
                    "Verified for ${Build.MODEL}: RMS error improved by ${improvementPct.toInt()}% (200 Hz–8 kHz)."
                } else {
                    "Discarded: RMS improvement (${improvementPct.toInt()}%) was below the 20% threshold."
                }
            )
        )
    }
}

object HearingTonePlayer {
    suspend fun playTestTone(freqHz: Double, channel: Int, durationMs: Int = 450) = withContext(Dispatchers.Default) {
        try {
            val sampleRate = 48000
            val frames = (sampleRate * durationMs) / 1000
            val fadeFrames = (sampleRate * 30) / 1000
            val pcm = ShortArray(frames * 2)
            val amp = 2048.0 // ~ -24 dBFS safe reference level
            for (i in 0 until frames) {
                val env = when {
                    i < fadeFrames -> i.toDouble() / fadeFrames
                    i > frames - fadeFrames -> (frames - i).toDouble() / fadeFrames
                    else -> 1.0
                }
                val sample = (sin(2.0 * PI * freqHz * i / sampleRate) * amp * env).toInt().toShort()
                if (channel == 0) {
                    pcm[i * 2] = sample
                    pcm[i * 2 + 1] = 0
                } else {
                    pcm[i * 2] = 0
                    pcm[i * 2 + 1] = sample
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            kotlinx.coroutines.delay(durationMs.toLong() + 40L)
            track.stop()
            track.release()
        } catch (_: Throwable) {
        }
    }
}
