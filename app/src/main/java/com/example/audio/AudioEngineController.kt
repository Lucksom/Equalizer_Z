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
    val btDisableAbsVolActive: Boolean = false,
    val virtualizerSupported: Boolean = true,
    val dolbyPropsDetected: Boolean = false,
    val avgBinderWriteCostMs: Double = 0.0
)

/**
 * AudioEngineController updated for Audio EQ Deep Optimization v5 + Enhancers Spec (APK-only):
 * - Hosts per-session `EqEngine` (own HandlerThread, 31-band FastRender PCHIP pre-EQ + 6-band MBC ceilings + limiter backstop, adaptive ~33 Hz delta writes, L/R channels)
 * - Hosts per-session `VirtualizerFx` (Binaural on headphones / Transaural on speaker for Surround Audio) and `BassBoostFx` (Legacy Tier 1 only)
 * - Hosts `DolbyControl` (`dolby.deepbass` and `dolby.bass` coalesced background shell runner with `restore()`)
 * - Falls back cleanly to Legacy Tier 1 (`Equalizer` + `BassBoostFx` + `VirtualizerFx`) using band-averaged 31-band curves (`AutoEq.map31ToNativeBands`)
 *   when `DynamicsProcessing` is unavailable, and forces `BassBoost = 0` whenever `DynamicsProcessing` is active
 */
class AudioEngineController(private val context: Context) {

    private class SessionEffectBundle(
        val sessionId: Int,
        var v5Engine: EqEngine? = null,
        var equalizer: Equalizer? = null,
        var bassBoostFx: BassBoostFx? = null,
        var virtualizerFx: VirtualizerFx? = null,
        var loudnessEnhancer: LoudnessEnhancer? = null
    ) {
        fun release() {
            try { v5Engine?.release() } catch (_: Exception) {}
            try { equalizer?.release() } catch (_: Exception) {}
            try { bassBoostFx?.release() } catch (_: Exception) {}
            try { virtualizerFx?.release() } catch (_: Exception) {}
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
        }
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val activeBundles = mutableMapOf<Int, SessionEffectBundle>()
    private val broadcastSessions = mutableSetOf<Int>()
    private var receiverRegistered = false

    val dolbyControl = DolbyControl { cmd ->
        runShellCommand(cmd, useSu = diagnostics.rootSuBinaryDetected)
    }

    private var lastLayers: Layers = Layers(
        userDb = DoubleArray(10),
        intensity = 1.0,
        routeDb = DoubleArray(10),
        contourDb = DoubleArray(10),
        correction31 = DoubleArray(31),
        enhancer31 = DoubleArray(31)
    )
    private var lastMbc: MbcConfig = EqConstants.DEFAULT_MBC_CONFIG
    private var lastRouteBaseDb: Float = -3.0f
    private var lastLimiterDb: Float = -1.0f
    private var lastFlatBypass: Boolean = false
    private var lastEnhancerAmounts: DoubleArray = DoubleArray(4)
    private var lastDolbyControlActive: Boolean = false
    private var lastDolbyDeepBass: Boolean = false
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
        val isSmallRoute = getCurrentRoute() == OutputRoute.SPEAKER

        val virtSupported = activeBundles.values.firstNotNullOfOrNull { it.virtualizerFx }
            ?.supported(isSmallRoute)
            ?: run {
                val probeVirt = VirtualizerFx(if (internalReferenceSessionId > 0) internalReferenceSessionId else 0)
                val ok = probeVirt.supported(isSmallRoute)
                probeVirt.release()
                ok
            }
        val avgWriteMs = activeBundles.values.mapNotNull { it.v5Engine?.writeCostMs() }
            .filter { it > 0.0 }
            .takeIf { it.isNotEmpty() }
            ?.average() ?: 0.0
        val dolbyDetected = if (hasSu || dumpGranted) dolbyControl.detect() else diagnostics.dolbyPropsDetected

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
            bluetoothCodecSummary = btCodec,
            btDisableAbsVolActive = diagnostics.btDisableAbsVolActive,
            virtualizerSupported = virtSupported,
            dolbyPropsDetected = dolbyDetected,
            avgBinderWriteCostMs = avgWriteMs
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
     * Called on preset, route, headphone profile, speaker calibration, intensity, or enhancer COMMIT.
     * Recomputes `inputDb`, `makeupDb`, and `EnhancerMbc.blend`, applies `VirtualizerFx`, `BassBoostFx` (Tier 1),
     * and `DolbyControl`, and glides over 250 ms.
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
        enhancerAmounts: DoubleArray = DoubleArray(4),
        dolbyControlActive: Boolean = false,
        dolbyDeepBass: Boolean = false,
        bassBoostStrength: Int = 0,
        virtualizerStrength: Int = 0,
        internalSessionId: Int = 0
    ) {
        currentMode = mode
        lastEnabled = enabled && !(usbBitPerfectEnabled && getCurrentRoute() == OutputRoute.USB)
        lastAbCompare = abCompare
        lastLayers = layers
        lastEnhancerAmounts = enhancerAmounts.copyOf()
        lastDolbyControlActive = dolbyControlActive
        lastDolbyDeepBass = dolbyDeepBass

        // Enhancers Section 3.1: Blend MBC parameters with active enhancer amounts on commit
        lastMbc = EnhancerMbc.blend(mbc, lastEnhancerAmounts)
        lastRouteBaseDb = routeBaseDb
        lastLimiterDb = limiterDb

        // Enhancers Section 3 Rule 4: Flat + enhancers must keep the chain ON
        val anyEnhancerOn = EnhancerMixer.anyActive(lastEnhancerAmounts)
        lastFlatBypass = flatBypass && !anyEnhancerOn

        lastBassBoostStrength = bassBoostStrength
        lastVirtualizerStrength = virtualizerStrength

        val base31 = Dsp.computeEffective31(layers, withContour = false, channel = 0)
        currentInputGainDb = routeBaseDb - kotlin.math.max(0.0, (layers.correction31.maxOrNull() ?: 0.0) - 3.0).toFloat()
        currentMakeupGainDb = if (lastFlatBypass) 0f else (-currentInputGainDb - Dsp.pinkGainDb(base31)).coerceIn(0.0, 4.0).toFloat()

        ensureTargetSessionsAttached(internalSessionId)
        loadAllSessions(glideMs = 250.0)

        if (lastDolbyControlActive) {
            val deepBassAmt = if (lastEnabled && !lastAbCompare) {
                lastEnhancerAmounts.getOrElse(Enhancer.DEEP_BASS.ordinal) { 0.0 }
            } else 0.0
            dolbyControl.apply(lastDolbyDeepBass && deepBassAmt > 0.0, deepBassAmt)
        }
    }

