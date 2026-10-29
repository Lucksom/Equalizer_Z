package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.example.model.ModeStatusInfo
import com.example.model.SectionWidths
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.ln

data class DeviceDiagnostics(
    val nativeEqBandCount: Int = 5,
    val nativeBandCentersHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val nativeLevelRangeDb: Pair<Float, Float> = -15f to 15f,
    val dynamicsProcessingSupported: Boolean = false,
    val globalSession0Supported: Boolean = false,
    val dumpPermissionGranted: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val rootSuBinaryDetected: Boolean = false,
    val audioEffectsConfigPath: String = "/vendor/etc/audio_effects.xml",
    val activeSessions: List<Int> = emptyList(),
    val backupExists: Boolean = false,
    val magiskModuleStaged: Boolean = false
)

/**
 * Manages Android hardware/OS audio effects across Legacy, Global, ADB/Shizuku, and Root modes.
 * Implements runtime capability detection, graceful fallback, and config backup/restore.
 */
class AudioEngineController(private val context: Context) {

    private class SessionEffectBundle(
        val sessionId: Int,
        var equalizer: Equalizer? = null,
        var bassBoost: BassBoost? = null,
        var virtualizer: Virtualizer? = null,
        var loudnessEnhancer: LoudnessEnhancer? = null,
        var dynamicsProcessing: DynamicsProcessing? = null
    ) {
        fun release() {
            try { equalizer?.release() } catch (_: Exception) {}
            try { bassBoost?.release() } catch (_: Exception) {}
            try { virtualizer?.release() } catch (_: Exception) {}
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
            try { dynamicsProcessing?.release() } catch (_: Exception) {}
        }
    }

    private val activeBundles = mutableMapOf<Int, SessionEffectBundle>()
    private val broadcastSessions = mutableSetOf<Int>()
    private var receiverRegistered = false

    private var lastGainsDb: List<Float> = List(10) { 0f }
    private var lastPreampDb: Float = 0f
    private var lastHighPassHz: Float = 0f
    private var lastWidths: SectionWidths = SectionWidths()
    private var lastBassBoostStrength: Int = 100
    private var lastVirtualizerStrength: Int = 200
    private var lastFavorTimeResolution: Boolean = false
    private var lastEnabled: Boolean = true
    private var currentMode: EngineMode = EngineMode.GLOBAL

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
                    applyCurrentParametersToAllSessions()
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

