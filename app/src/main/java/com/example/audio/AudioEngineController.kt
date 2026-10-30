package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.CapabilityLevel
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.MbcConfig
import com.example.model.ModeStatusInfo
import com.example.model.OutputRoute
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class DeviceDiagnostics(
    val nativeEqBandCount: Int = 5,
    val nativeBandCentersHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val nativeBandRangesMilliHz: List<IntArray> = listOf(
        intArrayOf(30_000, 120_000),
        intArrayOf(120_001, 460_000),
        intArrayOf(460_001, 1_800_000),
        intArrayOf(1_800_001, 7_000_000),
        intArrayOf(7_000_001, 20_000_000)
    ),
    val nativeLevelRangeDb: Pair<Float, Float> = -15f to 15f,
    val dynamicsProcessingSupported: Boolean = false,
    val activeRenderBandCount: Int = 31,
    val isLegacyTier1BasicEngine: Boolean = false,
    val globalSession0Supported: Boolean = false,
    val dumpPermissionGranted: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val rootSuBinaryDetected: Boolean = false,
    val activeSessions: List<Int> = emptyList(),
    val connectedProductName: String? = null,
    val connectedDeviceKey: String = "speaker:${Build.MODEL}",
    val usbBitPerfectSupported: Boolean = false,
    val usbBitPerfectActive: Boolean = false,
    val bluetoothCodecSummary: String? = null,
    val btDisableAbsVolActive: Boolean = false
)

/**
 * AudioEngineController updated for Audio EQ Deep Optimization v5 (APK-only):
 * - Hosts per-session `EqEngine` (31-band PCHIP pre-EQ + 6-band MBC ceilings + limiter backstop, ~33 Hz delta writes, L/R channels)
 * - Falls back cleanly to Legacy Tier 1 (`Equalizer` + `BassBoost` + `Virtualizer`) using band-averaged 31-band curves (`AutoEq.map31ToNativeBands`)
 *   when `DynamicsProcessing` is unavailable, and sets `BassBoost = 0` whenever `DynamicsProcessing` is active (v5 Section 13 & 16.4)
 * - APK-only Root helper (`su -c`) for global session discovery, Bluetooth codec readout (F10), and Bluetooth absolute-volume control (Section 18.2)
 * - Android 14+ USB DAC Bit-Perfect mixer attribute support (F7)
 */
class AudioEngineController(private val context: Context) {

