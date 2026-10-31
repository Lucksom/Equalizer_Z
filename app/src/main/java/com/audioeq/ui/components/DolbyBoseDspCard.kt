package com.audioeq.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audioeq.audio.BoseProfile
import com.audioeq.audio.DolbyBoseDspConfig
import com.audioeq.audio.DolbyBoseDspMath
import com.audioeq.audio.IeqPreset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dolby & Bose Non-Root DSP Card below the Equalizer:
 * 1. Bose Profiles (Dynamic & Balanced) — Scenario 60 (+4 dB Sub/Air) & Scenario 61 (-3 dB 1kHz Dip)
 * 2. Intelligent Equalizer (IEQ) — Detailed, Balanced, Warm, Custom (can be set manually) + Off
 * 3. Dialogue Enhancer — 1–4 kHz Formant Peaking, MBC Upward Expansion & <=180 Hz Ambient Ducking
 * 4. Bass Enhancer (with Virtual Bass Harmonic Synthesis) — +7.44 dB Shelf + H2/H3/H4 Harmonic Synthesis
 */
@Composable
fun DolbyBoseDspCard(
    config: DolbyBoseDspConfig,
    isEnabled: Boolean,
    onSelectBoseProfile: (BoseProfile) -> Unit,
    onSelectIeqPreset: (IeqPreset) -> Unit,
    onIeqAmountChange: (Int) -> Unit,
    onIeqCustomBandChange: (Int, Float) -> Unit,
    onResetIeqCustomBands: (Boolean) -> Unit,
    onToggleDialogueEnhancer: (Boolean) -> Unit,
    onDialogueAmountChange: (Int) -> Unit,
    onDialogueDuckingChange: (Int) -> Unit,
    onToggleBassEnhancer: (Boolean) -> Unit,
    onToggleVirtualBass: (Boolean) -> Unit,
    onResetDolbyBose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentAlpha = if (isEnabled) 1f else 0.48f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("dolby_bose_dsp_card"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card Header
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
                        text = "Audio Enhancers & Profiles",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Bose Profiles · Intelligent EQ · Dialogue & Bass",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = onResetDolbyBose,
                    enabled = isEnabled,
                    modifier = Modifier.testTag("reset_dolby_bose_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset Enhancers",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        maxLines = 1
                    )
                }
            }

            // ====================================================================
            // 1. BOSE PROFILES (DYNAMIC & BALANCED)
            // ====================================================================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Equalizer,
                                contentDescription = "Bose Profiles",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bose Profiles (Dynamic & Balanced)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = config.boseProfile.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        StatusPillBadge(
                            text = config.boseProfile.title,
                            active = config.boseProfile != BoseProfile.OFF
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BoseProfile.entries.forEach { profile ->
                            val selected = config.boseProfile == profile
                            SegmentedOptionPill(
                                label = profile.title,
                                selected = selected,
                                enabled = isEnabled,
                                onClick = { onSelectBoseProfile(profile) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("bose_profile_${profile.id}")
                            )
                        }
                    }
                }
            }

            // ====================================================================
            // 2. INTELLIGENT EQUALIZER (IEQ: Detailed, Balanced, Warm, Custom)
            // ====================================================================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val ieqPct = ((config.ieqAmount / 16.0f) * 100f).roundToInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Intelligent Equalizer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Intelligent Equalizer (IEQ)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (config.ieqPreset == IeqPreset.CUSTOM) {
                                        "Custom manual target curve · Strength $ieqPct%"
                                    } else {
                                        "Detailed, Balanced, Warm & Manual Custom"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        StatusPillBadge(
                            text = if (config.ieqPreset == IeqPreset.OFF) {
                                "Off"
                            } else {
                                "${config.ieqPreset.title} · $ieqPct%"
                            },
                            active = config.ieqPreset != IeqPreset.OFF
                        )
                    }

                    // Uniform Segmented Layout:
                    // Row 1: Detailed | Balanced | Warm | Custom
                    // Row 2: Off (Full-width or paired cleanly) -> Using 5 equal-height pills across 2 clean rows:
                    // Top row: Detailed, Balanced, Warm, Custom (the 4 subsets)
                    // Bottom row or 5-segment bar: Let's use a 2-row uniform container so every word has generous space and never wraps
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                IeqPreset.DETAILED,
                                IeqPreset.BALANCED,
                                IeqPreset.WARM,
                                IeqPreset.CUSTOM
                            ).forEach { preset ->
                                SegmentedOptionPill(
                                    label = preset.title,
                                    selected = config.ieqPreset == preset,
                                    enabled = isEnabled,
                                    onClick = { onSelectIeqPreset(preset) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ieq_chip_${preset.id}")
                                )
                            }
                        }

                        SegmentedOptionPill(
                            label = "Off (Disable IEQ)",
                            selected = config.ieqPreset == IeqPreset.OFF,
                            enabled = isEnabled,
                            onClick = { onSelectIeqPreset(IeqPreset.OFF) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ieq_chip_${IeqPreset.OFF.id}")
                        )
                    }

                    AnimatedVisibility(visible = config.ieqPreset != IeqPreset.OFF) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "IEQ Strength",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${config.ieqAmount} / 16 ($ieqPct%)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = config.ieqAmount.toFloat(),
                                    onValueChange = { onIeqAmountChange(it.roundToInt()) },
                                    valueRange = 0f..16f,
                                    steps = 15,
                                    enabled = isEnabled,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ieq_amount_slider")
                                )
                            }

                            // Manual Custom IEQ 10-Band Target Editor when Custom is selected
                            AnimatedVisibility(visible = config.ieqPreset == IeqPreset.CUSTOM) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ieq_custom_manual_panel")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Tune,
                                                    contentDescription = "Manual Custom IEQ",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Manual Custom IEQ Target (dB)",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                TextButton(
                                                    onClick = { onResetIeqCustomBands(true) },
                                                    enabled = isEnabled,
                                                    modifier = Modifier.testTag("ieq_custom_flat_button")
                                                ) {
                                                    Text("Flat", style = MaterialTheme.typography.labelSmall)
                                                }
                                                TextButton(
                                                    onClick = { onResetIeqCustomBands(false) },
                                                    enabled = isEnabled,
                                                    modifier = Modifier.testTag("ieq_custom_default_button")
                                                ) {
                                                    Text("Default", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }

                                        DolbyBoseDspMath.FREQ_LABELS_10.forEachIndexed { idx, freqLabel ->
                                            val bandVal = config.ieqCustom10Db.getOrElse(idx) { 0f }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "$freqLabel Hz",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.width(46.dp),
                                                    maxLines = 1
                                                )
                                                Slider(
                                                    value = bandVal,
                                                    onValueChange = { onIeqCustomBandChange(idx, it) },
                                                    valueRange = -12f..12f,
                                                    enabled = isEnabled,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(28.dp)
                                                        .testTag("ieq_custom_band_slider_$idx")
                                                )
                                                Text(
                                                    text = String.format(Locale.US, "%+.1f dB", bandVal),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    textAlign = TextAlign.End,
                                                    modifier = Modifier.width(52.dp),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ====================================================================
            // 3. DIALOGUE ENHANCER (Formant Expansion 1-4 kHz + Ambient Ducking <=180 Hz)
            // ====================================================================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val deFactor = if (config.dialogueEnhancerEnabled) config.dialogueAmount / 16.0f else 0f
                    val formant2kDb = 6.25f * deFactor
                    val duckDb = config.dialogueDucking * deFactor

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = "Dialogue Enhancer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dialogue Enhancer",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (config.dialogueEnhancerEnabled) {
                                        String.format(
                                            Locale.US,
                                            "Amount %d/16 · +%.1f dB @ 2 kHz · -%.1f dB Ducking",
                                            config.dialogueAmount,
                                            formant2kDb,
                                            duckDb
                                        )
                                    } else {
                                        "1–4 kHz Formant Peaking & Upward Vocal Expansion"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Switch(
                            checked = config.dialogueEnhancerEnabled,
                            onCheckedChange = onToggleDialogueEnhancer,
                            enabled = isEnabled,
                            modifier = Modifier.testTag("dialogue_enhancer_switch")
                        )
                    }

                    AnimatedVisibility(visible = config.dialogueEnhancerEnabled) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Dialogue Amount",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${config.dialogueAmount} / 16",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = config.dialogueAmount.toFloat(),
                                    onValueChange = { onDialogueAmountChange(it.roundToInt()) },
                                    valueRange = 0f..16f,
                                    steps = 15,
                                    enabled = isEnabled,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dialogue_amount_slider")
                                )
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Ambient Low-Frequency Ducking (≤ 180 Hz)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        0 to "Off (0 dB)",
                                        2 to "Default (-2 dB)",
                                        4 to "Strong (-4 dB)"
                                    ).forEach { (duckVal, label) ->
                                        SegmentedOptionPill(
                                            label = label,
                                            selected = config.dialogueDucking == duckVal,
                                            enabled = isEnabled,
                                            onClick = { onDialogueDuckingChange(duckVal) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("dialogue_ducking_chip_$duckVal")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ====================================================================
            // 4. BASS ENHANCER (WITH VIRTUAL BASS HARMONIC SYNTHESIS)
            // ====================================================================
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speaker,
                                contentDescription = "Bass Enhancer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bass Enhancer",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (config.bassEnhancerEnabled) {
                                        if (config.virtualBassEnabled) {
                                            "+7.44 dB Sub Shelf · Virtual Harmonics Active"
                                        } else {
                                            "+7.44 dB Sub Shelf · Cutoff 504 Hz"
                                        }
                                    } else {
                                        "Sub-bass boost & Virtual Bass Harmonic Synthesis"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Switch(
                            checked = config.bassEnhancerEnabled,
                            onCheckedChange = onToggleBassEnhancer,
                            enabled = isEnabled,
                            modifier = Modifier.testTag("bass_enhancer_switch")
                        )
                    }

                    AnimatedVisibility(visible = config.bassEnhancerEnabled) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = "Virtual Bass Harmonic Synthesis",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "H2 (-2 dB), H3 (-9 dB) & H4 (-12 dB) in 229–494 Hz window",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = config.virtualBassEnabled,
                                    onCheckedChange = onToggleVirtualBass,
                                    enabled = isEnabled,
                                    modifier = Modifier.testTag("virtual_bass_switch")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPillBadge(
    text: String,
    active: Boolean
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SegmentedOptionPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillBg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "segmentedPillBg"
    )
    val pillContent by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "segmentedPillContent"
    )

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(pillBg)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = pillContent,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
