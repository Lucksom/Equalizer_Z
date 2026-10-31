package com.audioeq.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.core.content.ContextCompat
import com.audioeq.model.CapabilityLevel
import com.audioeq.model.EngineMode
import com.audioeq.model.EqConstants
import com.audioeq.model.MbcConfig
import com.audioeq.model.ModeStatusInfo
import com.audioeq.model.OutputRoute
import com.audioeq.shizuku.ShizukuManager
import com.audioeq.shizuku.ShizukuState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.max

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
    val shizukuState: ShizukuState = ShizukuState.NOT_INSTALLED,
    val shizukuPrivilege: String = "none",
    val rootSuBinaryDetected: Boolean = false,
    val activeSessions: List<Int> = emptyList(),
    val connectedProductName: String? = null,
    val connectedDeviceKey: String = "speaker:${Build.MODEL}",
    val usbBitPerfectSupported: Boolean = false,
    val usbBitPerfectActive: Boolean = false,
    val bluetoothCodecSummary: String? = null,
    val btDisableAbsVolActive: Boolean = false,
    val virtualizerSupported: Boolean = true,
    val hardwareDaxDetected: Boolean = false,
    val hardwareDaxActive: Boolean = false,
    val activeOperatingMode: OperatingMode = OperatingMode.LEGACY_TIER2_DP,
    val avgBinderWriteCostMs: Double = 0.0
)

/**
 * AudioEngineController wired to Equalizer Master Database & Engineering Specification (v6):
 * - Hosts `MasterEqController` (`MasterEqEngine.kt`) per active audio session (and Global Session 0 for Root/Shizuku)
 * - Supports all 3 Operating Modes:
 *   1. `ROOT_GLOBAL` (Global Session 0 `DynamicsProcessing` + Optional OEM Hardware Bridge `9d4921da-8225-4f29-aefa-39537a04bcaa`)
 *   2. `SHIZUKU_GLOBAL` (Global Session 0 `DynamicsProcessing` + `dumpsys media.audio_flinger` via Shizuku UserService or DUMP permission)
 *   3. `LEGACY_TIER2_DP` / `LEGACY_TIER1_NATIVE` (Per-app `audioSessionId` via `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`)
 * - Executes `MasterEqController.loadFullState(state, glideMs = 250.0)` on Profile, Preset, Route, AutoEq, or Intensity change
 * - Executes `MasterEqController.updateSlidersOrVolume(state, glideMs = 60.0)` while dragging sliders or changing system volume
 */
class AudioEngineController(private val context: Context) {

