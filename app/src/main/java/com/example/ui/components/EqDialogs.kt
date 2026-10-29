package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.DeviceDiagnostics
import com.example.audio.Dsp
import com.example.model.HeadphoneProfile
import com.example.model.OutputRoute
import java.util.Locale
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
                val formattedPreamp = String.format(Locale.US, "%.1f", currentPreampDb)
                Text(
                    text = "Saves your 10-band EQ curve, Input Gain ($formattedPreamp dB), and High-Pass (${currentHighPassHz.toInt()} Hz).",
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
                Text("Use Legacy Mode")
            }
        }
    )
}

/**
 * Sound Calibration Bottom Sheet (v4 Section 8 & Section 10):
 * - Headphone Profile Picker with search, source/license attribution, and strength slider (default 0.8)
 * - Guided Speaker Self-Calibration Wizard (requests RECORD_AUDIO permission, verifies Speaker route & <=60% volume,
 *   runs 3 sweeps, shows Before/After 31-band curve, strength slider default 0.7, and Reset)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCalibrationSheet(
    activeRoute: OutputRoute,
    headphoneProfiles: List<HeadphoneProfile>,
    selectedHeadphoneProfileId: String,
    headphoneStrength: Float,
    speakerCalEnabled: Boolean,
    speakerCalStrength: Float,
    speakerMeas31Db: List<Float>,
    speakerCorrection31Db: List<Float>,
    isCalibratingSpeaker: Boolean,
    calibrationProgress: Float,
    calibrationStatusText: String,
    connectedProductName: String?,
    onSelectHeadphoneProfile: (String) -> Unit,
    onHeadphoneStrengthChange: (Float) -> Unit,
    onToggleSpeakerCalibration: (Boolean) -> Unit,
    onSpeakerStrengthChange: (Float) -> Unit,
    onRunSpeakerCalibration: () -> Unit,
    onResetSpeakerCalibration: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onRunSpeakerCalibration()
        }
    }

    val selectedHp = headphoneProfiles.find { it.id == selectedHeadphoneProfileId } ?: headphoneProfiles.first()
    val hpCorrection31 = remember(selectedHp, headphoneStrength) {
        Dsp.computeHeadphoneCorrection31(selectedHp, headphoneStrength.toDouble()).map { it.toFloat() }
    }

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
                text = "Sound Calibration (Dirac-Style Layer)",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Device correction sits underneath your 10-band EQ curve so sliders behave consistently across speakers and headphones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Before / After 31-Band Correction Preview Graph
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (activeRoute == OutputRoute.SPEAKER) {
                            "31-BAND SPEAKER CORRECTION CURVE"
                        } else {
                            "31-BAND HEADPHONE CORRECTION (${selectedHp.name})"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val activeCorr = if (activeRoute == OutputRoute.SPEAKER) speakerCorrection31Db else hpCorrection31
                    CalibrationBeforeAfterCanvas(
                        measured31Db = speakerMeas31Db,
                        correction31Db = activeCorr
                    )
                }
            }

            // Section 8.1: Headphone Profiles Picker
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Headphone Profile (Wired / BT / USB)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Strength ${(headphoneStrength * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!connectedProductName.isNullOrBlank()) {
                        Text(
                            text = "Connected device: $connectedProductName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = headphoneStrength,
                        onValueChange = onHeadphoneStrengthChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("headphone_strength_slider")
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search headphone model (Sony, Sennheiser, AirPods...)") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("headphone_search_input")
                    )

                    val filtered = headphoneProfiles.filter {
                        searchQuery.isBlank() ||
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.source.contains(searchQuery, ignoreCase = true)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filtered.forEach { profile ->
                            val isSelected = profile.id == selectedHeadphoneProfileId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable { onSelectHeadphoneProfile(profile.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("hp_profile_${profile.id}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${profile.source} · License: ${profile.license}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 8.2: Speaker Self-Calibration Wizard
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speaker Self-Calibration (200 Hz–10 kHz)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Measures 3 exponential sweeps (-18 dBFS), deconvolves 20 ms IR window, and keeps correction if RMS error improves >= 20%.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = speakerCalEnabled,
                            onCheckedChange = onToggleSpeakerCalibration,
                            modifier = Modifier.testTag("speaker_cal_toggle")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Correction Strength",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "${(speakerCalStrength * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = speakerCalStrength,
                        onValueChange = onSpeakerStrengthChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("speaker_strength_slider")
                    )

                    if (isCalibratingSpeaker) {
                        LinearProgressIndicator(
                            progress = { calibrationProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (calibrationStatusText.isNotBlank()) {
                        Text(
                            text = calibrationStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val hasMicPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMicPerm) {
                                    onRunSpeakerCalibration()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !isCalibratingSpeaker,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("run_speaker_calibration_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Calibrate speaker"
                            )
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text(if (isCalibratingSpeaker) "Running Sweeps..." else "Start 3-Sweep Calibration")
                        }

                        OutlinedButton(
                            onClick = onResetSpeakerCalibration,
                            enabled = !isCalibratingSpeaker,
                            modifier = Modifier.testTag("reset_speaker_calibration_button")
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CalibrationBeforeAfterCanvas(
    measured31Db: List<Float>,
    correction31Db: List<Float>
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
            val w = size.width
            val h = size.height
            val midY = h * 0.5f

            drawLine(
                color = outlineColor.copy(alpha = 0.4f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1.dp.toPx()
            )

            if (measured31Db.size == 31) {
                val measPath = Path()
                for (i in 0 until 31) {
                    val x = (i / 30f) * w
                    val y = midY - (measured31Db[i].coerceIn(-12f, 12f) / 12f) * (h * 0.42f)
                    if (i == 0) measPath.moveTo(x, y) else measPath.lineTo(x, y)
                }
                drawPath(
                    path = measPath,
                    color = tertiaryColor.copy(alpha = 0.55f),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            if (correction31Db.size == 31) {
                val corrPath = Path()
                for (i in 0 until 31) {
                    val x = (i / 30f) * w
                    val y = midY - (correction31Db[i].coerceIn(-12f, 12f) / 12f) * (h * 0.42f)
                    if (i == 0) corrPath.moveTo(x, y) else corrPath.lineTo(x, y)
                }
                drawPath(
                    path = corrPath,
                    color = primaryColor,
                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndDiagnosticsSheet(
    diagnostics: DeviceDiagnostics,
    highPassHz: Float,
    compatibility10Band: Boolean,
    transientMode: Boolean,
    onHighPassChange: (Float) -> Unit,
    onToggleCompatibility10Band: (Boolean) -> Unit,
    onToggleTransientMode: (Boolean) -> Unit,
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
                text = "DSP Engine Settings & Diagnostics (v4)",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // v4 Section 10: Compatibility mode (10 bands) switch & Transient Mode switch
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Compatibility Mode (10 Bands)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Use 10 Pre-EQ bands instead of the 31-band PCHIP renderer on devices whose audio HAL glitches with 31 bands.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = compatibility10Band,
                            onCheckedChange = onToggleCompatibility10Band,
                            modifier = Modifier.testTag("compatibility_10_band_switch")
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Transient Mode (Time Resolution)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Rebuilds DynamicsProcessing with VARIANT_FAVOR_TIME_RESOLUTION instead of Frequency Resolution.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = transientMode,
                            onCheckedChange = onToggleTransientMode,
                            modifier = Modifier.testTag("transient_mode_switch")
                        )
                    }
                }
            }

            // Root Sub-Bass High-Pass Filter
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
                            text = "Root Sub-Bass High-Pass Filter (Butterworth Q 0.707)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = if (highPassHz <= 10f) "Off" else "${highPassHz.roundToInt()} Hz",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Cleans up sub-bass rumble (40 Hz Percussion Clarity, 60 Hz Vocal Clarity, 140 Hz Speaker route).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = highPassHz,
                        onValueChange = onHighPassChange,
                        valueRange = 0f..140f,
                        modifier = Modifier.testTag("high_pass_filter_slider")
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
                        text = "• Active Renderer: ${if (diagnostics.dynamicsProcessingSupported) "${diagnostics.activeRenderBandCount}-Band PCHIP + 6-Band MBC Ceilings" else "Basic Engine (Tier 1 · ${diagnostics.nativeEqBandCount}-Band Native EQ)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Native Equalizer Centers: ${diagnostics.nativeBandCentersHz.joinToString(", ")} Hz (${diagnostics.nativeLevelRangeDb.first} to +${diagnostics.nativeLevelRangeDb.second} dB)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Session 0 Global Mix: ${if (diagnostics.globalSession0Supported) "Supported" else "Using Broadcast Sessions"}",
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
