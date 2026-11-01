package com.audioeq.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audioeq.audio.AudioEngineController
import com.audioeq.audio.AudioProfile
import com.audioeq.audio.AutoEq
import com.audioeq.audio.AutoEqRepo
import com.audioeq.audio.DeviceDiagnostics
import com.audioeq.audio.Dsp
import com.audioeq.audio.DspReferenceMonitor
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.EnhancerDsp
import com.audioeq.audio.EnhancerMath
import com.audioeq.audio.EqualizerNotificationHelper
import com.audioeq.audio.IeqPreset
import com.audioeq.audio.IndexEntry
import com.audioeq.audio.MusicAppTracker
import com.audioeq.data.EqRepository
import com.audioeq.model.EngineMode
import com.audioeq.model.EqBand
import com.audioeq.model.EqConstants
import com.audioeq.model.EqPreset
import com.audioeq.model.HeadphoneProfile
import com.audioeq.model.OperatingMode
import com.audioeq.model.OutputRoute
import com.audioeq.model.PersonalHearingCurve
import com.audioeq.model.RouteMemoryProfile
import com.audioeq.shizuku.ShizukuManager
import com.audioeq.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class EqUiState(
    val engineMode: EngineMode = EngineMode.LEGACY,
    val operatingMode: OperatingMode = OperatingMode.LEGACY_TIER2_DP,
    val masterEnabled: Boolean = true,
    val activeRoute: OutputRoute = OutputRoute.SPEAKER,
    val activeDeviceKey: String = "speaker:builtin",
    val connectedProductName: String? = null,
    val currentPresetId: String = "flat",
    val activePresetName: String = "Flat",
    val presets: List<EqPreset> = emptyList(),
    val bands: List<EqBand> = emptyList(),
    val rightBands: List<EqBand> = emptyList(),
    val isEqEnabled: Boolean = true,
    val isAbCompare: Boolean = false,
    val editingRightChannel: Boolean = false,
    val intensity: Float = 1.0f,
    val ceilingDb: Float = 0.0f,
    val effectiveGainsDb: List<Float> = emptyList(),
    val splitLREnabled: Boolean = false,
    val balanceDb: Float = 0f,
    val preampDb: Float = 0f,
    val autoGainEnabled: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -0.3f,
    val highPassHz: Float = 0f,
    val iso226Enabled: Boolean = false,
    val midSideWidth: Float = 1.0f,
    val vocalPresenceBoost: Float = 0.0f,
    val headphoneProfileId: String = "none",
    val headphoneStrength: Float = 0.8f,
    val headphoneFullRange: Boolean = false,
    val headphoneTrimmedDb: Float = 0f,
    val tiltBassDb: Float = 0f,
    val tiltTrebleDb: Float = 0f,
    val headphoneProfiles: List<HeadphoneProfile> = emptyList(),
    val autoEqCatalog: List<IndexEntry> = emptyList(),
    val autoEqLoading: Boolean = false,
    val autoEqRateLimited: Boolean = false,
    val autoEqOffline: Boolean = false,
    val speakerCalEnabled: Boolean = false,
    val speakerCalStrength: Float = 0.7f,
    val speakerMeas31Db: List<Float> = emptyList(),
    val speakerCorrection31Db: List<Float> = emptyList(),
    val isCalibratingSpeaker: Boolean = false,
    val calibrationProgress: Float = 0f,
    val calibrationStatusText: String = "",
    val hearingCurve: PersonalHearingCurve = PersonalHearingCurve(),
    val enhancerConfig: EnhancerConfig = EnhancerConfig(),
    val liveSpectrum31: List<Float> = emptyList(),
    val gainReduction6: List<Float> = emptyList(),
    val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    val shizukuState: ShizukuState = ShizukuState.NOT_RUNNING,
    val shizukuPrivilege: String = "",
    val startOnBoot: Boolean = true,
    val compatibility10Band: Boolean = false,
    val transientMode: Boolean = false,
    val usbBitPerfectEnabled: Boolean = false,
    val musicOnlyTrackingEnabled: Boolean = true,
    val themeMode: String = "light",
    val seedHex: String = "#5B8CFF",
    val isSeedFromWallpaper: Boolean = false,
    val showSavePresetDialog: Boolean = false,
    val showRootWarningDialog: Boolean = false,
    val showCalibrationSheet: Boolean = false,
    val showAbxSheet: Boolean = false,
    val showSettingsSheet: Boolean = false,
    val abxScoreCorrect: Int = 0,
    val abxScoreTotal: Int = 0,
    val abxLastResult: Boolean? = null,
    val abxAuditionMode: String = "X",
    val abxSourceId: String = "pink_noise",
    val statusBannerMessage: String? = null
)

class EqViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EqRepository(application)
    val shizukuManager = ShizukuManager(application)
    private val autoEqRepo = AutoEqRepo(application)
    val dspMonitor = DspReferenceMonitor(viewModelScope)

    val engineController = AudioEngineController(
        context = application,
        shizukuManager = shizukuManager,
        scope = viewModelScope,
        onRouteChanged = { route, devKey, _ ->
            repository.switchOutputRoute(route, devKey)
        }
    )

    private val _autoEqCatalog = MutableStateFlow<List<IndexEntry>>(emptyList())
    private val _autoEqLoading = MutableStateFlow(false)
    private val _autoEqRateLimited = MutableStateFlow(false)
    private val _autoEqOffline = MutableStateFlow(false)

    private val _speakerMeas31 = MutableStateFlow<List<Float>>(emptyList())
    private val _speakerCorr31 = MutableStateFlow<List<Float>>(emptyList())
    private val _isCalibratingSpeaker = MutableStateFlow(false)
    private val _calibrationProgress = MutableStateFlow(0f)
    private val _calibrationStatus = MutableStateFlow("")

    private val _showSavePresetDialog = MutableStateFlow(false)
    private val _showRootWarningDialog = MutableStateFlow(false)
    private val _showCalibrationSheet = MutableStateFlow(false)
    private val _showAbxSheet = MutableStateFlow(false)
    private val _showSettingsSheet = MutableStateFlow(false)

    private val _abxScoreCorrect = MutableStateFlow(0)
    private val _abxScoreTotal = MutableStateFlow(0)
    private val _abxLastResult = MutableStateFlow<Boolean?>(null)
    private val _abxAuditionMode = MutableStateFlow("X")
    private val _abxSourceId = MutableStateFlow("pink_noise")
    private val _abxHiddenIsA = MutableStateFlow(true)
    private val _abxBypassCurve = MutableStateFlow(false)

    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _isEqEnabled = MutableStateFlow(true)
    private val _isAbCompare = MutableStateFlow(false)
    private val _editingRightChannel = MutableStateFlow(false)
    private val _intensity = MutableStateFlow(1.0f)

    private val flow1A = combine(
        repository.engineMode,
        repository.masterEnabled,
        repository.activeRoute,
        repository.activeDeviceKey
    ) { mode, enabled, route, devKey ->
        Intermediate1A(mode, enabled, route, devKey)
    }

    private val flow1B = combine(
        repository.presets,
        repository.currentRouteProfile,
        repository.hearingCurve,
        repository.enhancerConfig,
        repository.customHeadphoneProfiles
    ) { presets, profile, hearing, enhancer, customHp ->
        Intermediate1B(presets, profile, hearing, enhancer, customHp)
    }

    private val flow1C = combine(
        _isEqEnabled,
        _isAbCompare,
        _editingRightChannel,
        _intensity
    ) { eq, ab, er, int ->
        Intermediate1C(eq, ab, er, int)
    }

    private val flowP1 = combine(flow1A, flow1B, flow1C) { a, b, c ->
        val bands = EqConstants.TEN_BAND_FREQUENCIES_HZ.indices.map { i ->
            EqBand(
                index = i,
                frequencyHz = EqConstants.TEN_BAND_FREQUENCIES_HZ[i],
                label = EqConstants.TEN_BAND_LABELS[i],
                gainDb = b.profile.bandGainsDb.getOrElse(i) { 0f }
            )
        }
        val rightBands = EqConstants.TEN_BAND_FREQUENCIES_HZ.indices.map { i ->
            EqBand(
                index = i,
                frequencyHz = EqConstants.TEN_BAND_FREQUENCIES_HZ[i],
                label = EqConstants.TEN_BAND_LABELS[i],
                gainDb = b.profile.rightBandGainsDb.getOrElse(i) { 0f }
            )
        }
        PartialUiState1(
            engineMode = a.engineMode,
            masterEnabled = a.masterEnabled,
            activeRoute = a.activeRoute,
            activeDeviceKey = a.activeDeviceKey,
            presets = b.presets,
            profile = b.profile,
            bands = bands,
            rightBands = rightBands,
            hearingCurve = b.hearingCurve,
            enhancerConfig = b.enhancerConfig,
            isEqEnabled = c.isEqEnabled,
            isAbCompare = c.isAbCompare,
            editingRightChannel = c.editingRightChannel,
            intensity = c.intensity,
            customHeadphoneProfiles = b.customHeadphoneProfiles
        )
    }

    private val flowP2 = combine(
        engineController.diagnostics,
        engineController.liveSpectrum31,
        engineController.gainReduction6,
        shizukuManager.state,
        shizukuManager.privilegeSummary
    ) { diag, spec, gr, sState, sPriv ->
        PartialUiState2(
            diagnostics = diag,
            liveSpectrum31 = spec,
            gainReduction6 = gr,
            shizukuState = sState,
            shizukuPrivilege = sPriv
        )
    }

    private val flow3A = combine(
        repository.startOnBoot,
        repository.compatibility10Band,
        repository.transientMode,
        repository.usbBitPerfectEnabled
    ) { boot, compat10, trans, usb ->
        Intermediate3A(boot, compat10, trans, usb)
    }

    private val flow3B = combine(
        repository.themeMode,
        repository.seedHex,
        repository.wallpaperExtractActive,
        repository.musicOnlyTracking
    ) { theme, seed, wall, musicOnly ->
        Intermediate3B(theme, seed, wall, musicOnly)
    }

    private val flowP3 = combine(flow3A, flow3B) { a, b ->
        PartialUiState3(
            startOnBoot = a.startOnBoot,
            compatibility10Band = a.compatibility10Band,
            transientMode = a.transientMode,
            usbBitPerfectEnabled = a.usbBitPerfectEnabled,
            musicOnlyTrackingEnabled = b.musicOnlyTrackingEnabled,
            themeMode = b.themeMode,
            seedHex = b.seedHex,
            isSeedFromWallpaper = b.isSeedFromWallpaper
        )
    }

    private val flow4A = combine(
        _autoEqCatalog,
        _autoEqLoading,
        _autoEqRateLimited,
        _autoEqOffline
    ) { cat, loading, rateLim, offline ->
        Intermediate4A(cat, loading, rateLim, offline)
    }

    private val flow4B = combine(
        _speakerMeas31,
        _speakerCorr31,
        _isCalibratingSpeaker,
        _calibrationProgress
    ) { meas, corr, isCal, prog ->
        Intermediate4B(meas, corr, isCal, prog)
    }

    private val flowP4 = combine(flow4A, flow4B) { a, b ->
        PartialUiState4(
            autoEqCatalog = a.autoEqCatalog,
            autoEqLoading = a.autoEqLoading,
            autoEqRateLimited = a.autoEqRateLimited,
            autoEqOffline = a.autoEqOffline,
            speakerMeas31Db = b.speakerMeas31Db,
            speakerCorrection31Db = b.speakerCorrection31Db,
            isCalibratingSpeaker = b.isCalibratingSpeaker,
            calibrationProgress = b.calibrationProgress
        )
    }

    private val flow5A = combine(
        _calibrationStatus,
        _showSavePresetDialog,
        _showRootWarningDialog
    ) { calStat, saveD, rootD ->
        Intermediate5A(calStat, saveD, rootD)
    }

    private val flow5B = combine(
        _showCalibrationSheet,
        _showAbxSheet,
        _showSettingsSheet,
        _statusBanner
    ) { calS, abxS, setS, banner ->
        Intermediate5B(calS, abxS, setS, banner)
    }

    private val flowP5 = combine(flow5A, flow5B) { a, b ->
        PartialUiState5(
            calibrationStatusText = a.calibrationStatusText,
            showSavePresetDialog = a.showSavePresetDialog,
            showRootWarningDialog = a.showRootWarningDialog,
            showCalibrationSheet = b.showCalibrationSheet,
            showAbxSheet = b.showAbxSheet,
            showSettingsSheet = b.showSettingsSheet,
            statusBannerMessage = b.statusBannerMessage
        )
    }

    val uiState: StateFlow<EqUiState> = combine(
        flowP1,
        flowP2,
        flowP3,
        flowP4,
        flowP5
    ) { p1, p2, p3, p4, p5 ->
        val allHp = EqConstants.BUILT_IN_HEADPHONE_PROFILES + p1.customHeadphoneProfiles
        val selHp = allHp.find { it.id == p1.profile.headphoneProfileId }
        val trimmed = if (selHp != null && selHp.peqFilters.isNotEmpty()) {
            AutoEq.computeTrimmedDb(selHp.peqFilters, p1.profile.headphoneFullRange).toFloat()
        } else {
            0f
        }
        val activePresetName = p1.presets.find { it.id == p1.profile.presetId }?.name ?: "Flat"
        val sliderGains = if (p1.editingRightChannel) p1.rightBands.map { it.gainDb } else p1.bands.map { it.gainDb }
        val tilt31 = AutoEq.computeTiltCurve31(
            bassDb = p1.profile.tiltBassDb.toDouble(),
            trebleDb = p1.profile.tiltTrebleDb.toDouble()
        )
        val contour10 = if (p1.profile.iso226ContourEnabled) {
            Dsp.contour(10.0 / 15.0)
        } else {
            DoubleArray(10)
        }
        val baseWithTone10 = EqConstants.FREQ_10_BAND.indices.map { i ->
            val eqGain = if (p1.isEqEnabled) sliderGains.getOrElse(i) { 0f } * p1.intensity else 0f
            val fHz = EqConstants.FREQ_10_BAND[i].toDouble()
            val tiltAtBand = EnhancerDsp.interpolate31AtFrequency(tilt31, fHz).toFloat()
            val contourAtBand = contour10.getOrElse(i) { 0.0 }.toFloat()
            eqGain + tiltAtBand + contourAtBand
        }
        val effectiveGains = if (!p1.masterEnabled || p1.isAbCompare) {
            List(10) { 0f }
        } else {
            EqConstants.FREQ_10_BAND.indices.map { i ->
                val fHz = EqConstants.FREQ_10_BAND[i].toDouble()
                val enhOffset = EnhancerMath.computeTotalEnhancerOffsetDb(fHz, p1.enhancerConfig)
                (baseWithTone10.getOrElse(i) { 0f } + enhOffset).coerceIn(-12.0f, 12.0f)
            }
        }
        val computedCeilingDb = if (!p1.masterEnabled || p1.isAbCompare) {
            0.0f
        } else {
            val maxPositiveGain = (effectiveGains.maxOrNull() ?: 0f).coerceAtLeast(0f)
            val virtualBassLoad = if (p1.enhancerConfig.virtualBassEnabled) 0.7f else 0f
            (maxPositiveGain * 0.52f + virtualBassLoad + trimmed * 0.35f)
                .coerceIn(0f, 9.0f)
        }

        EqUiState(
            engineMode = p1.engineMode,
            operatingMode = p2.diagnostics.operatingMode,
            masterEnabled = p1.masterEnabled,
            activeRoute = p1.activeRoute,
            activeDeviceKey = p1.activeDeviceKey,
            connectedProductName = p2.diagnostics.connectedProductName,
            currentPresetId = p1.profile.presetId,
            activePresetName = activePresetName,
            presets = p1.presets,
            bands = p1.bands,
            rightBands = p1.rightBands,
            isEqEnabled = p1.isEqEnabled,
            isAbCompare = p1.isAbCompare,
            editingRightChannel = p1.editingRightChannel,
            intensity = p1.intensity,
            ceilingDb = computedCeilingDb,
            effectiveGainsDb = effectiveGains,
            splitLREnabled = p1.profile.splitLREnabled,
            balanceDb = p1.profile.balanceDb,
            preampDb = p1.profile.preampDb,
            autoGainEnabled = p1.profile.autoGainEnabled,
            limiterEnabled = p1.profile.limiterEnabled,
            limiterCeilingDb = p1.profile.limiterCeilingDb,
            highPassHz = p1.profile.subBassHighPassHz,
            iso226Enabled = p1.profile.iso226ContourEnabled,
            midSideWidth = p1.profile.midSideWidth,
            vocalPresenceBoost = p1.profile.vocalPresenceBoost,
            headphoneProfileId = p1.profile.headphoneProfileId,
            headphoneStrength = p1.profile.headphoneCorrectionStrength,
            headphoneFullRange = p1.profile.headphoneFullRange,
            headphoneTrimmedDb = trimmed,
            tiltBassDb = p1.profile.tiltBassDb,
            tiltTrebleDb = p1.profile.tiltTrebleDb,
            headphoneProfiles = allHp,
            autoEqCatalog = p4.autoEqCatalog,
            autoEqLoading = p4.autoEqLoading,
            autoEqRateLimited = p4.autoEqRateLimited,
            autoEqOffline = p4.autoEqOffline,
            speakerCalEnabled = p1.profile.speakerCalibrationEnabled,
            speakerCalStrength = p1.profile.speakerCalStrength,
            speakerMeas31Db = p4.speakerMeas31Db,
            speakerCorrection31Db = p4.speakerCorrection31Db,
            isCalibratingSpeaker = p4.isCalibratingSpeaker,
            calibrationProgress = p4.calibrationProgress,
            calibrationStatusText = p5.calibrationStatusText,
            hearingCurve = p1.hearingCurve,
            enhancerConfig = p1.enhancerConfig,
            liveSpectrum31 = p2.liveSpectrum31,
            gainReduction6 = p2.gainReduction6,
            diagnostics = p2.diagnostics,
            shizukuState = p2.shizukuState,
            shizukuPrivilege = p2.shizukuPrivilege,
            startOnBoot = p3.startOnBoot,
            compatibility10Band = p3.compatibility10Band,
            transientMode = p3.transientMode,
            usbBitPerfectEnabled = p3.usbBitPerfectEnabled,
            musicOnlyTrackingEnabled = p3.musicOnlyTrackingEnabled,
            themeMode = p3.themeMode,
            seedHex = p3.seedHex,
            isSeedFromWallpaper = p3.isSeedFromWallpaper,
            showSavePresetDialog = p5.showSavePresetDialog,
            showRootWarningDialog = p5.showRootWarningDialog,
            showCalibrationSheet = p5.showCalibrationSheet,
            showAbxSheet = p5.showAbxSheet,
            showSettingsSheet = p5.showSettingsSheet,
            abxScoreCorrect = _abxScoreCorrect.value,
            abxScoreTotal = _abxScoreTotal.value,
            abxLastResult = _abxLastResult.value,
            abxAuditionMode = _abxAuditionMode.value,
            abxSourceId = _abxSourceId.value,
            statusBannerMessage = p5.statusBannerMessage
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EqUiState())

    init {
        loadStoredSpeakerCalibration(repository.activeRoute.value)
        refreshAutoEqCatalog(forceRefresh = false)

        viewModelScope.launch(Dispatchers.IO) {
            shizukuManager.refreshStatus()
            if (repository.engineMode.value == EngineMode.ROOT) {
                val rootOk = shizukuManager.verifyRootAccess()
                if (!rootOk) {
                    val fallback = if (shizukuManager.state.value == ShizukuState.READY) {
                        EngineMode.ADB_SHIZUKU
                    } else {
                        EngineMode.LEGACY
                    }
                    repository.setEngineMode(fallback)
                }
            } else if (shizukuManager.state.value == ShizukuState.READY &&
                repository.engineMode.value != EngineMode.ADB_SHIZUKU
            ) {
                repository.setEngineMode(EngineMode.ADB_SHIZUKU)
            }
            engineController.triggerImmediateSessionSync()
        }

        viewModelScope.launch {
            var previousShizukuState = shizukuManager.state.value
            shizukuManager.state.collect { newState ->
                if (newState == ShizukuState.READY && previousShizukuState != ShizukuState.READY) {
                    repository.setEngineMode(EngineMode.ADB_SHIZUKU)
                    engineController.triggerImmediateSessionSync()
                    _statusBanner.value = "Shizuku permission granted — ADB / Shizuku engine active"
                }
                previousShizukuState = newState
            }
        }

        viewModelScope.launch {
            uiState.collect { state ->
                val allHp = repository.getAllHeadphoneProfiles()
                val selHp = allHp.find { it.id == state.headphoneProfileId }
                val effectiveBands = if (state.isEqEnabled) state.bands.map { it.gainDb * state.intensity } else List(state.bands.size) { 0f }
                val effectiveRightBands = if (state.isEqEnabled) state.rightBands.map { it.gainDb * state.intensity } else List(state.rightBands.size) { 0f }
                engineController.applyDspState(
                    mode = state.engineMode,
                    masterEnabled = state.masterEnabled,
                    bandGainsDb = effectiveBands,
                    rightBandGainsDb = effectiveRightBands,
                    splitLREnabled = state.splitLREnabled,
                    balanceDb = state.balanceDb,
                    preampDb = state.preampDb,
                    autoGainEnabled = state.autoGainEnabled,
                    limiterEnabled = state.limiterEnabled,
                    limiterCeilingDb = state.limiterCeilingDb,
                    subBassHighPassHz = state.highPassHz,
                    iso226ContourEnabled = state.iso226Enabled,
                    midSideWidth = state.midSideWidth,
                    vocalPresenceBoost = state.vocalPresenceBoost,
                    headphoneProfile = selHp,
                    headphoneCorrectionStrength = state.headphoneStrength,
                    headphoneFullRange = state.headphoneFullRange,
                    tiltBassDb = state.tiltBassDb,
                    tiltTrebleDb = state.tiltTrebleDb,
                    speakerCalibrationEnabled = state.speakerCalEnabled,
                    speakerCalStrength = state.speakerCalStrength,
                    speakerCorrection31Db = state.speakerCorrection31Db,
                    hearingCurve = state.hearingCurve,
                    enhancerConfig = state.enhancerConfig,
                    compatibility10Band = state.compatibility10Band,
                    transientMode = state.transientMode,
                    usbBitPerfectEnabled = state.usbBitPerfectEnabled,
                    abxBypassCurve = _abxBypassCurve.value,
                    activeRoute = state.activeRoute,
                    musicOnlyTracking = state.musicOnlyTrackingEnabled
                )
                EqualizerNotificationHelper.updateOrCancelPersistentNotification(
                    context = getApplication(),
                    masterEnabled = state.masterEnabled,
                    presetName = state.activePresetName,
                    profileTitle = "",
                    routeLabel = state.activeRoute.label,
                    engineShortTitle = state.engineMode.shortTitle,
                    musicSummary = if (state.musicOnlyTrackingEnabled) {
                        state.diagnostics.trackedMusicSummary
                    } else {
                        "All Audio Sessions"
                    }
                )
            }
        }
    }

    private fun loadStoredSpeakerCalibration(route: OutputRoute) {
        val stored = repository.loadSpeakerCalibration(route.id)
        if (stored != null) {
            _speakerMeas31.value = stored.first
            _speakerCorr31.value = stored.second
        } else {
            _speakerMeas31.value = emptyList()
            _speakerCorr31.value = emptyList()
        }
    }

    fun toggleMaster(enabled: Boolean) {
        repository.setMasterEnabled(enabled)
    }

    fun selectPreset(preset: EqPreset) {
        repository.applyPreset(preset)
        repository.updateCurrentRouteProfile {
            it.copy(
                preampDb = preset.recommendedPreampDb,
                subBassHighPassHz = preset.recommendedHighPassHz
            )
        }
    }

    fun updateBandGain(index: Int, gainDb: Float, isRight: Boolean = false) {
        repository.updateBandGain(index, gainDb, editRightChannel = isRight)
    }

    fun flattenBands() {
        repository.flattenBands()
    }

    fun updatePreamp(preampDb: Float) {
        repository.updateCurrentRouteProfile { it.copy(preampDb = preampDb.coerceIn(-12f, 6f)) }
    }

    fun toggleAutoGain(enabled: Boolean) {
        repository.updateCurrentRouteProfile { it.copy(autoGainEnabled = enabled) }
    }

    fun toggleLimiter(enabled: Boolean) {
        repository.updateCurrentRouteProfile { it.copy(limiterEnabled = enabled) }
    }

    fun updateLimiterCeiling(ceilingDb: Float) {
        repository.updateCurrentRouteProfile { it.copy(limiterCeilingDb = ceilingDb.coerceIn(-6f, 0f)) }
    }

    fun updateHighPassHz(cutoffHz: Float) {
        repository.updateCurrentRouteProfile { it.copy(subBassHighPassHz = cutoffHz.coerceIn(0f, 200f)) }
    }

    fun toggleIso226(enabled: Boolean) {
        repository.updateCurrentRouteProfile { it.copy(iso226ContourEnabled = enabled) }
    }

    fun updateMidSideWidth(width: Float) {
        repository.updateCurrentRouteProfile { it.copy(midSideWidth = width.coerceIn(0.5f, 2.0f)) }
    }

    fun updateVocalPresence(boost: Float) {
        repository.updateCurrentRouteProfile { it.copy(vocalPresenceBoost = boost.coerceIn(0f, 1f)) }
    }

    fun setSplitLREnabled(enabled: Boolean) {
        repository.updateCurrentRouteProfile {
            it.copy(
                splitLREnabled = enabled,
                rightBandGainsDb = if (enabled && it.rightBandGainsDb.all { g -> g == 0f }) it.bandGainsDb else it.rightBandGainsDb
            )
        }
    }

    fun updateBalanceDb(balanceDb: Float) {
        repository.updateCurrentRouteProfile { it.copy(balanceDb = balanceDb.coerceIn(-6f, 6f)) }
    }

    fun selectHeadphoneProfile(profileId: String) {
        repository.updateCurrentRouteProfile { it.copy(headphoneProfileId = profileId) }
    }

    fun updateHeadphoneStrength(strength: Float) {
        repository.updateCurrentRouteProfile { it.copy(headphoneCorrectionStrength = strength.coerceIn(0f, 1f)) }
    }

    fun setHeadphoneFullRange(enabled: Boolean) {
        repository.updateCurrentRouteProfile { it.copy(headphoneFullRange = enabled) }
    }

    fun updateTiltBassDb(db: Float) {
        repository.updateCurrentRouteProfile { it.copy(tiltBassDb = db.coerceIn(-6f, 6f)) }
    }

    fun updateTiltTrebleDb(db: Float) {
        repository.updateCurrentRouteProfile { it.copy(tiltTrebleDb = db.coerceIn(-4f, 4f)) }
    }

    fun toggleSpeakerCalibration(enabled: Boolean) {
        repository.updateCurrentRouteProfile { it.copy(speakerCalibrationEnabled = enabled) }
    }

    fun updateSpeakerCalStrength(strength: Float) {
        repository.updateCurrentRouteProfile { it.copy(speakerCalStrength = strength.coerceIn(0f, 1f)) }
    }

    fun runSpeakerSelfCalibration() {
        if (_isCalibratingSpeaker.value) return
        _isCalibratingSpeaker.value = true
        _calibrationProgress.value = 0.05f
        _calibrationStatus.value = "Starting log sweep calibration…"

        viewModelScope.launch {
            val res = dspMonitor.runSpeakerSelfCalibration(getApplication()) { prog, status ->
                _calibrationProgress.value = prog
                _calibrationStatus.value = status
            }
            _isCalibratingSpeaker.value = false
            if (res.success) {
                _speakerMeas31.value = res.measured31Db
                _speakerCorr31.value = res.correction31Db
                val route = repository.activeRoute.value
                repository.saveSpeakerCalibration(route.id, res.measured31Db, res.correction31Db)
                repository.updateCurrentRouteProfile { it.copy(speakerCalibrationEnabled = true) }
                _statusBanner.value = "Speaker calibrated: ${res.statusMessage}"
            } else {
                _statusBanner.value = res.statusMessage
            }
        }
    }

    fun resetSpeakerCalibration() {
        val route = repository.activeRoute.value
        repository.clearSpeakerCalibration(route.id)
        _speakerMeas31.value = emptyList()
        _speakerCorr31.value = emptyList()
        repository.updateCurrentRouteProfile { it.copy(speakerCalibrationEnabled = false) }
    }

    fun setHearingCurveEnabled(enabled: Boolean) {
        val current = repository.hearingCurve.value
        repository.updateHearingCurve(current.copy(enabled = enabled))
    }

    fun updateHearingCurveBand(isRight: Boolean, index: Int, boostDb: Float) {
        val current = repository.hearingCurve.value
        val clamped = boostDb.coerceIn(-6f, 12f)
        if (isRight) {
            val list = current.rightEarBoostDb.toMutableList()
            if (index in list.indices) {
                list[index] = clamped
                repository.updateHearingCurve(current.copy(rightEarBoostDb = list))
            }
        } else {
            val list = current.leftEarBoostDb.toMutableList()
            if (index in list.indices) {
                list[index] = clamped
                repository.updateHearingCurve(current.copy(leftEarBoostDb = list))
            }
        }
    }

    fun playHearingTestTone(index: Int, isRight: Boolean) {
        val freqs = EqConstants.HEARING_TEST_FREQUENCIES_HZ
        if (index in freqs.indices) {
            dspMonitor.playHearingTone(freqs[index], isRight)
        }
    }

    fun setIeqPreset(preset: IeqPreset) {
        repository.updateEnhancerConfig { it.copy(ieqPreset = preset) }
    }

    fun updateIeqAmount(amount: Int) {
        repository.updateEnhancerConfig { it.copy(ieqAmount = amount.coerceIn(0, 16)) }
    }

    fun updateIeqCustomBand(index: Int, gainDb: Float) {
        val current = repository.enhancerConfig.value.ieqCustom10Db.toMutableList()
        if (index in current.indices) {
            current[index] = gainDb.coerceIn(-12f, 12f)
            repository.updateEnhancerConfig { it.copy(ieqCustom10Db = current, ieqPreset = IeqPreset.CUSTOM) }
        }
    }

    fun resetIeqCustomBands(flat: Boolean) {
        val target = if (flat) List(10) { 0f } else EnhancerDsp.ieqDefaultCustom10Db(IeqPreset.BALANCED)
        repository.updateEnhancerConfig { it.copy(ieqCustom10Db = target, ieqPreset = IeqPreset.CUSTOM) }
    }

    fun setDialogueEnhancerEnabled(enabled: Boolean) {
        repository.updateEnhancerConfig { it.copy(dialogueEnhancerEnabled = enabled) }
    }

    fun updateDialogueAmount(amount: Int) {
        repository.updateEnhancerConfig { it.copy(dialogueAmount = amount.coerceIn(0, 16)) }
    }

    fun updateDialogueDucking(ducking: Int) {
        repository.updateEnhancerConfig { it.copy(dialogueDucking = ducking.coerceIn(0, 16)) }
    }

    fun setBassEnhancerEnabled(enabled: Boolean) {
        repository.updateEnhancerConfig { cur ->
            val nextStrength = if (enabled && cur.bassEnhancerStrength == 0) {
                80
            } else {
                cur.bassEnhancerStrength
            }
            cur.copy(
                bassEnhancerEnabled = enabled,
                bassEnhancerStrength = nextStrength,
                virtualBassEnabled = if (enabled) cur.virtualBassEnabled else false
            )
        }
    }

    fun updateBassEnhancerStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 100)
        repository.updateEnhancerConfig { cur ->
            cur.copy(
                bassEnhancerEnabled = clamped > 0,
                bassEnhancerStrength = clamped,
                virtualBassEnabled = if (clamped > 0) cur.virtualBassEnabled else false
            )
        }
    }

    fun setVirtualBassEnabled(enabled: Boolean) {
        repository.updateEnhancerConfig { cur ->
            cur.copy(
                bassEnhancerEnabled = if (enabled) true else cur.bassEnhancerStrength > 0,
                bassEnhancerStrength = if (enabled && cur.bassEnhancerStrength == 0) 80 else cur.bassEnhancerStrength,
                virtualBassEnabled = enabled
            )
        }
    }

    fun setAudioProfile(profile: AudioProfile = AudioProfile.OFF) {
    }

    fun resetEnhancers() {
        repository.updateEnhancerConfig {
            EnhancerConfig(
                ieqCustom10Db = EnhancerDsp.ieqDefaultCustom10Db(IeqPreset.BALANCED)
            )
        }
    }

    fun refreshAutoEqCatalog(forceRefresh: Boolean = false) {
        if (_autoEqLoading.value) return
        _autoEqLoading.value = true
        viewModelScope.launch {
            val res = autoEqRepo.loadIndex(forceRefresh)
            _autoEqLoading.value = false
            _autoEqRateLimited.value = res.isRateLimited
            _autoEqOffline.value = res.fromCache
            if (res.entries.isNotEmpty()) {
                _autoEqCatalog.value = res.entries
            }
        }
    }

    fun selectAutoEqCatalogEntry(entry: IndexEntry) {
        viewModelScope.launch {
            _statusBanner.value = "Downloading ${entry.name} from AutoEq…"
            val (peq, geq) = autoEqRepo.fetchProfileForEntry(entry)
            if (peq.isEmpty() && geq.isEmpty()) {
                _statusBanner.value = "Could not fetch profile for ${entry.name}"
                return@launch
            }
            val safeId = "autoeq_" + entry.name.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(24)
            val corr31 = if (peq.isNotEmpty()) {
                AutoEq.peqToCorrection31(peq).map { it.toFloat() }
            } else {
                AutoEq.graphicToCorrection31(geq).map { it.toFloat() }
            }
            val profile = HeadphoneProfile(
                id = safeId,
                name = entry.name,
                source = entry.source ?: "AutoEq",
                rig = entry.rig ?: "HMS II.3",
                peqFilters = peq,
                graphicPoints = geq,
                correction31Db = corr31,
                isApproximation = peq.isEmpty(),
                license = "CC-BY-NC-SA-4.0"
            )
            repository.registerCustomHeadphoneProfile(profile)
            repository.updateCurrentRouteProfile { it.copy(headphoneProfileId = profile.id) }
            _statusBanner.value = "Applied AutoEq profile: ${entry.name}"
        }
    }

    fun importCustomAutoEqText(name: String, rawText: String) {
        val cleanName = name.trim().ifEmpty { "Imported PEQ" }
        val peq = AutoEq.parsePeq(rawText)
        val geq = if (peq.isEmpty()) AutoEq.parseGraphicEq(rawText) else emptyList()
        if (peq.isEmpty() && geq.isEmpty()) {
            _statusBanner.value = "No valid ParametricEQ or GraphicEQ lines found."
            return
        }
        val safeId = "import_" + System.currentTimeMillis().toString().takeLast(6)
        val corr31 = if (peq.isNotEmpty()) {
            AutoEq.peqToCorrection31(peq).map { it.toFloat() }
        } else {
            AutoEq.graphicToCorrection31(geq).map { it.toFloat() }
        }
        val profile = HeadphoneProfile(
            id = safeId,
            name = cleanName,
            source = "Custom Import",
            rig = "User Text",
            peqFilters = peq,
            graphicPoints = geq,
            correction31Db = corr31,
            isApproximation = peq.isEmpty(),
            license = "User Content"
        )
        repository.registerCustomHeadphoneProfile(profile)
        repository.updateCurrentRouteProfile { it.copy(headphoneProfileId = profile.id) }
        _statusBanner.value = "Imported & applied: $cleanName"
    }

    fun exportActiveCorrectionText(): String {
        val s = uiState.value
        val selHp = s.headphoneProfiles.find { it.id == s.headphoneProfileId }
        return if (selHp != null && selHp.peqFilters.isNotEmpty()) {
            AutoEq.exportToParametricEqText(
                profileName = selHp.name,
                preampDb = s.preampDb.toDouble(),
                filters = selHp.peqFilters
            )
        } else {
            val combined = Dsp.compute31BandTargets(
                userGains10 = DoubleArray(10) { s.bands[it].gainDb.toDouble() },
                cal31 = DoubleArray(31) { s.speakerCorrection31Db.getOrElse(it) { 0f }.toDouble() },
                hpFc = s.highPassHz.toDouble(),
                iso226On = s.iso226Enabled,
                volIdx = 10,
                volMax = 15
            )
            AutoEq.export31ToGraphicEqText(combined)
        }
    }

    fun selectAbxListeningSource(sourceId: String) {
        _abxSourceId.value = sourceId
    }

    fun auditionAbx(channel: String) {
        _abxAuditionMode.value = channel
        val s = uiState.value
        val enh31 = EnhancerDsp.computeCombinedEnhancer31(s.enhancerConfig)
        val cal31 = DoubleArray(31) {
            (s.speakerCorrection31Db.getOrElse(it) { 0f }.toDouble() + enh31[it]).coerceIn(-15.0, 15.0)
        }
        val target31 = Dsp.compute31BandTargets(
            userGains10 = DoubleArray(10) { if (s.isEqEnabled) (s.bands[it].gainDb * s.intensity).toDouble() else 0.0 },
            cal31 = cal31,
            hpFc = s.highPassHz.toDouble(),
            iso226On = s.iso226Enabled,
            volIdx = 10,
            volMax = 15
        ).map { it.toFloat() }

        when (channel) {
            "A" -> {
                _abxBypassCurve.value = false
                dspMonitor.playAbxAuditionStimulus(
                    sourceId = _abxSourceId.value,
                    applyEq = true,
                    curve31Db = target31,
                    preampDb = s.preampDb,
                    enhancerConfig = s.enhancerConfig
                )
            }
            "B" -> {
                _abxBypassCurve.value = true
                dspMonitor.playAbxAuditionStimulus(
                    sourceId = _abxSourceId.value,
                    applyEq = false,
                    curve31Db = emptyList(),
                    preampDb = 0f,
                    enhancerConfig = EnhancerConfig()
                )
            }
            "X" -> {
                val isEq = _abxHiddenIsA.value
                _abxBypassCurve.value = !isEq
                dspMonitor.playAbxAuditionStimulus(
                    sourceId = _abxSourceId.value,
                    applyEq = isEq,
                    curve31Db = if (isEq) target31 else emptyList(),
                    preampDb = if (isEq) s.preampDb else 0f,
                    enhancerConfig = if (isEq) s.enhancerConfig else EnhancerConfig()
                )
            }
        }
    }

    fun submitAbxGuess(guessIsA: Boolean) {
        val actualIsA = _abxHiddenIsA.value
        val correct = (guessIsA == actualIsA)
        _abxLastResult.value = correct
        _abxScoreTotal.value += 1
        if (correct) {
            _abxScoreCorrect.value += 1
        }
        _abxHiddenIsA.value = (Math.random() >= 0.5)
    }

    fun resetAbxScore() {
        _abxScoreCorrect.value = 0
        _abxScoreTotal.value = 0
        _abxLastResult.value = null
        _abxHiddenIsA.value = (Math.random() >= 0.5)
    }

    fun selectMode(mode: EngineMode) {
        when (mode) {
            EngineMode.ROOT -> {
                if (!repository.rootWarningAccepted.value) {
                    _showRootWarningDialog.value = true
                } else {
                    verifyAndActivateRootMode()
                }
            }
            EngineMode.ADB_SHIZUKU -> {
                shizukuManager.clearRootVerification()
                repository.setEngineMode(EngineMode.ADB_SHIZUKU)
                shizukuManager.refreshStatus()
                if (shizukuManager.state.value == ShizukuState.NEEDS_PERMISSION ||
                    shizukuManager.state.value == ShizukuState.DENIED_PERMANENTLY
                ) {
                    shizukuManager.requestPermission()
                }
                engineController.triggerImmediateSessionSync()
            }
            EngineMode.LEGACY -> {
                shizukuManager.clearRootVerification()
                repository.setEngineMode(EngineMode.LEGACY)
                engineController.triggerImmediateSessionSync()
            }
        }
    }

    fun confirmRootWarning() {
        repository.setRootWarningAccepted(true)
        _showRootWarningDialog.value = false
        verifyAndActivateRootMode()
    }

    private fun verifyAndActivateRootMode() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootGranted = shizukuManager.verifyRootAccess()
            if (rootGranted) {
                repository.setEngineMode(EngineMode.ROOT)
                engineController.triggerImmediateSessionSync()
                _statusBanner.value = "Root su access verified — Root engine active"
            } else {
                val fallback = if (shizukuManager.state.value == ShizukuState.READY) {
                    EngineMode.ADB_SHIZUKU
                } else {
                    EngineMode.LEGACY
                }
                repository.setEngineMode(fallback)
                engineController.triggerImmediateSessionSync()
                _statusBanner.value = "Root permission not granted on this device. Using ${fallback.shortTitle} mode."
            }
        }
    }

    fun dismissRootWarningAndFallback() {
        _showRootWarningDialog.value = false
        shizukuManager.clearRootVerification()
        val fallback = if (shizukuManager.state.value == ShizukuState.READY) {
            EngineMode.ADB_SHIZUKU
        } else {
            EngineMode.LEGACY
        }
        repository.setEngineMode(fallback)
        engineController.triggerImmediateSessionSync()
    }

    fun handleShizukuAction() {
        shizukuManager.refreshStatus()
        when (shizukuManager.state.value) {
            ShizukuState.NEEDS_PERMISSION,
            ShizukuState.DENIED_PERMANENTLY -> shizukuManager.requestPermission()
            ShizukuState.NOT_RUNNING -> shizukuManager.launchShizukuApp()
            ShizukuState.NOT_INSTALLED -> shizukuManager.openPlayStoreForShizuku()
            ShizukuState.READY -> {
                repository.setEngineMode(EngineMode.ADB_SHIZUKU)
                shizukuManager.requestPermission()
                engineController.triggerImmediateSessionSync()
                _statusBanner.value = "Shizuku connected & active"
            }
            else -> shizukuManager.refreshStatus()
        }
    }

    fun setStartOnBoot(enabled: Boolean) {
        repository.setStartOnBoot(enabled)
    }

    fun setCompatibility10Band(enabled: Boolean) {
        repository.setCompatibility10Band(enabled)
    }

    fun setTransientMode(enabled: Boolean) {
        repository.setTransientMode(enabled)
    }

    fun setUsbBitPerfectEnabled(enabled: Boolean) {
        repository.setUsbBitPerfectEnabled(enabled)
    }

    fun setMusicOnlyTrackingEnabled(enabled: Boolean) {
        repository.setMusicOnlyTracking(enabled)
        engineController.triggerImmediateSessionSync()
    }

    fun openNotificationAccessSettings() {
        MusicAppTracker.openNotificationAccessSettings(getApplication())
    }

    fun refreshNotificationAndSessions() {
        MusicAppTracker.isNotificationListenerEnabled(getApplication())
        engineController.triggerImmediateSessionSync()
        val s = uiState.value
        EqualizerNotificationHelper.updateOrCancelPersistentNotification(
            context = getApplication(),
            masterEnabled = s.masterEnabled,
            presetName = s.activePresetName,
            profileTitle = "",
            routeLabel = s.activeRoute.label,
            engineShortTitle = s.engineMode.shortTitle,
            musicSummary = if (s.musicOnlyTrackingEnabled) {
                s.diagnostics.trackedMusicSummary
            } else {
                "All Audio Sessions"
            }
        )
    }

    fun setThemeMode(mode: String) {
        repository.setThemeMode(mode)
    }

    fun setSeedHex(hex: String, fromWallpaper: Boolean = false) {
        repository.setSeedHex(hex, fromWallpaper)
    }

    fun openSavePresetDialog() {
        _showSavePresetDialog.value = true
    }

    fun dismissSavePresetDialog() {
        _showSavePresetDialog.value = false
    }

    fun saveCustomPreset(name: String) {
        val s = uiState.value
        repository.saveNewCustomPreset(
            name = name,
            gainsDb = s.bands.map { it.gainDb },
            preampDb = s.preampDb,
            highPassHz = s.highPassHz
        )
        _showSavePresetDialog.value = false
    }

    fun openCalibrationSheet() {
        _showCalibrationSheet.value = true
    }

    fun dismissCalibrationSheet() {
        _showCalibrationSheet.value = false
    }

    fun openAbxSheet() {
        resetAbxScore()
        _showAbxSheet.value = true
    }

    fun dismissAbxSheet() {
        _abxBypassCurve.value = false
        _showAbxSheet.value = false
    }

    fun openSettingsSheet() {
        _showSettingsSheet.value = true
    }

    fun dismissSettingsSheet() {
        _showSettingsSheet.value = false
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun attachExternalAudioSession(sessionId: Int) {
        engineController.attachExternalAudioSession(sessionId)
    }

    fun setMasterEnabled(enabled: Boolean) {
        toggleMaster(enabled)
    }

    fun selectPreset(id: String) {
        repository.presets.value.firstOrNull { it.id == id }?.let { selectPreset(it) }
    }

    fun deleteCustomPreset(presetId: String) {
        repository.deleteCustomPreset(presetId)
    }

    fun setEqEnabled(enabled: Boolean) {
        _isEqEnabled.value = enabled
    }

    fun setEditingRightChannel(isRight: Boolean) {
        _editingRightChannel.value = isRight
    }

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 2f)
    }

    fun setTiltBassDb(value: Float) {
        updateTiltBassDb(value)
    }

    fun setTiltTrebleDb(value: Float) {
        updateTiltTrebleDb(value)
    }

    fun setIso226Enabled(enabled: Boolean) {
        toggleIso226(enabled)
    }

    fun setAbCompare(enabled: Boolean) {
        _isAbCompare.value = enabled
        _abxBypassCurve.value = enabled
    }

    fun setShowSettingsSheet(show: Boolean) {
        _showSettingsSheet.value = show
    }

    fun setShowSavePresetDialog(show: Boolean) {
        _showSavePresetDialog.value = show
    }

    fun setShowCalibrationSheet(show: Boolean) {
        _showCalibrationSheet.value = show
    }

    fun setShowAbxSheet(show: Boolean) {
        _showAbxSheet.value = show
    }

    fun dismissStatusBanner() {
        dismissBanner()
    }

    override fun onCleared() {
        super.onCleared()
        engineController.release()
        dspMonitor.release()
    }

    private data class Intermediate1A(
        val engineMode: EngineMode,
        val masterEnabled: Boolean,
        val activeRoute: OutputRoute,
        val activeDeviceKey: String
    )

    private data class Intermediate1B(
        val presets: List<EqPreset>,
        val profile: RouteMemoryProfile,
        val hearingCurve: PersonalHearingCurve,
        val enhancerConfig: EnhancerConfig,
        val customHeadphoneProfiles: List<HeadphoneProfile> = emptyList()
    )

    private data class Intermediate1C(
        val isEqEnabled: Boolean,
        val isAbCompare: Boolean,
        val editingRightChannel: Boolean,
        val intensity: Float
    )

    private data class Intermediate3A(
        val startOnBoot: Boolean,
        val compatibility10Band: Boolean,
        val transientMode: Boolean,
        val usbBitPerfectEnabled: Boolean
    )

    private data class Intermediate3B(
        val themeMode: String,
        val seedHex: String,
        val isSeedFromWallpaper: Boolean,
        val musicOnlyTrackingEnabled: Boolean
    )

    private data class Intermediate4A(
        val autoEqCatalog: List<IndexEntry>,
        val autoEqLoading: Boolean,
        val autoEqRateLimited: Boolean,
        val autoEqOffline: Boolean
    )

    private data class Intermediate4B(
        val speakerMeas31Db: List<Float>,
        val speakerCorrection31Db: List<Float>,
        val isCalibratingSpeaker: Boolean,
        val calibrationProgress: Float
    )

    private data class Intermediate5A(
        val calibrationStatusText: String,
        val showSavePresetDialog: Boolean,
        val showRootWarningDialog: Boolean
    )

    private data class Intermediate5B(
        val showCalibrationSheet: Boolean,
        val showAbxSheet: Boolean,
        val showSettingsSheet: Boolean,
        val statusBannerMessage: String?
    )

    private data class PartialUiState1(
        val engineMode: EngineMode,
        val masterEnabled: Boolean,
        val activeRoute: OutputRoute,
        val activeDeviceKey: String,
        val presets: List<EqPreset>,
        val profile: RouteMemoryProfile,
        val bands: List<EqBand>,
        val rightBands: List<EqBand>,
        val hearingCurve: PersonalHearingCurve,
        val enhancerConfig: EnhancerConfig,
        val isEqEnabled: Boolean,
        val isAbCompare: Boolean,
        val editingRightChannel: Boolean,
        val intensity: Float,
        val customHeadphoneProfiles: List<HeadphoneProfile> = emptyList()
    )

    private data class PartialUiState2(
        val diagnostics: DeviceDiagnostics,
        val liveSpectrum31: List<Float>,
        val gainReduction6: List<Float>,
        val shizukuState: ShizukuState,
        val shizukuPrivilege: String
    )

    private data class PartialUiState3(
        val startOnBoot: Boolean,
        val compatibility10Band: Boolean,
        val transientMode: Boolean,
        val usbBitPerfectEnabled: Boolean,
        val musicOnlyTrackingEnabled: Boolean,
        val themeMode: String,
        val seedHex: String,
        val isSeedFromWallpaper: Boolean
    )

    private data class PartialUiState4(
        val autoEqCatalog: List<IndexEntry>,
        val autoEqLoading: Boolean,
        val autoEqRateLimited: Boolean,
        val autoEqOffline: Boolean,
        val speakerMeas31Db: List<Float>,
        val speakerCorrection31Db: List<Float>,
        val isCalibratingSpeaker: Boolean,
        val calibrationProgress: Float
    )

    private data class PartialUiState5(
        val calibrationStatusText: String,
        val showSavePresetDialog: Boolean,
        val showRootWarningDialog: Boolean,
        val showCalibrationSheet: Boolean,
        val showAbxSheet: Boolean,
        val showSettingsSheet: Boolean,
        val statusBannerMessage: String?
    )
}
