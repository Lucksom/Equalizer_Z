package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.DeviceDiagnostics
import com.example.model.CapabilityLevel
import com.example.model.EngineMode
import com.example.model.ModeStatusInfo
import kotlin.math.roundToInt

/**
 * Engine Mode Selector & Capability Status Panel.
 * Implements all four modes required by the specification:
 * 1. Legacy Mode (Standard Equalizer, BassBoost, Virtualizer)
 * 2. Global Mode (System-wide Session 0 + Broadcast sessions)
 * 3. ADB / Shizuku Mode (Session discovery via DUMP / Shizuku without root + setup instructions)
 * 4. Root Mode (Deep Biquad + Linkwitz-Riley Mid/Side crossovers + backup/restore Stock config)
 */
@Composable
fun ModeSelectorCard(
    selectedMode: EngineMode,
    activeStatus: ModeStatusInfo,
    diagnostics: DeviceDiagnostics,
    spatialStrength: Int,
    alwaysShowSectionWidths: Boolean,
    onSelectMode: (EngineMode) -> Unit,
    onSpatialStrengthChange: (Int) -> Unit,
    onBackupRootConfig: () -> Unit,
    onRestoreRootConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSetupDetails by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("engine_mode_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.engine_mode_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (diagnostics.dynamicsProcessingSupported) "10-Band DP Ready" else "${diagnostics.nativeEqBandCount}-Band Native",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EngineMode.entries.forEach { mode ->
                    val isSelected = mode == selectedMode
                    ModeRowItem(
                        mode = mode,
                        isSelected = isSelected,
                        onClick = { onSelectMode(mode) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status Pill + Expandable Setup / Fallback Details
            val isAvailable = activeStatus.level == CapabilityLevel.AVAILABLE
            val pillContainer = if (isAvailable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
            val pillContent = if (isAvailable) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = pillContainer,
                    contentColor = pillContent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showSetupDetails = !showSetupDetails }
                        .testTag("capability_status_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(pillContent)
                        )
                        Text(
                            text = activeStatus.summaryText,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                IconButton(
                    onClick = { showSetupDetails = !showSetupDetails },
                    modifier = Modifier.testTag("toggle_mode_details_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Mode capability and setup details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Broadband Spatial (Virtualizer) slider when in Legacy / Global / ADB modes
            if (selectedMode != EngineMode.ROOT) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.spatial_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(spatialStrength / 10f).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = spatialStrength.toFloat(),
                            onValueChange = { onSpatialStrengthChange(it.roundToInt()) },
                            valueRange = 0f..1000f,
                            modifier = Modifier.testTag("spatial_virtualizer_slider")
                        )
                        if (alwaysShowSectionWidths) {
                            Text(
                                text = "Per-section Mid/Side width sliders below apply to Root DSP & the built-in Reference Monitor.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Expandable capability details, ADB commands, or Root backup/restore controls
            AnimatedVisibility(visible = showSetupDetails || activeStatus.level != CapabilityLevel.AVAILABLE) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = activeStatus.detailReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (activeStatus.setupSteps.isNotEmpty()) {
                            activeStatus.setupSteps.forEach { step ->
                                Text(
                                    text = step,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (selectedMode == EngineMode.ADB_SHIZUKU) {
                            val adbCmd = "adb shell pm grant ${context.packageName} android.permission.DUMP"
                            FilledTonalButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(adbCmd))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("copy_adb_command_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy ADB command",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Copy ADB DUMP Grant Command")
                            }
                        }

                        if (selectedMode == EngineMode.ROOT) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onBackupRootConfig,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("backup_root_config_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Backup config",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(if (diagnostics.backupExists) "Update Backup" else "Backup & Stage")
                                }

                                OutlinedButton(
                                    onClick = onRestoreRootConfig,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("restore_root_config_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Restore stock config",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text("Restore Stock")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRowItem(
    mode: EngineMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (isSelected) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconBoxBg = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val iconVector = when (mode) {
        EngineMode.LEGACY -> Icons.Default.GraphicEq
        EngineMode.GLOBAL -> Icons.Default.Public
        EngineMode.ADB_SHIZUKU -> Icons.Default.Build
        EngineMode.ROOT -> Icons.Default.Security
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("mode_option_${mode.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBoxBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = mode.title,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
            Text(
                text = mode.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor
            )
        }

        // Custom M3 Radio indicator matching HTML v3 .radio
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
