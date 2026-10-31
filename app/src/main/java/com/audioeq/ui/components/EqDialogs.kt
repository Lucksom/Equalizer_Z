package com.audioeq.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.audioeq.audio.AutoEq
import com.audioeq.audio.DeviceDiagnostics
import com.audioeq.audio.Dsp
import com.audioeq.audio.EqMath
import com.audioeq.audio.IndexEntry
import com.audioeq.model.EqConstants
import com.audioeq.model.HeadphoneProfile
import com.audioeq.model.OutputRoute
import com.audioeq.model.PersonalHearingCurve
import java.util.Locale
import kotlin.math.abs
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
                contentDescription = "Root Mode Notice",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Root Mode (APK-Only Helper)",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "In Deep Optimization v5, Root Mode is 100% APK-only: it never flashes modules or edits /system or /vendor partitions.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Starts a lightweight helper process via su for global audio session discovery without needing a PC/ADB session.\n• Enables automatic Start-on-Boot (F12) and Bluetooth codec readout (F10).\n• Can toggle 'Disable Bluetooth Absolute Volume' so the ISO 226 volume contour receives volume-scaled audio.",
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
                Text("Enable Root Helper")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancelAndFallback,
                modifier = Modifier.testTag("cancel_root_warning_button")
            ) {
                Text("Use Legacy Mode")
            }
        }
    )
}

