package com.example.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.DeviceDiagnostics
import kotlin.math.roundToInt

@Composable
fun SavePresetDialog(
    currentPreampDb: Float,
    currentHighPassHz: Float,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var presetName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Save Custom Preset",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Saves your 10-band EQ curve, Preamp (${currentPreampDb} dB), High-Pass (${currentHighPassHz.toInt()} Hz), and 4-section Mid/Side stereo widths.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset Name") },
                    placeholder = { Text("e.g., Studio Tabla Live") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(presetName) },
                modifier = Modifier.testTag("confirm_save_preset_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_save_preset_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RootWarningDialog(
    onAccept: () -> Unit,
    onCancelAndFallback: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelAndFallback,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Root Risk Warning",
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = "Root Mode Safety & Risks",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Root Mode installs a systemless Magisk module (/data/adb/modules/eq_deep) and disables hardware audio offload (audio.offload.disable=1).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Never edit /system or /vendor directly.\n• Modifying system audio effects or rooting may affect Play Integrity, banking apps, or DRM playback.\n• Always back up stock audio_effects.xml before applying changes.\n• To recover in case of boot or audio issues: boot into Magisk Safe Mode or tap 'Restore Stock' in Audio EQ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("accept_root_warning_button")
            ) {
                Text("I Understand, Enable")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelAndFallback,
                modifier = Modifier.testTag("decline_root_warning_button")
            ) {
                Text("Use Global Mode")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndDiagnosticsSheet(
    diagnostics: DeviceDiagnostics,
    highPassHz: Float,
    alwaysShowSectionWidths: Boolean,
    onHighPassChange: (Float) -> Unit,
    onToggleAlwaysShowWidths: (Boolean) -> Unit,
    onBackupRootConfig: () -> Unit,
    onRestoreRootConfig: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "DSP Settings & Device Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Sub-bass High-Pass Filter Cleanup control
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sub-Bass High-Pass Filter (Butterworth Q 0.707)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = if (highPassHz <= 10f) "Off" else "${highPassHz.roundToInt()} Hz",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Cleans up sub-bass rumble (e.g., 40 Hz for Percussion Clarity, 60 Hz for Vocal Clarity).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = highPassHz,
                        onValueChange = onHighPassChange,
                        valueRange = 0f..100f,
                        modifier = Modifier.testTag("high_pass_filter_slider")
                    )
                }
            }

            // Show per-section width sliders in all modes toggle
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show Section Width Sliders in All Modes",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "When off, per-section Width sliders appear only in Root mode (and map to Spatial Virtualizer in other modes).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = alwaysShowSectionWidths,
                        onCheckedChange = onToggleAlwaysShowWidths,
                        modifier = Modifier.testTag("toggle_always_show_widths")
                    )
                }
            }

            // Hardware Audio Diagnostics
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "DETECTED HARDWARE CAPABILITIES",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Native Equalizer Bands: ${diagnostics.nativeEqBandCount} (${diagnostics.nativeBandCentersHz.joinToString(", ")} Hz)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Hardware Gain Range: ${diagnostics.nativeLevelRangeDb.first} dB to +${diagnostics.nativeLevelRangeDb.second} dB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• 10-Band DynamicsProcessing + MBC + Limiter: ${if (diagnostics.dynamicsProcessingSupported) "Supported" else "Fallback to Native EQ"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Session 0 Global Mix: ${if (diagnostics.globalSession0Supported) "Supported" else "Restricted (Using Broadcast Sessions)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• ADB DUMP Permission: ${if (diagnostics.dumpPermissionGranted) "Granted" else "Not Granted"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Root (su) / Effects Config: ${if (diagnostics.rootSuBinaryDetected) "Detected" else "Not Detected"} (${diagnostics.audioEffectsConfigPath})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            HorizontalDivider()

            // Bluetooth / Developer Options Guided Deep Links (per section 5.5)
            Text(
                text = "BLUETOOTH & SYSTEM AUDIO TWEAKS",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Developer Options (LDAC / Abs Vol)")
                }

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("System Sound")
                }
            }

            // Root Backup & Restore controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onBackupRootConfig,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Backup audio_effects.xml")
                }
                OutlinedButton(
                    onClick = onRestoreRootConfig,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Restore Stock Config")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