    /**
     * Called on slider drags, enhancer drags, and volume contour ticks (v5 Section 9 `update` & Enhancers `onDrag`).
     * Never modifies `inputDb`, `makeupDb`, MBC, or side effects (`Virtualizer`, `BassBoost`, `Dolby`) and glides over 60 ms.
     */
    fun updateSlidersOrContourFast(
        enabled: Boolean,
        abCompare: Boolean,
        layers: Layers,
        flatBypass: Boolean,
        enhancerAmounts: DoubleArray = lastEnhancerAmounts,
        internalSessionId: Int = 0
    ) {
        lastEnabled = enabled && !(usbBitPerfectEnabled && getCurrentRoute() == OutputRoute.USB)
        lastAbCompare = abCompare
        lastLayers = layers
        lastEnhancerAmounts = enhancerAmounts.copyOf()
        val anyEnhancerOn = EnhancerMixer.anyActive(lastEnhancerAmounts)
        lastFlatBypass = flatBypass && !anyEnhancerOn

        ensureTargetSessionsAttached(internalSessionId)
        for (bundle in activeBundles.values) {
            val v5 = bundle.v5Engine
            if (v5 != null) {
                v5.setEnabled(lastEnabled && !lastFlatBypass)
                v5.setAbCompareMode(lastAbCompare)
                v5.update(lastLayers, glideMs = 60.0)
            } else {
                applyTier1FallbackToBundle(bundle, commitSideEffects = false)
            }
        }
    }

    fun restoreDolbyProperties() {
        dolbyControl.restore()
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

        val eq: Equalizer? = try {
            Equalizer(0, sessionId)
        } catch (_: Throwable) {
            null
        }

        val bbFx = BassBoostFx(sessionId)
        val virtFx = VirtualizerFx(sessionId)

        val le: LoudnessEnhancer? = try {
            LoudnessEnhancer(sessionId)
        } catch (_: Throwable) {
            null
        }

        if (v5Engine != null || eq != null) {
            activeBundles[sessionId] = SessionEffectBundle(
                sessionId = sessionId,
                v5Engine = v5Engine,
                equalizer = eq,
                bassBoostFx = bbFx,
                virtualizerFx = virtFx,
                loudnessEnhancer = le
            )
        } else {
            bbFx.release()
            virtFx.release()
            try { le?.release() } catch (_: Throwable) {}
        }
    }

    private fun loadAllSessions(glideMs: Double) {
        val isSmallSpeaker = getCurrentRoute() == OutputRoute.SPEAKER
        val surroundAmount = lastEnhancerAmounts.getOrElse(Enhancer.SURROUND.ordinal) { 0.0 }
        val activeGate = lastEnabled && !lastFlatBypass && !lastAbCompare

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

                // v5 Section 13 & Enhancers Section 4.2: When DynamicsProcessing is active, disable native Equalizer and set BassBoost to 0
                try { bundle.equalizer?.enabled = false } catch (_: Throwable) {}
                bundle.bassBoostFx?.apply(0.0, enabledGate = false)

                // Enhancers Section 4.1: Surround VirtualizerFx applied on commit
                bundle.virtualizerFx?.apply(surroundAmount, small = isSmallSpeaker, enabledGate = activeGate)
            } else {
                applyTier1FallbackToBundle(bundle, commitSideEffects = true)
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
     * Legacy Tier 1 fallback (v5 Section 13, 16.4 & Enhancers Section 4.2) when `DynamicsProcessing` fails on a session:
     * Reads each native band's edges with `getBandFreqRange()` (millihertz) and uses the mean of the 31-band
     * `effective31` curve (including `enhancer31`) over that band (`AutoEq.map31ToNativeBands`), capped at +4 dB,
     * plus `BassBoostFx` (`600 * deepBassAmount`) and `VirtualizerFx` (`Surround`).
     */
    private fun applyTier1FallbackToBundle(bundle: SessionEffectBundle, commitSideEffects: Boolean) {
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

        if (commitSideEffects) {
            val isSmallSpeaker = getCurrentRoute() == OutputRoute.SPEAKER
            val deepBassAmount = lastEnhancerAmounts.getOrElse(Enhancer.DEEP_BASS.ordinal) { 0.0 }
            val surroundAmount = lastEnhancerAmounts.getOrElse(Enhancer.SURROUND.ordinal) { 0.0 }
            bundle.bassBoostFx?.apply(deepBassAmount, enabledGate = active)
            bundle.virtualizerFx?.apply(surroundAmount, small = isSmallSpeaker, enabledGate = active)
        }
    }

    private fun runShellCommand(command: String, useSu: Boolean): String? {
        return try {
            val cmd = if (useSu) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
            val proc = Runtime.getRuntime().exec(cmd)
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        } catch (_: Throwable) {
            null
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
        dolbyControl.restore()
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
