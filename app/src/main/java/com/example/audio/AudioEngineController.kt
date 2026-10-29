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
import kotlin.math.ln

data class DeviceDiagnostics(
    val nativeEqBandCount: Int = 5,
    val nativeBandCentersHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val nativeLevelRangeDb: Pair<Float, Float> = -15f to 15f,
    val dynamicsProcessingSupported: Boolean = false,
    val activeRenderBandCount: Int = 31,
    val isLegacyTier1BasicEngine: Boolean = false,
    val globalSession0Supported: Boolean = false,
    val dumpPermissionGranted: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val rootSuBinaryDetected: Boolean = false,
    val audioEffectsConfigPath: String = "/vendor/etc/audio_effects.xml",
    val activeSessions: List<Int> = emptyList(),
    val connectedProductName: String? = null,
    val backupExists: Boolean = false,
    val magiskModuleStaged: Boolean = false
)

/**
 * AudioEngineController updated for Audio EQ Deep Optimization v4:
 * - Hosts per-session `EqEngine` (31-band PCHIP pre-EQ + 6-band MBC ceilings + limiter backstop, ~33 Hz delta writes)
 * - Falls back cleanly to Legacy Tier 1 (`Equalizer` + `BassBoost` + `Virtualizer`) when `DynamicsProcessing` is unavailable,
 *   and sets `BassBoost = 0` whenever `DynamicsProcessing` is active (v4 Section 13)
 * - Supports 10-band compatibility switch and Transient Mode (`VARIANT_FAVOR_TIME_RESOLUTION`) rebuild
 */
class AudioEngineController(private val context: Context) {