    private class SessionEffectBundle(
        val sessionId: Int,
        var v5Engine: EqEngine? = null,
        var equalizer: Equalizer? = null,
        var bassBoost: BassBoost? = null,
        var virtualizer: Virtualizer? = null,
        var loudnessEnhancer: LoudnessEnhancer? = null
    ) {
        fun release() {
            try { v5Engine?.release() } catch (_: Exception) {}
            try { equalizer?.release() } catch (_: Exception) {}
            try { bassBoost?.release() } catch (_: Exception) {}
            try { virtualizer?.release() } catch (_: Exception) {}
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
        }
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val activeBundles = mutableMapOf<Int, SessionEffectBundle>()
    private val broadcastSessions = mutableSetOf<Int>()
    private var receiverRegistered = false

    private var lastLayers: Layers = Layers(
        userDb = DoubleArray(10),
        intensity = 1.0,
        routeDb = DoubleArray(10),
        contourDb = DoubleArray(10),
        correction31 = DoubleArray(31)
    )
    private var lastMbc: MbcConfig = EqConstants.DEFAULT_MBC_CONFIG
    private var lastRouteBaseDb: Float = -3.0f
    private var lastLimiterDb: Float = -1.0f
    private var lastFlatBypass: Boolean = false
    private var lastBassBoostStrength: Int = 150
    private var lastVirtualizerStrength: Int = 150
    private var lastEnabled: Boolean = true
    private var lastAbCompare: Boolean = false
    private var currentMode: EngineMode = EngineMode.LEGACY
    private var compatibility10Band: Boolean = false
    private var transientMode: Boolean = false
    private var usbBitPerfectEnabled: Boolean = false

    var currentInputGainDb: Float = -3.0f
        private set
    var currentMakeupGainDb: Float = 0.0f
        private set

    var diagnostics: DeviceDiagnostics = DeviceDiagnostics()
        private set

    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val sessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) ?: -1
            if (sessionId <= 0) return
            when (intent?.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    broadcastSessions.add(sessionId)
                    attachToSessionIfNeeded(sessionId)
                    loadAllSessions(glideMs = 250.0)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    broadcastSessions.remove(sessionId)
                    activeBundles.remove(sessionId)?.release()
                }
            }
        }
    }

    init {
        registerSessionBroadcastReceiver()
        probeCapabilities()
    }

    private fun registerSessionBroadcastReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            ContextCompat.registerReceiver(
                context,
                audioSessionReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Exception) {
        }
    }

    fun getCurrentRoute(): OutputRoute = detectCurrentRoute(audioManager)

    fun getCurrentVolumeRatio(): Double {
        return try {
            val maxV = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curV = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxV)
            curV.toDouble() / maxV.toDouble()
        } catch (_: Throwable) {
            0.5
        }
    }

    fun getAudioManager(): AudioManager = audioManager

    fun setUsbBitPerfectMode(enabled: Boolean): Boolean {
        val applied = UsbBitPerfectHelper.setBitPerfectEnabled(audioManager, enabled)
        usbBitPerfectEnabled = enabled && applied
        probeCapabilities()
        loadAllSessions(glideMs = 250.0)
        return usbBitPerfectEnabled
    }

    fun reconfigureEngineOptions(
        use10BandCompatibility: Boolean,
        useTransientMode: Boolean
    ) {
        if (compatibility10Band != use10BandCompatibility || transientMode != useTransientMode) {
            compatibility10Band = use10BandCompatibility
            transientMode = useTransientMode
            val existingSessions = activeBundles.keys.toList()
            activeBundles.values.forEach { it.release() }
            activeBundles.clear()
            existingSessions.forEach { attachToSessionIfNeeded(it) }
            loadAllSessions(glideMs = 250.0)
        }
    }

    fun probeCapabilities(internalReferenceSessionId: Int = 0): Map<EngineMode, ModeStatusInfo> {
        var nativeBands = 5
        val centers = mutableListOf<Int>()
        val freqRanges = mutableListOf<IntArray>()
        var minDb = -15f
        var maxDb = 15f
        var global0Ok = false
        var dpOk = false
        var dpBandCount = if (compatibility10Band) 10 else 31

        try {
            val eq0 = Equalizer(0, 0)
            global0Ok = true
            val count = eq0.numberOfBands.toInt()
            if (count > 0) {
                nativeBands = count
                for (b in 0 until count) {
                    centers.add((eq0.getCenterFreq(b.toShort()) / 1000))
                    val r = eq0.getBandFreqRange(b.toShort())
                    if (r != null && r.size >= 2) {
                        freqRanges.add(intArrayOf(r[0], r[1]))
                    }
                }
            }
            val range = eq0.bandLevelRange
            if (range != null && range.size >= 2) {
                minDb = range[0] / 100f
                maxDb = range[1] / 100f
            }
            eq0.release()
        } catch (_: Throwable) {
            global0Ok = false
        }

        if (centers.isEmpty() && internalReferenceSessionId > 0) {
            try {
                val eqTest = Equalizer(0, internalReferenceSessionId)
                val count = eqTest.numberOfBands.toInt()
                if (count > 0) {
                    nativeBands = count
                    for (b in 0 until count) {
                        centers.add((eqTest.getCenterFreq(b.toShort()) / 1000))
                        val r = eqTest.getBandFreqRange(b.toShort())
                        if (r != null && r.size >= 2) {
                            freqRanges.add(intArrayOf(r[0], r[1]))
                        }
                    }
                }
                val range = eqTest.bandLevelRange
                if (range != null && range.size >= 2) {
                    minDb = range[0] / 100f
                    maxDb = range[1] / 100f
                }
                eqTest.release()
            } catch (_: Throwable) {
            }
        }

        if (centers.isEmpty()) {
            centers.addAll(listOf(60, 230, 910, 3600, 14000))
        }
        if (freqRanges.isEmpty()) {
            freqRanges.addAll(
                listOf(
                    intArrayOf(30_000, 120_000),
                    intArrayOf(120_001, 460_000),
                    intArrayOf(460_001, 1_800_000),
                    intArrayOf(1_800_001, 7_000_000),
                    intArrayOf(7_000_001, 20_000_000)
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val testSession = if (internalReferenceSessionId > 0) internalReferenceSessionId else 0
            val candidates = if (compatibility10Band) intArrayOf(10) else intArrayOf(31, 20, 10)
            for (c in candidates) {
                try {
                    val cfg = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,
                        true, c,
                        true, 6,
                        false, 0,
                        true
                    ).build()
                    val dp = DynamicsProcessing(0, testSession, cfg)
                    dpOk = true
                    dpBandCount = c
                    dp.release()
                    break
                } catch (_: Throwable) {
                    dpOk = false
                }
            }
        }

        val dumpGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.DUMP
        ) == PackageManager.PERMISSION_GRANTED

        val shizukuPresent = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }

        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/debug_ramdisk/su"
        )
        val hasSu = suPaths.any { File(it).exists() }

        val prodName = detectConnectedDeviceProductName(audioManager)
        val devKey = buildCurrentDeviceKey(audioManager)
        val bitPerfectSupported = UsbBitPerfectHelper.isBitPerfectSupported(audioManager)
        val btCodec = if (dumpGranted || hasSu) readBluetoothCodecFromDumpsys(hasSu) else null

        diagnostics = DeviceDiagnostics(
            nativeEqBandCount = nativeBands,
            nativeBandCentersHz = centers,
            nativeBandRangesMilliHz = freqRanges,
            nativeLevelRangeDb = minDb to maxDb,
            dynamicsProcessingSupported = dpOk,
            activeRenderBandCount = if (dpOk) dpBandCount else nativeBands,
            isLegacyTier1BasicEngine = !dpOk,
            globalSession0Supported = global0Ok,
            dumpPermissionGranted = dumpGranted,
            shizukuInstalled = shizukuPresent,
            rootSuBinaryDetected = hasSu,
            activeSessions = activeBundles.keys.toList(),
            connectedProductName = prodName,
            connectedDeviceKey = devKey,
            usbBitPerfectSupported = bitPerfectSupported,
            usbBitPerfectActive = usbBitPerfectEnabled && bitPerfectSupported,
            bluetoothCodecSummary = btCodec
        )

        val pkgName = context.packageName

        val legacyStatus = ModeStatusInfo(
            mode = EngineMode.LEGACY,
            level = CapabilityLevel.AVAILABLE,
            summaryText = if (dpOk) {
                "Legacy Global ($dpBandCount-Band PCHIP + 6-Band Ceilings)"
            } else {
                "Basic Engine (Tier 1 · $nativeBands-Band Native EQ · Approximate)"
            },
            detailReason = buildString {
                append("Works globally across apps without ADB, Shizuku, or Root. ")
                if (dpOk) {
                    append("Using v5 $dpBandCount-band PCHIP renderer, 6-band boost-tracking MBC ceilings (90..20k Hz), and -1 dB limiter.")
                } else {
                    append("DynamicsProcessing unavailable; using Tier 1 Basic Engine ($nativeBands-band native Equalizer band-averaged + BassBoost).")
                }
            },
            setupSteps = emptyList(),
            activeSessionsCount = activeBundles.size.coerceAtLeast(1)
        )

        val adbStatus = if (dumpGranted) {
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "ADB / Shizuku active ($dpBandCount-Band Renderer)",
                detailReason = buildString {
                    append("Scanning dumpsys media.audio_flinger for all active player sessions with $dpBandCount-band PCHIP pre-EQ, L/R channel control, and 6-band ceilings.")
                    if (btCodec != null) append(" Detected BT codec: $btCodec.")
                },
                setupSteps = listOf(
                    "Permission android.permission.DUMP is granted.",
                    "Throttled delta-only binder writes (~33 updates/sec)."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            val shizukuNote = if (shizukuPresent) "Shizuku installed, but shell/DUMP not active" else "Shizuku not running"
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires permission ($shizukuNote · Using Legacy fallback)",
                detailReason = "Start Shizuku or grant DUMP permission once via ADB to discover all active sessions and read Bluetooth codec headroom. Falling back to Legacy Global until granted.",
                setupSteps = listOf(
                    "1. Start Shizuku (Wireless Debugging) or connect phone to PC with USB Debugging.",
                    "2. Run one-time ADB command:\nadb shell pm grant $pkgName android.permission.DUMP"
                ),
                activeSessionsCount = activeBundles.size
            )
        }

        val rootStatus = if (hasSu) {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "Root Helper active (APK-only su session & BT control)",
                detailReason = "Root su binary detected. APK-only helper runs global session discovery, start-on-boot, codec readout, and Bluetooth absolute-volume control without modifying /system or /vendor.",
                setupSteps = listOf(
                    "APK-only mode (v5 Section 18): No system/vendor files are flashed or modified.",
                    "Uses su helper for session discovery, Start-on-Boot (F12), and Bluetooth absolute-volume toggle."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires root access (APK-only · Using Legacy fallback)",
                detailReason = "No su binary detected. Root mode in v5 is strictly APK-only (no flashing or /vendor edits) and uses su to launch the session helper on boot and control Bluetooth absolute volume.",
                setupSteps = listOf(
                    "1. Grant su access in Magisk, KernelSU, or APatch if your device is rooted.",
                    "2. Zero-flash APK-only architecture: never touches /system or /vendor partitions."
                ),
                activeSessionsCount = activeBundles.size
            )
        }

        return mapOf(
            EngineMode.LEGACY to legacyStatus,
            EngineMode.ADB_SHIZUKU to adbStatus,
            EngineMode.ROOT to rootStatus
        )
    }

    /**
     * APK-only Root helper action (v5 Section 18.2):
     * Toggles Bluetooth "Disable absolute volume" via `su` so the Section 7 software-volume contour
     * receives volume-scaled audio on Bluetooth headphones.
     */
    fun toggleRootBluetoothDisableAbsoluteVolume(disableAbsVol: Boolean): Result<String> {
        return try {
            val propVal = if (disableAbsVol) "true" else "false"
            val settingVal = if (disableAbsVol) "1" else "0"
            if (diagnostics.rootSuBinaryDetected) {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "setprop persist.bluetooth.disableabsvol $propVal; settings put global bluetooth_disable_absolute_volume $settingVal"
                    )
                )
                diagnostics = diagnostics.copy(btDisableAbsVolActive = disableAbsVol)
                Result.success(
                    if (disableAbsVol) {
                        "Root helper set persist.bluetooth.disableabsvol=true (software volume contour active on Bluetooth)."
                    } else {
                        "Restored default Bluetooth absolute volume behavior."
                    }
                )
            } else {
                Result.failure(
                    IllegalStateException("Root su not available. Enable 'Disable absolute volume' in Android Developer Options.")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Called on preset, route, headphone profile, speaker calibration, or intensity change (v5 Section 9 `load`).
     * Recomputes `inputDb` and `makeupDb` and glides over 250 ms.
     */
    fun loadEngineState(
        mode: EngineMode,
        enabled: Boolean,
        abCompare: Boolean,
        layers: Layers,
        mbc: MbcConfig,
        routeBaseDb: Float,
        limiterDb: Float,
        flatBypass: Boolean,
        bassBoostStrength: Int,
        virtualizerStrength: Int,
        internalSessionId: Int = 0
    ) {
        currentMode = mode
        lastEnabled = enabled && !(usbBitPerfectEnabled && getCurrentRoute() == OutputRoute.USB)
        lastAbCompare = abCompare
        lastLayers = layers
        lastMbc = mbc
        lastRouteBaseDb = routeBaseDb
        lastLimiterDb = limiterDb
        lastFlatBypass = flatBypass
        lastBassBoostStrength = bassBoostStrength
        lastVirtualizerStrength = virtualizerStrength

        val base31 = Dsp.computeEffective31(layers, withContour = false, channel = 0)
        currentInputGainDb = routeBaseDb - kotlin.math.max(0.0, (layers.correction31.maxOrNull() ?: 0.0) - 3.0).toFloat()
        currentMakeupGainDb = if (flatBypass) 0f else (-currentInputGainDb - Dsp.pinkGainDb(base31)).coerceIn(0.0, 4.0).toFloat()

        ensureTargetSessionsAttached(internalSessionId)
        loadAllSessions(glideMs = 250.0)
    }

    /**
     * Called on slider drags and volume contour ticks (v5 Section 9 `update`).
     * Never modifies `inputDb` or `makeupDb` (makeup stays frozen while dragging sliders) and glides over 60 ms.
     */
    fun updateSlidersOrContourFast(
        enabled: Boolean,
        abCompare: Boolean,
        layers: Layers,
        flatBypass: Boolean,
        internalSessionId: Int = 0
    ) {
        lastEnabled = enabled && !(usbBitPerfectEnabled && getCurrentRoute() == OutputRoute.USB)
        lastAbCompare = abCompare
        lastLayers = layers
        lastFlatBypass = flatBypass

        ensureTargetSessionsAttached(internalSessionId)
        for (bundle in activeBundles.values) {
            val v5 = bundle.v5Engine
            if (v5 != null) {
                v5.setEnabled(lastEnabled)
                v5.setAbCompareMode(lastAbCompare)
                v5.update(lastLayers, glideMs = 60.0)
            } else {
                applyTier1FallbackToBundle(bundle)
            }
        }
    }

    private fun ensureTargetSessionsAttached(internalSessionId: Int) {
        val targetSessions = mutableSetOf<Int>()
        targetSessions.add(0)
        targetSessions.addAll(broadcastSessions)
        if (internalSessionId > 0) {
            targetSessions.add(internalSessionId)
        }
        if ((currentMode == EngineMode.ADB_SHIZUKU && diagnostics.dumpPermissionGranted) ||
            (currentMode == EngineMode.ROOT && diagnostics.rootSuBinaryDetected)
        ) {
            targetSessions.addAll(discoverSessionsViaDumpsys(useSu = currentMode == EngineMode.ROOT && diagnostics.rootSuBinaryDetected))
        }
        for (sId in targetSessions) {
            attachToSessionIfNeeded(sId)
        }
    }

    private fun attachToSessionIfNeeded(sessionId: Int) {
        if (activeBundles.containsKey(sessionId)) return

        var v5Engine: EqEngine? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val candidate = EqEngine(
                sessionId = sessionId,
                compatibility10Band = compatibility10Band,
                transientMode = transientMode
            )
            if (candidate.start()) {
                v5Engine = candidate
            }
        }

        var eq: Equalizer? = null
        var bb: BassBoost? = null
        var virt: Virtualizer? = null
        var le: LoudnessEnhancer? = null

        try {
            eq = Equalizer(0, sessionId)
        } catch (_: Throwable) {
            eq = null
        }

        try {
            val candidateBb = BassBoost(0, sessionId)
            if (candidateBb.strengthSupported) {
                bb = candidateBb
            } else {
                candidateBb.release()
            }
        } catch (_: Throwable) {
        }

        try {
            val candidateVirt = Virtualizer(0, sessionId)
            if (candidateVirt.strengthSupported) {
                virt = candidateVirt
            } else {
                candidateVirt.release()
            }
        } catch (_: Throwable) {
        }

        try {
            le = LoudnessEnhancer(sessionId)
        } catch (_: Throwable) {
        }

        if (v5Engine != null || eq != null || bb != null || virt != null) {
            activeBundles[sessionId] = SessionEffectBundle(
                sessionId = sessionId,
                v5Engine = v5Engine,
                equalizer = eq,
                bassBoost = bb,
                virtualizer = virt,
                loudnessEnhancer = le
            )
        }
    }

    private fun loadAllSessions(glideMs: Double) {
        for (bundle in activeBundles.values) {
            val v5 = bundle.v5Engine
            if (v5 != null) {
                v5.setEnabled(lastEnabled)
                v5.setAbCompareMode(lastAbCompare)
                v5.load(
                    l = lastLayers,
                    m = lastMbc,
                    routeBaseDb = lastRouteBaseDb,
                    limiter = lastLimiterDb,
                    flatBypass = lastFlatBypass,
                    glideMs = glideMs
                )
                currentInputGainDb = v5.inputDb
                currentMakeupGainDb = v5.makeupDb

                // v5 Section 13: When DynamicsProcessing is active, disable native Equalizer and set BassBoost to 0
                try { bundle.equalizer?.enabled = false } catch (_: Throwable) {}
                try {
                    bundle.bassBoost?.setStrength(0)
                    bundle.bassBoost?.enabled = false
                } catch (_: Throwable) {}
                try {
                    bundle.virtualizer?.enabled = lastEnabled && !lastFlatBypass && lastVirtualizerStrength > 0
                    if (bundle.virtualizer?.strengthSupported == true) {
                        bundle.virtualizer?.setStrength(lastVirtualizerStrength.coerceIn(0, 1000).toShort())
                    }
                } catch (_: Throwable) {}
            } else {
                applyTier1FallbackToBundle(bundle)
            }

            bundle.loudnessEnhancer?.let { le ->
                try {
                    le.enabled = false
                    le.setTargetGain(0)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Legacy Tier 1 fallback (v5 Section 13, 16.4 & 19) when `DynamicsProcessing` fails on a session:
     * Reads each native band's edges with `getBandFreqRange()` (millihertz) and uses the mean of the 31-band
     * `effective31` curve over that band (`AutoEq.map31ToNativeBands`), capped at +4 dB, plus `BassBoost` & `Virtualizer`.
     */
    private fun applyTier1FallbackToBundle(bundle: SessionEffectBundle) {
        val active = lastEnabled && !lastFlatBypass && !lastAbCompare
        val eq = bundle.equalizer
        if (eq != null) {
            try {
                eq.enabled = active
                if (active) {
                    val bandCount = eq.numberOfBands.toInt()
                    val range = eq.bandLevelRange
                    val minMb = range?.getOrNull(0)?.toInt() ?: -1500
                    val maxMb = range?.getOrNull(1)?.toInt() ?: 1500

                    val bandRanges = (0 until bandCount).map { b ->
                        val r = try { eq.getBandFreqRange(b.toShort()) } catch (_: Throwable) { null }
                        if (r != null && r.size >= 2) intArrayOf(r[0], r[1]) else intArrayOf(30_000, 20_000_000)
                    }

                    val effective31 = Dsp.computeEffective31(lastLayers, withContour = true, channel = 0)
                    val mappedNativeDb = AutoEq.map31ToNativeBands(effective31, bandRanges)

                    for (b in 0 until bandCount) {
                        val targetDb = mappedNativeDb.getOrElse(b) { 0f }.coerceIn(-12f, 4f)
                        val targetMb = (targetDb * 100f).toInt().coerceIn(minMb, maxMb).toShort()
                        eq.setBandLevel(b.toShort(), targetMb)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        bundle.bassBoost?.let { bb ->
            try {
                bb.enabled = active && lastBassBoostStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(if (active) lastBassBoostStrength.coerceIn(0, 1000).toShort() else 0)
                }
            } catch (_: Throwable) {
            }
        }

        bundle.virtualizer?.let { virt ->
            try {
                virt.enabled = active && lastVirtualizerStrength > 0
                if (virt.strengthSupported) {
                    virt.setStrength(if (active) lastVirtualizerStrength.coerceIn(0, 1000).toShort() else 0)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun discoverSessionsViaDumpsys(useSu: Boolean = false): Set<Int> {
        val discovered = mutableSetOf<Int>()
        try {
            val cmd = if (useSu) arrayOf("su", "-c", "dumpsys media.audio_flinger")
            else arrayOf("sh", "-c", "dumpsys media.audio_flinger")
            val proc = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val sessionRegex = Regex("""session(?:\s+id|\s*Id|:)?\s*[=:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            var line: String? = reader.readLine()
            while (line != null) {
                sessionRegex.findAll(line).forEach { match ->
                    val id = match.groupValues.getOrNull(1)?.toIntOrNull()
                    if (id != null && id > 0) {
                        discovered.add(id)
                    }
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (_: Throwable) {
        }
        return discovered
    }

    /**
     * Reads active Bluetooth codec and sample rate from dumpsys (v5 Section 17.2 F10).
     */
    private fun readBluetoothCodecFromDumpsys(useSu: Boolean = false): String? {
        return try {
            val cmd = if (useSu) arrayOf("su", "-c", "dumpsys bluetooth_manager")
            else arrayOf("sh", "-c", "dumpsys bluetooth_manager")
            val proc = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val codecRegex = Regex("""codecName\s*[:=]\s*([A-Za-z0-9_+-]+)""", RegexOption.IGNORE_CASE)
            var found: String? = null
            var line: String? = reader.readLine()
            while (line != null) {
                val m = codecRegex.find(line)
                if (m != null) {
                    found = m.groupValues[1]
                    break
                }
                line = reader.readLine()
            }
            reader.close()
            found
        } catch (_: Throwable) {
            null
        }
    }

    fun releaseAll() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(audioSessionReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        activeBundles.values.forEach { it.release() }
        activeBundles.clear()
    }
}
