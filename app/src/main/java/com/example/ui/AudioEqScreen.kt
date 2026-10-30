package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.OutputRoute
import com.example.ui.components.AbxBlindTestSheet
import com.example.ui.components.DspReferenceMonitorCard
import com.example.ui.components.ModeSelectorCard
import com.example.ui.components.RootWarningDialog
import com.example.ui.components.SavePresetDialog
import com.example.ui.components.SettingsAndDiagnosticsSheet
import com.example.ui.components.SoundCalibrationSheet
import com.example.ui.components.TopBarHeader
import com.example.ui.components.UnifiedEqualizerCard

@Composable
fun AudioEqScreen(
    viewModel: EqViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val activeCalLabel = when {
        state.activeRoute == OutputRoute.SPEAKER && state.speakerCalEnabled -> "Spk Cal On"
        state.activeRoute != OutputRoute.SPEAKER && state.selectedHeadphoneProfileId != "none" -> state.selectedHeadphoneProfile.name.take(12)
        else -> "Sound Calibrate"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openSavePresetDialog() },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.save_preset)
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.save_preset),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                modifier = Modifier.testTag("save_preset_fab")
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 980.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 104.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Top Bar Header + Settings Icon
                item {
                    TopBarHeader(
                        onOpenSettings = { viewModel.openSettingsSheet() }
                    )
                }

                // Optional feedback banner
                item {
                    AnimatedVisibility(visible = state.statusBannerMessage != null) {
                        state.statusBannerMessage?.let { msg ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("status_feedback_banner")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { viewModel.clearStatusBanner() }) {
                                        Text("Dismiss")
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Top Horizontal Engine Mode Selector (3 Profiles: Legacy [Global without ADB/Root], ADB / Shizuku, Root [APK-only su])
                item {
                    ModeSelectorCard(
                        selectedMode = state.selectedMode,
                        activeStatus = state.activeStatus,
                        diagnostics = state.diagnostics,
                        startOnBoot = state.startOnBoot,
                        onSelectMode = { viewModel.selectMode(it) },
                        onToggleStartOnBoot = { viewModel.setStartOnBoot(it) },
                        onToggleRootBtAbsVol = { viewModel.toggleRootBluetoothAbsVolume(it) }
                    )
                }

                // 3. Unified 10-Band Equalizer Card (with v5 Presets, Graph, True Category Ranges, 10 Fluid Sliders, L/R Channel tabs, Intensity, Volume Contour, Night Mode, A/B Compare, Blind ABX, Sound Calibration, Headroom Meter)
                item {
                    UnifiedEqualizerCard(
                        presets = state.presets,
                        selectedPresetId = state.selectedPresetId,
                        targetGainsDb = state.activeSlidersGainsDb,
                        splitLREnabled = state.splitLREnabled,
                        editingRightChannel = state.editingRightChannel,
                        intensity = state.intensity,
                        volumeContourEnabled = state.volumeContourEnabled,
                        nightModeEnabled = state.nightModeEnabled,
                        usbBitPerfectActive = state.diagnostics.usbBitPerfectActive,
                        isAbCompare = state.isAbCompare,
                        activeRoute = state.activeRoute,
                        inputGainDb = state.inputGainDb,
                        makeupGainDb = state.makeupGainDb,
                        ceilingLoadDb = state.ceilingLoadDb,
                        activeCalibrationLabel = activeCalLabel,
                        isEnabled = state.isEnabled,
                        onSelectPreset = { viewModel.selectPreset(it) },
                        onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) },
                        onSelectEditingChannel = { viewModel.setEditingRightChannel(it) },
                        onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) },
                        onIntensityChange = { viewModel.setIntensity(it) },
                        onToggleVolumeContour = { viewModel.setVolumeContourEnabled(it) },
                        onToggleNightMode = { viewModel.setNightModeEnabled(it) },
                        onToggleAbCompare = { viewModel.setAbCompare(it) },
                        onOpenAbxTest = { viewModel.openAbxSheet() },
                        onOpenCalibration = { viewModel.openCalibrationSheet() },
                        onToggleEnabled = { viewModel.setPowerEnabled(it) },
                        onResetFlat = { viewModel.resetToFlat() }
                    )
                }

                // 4. Live DSP Reference Monitor Card (Tabla Groove, Pink Noise, Log Sweep)
                item {
                    DspReferenceMonitorCard(
                        isPlaying = state.isReferencePlaying,
                        selectedSignal = state.referenceSignal,
                        onTogglePlay = { viewModel.toggleReferenceMonitor() },
                        onSelectSignal = { viewModel.selectReferenceSignal(it) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
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
            diagnostics = state.diagnostics,
            highPassHz = state.highPassHz,
            splitLREnabled = state.splitLREnabled,
            balanceDb = state.balanceDb,
            usbBitPerfectEnabled = state.usbBitPerfectEnabled,
            startOnBoot = state.startOnBoot,
            compatibility10Band = state.compatibility10Band,
            transientMode = state.transientMode,
            onHighPassChange = { viewModel.updateHighPassHz(it) },
            onToggleSplitLR = { viewModel.setSplitLREnabled(it) },
            onBalanceChange = { viewModel.updateBalanceDb(it) },
            onToggleUsbBitPerfect = { viewModel.setUsbBitPerfectEnabled(it) },
            onToggleStartOnBoot = { viewModel.setStartOnBoot(it) },
            onToggleCompatibility10Band = { viewModel.setCompatibility10Band(it) },
            onToggleTransientMode = { viewModel.setTransientMode(it) },
            onDismiss = { viewModel.dismissSettingsSheet() }
        )
    }
}
