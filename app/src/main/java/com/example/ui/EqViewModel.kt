package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEngineController
import com.example.audio.DeviceDiagnostics
import com.example.audio.Dsp
import com.example.audio.DspReferenceMonitor
import com.example.audio.Layers
import com.example.audio.ReferenceSignalType
import com.example.audio.SpeakerCalibrationRunner
import com.example.data.EqRepository
import com.example.model.CapabilityLevel
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.HeadphoneProfile
import com.example.model.ModeStatusInfo
import com.example.model.OutputRoute
import com.example.model.PresetConfig
import com.example.model.SectionWidths
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EqUiState(
    val isEnabled: Boolean = true,
    val isAbCompare: Boolean = false,
    val selectedMode: EngineMode = EngineMode.LEGACY,
    val modeStatuses: Map<EngineMode, ModeStatusInfo> = emptyMap(),
    val presets: List<PresetConfig> = EqConstants.BUILT_IN_PRESETS,
    val selectedPresetId: String = "percussion_clarity",
    val gainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val intensity: Float = 1.0f, // 0.5 .. 1.5 (Light 0.6, Standard 1.0, Deep+ 1.3)
    val volumeContourEnabled: Boolean = true,
    val currentVolumeRatio: Float = 0.6f,
    val activeRoute: OutputRoute = OutputRoute.SPEAKER,
    val inputGainDb: Float = -3.0f,
    val makeupGainDb: Float = 0.0f,
    val ceilingLoadDb: Float = 0.0f, // max(0, max(effective31) - 3)
    val highPassHz: Float = 40f,
    val bassHarmonicMixDb: Float? = -15f,
    val bassBoostStrength: Int = 100,
    val virtualizerStrength: Int = 200,
    val qOverrides: Map<Int, Float> = mapOf(250 to 1.0f, 4000 to 2.0f),
    val headphoneProfiles: List<HeadphoneProfile> = EqConstants.HEADPHONE_PROFILES,
    val selectedHeadphoneProfileId: String = "none",
    val headphoneStrength: Float = 0.8f,
    val speakerCalEnabled: Boolean = false,
    val speakerCalStrength: Float = 0.7f,
    val speakerMeas31Db: List<Float> = emptyList(),
    val speakerCorrection31Db: List<Float> = emptyList(),
    val isCalibratingSpeaker: Boolean = false,
    val calibrationProgress: Float = 0f,
    val calibrationStatusText: String = "",
    val compatibility10Band: Boolean = false,
    val transientMode: Boolean = false,
    val isReferencePlaying: Boolean = false,
    val referenceSignal: ReferenceSignalType = ReferenceSignalType.TABLA_LOOP,
    val rootWarningAccepted: Boolean = false,
    val showRootWarningDialog: Boolean = false,
    val showSavePresetDialog: Boolean = false,
    val showSettingsSheet: Boolean = false,
    val showCalibrationSheet: Boolean = false,
    val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    val statusBannerMessage: String? = null
) {
    val activeStatus: ModeStatusInfo
        get() = modeStatuses[selectedMode] ?: ModeStatusInfo(
            mode = selectedMode,
            level = CapabilityLevel.AVAILABLE,
            summaryText = "${selectedMode.shortTitle} available",
            detailReason = selectedMode.subtitle
        )

    val selectedHeadphoneProfile: HeadphoneProfile
        get() = headphoneProfiles.find { it.id == selectedHeadphoneProfileId } ?: headphoneProfiles.first()

    val isCeilingWarning: Boolean
        get() = ceilingLoadDb > 4.0f
}

class EqViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EqRepository(application)
    private val engineController = AudioEngineController(application)
    private val dspMonitor = DspReferenceMonitor(viewModelScope)

    private val _uiState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = _uiState.asStateFlow()

    private var initialLoadComplete = false
    private var persistDebounceJob: Job? = null

    // Smoothed contour offsets (max 1 dB step per 500 ms per v4 Section 7)
    private val currentContourOffsets = DoubleArray(10)
    private var speakerMeasArray = DoubleArray(31)
    private var speakerValidArray = BooleanArray(31)

    init {
        val initialStatuses = engineController.probeCapabilities()
        val initialRoute = engineController.getCurrentRoute()
        val initialVol = engineController.getCurrentVolumeRatio()

        _uiState.update { state ->
            state.copy(
                modeStatuses = initialStatuses,
                activeRoute = initialRoute,
                currentVolumeRatio = initialVol.toFloat(),
                diagnostics = engineController.diagnostics
            )
        }

        // Observe persisted preferences and saved Room presets
        viewModelScope.launch {
            combine(
                repository.preferencesFlow,
                repository.customPresetsFlow
            ) { prefs, customPresets ->
                prefs to customPresets
            }.collect { (prefs, customPresets) ->
                val allPresets = EqConstants.BUILT_IN_PRESETS + customPresets
                if (!initialLoadComplete) {
                    initialLoadComplete = true
                    val mode = EngineMode.entries.find { it.id == prefs.selectedModeId } ?: EngineMode.LEGACY
                    val matchedPreset = allPresets.find { it.id == prefs.selectedPresetId }
                    val qOverrides = matchedPreset?.qOverrides ?: emptyMap()
                    val harmMix = matchedPreset?.bassHarmonicMixDb

                    // Restore speaker calibration arrays if saved
                    val parsedMeas = prefs.speakerMeas31Csv.split(",").mapNotNull { it.toDoubleOrNull() }
                    val parsedValid = prefs.speakerValid31Csv.split(",").map { it == "1" }
                    if (parsedMeas.size == 31 && parsedValid.size == 31) {
                        speakerMeasArray = parsedMeas.toDoubleArray()
                        speakerValidArray = parsedValid.toBooleanArray()
                    }
                    val spkCorr31 = if (prefs.speakerCalEnabled && parsedMeas.size == 31) {
                        Dsp.computeSpeakerCorrection(speakerMeasArray, speakerValidArray, prefs.speakerCalStrength.toDouble())
                    } else {
                        DoubleArray(31)
                    }

                    engineController.reconfigureEngineOptions(
                        use10BandCompatibility = prefs.compatibility10Band,
                        useTransientMode = prefs.transientMode
                    )

                    _uiState.update { current ->
                        current.copy(
                            isEnabled = prefs.isEnabled,
                            selectedMode = mode,
                            presets = allPresets,
                            selectedPresetId = prefs.selectedPresetId,
                            gainsDb = prefs.gainsDb,
                            highPassHz = prefs.highPassHz,
                            bassHarmonicMixDb = harmMix,
                            bassBoostStrength = matchedPreset?.bassBoostStrength ?: 100,
                            virtualizerStrength = matchedPreset?.virtualizerStrength ?: 200,
                            qOverrides = qOverrides,
                            intensity = prefs.intensity,
                            volumeContourEnabled = prefs.volumeContourEnabled,
                            selectedHeadphoneProfileId = prefs.selectedHeadphoneProfileId,
                            headphoneStrength = prefs.headphoneStrength,
                            speakerCalEnabled = prefs.speakerCalEnabled,
                            speakerCalStrength = prefs.speakerCalStrength,
                            speakerMeas31Db = speakerMeasArray.map { it.toFloat() },
                            speakerCorrection31Db = spkCorr31.map { it.toFloat() },
                            compatibility10Band = prefs.compatibility10Band,
                            transientMode = prefs.transientMode,
                            rootWarningAccepted = prefs.rootWarningAccepted
                        )
                    }
                    applyPresetOrContextLoad(persistNow = false)
                } else {
                    _uiState.update { current ->
                        current.copy(
                            presets = allPresets,
                            rootWarningAccepted = prefs.rootWarningAccepted
                        )
                    }
                }
            }
        }

        // Volume Contour & Output Route polling loop every 500 ms (v4 Section 6 & 7: max 1 dB per 500 ms step)
        viewModelScope.launch {
            var tickCounter = 0
            while (isActive) {
                delay(500L)
                tickCounter++
                val s = _uiState.value
                val newRoute = engineController.getCurrentRoute()
                val volRatio = engineController.getCurrentVolumeRatio()
                val routeChanged = newRoute != s.activeRoute

                // Step volume contour at most 1.0 dB per 500 ms (not applied in Flat)
                val isFlat = s.selectedPresetId == "flat"
                val targetContour = if (s.volumeContourEnabled && !isFlat) {
                    Dsp.contour(volRatio)
                } else {
                    DoubleArray(10)
                }
                var contourMoved = false
                for (i in 0 until 10) {
                    val diff = targetContour[i] - currentContourOffsets[i]
                    if (abs(diff) > 0.02) {
                        val step = diff.coerceIn(-1.0, 1.0)
                        currentContourOffsets[i] += step
                        contourMoved = true
                    } else {
                        currentContourOffsets[i] = targetContour[i]
                    }
                }

                if (routeChanged) {
                    _uiState.update {
                        it.copy(
                            activeRoute = newRoute,
                            currentVolumeRatio = volRatio.toFloat()
                        )
                    }
                    applyPresetOrContextLoad(persistNow = false)
                } else if (contourMoved || abs(volRatio.toFloat() - s.currentVolumeRatio) > 0.02f) {
                    _uiState.update { it.copy(currentVolumeRatio = volRatio.toFloat()) }
                    if (contourMoved) {
                        applySliderOrContourFast(persistNow = false)
                    }
                }

                // Every 3 seconds (6 ticks), refresh session discovery if in ADB/Shizuku or Legacy mode
                if (tickCounter % 6 == 0 && s.isEnabled) {
                    val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
                    _uiState.update {
                        it.copy(
                            modeStatuses = statuses,
                            diagnostics = engineController.diagnostics
                        )
                    }
                }
            }
        }
    }

    private fun buildCurrentLayers(state: EqUiState): Layers {
        val userDb = DoubleArray(10) { i -> state.gainsDb.getOrElse(i) { 0f }.toDouble() }
        val isFlat = state.selectedPresetId == "flat"
        val intensity = if (isFlat) 1.0 else state.intensity.toDouble()

        // v4 Section 6: If speaker calibration is active on Speaker route, use zero route offsets
        val routeDb = if (isFlat) {
            DoubleArray(10)
        } else if (state.activeRoute == OutputRoute.SPEAKER && state.speakerCalEnabled && state.speakerCorrection31Db.size == 31) {
            DoubleArray(10)
        } else {
            state.activeRoute.offsetsDb.clone()
        }

        val contourDb = if (state.volumeContourEnabled && !isFlat) {
            currentContourOffsets.clone()
        } else {
            DoubleArray(10)
        }

        // v4 Section 8: Correction31 from Speaker Calibration (on Speaker route) or Headphone Profile (on Wired/BT/USB)
        val correction31 = when {
            isFlat -> DoubleArray(31)
            state.activeRoute == OutputRoute.SPEAKER && state.speakerCalEnabled && state.speakerCorrection31Db.size == 31 -> {
                DoubleArray(31) { state.speakerCorrection31Db[it].toDouble() }
            }
            state.activeRoute != OutputRoute.SPEAKER && state.selectedHeadphoneProfileId != "none" -> {
                Dsp.computeHeadphoneCorrection31(state.selectedHeadphoneProfile, state.headphoneStrength.toDouble())
            }
            else -> DoubleArray(31)
        }

        return Layers(
            userDb = userDb,
            intensity = intensity,
            routeDb = routeDb,
            contourDb = contourDb,
            correction31 = correction31
        )
    }

    /**
     * Called on preset, route, headphone profile, speaker calibration, or intensity change.
     * Recomputes `inputGainDb` and `makeupGainDb` (v4 Section 3 & 5.3) and glides 250 ms.
     */
    private fun applyPresetOrContextLoad(persistNow: Boolean = true) {
        val s = _uiState.value
        val layers = buildCurrentLayers(s)
        val eff31 = Dsp.computeEffective31(layers, withContour = true)
        val ceilingLoad = Dsp.computeCeilingLoadDb(eff31)

        val matchedPreset = s.presets.find { it.id == s.selectedPresetId }
        val mbc = EqConstants.mbcForPreset(matchedPreset)
        val flatBypass = matchedPreset?.bypassChain == true

        engineController.loadEngineState(
            mode = s.selectedMode,
            enabled = s.isEnabled,
            abCompare = s.isAbCompare,
            layers = layers,
            mbc = mbc,
            routeBaseDb = s.activeRoute.baseInputGainDb,
            limiterDb = s.activeRoute.limiterThresholdDb,
            flatBypass = flatBypass,
            bassBoostStrength = s.bassBoostStrength,
            virtualizerStrength = s.virtualizerStrength,
            internalSessionId = dspMonitor.getAudioSessionId()
        )

        val inGain = engineController.currentInputGainDb
        val makeupGain = engineController.currentMakeupGainDb

        syncReferenceMonitorDsp(s, layers, inGain, makeupGain, flatBypass)

        _uiState.update {
            it.copy(
                inputGainDb = inGain,
                makeupGainDb = makeupGain,
                ceilingLoadDb = ceilingLoad
            )
        }

        if (persistNow) {
            scheduleDebouncedPersist(delayMs = 80L)
        }
    }

    /**
     * Fast path for continuous slider drags and volume contour steps:
     * Freezes `inputGainDb` and `makeupGainDb`, glides 60 ms, and never calls `probeCapabilities()`.
     */
    private fun applySliderOrContourFast(persistNow: Boolean = true) {
        val s = _uiState.value
        val layers = buildCurrentLayers(s)
        val eff31 = Dsp.computeEffective31(layers, withContour = true)
        val ceilingLoad = Dsp.computeCeilingLoadDb(eff31)
        val flatBypass = s.selectedPresetId == "flat"

        engineController.updateSlidersOrContourFast(
            enabled = s.isEnabled,
            abCompare = s.isAbCompare,
            layers = layers,
            flatBypass = flatBypass,
            internalSessionId = dspMonitor.getAudioSessionId()
        )

        syncReferenceMonitorDsp(s, layers, s.inputGainDb, s.makeupGainDb, flatBypass)

        _uiState.update {
            it.copy(ceilingLoadDb = ceilingLoad)
        }

        if (persistNow) {
            scheduleDebouncedPersist(delayMs = 250L)
        }
    }

    private fun syncReferenceMonitorDsp(
        s: EqUiState,
        layers: Layers,
        inGainDb: Float,
        makeupDb: Float,
        flatBypass: Boolean
    ) {
        val effective10 = List(10) { i ->
            (layers.userDb[i] * layers.intensity + layers.routeDb[i] + layers.contourDb[i]).toFloat()
        }
        val effectiveHpf = if (s.activeRoute == OutputRoute.SPEAKER) {
            max(s.highPassHz, OutputRoute.SPEAKER.rootHighPassHz)
        } else {
            s.highPassHz
        }
        val effectiveHarmonicMix = s.bassHarmonicMixDb?.let { baseMix ->
            baseMix + s.activeRoute.harmonicMixDeltaDb
        }

        dspMonitor.updateDspState(
            gainsDb = effective10,
            preampDb = inGainDb,
            makeupDb = makeupDb,
            highPassHz = effectiveHpf,
            bassHarmonicMixDb = effectiveHarmonicMix,
            limiterThresholdDb = s.activeRoute.limiterThresholdDb,
            widths = SectionWidths(),
            qOverrides = s.qOverrides,
            bypassed = !s.isEnabled || flatBypass,
            abCompare = s.isAbCompare
        )
    }

    private fun scheduleDebouncedPersist(delayMs: Long = 250L) {
        persistDebounceJob?.cancel()
        persistDebounceJob = viewModelScope.launch {
            delay(delayMs)
            val s = _uiState.value
            repository.saveState(
                isEnabled = s.isEnabled,
                modeId = s.selectedMode.id,
                presetId = s.selectedPresetId,
                gainsDb = s.gainsDb,
                highPassHz = s.highPassHz,
                intensity = s.intensity,
                volumeContourEnabled = s.volumeContourEnabled,
                headphoneProfileId = s.selectedHeadphoneProfileId,
                headphoneStrength = s.headphoneStrength,
                speakerCalEnabled = s.speakerCalEnabled,
                speakerCalStrength = s.speakerCalStrength,
                compatibility10Band = s.compatibility10Band,
                transientMode = s.transientMode
            )
        }
    }

    fun setPowerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isEnabled = enabled) }
        applySliderOrContourFast()
    }

    fun setAbCompare(abCompare: Boolean) {
        _uiState.update { it.copy(isAbCompare = abCompare) }
        applySliderOrContourFast(persistNow = false)
    }

    fun setIntensity(newIntensity: Float) {
        val clamped = newIntensity.coerceIn(0.5f, 1.5f)
        _uiState.update { it.copy(intensity = clamped) }
        applyPresetOrContextLoad()
    }

    fun setVolumeContourEnabled(enabled: Boolean) {
        _uiState.update { it.copy(volumeContourEnabled = enabled) }
        if (!enabled) {
            currentContourOffsets.fill(0.0)
        } else {
            val c = Dsp.contour(engineController.getCurrentVolumeRatio())
            for (i in 0 until 10) currentContourOffsets[i] = c[i]
        }
        applySliderOrContourFast()
    }

    fun selectMode(mode: EngineMode) {
        val s = _uiState.value
        if (mode == EngineMode.ROOT && !s.rootWarningAccepted) {
            _uiState.update { it.copy(showRootWarningDialog = true, selectedMode = mode) }
        } else {
            _uiState.update { it.copy(selectedMode = mode) }
        }
        applyPresetOrContextLoad()
        val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
        _uiState.update { it.copy(modeStatuses = statuses, diagnostics = engineController.diagnostics) }
    }

    fun confirmRootWarning() {
        viewModelScope.launch {
            repository.setRootWarningAccepted(true)
        }
        _uiState.update { it.copy(showRootWarningDialog = false, rootWarningAccepted = true) }
    }

    fun dismissRootWarningAndFallback() {
        _uiState.update {
            it.copy(
                showRootWarningDialog = false,
                selectedMode = EngineMode.LEGACY
            )
        }
        applyPresetOrContextLoad()
    }

    fun selectPreset(preset: PresetConfig) {
        _uiState.update {
            it.copy(
                selectedPresetId = preset.id,
                gainsDb = preset.gainsDb,
                highPassHz = preset.highPassHz,
                bassHarmonicMixDb = preset.bassHarmonicMixDb,
                bassBoostStrength = preset.bassBoostStrength,
                virtualizerStrength = preset.virtualizerStrength,
                qOverrides = preset.qOverrides
            )
        }
        applyPresetOrContextLoad()
    }

    fun resetToFlat() {
        val flat = EqConstants.BUILT_IN_PRESETS.first { it.id == "flat" }
        selectPreset(flat)
    }

    /**
     * Continuous slider drag update: freezes `inputGainDb` and `makeupGainDb` (v4 Section 5.3 & 9).
     */
    fun updateBandGain(bandIndex: Int, newGainDb: Float) {
        val clamped = newGainDb.coerceIn(-12f, 12f)
        _uiState.update { state ->
            val currentVal = state.gainsDb.getOrElse(bandIndex) { 0f }
            if (abs(currentVal - clamped) < 0.01f) return@update state
            val updatedGains = state.gainsDb.toMutableList().apply {
                if (bandIndex in indices) this[bandIndex] = clamped
            }
            state.copy(
                selectedPresetId = "custom",
                gainsDb = updatedGains
            )
        }
        applySliderOrContourFast()
    }

    fun updateHighPassHz(freqHz: Float) {
        val clamped = freqHz.coerceIn(0f, 140f)
        _uiState.update { state ->
            state.copy(
                selectedPresetId = "custom",
                highPassHz = clamped
            )
        }
        applySliderOrContourFast()
    }

    fun selectHeadphoneProfile(profileId: String) {
        _uiState.update { it.copy(selectedHeadphoneProfileId = profileId) }
        applyPresetOrContextLoad()
    }

    fun updateHeadphoneStrength(strength: Float) {
        _uiState.update { it.copy(headphoneStrength = strength.coerceIn(0f, 1f)) }
        applyPresetOrContextLoad()
    }

    fun toggleSpeakerCalibration(enabled: Boolean) {
        val corr31 = if (enabled && speakerMeasArray.any { it != 0.0 }) {
            Dsp.computeSpeakerCorrection(
                speakerMeasArray,
                speakerValidArray,
                _uiState.value.speakerCalStrength.toDouble()
            )
        } else {
            DoubleArray(31)
        }
        _uiState.update {
            it.copy(
                speakerCalEnabled = enabled,
                speakerCorrection31Db = corr31.map { v -> v.toFloat() }
            )
        }
        viewModelScope.launch {
            repository.saveSpeakerCalibrationData(
                enabled = enabled,
                measDb = speakerMeasArray,
                valid = speakerValidArray,
                strength = _uiState.value.speakerCalStrength
            )
        }
        applyPresetOrContextLoad()
    }

    fun updateSpeakerCalStrength(strength: Float) {
        val clamped = strength.coerceIn(0f, 1f)
        val corr31 = if (_uiState.value.speakerCalEnabled && speakerMeasArray.any { it != 0.0 }) {
            Dsp.computeSpeakerCorrection(speakerMeasArray, speakerValidArray, clamped.toDouble())
        } else {
            DoubleArray(31)
        }
        _uiState.update {
            it.copy(
                speakerCalStrength = clamped,
                speakerCorrection31Db = corr31.map { v -> v.toFloat() }
            )
        }
        applyPresetOrContextLoad()
    }

    fun resetSpeakerCalibration() {
        speakerMeasArray = DoubleArray(31)
        speakerValidArray = BooleanArray(31)
        _uiState.update {
            it.copy(
                speakerCalEnabled = false,
                speakerMeas31Db = emptyList(),
                speakerCorrection31Db = emptyList(),
                calibrationStatusText = "Speaker calibration reset."
            )
        }
        viewModelScope.launch {
            repository.saveSpeakerCalibrationData(false, speakerMeasArray, speakerValidArray, 0.7f)
        }
        applyPresetOrContextLoad()
    }

    fun runSpeakerSelfCalibration() {
        if (_uiState.value.isCalibratingSpeaker) return
        _uiState.update {
            it.copy(
                isCalibratingSpeaker = true,
                calibrationProgress = 0.05f,
                calibrationStatusText = "Checking speaker route & safe volume (<= 60%)..."
            )
        }
        viewModelScope.launch {
            val res = SpeakerCalibrationRunner.runGuidedCalibration(
                audioManager = engineController.getAudioManager(),
                strength = _uiState.value.speakerCalStrength.toDouble(),
                onProgress = { prog, msg ->
                    _uiState.update {
                        it.copy(calibrationProgress = prog, calibrationStatusText = msg)
                    }
                }
            )
            res.fold(
                onSuccess = { cal ->
                    speakerMeasArray = cal.measured31Db
                    speakerValidArray = cal.validBands
                    val enabled = cal.passedVerification
                    repository.saveSpeakerCalibrationData(
                        enabled = enabled,
                        measDb = cal.measured31Db,
                        valid = cal.validBands,
                        strength = _uiState.value.speakerCalStrength
                    )
                    _uiState.update {
                        it.copy(
                            isCalibratingSpeaker = false,
                            speakerCalEnabled = enabled,
                            speakerMeas31Db = cal.measured31Db.map { v -> v.toFloat() },
                            speakerCorrection31Db = cal.correction31Db.map { v -> v.toFloat() },
                            calibrationStatusText = cal.message,
                            statusBannerMessage = cal.message
                        )
                    }
                    applyPresetOrContextLoad()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isCalibratingSpeaker = false,
                            calibrationProgress = 0f,
                            calibrationStatusText = err.message ?: "Calibration failed"
                        )
                    }
                }
            )
        }
    }

    fun setCompatibility10Band(enabled: Boolean) {
        _uiState.update { it.copy(compatibility10Band = enabled) }
        engineController.reconfigureEngineOptions(
            use10BandCompatibility = enabled,
            useTransientMode = _uiState.value.transientMode
        )
        val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
        _uiState.update { it.copy(modeStatuses = statuses, diagnostics = engineController.diagnostics) }
        scheduleDebouncedPersist(delayMs = 50L)
    }

    fun setTransientMode(enabled: Boolean) {
        _uiState.update { it.copy(transientMode = enabled) }
        engineController.reconfigureEngineOptions(
            use10BandCompatibility = _uiState.value.compatibility10Band,
            useTransientMode = enabled
        )
        val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
        _uiState.update { it.copy(modeStatuses = statuses, diagnostics = engineController.diagnostics) }
        scheduleDebouncedPersist(delayMs = 50L)
    }

    fun toggleReferenceMonitor() {
        if (dspMonitor.isPlaying) {
            dspMonitor.stop()
            _uiState.update { it.copy(isReferencePlaying = false) }
        } else {
            val s = _uiState.value
            val layers = buildCurrentLayers(s)
            syncReferenceMonitorDsp(s, layers, s.inputGainDb, s.makeupGainDb, s.selectedPresetId == "flat")
            dspMonitor.start { _ ->
                applyPresetOrContextLoad(persistNow = false)
            }
            _uiState.update { it.copy(isReferencePlaying = true) }
        }
    }

    fun selectReferenceSignal(signal: ReferenceSignalType) {
        dspMonitor.selectSignal(signal)
        _uiState.update { it.copy(referenceSignal = signal) }
    }

    fun openSavePresetDialog() {
        _uiState.update { it.copy(showSavePresetDialog = true) }
    }

    fun dismissSavePresetDialog() {
        _uiState.update { it.copy(showSavePresetDialog = false) }
    }

    fun saveCustomPreset(name: String) {
        val s = _uiState.value
        viewModelScope.launch {
            repository.saveCustomPreset(
                name = name,
                gainsDb = s.gainsDb,
                preampDb = s.inputGainDb,
                highPassHz = s.highPassHz,
                widths = SectionWidths(),
                bassBoostStrength = s.bassBoostStrength,
                virtualizerStrength = s.virtualizerStrength
            )
            _uiState.update {
                it.copy(
                    showSavePresetDialog = false,
                    statusBannerMessage = "Saved preset \"${name.trim().ifEmpty { "My Custom EQ" }}\""
                )
            }
        }
    }

    fun deleteCustomPreset(presetId: String) {
        viewModelScope.launch {
            repository.deleteCustomPreset(presetId)
            if (_uiState.value.selectedPresetId == presetId) {
                selectPreset(EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" })
            }
        }
    }

    fun openSettingsSheet() {
        val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
        _uiState.update {
            it.copy(
                showSettingsSheet = true,
                modeStatuses = statuses,
                diagnostics = engineController.diagnostics
            )
        }
    }

    fun dismissSettingsSheet() {
        _uiState.update { it.copy(showSettingsSheet = false) }
    }

    fun openCalibrationSheet() {
        _uiState.update { it.copy(showCalibrationSheet = true) }
    }

    fun dismissCalibrationSheet() {
        _uiState.update { it.copy(showCalibrationSheet = false) }
    }

    fun backupAndStageRootModule() {
        val result = engineController.backupAndStageMagiskModule()
        val msg = result.getOrElse { "Backup failed: ${it.message}" }
        _uiState.update {
            it.copy(
                diagnostics = engineController.diagnostics,
                statusBannerMessage = msg
            )
        }
    }

    fun restoreStockAudioConfig() {
        val result = engineController.restoreStockAudioConfig()
        val msg = result.getOrElse { "Restore failed: ${it.message}" }
        _uiState.update {
            it.copy(
                diagnostics = engineController.diagnostics,
                statusBannerMessage = msg
            )
        }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        dspMonitor.stop()
        engineController.releaseAll()
    }
}
