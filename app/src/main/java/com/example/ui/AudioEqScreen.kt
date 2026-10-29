package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.FrequencySection
import com.example.ui.components.DspReferenceMonitorCard
import com.example.ui.components.FrequencyResponseGraph
import com.example.ui.components.FrequencySectionCard
import com.example.ui.components.ModeSelectorCard
import com.example.ui.components.PowerHeroCard
import com.example.ui.components.PresetChipRow
import com.example.ui.components.RootWarningDialog
import com.example.ui.components.SavePresetDialog
import com.example.ui.components.SettingsAndDiagnosticsSheet
import com.example.ui.components.TopBarHeader

@Composable
fun AudioEqScreen(
    viewModel: EqViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val showPerSectionWidths = state.alwaysShowSectionWidths || state.selectedMode == EngineMode.ROOT

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openSavePresetDialog() },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
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
            val isExpandedScreen = maxWidth >= 720.dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = if (isExpandedScreen) 960.dp else 500.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 104.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Top Brand Header + Settings Button
                item {
                    TopBarHeader(
                        onOpenSettings = { viewModel.openSettingsSheet() }
                    )
                }

                // Optional status toast banner for backup/restore/preset save feedback
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

                // 2. Global Equalizer Power Hero Card
                item {
                    PowerHeroCard(
                        isEnabled = state.isEnabled,
                        activeSessionsCount = state.activeStatus.activeSessionsCount,
                        onTogglePower = { viewModel.setPowerEnabled(it) }
                    )
                }

                // 3. Engine Mode Selector + Capability Status Pill
                item {
                    ModeSelectorCard(
                        selectedMode = state.selectedMode,
                        activeStatus = state.activeStatus,
                        diagnostics = state.diagnostics,
                        spatialStrength = state.spatialStrength,
                        alwaysShowSectionWidths = state.alwaysShowSectionWidths,
                        onSelectMode = { viewModel.selectMode(it) },
                        onSpatialStrengthChange = { viewModel.updateSpatialStrength(it) },
                        onBackupRootConfig = { viewModel.backupAndStageRootModule() },
                        onRestoreRootConfig = { viewModel.restoreStockAudioConfig() }
                    )
                }

                // 4. Preset Chip Row (Flat, Balanced Deep, Bass Boost, Vocal Clarity, Percussion Clarity, Custom)
                item {
                    PresetChipRow(
                        presets = state.presets,
                        selectedPresetId = state.selectedPresetId,
                        onSelectPreset = { viewModel.selectPreset(it) },
                        onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) }
                    )
                }

                // 5. Live Frequency Response Curve Card
                item {
                    FrequencyResponseGraph(
                        responsePoints = state.responseCurvePoints,
                        gainsDb = state.gainsDb,
                        preampDb = state.preampDb,
                        highPassHz = state.highPassHz,
                        isEnabled = state.isEnabled
                    )
                }

                // 6. Live DSP Reference Monitor (Tabla Groove / Pink Noise / Sine Sweep)
                item {
                    DspReferenceMonitorCard(
                        isPlaying = state.isReferencePlaying,
                        selectedSignal = state.referenceSignal,
                        onTogglePlay = { viewModel.toggleReferenceMonitor() },
                        onSelectSignal = { viewModel.selectReferenceSignal(it) }
                    )
                }

                // 7. Four Frequency Section Cards (Bass, Mids, Treble, Air)
                if (isExpandedScreen) {
                    // Canonical 2-column grid layout on Tablets / Foldables
                    val sections = FrequencySection.entries
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            FrequencySectionCard(
                                section = sections[0],
                                bands = EqConstants.MASTER_BANDS.filter { it.section == sections[0] },
                                gainsDb = state.gainsDb,
                                widthPct = state.widths.forSection(sections[0]),
                                showWidthSlider = showPerSectionWidths,
                                isEnabled = state.isEnabled,
                                onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) },
                                onWidthChange = { sec, w -> viewModel.updateSectionWidth(sec, w) },
                                modifier = Modifier.weight(1f)
                            )
                            FrequencySectionCard(
                                section = sections[1],
                                bands = EqConstants.MASTER_BANDS.filter { it.section == sections[1] },
                                gainsDb = state.gainsDb,
                                widthPct = state.widths.forSection(sections[1]),
                                showWidthSlider = showPerSectionWidths,
                                isEnabled = state.isEnabled,
                                onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) },
                                onWidthChange = { sec, w -> viewModel.updateSectionWidth(sec, w) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            FrequencySectionCard(
                                section = sections[2],
                                bands = EqConstants.MASTER_BANDS.filter { it.section == sections[2] },
                                gainsDb = state.gainsDb,
                                widthPct = state.widths.forSection(sections[2]),
                                showWidthSlider = showPerSectionWidths,
                                isEnabled = state.isEnabled,
                                onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) },
                                onWidthChange = { sec, w -> viewModel.updateSectionWidth(sec, w) },
                                modifier = Modifier.weight(1f)
                            )
                            FrequencySectionCard(
                                section = sections[3],
                                bands = EqConstants.MASTER_BANDS.filter { it.section == sections[3] },
                                gainsDb = state.gainsDb,
                                widthPct = state.widths.forSection(sections[3]),
                                showWidthSlider = showPerSectionWidths,
                                isEnabled = state.isEnabled,
                                onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) },
                                onWidthChange = { sec, w -> viewModel.updateSectionWidth(sec, w) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    FrequencySection.entries.forEach { section ->
                        item(key = section.id) {
                            val sectionBands = EqConstants.MASTER_BANDS.filter { it.section == section }
                            FrequencySectionCard(
                                section = section,
                                bands = sectionBands,
                                gainsDb = state.gainsDb,
                                widthPct = state.widths.forSection(section),
                                showWidthSlider = showPerSectionWidths,
                                isEnabled = state.isEnabled,
                                onBandGainChange = { idx, db -> viewModel.updateBandGain(idx, db) },
                                onWidthChange = { sec, w -> viewModel.updateSectionWidth(sec, w) }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (state.showSavePresetDialog) {
        SavePresetDialog(
            currentPreampDb = state.preampDb,
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

    if (state.showSettingsSheet) {
        SettingsAndDiagnosticsSheet(
            diagnostics = state.diagnostics,
            highPassHz = state.highPassHz,
            alwaysShowSectionWidths = state.alwaysShowSectionWidths,
            onHighPassChange = { viewModel.updateHighPassHz(it) },
            onToggleAlwaysShowWidths = { viewModel.setAlwaysShowSectionWidths(it) },
            onBackupRootConfig = { viewModel.backupAndStageRootModule() },
            onRestoreRootConfig = { viewModel.restoreStockAudioConfig() },
            onDismiss = { viewModel.dismissSettingsSheet() }
        )
    }
}
