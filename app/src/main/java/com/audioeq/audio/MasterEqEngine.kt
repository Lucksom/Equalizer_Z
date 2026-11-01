package com.audioeq.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.audioeq.model.EngineMode
import com.audioeq.model.HeadphoneProfile
import com.audioeq.model.OperatingMode
import com.audioeq.model.OutputRoute
import com.audioeq.model.PersonalHearingCurve
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

data class EngineRenderParams(
    val masterEnabled: Boolean = true,
    val bandGains10L: DoubleArray = DoubleArray(10),
    val bandGains10R: DoubleArray = DoubleArray(10),
    val splitLREnabled: Boolean = false,
    val balanceDb: Double = 0.0,
    val preampDb: Double = 0.0,
    val autoGainEnabled: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Double = -0.3,
    val highPassHz: Double = 0.0,
    val iso226Enabled: Boolean = false,
    val headphoneProfile: HeadphoneProfile? = null,
    val headphoneStrength: Double = 0.8,
    val headphoneFullRange: Boolean = false,
    val tiltBassDb: Double = 0.0,
    val tiltTrebleDb: Double = 0.0,
    val speakerCalEnabled: Boolean = false,
    val speakerCalStrength: Double = 0.7,
    val speakerCorrection31Db: DoubleArray = DoubleArray(31),
    val hearingCurve: PersonalHearingCurve = PersonalHearingCurve(),
    val enhancerConfig: EnhancerConfig = EnhancerConfig(),
    val midSideWidth: Float = 1.0f,
    val vocalPresenceBoost: Float = 0.0f,
    val compatibility10Band: Boolean = false,
    val transientMode: Boolean = false,
    val usbBitPerfectEnabled: Boolean = false,
    val abxBypassCurve: Boolean = false
)

