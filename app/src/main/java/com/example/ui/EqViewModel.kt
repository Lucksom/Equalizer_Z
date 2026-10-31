package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEngineController
import com.example.audio.AutoEq
import com.example.audio.AutoEqRepo
import com.example.audio.BoseProfile
import com.example.audio.DaxProfile
import com.example.audio.DeviceDiagnostics
import com.example.audio.DolbyBoseDspConfig
import com.example.audio.DolbyBoseDspMath
import com.example.audio.Dsp
import com.example.audio.DspReferenceMonitor
import com.example.audio.EqMath
import com.example.audio.HearingTonePlayer
import com.example.audio.IeqPreset
import com.example.audio.IndexEntry
import com.example.audio.Layers
import com.example.audio.ReferenceSignalType
import com.example.audio.SpeakerCalibrationRunner
import com.example.audio.buildCurrentDeviceKey
import com.example.data.EqRepository
import com.example.shizuku.ShizukuManager
import com.example.shizuku.ShizukuState
import com.example.model.CapabilityLevel
import com.example.model.DeviceProfileMemory
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.FilterType
import com.example.model.HeadphoneProfile
import com.example.model.ModeStatusInfo
import com.example.model.OutputRoute
import com.example.model.ParametricCorrectionFilter
import com.example.model.PersonalHearingCurve
import com.example.model.PresetConfig
import com.example.model.SectionWidths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class EqUiState(
    val isEnabled: Boolean = true,
    val isAbCompare: Boolean = false,
    val selectedMode: EngineMode = EngineMode.LEGACY,
    val selectedDaxProfile: DaxProfile = DaxProfile.MUSIC, // v6 Section 4 Acoustic Profile (0=Dynamic, 1=Movie, 2=Music, 3=Voice)
    val dolbyBoseConfig: DolbyBoseDspConfig = DolbyBoseDspConfig(),
    val modeStatuses: Map<EngineMode, ModeStatusInfo> = emptyMap(),
    val presets: List<PresetConfig> = EqConstants.BUILT_IN_PRESETS,
    val selectedPresetId: String = "percussion_clarity",
    val gainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val rightGainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val splitLREnabled: Boolean = false, // F4 Independent L/R EQ
    val editingRightChannel: Boolean = false, // F4 Which channel the 10 sliders currently edit
    val balanceDb: Float = 0.0f, // F4 L/R Balance (-6..+6 dB)
    val intensity: Float = 1.0f, // 0.5 .. 1.5 (Light 0.6, Standard 1.0, Deep+ 1.3)
    val volumeContourEnabled: Boolean = true,
    val nightModeEnabled: Boolean = false, // F8 Night mode ceilings
    val startOnBoot: Boolean = false, // F12 Start on boot
    val usbBitPerfectEnabled: Boolean = false, // F7 Android 14+ USB DAC bit-perfect
    val currentVolumeRatio: Float = 0.6f,
    val activeRoute: OutputRoute = OutputRoute.SPEAKER,
    val activeDeviceKey: String = "",
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
    val headphoneFullRange: Boolean = false, // Section 16.4 Full range (+10 dB cap)
    val headphoneTrimmedDb: Float = 0.0f, // Section 16.4 Trimmed by X dB notice
    val tiltBassDb: Float = 0.0f, // Section 16.4 & F3 Bass tilt (-6..+6 dB)
    val tiltTrebleDb: Float = 0.0f, // Section 16.4 & F3 Treble tilt (-4..+4 dB)
    val autoEqCatalog: List<IndexEntry> = emptyList(),
    val autoEqLoading: Boolean = false,
    val autoEqRateLimited: Boolean = false,
    val autoEqOffline: Boolean = false,
    val customAutoEqName: String = "",
    val customAutoEqSource: String = "",
    val customAutoEqPath: String = "",
    val customAutoEqRawText: String = "",
    val deviceMemories: Map<String, DeviceProfileMemory> = emptyMap(), // F2
    val speakerCalEnabled: Boolean = false,
    val speakerCalStrength: Float = 0.7f,
    val speakerMeas31Db: List<Float> = emptyList(),
    val speakerCorrection31Db: List<Float> = emptyList(),
    val isCalibratingSpeaker: Boolean = false,
    val calibrationProgress: Float = 0f,
    val calibrationStatusText: String = "",
    val hearingCurve: PersonalHearingCurve = PersonalHearingCurve(), // F5
    val compatibility10Band: Boolean = false,
    val transientMode: Boolean = false,
    val shizukuState: ShizukuState = ShizukuState.NOT_INSTALLED,
    val shizukuPrivilege: String = "none",
    val isReferencePlaying: Boolean = false,
    val referenceSignal: ReferenceSignalType = ReferenceSignalType.TABLA_LOOP,
    val rootWarningAccepted: Boolean = false,
    val showRootWarningDialog: Boolean = false,
    val showSavePresetDialog: Boolean = false,
    val showSettingsSheet: Boolean = false,
    val showCalibrationSheet: Boolean = false,
    val showAbxSheet: Boolean = false, // F6 Blind ABX Tester
    val abxListeningChoice: String = "B", // "A" (Bypass), "B" (EQ On), "X" (Hidden)
    val abxHiddenXIsB: Boolean = true,
    val abxCorrectCount: Int = 0,
    val abxTrialCount: Int = 0,
    val abxLastResultText: String? = null,
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

    val activeSlidersGainsDb: List<Float>
        get() = if (splitLREnabled && editingRightChannel) rightGainsDb else gainsDb

    val dax20BandInt: List<Int>
        get() = EqMath.sliders10ToDax20Int(
            DoubleArray(10) { activeSlidersGainsDb.getOrElse(it) { 0f }.toDouble() }
        ).toList()

    val isCeilingWarning: Boolean
        get() = ceilingLoadDb > 4.0f
}

class EqViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EqRepository(application)
    private val engineController = AudioEngineController(application)
    private val dspMonitor = DspReferenceMonitor(viewModelScope)
    private val autoEqRepo = AutoEqRepo(File(application.filesDir, "autoeq_cache"))

    private val _uiState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = _uiState.asStateFlow()

    private var initialLoadComplete = false
    private var persistDebounceJob: Job? = null

    // Smoothed contour offsets (max 1 dB step per 500 ms per v5 Section 7)
    private val currentContourOffsets = DoubleArray(10)
    private var speakerMeasArray = DoubleArray(31)
    private var speakerValidArray = BooleanArray(31)

    init {
        val initialStatuses = engineController.probeCapabilities()
        val initialRoute = engineController.getCurrentRoute()
        val initialVol = engineController.getCurrentVolumeRatio()
        val initialDevKey = buildCurrentDeviceKey(engineController.getAudioManager())

        _uiState.update { state ->
            state.copy(
                modeStatuses = initialStatuses,
                activeRoute = initialRoute,
                activeDeviceKey = initialDevKey,
                currentVolumeRatio = initialVol.toFloat(),
                shizukuState = ShizukuManager.state.value,
                shizukuPrivilege = ShizukuManager.privilege(),
                diagnostics = engineController.diagnostics
            )
        }

        // Observe Shizuku state transitions and auto-grant DUMP when Shizuku is READY
        viewModelScope.launch {
            ShizukuManager.state.collect { shizukuSt ->
                val priv = ShizukuManager.privilege()
                if (shizukuSt == ShizukuState.READY) {
                    val pkg = getApplication<Application>().packageName
                    ShizukuManager.exec("pm grant $pkg android.permission.DUMP; pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
                }
                val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
                _uiState.update {
                    it.copy(
                        shizukuState = shizukuSt,
                        shizukuPrivilege = priv,
                        modeStatuses = statuses,
                        diagnostics = engineController.diagnostics
                    )
                }
            }
        }

        // Load AutoEq index on IO dispatcher
        refreshAutoEqCatalog(force = false)

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
                    val daxProf = DaxProfile.fromId(prefs.selectedProfileId)
                    val matchedPreset = allPresets.find { it.id == prefs.selectedPresetId }
                    val qOverrides = matchedPreset?.qOverrides ?: emptyMap()
                    val harmMix = matchedPreset?.bassHarmonicMixDb

                    val parsedMeas = prefs.speakerMeas31Csv.split(",").mapNotNull { it.toDoubleOrNull() }
                    val parsedValid = prefs.speakerValid31Csv.split(",").map { it == "1" }
                    if (parsedMeas.size == 31 && parsedValid.size == 31) {
                        speakerMeasArray = parsedMeas.toDoubleArray()
                        speakerValidArray = parsedValid.toBooleanArray()
                    }
                    val spkCorr31 = if (prefs.speakerCalEnabled && parsedMeas.size == 31) {
                        EqMath.computeSpeakerCalibration31(speakerMeasArray, speakerValidArray, prefs.speakerCalStrength.toDouble())
                    } else {
                        DoubleArray(31)
                    }

                    val profilesList = buildHeadphoneProfilesList(
                        customName = prefs.customAutoEqName,
                        customSource = prefs.customAutoEqSource,
                        customPath = prefs.customAutoEqPath,
                        customRaw = prefs.customAutoEqRawText
                    )

                    engineController.reconfigureEngineOptions(
                        use10BandCompatibility = prefs.compatibility10Band,
                        useTransientMode = prefs.transientMode
                    )
                    if (prefs.usbBitPerfectEnabled) {
                        engineController.setUsbBitPerfectMode(true)
                    }

                    _uiState.update { current ->
                        current.copy(
                            isEnabled = prefs.isEnabled,
                            selectedMode = mode,
                            selectedDaxProfile = daxProf,
                            dolbyBoseConfig = prefs.dolbyBoseConfig,
                            presets = allPresets,
                            selectedPresetId = prefs.selectedPresetId,
                            gainsDb = prefs.gainsDb,
                            rightGainsDb = prefs.rightGainsDb ?: prefs.gainsDb,
                            splitLREnabled = prefs.splitLREnabled,
                            balanceDb = prefs.balanceDb,
                            highPassHz = prefs.highPassHz,
                            bassHarmonicMixDb = harmMix,
                            bassBoostStrength = matchedPreset?.bassBoostStrength ?: 100,
                            virtualizerStrength = matchedPreset?.virtualizerStrength ?: 200,
                            qOverrides = qOverrides,
                            intensity = prefs.intensity,
                            volumeContourEnabled = prefs.volumeContourEnabled,
                            nightModeEnabled = prefs.nightModeEnabled,
                            startOnBoot = prefs.startOnBoot,
                            usbBitPerfectEnabled = prefs.usbBitPerfectEnabled,
                            headphoneProfiles = profilesList,
                            selectedHeadphoneProfileId = prefs.selectedHeadphoneProfileId,
                            headphoneStrength = prefs.headphoneStrength,
                            headphoneFullRange = prefs.headphoneFullRange,
                            tiltBassDb = prefs.tiltBassDb,
                            tiltTrebleDb = prefs.tiltTrebleDb,
                            customAutoEqName = prefs.customAutoEqName,
                            customAutoEqSource = prefs.customAutoEqSource,
                            customAutoEqPath = prefs.customAutoEqPath,
                            customAutoEqRawText = prefs.customAutoEqRawText,
                            speakerCalEnabled = prefs.speakerCalEnabled,
                            speakerCalStrength = prefs.speakerCalStrength,
                            speakerMeas31Db = speakerMeasArray.map { it.toFloat() },
                            speakerCorrection31Db = spkCorr31.map { it.toFloat() },
                            hearingCurve = prefs.hearingCurve,
                            deviceMemories = prefs.deviceMemories,
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
                            deviceMemories = prefs.deviceMemories,
                            rootWarningAccepted = prefs.rootWarningAccepted
                        )
                    }
                }
            }
        }

        // Volume Contour, Output Route & Per-Device Memory polling loop (v5 Section 6, 7 & 16.6)
        viewModelScope.launch {
            var tickCounter = 0
            while (isActive) {
                delay(280L) // < 300 ms route & per-device memory response (v5 Section 14 & 19)
                tickCounter++
                val s = _uiState.value
                val newRoute = engineController.getCurrentRoute()
                val newDevKey = buildCurrentDeviceKey(engineController.getAudioManager())
                val volRatio = engineController.getCurrentVolumeRatio()
                val routeOrDeviceChanged = newRoute != s.activeRoute || newDevKey != s.activeDeviceKey

                // Step volume contour at most 1.0 dB per ~500 ms (not applied in Flat)
                var contourMoved = false
                if (tickCounter % 2 == 0) {
                    val isFlat = s.selectedPresetId == "flat"
                    val targetContour = if (s.volumeContourEnabled && !isFlat) {
                        Dsp.contour(volRatio)
                    } else {
                        DoubleArray(10)
                    }
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
                }

                if (routeOrDeviceChanged) {
                    // Check Per-Device Profile Memory (v5 Section 16.6 & 17.2 F2)
                    val savedMem = s.deviceMemories[newDevKey]
                    if (savedMem != null) {
                        val updatedProfiles = buildHeadphoneProfilesList(
                            customName = savedMem.profileName,
                            customSource = savedMem.profileSource,
                            customPath = savedMem.autoEqPath,
                            customRaw = savedMem.rawPeqOrGraphicText
                        )
                        val matchedPreset = s.presets.find { it.id == savedMem.presetId }
                        val restoredDax = DaxProfile.fromId(savedMem.daxProfileId)
                        _uiState.update { cur ->
                            cur.copy(
                                activeRoute = newRoute,
                                activeDeviceKey = newDevKey,
                                currentVolumeRatio = volRatio.toFloat(),
                                selectedDaxProfile = restoredDax,
                                headphoneProfiles = updatedProfiles,
                                selectedHeadphoneProfileId = savedMem.profileId,
                                headphoneStrength = savedMem.strength,
                                headphoneFullRange = savedMem.fullRange,
                                tiltBassDb = savedMem.tiltBassDb,
                                tiltTrebleDb = savedMem.tiltTrebleDb,
                                customAutoEqName = savedMem.profileName,
                                customAutoEqSource = savedMem.profileSource,
                                customAutoEqPath = savedMem.autoEqPath,
                                customAutoEqRawText = savedMem.rawPeqOrGraphicText,
                                selectedPresetId = matchedPreset?.id ?: cur.selectedPresetId,
                                gainsDb = matchedPreset?.gainsDb ?: cur.gainsDb,
                                intensity = savedMem.intensity,
                                statusBannerMessage = "Restored profile for ${savedMem.productName}"
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                activeRoute = newRoute,
                                activeDeviceKey = newDevKey,
                                currentVolumeRatio = volRatio.toFloat()
                            )
                        }
                    }
                    applyPresetOrContextLoad(persistNow = true)
                } else if (contourMoved || abs(volRatio.toFloat() - s.currentVolumeRatio) > 0.02f) {
                    _uiState.update { it.copy(currentVolumeRatio = volRatio.toFloat()) }
                    if (contourMoved) {
                        applySliderOrContourFast(persistNow = false)
                    }
                }

                if (tickCounter % 10 == 0 && s.isEnabled) {
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

    private fun buildHeadphoneProfilesList(
        customName: String,
        customSource: String,
        customPath: String,
        customRaw: String
    ): List<HeadphoneProfile> {
        val base = EqConstants.HEADPHONE_PROFILES.toMutableList()
        if (customName.isNotBlank() && customRaw.isNotBlank()) {
            val peq = AutoEq.parsePeq(customRaw)
            val isGraphic = peq.filters.isEmpty() && AutoEq.parseGraphic(customRaw).isNotEmpty()
            val mappedFilters = peq.filters.map { f ->
                val ft = when (f.type) {
                    "LSC" -> FilterType.LOW_SHELF
                    "HSC" -> FilterType.HIGH_SHELF
                    else -> FilterType.PEAKING
                }
                ParametricCorrectionFilter(ft, f.fc, f.gainDb, f.q)
            }
            val customProfile = HeadphoneProfile(
                id = "autoeq_active",
                name = customName,
                category = "over_ear",
                source = customSource.ifBlank { "AutoEq (MIT)" },
                license = "MIT",
                preampDb = peq.preampDb.toFloat(),
                filters = mappedFilters,
                autoEqPath = customPath,
                rawText = customRaw,
                isApproximation = isGraphic
            )
            base.add(1, customProfile)
        }
        return base
    }

    fun refreshAutoEqCatalog(force: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(autoEqLoading = true) }
            val entries = autoEqRepo.index(force = force)
            _uiState.update {
                it.copy(
                    autoEqCatalog = entries,
                    autoEqLoading = false,
                    autoEqRateLimited = autoEqRepo.isRateLimited,
                    autoEqOffline = autoEqRepo.isOfflineFallback
                )
            }
        }
    }

    fun selectAutoEqCatalogEntry(entry: IndexEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(autoEqLoading = true, calibrationStatusText = "Loading ${entry.name}...") }
            val s = _uiState.value
            val detailed = autoEqRepo.loadDetailed(
                e = entry,
                strength = s.headphoneStrength.toDouble(),
                fullRange = s.headphoneFullRange,
                tiltBassDb = s.tiltBassDb.toDouble(),
                tiltTrebleDb = s.tiltTrebleDb.toDouble()
            )
            if (detailed != null) {
                val srcLabel = buildString {
                    append("AutoEq")
                    entry.source?.let { append(" · $it") }
                    entry.rig?.let { append(" ($it)") }
                    if (detailed.format.isApproximation) append(" · Graphic EQ approx")
                }
                val profiles = buildHeadphoneProfilesList(
                    customName = entry.name,
                    customSource = srcLabel,
                    customPath = entry.path,
                    customRaw = detailed.rawText
                )
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val filtersJson = detailed.filters.joinToString(prefix = "[", postfix = "]") { f ->
                            String.format(
                                Locale.US,
                                """{"type":"%s","fc":%.1f,"gainDb":%.2f,"q":%.2f}""",
                                f.type,
                                f.fc,
                                f.gainDb,
                                f.q
                            )
                        }
                        repository.sqliteHelper.cacheAutoEqProfile(
                            modelId = entry.path,
                            modelName = entry.name,
                            sourceName = entry.source ?: "AutoEq",
                            rigName = entry.rig,
                            preampDb = detailed.preampDb,
                            filtersJson = filtersJson,
                            correction31 = detailed.correction31
                        )
                    } catch (_: Throwable) {
                    }
                }
                _uiState.update {
                    it.copy(
                        autoEqLoading = false,
                        autoEqRateLimited = autoEqRepo.isRateLimited,
                        autoEqOffline = autoEqRepo.isOfflineFallback,
                        headphoneProfiles = profiles,
                        selectedHeadphoneProfileId = "autoeq_active",
                        customAutoEqName = entry.name,
                        customAutoEqSource = srcLabel,
                        customAutoEqPath = entry.path,
                        customAutoEqRawText = detailed.rawText,
                        headphoneTrimmedDb = detailed.trimmedDb.toFloat(),
                        calibrationStatusText = "Applied ${entry.name} (${detailed.format.label})",
                        statusBannerMessage = "Loaded AutoEq profile: ${entry.name}"
                    )
                }
                saveCurrentDeviceMemory()
                applyPresetOrContextLoad()
            } else {
                _uiState.update {
                    it.copy(
                        autoEqLoading = false,
                        calibrationStatusText = "Could not load profile for ${entry.name}"
                    )
                }
            }
        }
    }

    /**
     * Imports user-pasted ParametricEQ or GraphicEQ text (v5 Section 16.6 & 17.2 F9).
     */
    fun importCustomAutoEqText(name: String, text: String) {
        val trimmedText = text.trim()
        val peq = AutoEq.parsePeq(trimmedText)
        val graphic = AutoEq.parseGraphic(trimmedText)
        if (peq.filters.isEmpty() && graphic.size < 4) {
            _uiState.update {
                it.copy(statusBannerMessage = "Invalid format: Paste EqualizerAPO ParametricEQ filters or a 'GraphicEQ:' line.")
            }
            return
        }
        val cleanName = name.trim().ifEmpty { "Imported EQ Profile" }
        val src = if (peq.filters.isNotEmpty()) "Imported ParametricEQ (${peq.filters.size} filters)" else "Imported GraphicEQ (${graphic.size} pts)"
        val profiles = buildHeadphoneProfilesList(
            customName = cleanName,
            customSource = src,
            customPath = "imported",
            customRaw = trimmedText
        )
        _uiState.update {
            it.copy(
                headphoneProfiles = profiles,
                selectedHeadphoneProfileId = "autoeq_active",
                customAutoEqName = cleanName,
                customAutoEqSource = src,
                customAutoEqPath = "imported",
                customAutoEqRawText = trimmedText,
                statusBannerMessage = "Imported profile \"$cleanName\""
            )
        }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    /**
     * Exports the active headphone correction as ParametricEQ or GraphicEQ text (v5 Section 16.6 & 17.2 F9).
     */
    fun exportActiveCorrectionText(): String {
        val s = _uiState.value
        val prof = s.selectedHeadphoneProfile
        if (!prof.rawText.isNullOrBlank()) return prof.rawText
        if (prof.filters.isNotEmpty()) {
            return AutoEq.exportToParametricText(
                preampDb = prof.preampDb.toDouble(),
                filters = AutoEq.fromBuiltInProfile(prof)
            )
        }
        val layers = buildCurrentLayers(s)
        return AutoEq.exportCorrection31ToGraphicText(layers.correction31)
    }

    private fun saveCurrentDeviceMemory() {
        val s = _uiState.value
        if (s.activeRoute == OutputRoute.SPEAKER) return
        val prodName = s.diagnostics.connectedProductName ?: s.activeRoute.label
        val prof = s.selectedHeadphoneProfile
        val mem = DeviceProfileMemory(
            deviceKey = s.activeDeviceKey.ifBlank { buildCurrentDeviceKey(engineController.getAudioManager()) },
            routeId = s.activeRoute.id,
            productName = prodName,
            profileId = s.selectedHeadphoneProfileId,
            profileName = prof.name,
            profileSource = prof.source,
            autoEqPath = prof.autoEqPath ?: "",
            rawPeqOrGraphicText = prof.rawText ?: exportActiveCorrectionText(),
            strength = s.headphoneStrength,
            fullRange = s.headphoneFullRange,
            presetId = s.selectedPresetId,
            intensity = s.intensity,
            tiltBassDb = s.tiltBassDb,
            tiltTrebleDb = s.tiltTrebleDb,
            daxProfileId = s.selectedDaxProfile.id
        )
        viewModelScope.launch {
            repository.saveDeviceMemory(
                memory = mem,
                activeProfileId = s.selectedDaxProfile.id,
                volumeContourOn = s.volumeContourEnabled
            )
        }
    }

    private fun buildCurrentLayers(state: EqUiState): Layers {
        val userDb = DoubleArray(10) { i -> state.gainsDb.getOrElse(i) { 0f }.toDouble() }
        val rightUserDb = if (state.splitLREnabled) {
            DoubleArray(10) { i -> state.rightGainsDb.getOrElse(i) { 0f }.toDouble() }
        } else null
        val isFlat = state.selectedPresetId == "flat"
        val intensity = if (isFlat) 1.0 else state.intensity.toDouble()

        // v5 Section 6: If speaker calibration is active on Speaker route, use zero route offsets
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

        val hasTilt = abs(state.tiltBassDb) > 0.01f || abs(state.tiltTrebleDb) > 0.01f
        val correction31 = when {
            isFlat -> DoubleArray(31)
            state.activeRoute == OutputRoute.SPEAKER && state.speakerCalEnabled && state.speakerCorrection31Db.size == 31 -> {
                DoubleArray(31) { state.speakerCorrection31Db[it].toDouble() }
            }
            state.activeRoute != OutputRoute.SPEAKER && (state.selectedHeadphoneProfileId != "none" || hasTilt) -> {
                val prof = if (state.selectedHeadphoneProfileId != "none") state.selectedHeadphoneProfile else null
                Dsp.computeHeadphoneCorrection31(
                    profile = prof,
                    strength = state.headphoneStrength.toDouble(),
                    fullRange = state.headphoneFullRange,
                    tiltBassDb = state.tiltBassDb.toDouble(),
                    tiltTrebleDb = state.tiltTrebleDb.toDouble()
                )
            }
            hasTilt -> {
                Dsp.computeHeadphoneCorrection31(
                    profile = null,
                    strength = state.headphoneStrength.toDouble(),
                    fullRange = state.headphoneFullRange,
                    tiltBassDb = state.tiltBassDb.toDouble(),
                    tiltTrebleDb = state.tiltTrebleDb.toDouble()
                )
            }
            else -> DoubleArray(31)
        }

        val hearingLeft31 = if (!isFlat && state.hearingCurve.enabled) {
            Dsp.computeHearingCurve31(state.hearingCurve.leftEarDb, state.hearingCurve.strength)
        } else {
            DoubleArray(31)
        }
        val hearingRight31 = if (!isFlat && state.hearingCurve.enabled) {
            Dsp.computeHearingCurve31(state.hearingCurve.rightEarDb, state.hearingCurve.strength)
        } else {
            DoubleArray(31)
        }

        val dolbyBose10 = DolbyBoseDspMath.computeComposite10BandDeltaDb(
            config = state.dolbyBoseConfig,
            userCustom10Db = userDb
        )

        return Layers(
            userDb = userDb,
            intensity = intensity,
            routeDb = routeDb,
            contourDb = contourDb,
            correction31 = correction31,
            balanceDb = if (isFlat) 0f else state.balanceDb,
            rightUserDb = rightUserDb,
            hearingLeft31 = hearingLeft31,
            hearingRight31 = hearingRight31,
            dolbyBose10Db = dolbyBose10
        )
    }

    /**
     * Called on preset, route, headphone profile, speaker calibration, or intensity COMMIT.
     * Recomputes `inputGainDb` and `makeupGainDb` and glides 250 ms.
     */
    private fun applyPresetOrContextLoad(persistNow: Boolean = true) {
        val s = _uiState.value
        val layers = buildCurrentLayers(s)

        val trimmedDb = if (s.selectedHeadphoneProfileId != "none" || abs(s.tiltBassDb) > 0.01f || abs(s.tiltTrebleDb) > 0.01f) {
            val prof = if (s.selectedHeadphoneProfileId != "none") s.selectedHeadphoneProfile else null
            Dsp.computeHeadphoneTrimmedDb(
                profile = prof,
                fullRange = s.headphoneFullRange,
                tiltBassDb = s.tiltBassDb.toDouble(),
                tiltTrebleDb = s.tiltTrebleDb.toDouble()
            ).toFloat()
        } else 0f

        val matchedPreset = s.presets.find { it.id == s.selectedPresetId }
        val mbc = EqConstants.mbcForPreset(matchedPreset, s.nightModeEnabled)
        val flatBypass = matchedPreset?.bypassChain == true
        val tier1Native5 = matchedPreset?.legacyNativeDb?.toFloatArray()
            ?: EqConstants.convert10BandTo5BandNative(s.gainsDb).toFloatArray()

        engineController.loadEngineState(
            mode = s.selectedMode,
            enabled = s.isEnabled,
            abCompare = s.isAbCompare,
            daxProfile = s.selectedDaxProfile,
            dolbyBoseConfig = s.dolbyBoseConfig,
            layers = layers,
            mbc = mbc,
            routeBaseDb = s.activeRoute.baseInputGainDb,
            limiterDb = s.activeRoute.limiterThresholdDb,
            flatBypass = flatBypass,
            tier1Native5Db = tier1Native5,
            bassBoostStrength = s.bassBoostStrength,
            virtualizerStrength = s.virtualizerStrength,
            internalSessionId = dspMonitor.getAudioSessionId()
        )

        val inGain = engineController.currentInputGainDb
        val makeupGain = engineController.currentMakeupGainDb
        val ceilingLoad = engineController.currentCeilingLoadDb

        syncReferenceMonitorDsp(s, layers, inGain, makeupGain, flatBypass && s.selectedDaxProfile == DaxProfile.MUSIC)

        _uiState.update {
            it.copy(
                inputGainDb = inGain,
                makeupGainDb = makeupGain,
                ceilingLoadDb = ceilingLoad,
                headphoneTrimmedDb = trimmedDb
            )
        }

        if (persistNow) {
            scheduleDebouncedPersist(delayMs = 80L)
        }
    }

    /**
     * Fast path for continuous slider drags and volume contour steps:
     * Freezes `inputGainDb`, `makeupGainDb`, and MBC parameters, glides 60 ms, and never calls `probeCapabilities()`.
     */
    private fun applySliderOrContourFast(persistNow: Boolean = true) {
        val s = _uiState.value
        val layers = buildCurrentLayers(s)
        val flatBypass = s.selectedPresetId == "flat"
        val tier1Native5 = EqConstants.convert10BandTo5BandNative(s.gainsDb).toFloatArray()

        engineController.updateSlidersOrContourFast(
            enabled = s.isEnabled,
            abCompare = s.isAbCompare,
            daxProfile = s.selectedDaxProfile,
            dolbyBoseConfig = s.dolbyBoseConfig,
            layers = layers,
            flatBypass = flatBypass,
            tier1Native5Db = tier1Native5,
            internalSessionId = dspMonitor.getAudioSessionId()
        )

        val ceilingLoad = engineController.currentCeilingLoadDb

        syncReferenceMonitorDsp(s, layers, s.inputGainDb, s.makeupGainDb, flatBypass && s.selectedDaxProfile == DaxProfile.MUSIC)

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
            (layers.userDb[i] * layers.intensity +
                layers.dolbyBose10Db.getOrElse(i) { 0.0 } +
                layers.routeDb[i] +
                layers.contourDb[i]).toFloat()
        }
        val effectiveHpf = if (s.activeRoute == OutputRoute.SPEAKER) {
            max(s.highPassHz, OutputRoute.SPEAKER.rootHighPassHz)
        } else {
            s.highPassHz
        }
        val effectiveHarmonicMix = if (s.dolbyBoseConfig.bassEnhancerEnabled && s.dolbyBoseConfig.virtualBassEnabled) {
            -6.0f + s.activeRoute.harmonicMixDeltaDb
        } else {
            s.bassHarmonicMixDb?.let { baseMix ->
                baseMix + s.activeRoute.harmonicMixDeltaDb
            }
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
                profileId = s.selectedDaxProfile.id,
                presetId = s.selectedPresetId,
                gainsDb = s.gainsDb,
                rightGainsDb = s.rightGainsDb,
                splitLREnabled = s.splitLREnabled,
                balanceDb = s.balanceDb,
                highPassHz = s.highPassHz,
                intensity = s.intensity,
                volumeContourEnabled = s.volumeContourEnabled,
                nightModeEnabled = s.nightModeEnabled,
                startOnBoot = s.startOnBoot,
                usbBitPerfectEnabled = s.usbBitPerfectEnabled,
                headphoneProfileId = s.selectedHeadphoneProfileId,
                headphoneStrength = s.headphoneStrength,
                headphoneFullRange = s.headphoneFullRange,
                tiltBassDb = s.tiltBassDb,
                tiltTrebleDb = s.tiltTrebleDb,
                customAutoEqName = s.customAutoEqName,
                customAutoEqSource = s.customAutoEqSource,
                customAutoEqPath = s.customAutoEqPath,
                customAutoEqRawText = s.customAutoEqRawText,
                speakerCalEnabled = s.speakerCalEnabled,
                speakerCalStrength = s.speakerCalStrength,
                compatibility10Band = s.compatibility10Band,
                transientMode = s.transientMode,
                dolbyBoseConfig = s.dolbyBoseConfig
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
        saveCurrentDeviceMemory()
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

    fun setNightModeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(nightModeEnabled = enabled) }
        applyPresetOrContextLoad()
    }

    fun setStartOnBoot(enabled: Boolean) {
        _uiState.update { it.copy(startOnBoot = enabled) }
        scheduleDebouncedPersist(delayMs = 50L)
    }

    fun setUsbBitPerfectEnabled(enabled: Boolean) {
        val active = engineController.setUsbBitPerfectMode(enabled)
        _uiState.update {
            it.copy(
                usbBitPerfectEnabled = active,
                diagnostics = engineController.diagnostics,
                statusBannerMessage = if (active) "USB Bit-Perfect ON — EQ bypassed" else "USB Bit-Perfect OFF — EQ active"
            )
        }
        applyPresetOrContextLoad()
    }

    fun setSplitLREnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                splitLREnabled = enabled,
                editingRightChannel = if (!enabled) false else it.editingRightChannel,
                rightGainsDb = if (enabled && it.rightGainsDb.isEmpty()) it.gainsDb else it.rightGainsDb
            )
        }
        applySliderOrContourFast()
    }

    fun setEditingRightChannel(editRight: Boolean) {
        _uiState.update { it.copy(editingRightChannel = editRight) }
    }

    fun updateBalanceDb(newBalanceDb: Float) {
        val clamped = newBalanceDb.coerceIn(-6.0f, 6.0f)
        _uiState.update { it.copy(balanceDb = clamped) }
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

    fun selectDaxProfile(profile: DaxProfile) {
        _uiState.update { cur ->
            val updatedDolby = DolbyBoseDspMath.defaultConfigForDaxProfile(
                profile = profile,
                currentBose = cur.dolbyBoseConfig.boseProfile
            )
            cur.copy(
                selectedDaxProfile = profile,
                dolbyBoseConfig = updatedDolby
            )
        }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    // --- Dolby Atmos (DAP) & Bose MiSound DSP Controls ---

    fun selectIeqPreset(preset: IeqPreset) {
        _uiState.update { cur ->
            val nextAmount = if (preset == IeqPreset.CUSTOM && cur.dolbyBoseConfig.ieqPreset != IeqPreset.CUSTOM) {
                preset.defaultAmount
            } else if (cur.dolbyBoseConfig.ieqPreset == IeqPreset.CUSTOM && preset != IeqPreset.CUSTOM) {
                preset.defaultAmount
            } else {
                cur.dolbyBoseConfig.ieqAmount
            }
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    ieqPreset = preset,
                    ieqAmount = nextAmount
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun updateIeqAmount(amount: Int) {
        val clamped = amount.coerceIn(0, 16)
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(ieqAmount = clamped)
            )
        }
        applyPresetOrContextLoad()
    }

    fun updateIeqCustomBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until 10) return
        val clamped = (kotlin.math.round(gainDb * 2f) / 2f).coerceIn(-12f, 12f)
        _uiState.update { cur ->
            val currentList = cur.dolbyBoseConfig.ieqCustom10Db
            val updated = MutableList(10) { i -> currentList.getOrElse(i) { 0f } }
            updated[bandIndex] = clamped
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    ieqPreset = IeqPreset.CUSTOM,
                    ieqCustom10Db = updated
                )
            )
        }
        applySliderOrContourFast()
    }

    fun resetIeqCustomBands(flat: Boolean = true) {
        val target = if (flat) {
            List(10) { 0f }
        } else {
            DolbyBoseDspMath.IEQ_CUSTOM_DEFAULT_10.toList()
        }
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    ieqPreset = IeqPreset.CUSTOM,
                    ieqCustom10Db = target
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun setDialogueEnhancerEnabled(enabled: Boolean) {
        _uiState.update { cur ->
            val nextAmount = if (enabled && cur.dolbyBoseConfig.dialogueAmount == 0) 5 else cur.dolbyBoseConfig.dialogueAmount
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    dialogueEnhancerEnabled = enabled,
                    dialogueAmount = nextAmount
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun updateDialogueAmount(amount: Int) {
        val clamped = amount.coerceIn(0, 16)
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    dialogueEnhancerEnabled = clamped > 0,
                    dialogueAmount = clamped
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun updateDialogueDucking(ducking: Int) {
        val clamped = ducking.coerceIn(0, 8)
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(dialogueDucking = clamped)
            )
        }
        applyPresetOrContextLoad()
    }

    fun setBassEnhancerEnabled(enabled: Boolean) {
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    bassEnhancerEnabled = enabled,
                    virtualBassEnabled = if (enabled) cur.dolbyBoseConfig.virtualBassEnabled else false
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun setVirtualBassEnabled(enabled: Boolean) {
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(
                    bassEnhancerEnabled = if (enabled) true else cur.dolbyBoseConfig.bassEnhancerEnabled,
                    virtualBassEnabled = enabled
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun selectBoseProfile(profile: BoseProfile) {
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = cur.dolbyBoseConfig.copy(boseProfile = profile)
            )
        }
        applyPresetOrContextLoad()
    }

    fun resetDolbyBoseDsp() {
        _uiState.update { cur ->
            cur.copy(
                dolbyBoseConfig = DolbyBoseDspMath.defaultConfigForDaxProfile(
                    profile = cur.selectedDaxProfile,
                    currentBose = BoseProfile.OFF
                )
            )
        }
        applyPresetOrContextLoad()
    }

    fun attachExternalAudioSession(sessionId: Int) {
        if (sessionId <= 0) return
        engineController.attachExternalSession(sessionId)
        val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
        _uiState.update {
            it.copy(
                modeStatuses = statuses,
                diagnostics = engineController.diagnostics
            )
        }
    }

    fun selectPreset(preset: PresetConfig) {
        _uiState.update {
            it.copy(
                selectedPresetId = preset.id,
                gainsDb = preset.gainsDb,
                rightGainsDb = preset.gainsDb,
                highPassHz = preset.highPassHz,
                bassHarmonicMixDb = preset.bassHarmonicMixDb,
                bassBoostStrength = preset.bassBoostStrength,
                virtualizerStrength = preset.virtualizerStrength,
                qOverrides = preset.qOverrides
            )
        }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    fun resetToFlat() {
        val flat = EqConstants.BUILT_IN_PRESETS.first { it.id == "flat" }
        selectPreset(flat)
    }

    /**
     * Continuous slider drag update: freezes `inputGainDb` and `makeupGainDb` (v5 Section 5.3 & 9).
     */
    fun updateBandGain(bandIndex: Int, newGainDb: Float) {
        val clamped = newGainDb.coerceIn(-12f, 12f)
        _uiState.update { state ->
            if (state.splitLREnabled && state.editingRightChannel) {
                val currentVal = state.rightGainsDb.getOrElse(bandIndex) { 0f }
                if (abs(currentVal - clamped) < 0.01f) return@update state
                val updatedRight = state.rightGainsDb.toMutableList().apply {
                    if (bandIndex in indices) this[bandIndex] = clamped
                }
                state.copy(
                    selectedPresetId = "custom",
                    rightGainsDb = updatedRight
                )
            } else {
                val currentVal = state.gainsDb.getOrElse(bandIndex) { 0f }
                if (abs(currentVal - clamped) < 0.01f) return@update state
                val updatedLeft = state.gainsDb.toMutableList().apply {
                    if (bandIndex in indices) this[bandIndex] = clamped
                }
                state.copy(
                    selectedPresetId = "custom",
                    gainsDb = updatedLeft
                )
            }
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
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    fun updateHeadphoneStrength(strength: Float) {
        _uiState.update { it.copy(headphoneStrength = strength.coerceIn(0f, 1f)) }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    fun setHeadphoneFullRange(fullRange: Boolean) {
        _uiState.update { it.copy(headphoneFullRange = fullRange) }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    fun updateTiltBassDb(bassDb: Float) {
        _uiState.update { it.copy(tiltBassDb = bassDb.coerceIn(-6f, 6f)) }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    fun updateTiltTrebleDb(trebleDb: Float) {
        _uiState.update { it.copy(tiltTrebleDb = trebleDb.coerceIn(-4f, 4f)) }
        saveCurrentDeviceMemory()
        applyPresetOrContextLoad()
    }

    // Personal Hearing Curve (v5 Section 17.2 F5)
    fun setHearingCurveEnabled(enabled: Boolean) {
        val updated = _uiState.value.hearingCurve.copy(enabled = enabled)
        _uiState.update { it.copy(hearingCurve = updated) }
        viewModelScope.launch { repository.saveHearingCurve(updated) }
        applyPresetOrContextLoad()
    }

    fun updateHearingCurveBand(isRightEar: Boolean, freqIndex: Int, db: Float) {
        if (freqIndex !in 0..5) return
        val clamped = db.coerceIn(-6f, 6f)
        val cur = _uiState.value.hearingCurve
        val updated = if (isRightEar) {
            val list = cur.rightEarDb.toMutableList().apply { this[freqIndex] = clamped }
            cur.copy(rightEarDb = list)
        } else {
            val list = cur.leftEarDb.toMutableList().apply { this[freqIndex] = clamped }
            cur.copy(leftEarDb = list)
        }
        _uiState.update { it.copy(hearingCurve = updated) }
        viewModelScope.launch { repository.saveHearingCurve(updated) }
        applyPresetOrContextLoad()
    }

    fun playHearingTestTone(freqIndex: Int, isRightEar: Boolean) {
        val freq = EqConstants.HEARING_TEST_FREQS_HZ.getOrElse(freqIndex) { 1000.0 }
        viewModelScope.launch {
            val prevAb = _uiState.value.isAbCompare
            // Bypass EQ during hearing test tone per v5 Section 17.2 F5
            setAbCompare(true)
            HearingTonePlayer.playTestTone(freqHz = freq, channel = if (isRightEar) 1 else 0)
            setAbCompare(prevAb)
        }
    }

    // ABX & Blind A/B Tool (v5 Section 17.2 F6)
    fun openAbxSheet() {
        val hiddenIsB = Random.nextBoolean()
        _uiState.update {
            it.copy(
                showAbxSheet = true,
                abxListeningChoice = "X",
                abxHiddenXIsB = hiddenIsB,
                isAbCompare = !hiddenIsB,
                abxLastResultText = null
            )
        }
        applySliderOrContourFast(persistNow = false)
    }

    fun dismissAbxSheet() {
        _uiState.update {
            it.copy(
                showAbxSheet = false,
                isAbCompare = false
            )
        }
        applySliderOrContourFast(persistNow = false)
    }

    fun selectAbxListeningSource(source: String) {
        val s = _uiState.value
        val bypass = when (source) {
            "A" -> true
            "B" -> false
            else -> !s.abxHiddenXIsB
        }
        _uiState.update {
            it.copy(
                abxListeningChoice = source,
                isAbCompare = bypass
            )
        }
        applySliderOrContourFast(persistNow = false)
    }

    fun submitAbxGuess(guessedXIsB: Boolean) {
        val s = _uiState.value
        val correct = guessedXIsB == s.abxHiddenXIsB
        val newCorrect = s.abxCorrectCount + (if (correct) 1 else 0)
        val newTrials = s.abxTrialCount + 1
        val actualLabel = if (s.abxHiddenXIsB) "B (EQ On)" else "A (Bypass)"
        val nextHiddenIsB = Random.nextBoolean()
        val nextBypass = when (s.abxListeningChoice) {
            "A" -> true
            "B" -> false
            else -> !nextHiddenIsB
        }
        _uiState.update {
            it.copy(
                abxCorrectCount = newCorrect,
                abxTrialCount = newTrials,
                abxHiddenXIsB = nextHiddenIsB,
                isAbCompare = nextBypass,
                abxLastResultText = if (correct) {
                    "Correct! X was $actualLabel. New X loaded."
                } else {
                    "Missed — X was $actualLabel. New X loaded."
                }
            )
        }
        applySliderOrContourFast(persistNow = false)
    }

    fun resetAbxScore() {
        _uiState.update {
            it.copy(
                abxCorrectCount = 0,
                abxTrialCount = 0,
                abxLastResultText = "Score reset."
            )
        }
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

    fun toggleRootBluetoothAbsVolume(disableAbsVol: Boolean) {
        val res = engineController.toggleRootBluetoothDisableAbsoluteVolume(disableAbsVol)
        val msg = res.getOrElse { it.message ?: "Could not toggle Bluetooth absolute volume" }
        _uiState.update {
            it.copy(
                diagnostics = engineController.diagnostics,
                statusBannerMessage = msg
            )
        }
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

    fun handleShizukuAction() {
        ShizukuManager.refresh()
        val state = ShizukuManager.state.value
        when (state) {
            ShizukuState.NEEDS_PERMISSION -> ShizukuManager.requestPermission()
            ShizukuState.DENIED_PERMANENTLY,
            ShizukuState.NOT_RUNNING -> ShizukuManager.openShizukuApp()
            ShizukuState.NOT_INSTALLED -> ShizukuManager.openShizukuDownloadPage()
            ShizukuState.READY -> {
                viewModelScope.launch {
                    val pkg = getApplication<Application>().packageName
                    val res = ShizukuManager.exec("pm grant $pkg android.permission.DUMP; pm grant $pkg android.permission.PACKAGE_USAGE_STATS")
                    val statuses = engineController.probeCapabilities(dspMonitor.getAudioSessionId())
                    _uiState.update {
                        it.copy(
                            shizukuState = ShizukuManager.state.value,
                            shizukuPrivilege = ShizukuManager.privilege(),
                            modeStatuses = statuses,
                            diagnostics = engineController.diagnostics,
                            statusBannerMessage = if (res.isSuccess) {
                                "Granted DUMP permission via Shizuku (${ShizukuManager.privilege()})"
                            } else {
                                "Shizuku connected (${ShizukuManager.privilege()})"
                            }
                        )
                    }
                    applyPresetOrContextLoad(persistNow = false)
                }
            }
            ShizukuState.UNSUPPORTED_VERSION -> {
                _uiState.update {
                    it.copy(statusBannerMessage = "Please update Shizuku to v11 or newer")
                }
            }
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