/**
 * Sound Calibration Bottom Sheet (v5 Section 8, 16 & 17 F1/F2/F3/F5/F9):
 * 1. AutoEq Headphone Correction (GitHub catalog search, Suggested match >= 0.6, 44.1 kHz RBJ biquad evaluation,
 *    Strength slider, Full Range +10 dB switch, Trimmed dB notice, Tilt Bass/Treble shelves, Import/Export text, MIT attribution)
 * 2. Guided Speaker Self-Calibration Wizard (RECORD_AUDIO, 3 sweeps at -18 dBFS, 20 ms IR window, >= 20% RMS improvement check)
 * 3. Personal Hearing Curve (Per-ear 6-band tone test at -24 dBFS with EQ bypassed, ±6 dB cap, 0.5 strength)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCalibrationSheet(
    activeRoute: OutputRoute,
    isLegacyTier1: Boolean,
    headphoneProfiles: List<HeadphoneProfile>,
    selectedHeadphoneProfileId: String,
    headphoneStrength: Float,
    headphoneFullRange: Boolean,
    headphoneTrimmedDb: Float,
    tiltBassDb: Float,
    tiltTrebleDb: Float,
    autoEqCatalog: List<IndexEntry>,
    autoEqLoading: Boolean,
    autoEqRateLimited: Boolean,
    autoEqOffline: Boolean,
    speakerCalEnabled: Boolean,
    speakerCalStrength: Float,
    speakerMeas31Db: List<Float>,
    speakerCorrection31Db: List<Float>,
    isCalibratingSpeaker: Boolean,
    calibrationProgress: Float,
    calibrationStatusText: String,
    hearingCurve: PersonalHearingCurve,
    connectedProductName: String?,
    onSelectHeadphoneProfile: (String) -> Unit,
    onSelectAutoEqEntry: (IndexEntry) -> Unit,
    onRefreshAutoEqCatalog: () -> Unit,
    onHeadphoneStrengthChange: (Float) -> Unit,
    onToggleHeadphoneFullRange: (Boolean) -> Unit,
    onTiltBassChange: (Float) -> Unit,
    onTiltTrebleChange: (Float) -> Unit,
    onImportCustomText: (String, String) -> Unit,
    onExportActiveText: () -> String,
    onToggleSpeakerCalibration: (Boolean) -> Unit,
    onSpeakerStrengthChange: (Float) -> Unit,
    onRunSpeakerCalibration: () -> Unit,
    onResetSpeakerCalibration: () -> Unit,
    onToggleHearingCurve: (Boolean) -> Unit,
    onUpdateHearingBand: (Boolean, Int, Float) -> Unit,
    onPlayHearingTone: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var showImportSection by remember { mutableStateOf(false) }
    var showHearingSection by remember { mutableStateOf(false) }
    var importName by remember { mutableStateOf("") }
    var importRawText by remember { mutableStateOf("") }
    var hearingEditRightEar by remember { mutableStateOf(false) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onRunSpeakerCalibration()
        }
    }

    val selectedProfile = headphoneProfiles.find { it.id == selectedHeadphoneProfileId } ?: headphoneProfiles.first()
    val headphoneCurve31 = remember(selectedProfile, headphoneStrength, headphoneFullRange, tiltBassDb, tiltTrebleDb) {
        Dsp.computeHeadphoneCorrection31(
            profile = if (selectedProfile.id == "none") null else selectedProfile,
            strength = headphoneStrength.toDouble(),
            fullRange = headphoneFullRange,
            tiltBassDb = tiltBassDb.toDouble(),
            tiltTrebleDb = tiltTrebleDb.toDouble()
        ).map { it.toFloat() }
    }

    // Section 16.5 & 16.6: Suggest top AutoEq entry when matchScore >= 0.6
    val suggestedAutoEqEntry = remember(connectedProductName, autoEqCatalog) {
        val prod = connectedProductName?.takeIf { it.isNotBlank() } ?: return@remember null
        autoEqCatalog
            .map { it to AutoEq.matchScore(prod, it.name) }
            .filter { it.second >= 0.6 }
            .maxByOrNull { it.second }
            ?.first
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sound Calibration (Dirac-Style Layer)",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "Sits under your 10 taste sliders · Active route: ${activeRoute.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLegacyTier1) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "Approximate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 1. Headphone Profile Database & AutoEq Section (v5 Section 8.1 & 16)
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
                                text = "1. AutoEq Headphone Correction",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Active: ${selectedProfile.name} (${selectedProfile.source})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onRefreshAutoEqCatalog,
                            modifier = Modifier.testTag("refresh_autoeq_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh AutoEq Index"
                            )
                        }
                    }

                    // Status chips (Rate-limited, Offline cache, GraphicEQ approximation)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (autoEqRateLimited) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "Limited, using saved data",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (autoEqOffline) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "Offline starter cache active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (selectedProfile.isApproximation) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "Graphic EQ approximation",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Suggested for connected device (Section 16.6: matchScore >= 0.6)
                    if (suggestedAutoEqEntry != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectAutoEqEntry(suggestedAutoEqEntry) }
                                .testTag("suggested_autoeq_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Suggested for $connectedProductName",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "${suggestedAutoEqEntry.name} · ${suggestedAutoEqEntry.source ?: "AutoEq"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                                Text(
                                    text = "Apply",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Strength Slider (Default 0.8)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Correction Strength",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "${(headphoneStrength * 100).roundToInt()}% (Default 80%)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = headphoneStrength,
                        onValueChange = onHeadphoneStrengthChange,
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("headphone_strength_slider")
                    )

                    // Section 16.4 Trim notice & Full Range (+10 dB boost limit) switch
                    if (headphoneTrimmedDb > 1.0f || headphoneFullRange) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = if (headphoneTrimmedDb > 1.0f) {
                                            String.format(Locale.US, "Trimmed by %.1f dB to protect headroom", headphoneTrimmedDb)
                                        } else {
                                            "Full Range Boost (+10 dB limit active)"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Full range raises boost cap from +6 dB to +10 dB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = headphoneFullRange,
                                    onCheckedChange = onToggleHeadphoneFullRange,
                                    modifier = Modifier.testTag("headphone_full_range_switch")
                                )
                            }
                        }
                    }

                    // Section 16.4 & 17.2 F3: Tilt Knobs (Bass LSC 105 Hz Q 0.7 [-6..+6 dB], Treble HSC 6 kHz Q 0.7 [-4..+4 dB])
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Target Tilt Knobs (Pre-Limit)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                if (abs(tiltBassDb) > 0.05f || abs(tiltTrebleDb) > 0.05f) {
                                    TextButton(
                                        onClick = {
                                            onTiltBassChange(0f)
                                            onTiltTrebleChange(0f)
                                        }
                                    ) {
                                        Text("Reset Tilt")
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Bass Shelf (105 Hz, Q 0.7)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.1f dB", tiltBassDb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = tiltBassDb,
                                onValueChange = onTiltBassChange,
                                valueRange = -6f..6f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tilt_bass_slider")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Treble Shelf (6 kHz, Q 0.7)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.1f dB", tiltTrebleDb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = tiltTrebleDb,
                                onValueChange = onTiltTrebleChange,
                                valueRange = -4f..4f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tilt_treble_slider")
                            )
                        }
                    }

                    if (selectedProfile.id != "none" || abs(tiltBassDb) > 0.05f || abs(tiltTrebleDb) > 0.05f) {
                        CalibrationCurvePreview(
                            label = "31-Band Correction Curve (${selectedProfile.name})",
                            curve31Db = headphoneCurve31
                        )
                    }

                    // Search Bar for Built-in + AutoEq GitHub Catalog
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        placeholder = { Text("Search AutoEq headphones (e.g., HD 650, XM5, Sundara)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("headphone_search_input")
                    )

                    if (autoEqLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // Built-in / Active Profiles
                    val filteredBuiltIn = headphoneProfiles.filter {
                        searchQuery.isBlank() ||
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.source.contains(searchQuery, ignoreCase = true)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filteredBuiltIn.forEach { prof ->
                            val isSelected = prof.id == selectedHeadphoneProfileId
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectHeadphoneProfile(prof.id) }
                                    .testTag("headphone_profile_${prof.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = prof.name,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            text = "${prof.source} · ${prof.license}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // AutoEq GitHub Index Matches
                    val matchingCatalog = remember(searchQuery, autoEqCatalog) {
                        if (searchQuery.isBlank()) {
                            autoEqCatalog.take(6)
                        } else {
                            autoEqCatalog.filter {
                                it.name.contains(searchQuery, ignoreCase = true) ||
                                    (it.source?.contains(searchQuery, ignoreCase = true) == true)
                            }.take(15)
                        }
                    }

                    if (matchingCatalog.isNotEmpty()) {
                        Text(
                            text = "AutoEq GitHub Repository (${autoEqCatalog.size} models indexed)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            matchingCatalog.forEach { entry ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectAutoEqEntry(entry) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.name,
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    append(entry.source ?: "AutoEq")
                                                    entry.rig?.let { append(" · $it") }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download AutoEq profile",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 16.6 & 17.2 F9: Import & Export ParametricEQ / GraphicEQ text
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showImportSection = !showImportSection },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("toggle_import_peq_button")
                        ) {
                            Text("Import Text", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                val exported = onExportActiveText()
                                clipboard.setText(AnnotatedString(exported))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_peq_clipboard_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Export EQ Text",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Export Copy", maxLines = 1)
                        }
                    }

                    AnimatedVisibility(visible = showImportSection) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = importName,
                                onValueChange = { importName = it },
                                label = { Text("Profile Name") },
                                placeholder = { Text("e.g., Custom Oratory1990") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = importRawText,
                                onValueChange = { importRawText = it },
                                label = { Text("Paste ParametricEQ.txt or GraphicEQ: line") },
                                placeholder = {
                                    Text("Preamp: -4.7 dB\nFilter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70")
                                },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("import_peq_text_input")
                            )
                            Button(
                                onClick = {
                                    onImportCustomText(importName, importRawText)
                                    showImportSection = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Apply Imported Correction")
                            }
                        }
                    }
                }
            }

            // 2. Speaker Self-Calibration Wizard (v5 Section 8.2)
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
                                text = "2. Speaker Self-Calibration",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "3 sweeps at -18 dBFS · 20 ms IR window · 200 Hz–10 kHz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = speakerCalEnabled,
                            onCheckedChange = onToggleSpeakerCalibration,
                            enabled = speakerCorrection31Db.isNotEmpty(),
                            modifier = Modifier.testTag("speaker_cal_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speaker Correction Strength",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "${(speakerCalStrength * 100).roundToInt()}% (Default 70%)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = speakerCalStrength,
                        onValueChange = onSpeakerStrengthChange,
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speaker_strength_slider")
                    )

                    if (isCalibratingSpeaker) {
                        LinearProgressIndicator(
                            progress = { calibrationProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (calibrationStatusText.isNotEmpty()) {
                        Text(
                            text = calibrationStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (speakerCorrection31Db.isNotEmpty()) {
                        CalibrationCurvePreview(
                            label = "Measured vs Corrected Speaker Curve (200 Hz–10 kHz)",
                            curve31Db = speakerCorrection31Db,
                            secondaryCurve31Db = speakerMeas31Db
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onRunSpeakerCalibration()
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !isCalibratingSpeaker,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("run_speaker_calibration_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Run Speaker Sweep",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (isCalibratingSpeaker) "Measuring..." else "Run 3-Sweep Cal")
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

            // 3. Personal Hearing Curve Per-Ear Tone Test (v5 Section 17.2 F5)
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
                                text = "3. Personal Hearing Curve (Per-Ear)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "In-app tone test per ear (±6 dB cap, 0.5x strength). Not a medical test.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = hearingCurve.enabled,
                            onCheckedChange = onToggleHearingCurve,
                            modifier = Modifier.testTag("hearing_curve_switch")
                        )
                    }

                    OutlinedButton(
                        onClick = { showHearingSection = !showHearingSection },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "Personal Hearing Test",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(if (showHearingSection) "Hide Per-Ear Tone Sliders" else "Configure Per-Ear Tone Curve")
                    }

                    AnimatedVisibility(visible = showHearingSection) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("Left Ear" to false, "Right Ear" to true).forEach { (earLabel, isRight) ->
                                    val selected = hearingEditRightEar == isRight
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { hearingEditRightEar = isRight },
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                    ) {
                                        Text(
                                            text = earLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            val freqLabels = listOf("250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz")
                            val activeEarOffsets = if (hearingEditRightEar) hearingCurve.rightEarDb else hearingCurve.leftEarDb

                            freqLabels.forEachIndexed { idx, fLabel ->
                                val offsetVal = activeEarOffsets.getOrElse(idx) { 0f }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = fLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.size(width = 52.dp, height = 20.dp)
                                    )
                                    Slider(
                                        value = offsetVal,
                                        onValueChange = { onUpdateHearingBand(hearingEditRightEar, idx, it) },
                                        valueRange = -6f..6f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%+.1f", offsetVal),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.size(width = 36.dp, height = 18.dp)
                                    )
                                    TextButton(
                                        onClick = { onPlayHearingTone(idx, hearingEditRightEar) }
                                    ) {
                                        Text("Tone")
                                    }
                                }
                            }

                            val earStepCsv = remember(activeEarOffsets) {
                                EqMath.formatCustomizedSoundStepsCsv(activeEarOffsets)
                            }
                            Text(
                                text = "CustomizedSoundModel Steps (INIT=-42dB, DIV=3dB): [$earStepCsv]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 4. Attribution & Licensing Notice (v5 Section 16.6 & 16.8)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Profile data: AutoEq (MIT License)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Measurements by ${selectedProfile.source}. AutoEq profiles are fetched on demand and evaluated at 44.1 kHz.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Blind ABX & Loudness-Matched A/B Tester Bottom Sheet (v5 Section 17.2 F6):
 * Randomizes hidden source `X` between `A` (Bypass at matched loudness) and `B` (Active EQ)
 * and scores user identification accuracy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbxBlindTestSheet(
    listeningChoice: String,
    correctCount: Int,
    trialCount: Int,
    lastResultText: String?,
    onSelectSource: (String) -> Unit,
    onGuessXIsB: (Boolean) -> Unit,
    onResetScore: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Blind ABX Listening Test",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Compares A (Bypass at matched makeup loudness) vs B (Active EQ) vs X (Randomly A or B) to verify audible improvements without volume bias.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 3 Audition Buttons: A, B, X
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "A" to "Listen A (Bypass)",
                    "B" to "Listen B (EQ On)",
                    "X" to "Listen X (Hidden)"
                ).forEach { (code, label) ->
                    val selected = listeningChoice == code
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectSource(code) }
                            .testTag("abx_listen_$code"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Identification Guess Buttons
            Text(
                text = "Identify Hidden Source X:",
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = { onGuessXIsB(false) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("abx_guess_a_button")
                ) {
                    Text("X is A (Bypass)", maxLines = 1)
                }
                Button(
                    onClick = { onGuessXIsB(true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("abx_guess_b_button")
                ) {
                    Text("X is B (EQ On)", maxLines = 1)
                }
            }

            // Score Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pct = if (trialCount > 0) (correctCount * 100) / trialCount else 0
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Score: $correctCount / $trialCount ($pct%)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = lastResultText ?: "Switch between A, B, and X while audio plays, then submit your guess.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onResetScore) {
                        Text("Reset")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CalibrationCurvePreview(
    label: String,
    curve31Db: List<Float>,
    secondaryCurve31Db: List<Float>? = null
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val zeroLineColor = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(68.dp)) {
                val w = size.width
                val h = size.height
                val midY = h / 2f
                drawLine(
                    color = zeroLineColor,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.dp.toPx()
                )

                fun draw31(vals: List<Float>, color: androidx.compose.ui.graphics.Color) {
                    if (vals.size < 2) return
                    val path = Path()
                    vals.forEachIndexed { idx, db ->
                        val x = (idx.toFloat() / (vals.size - 1).coerceAtLeast(1)) * w
                        val norm = (db.coerceIn(-12f, 12f) / 12f)
                        val y = midY - norm * (h * 0.42f)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                secondaryCurve31Db?.let { draw31(it, secondaryColor) }
                draw31(curve31Db, primaryColor)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndDiagnosticsSheet(
    diagnostics: DeviceDiagnostics,
    highPassHz: Float,
    splitLREnabled: Boolean,
    balanceDb: Float,
    usbBitPerfectEnabled: Boolean,
    startOnBoot: Boolean,
    compatibility10Band: Boolean,
    transientMode: Boolean,
    onHighPassChange: (Float) -> Unit,
    onToggleSplitLR: (Boolean) -> Unit,
    onBalanceChange: (Float) -> Unit,
    onToggleUsbBitPerfect: (Boolean) -> Unit,
    onToggleStartOnBoot: (Boolean) -> Unit,
    onToggleCompatibility10Band: (Boolean) -> Unit,
    onToggleTransientMode: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "DSP Engine Settings & Diagnostics (v5)",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 1. Stereo & Channel Controls (v5 Section 17.2 F4: Independent L/R EQ & Balance)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Channel Balance & Dual-Channel EQ (F4)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Independent Left / Right 10-Band EQ",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Compensates unequal drivers or per-ear preference",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = splitLREnabled,
                            onCheckedChange = onToggleSplitLR,
                            modifier = Modifier.testTag("split_lr_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "L/R Channel Balance",
                            style = MaterialTheme.typography.labelLarge
                        )
                        val balLabel = when {
                            abs(balanceDb) < 0.1f -> "Center (0.0 dB)"
                            balanceDb < 0f -> String.format(Locale.US, "Left %.1f dB", abs(balanceDb))
                            else -> String.format(Locale.US, "Right +%.1f dB", balanceDb)
                        }
                        Text(
                            text = balLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = balanceDb,
                        onValueChange = onBalanceChange,
                        valueRange = -6f..6f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lr_balance_slider")
                    )
                }
            }

            // 2. Renderer, USB Bit-Perfect & Boot Options (v5 Section 5.1, 10, 17.2 F7 & F12)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Renderer, USB DAC & Boot Options",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start Equalizer on Boot (F12)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = "Automatically restores active profile after device reboot",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = onToggleStartOnBoot,
                            modifier = Modifier.testTag("settings_start_on_boot_switch")
                        )
                    }

                    if (diagnostics.usbBitPerfectSupported || usbBitPerfectEnabled) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "USB DAC Bit-Perfect Mode (Android 14+)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = if (usbBitPerfectEnabled) "Bit-perfect ON, EQ off" else "Bypasses OS mixer and EQ for USB DAC",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = usbBitPerfectEnabled,
                                onCheckedChange = onToggleUsbBitPerfect,
                                modifier = Modifier.testTag("usb_bit_perfect_switch")
                            )
                        }
                    }

                    HorizontalDivider()

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
                                text = "Use 10 pre-EQ bands instead of 31-band PCHIP if your HAL glitches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = compatibility10Band,
                            onCheckedChange = onToggleCompatibility10Band,
                            modifier = Modifier.testTag("compat_10_band_switch")
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
                                text = "Rebuilds DynamicsProcessing with VARIANT_FAVOR_TIME_RESOLUTION",
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

            // 3. Sub-Bass High-Pass Filter (Reference Monitor / In-App DSP)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sub-Bass Butterworth High-Pass",
                            style = MaterialTheme.typography.titleSmall
                        )
                        val hpText = if (highPassHz <= 5f) "Off" else "${highPassHz.roundToInt()} Hz"
                        Text(
                            text = hpText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = highPassHz,
                        onValueChange = onHighPassChange,
                        valueRange = 0f..120f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("high_pass_slider")
                    )
                }
            }

            // 4. Hardware & Audio Effect Diagnostics
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Hardware & Audio Effect Diagnostics",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DiagnosticRow("Active OperatingMode", diagnostics.activeOperatingMode.name)
                    DiagnosticRow("DynamicsProcessing API 28+", if (diagnostics.dynamicsProcessingSupported) "Active (${diagnostics.activeRenderBandCount} bands)" else "Basic Engine Fallback")
                    DiagnosticRow("OEM Hardware DAX Bridge", when {
                        diagnostics.hardwareDaxActive -> "Active (96-Byte 20-Band GEQ)"
                        diagnostics.hardwareDaxDetected -> "Detected (9d4921da-8225-4f29...)"
                        else -> "Not Present on Device"
                    })
                    DiagnosticRow("Avg Binder Write Cost", String.format(Locale.US, "%.2f ms", diagnostics.avgBinderWriteCostMs))
                    DiagnosticRow("Virtualizer Support", if (diagnostics.virtualizerSupported) "Supported" else "May Not Apply on Route")
                    DiagnosticRow("Legacy Tier 1 Native Bands", "${diagnostics.nativeEqBandCount} bands (${diagnostics.nativeBandCentersHz.joinToString("/")} Hz)")
                    DiagnosticRow("Global Session 0 Mix", if (diagnostics.globalSession0Supported) "Supported" else "Restricted by OEM")
                    DiagnosticRow("ADB DUMP Permission", if (diagnostics.dumpPermissionGranted) "Granted" else "Not Granted")
                    DiagnosticRow("Shizuku Service", "${diagnostics.shizukuState.name} (${diagnostics.shizukuPrivilege})")
                    DiagnosticRow("Root su Binary", if (diagnostics.rootSuBinaryDetected) "Detected (APK-Only Helper)" else "Not Found")
                    DiagnosticRow("Connected Device Key", diagnostics.connectedDeviceKey)
                    DiagnosticRow("Bluetooth Codec (F10)", diagnostics.bluetoothCodecSummary ?: "Standard / Not Active")
                    DiagnosticRow("Active Audio Sessions", diagnostics.activeSessions.ifEmpty { listOf(0) }.joinToString(", "))
                }
            }

            // 5. Battery Optimization Exemption Helper
            Surface(
                shape = RoundedCornerShape(18.dp),
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
                            text = "Keep Equalizer Alive",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Exempt Audio EQ from battery optimization so background sessions never drop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.testTag("battery_optimization_button")
                    ) {
                        Text("Settings")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
