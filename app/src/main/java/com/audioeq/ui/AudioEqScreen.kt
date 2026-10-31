package com.audioeq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audioeq.ui.components.AbxBlindTestSheet
import com.audioeq.ui.components.EnhancerCard
import com.audioeq.ui.components.EqualizerCard
import com.audioeq.ui.components.PresetsCard
import com.audioeq.ui.components.RootWarningDialog
import com.audioeq.ui.components.SavePresetDialog
import com.audioeq.ui.components.SettingsAndDiagnosticsSheet
import com.audioeq.ui.components.SoundCalibrationSheet
import com.audioeq.ui.components.StatusBanner
import com.audioeq.ui.components.ToneCard
import com.audioeq.ui.components.TopBarHeader
import com.audioeq.ui.theme.LocalMonetPalette

@Composable
fun AudioEqScreen(
    viewModel: EqViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = LocalMonetPalette.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openSavePresetDialog() },
                shape = RoundedCornerShape(20.dp),
                containerColor = palette.primaryC,
                contentColor = palette.onPrimaryC,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 12.dp
                ),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Save preset"
                    )
                },
                text = {
                    Text(
                        text = "Save preset",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                },
                modifier = Modifier.testTag("save_preset_fab")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg)
        ) {
            // Radial ambient background glows matching audio-eq-v7.html body background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryC.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.12f, 0f),
                        radius = maxOf(w * 0.65f, 420f)
                    ),
                    center = Offset(w * 0.12f, 0f),
                    radius = maxOf(w * 0.65f, 420f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.tertiaryC.copy(alpha = 0.25f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.90f, h),
                        radius = maxOf(w * 0.55f, 360f)
                    ),
                    center = Offset(w * 0.90f, h),
                    radius = maxOf(w * 0.55f, 360f)
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                val isWide = maxWidth >= 840.dp

                if (isWide) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1180.dp)
                            .verticalScroll(scrollState)
                            .padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = 16.dp,
                                bottom = 110.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TopBarHeader(
                            masterEnabled = state.masterEnabled,
                            activePresetName = state.activePresetDisplayName,
                            selectedMode = state.selectedMode,
                            onToggleMaster = { viewModel.setMasterEnabled(it) },
                            onOpenSettings = { viewModel.openSettingsSheet() }
                        )

                        AnimatedVisibility(visible = state.statusBannerMessage != null) {
                            state.statusBannerMessage?.let { msg ->
                                StatusBanner(
                                    message = msg,
                                    onDismiss = { viewModel.clearStatusBanner() }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Left Column (300dp): Presets Card
                            Box(modifier = Modifier.width(300.dp)) {
                                PresetsCard(
                                    masterEnabled = state.masterEnabled,
                                    presets = state.presets,
                                    selectedPresetId = state.selectedPresetId,
                                    activePresetName = state.activePresetDisplayName,
                                    onSelectPreset = { viewModel.selectPreset(it) },
                                    onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) }
                                )
                            }

                            // Right Column (1fr): Equalizer + Tone + Audio Enhancers & Profiles
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                EqualizerCard(
                                    masterEnabled = state.masterEnabled,
                                    isEnabled = state.isEnabled,
                                    isAbCompare = state.isAbCompare,
                                    usbBitPerfectActive = state.diagnostics.usbBitPerfectActive,
                                    intensity = state.intensity,
                                    activeRoute = state.activeRoute,
                                    ceilingDb = state.v7CeilingDb,
                                    sliderGainsDb = state.activeSlidersGainsDb,
                                    effectiveGainsDb = state.effectiveBandGainsDb,
                                    splitLREnabled = state.splitLREnabled,
                                    editingRightChannel = state.editingRightChannel,
                                    onToggleEnabled = { viewModel.setPowerEnabled(it) },
                                    onSelectEditingChannel = { viewModel.setEditingRightChannel(it) },
                                    onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) }
                                )

                                ToneCard(
                                    masterEnabled = state.masterEnabled,
                                    tiltBassDb = state.tiltBassDb,
                                    tiltTrebleDb = state.tiltTrebleDb,
                                    intensity = state.intensity,
                                    volumeContourEnabled = state.volumeContourEnabled,
                                    isAbCompare = state.isAbCompare,
                                    onTiltBassChange = { viewModel.updateTiltBassDb(it) },
                                    onTiltTrebleChange = { viewModel.updateTiltTrebleDb(it) },
                                    onIntensityChange = { viewModel.setIntensity(it) },
                                    onToggleVolumeContour = { viewModel.setVolumeContourEnabled(it) },
                                    onToggleAbCompare = { viewModel.setAbCompare(it) }
                                )

                                EnhancerCard(
                                    config = state.enhancerConfig,
                                    masterEnabled = state.masterEnabled && !state.diagnostics.usbBitPerfectActive,
                                    onSelectProfile = { viewModel.selectProfile(it) },
                                    onSelectIeqPreset = { viewModel.selectIeqPreset(it) },
                                    onIeqAmountChange = { viewModel.updateIeqAmount(it) },
                                    onIeqCustomBandChange = { idx, db -> viewModel.updateIeqCustomBand(idx, db) },
                                    onResetIeqCustomBands = { flat -> viewModel.resetIeqCustomBands(flat) },
                                    onToggleDialogueEnhancer = { viewModel.setDialogueEnhancerEnabled(it) },
                                    onDialogueAmountChange = { viewModel.updateDialogueAmount(it) },
                                    onDialogueDuckingChange = { viewModel.updateDialogueDucking(it) },
                                    onToggleBassEnhancer = { viewModel.setBassEnhancerEnabled(it) },
                                    onBassEnhancerStrengthChange = { viewModel.updateBassEnhancerStrength(it) },
                                    onToggleVirtualBass = { viewModel.setVirtualBassEnabled(it) },
                                    onResetEnhancers = { viewModel.resetEnhancers() },
                                    onOpenCalibration = { viewModel.openCalibrationSheet() },
                                    onOpenAbxTest = { viewModel.openAbxSheet() }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 110.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            TopBarHeader(
                                masterEnabled = state.masterEnabled,
                                activePresetName = state.activePresetDisplayName,
                                selectedMode = state.selectedMode,
                                onToggleMaster = { viewModel.setMasterEnabled(it) },
                                onOpenSettings = { viewModel.openSettingsSheet() }
                            )
                        }

                        if (state.statusBannerMessage != null) {
                            item {
                                state.statusBannerMessage?.let { msg ->
                                    StatusBanner(
                                        message = msg,
                                        onDismiss = { viewModel.clearStatusBanner() }
                                    )
                                }
                            }
                        }

                        item {
                            PresetsCard(
                                masterEnabled = state.masterEnabled,
                                presets = state.presets,
                                selectedPresetId = state.selectedPresetId,
                                activePresetName = state.activePresetDisplayName,
                                onSelectPreset = { viewModel.selectPreset(it) },
                                onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) }
                            )
                        }

                        item {
                            EqualizerCard(
                                masterEnabled = state.masterEnabled,
                                isEnabled = state.isEnabled,
                                isAbCompare = state.isAbCompare,
                                usbBitPerfectActive = state.diagnostics.usbBitPerfectActive,
                                intensity = state.intensity,
                                activeRoute = state.activeRoute,
                                ceilingDb = state.v7CeilingDb,
                                sliderGainsDb = state.activeSlidersGainsDb,
                                effectiveGainsDb = state.effectiveBandGainsDb,
                                splitLREnabled = state.splitLREnabled,
                                editingRightChannel = state.editingRightChannel,
                                onToggleEnabled = { viewModel.setPowerEnabled(it) },
                                onSelectEditingChannel = { viewModel.setEditingRightChannel(it) },
                                onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) }
                            )
                        }

                        item {
                            ToneCard(
                                masterEnabled = state.masterEnabled,
                                tiltBassDb = state.tiltBassDb,
                                tiltTrebleDb = state.tiltTrebleDb,
                                intensity = state.intensity,
                                volumeContourEnabled = state.volumeContourEnabled,
                                isAbCompare = state.isAbCompare,
                                onTiltBassChange = { viewModel.updateTiltBassDb(it) },
                                onTiltTrebleChange = { viewModel.updateTiltTrebleDb(it) },
                                onIntensityChange = { viewModel.setIntensity(it) },
                                onToggleVolumeContour = { viewModel.setVolumeContourEnabled(it) },
                                onToggleAbCompare = { viewModel.setAbCompare(it) }
                            )
                        }

                        item {
                            EnhancerCard(
                                config = state.enhancerConfig,
                                masterEnabled = state.masterEnabled && !state.diagnostics.usbBitPerfectActive,
                                onSelectProfile = { viewModel.selectProfile(it) },
                                onSelectIeqPreset = { viewModel.selectIeqPreset(it) },
                                onIeqAmountChange = { viewModel.updateIeqAmount(it) },
                                onIeqCustomBandChange = { idx, db -> viewModel.updateIeqCustomBand(idx, db) },
                                onResetIeqCustomBands = { flat -> viewModel.resetIeqCustomBands(flat) },
                                onToggleDialogueEnhancer = { viewModel.setDialogueEnhancerEnabled(it) },
                                onDialogueAmountChange = { viewModel.updateDialogueAmount(it) },
                                onDialogueDuckingChange = { viewModel.updateDialogueDucking(it) },
                                onToggleBassEnhancer = { viewModel.setBassEnhancerEnabled(it) },
                                onBassEnhancerStrengthChange = { viewModel.updateBassEnhancerStrength(it) },
                                onToggleVirtualBass = { viewModel.setVirtualBassEnabled(it) },
                                onResetEnhancers = { viewModel.resetEnhancers() },
                                onOpenCalibration = { viewModel.openCalibrationSheet() },
                                onOpenAbxTest = { viewModel.openAbxSheet() }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }

    if (state.showSavePresetDialog) {
        SavePresetDialog(
            currentPreampDb = state.inputGainDb,
            currentHighPassHz = state.highPassHz,
            onDismiss = { viewModel.dismissSavePresetDialog() },
            onSave = { name -> viewModel.saveCustomPreset(name) }
        )
    }

    if (state.showRootWarningDialog) {
        RootWarningDialog(
            onAccept = { viewModel.confirmRootWarning() },
            onCancelAndFallback = { viewModel.dismissRootWarningAndFallback() }
        )
    }

    if (state.showCalibrationSheet) {
        SoundCalibrationSheet(
            activeRoute = state.activeRoute,
            isLegacyTier1 = state.diagnostics.isLegacyTier1BasicEngine,
            headphoneProfiles = state.headphoneProfiles,
            selectedHeadphoneProfileId = state.selectedHeadphoneProfileId,
            headphoneStrength = state.headphoneStrength,
            headphoneFullRange = state.headphoneFullRange,
            headphoneTrimmedDb = state.headphoneTrimmedDb,
            tiltBassDb = state.tiltBassDb,
            tiltTrebleDb = state.tiltTrebleDb,
            autoEqCatalog = state.autoEqCatalog,
            autoEqLoading = state.autoEqLoading,
            autoEqRateLimited = state.autoEqRateLimited,
            autoEqOffline = state.autoEqOffline,
            speakerCalEnabled = state.speakerCalEnabled,
            speakerCalStrength = state.speakerCalStrength,
            speakerMeas31Db = state.speakerMeas31Db,
            speakerCorrection31Db = state.speakerCorrection31Db,
            isCalibratingSpeaker = state.isCalibratingSpeaker,
            calibrationProgress = state.calibrationProgress,
            calibrationStatusText = state.calibrationStatusText,
            hearingCurve = state.hearingCurve,
            connectedProductName = state.diagnostics.connectedProductName,
            onSelectHeadphoneProfile = { viewModel.selectHeadphoneProfile(it) },
            onSelectAutoEqEntry = { viewModel.selectAutoEqCatalogEntry(it) },
            onRefreshAutoEqCatalog = { viewModel.refreshAutoEqCatalog(force = true) },
            onHeadphoneStrengthChange = { viewModel.updateHeadphoneStrength(it) },
            onToggleHeadphoneFullRange = { viewModel.setHeadphoneFullRange(it) },
            onTiltBassChange = { viewModel.updateTiltBassDb(it) },
            onTiltTrebleChange = { viewModel.updateTiltTrebleDb(it) },
            onImportCustomText = { name, raw -> viewModel.importCustomAutoEqText(name, raw) },
            onExportActiveText = { viewModel.exportActiveCorrectionText() },
            onToggleSpeakerCalibration = { viewModel.toggleSpeakerCalibration(it) },
            onSpeakerStrengthChange = { viewModel.updateSpeakerCalStrength(it) },
            onRunSpeakerCalibration = { viewModel.runSpeakerSelfCalibration() },
            onResetSpeakerCalibration = { viewModel.resetSpeakerCalibration() },
            onToggleHearingCurve = { viewModel.setHearingCurveEnabled(it) },
            onUpdateHearingBand = { isRight, idx, db -> viewModel.updateHearingCurveBand(isRight, idx, db) },
            onPlayHearingTone = { idx, isRight -> viewModel.playHearingTestTone(idx, isRight) },
            onDismiss = { viewModel.dismissCalibrationSheet() }
        )
    }

    if (state.showAbxSheet) {
        AbxBlindTestSheet(
            listeningChoice = state.abxListeningChoice,
            correctCount = state.abxCorrectCount,
            trialCount = state.abxTrialCount,
            lastResultText = state.abxLastResultText,
            onSelectSource = { viewModel.selectAbxListeningSource(it) },
            onGuessXIsB = { viewModel.submitAbxGuess(it) },
            onResetScore = { viewModel.resetAbxScore() },
            onDismiss = { viewModel.dismissAbxSheet() }
        )
    }

    if (state.showSettingsSheet) {
        SettingsAndDiagnosticsSheet(
            selectedMode = state.selectedMode,
            shizukuState = state.shizukuState,
            shizukuPrivilege = state.shizukuPrivilege,
            diagnostics = state.diagnostics,
            highPassHz = state.highPassHz,
            splitLREnabled = state.splitLREnabled,
            balanceDb = state.balanceDb,
            usbBitPerfectEnabled = state.usbBitPerfectEnabled,
            startOnBoot = state.startOnBoot,
            compatibility10Band = state.compatibility10Band,
            transientMode = state.transientMode,
            themeMode = state.themeMode,
            seedHex = state.seedHex,
            onSelectMode = { viewModel.selectMode(it) },
            onShizukuAction = { viewModel.handleShizukuAction() },
            onHighPassChange = { viewModel.updateHighPassHz(it) },
            onToggleSplitLR = { viewModel.setSplitLREnabled(it) },
            onBalanceChange = { viewModel.updateBalanceDb(it) },
            onToggleUsbBitPerfect = { viewModel.setUsbBitPerfectEnabled(it) },
            onToggleStartOnBoot = { viewModel.setStartOnBoot(it) },
            onToggleCompatibility10Band = { viewModel.setCompatibility10Band(it) },
            onToggleTransientMode = { viewModel.setTransientMode(it) },
            onSelectThemeMode = { viewModel.setThemeMode(it) },
            onSelectSeedHex = { hex, fromWallpaper -> viewModel.setSeedHex(hex, fromWallpaper) },
            onDismiss = { viewModel.dismissSettingsSheet() }
        )
    }
}