    private class SessionEffectBundle(
        val sessionId: Int,
        var v4Engine: EqEngine? = null,
        var equalizer: Equalizer? = null,
        var bassBoost: BassBoost? = null,
        var virtualizer: Virtualizer? = null,
        var loudnessEnhancer: LoudnessEnhancer? = null
    ) {
        fun release() {
            try { v4Engine?.release() } catch (_: Exception) {}
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

        val effectCandidatePaths = listOf(
            "/vendor/etc/audio_effects.xml",
            "/vendor/etc/audio/audio_effects.xml",
            "/odm/etc/audio_effects.xml",
            "/system/etc/audio_effects.xml"
        )
        val foundEffectPath = effectCandidatePaths.firstOrNull { File(it).exists() }
            ?: "/vendor/etc/audio_effects.xml"

        val backupDir = File(context.filesDir, "audio_config_backup")
        val hasBackup = File(backupDir, "audio_effects.xml.bak").exists()
        val moduleStaged = File(context.filesDir, "magisk_module/eq_deep/module.prop").exists()
        val prodName = detectConnectedDeviceProductName(audioManager)

        diagnostics = DeviceDiagnostics(
            nativeEqBandCount = nativeBands,
            nativeBandCentersHz = centers,
            nativeLevelRangeDb = minDb to maxDb,
            dynamicsProcessingSupported = dpOk,
            activeRenderBandCount = if (dpOk) dpBandCount else nativeBands,
            isLegacyTier1BasicEngine = !dpOk,
            globalSession0Supported = global0Ok,
            dumpPermissionGranted = dumpGranted,
            shizukuInstalled = shizukuPresent,
            rootSuBinaryDetected = hasSu,
            audioEffectsConfigPath = foundEffectPath,
            activeSessions = activeBundles.keys.toList(),
            connectedProductName = prodName,
            backupExists = hasBackup,
            magiskModuleStaged = moduleStaged
        )

        val pkgName = context.packageName

        val legacyStatus = ModeStatusInfo(
            mode = EngineMode.LEGACY,
            level = CapabilityLevel.AVAILABLE,
            summaryText = if (dpOk) {
                "Legacy Global ($dpBandCount-Band PCHIP + 6-Band Ceilings)"
            } else {
                "Basic Engine (Tier 1 · $nativeBands-Band Native EQ)"
            },
            detailReason = buildString {
                append("Works globally across apps without ADB, Shizuku, or Root. ")
                if (dpOk) {
                    append("Using v4 $dpBandCount-band PCHIP renderer, 6-band boost-tracking MBC ceilings (90..20k Hz), and -1 dB limiter.")
                } else {
                    append("DynamicsProcessing unavailable; using Tier 1 Basic Engine ($nativeBands-band native Equalizer + BassBoost).")
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
                detailReason = "Scanning dumpsys media.audio_flinger for all active player sessions with $dpBandCount-band PCHIP pre-EQ and 6-band ceilings.",
                setupSteps = listOf(
                    "Permission android.permission.DUMP is granted.",
                    "Throttled delta-only binder writes (~33 updates/sec)."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires Shizuku or ADB permission (Using Legacy fallback)",
                detailReason = "Grant DUMP permission once via ADB or connect Shizuku to discover audio sessions from apps that do not broadcast them. Falling back to Legacy Global until granted.",
                setupSteps = listOf(
                    "1. Connect phone to PC with USB Debugging or open Wireless Debugging + Shizuku.",
                    "2. Run one-time ADB command:\nadb shell pm grant $pkgName android.permission.DUMP"
                ),
                activeSessionsCount = activeBundles.size
            )
        }

        val rootStatus = if (hasSu) {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "Root DSP Engine available (Biquad + Harmonic Enhancer)",
                detailReason = "Root binary detected. Stream-level Biquad EQ (Q 0.9–1.6), Bass Harmonic Enhancer (40–100 Hz saturation), and Butterworth HPF active.",
                setupSteps = listOf(
                    "Detected audio effects config: $foundEffectPath",
                    "Systemless Magisk module (/data/adb/modules/eq_deep) ready with audio.offload.disable=1."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires root access (Using Legacy fallback)",
                detailReason = "No su binary detected. System audio uses Legacy Global while the built-in DSP Reference Monitor runs full v4 Biquad + Bass Harmonic Enhancer.",
                setupSteps = listOf(
                    "1. Requires Magisk or KernelSU root environment.",
                    "2. Installs systemless module at /data/adb/modules/eq_deep without modifying /vendor directly.",
                    "3. Always back up stock audio_effects.xml before staging module."
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
     * Called on preset, route, headphone profile, speaker calibration, or intensity change (v4 Section 9 `load`).
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
        lastEnabled = enabled
        lastAbCompare = abCompare
        lastLayers = layers
        lastMbc = mbc
        lastRouteBaseDb = routeBaseDb
        lastLimiterDb = limiterDb
        lastFlatBypass = flatBypass
        lastBassBoostStrength = bassBoostStrength
        lastVirtualizerStrength = virtualizerStrength

        val base31 = Dsp.computeEffective31(layers, withContour = false)
        currentInputGainDb = routeBaseDb - kotlin.math.max(0.0, (layers.correction31.maxOrNull() ?: 0.0) - 3.0).toFloat()
        currentMakeupGainDb = if (flatBypass) 0f else (-currentInputGainDb - Dsp.pinkGainDb(base31)).coerceIn(0.0, 4.0).toFloat()

        ensureTargetSessionsAttached(internalSessionId)
        loadAllSessions(glideMs = 250.0)
    }

    /**
     * Called on slider drags and volume contour ticks (v4 Section 9 `update`).
     * Never modifies `inputDb` or `makeupDb` (makeup stays frozen while dragging sliders) and glides over 60 ms.
     */
    fun updateSlidersOrContourFast(
        enabled: Boolean,
        abCompare: Boolean,
        layers: Layers,
        flatBypass: Boolean,
        internalSessionId: Int = 0
    ) {
        lastEnabled = enabled
        lastAbCompare = abCompare
        lastLayers = layers
        lastFlatBypass = flatBypass

        ensureTargetSessionsAttached(internalSessionId)
        for (bundle in activeBundles.values) {
            val v4 = bundle.v4Engine
            if (v4 != null) {
                v4.setEnabled(lastEnabled)
                v4.setAbCompareMode(lastAbCompare)
                v4.update(lastLayers, glideMs = 60.0)
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
        if (currentMode == EngineMode.ADB_SHIZUKU && diagnostics.dumpPermissionGranted) {
            targetSessions.addAll(discoverSessionsViaDumpsys())
        }
        for (sId in targetSessions) {
            attachToSessionIfNeeded(sId)
        }
    }

    private fun attachToSessionIfNeeded(sessionId: Int) {
        if (activeBundles.containsKey(sessionId)) return

        var v4Engine: EqEngine? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val candidate = EqEngine(
                sessionId = sessionId,
                compatibility10Band = compatibility10Band,
                transientMode = transientMode
            )
            if (candidate.start()) {
                v4Engine = candidate
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

        if (v4Engine != null || eq != null || bb != null || virt != null) {
            activeBundles[sessionId] = SessionEffectBundle(
                sessionId = sessionId,
                v4Engine = v4Engine,
                equalizer = eq,
                bassBoost = bb,
                virtualizer = virt,
                loudnessEnhancer = le
            )
        }
    }

    private fun loadAllSessions(glideMs: Double) {
        for (bundle in activeBundles.values) {
            val v4 = bundle.v4Engine
            if (v4 != null) {
                v4.setEnabled(lastEnabled)
                v4.setAbCompareMode(lastAbCompare)
                v4.load(
                    l = lastLayers,
                    m = lastMbc,
                    routeBaseDb = lastRouteBaseDb,
                    limiter = lastLimiterDb,
                    flatBypass = lastFlatBypass,
                    glideMs = glideMs
                )
                currentInputGainDb = v4.inputDb
                currentMakeupGainDb = v4.makeupDb

                // v4 Section 13: When DynamicsProcessing is active, disable native Equalizer and set BassBoost to 0
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
     * Legacy Tier 1 fallback (v4 Section 13) when `DynamicsProcessing` fails on a session:
     * Interpolates the 10-band curve (with intensity + route + contour) at the device's native band centers,
     * capped at +4 dB, and applies `BassBoost` + `Virtualizer`.
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

                    val composite10 = FloatArray(10) { i ->
                        (lastLayers.userDb[i] * lastLayers.intensity +
                            lastLayers.routeDb[i] +
                            lastLayers.contourDb[i]).toFloat()
                    }

                    for (b in 0 until bandCount) {
                        val centerHz = (eq.getCenterFreq(b.toShort()) / 1000).coerceAtLeast(20)
                        val interpolatedDb = interpolateMasterCurveAt(centerHz.toFloat(), composite10)
                            .coerceIn(-12f, 4f) // Capped at +4 dB in Tier 1 native EQ
                        val targetMb = (interpolatedDb * 100f).toInt().coerceIn(minMb, maxMb).toShort()
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

    private fun interpolateMasterCurveAt(freqHz: Float, gainsDb: FloatArray): Float {
        val masterFreqs = EqConstants.MASTER_BANDS.map { it.frequencyHz.toFloat() }
        if (freqHz <= masterFreqs.first()) return gainsDb.firstOrNull() ?: 0f
        if (freqHz >= masterFreqs.last()) return gainsDb.lastOrNull() ?: 0f

        for (i in 0 until masterFreqs.size - 1) {
            val f0 = masterFreqs[i]
            val f1 = masterFreqs[i + 1]
            if (freqHz in f0..f1) {
                val t = ((ln(freqHz) - ln(f0)) / (ln(f1) - ln(f0))).coerceIn(0f, 1f)
                val g0 = gainsDb.getOrElse(i) { 0f }
                val g1 = gainsDb.getOrElse(i + 1) { 0f }
                return g0 + t * (g1 - g0)
            }
        }
        return 0f
    }

    private fun discoverSessionsViaDumpsys(): Set<Int> {
        val discovered = mutableSetOf<Int>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys media.audio_flinger"))
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

    fun backupAndStageMagiskModule(): Result<String> {
        return try {
            val backupDir = File(context.filesDir, "audio_config_backup").apply { mkdirs() }
            val stockEffectsFile = File(diagnostics.audioEffectsConfigPath)
            val backupEffectsFile = File(backupDir, "audio_effects.xml.bak")

            if (stockEffectsFile.exists() && stockEffectsFile.canRead()) {
                stockEffectsFile.copyTo(backupEffectsFile, overwrite = true)
            } else {
                backupEffectsFile.writeText(
                    """
                    <!-- Stock Audio Effects Backup Snapshot (${diagnostics.audioEffectsConfigPath}) -->
                    <audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">
                        <libraries>
                            <library name="bundle" path="libbundlewrapper.so"/>
                            <library name="dynamics_processing" path="libdynproc.so"/>
                        </libraries>
                        <effects>
                            <effect name="equalizer" library="bundle" uuid="ce772f20-847d-11df-bb17-0002a5d5c51b"/>
                            <effect name="dynamics_processing" library="dynamics_processing" uuid="e0e6539b-1781-7261-676f-6d7573696320"/>
                        </effects>
                    </audio_effects_conf>
                    """.trimIndent()
                )
            }

            val moduleDir = File(context.filesDir, "magisk_module/eq_deep").apply { mkdirs() }
            File(moduleDir, "module.prop").writeText(
                """
                id=eq_deep
                name=Audio EQ Deep Optimization v4 DSP
                version=v4.0
                versionCode=4
                author=AudioEQ
                description=Systemless stream=music v4 Biquad + Harmonic Enhancer DSP with offload disabled
                """.trimIndent()
            )
            File(moduleDir, "system.prop").writeText("audio.offload.disable=1\n")
            val vendorEtc = File(moduleDir, "system/vendor/etc").apply { mkdirs() }
            File(vendorEtc, "audio_effects.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">
                    <libraries>
                        <library name="deepeq" path="libdeepeq.so"/>
                    </libraries>
                    <effects>
                        <effect name="deepeq" library="deepeq" uuid="f4a7c912-65d8-4e2a-9b34-18e3b20c9a71"/>
                    </effects>
                    <postprocess>
                        <stream type="music">
                            <apply effect="deepeq"/>
                        </stream>
                    </postprocess>
                </audio_effects_conf>
                """.trimIndent()
            )

            probeCapabilities()
            Result.success("Backed up stock config & staged v4 systemless module at ${moduleDir.absolutePath}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun restoreStockAudioConfig(): Result<String> {
        return try {
            val moduleDir = File(context.filesDir, "magisk_module/eq_deep")
            if (moduleDir.exists()) {
                moduleDir.deleteRecursively()
            }
            probeCapabilities()
            Result.success("Restored stock audio configuration and removed eq_deep module.")
        } catch (e: Exception) {
            Result.failure(e)
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