class MasterEqEngine(
    private val context: Context,
    private val onRouteOrDeviceChanged: (OutputRoute, String, String?) -> Unit,
    private val onSessionsChanged: (List<Int>, OperatingMode) -> Unit
) {

    companion object {
        val EFFECT_TYPE_HARDWARE_DSP: UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
        val EFFECT_UUID_IMPL_SW: UUID = UUID.fromString("f22224d7-a540-4161-9c9a-e53a43681a9c")
        const val EFFECT_PARAM_CP_VALUES: Int = 5
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val dspThread = HandlerThread("EqDspWorker").apply { start() }
    private val dspHandler = Handler(dspThread.looper)

    private val dirty = AtomicBoolean(false)
    private val latestParams = AtomicReference(EngineRenderParams())
    private val requestedEngineMode = AtomicReference(EngineMode.LEGACY)

    private val dpSessions = ConcurrentHashMap<Int, DynamicsProcessing>()
    private val eqSessions = ConcurrentHashMap<Int, Equalizer>()
    private val loudnessSessions = ConcurrentHashMap<Int, LoudnessEnhancer>()
    private val virtualizerSessions = ConcurrentHashMap<Int, Virtualizer>()
    private val bassBoostSessions = ConcurrentHashMap<Int, BassBoost>()
    private val hardwareDaxSessions = ConcurrentHashMap<Int, AudioEffect>()
    private val lastAppliedL = ConcurrentHashMap<Int, FloatArray>()
    private val lastAppliedR = ConcurrentHashMap<Int, FloatArray>()
    private val lastAppliedPostL = ConcurrentHashMap<Int, FloatArray>()
    private val lastAppliedPostR = ConcurrentHashMap<Int, FloatArray>()
    private val lastAppliedPreamp = ConcurrentHashMap<Int, Float>()
    private val knownSessions = ConcurrentHashMap.newKeySet<Int>()

    private val consecutiveErrors = AtomicInteger(0)
    @Volatile
    var avgBinderWriteCostMs: Double = 0.35
        private set

    @Volatile
    var activeOperatingMode: OperatingMode = OperatingMode.LEGACY_TIER2_DP
        private set

    @Volatile
    var currentBandCount: Int = 31
        private set

    @Volatile
    var usbBitPerfectActive: Boolean = false
        private set

    @Volatile
    var hardwareDaxSupported: Boolean = false
        private set

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!dirty.getAndSet(false)) return
            val t0 = SystemClock.elapsedRealtimeNanos()
            try {
                applyParamsOnWorker(latestParams.get())
                consecutiveErrors.set(0)
            } catch (_: Throwable) {
                val errCount = consecutiveErrors.incrementAndGet()
                if (errCount >= 3) {
                    rebuildAllSessionsInternal()
                    consecutiveErrors.set(0)
                }
            }
            val dtMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            avgBinderWriteCostMs = 0.8 * avgBinderWriteCostMs + 0.2 * dtMs
            if (dirty.get()) {
                dspHandler.postDelayed(this, 16L)
            }
        }
    }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (sid <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    knownSessions.add(sid)
                    dspHandler.post {
                        attachSessionInternal(sid)
                        applyParamsOnWorker(latestParams.get())
                        notifySessionsUpdated()
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    knownSessions.remove(sid)
                    dspHandler.post {
                        detachSessionInternal(sid)
                        notifySessionsUpdated()
                    }
                }
            }
        }
    }

    private val volumeAndBtReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    if (latestParams.get().iso226Enabled) {
                        scheduleCoalescedUpdate()
                    }
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    dspHandler.post {
                        applyTransientMuteRamp()
                    }
                }
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            inspectOutputDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            inspectOutputDevices()
        }
    }

    init {
        hardwareDaxSupported = detectHardwareDaxEffect()
        registerReceiversAndCallbacks()
        knownSessions.add(0)
        dspHandler.post {
            attachSessionInternal(0)
            inspectOutputDevices()
            applyParamsOnWorker(latestParams.get())
            notifySessionsUpdated()
        }
    }

    private fun detectHardwareDaxEffect(): Boolean {
        return try {
            val descriptors = AudioEffect.queryEffects() ?: return false
            descriptors.any { desc ->
                desc.type == EFFECT_TYPE_HARDWARE_DSP ||
                    desc.uuid == EFFECT_UUID_IMPL_SW
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun registerReceiversAndCallbacks() {
        try {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            ContextCompat.registerReceiver(
                context,
                sessionReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }

        try {
            val vFilter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }
            ContextCompat.registerReceiver(
                context,
                volumeAndBtReceiver,
                vFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }

        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, dspHandler)
        } catch (_: Exception) {
        }
    }

    fun inspectOutputDevices() {
        try {
            val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val types = outs.map { it.type }
            val route = OutputRoute.fromDeviceTypes(types)
            val activeDev = outs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            } ?: outs.firstOrNull()

            val prodName = activeDev?.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    activeDev?.address?.takeIf { it.isNotBlank() }
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }

            val deviceKey = when (route) {
                OutputRoute.BLUETOOTH -> "bt:${addr ?: prodName ?: "default"}"
                OutputRoute.USB_DAC -> "usb:${prodName ?: "dac"}"
                OutputRoute.HEADPHONES -> "wired:3.5mm"
                OutputRoute.SPEAKER -> "speaker:builtin"
            }

            updateUsbBitPerfectRouting(route, activeDev, latestParams.get().usbBitPerfectEnabled)
            onRouteOrDeviceChanged(route, deviceKey, prodName)
        } catch (_: Exception) {
            onRouteOrDeviceChanged(OutputRoute.SPEAKER, "speaker:builtin", null)
        }
    }

    private fun updateUsbBitPerfectRouting(
        route: OutputRoute,
        usbDevice: AudioDeviceInfo?,
        wantBitPerfect: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            route == OutputRoute.USB_DAC &&
            usbDevice != null
        ) {
            try {
                val attr = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                if (wantBitPerfect) {
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    audioManager.setPreferredMixerAttributes(
                        attr,
                        usbDevice,
                        android.media.AudioMixerAttributes.Builder(format)
                            .setMixerBehavior(android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                            .build()
                    )
                    usbBitPerfectActive = true
                } else {
                    audioManager.clearPreferredMixerAttributes(attr, usbDevice)
                    usbBitPerfectActive = false
                }
            } catch (_: Throwable) {
                usbBitPerfectActive = false
            }
        } else {
            usbBitPerfectActive = false
        }
    }

    fun isUsbBitPerfectSupportedOnDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usb = outs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            } ?: return false
            val attr = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build()
            audioManager.getSupportedMixerAttributes(usb).isNotEmpty() || attr.usage == android.media.AudioAttributes.USAGE_MEDIA
        } catch (_: Throwable) {
            false
        }
    }

    fun setEngineMode(
        mode: EngineMode,
        privilegeReady: Boolean
    ) {
        requestedEngineMode.set(mode)
        dspHandler.post {
            activeOperatingMode = when (mode) {
                EngineMode.ROOT -> if (privilegeReady) OperatingMode.ROOT_GLOBAL else OperatingMode.SUSPENDED_PRIVILEGED
                EngineMode.ADB_SHIZUKU -> if (privilegeReady) OperatingMode.SHIZUKU_GLOBAL else OperatingMode.SUSPENDED_PRIVILEGED
                EngineMode.LEGACY -> {
                    if (dpSessions.isNotEmpty() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        OperatingMode.LEGACY_TIER2_DP
                    } else {
                        OperatingMode.LEGACY_TIER1_EQ
                    }
                }
            }
            applyParamsOnWorker(latestParams.get())
            notifySessionsUpdated()
        }
    }

    fun syncDiscoveredSessions(discoveredSids: Collection<Int>) {
        dspHandler.post {
            val all = HashSet<Int>(discoveredSids)
            all.add(0)
            for (sid in all) {
                if (knownSessions.add(sid)) {
                    attachSessionInternal(sid)
                }
            }
            val toRemove = knownSessions.filter { it != 0 && it !in all && !dpSessions.containsKey(it) }
            for (sid in toRemove) {
                knownSessions.remove(sid)
                detachSessionInternal(sid)
            }
            applyParamsOnWorker(latestParams.get())
            notifySessionsUpdated()
        }
    }

    fun attachSession(sid: Int) {
        if (sid <= 0) return
        dspHandler.post {
            if (knownSessions.add(sid)) {
                attachSessionInternal(sid)
                applyParamsOnWorker(latestParams.get())
                notifySessionsUpdated()
            }
        }
    }

    fun updateParams(params: EngineRenderParams) {
        val prev = latestParams.getAndSet(params)
        if (prev.compatibility10Band != params.compatibility10Band ||
            prev.transientMode != params.transientMode
        ) {
            dspHandler.post {
                rebuildAllSessionsInternal()
                applyParamsOnWorker(params)
            }
            return
        }
        if (prev.usbBitPerfectEnabled != params.usbBitPerfectEnabled) {
            dspHandler.post { inspectOutputDevices() }
        }
        scheduleCoalescedUpdate()
    }

    private fun scheduleCoalescedUpdate() {
        dirty.set(true)
        if (!dspHandler.hasCallbacks(tickRunnable)) {
            dspHandler.postDelayed(tickRunnable, 16L)
        }
    }

    private fun attachSessionInternal(sid: Int) {
        if (dpSessions.containsKey(sid) || eqSessions.containsKey(sid)) return
        val params = latestParams.get()
        val bands = if (params.compatibility10Band) 10 else 31
        currentBandCount = bands

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cfgBuilder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    bands,
                    true,
                    6,
                    true,
                    bands,
                    true
                )
                cfgBuilder.setPreferredFrameDuration(if (params.transientMode) 5.0f else 10.0f)
                val cfg = cfgBuilder.build()
                val dp = DynamicsProcessing(0, sid, cfg)
                dp.enabled = params.masterEnabled

                for (ch in 0..1) {
                    for (b in 0 until 6) {
                        val mbcBand = dp.getMbcBandByChannelIndex(ch, b)
                        mbcBand.isEnabled = true
                        mbcBand.cutoffFrequency = Dsp.MBC_CUTOFFS[b]
                        mbcBand.attackTime = Dsp.MBC_ATTACK_MS[b]
                        mbcBand.releaseTime = Dsp.MBC_RELEASE_MS[b]
                        mbcBand.ratio = 1.2f
                        mbcBand.threshold = -2.0f
                        mbcBand.kneeWidth = 4.0f
                        mbcBand.noiseGateThreshold = -90.0f
                        mbcBand.expanderRatio = 1.0f
                        mbcBand.preGain = 0.0f
                        mbcBand.postGain = 0.0f
                        dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                    }
                }

                dpSessions[sid] = dp
                lastAppliedL[sid] = FloatArray(bands) { Float.NaN }
                lastAppliedR[sid] = FloatArray(bands) { Float.NaN }
                lastAppliedPostL[sid] = FloatArray(bands) { Float.NaN }
                lastAppliedPostR[sid] = FloatArray(bands) { Float.NaN }

                tryAttachHardwareDaxSession(sid)

                if (requestedEngineMode.get() == EngineMode.LEGACY) {
                    activeOperatingMode = OperatingMode.LEGACY_TIER2_DP
                }
                return
            } catch (_: Throwable) {
            }
        }

        if (sid > 0) {
            try {
                val eq = Equalizer(0, sid)
                eq.enabled = params.masterEnabled
                eqSessions[sid] = eq
                val le = LoudnessEnhancer(sid)
                le.enabled = params.masterEnabled
                loudnessSessions[sid] = le
                try {
                    val virt = Virtualizer(0, sid)
                    virtualizerSessions[sid] = virt
                } catch (_: Throwable) {
                }
                try {
                    val bb = BassBoost(0, sid)
                    bassBoostSessions[sid] = bb
                } catch (_: Throwable) {
                }
                tryAttachHardwareDaxSession(sid)
                if (requestedEngineMode.get() == EngineMode.LEGACY && dpSessions.isEmpty()) {
                    activeOperatingMode = OperatingMode.LEGACY_TIER1_EQ
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryAttachHardwareDaxSession(sid: Int) {
        if (!hardwareDaxSupported) return
        try {
            val ctor = AudioEffect::class.java.getConstructor(
                UUID::class.java,
                UUID::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val fx = ctor.newInstance(EFFECT_TYPE_HARDWARE_DSP, EFFECT_UUID_IMPL_SW, 0, sid)
            hardwareDaxSessions[sid] = fx
        } catch (_: Throwable) {
        }
    }

    private fun detachSessionInternal(sid: Int) {
        dpSessions.remove(sid)?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        eqSessions.remove(sid)?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        loudnessSessions.remove(sid)?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        virtualizerSessions.remove(sid)?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        bassBoostSessions.remove(sid)?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        hardwareDaxSessions.remove(sid)?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        lastAppliedL.remove(sid)
        lastAppliedR.remove(sid)
        lastAppliedPostL.remove(sid)
        lastAppliedPostR.remove(sid)
        lastAppliedPreamp.remove(sid)
    }

    private fun rebuildAllSessionsInternal() {
        val currentSids = ArrayList(knownSessions)
        for (sid in currentSids) {
            detachSessionInternal(sid)
        }
        for (sid in currentSids) {
            attachSessionInternal(sid)
        }
    }

    private fun applyTransientMuteRamp() {
        for ((_, dp) in dpSessions) {
            try {
                dp.setInputGainAllChannelsTo(-24.0f)
            } catch (_: Throwable) {
            }
        }
        dspHandler.postDelayed({
            scheduleCoalescedUpdate()
        }, 120L)
    }

    private fun applyParamsOnWorker(params: EngineRenderParams) {
        val isSuspended = activeOperatingMode == OperatingMode.SUSPENDED_PRIVILEGED || usbBitPerfectActive
        val effectiveEnabled = params.masterEnabled && !isSuspended && !params.abxBypassCurve

        val volIdx = try {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            10
        }
        val volMax = try {
            max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        } catch (_: Exception) {
            15
        }

        val calCurve = DoubleArray(31)
        val hpCurve = Dsp.computeHeadphoneCorrection31(
            profile = params.headphoneProfile,
            strength = params.headphoneStrength,
            fullRange = params.headphoneFullRange,
            tiltBassDb = params.tiltBassDb,
            tiltTrebleDb = params.tiltTrebleDb
        )
        for (i in 0 until 31) {
            calCurve[i] += hpCurve[i]
        }
        if (params.speakerCalEnabled && params.speakerCorrection31Db.size == 31) {
            for (i in 0 until 31) {
                calCurve[i] += params.speakerCorrection31Db[i] * params.speakerCalStrength.coerceIn(0.0, 1.0)
            }
        }

        val enhancerCurve31 = EnhancerDsp.computeCombinedEnhancer31(params.enhancerConfig)
        for (i in 0 until 31) {
            calCurve[i] = (calCurve[i] + enhancerCurve31[i]).coerceIn(-15.0, 15.0)
        }

        val (stereoDeltaL31, stereoDeltaR31) = EnhancerDsp.computeStereoWideningLRDelta31(
            config = params.enhancerConfig,
            midSideWidth = params.midSideWidth
        )

        val hearingL31 = interpolateHearingTo31(params.hearingCurve, isRight = false)
        val hearingR31 = interpolateHearingTo31(params.hearingCurve, isRight = true)

        val calL = DoubleArray(31) {
            (calCurve[it] + hearingL31[it]).coerceIn(-15.0, 15.0)
        }
        val calR = DoubleArray(31) {
            (calCurve[it] + hearingR31[it]).coerceIn(-15.0, 15.0)
        }

        val targetL31 = Dsp.compute31BandTargets(
            userGains10 = params.bandGains10L,
            cal31 = calL,
            hpFc = params.highPassHz,
            iso226On = params.iso226Enabled,
            volIdx = volIdx,
            volMax = volMax
        )
        val rawRight10 = if (params.splitLREnabled) params.bandGains10R else params.bandGains10L
        val targetR31 = Dsp.compute31BandTargets(
            userGains10 = rawRight10,
            cal31 = calR,
            hpFc = params.highPassHz,
            iso226On = params.iso226Enabled,
            volIdx = volIdx,
            volMax = volMax
        )

        val bal = params.balanceDb.coerceIn(-6.0, 6.0)
        val trimL = if (bal > 0.0) -bal else 0.0
        val trimR = if (bal < 0.0) bal else 0.0

        val is10BandMode = params.compatibility10Band
        val bandCutoffs = if (is10BandMode) Dsp.FALLBACK_10_CUT else Dsp.RENDER_CUT
        val bandCount = bandCutoffs.size

        val effL = FloatArray(bandCount) { k ->
            if (!effectiveEnabled) 0.0f
            else if (is10BandMode) {
                val nearest31 = Dsp.F_DP.indices.minByOrNull { abs(ln(Dsp.F_DP[it]) - ln(Dsp.F_USER[k])) } ?: 0
                targetL31[nearest31].toFloat()
            } else {
                targetL31[k].toFloat()
            }
        }
        val effR = FloatArray(bandCount) { k ->
            if (!effectiveEnabled) 0.0f
            else if (is10BandMode) {
                val nearest31 = Dsp.F_DP.indices.minByOrNull { abs(ln(Dsp.F_DP[it]) - ln(Dsp.F_USER[k])) } ?: 0
                targetR31[nearest31].toFloat()
            } else {
                targetR31[k].toFloat()
            }
        }

        val postL = FloatArray(bandCount) { k ->
            if (!effectiveEnabled) 0.0f
            else if (is10BandMode) {
                val nearest31 = Dsp.F_DP.indices.minByOrNull { abs(ln(Dsp.F_DP[it]) - ln(Dsp.F_USER[k])) } ?: 0
                stereoDeltaL31[nearest31].toFloat()
            } else {
                stereoDeltaL31[k].toFloat()
            }
        }
        val postR = FloatArray(bandCount) { k ->
            if (!effectiveEnabled) 0.0f
            else if (is10BandMode) {
                val nearest31 = Dsp.F_DP.indices.minByOrNull { abs(ln(Dsp.F_DP[it]) - ln(Dsp.F_USER[k])) } ?: 0
                stereoDeltaR31[nearest31].toFloat()
            } else {
                stereoDeltaR31[k].toFloat()
            }
        }

        val leftInputGain = if (effectiveEnabled) trimL.toFloat() else 0.0f
        val rightInputGain = if (effectiveEnabled) trimR.toFloat() else 0.0f
        val anyEnhancerActive = params.enhancerConfig.isAnyActive()
        val makeupPostGain = if (effectiveEnabled && anyEnhancerActive) 0.5f else 0.0f

        for ((sid, dp) in dpSessions) {
            try {
                if (dp.enabled != effectiveEnabled) {
                    dp.enabled = effectiveEnabled
                }
                val prevPre = lastAppliedPreamp[sid]
                val balKey = leftInputGain - rightInputGain
                if (prevPre == null || abs(prevPre - balKey) >= 0.02f) {
                    dp.setInputGainbyChannel(0, leftInputGain)
                    dp.setInputGainbyChannel(1, rightInputGain)
                    lastAppliedPreamp[sid] = balKey
                }

                val prevL = lastAppliedL.getOrPut(sid) { FloatArray(bandCount) { Float.NaN } }
                val prevR = lastAppliedR.getOrPut(sid) { FloatArray(bandCount) { Float.NaN } }
                val prevPostL = lastAppliedPostL.getOrPut(sid) { FloatArray(bandCount) { Float.NaN } }
                val prevPostR = lastAppliedPostR.getOrPut(sid) { FloatArray(bandCount) { Float.NaN } }

                for (k in 0 until bandCount) {
                    if (prevL[k].isNaN() || abs(prevL[k] - effL[k]) >= 0.05f) {
                        val band = DynamicsProcessing.EqBand(true, bandCutoffs[k], effL[k])
                        dp.setPreEqBandByChannelIndex(0, k, band)
                        prevL[k] = effL[k]
                    }
                    if (prevR[k].isNaN() || abs(prevR[k] - effR[k]) >= 0.05f) {
                        val band = DynamicsProcessing.EqBand(true, bandCutoffs[k], effR[k])
                        dp.setPreEqBandByChannelIndex(1, k, band)
                        prevR[k] = effR[k]
                    }
                    if (prevPostL[k].isNaN() || abs(prevPostL[k] - postL[k]) >= 0.05f) {
                        val band = DynamicsProcessing.EqBand(true, bandCutoffs[k], postL[k])
                        dp.setPostEqBandByChannelIndex(0, k, band)
                        prevPostL[k] = postL[k]
                    }
                    if (prevPostR[k].isNaN() || abs(prevPostR[k] - postR[k]) >= 0.05f) {
                        val band = DynamicsProcessing.EqBand(true, bandCutoffs[k], postR[k])
                        dp.setPostEqBandByChannelIndex(1, k, band)
                        prevPostR[k] = postR[k]
                    }
                }

                for (ch in 0..1) {
                    for (b in 0 until 6) {
                        val mbcBand = dp.getMbcBandByChannelIndex(ch, b)
                        mbcBand.isEnabled = effectiveEnabled
                        mbcBand.cutoffFrequency = Dsp.MBC_CUTOFFS[b]
                        mbcBand.attackTime = Dsp.MBC_ATTACK_MS[b]
                        mbcBand.releaseTime = Dsp.MBC_RELEASE_MS[b]
                        mbcBand.ratio = 1.2f
                        mbcBand.threshold = -2.0f
                        mbcBand.kneeWidth = 4.0f
                        mbcBand.noiseGateThreshold = -90.0f
                        mbcBand.expanderRatio = 1.0f
                        mbcBand.preGain = 0.0f
                        mbcBand.postGain = makeupPostGain
                        dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                    }
                }

                val lim = DynamicsProcessing.Limiter(
                    true,
                    params.limiterEnabled && effectiveEnabled,
                    0,
                    Dsp.LIM_ATTACK_MS,
                    Dsp.LIM_RELEASE_MS,
                    Dsp.LIM_RATIO,
                    params.limiterCeilingDb.toFloat().coerceIn(-3.0f, -0.1f),
                    0.0f
                )
                dp.setLimiterAllChannelsTo(lim)
            } catch (_: Throwable) {
            }
        }

        val wideningPermille = when (params.enhancerConfig.stereoWideningMode) {
            StereoWideningMode.NO_EFFECT -> 0
            StereoWideningMode.NEAR -> 450
            StereoWideningMode.MIDDLE -> 750
            StereoWideningMode.FAR -> 1000
        }
        val virtStrength = max(
            wideningPermille,
            (((params.midSideWidth - 1.0f).coerceIn(0f, 1f)) * 1000f).roundToInt()
        ).coerceIn(0, 1000)

        val isSpeakerRoute = try {
            detectCurrentRoute(audioManager) == OutputRoute.SPEAKER
        } catch (_: Throwable) {
            false
        }

        for ((_, virt) in virtualizerSessions) {
            try {
                val wantVirt = effectiveEnabled && virtStrength > 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && wantVirt) {
                    val vMode = if (isSpeakerRoute) {
                        Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL
                    } else {
                        Virtualizer.VIRTUALIZATION_MODE_BINAURAL
                    }
                    try {
                        virt.forceVirtualizationMode(vMode)
                    } catch (_: Throwable) {
                    }
                }
                if (virt.strengthSupported) {
                    virt.setStrength(if (wantVirt) virtStrength.toShort() else 0)
                } else {
                    virt.setStrength(if (wantVirt) 1000 else 0)
                }
                if (virt.enabled != wantVirt) {
                    virt.enabled = wantVirt
                }
            } catch (_: Throwable) {
            }
        }

        val bassStrengthPermille = if (params.enhancerConfig.bassEnhancerEnabled) {
            (params.enhancerConfig.bassEnhancerStrength.coerceIn(0, 100) * 10).coerceIn(0, 1000)
        } else {
            0
        }

        for ((_, bb) in bassBoostSessions) {
            try {
                val wantBb = effectiveEnabled && bassStrengthPermille > 0
                if (bb.enabled != wantBb) bb.enabled = wantBb
                if (wantBb && bb.strengthSupported) {
                    bb.setStrength(bassStrengthPermille.toShort())
                }
            } catch (_: Throwable) {
            }
        }

        if (hardwareDaxSessions.isNotEmpty()) {
            val ieqTarget20 = EnhancerDsp.ieqTarget20Db(
                params.enhancerConfig.ieqPreset,
                params.enhancerConfig.ieqCustom10Db
            )
            val gains20Q4 = IntArray(20) { idx ->
                if (!effectiveEnabled) 0
                else (ieqTarget20[idx] * 64.0).roundToInt().coerceIn(-960, 384)
            }
            val payload = EnhancerDsp.buildHardwareDax96BytePayload(
                gains20Q4 = gains20Q4,
                ieqPresetIndex = params.enhancerConfig.ieqPreset.index
            )
            for ((_, hwFx) in hardwareDaxSessions) {
                try {
                    hwFx.enabled = effectiveEnabled
                    val setParamMethod = AudioEffect::class.java.getMethod(
                        "setParameter",
                        Int::class.javaPrimitiveType,
                        ByteArray::class.java
                    )
                    setParamMethod.invoke(hwFx, EFFECT_PARAM_CP_VALUES, payload)
                } catch (_: Throwable) {
                }
            }
        }

        for ((sid, eq) in eqSessions) {
            try {
                eq.enabled = effectiveEnabled
                val nBands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()

                val bandFreqRanges = (0 until nBands).map { b ->
                    val r = eq.getBandFreqRange(b.toShort())
                    (r[0] / 1000.0) to (r[1] / 1000.0)
                }
                val mappedMb = AutoEq.mapCorrection31ToNativeBands(
                    correction31Db = targetL31,
                    nativeBandsHz = bandFreqRanges,
                    strength = 1.0
                )
                for (b in 0 until nBands) {
                    val mb = if (effectiveEnabled) mappedMb[b].coerceIn(minMb, maxMb) else 0
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }

                loudnessSessions[sid]?.let { le ->
                    le.enabled = effectiveEnabled
                    val targetGainMb = if (effectiveEnabled) (makeupPostGain * 100f).roundToInt() else 0
                    le.setTargetGain(targetGainMb)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun interpolateHearingTo31(curve: PersonalHearingCurve, isRight: Boolean): DoubleArray {
        if (!curve.enabled) return DoubleArray(31)
        val boosts = if (isRight) curve.rightEarBoostDb else curve.leftEarBoostDb
        if (boosts.size < 6 || boosts.all { abs(it) < 0.05f }) return DoubleArray(31)
        val pts = curve.frequenciesHz.indices.map { i ->
            curve.frequenciesHz[i].toDouble() to boosts[i].toDouble().coerceIn(-6.0, 12.0)
        }
        val xs = DoubleArray(pts.size) { ln(pts[it].first) }
        val ys = DoubleArray(pts.size) { pts[it].second }
        return DoubleArray(31) { k ->
            val x = ln(Dsp.F_DP[k])
            when {
                x <= xs.first() -> ys.first()
                x >= xs.last() -> ys.last()
                else -> {
                    var idx = xs.binarySearch(x)
                    if (idx < 0) idx = -idx - 2
                    val i = idx.coerceIn(0, xs.size - 2)
                    val t = (x - xs[i]) / (xs[i + 1] - xs[i])
                    ys[i] + t * (ys[i + 1] - ys[i])
                }
            }
        }
    }

    private fun notifySessionsUpdated() {
        val activeList = (dpSessions.keys + eqSessions.keys).distinct().sorted()
        onSessionsChanged(activeList, activeOperatingMode)
    }

    fun getActiveSessionIds(): List<Int> = (dpSessions.keys + eqSessions.keys).distinct().sorted()

    fun getActiveVirtualizerCount(): Int = virtualizerSessions.size

    fun getActiveHardwareDaxCount(): Int = hardwareDaxSessions.size

    fun release() {
        try {
            context.unregisterReceiver(sessionReceiver)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(volumeAndBtReceiver)
        } catch (_: Exception) {
        }
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        val sids = ArrayList(knownSessions)
        for (sid in sids) {
            detachSessionInternal(sid)
        }
        dspThread.quitSafely()
    }
}
