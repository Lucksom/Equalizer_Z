package com.audioeq.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audioeq.model.EqBandSpec
import com.audioeq.model.EqConstants
import com.audioeq.model.FrequencySection
import com.audioeq.model.OutputRoute
import com.audioeq.model.PresetConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * Unified 10-Band Equalizer Card updated for Deep Optimization v4:
 * - Header with "Equalizer", Active Route badge (`Speaker`, `Wired Headphones`, `Bluetooth`, `USB DAC`), and Headroom / Ceiling Load meter (`ceilingLoad = max(0, max(effective) - 3) dB`, warning above 4 dB)
 * - Preset Pills (`Flat`, `Balanced Deep`, `Bass Boost`, `Vocal Clarity`, `Percussion Clarity`, `Cinema`, plus custom presets)
 * - Live Frequency Response Graph (`equalizer.html` curve)
 * - True v4 Category Boundary Strip (`Bass 20–350 Hz`, `Mids 350 Hz–2.8 kHz`, `Treble 2.8–11 kHz`, `Air 11–20 kHz`)
 * - 10 Fluid Vertical Sliders (with raw pointer tracking so dragging never gets stuck)
 * - v4 Intensity Macro (`Light 0.6x`, `Standard 1.0x`, `Deep+ 1.3x`), `Volume Contour` toggle, `A/B Loudness Compare` button, and `Sound Calibration` launcher
 * - Footer with `Enabled` checkbox and `Reset` button
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedEqualizerCard(
    presets: List<PresetConfig>,
    selectedPresetId: String,
    selectedDaxProfile: com.audioeq.audio.DaxProfile = com.audioeq.audio.DaxProfile.MUSIC,
    targetGainsDb: List<Float>,
    splitLREnabled: Boolean = false,
    editingRightChannel: Boolean = false,
    intensity: Float,
    volumeContourEnabled: Boolean,
    nightModeEnabled: Boolean = false,
    usbBitPerfectActive: Boolean = false,
    isAbCompare: Boolean,
    activeRoute: OutputRoute,
    inputGainDb: Float,
    makeupGainDb: Float,
    ceilingLoadDb: Float,
    activeCalibrationLabel: String?,
    isEnabled: Boolean,
    onSelectDaxProfile: (com.audioeq.audio.DaxProfile) -> Unit = {},
    onSelectPreset: (PresetConfig) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onSelectEditingChannel: (Boolean) -> Unit = {},
    onBandGainChange: (Int, Float) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onToggleVolumeContour: (Boolean) -> Unit,
    onToggleNightMode: (Boolean) -> Unit = {},
    onToggleAbCompare: (Boolean) -> Unit,
    onOpenAbxTest: () -> Unit = {},
    onOpenCalibration: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onResetFlat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayGains = remember {
        mutableStateListOf<Float>().apply {
            for (i in 0 until 10) {
                add(targetGainsDb.getOrElse(i) { 0f } * intensity)
            }
        }
    }
    val draggingBands = remember {
        mutableStateListOf<Boolean>().apply {
            repeat(10) { add(false) }
        }
    }

    val currentTargetGains by rememberUpdatedState(targetGainsDb)
    val currentIntensity by rememberUpdatedState(intensity)

    // Frame-rate independent exponential smoothing loop matching equalizer.html: k = 1 - exp(-dt * 18)
    LaunchedEffect(targetGainsDb, intensity) {
        var lastNanos = withFrameNanos { it }
        var moving = true
        while (moving) {
            val nowNanos = withFrameNanos { it }
            val dt = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastNanos = nowNanos
            val k = (1f - exp(-dt * 18f)).coerceIn(0.05f, 1f)

            moving = false
            for (i in 0 until 10) {
                val target = currentTargetGains.getOrElse(i) { 0f }
                if (draggingBands[i]) {
                    displayGains[i] = target
                } else {
                    val diff = target - displayGains[i]
                    if (abs(diff) > 0.005f) {
                        displayGains[i] = displayGains[i] + diff * k
                        moving = true
                    } else {
                        displayGains[i] = target
                    }
                }
            }
        }
    }

    val bandsAlpha by animateFloatAsState(
        targetValue = if (isEnabled && !isAbCompare && !usbBitPerfectActive) 1f else 0.45f,
        animationSpec = tween(250),
        label = "bandsAlpha"
    )

    // Graph shows user × intensity when active, or flat when A/B compare is held
    val graphDisplayGains = remember(displayGains.toList(), intensity, isAbCompare, usbBitPerfectActive) {
        if (isAbCompare || usbBitPerfectActive) {
            List(10) { 0f }
        } else {
            displayGains.map { (it * intensity).coerceIn(-12f, 12f) }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("unified_equalizer_card"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            // Header: "Equalizer" + Route & Headroom / Ceiling Meter Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "Equalizer",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (usbBitPerfectActive) {
                            "Route: ${activeRoute.label} · Bit-perfect ON, EQ off"
                        } else {
                            "Route: ${activeRoute.label} · In ${String.format(Locale.US, "%.1f", inputGainDb)} dB · Makeup +${String.format(Locale.US, "%.1f", makeupGainDb)} dB"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // v6 Section 3.1 Rule 3 Headroom Meter (ceilingLoadDb = max(0, max(effective) - 3) dB, warn > 4 dB)
                    val isCeilingWarn = ceilingLoadDb > 4.0f
                    val meterBg by animateColorAsState(
                        targetValue = if (isCeilingWarn) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        label = "headroomMeterBg"
                    )
                    val meterContent by animateColorAsState(
                        targetValue = if (isCeilingWarn) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        label = "headroomMeterContent"
                    )

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = meterBg,
                        contentColor = meterContent,
                        modifier = Modifier.testTag("headroom_ceiling_meter")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isCeilingWarn) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "High ceiling load warning",
                                    modifier = Modifier.height(14.dp)
                                )
                            }
                            Text(
                                text = "Ceiling ${String.format(Locale.US, "%.1f", ceilingLoadDb)} dB",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }

                    // Interactive Enable / Disable Power Icon Button at the top of the Equalizer Card
                    val powerBtnBg by animateColorAsState(
                        targetValue = if (isEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        label = "eqPowerBtnBg"
                    )
                    val powerBtnTint by animateColorAsState(
                        targetValue = if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "eqPowerBtnTint"
                    )

                    IconButton(
                        onClick = { onToggleEnabled(!isEnabled) },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(powerBtnBg)
                            .border(
                                width = 1.dp,
                                color = if (isEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                            .testTag("global_power_switch")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = if (isEnabled) "Disable Equalizer" else "Enable Equalizer",
                            tint = powerBtnTint
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // v6 Section 4: The 4 Acoustic Profiles (0: Dynamic, 1: Video / Movie, 2: Music, 3: Voice)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dax_profile_selector_bar")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Acoustic Profile (dax-default.xml)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val ieqLabel = when (selectedDaxProfile.ieqTargetId) {
                            "ieq_balanced" -> "IEQ Balanced"
                            "ieq_warm" -> "IEQ Warm"
                            else -> "Pure Curve"
                        }
                        val dlgSteps = if (activeRoute == OutputRoute.SPEAKER) {
                            selectedDaxProfile.dialogStepsSpk
                        } else {
                            selectedDaxProfile.dialogStepsHp
                        }
                        val lvlLabel = if (selectedDaxProfile.levelerOn) "Leveler ON" else "Leveler OFF"
                        Text(
                            text = "$ieqLabel · Dlg +${String.format(Locale.US, "%.1f", dlgSteps * 0.45)} dB · $lvlLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.audioeq.audio.DaxProfile.entries.forEach { prof ->
                            val selected = prof == selectedDaxProfile
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectDaxProfile(prof) }
                                    .testTag("dax_profile_${prof.id}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            ) {
                                Text(
                                    text = if (prof == com.audioeq.audio.DaxProfile.MOVIE) "Movie" else prof.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Preset Chips (Flat, Balanced Deep, Bass Boost, Vocal Clarity, Percussion Clarity, Cinema, + Saved)
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preset_chip_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = preset.id == selectedPresetId
                    EqualizerPresetPill(
                        preset = preset,
                        isSelected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        onDelete = if (!preset.isBuiltIn) {
                            { onDeleteCustomPreset(preset.id) }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Response Graph (matching equalizer.html .graph)
            FrequencyResponseGraph(
                displayGainsDb = graphDisplayGains,
                isEnabled = isEnabled && !usbBitPerfectActive
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Optional Independent Left / Right Channel Selector when Split L/R EQ is enabled (v5 Section 17.2 F4)
            if (splitLREnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Left Channel (L)" to false, "Right Channel (R)" to true).forEach { (label, isRight) ->
                        val selected = editingRightChannel == isRight
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectEditingChannel(isRight) }
                                .testTag("channel_tab_${if (isRight) "right" else "left"}"),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // v5 Section 2 True Category Boundaries Strip:
            // Bass (20–350 Hz) | Mids (350–2.8k Hz) | Treble (2.8–11 kHz) | Air (11–20 kHz)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FrequencySection.entries.forEach { sec ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = sec.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = sec.rangeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 10-Band Fluid Vertical Sliders Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .alpha(bandsAlpha)
                    .padding(horizontal = 6.dp, vertical = 16.dp)
                    .testTag("ten_band_sliders_container")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EqConstants.MASTER_BANDS.forEach { band ->
                        val gain = displayGains.getOrElse(band.index) { 0f }
                        FluidVerticalBandSlider(
                            band = band,
                            gainDb = gain,
                            enabled = isEnabled && !usbBitPerfectActive,
                            onDragStateChange = { isDragging ->
                                draggingBands[band.index] = isDragging
                            },
                            onGainChange = { newDb ->
                                displayGains[band.index] = newDb
                                onBandGainChange(band.index, newDb)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // v5 Section 4.2, 7, 10 & 17: Ordered Intensity Macro, Volume Contour, Night Mode, A/B Compare, Blind ABX & Sound Calibration Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Intensity Header + 3 Equal-Width Mode Chips
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "EQ Intensity",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fx scale", intensity),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val options = listOf(
                                Triple("Light", "0.6x", 0.6f),
                                Triple("Standard", "1.0x", 1.0f),
                                Triple("Deep+", "1.3x", 1.3f)
                            )
                            options.forEach { (label, sub, value) ->
                                val selected = abs(intensity - value) < 0.08f
                                val chipBg = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                                val chipText = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onIntensityChange(value) }
                                        .testTag("intensity_chip_${label.lowercase()}"),
                                    shape = RoundedCornerShape(12.dp),
                                    color = chipBg
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = chipText,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = chipText.copy(alpha = 0.8f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Volume Contour Full-Width Switch Row
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "Volume Contour (ISO 226)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Boosts low-volume bass & treble balance",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = volumeContourEnabled,
                                onCheckedChange = onToggleVolumeContour,
                                modifier = Modifier.testTag("volume_contour_switch")
                            )
                        }
                    }

                    // 3. Night Mode (F8: Lower ceiling thresholds & higher ratios for quiet listening)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "Night Mode (Quiet Ceilings)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Lowers 6-band ceiling thresholds (-6 dB) without peaks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = nightModeEnabled,
                                onCheckedChange = onToggleNightMode,
                                modifier = Modifier.testTag("night_mode_switch")
                            )
                        }
                    }

                    // 4. Two Equal-Width Action Buttons: A/B Compare & Sound Calibration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val abContainerColor = if (isAbCompare) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                        val abContentColor = if (isAbCompare) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }

                        FilledTonalButton(
                            onClick = { onToggleAbCompare(!isAbCompare) },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = abContainerColor,
                                contentColor = abContentColor
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("ab_compare_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "A/B Loudness Compare",
                                modifier = Modifier.height(18.dp)
                            )
                            Spacer(modifier = Modifier.padding(3.dp))
                            Text(
                                text = if (isAbCompare) "A/B: Bypass" else "A/B Compare",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        FilledTonalButton(
                            onClick = onOpenCalibration,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("open_calibration_button")
                        ) {
                            Icon(
                                imageVector = if (activeRoute == OutputRoute.SPEAKER) Icons.Default.Speaker else Icons.Default.Headphones,
                                contentDescription = "Sound Calibration",
                                modifier = Modifier.height(18.dp)
                            )
                            Spacer(modifier = Modifier.padding(3.dp))
                            Text(
                                text = activeCalibrationLabel ?: "Sound Calibrate",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 5. Blind ABX Tool Launcher (v5 Section 17.2 F6)
                    OutlinedButton(
                        onClick = onOpenAbxTest,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("open_abx_test_button")
                    ) {
                        Text(
                            text = "Blind ABX Listening Test (A vs B vs Hidden X)",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Footer: Status Indicator + Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEnabled) "Equalizer Active" else "Equalizer Bypassed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("power_status_text")
                )

                OutlinedButton(
                    onClick = onResetFlat,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("reset_equalizer_button")
                ) {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EqualizerPresetPill(
    preset: PresetConfig,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (isSelected) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("preset_chip_${preset.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = preset.name,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
        if (onDelete != null) {
            Text(
                text = "×",
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(horizontal = 2.dp)
            )
        }
    }
}

@Composable
private fun FluidVerticalBandSlider(
    band: EqBandSpec,
    gainDb: Float,
    enabled: Boolean,
    onDragStateChange: (Boolean) -> Unit,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    var isPressed by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) 1.18f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 600f),
        label = "thumbScale"
    )

    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnGainChange by rememberUpdatedState(onGainChange)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)

    val formattedGain = remember(gainDb) {
        val sign = if (gainDb > 0.05f) "+" else ""
        String.format(Locale.US, "%s%.1f", sign, gainDb)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formattedGain,
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .height(20.dp)
                .padding(bottom = 4.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag("band_slider_${band.frequencyHz}")
                .semantics {
                    contentDescription = "${band.label} Hz gain"
                }
                .pointerInput(band.index) {
                    var lastTapTime = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentEnabled) return@awaitEachGesture

                        down.consume()

                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 280L) {
                            currentOnDragStateChange(true)
                            currentOnGainChange(0f)
                            currentOnDragStateChange(false)
                            lastTapTime = 0L
                            return@awaitEachGesture
                        }
                        lastTapTime = now

                        isPressed = true
                        currentOnDragStateChange(true)

                        val padPx = 14.dp.toPx()
                        fun yToDb(y: Float): Float {
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val usable = (h - 2f * padPx).coerceAtLeast(1f)
                            val norm = (1f - ((y - padPx) / usable)).coerceIn(0f, 1f)
                            return (-12f + norm * 24f).coerceIn(-12f, 12f)
                        }

                        currentOnGainChange(yToDb(down.position.y))

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }

                                change.consume()
                                currentOnGainChange(yToDb(change.position.y))
                            }
                        } finally {
                            isPressed = false
                            currentOnDragStateChange(false)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                val trackWidth = 14.dp.toPx()
                val pad = 14.dp.toPx()
                val usableHeight = (size.height - 2f * pad).coerceAtLeast(1f)
                val centerX = size.width * 0.5f

                val pct = ((gainDb.coerceIn(-12f, 12f) + 12f) / 24f).coerceIn(0f, 1f)
                val thumbY = pad + (1f - pct) * usableHeight

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(centerX - trackWidth / 2f, pad),
                    size = Size(trackWidth, usableHeight),
                    cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
                )

                val fillTop = thumbY
                val fillHeight = (pad + usableHeight - fillTop).coerceAtLeast(0f)
                if (fillHeight > 0.5f) {
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(centerX - trackWidth / 2f, fillTop),
                        size = Size(trackWidth, fillHeight),
                        cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
                    )
                }

                val baseRadius = 11.dp.toPx() * thumbScale
                drawCircle(
                    color = Color.Black.copy(alpha = 0.24f),
                    radius = baseRadius + 1.dp.toPx(),
                    center = Offset(centerX, thumbY + 1.5.dp.toPx())
                )
                drawCircle(
                    color = primaryColor,
                    radius = baseRadius,
                    center = Offset(centerX, thumbY)
                )
                drawCircle(
                    color = surfaceColor,
                    radius = baseRadius,
                    center = Offset(centerX, thumbY),
                    style = Stroke(width = 3.5.dp.toPx())
                )
            }
        }

        Text(
            text = band.label,
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
