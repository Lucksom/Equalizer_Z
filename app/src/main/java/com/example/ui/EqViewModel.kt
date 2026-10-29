package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEngineController
import com.example.audio.DeviceDiagnostics
import com.example.audio.DspReferenceMonitor
import com.example.audio.ReferenceSignalType
import com.example.data.EqRepository
import com.example.model.CapabilityLevel
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.FrequencySection
import com.example.model.ModeStatusInfo
import com.example.model.PresetConfig
import com.example.model.SectionWidths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EqUiState(
    val isEnabled: Boolean = true,
    val selectedMode: EngineMode = EngineMode.GLOBAL,
    val modeStatuses: Map<EngineMode, ModeStatusInfo> = emptyMap(),
    val presets: List<PresetConfig> = EqConstants.BUILT_IN_PRESETS,
    val selectedPresetId: String = "percussion_clarity",
    val gainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val preampDb: Float = -2f,
    val highPassHz: Float = 40f,
    val widths: SectionWidths = SectionWidths(10, 100, 115, 125),
    val spatialStrength: Int = 200,
    val bassBoostStrength: Int = 100,
    val qOverrides: Map<Int, Float> = mapOf(250 to 1.0f, 4000 to 2.0f),
    val favorTimeResolution: Boolean = true,
    val responseCurvePoints: List<Pair<Float, Float>> = emptyList(),
    val isReferencePlaying: Boolean = false,
    val referenceSignal: ReferenceSignalType = ReferenceSignalType.TABLA_LOOP,
    val alwaysShowSectionWidths: Boolean = true,
    val rootWarningAccepted: Boolean = false,
    val showRootWarningDialog: Boolean = false,
    val showSavePresetDialog: Boolean = false,
    val showSettingsSheet: Boolean = false,
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

    val selectedPreset: PresetConfig?
        get() = presets.find { it.id == selectedPresetId }
}

class EqViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EqRepository(application)
    private val engineController = AudioEngineController(application)
    private val dspMonitor = DspReferenceMonitor(viewModelScope)

    private val _uiState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = _uiState.asStateFlow()

    private var initialLoadComplete = false

    init {
        val initialStatuses = engineController.probeCapabilities()
        _uiState.update { state ->
            state.copy(
                modeStatuses = initialStatuses,
                diagnostics = engineController.diagnostics,
                responseCurvePoints = recomputeCurve(
                    gainsDb = state.gainsDb,
                    preampDb = state.preampDb,
                    highPassHz = state.highPassHz,
                    widths = state.widths,
                    qOverrides = state.qOverrides
                )
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
                    val mode = EngineMode.entries.find { it.id == prefs.selectedModeId } ?: EngineMode.GLOBAL
                    val matchedPreset = allPresets.find { it.id == prefs.selectedPresetId }
                    val qOverrides = matchedPreset?.qOverrides ?: emptyMap()
                    val favorTime = matchedPreset?.favorTimeResolution ?: false

                    _uiState.update { current ->
                        current.copy(
                            isEnabled = prefs.isEnabled,
                            selectedMode = mode,
                            presets = allPresets,
                            selectedPresetId = prefs.selectedPresetId,
                            gainsDb = prefs.gainsDb,
                            preampDb = prefs.preampDb,
                            highPassHz = prefs.highPassHz,
                            widths = prefs.widths,
                            spatialStrength = prefs.spatialStrength,
                            bassBoostStrength = prefs.bassBoostStrength,
                            qOverrides = qOverrides,
                            favorTimeResolution = favorTime,
                            alwaysShowSectionWidths = prefs.alwaysShowSectionWidths,
                            rootWarningAccepted = prefs.rootWarningAccepted,
                            responseCurvePoints = recomputeCurve(
                                gainsDb = prefs.gainsDb,
                                preampDb = prefs.preampDb,
                                highPassHz = prefs.highPassHz,
                                widths = prefs.widths,
                                qOverrides = qOverrides
                            )
                        )
                    }
                    syncAudioEngine()
                } else {
                    _uiState.update { current ->
                        current.copy(
                            presets = allPresets,
                            alwaysShowSectionWidths = prefs.alwaysShowSectionWidths,
                            rootWarningAccepted = prefs.rootWarningAccepted
                        )
                    }
                }
            }
        }

        // Periodic session scanner loop (every 3 seconds when ADB/Shizuku or Global mode is active)
        viewModelScope.launch {
            while (isActive) {
                delay(3000L)
                val state = _uiState.value
                if (state.isEnabled && (state.selectedMode == EngineMode.ADB_SHIZUKU || state.selectedMode == EngineMode.GLOBAL)) {
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

    private fun recomputeCurve(
        gainsDb: List<Float>,
        preampDb: Float,
        highPassHz: Float,
        widths: SectionWidths,
        qOverrides: Map<Int, Float>
    ): List<Pair<Float, Float>> {
        dspMonitor.updateDspState(
            gainsDb = gainsDb,
            preampDb = preampDb,
            highPassHz = highPassHz,
            widths = widths,
            qOverrides = qOverrides,
            bypassed = !_uiState.value.isEnabled
        )
        return dspMonitor.processor.computeFrequencyResponseCurve(96)
    }

    private fun syncAudioEngine() {
        val s = _uiState.value
        val curve = recomputeCurve(
            gainsDb = s.gainsDb,
            preampDb = s.preampDb,
            highPassHz = s.highPassHz,
            widths = s.widths,
            qOverrides = s.qOverrides
        )
        engineController.syncSessionsAndApply(
            mode = s.selectedMode,
            enabled = s.isEnabled,
            gainsDb = s.gainsDb,
            preampDb = s.preampDb,
            highPassHz = s.highPassHz,
            widths = s.widths,
            bassBoostStrength = s.bassBoostStrength,
            virtualizerStrength = s.spatialStrength,
            favorTimeResolution = s.favorTimeResolution,
            internalSessionId = dspMonitor.getAudioSessionId()
        )
        val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
        _uiState.update {
            it.copy(
                responseCurvePoints = curve,
                modeStatuses = statuses,
                diagnostics = engineController.diagnostics
            )
        }
        persistCurrentState()
    }

    private fun persistCurrentState() {
        val s = _uiState.value
        viewModelScope.launch {
            repository.saveState(
                isEnabled = s.isEnabled,
                modeId = s.selectedMode.id,
                presetId = s.selectedPresetId,
                gainsDb = s.gainsDb,
                preampDb = s.preampDb,
                highPassHz = s.highPassHz,
                widths = s.widths,
                spatialStrength = s.spatialStrength,
                bassBoostStrength = s.bassBoostStrength
            )
        }
    }

    fun setPowerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isEnabled = enabled) }
        syncAudioEngine()
    }

    fun selectMode(mode: EngineMode) {
        val s = _uiState.value
        if (mode == EngineMode.ROOT && !s.rootWarningAccepted) {
            _uiState.update { it.copy(showRootWarningDialog = true, selectedMode = mode) }
        } else {
            _uiState.update { it.copy(selectedMode = mode) }
        }
        syncAudioEngine()
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
                selectedMode = EngineMode.GLOBAL
            )
        }
        syncAudioEngine()
    }

    fun selectPreset(preset: PresetConfig) {
        _uiState.update {
            it.copy(
                selectedPresetId = preset.id,
                gainsDb = preset.gainsDb,
                preampDb = preset.preampDb,
                highPassHz = preset.highPassHz,
                widths = preset.widths,
                spatialStrength = preset.virtualizerStrength,
                bassBoostStrength = preset.bassBoostStrength,
                qOverrides = preset.qOverrides,
                favorTimeResolution = preset.favorTimeResolution
            )
        }
        syncAudioEngine()
    }

    fun updateBandGain(bandIndex: Int, newGainDb: Float) {
        val clamped = (kotlin.math.round(newGainDb * 2f) / 2f).coerceIn(-12f, 12f)
        _uiState.update { state ->
            val updatedGains = state.gainsDb.toMutableList().apply {
                if (bandIndex in indices) this[bandIndex] = clamped
            }
            val autoPreamp = EqConstants.calculateAutoPreamp(updatedGains)
            state.copy(
                selectedPresetId = "custom",
                gainsDb = updatedGains,
                preampDb = autoPreamp
            )
        }
        syncAudioEngine()
    }

    fun updateSectionWidth(section: FrequencySection, widthPct: Int) {
        val clamped = widthPct.coerceIn(0, 200)
        _uiState.update { state ->
            val newWidths = state.widths.withSection(section, clamped)
            // Also keep Virtualizer spatial strength proportional to average high/mid width for non-Root hardware sync
            val avgWidth = (newWidths.midsWidthPct + newWidths.trebleWidthPct + newWidths.airWidthPct) / 3
            val mappedSpatial = ((avgWidth - 80).coerceAtLeast(0) * 8).coerceIn(0, 1000)
            state.copy(
                selectedPresetId = "custom",
                widths = newWidths,
                spatialStrength = mappedSpatial
            )
        }
        syncAudioEngine()
    }

    fun updateSpatialStrength(strengthPermille: Int) {
        val clamped = strengthPermille.coerceIn(0, 1000)
        _uiState.update { state ->
            state.copy(
                selectedPresetId = "custom",
                spatialStrength = clamped
            )
        }
        syncAudioEngine()
    }

    fun updateHighPassHz(freqHz: Float) {
        val clamped = freqHz.coerceIn(0f, 120f)
        _uiState.update { state ->
            state.copy(
                selectedPresetId = "custom",
                highPassHz = clamped
            )
        }
        syncAudioEngine()
    }

    fun toggleReferenceMonitor() {
        if (dspMonitor.isPlaying) {
            dspMonitor.stop()
            _uiState.update { it.copy(isReferencePlaying = false) }
        } else {
            val s = _uiState.value
            dspMonitor.updateDspState(
                gainsDb = s.gainsDb,
                preampDb = s.preampDb,
                highPassHz = s.highPassHz,
                widths = s.widths,
                qOverrides = s.qOverrides,
                bypassed = !s.isEnabled
            )
            dspMonitor.start { sessionId ->
                engineController.syncSessionsAndApply(
                    mode = _uiState.value.selectedMode,
                    enabled = _uiState.value.isEnabled,
                    gainsDb = _uiState.value.gainsDb,
                    preampDb = _uiState.value.preampDb,
                    highPassHz = _uiState.value.highPassHz,
                    widths = _uiState.value.widths,
                    bassBoostStrength = _uiState.value.bassBoostStrength,
                    virtualizerStrength = _uiState.value.spatialStrength,
                    favorTimeResolution = _uiState.value.favorTimeResolution,
                    internalSessionId = sessionId
                )
            }
            _uiState.update { it.copy(isReferencePlaying = true) }
        }
    }

    fun selectReferenceSignal(signal: ReferenceSignalType) {
        dspMonitor.selectSignal(signal)
        _uiState.update { it.copy(referenceSignal = signal) }
    }

    fun setAlwaysShowSectionWidths(alwaysShow: Boolean) {
        viewModelScope.launch {
            repository.setAlwaysShowSectionWidths(alwaysShow)
        }
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
                preampDb = s.preampDb,
                highPassHz = s.highPassHz,
                widths = s.widths,
                bassBoostStrength = s.bassBoostStrength,
                virtualizerStrength = s.spatialStrength
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