    private class SessionEffectBundle(
        val sessionId: Int,
        val masterController: MasterEqController,
        var v5Engine: EqEngine? = null,
        var bassBoostFx: BassBoostFx? = null,
        var virtualizerFx: VirtualizerFx? = null
    ) {
        fun release() {
            try { masterController.release() } catch (_: Exception) {}
            try { v5Engine?.release() } catch (_: Exception) {}
            try { bassBoostFx?.release() } catch (_: Exception) {}
            try { virtualizerFx?.release() } catch (_: Exception) {}
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
    private var lastDaxProfile: DaxProfile = DaxProfile.MUSIC
    private var lastDolbyBoseConfig: DolbyBoseDspConfig = DolbyBoseDspConfig()
    private var lastMbc: MbcConfig = EqConstants.DEFAULT_MBC_CONFIG
    private var lastRouteBaseDb: Float = -3.0f
    private var lastLimiterDb: Float = -1.0f
    private var lastFlatBypass: Boolean = false
    private var lastBassBoostStrength: Int = 150
    private var lastVirtualizerStrength: Int = 150
    private var lastTier1Native5Db: FloatArray = FloatArray(5)
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
    var currentCeilingLoadDb: Float = 0.0f
        private set

    var diagnostics: DeviceDiagnostics = DeviceDiagnostics()
        private set

    private var hardwareProbedOnce: Boolean = false
    private var probedPositiveSessionOnce: Boolean = false
    private var cachedNativeBands: Int = 5
    private val cachedCentersHz = mutableListOf(60, 230, 910, 3600, 14000)
    private val cachedFreqRanges = mutableListOf(
        intArrayOf(30_000, 120_000),
        intArrayOf(120_001, 460_000),
        intArrayOf(460_001, 1_800_000),
        intArrayOf(1_800_001, 7_000_000),
        intArrayOf(7_000_001, 20_000_000)
    )
    private var cachedMinDb: Float = -15f
    private var cachedMaxDb: Float = 15f
    private var cachedGlobal0Ok: Boolean = false
    private var cachedDpOk: Boolean = false
    private var cachedDpBandCount: Int = 31
    private var cachedVirtSupported: Boolean = true

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

    fun getCurrentAudioRoute(): AudioRoute = when (getCurrentRoute()) {
        OutputRoute.SPEAKER -> AudioRoute.SPEAKER
        OutputRoute.WIRED -> AudioRoute.WIRED
        OutputRoute.BLUETOOTH -> AudioRoute.BLUETOOTH
        OutputRoute.USB -> AudioRoute.USB
    }

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

    fun attachExternalSession(sessionId: Int) {
        if (sessionId <= 0) return
        broadcastSessions.add(sessionId)
        attachToSessionIfNeeded(sessionId)
        loadAllSessions(glideMs = 250.0)
    }

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

    private fun resolvePreferredOperatingMode(): OperatingMode {
        return when (currentMode) {
            EngineMode.ROOT -> OperatingMode.ROOT_GLOBAL
            EngineMode.ADB_SHIZUKU -> OperatingMode.SHIZUKU_GLOBAL
            EngineMode.LEGACY -> if (diagnostics.dynamicsProcessingSupported) {
                OperatingMode.LEGACY_TIER2_DP
            } else {
                OperatingMode.LEGACY_TIER1_NATIVE
            }
        }
    }

    fun probeCapabilities(internalReferenceSessionId: Int = 0): Map<EngineMode, ModeStatusInfo> {
        val isSmallRoute = getCurrentRoute() == OutputRoute.SPEAKER

        if (!hardwareProbedOnce && activeBundles.isEmpty()) {
            hardwareProbedOnce = true
            val centers = mutableListOf<Int>()
            val freqRanges = mutableListOf<IntArray>()
            try {
                val eq0 = Equalizer(0, 0)
                cachedGlobal0Ok = true
                val count = eq0.numberOfBands.toInt()
                if (count > 0) {
                    cachedNativeBands = count
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
                    cachedMinDb = range[0] / 100f
                    cachedMaxDb = range[1] / 100f
                }
                eq0.release()
            } catch (_: Throwable) {
                cachedGlobal0Ok = false
            }

            if (centers.isNotEmpty()) {
                cachedCentersHz.clear()
                cachedCentersHz.addAll(centers)
            }
            if (freqRanges.isNotEmpty()) {
                cachedFreqRanges.clear()
                cachedFreqRanges.addAll(freqRanges)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val testSession = if (internalReferenceSessionId > 0) internalReferenceSessionId else 0
                val candidates = if (compatibility10Band) intArrayOf(10) else intArrayOf(31, 20, 10)
                for (c in candidates) {
                    var dp: DynamicsProcessing? = null
                    try {
                        val cfg = DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2,
                            true, c,
                            true, 6,
                            false, 0,
                            true
                        ).build()
                        dp = DynamicsProcessing(0, testSession, cfg)
                        val probeBand = DynamicsProcessing.EqBand(true, EqMath.RENDER_31_CUTOFF_HZ[0], 0f)
                        dp.setPreEqBandAllChannelsTo(0, probeBand)
                        cachedDpOk = true
                        cachedDpBandCount = c
                        dp.release()
                        dp = null
                        break
                    } catch (_: Throwable) {
                        try { dp?.release() } catch (_: Throwable) {}
                        cachedDpOk = false
                    }
                }
            }

            val probeVirt = VirtualizerFx(if (internalReferenceSessionId > 0) internalReferenceSessionId else 0)
            cachedVirtSupported = probeVirt.supported(isSmallRoute)
            probeVirt.release()
        } else if (internalReferenceSessionId > 0 &&
            !probedPositiveSessionOnce &&
            !activeBundles.containsKey(internalReferenceSessionId)
        ) {
            probedPositiveSessionOnce = true
            if (!cachedDpOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val candidates = if (compatibility10Band) intArrayOf(10) else intArrayOf(31, 20, 10)
                for (c in candidates) {
                    var dp: DynamicsProcessing? = null
                    try {
                        val cfg = DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2,
                            true, c,
                            true, 6,
                            false, 0,
                            true
                        ).build()
                        dp = DynamicsProcessing(0, internalReferenceSessionId, cfg)
                        val probeBand = DynamicsProcessing.EqBand(true, EqMath.RENDER_31_CUTOFF_HZ[0], 0f)
                        dp.setPreEqBandAllChannelsTo(0, probeBand)
                        cachedDpOk = true
                        cachedDpBandCount = c
                        dp.release()
                        dp = null
                        break
                    } catch (_: Throwable) {
                        try { dp?.release() } catch (_: Throwable) {}
                    }
                }
            }
        }

        // If any active MasterEqController is running Tier 2 DP, reflect that in cachedDpOk
        val anyActiveDp = activeBundles.values.any {
            it.masterController.activeMode != OperatingMode.LEGACY_TIER1_NATIVE
        }
        if (anyActiveDp) {
            cachedDpOk = true
        } else if (activeBundles.isNotEmpty() && activeBundles.values.all {
                it.masterController.activeMode == OperatingMode.LEGACY_TIER1_NATIVE
            }) {
            cachedDpOk = false
        }

        val nativeBands = cachedNativeBands
        val centers = cachedCentersHz.toList()
        val freqRanges = cachedFreqRanges.toList()
        val minDb = cachedMinDb
        val maxDb = cachedMaxDb
        val global0Ok = cachedGlobal0Ok
        val dpOk = cachedDpOk
        val dpBandCount = if (compatibility10Band) 10 else cachedDpBandCount

        ShizukuManager.refresh()
        val shizukuState = ShizukuManager.state.value
        val shizukuPriv = ShizukuManager.privilege()
        val shizukuReady = shizukuState == ShizukuState.READY

        val dumpGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.DUMP
        ) == PackageManager.PERMISSION_GRANTED