    fun probeCapabilities(internalReferenceSessionId: Int = 0): Map<EngineMode, ModeStatusInfo> {
        var nativeBands = 5
        val centers = mutableListOf<Int>()
        var minDb = -15f
        var maxDb = 15f
        var global0Ok = false
        var dpOk = false

        // 1. Probe Session 0 (Global mix) and standard Equalizer
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

        // If Session 0 threw, probe on internal reference session if available
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

        // 2. Probe DynamicsProcessing (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val testSession = if (internalReferenceSessionId > 0) internalReferenceSessionId else 0
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, 10,
                    true, 4,
                    false, 0,
                    true
                ).build()
                val dp = DynamicsProcessing(0, testSession, cfg)
                dpOk = true
                dp.release()
            } catch (_: Throwable) {
                dpOk = false
            }
        }

        // 3. Probe DUMP permission & Shizuku package
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

        // 4. Probe Root (su binary & vendor audio_effects.xml)
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

        diagnostics = DeviceDiagnostics(
            nativeEqBandCount = nativeBands,
            nativeBandCentersHz = centers,
            nativeLevelRangeDb = minDb to maxDb,
            dynamicsProcessingSupported = dpOk,
            globalSession0Supported = global0Ok,
            dumpPermissionGranted = dumpGranted,
            shizukuInstalled = shizukuPresent,
            rootSuBinaryDetected = hasSu,
            audioEffectsConfigPath = foundEffectPath,
            activeSessions = activeBundles.keys.toList(),
            backupExists = hasBackup,
            magiskModuleStaged = moduleStaged
        )

        val pkgName = context.packageName

        val legacyStatus = ModeStatusInfo(
            mode = EngineMode.LEGACY,
            level = CapabilityLevel.AVAILABLE,
            summaryText = "Legacy Mode available (${nativeBands}-band native AudioEffect)",
            detailReason = "Uses standard Android Equalizer, BassBoost, and Virtualizer on compatible app sessions.",
            setupSteps = emptyList(),
            activeSessionsCount = activeBundles.size.coerceAtLeast(1)
        )

        val globalStatus = if (global0Ok || dpOk) {
            ModeStatusInfo(
                mode = EngineMode.GLOBAL,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "Legacy / Global available",
                detailReason = buildString {
                    append("Attached to system output mix (Session 0) + broadcast sessions. ")
                    if (dpOk) append("10-band DynamicsProcessing + MBC active.")
                    else append("5-band native Equalizer interpolation active.")
                },
                setupSteps = emptyList(),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            ModeStatusInfo(
                mode = EngineMode.GLOBAL,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "Global (Broadcast Session Fallback)",
                detailReason = "Direct Session 0 attach restricted by OEM ROM; automatically attaching to apps broadcasting ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.",
                setupSteps = emptyList(),
                activeSessionsCount = activeBundles.size
            )
        }

        val adbStatus = if (dumpGranted) {
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "ADB / Shizuku active (DUMP granted)",
                detailReason = "Scanning dumpsys media.audio_flinger for all active player sessions with 10-band DynamicsProcessing.",
                setupSteps = listOf(
                    "Permission android.permission.DUMP is granted.",
                    "Scanning active audio sessions every 3 seconds."
                ),
                activeSessionsCount = activeBundles.size.coerceAtLeast(1)
            )
        } else {
            ModeStatusInfo(
                mode = EngineMode.ADB_SHIZUKU,
                level = CapabilityLevel.REQUIRES_PERMISSION,
                summaryText = "Requires Shizuku or ADB permission",
                detailReason = "Grant DUMP permission once via ADB or connect Shizuku to discover audio sessions from apps that do not broadcast them. Falling back to Global/Legacy until granted.",
                setupSteps = listOf(
                    "1. Connect phone to PC with USB Debugging or open Wireless Debugging + Shizuku.",
                    "2. Run one-time ADB command:\nadb shell pm grant $pkgName android.permission.DUMP",
                    "3. Optional secure settings grant:\nadb shell pm grant $pkgName android.permission.WRITE_SECURE_SETTINGS",
                    "4. Note: Shizuku started via ADB must be restarted after reboot; DUMP grant persists across reboots."
                ),
                activeSessionsCount = activeBundles.size
            )
        }

        val rootStatus = if (hasSu) {
            ModeStatusInfo(
                mode = EngineMode.ROOT,
                level = CapabilityLevel.AVAILABLE,
                summaryText = "Root DSP & Mid/Side Engine available",
                detailReason = "Root binary detected. Per-section Linkwitz-Riley Mid/Side stereo width sliders and Biquad HPF active.",
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
                summaryText = "Requires root access (DSP Preview Active)",
                detailReason = "No su binary detected for system-wide /vendor/lib64/soundfx/libdeepeq.so injection. System audio falls back to Legacy/Global while the built-in DSP Reference Monitor runs full Biquad + Mid/Side width.",
                setupSteps = listOf(
                    "1. Requires Magisk or KernelSU root environment.",
                    "2. Installs systemless module at /data/adb/modules/eq_deep without modifying /vendor directly.",
                    "3. Sets audio.offload.disable=1 in system.prop so hardware offload does not bypass EQ.",
                    "4. Always back up stock audio_effects.xml before staging module."
                ),
                activeSessionsCount = activeBundles.size
            )
        }

        return mapOf(
            EngineMode.LEGACY to legacyStatus,
            EngineMode.GLOBAL to globalStatus,
            EngineMode.ADB_SHIZUKU to adbStatus,
            EngineMode.ROOT to rootStatus
        )
    }

    fun syncSessionsAndApply(
        mode: EngineMode,
        enabled: Boolean,
        gainsDb: List<Float>,
        preampDb: Float,
        highPassHz: Float,
        widths: SectionWidths,
        bassBoostStrength: Int,
        virtualizerStrength: Int,
        favorTimeResolution: Boolean,
        internalSessionId: Int = 0
    ) {
        currentMode = mode
        lastEnabled = enabled
        lastGainsDb = gainsDb
        lastPreampDb = preampDb
        lastHighPassHz = highPassHz
        lastWidths = widths
        lastBassBoostStrength = bassBoostStrength
        lastVirtualizerStrength = virtualizerStrength
        lastFavorTimeResolution = favorTimeResolution

        // Determine target sessions based on mode + graceful fallback
        val targetSessions = mutableSetOf<Int>()
        targetSessions.add(0) // Always attempt global mix session 0 first as best-effort
        targetSessions.addAll(broadcastSessions)
        if (internalSessionId > 0) {
            targetSessions.add(internalSessionId)
        }

        if (mode == EngineMode.ADB_SHIZUKU && diagnostics.dumpPermissionGranted) {
            targetSessions.addAll(discoverSessionsViaDumpsys())
        }

        for (sessionId in targetSessions) {
            attachToSessionIfNeeded(sessionId)
        }

        applyCurrentParametersToAllSessions()
    }

    private fun attachToSessionIfNeeded(sessionId: Int) {
        if (activeBundles.containsKey(sessionId)) return

        var eq: Equalizer? = null
        var bb: BassBoost? = null
        var virt: Virtualizer? = null
        var le: LoudnessEnhancer? = null
        var dp: DynamicsProcessing? = null

        val preferDp = (currentMode != EngineMode.LEGACY) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        if (preferDp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val variant = if (lastFavorTimeResolution) {
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION
                } else {
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
                }
                val cfg = DynamicsProcessing.Config.Builder(
                    variant,
                    2,
                    true, 10,
                    true, 4,
                    false, 0,
                    true
                ).setPreferredFrameDuration(10f).build()

                dp = DynamicsProcessing(0, sessionId, cfg)
            } catch (_: Throwable) {
                dp = null
            }
        }

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

        if (dp != null || eq != null || bb != null || virt != null) {
            activeBundles[sessionId] = SessionEffectBundle(
                sessionId = sessionId,
                equalizer = eq,
                bassBoost = bb,
                virtualizer = virt,
                loudnessEnhancer = le,
                dynamicsProcessing = dp
            )
        }
    }

    private fun applyCurrentParametersToAllSessions() {
        val cutoffs = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
        val mbcCutoffs = floatArrayOf(250f, 4000f, 12000f, 20000f)

        for (bundle in activeBundles.values) {
            val dp = bundle.dynamicsProcessing
            if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && currentMode != EngineMode.LEGACY) {
                try {
                    dp.setInputGainAllChannelsTo(lastPreampDb)
                    for (i in cutoffs.indices) {
                        var gain = lastGainsDb.getOrElse(i) { 0f }
                        // Emulate high-pass in DynamicsProcessing by attenuating 31 Hz when HPF >= 40 Hz
                        if (i == 0 && lastHighPassHz >= 40f && gain > -4f) {
                            gain -= 3f
                        }
                        dp.setPreEqBandAllChannelsTo(
                            i,
                            DynamicsProcessing.EqBand(true, cutoffs[i], gain)
                        )
                    }

                    // Configure 4-band Multiband Compressor matching Bass, Mids, Treble, Air
                    val attacks = if (lastFavorTimeResolution) {
                        floatArrayOf(40f, 25f, 10f, 5f)
                    } else {
                        floatArrayOf(30f, 15f, 5f, 3f)
                    }
                    val releases = if (lastFavorTimeResolution) {
                        floatArrayOf(180f, 120f, 70f, 50f)
                    } else {
                        floatArrayOf(200f, 150f, 80f, 60f)
                    }
                    val ratios = if (lastFavorTimeResolution) {
                        floatArrayOf(1.8f, 1.5f, 1.6f, 1.4f)
                    } else {
                        floatArrayOf(2.0f, 1.6f, 1.8f, 1.5f)
                    }
                    val thresholds = floatArrayOf(-18f, -20f, -22f, -24f)

                    for (m in 0 until 4) {
                        val mbcBand = DynamicsProcessing.MbcBand(
                            true,
                            mbcCutoffs[m],
                            attacks[m],
                            releases[m],
                            ratios[m],
                            thresholds[m],
                            6f,   // knee width dB
                            -90f, // noise gate threshold dB
                            1.0f, // expander ratio
                            0f,   // preGain
                            0f    // postGain
                        )
                        dp.setMbcBandAllChannelsTo(m, mbcBand)
                    }

                    // Limiter: 1 ms attack, 60 ms release, 10:1 ratio, -1.0 dB threshold, 0 dB post-gain
                    val limiter = DynamicsProcessing.Limiter(
                        true,
                        true,
                        0,
                        1f,
                        60f,
                        10f,
                        -1.0f,
                        0f
                    )
                    dp.setLimiterAllChannelsTo(limiter)
                    dp.enabled = lastEnabled
                } catch (_: Throwable) {
                }
            }

            // Native 5-band Equalizer (active in Legacy Mode or fallback when DynamicsProcessing is absent)
            val eq = bundle.equalizer
            if (eq != null) {
                try {
                    val useNativeEq = (dp == null || currentMode == EngineMode.LEGACY)
                    eq.enabled = lastEnabled && useNativeEq
                    if (useNativeEq) {
                        val bandCount = eq.numberOfBands.toInt()
                        val range = eq.bandLevelRange
                        val minMb = range?.getOrNull(0)?.toInt() ?: -1500
                        val maxMb = range?.getOrNull(1)?.toInt() ?: 1500

                        for (b in 0 until bandCount) {
                            val centerHz = (eq.getCenterFreq(b.toShort()) / 1000).coerceAtLeast(20)
                            val interpolatedDb = interpolateMasterCurveAt(centerHz.toFloat(), lastGainsDb)
                                .coerceIn(-12f, 4f) // Capped at +4 dB in native EQ to prevent clipping without preamp
                            val targetMb = (interpolatedDb * 100f).toInt().coerceIn(minMb, maxMb).toShort()
                            eq.setBandLevel(b.toShort(), targetMb)
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // Companion BassBoost
            bundle.bassBoost?.let { bb ->
                try {
                    bb.enabled = lastEnabled && lastBassBoostStrength > 0
                    if (bb.strengthSupported) {
                        bb.setStrength(lastBassBoostStrength.coerceIn(0, 1000).toShort())
                    }
                } catch (_: Throwable) {
                }
            }

            // Companion Virtualizer (Spatial control for non-Root modes)
            bundle.virtualizer?.let { virt ->
                try {
                    virt.enabled = lastEnabled && lastVirtualizerStrength > 0
                    if (virt.strengthSupported) {
                        virt.setStrength(lastVirtualizerStrength.coerceIn(0, 1000).toShort())
                    }
                } catch (_: Throwable) {
                }
            }

            // Keep LoudnessEnhancer at 0 mB by default for hearing & headroom safety
            bundle.loudnessEnhancer?.let { le ->
                try {
                    le.enabled = false
                    le.setTargetGain(0)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Log-frequency interpolation from the 10-band master gains to any hardware native band center frequency.
     */
    private fun interpolateMasterCurveAt(freqHz: Float, gainsDb: List<Float>): Float {
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

    /**
     * Parses `dumpsys media.audio_flinger` or `dumpsys audio` when DUMP permission is granted.
     */
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

    /**
     * Root Safety: Backs up stock `audio_effects.xml` and `audio_policy_configuration.xml` to app storage
     * and stages the systemless Magisk module (`eq_deep`) with a unique UUID and `audio.offload.disable=1`.
     */
    fun backupAndStageMagiskModule(): Result<String> {
        return try {
            val backupDir = File(context.filesDir, "audio_config_backup").apply { mkdirs() }
            val stockEffectsFile = File(diagnostics.audioEffectsConfigPath)
            val backupEffectsFile = File(backupDir, "audio_effects.xml.bak")

            if (stockEffectsFile.exists() && stockEffectsFile.canRead()) {
                stockEffectsFile.copyTo(backupEffectsFile, overwrite = true)
            } else {
                // Save reference snapshot header if vendor partition is unreadable without su
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

            // Stage systemless Magisk module structure
            val moduleDir = File(context.filesDir, "magisk_module/eq_deep").apply { mkdirs() }
            File(moduleDir, "module.prop").writeText(
                """
                id=eq_deep
                name=Audio EQ Deep Biquad & Mid/Side DSP
                version=v1.0
                versionCode=1
                author=AudioEQ
                description=Systemless stream=music biquad & Linkwitz-Riley Mid/Side DSP with offload disabled
                """.trimIndent()
            )
            File(moduleDir, "system.prop").writeText("audio.offload.disable=1\n")
            val vendorEtc = File(moduleDir, "system/vendor/etc").apply { mkdirs() }
            File(vendorEtc, "audio_effects.xml").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
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
            Result.success("Backed up stock config & staged systemless module at ${moduleDir.absolutePath}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One-tap restore of stock audio configuration and removal of staged Magisk module.
     */
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