        val shizukuPresent = ShizukuManager.isShizukuAppInstalled(context) ||
            shizukuState != ShizukuState.NOT_INSTALLED

        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/debug_ramdisk/su"
        )
        val hasSu = suPaths.any { File(it).exists() } || shizukuPriv == "root"

        val prodName = detectConnectedDeviceProductName(audioManager)
        val devKey = buildCurrentDeviceKey(audioManager)
        val bitPerfectSupported = UsbBitPerfectHelper.isBitPerfectSupported(audioManager)
        val btCodec = if (dumpGranted || hasSu || shizukuReady) readBluetoothCodecFromDumpsys(hasSu) else null

        val virtSupported = activeBundles.values.firstNotNullOfOrNull { it.virtualizerFx }
            ?.supported(isSmallRoute)
            ?: cachedVirtSupported
        val avgWriteMs = activeBundles.values.mapNotNull { it.v5Engine?.writeCostMs() }
            .filter { it > 0.0 }
            .takeIf { it.isNotEmpty() }
            ?.average() ?: 0.0
        val hwDaxSupported = OemDaxBridge.isHardwareDaxSupported()
        val hwDaxActive = activeBundles.values.any { it.masterController.oemBridgeActive }
        val activeOpMode = activeBundles.values.firstOrNull()?.masterController?.activeMode
            ?: if (dpOk) OperatingMode.LEGACY_TIER2_DP else OperatingMode.LEGACY_TIER1_NATIVE

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
            shizukuState = shizukuState,
            shizukuPrivilege = shizukuPriv,
            rootSuBinaryDetected = hasSu,
            activeSessions = activeBundles.keys.toList(),
            connectedProductName = prodName,
            connectedDeviceKey = devKey,
            usbBitPerfectSupported = bitPerfectSupported,
            usbBitPerfectActive = usbBitPerfectEnabled && bitPerfectSupported,
            bluetoothCodecSummary = btCodec,
            btDisableAbsVolActive = diagnostics.btDisableAbsVolActive,
            virtualizerSupported = virtSupported,
            hardwareDaxDetected = hwDaxSupported,
            hardwareDaxActive = hwDaxActive,
            activeOperatingMode = activeOpMode,
            avgBinderWriteCostMs = avgWriteMs
        )

        val pkgName = context.packageName

        val legacyStatus = ModeStatusInfo(
            mode = EngineMode.LEGACY,
            level = CapabilityLevel.AVAILABLE,
            summaryText = if (dpOk) {
                "Legacy Tier 2 ($dpBandCount-Band DP + 6-Band MBC + Limiter)"
            } else {
                "Legacy Tier 1 ($nativeBands-Band Native EQ + BassBoost)"
            },
            detailReason = buildString {
                append("Standard App UID (Section 1.1): attaches to per-app audioSessionId via ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION without ADB or Root. ")
                if (dpOk) {
                    append("Using Tier 2 31-band PCHIP pre-EQ, 20-band IEQ profile targets, 6-band MBC ceilings, and -1 dB limiter.")
                } else {
                    append("Using Tier 1 5-band native Equalizer (capped at +4 dB) + BassBoost + Virtualizer.")
                }
            },
            setupSteps = emptyList(),
            activeSessionsCount = activeBundles.size.coerceAtLeast(1)
        )

        val adbStatus = if (shizukuReady || dumpGranted) {
            val privTag = if (shizukuReady) "Shizuku ($shizukuPriv)" else "ADB DUMP"
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "$privTag Global (Session 0 + audio_flinger)",
                detailReason = buildString {
                    append("Shell UID 2000 / Shizuku UserService (Section 1.2): attaches DynamicsProcessing(0, 0, cfg) to Global Audio Session 0 and polls dumpsys media.audio_flinger.")
                    if (btCodec != null) append(" Detected BT codec: $btCodec.")
                },
                setupSteps = listOf(
                    if (shizukuReady) "Shizuku UserService connected ($shizukuPriv privilege)." else "Permission android.permission.DUMP is granted.",
                    "Global Session 0 + throttled delta-only binder writes (~33 Hz)."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            val shizukuNote = when (shizukuState) {
                ShizukuState.NEEDS_PERMISSION -> "Shizuku running — tap Grant Shizuku Permission"
                ShizukuState.DENIED_PERMANENTLY -> "Shizuku denied — re-allow in Shizuku app"
                ShizukuState.NOT_RUNNING -> "Shizuku installed — start Shizuku service"
                ShizukuState.UNSUPPORTED_VERSION -> "Update Shizuku to v11+"
                else -> "Shizuku not running"
            }
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires permission ($shizukuNote · Using Legacy fallback)",
                detailReason = "Grant Shizuku permission directly in-app or grant DUMP permission once via ADB to attach to Global Session 0 and read Bluetooth codec headroom.",
                setupSteps = listOf(
                    "1. Open Shizuku and start the service (Wireless Debugging on Android 11+ or ADB).",
                    "2. Tap 'Grant Shizuku Permission' below (or run: adb shell pm grant $pkgName android.permission.DUMP)."
                ),
                activeSessionsCount = activeBundles.size
            )
        }

        val rootStatus = if (hasSu) {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.AVAILABLE,
                summaryText = buildString {
                    append("Root Global (Session 0")
                    if (hwDaxSupported) append(" + OEM 20-Band Bridge")
                    append(")")
                },
                detailReason = buildString {
                    append("Superuser RAM Helper (Section 1.3 & Section 7): Global Session 0 DynamicsProcessing + boot persistence.")
                    if (hwDaxSupported) {
                        append(" OEM Hardware DAX Effect (9d4921da-8225-4f29-aefa-39537a04bcaa) detected: 96-byte 20-band GEQ packet bridge active.")
                    }
                },
                setupSteps = listOf(
                    "100% APK-only, RAM-only execution (Section 1.3): /system and /vendor are never modified.",
                    "Uses su helper for Global Session 0, OEM 96-byte GEQ bridge, and Bluetooth absolute-volume toggle."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires root access (APK-only · Using Legacy fallback)",
                detailReason = "No su binary detected. Root mode in v6 is strictly APK-only (RAM-only, zero flashing) and attaches Global Session 0 + optional OEM 20-band hardware bridge.",
                setupSteps = listOf(
                    "1. Grant su access in Magisk, KernelSU, or APatch if your device is rooted.",
                    "2. 100% APK-only, RAM-only execution: never touches /system or /vendor partitions."
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

    fun toggleRootBluetoothDisableAbsoluteVolume(disableAbsVol: Boolean): Result<String> {
        return try {
            val propVal = if (disableAbsVol) "true" else "false"
            val settingVal = if (disableAbsVol) "1" else "0"
            val cmd = "setprop persist.bluetooth.disableabsvol $propVal; settings put global bluetooth_disable_absolute_volume $settingVal"
            if (ShizukuManager.state.value == ShizukuState.READY) {
                ShizukuManager.execIfBound(cmd)
                diagnostics = diagnostics.copy(btDisableAbsVolActive = disableAbsVol)
                Result.success(
                    if (disableAbsVol) {
                        "Set persist.bluetooth.disableabsvol=true via Shizuku (software volume contour active on Bluetooth)."
                    } else {
                        "Restored default Bluetooth absolute volume behavior."
                    }
                )
            } else if (diagnostics.rootSuBinaryDetected) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
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
                    IllegalStateException("Root su or Shizuku not available. Enable 'Disable absolute volume' in Android Developer Options.")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildMasterEngineState(
        enabled: Boolean,
        flatBypass: Boolean,
        daxProfile: DaxProfile,
        dolbyBoseConfig: DolbyBoseDspConfig,
        layers: Layers,
        mbc: MbcConfig,
        tier1Native5Db: FloatArray,
        bassBoostStrength: Int
    ): EngineState {
        val opMode = resolvePreferredOperatingMode()
        val audioRoute = getCurrentAudioRoute()
        val volRatio = getCurrentVolumeRatio()
        val mbcSettings = MbcSettings(
            attackMs = mbc.attackMs.clone(),
            releaseMs = mbc.releaseMs.clone(),
            ratio = mbc.ratio.clone(),
            baseThresholdDb = mbc.baseThresholdDb.clone(),
            kneeDb = mbc.kneeDb.clone()
        )
        val hearing31 = DoubleArray(31) { i ->
            0.5 * (layers.hearingLeft31.getOrElse(i) { 0.0 } + layers.hearingRight31.getOrElse(i) { 0.0 })
        }
        return EngineState(
            enabled = enabled,
            flatBypass = flatBypass,
            mode = opMode,
            route = audioRoute,
            profile = daxProfile,
            userSliders10Db = layers.userDb.clone(),
            intensity = layers.intensity,
            volumeContourOn = layers.contourDb.any { kotlin.math.abs(it) > 0.001 },
            volumeRatio = volRatio,
            correction31Db = layers.correction31.clone(),
            mbc = mbcSettings,
            tier1Native5Db = tier1Native5Db.clone(),
            tier1BassBoost = bassBoostStrength.coerceIn(0, 1000).toShort(),
            hearing31Db = hearing31,
            dolbyBoseConfig = dolbyBoseConfig
        )
    }

    /**
     * Section 9 & Section 10 Step 3 — Called on Profile, Preset, Route, AutoEq, or Intensity COMMIT:
     * Recomputes `inputGainDb` and `makeupDb` via `EqMath` and `MasterEqController.loadFullState(newState, glideMs = 250.0)`.
     */
    fun loadEngineState(
        mode: EngineMode,
        enabled: Boolean,
        abCompare: Boolean,
        daxProfile: DaxProfile = DaxProfile.MUSIC,
        dolbyBoseConfig: DolbyBoseDspConfig = lastDolbyBoseConfig,
        layers: Layers,
        mbc: MbcConfig,
        routeBaseDb: Float,
        limiterDb: Float,
        flatBypass: Boolean,
        tier1Native5Db: FloatArray = FloatArray(5),
        bassBoostStrength: Int = 0,
        virtualizerStrength: Int = 0,
        internalSessionId: Int = 0
    ) {
        currentMode = mode
        lastEnabled = enabled && !(usbBitPerfectEnabled && getCurrentRoute() == OutputRoute.USB)
        lastAbCompare = abCompare
        lastDaxProfile = daxProfile
        lastDolbyBoseConfig = dolbyBoseConfig
        lastLayers = layers
        lastMbc = mbc
        lastRouteBaseDb = routeBaseDb
        lastLimiterDb = limiterDb
        val anyDolbyBoseActive = dolbyBoseConfig.ieqPreset != IeqPreset.OFF ||
            dolbyBoseConfig.dialogueEnhancerEnabled ||
            dolbyBoseConfig.bassEnhancerEnabled ||
            dolbyBoseConfig.boseProfile != BoseProfile.OFF
        lastFlatBypass = flatBypass && daxProfile == DaxProfile.MUSIC && !anyDolbyBoseActive

        lastTier1Native5Db = tier1Native5Db.copyOf()
        lastBassBoostStrength = if (dolbyBoseConfig.bassEnhancerEnabled) {
            max(bassBoostStrength, 650)
        } else {
            bassBoostStrength
        }
        lastVirtualizerStrength = max(virtualizerStrength, daxProfile.virtualizerStrength.toInt())

        // Compute exact EqMath inputGainDb, makeupDb, and ceilingLoadDb synchronously for immediate UI feedback
        val masterState = buildMasterEngineState(
            enabled = lastEnabled && !lastAbCompare,
            flatBypass = lastFlatBypass,
            daxProfile = lastDaxProfile,
            dolbyBoseConfig = lastDolbyBoseConfig,
            layers = lastLayers,
            mbc = lastMbc,
            tier1Native5Db = lastTier1Native5Db,
            bassBoostStrength = lastBassBoostStrength
        )
        val noContour31 = EqMath.computeEffective31(masterState, includeContour = false)
        val maxPosGain = (noContour31.maxOrNull() ?: 0.0).toFloat()
        val antiClipHeadroomDb = DolbyBoseDspMath.computeSafeHeadroomDb(maxPosGain)
        val corrPeak = masterState.correction31Db.maxOrNull() ?: 0.0
        val routeInDb = masterState.route.baseInputGainDb - max(0.0, corrPeak - 3.0).toFloat()
        currentInputGainDb = kotlin.math.min(routeInDb, antiClipHeadroomDb).coerceAtLeast(-11.0f)

        val levelerBonus = if (masterState.profile.levelerOn) 1.0 else 0.0
        currentMakeupGainDb = if (masterState.flatBypass) {
            0f
        } else {
            (-currentInputGainDb - EqMath.pinkGainDb(noContour31) + levelerBonus)
                .coerceIn(0.0, 4.0)
                .toFloat()
        }
        val eff31WithContour = EqMath.computeEffective31(masterState, includeContour = true)
        currentCeilingLoadDb = EqMath.computeCeilingLoadDb(eff31WithContour)

        ensureTargetSessionsAttached(internalSessionId)
        loadAllSessions(glideMs = 250.0)
    }

    /**
     * Section 9 & Section 10 Step 4 — Called while dragging any of the 10 sliders or changing system volume:
     * Freezes `makeupDb` and `inputGainDb` and calls `MasterEqController.updateSlidersOrVolume(newState, glideMs = 60.0)`.
     */
    fun updateSlidersOrContourFast(
        enabled: Boolean,
        abCompare: Boolean,
        daxProfile: DaxProfile = lastDaxProfile,
        dolbyBoseConfig: DolbyBoseDspConfig = lastDolbyBoseConfig,
        layers: Layers,
        flatBypass: Boolean,
        tier1Native5Db: FloatArray = lastTier1Native5Db,
        internalSessionId: Int = 0
    ) {
        lastEnabled = enabled && !(usbBitPerfectEnabled && getCurrentRoute() == OutputRoute.USB)
        lastAbCompare = abCompare
        lastDaxProfile = daxProfile
        lastDolbyBoseConfig = dolbyBoseConfig
        lastLayers = layers
        lastTier1Native5Db = tier1Native5Db.copyOf()
        val anyDolbyBoseActive = dolbyBoseConfig.ieqPreset != IeqPreset.OFF ||
            dolbyBoseConfig.dialogueEnhancerEnabled ||
            dolbyBoseConfig.bassEnhancerEnabled ||
            dolbyBoseConfig.boseProfile != BoseProfile.OFF
        lastFlatBypass = flatBypass && daxProfile == DaxProfile.MUSIC && !anyDolbyBoseActive

        val masterState = buildMasterEngineState(
            enabled = lastEnabled && !lastAbCompare,
            flatBypass = lastFlatBypass,
            daxProfile = lastDaxProfile,
            dolbyBoseConfig = lastDolbyBoseConfig,
            layers = lastLayers,
            mbc = lastMbc,
            tier1Native5Db = lastTier1Native5Db,
            bassBoostStrength = lastBassBoostStrength
        )
        val eff31 = EqMath.computeEffective31(masterState, includeContour = true)
        currentCeilingLoadDb = EqMath.computeCeilingLoadDb(eff31)

        ensureTargetSessionsAttached(internalSessionId)
        for (bundle in activeBundles.values) {
            bundle.masterController.updateSlidersOrVolume(masterState, glideMs = 60.0)
            val v5 = bundle.v5Engine
            if (v5 != null && (layers.rightUserDb != null || kotlin.math.abs(layers.balanceDb) > 0.05f)) {
                v5.setEnabled(lastEnabled && !lastFlatBypass)
                v5.setAbCompareMode(lastAbCompare)
                v5.update(lastLayers, glideMs = 60.0)
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
        val shizukuReady = ShizukuManager.state.value == ShizukuState.READY
        if ((currentMode == EngineMode.ADB_SHIZUKU && (diagnostics.dumpPermissionGranted || shizukuReady)) ||
            (currentMode == EngineMode.ROOT && diagnostics.rootSuBinaryDetected)
        ) {
            targetSessions.addAll(
                discoverSessionsViaDumpsys(
                    useSu = currentMode == EngineMode.ROOT && diagnostics.rootSuBinaryDetected
                )
            )
        }
        for (sId in targetSessions) {
            attachToSessionIfNeeded(sId)
        }
    }

    private fun attachToSessionIfNeeded(sessionId: Int) {
        if (activeBundles.containsKey(sessionId)) return

        val preferredOpMode = resolvePreferredOperatingMode()
        val masterController = MasterEqController(sessionId)
        val actualMode = masterController.start(preferredOpMode)

        var v5Engine: EqEngine? = null
        if ((compatibility10Band || transientMode) && actualMode == OperatingMode.LEGACY_TIER1_NATIVE) {
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
        }

        val bbFx = if (actualMode == OperatingMode.LEGACY_TIER1_NATIVE) BassBoostFx(sessionId) else null
        val virtFx = VirtualizerFx(sessionId)

        activeBundles[sessionId] = SessionEffectBundle(
            sessionId = sessionId,
            masterController = masterController,
            v5Engine = v5Engine,
            bassBoostFx = bbFx,
            virtualizerFx = virtFx
        )
    }

    private fun loadAllSessions(glideMs: Double) {
        val isSmallSpeaker = getCurrentRoute() == OutputRoute.SPEAKER
        val surroundAmount = lastDaxProfile.virtualizerStrength / 1000.0
        val activeGate = lastEnabled && !lastFlatBypass && !lastAbCompare

        val masterState = buildMasterEngineState(
            enabled = lastEnabled && !lastAbCompare,
            flatBypass = lastFlatBypass,
            daxProfile = lastDaxProfile,
            dolbyBoseConfig = lastDolbyBoseConfig,
            layers = lastLayers,
            mbc = lastMbc,
            tier1Native5Db = lastTier1Native5Db,
            bassBoostStrength = lastBassBoostStrength
        )

        for (bundle in activeBundles.values) {
            bundle.masterController.loadFullState(masterState, glideMs = glideMs)

            val v5 = bundle.v5Engine
            if (v5 != null && (lastLayers.rightUserDb != null || kotlin.math.abs(lastLayers.balanceDb) > 0.05f)) {
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
            }

            if (bundle.masterController.activeMode != OperatingMode.LEGACY_TIER1_NATIVE) {
                bundle.bassBoostFx?.apply(0.0, enabledGate = false)
                bundle.virtualizerFx?.apply(surroundAmount, small = isSmallSpeaker, enabledGate = activeGate)
            } else {
                val deepBassAmount = lastBassBoostStrength / 1000.0
                bundle.bassBoostFx?.apply(deepBassAmount, enabledGate = activeGate)
                bundle.virtualizerFx?.apply(surroundAmount, small = isSmallSpeaker, enabledGate = activeGate)
            }
        }
    }

    private fun discoverSessionsViaDumpsys(useSu: Boolean = false): Set<Int> {
        val discovered = mutableSetOf<Int>()
        val sessionRegex = Regex("""session(?:\s+id|\s*Id|:)?\s*[=:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        try {
            val shizukuOutput = if (!useSu && ShizukuManager.state.value == ShizukuState.READY) {
                ShizukuManager.execIfBound("dumpsys media.audio_flinger | head -c 200000")
            } else null

            if (shizukuOutput != null) {
                shizukuOutput.lineSequence().forEach { line ->
                    sessionRegex.findAll(line).forEach { match ->
                        val id = match.groupValues.getOrNull(1)?.toIntOrNull()
                        if (id != null && id > 0) {
                            discovered.add(id)
                        }
                    }
                }
                return discovered
            }

            val cmd = if (useSu) arrayOf("su", "-c", "dumpsys media.audio_flinger")
            else arrayOf("sh", "-c", "dumpsys media.audio_flinger")
            val proc = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
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

    private fun readBluetoothCodecFromDumpsys(useSu: Boolean = false): String? {
        val codecRegex = Regex("""codecName\s*[:=]\s*([A-Za-z0-9_+-]+)""", RegexOption.IGNORE_CASE)
        return try {
            val shizukuOutput = if (!useSu && ShizukuManager.state.value == ShizukuState.READY) {
                ShizukuManager.execIfBound("dumpsys bluetooth_manager | head -c 120000")
            } else null

            if (shizukuOutput != null) {
                for (line in shizukuOutput.lineSequence()) {
                    val m = codecRegex.find(line)
                    if (m != null) return m.groupValues[1]
                }
            }

            val cmd = if (useSu) arrayOf("su", "-c", "dumpsys bluetooth_manager")
            else arrayOf("sh", "-c", "dumpsys bluetooth_manager")
            val proc = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
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
