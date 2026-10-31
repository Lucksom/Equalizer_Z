package com.audioeq.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioeq.audio.AudioProfile
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.EnhancerMath
import com.audioeq.audio.IeqPreset
import com.audioeq.ui.theme.JetBrainsMonoFontFamily
import com.audioeq.ui.theme.LocalMonetPalette
import com.audioeq.ui.theme.SpaceGroteskFontFamily
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EnhancerCard(
    config: EnhancerConfig,
    masterEnabled: Boolean,
    onSelectProfile: (AudioProfile) -> Unit,
    onSelectIeqPreset: (IeqPreset) -> Unit,
    onIeqAmountChange: (Int) -> Unit,
    onIeqCustomBandChange: (Int, Float) -> Unit,
    onResetIeqCustomBands: (Boolean) -> Unit,
    onToggleDialogueEnhancer: (Boolean) -> Unit,
    onDialogueAmountChange: (Int) -> Unit,
    onDialogueDuckingChange: (Int) -> Unit,
    onToggleBassEnhancer: (Boolean) -> Unit,
    onBassEnhancerStrengthChange: (Int) -> Unit,
    onToggleVirtualBass: (Boolean) -> Unit,
    onResetEnhancers: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenAbxTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    var showCustomBandSliders by remember { mutableStateOf(false) }

    val cardAlpha by animateFloatAsState(
        targetValue = if (masterEnabled) 1f else 0.45f,
        animationSpec = tween(220),
        label = "enhanceCardAlpha"
    )

    val enhanceSubText = if (masterEnabled) {
        "Profiles and enhancers"
    } else {
        "Master is off · enhancers disabled"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .border(
                width = 1.dp,
                color = palette.outlineV.copy(alpha = 0.55f),
                shape = RoundedCornerShape(28.dp)
            )
            .testTag("enhancers_card"),
        shape = RoundedCornerShape(28.dp),
        color = palette.sCont
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.primaryC),
                        contentAlignment = Alignment.Center
                    ) {
                        val iconColor = palette.onPrimaryC
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val w = size.width
                            val h = size.height
                            val topPoly = Path().apply {
                                moveTo(w * 0.5f, h * (3f / 24f))
                                lineTo(w * (2f / 24f), h * (8f / 24f))
                                lineTo(w * 0.5f, h * (13f / 24f))
                                lineTo(w * (22f / 24f), h * (8f / 24f))
                                close()
                            }
                            val midLine = Path().apply {
                                moveTo(w * (2f / 24f), h * (13f / 24f))
                                lineTo(w * 0.5f, h * (18f / 24f))
                                lineTo(w * (22f / 24f), h * (13f / 24f))
                            }
                            val botLine = Path().apply {
                                moveTo(w * (2f / 24f), h * (17f / 24f))
                                lineTo(w * 0.5f, h * (22f / 24f))
                                lineTo(w * (22f / 24f), h * (17f / 24f))
                            }
                            val strokeStyle = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                            drawPath(topPoly, iconColor, style = strokeStyle)
                            drawPath(midLine, iconColor, style = strokeStyle)
                            drawPath(botLine, iconColor, style = strokeStyle)
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Audio Enhancers & Profiles",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            letterSpacing = (-0.2).sp,
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = enhanceSubText,
                            fontSize = 12.sp,
                            color = palette.onVar,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = masterEnabled) { onResetEnhancers() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("reset_enhancers_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset Enhancers",
                        tint = palette.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Reset",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = palette.primary
                    )
                }
            }

            EnhancerBlockSurface {
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
                        BlockIconBox {
                            Icon(
                                imageVector = Icons.Default.Speaker,
                                contentDescription = "Profiles",
                                tint = palette.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Profiles",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = palette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = config.profile.subtitle,
                                fontSize = 12.sp,
                                color = palette.onVar,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    PillBadge(
                        text = config.profile.title,
                        active = config.profile != AudioProfile.OFF
                    )
                }

                V7SegmentedRow(
                    options = listOf(
                        AudioProfile.OFF to "Off",
                        AudioProfile.DYNAMIC to "Dynamic",
                        AudioProfile.BALANCED to "Balanced"
                    ),
                    selectedValue = config.profile,
                    enabled = masterEnabled,
                    onSelect = onSelectProfile,
                    modifier = Modifier.testTag("profile_segmented_row")
                )

                V7SegmentedRow(
                    options = listOf(
                        AudioProfile.EFFECT_3D to "3D",
                        AudioProfile.LIVE_MUSIC to "Live Music"
                    ),
                    selectedValue = config.profile,
                    enabled = masterEnabled,
                    onSelect = onSelectProfile,
                    modifier = Modifier.testTag("profile_spatial_segmented_row")
                )
            }

            EnhancerBlockSurface {
                val ieqPct = ((config.ieqAmount / 16.0f) * 100f).roundToInt()
                val ieqBadgeText = if (config.ieqPreset == IeqPreset.OFF) {
                    "Off"
                } else {
                    "${config.ieqPreset.title} · $ieqPct%"
                }

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
                        BlockIconBox {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Intelligent Equalizer",
                                tint = palette.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Intelligent Equalizer",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = palette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Adaptive target tone curve",
                                fontSize = 12.sp,
                                color = palette.onVar,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    PillBadge(
                        text = ieqBadgeText,
                        active = config.ieqPreset != IeqPreset.OFF
                    )
                }

                // .seg.four: Detailed | Balanced | Warm | Custom
                V7SegmentedRow(
                    options = listOf(
                        IeqPreset.DETAILED to "Detailed",
                        IeqPreset.BALANCED to "Balanced",
                        IeqPreset.WARM to "Warm",
                        IeqPreset.CUSTOM to "Custom"
                    ),
                    selectedValue = config.ieqPreset,
                    enabled = masterEnabled,
                    onSelect = onSelectIeqPreset,
                    modifier = Modifier.testTag("ieq_subsets_row")
                )

                // .seg (1 column): Off (disable IEQ)
                V7SegmentedRow(
                    options = listOf(IeqPreset.OFF to "Off (disable IEQ)"),
                    selectedValue = config.ieqPreset,
                    enabled = masterEnabled,
                    onSelect = onSelectIeqPreset,
                    modifier = Modifier.testTag("ieq_off_row")
                )

                // .slider-row: IEQ strength 0..16
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "IEQ strength",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                        Text(
                            text = "${config.ieqAmount} / 16 ($ieqPct%)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = palette.primary
                        )
                    }
                    Slider(
                        value = config.ieqAmount.toFloat(),
                        onValueChange = { onIeqAmountChange(it.roundToInt()) },
                        valueRange = 0f..16f,
                        steps = 15,
                        enabled = masterEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            inactiveTrackColor = palette.sTop
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("ieq_amount_slider")
                    )
                }

                // Optional Manual Custom IEQ 10-Band Target Editor when Custom is selected
                AnimatedVisibility(visible = config.ieqPreset == IeqPreset.CUSTOM) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = masterEnabled) {
                                        showCustomBandSliders = !showCustomBandSliders
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Edit Custom IEQ Bands",
                                    tint = palette.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (showCustomBandSliders) {
                                        "Hide manual 10-band custom target"
                                    } else {
                                        "Edit manual 10-band custom target"
                                    },
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = palette.primary
                                )
                            }
                            if (showCustomBandSliders) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { onResetIeqCustomBands(true) },
                                        enabled = masterEnabled
                                    ) {
                                        Text("Flat", fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = { onResetIeqCustomBands(false) },
                                        enabled = masterEnabled
                                    ) {
                                        Text("Default", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(visible = showCustomBandSliders) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(palette.sCont)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                EnhancerMath.FREQ_LABELS_10.forEachIndexed { idx, freqLabel ->
                                    val bandVal = config.ieqCustom10Db.getOrElse(idx) { 0f }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "$freqLabel Hz",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = palette.onVar,
                                            modifier = Modifier.width(46.dp)
                                        )
                                        Slider(
                                            value = bandVal,
                                            onValueChange = { onIeqCustomBandChange(idx, it) },
                                            valueRange = -12f..12f,
                                            enabled = masterEnabled,
                                            colors = SliderDefaults.colors(
                                                thumbColor = palette.primary,
                                                activeTrackColor = palette.primary,
                                                inactiveTrackColor = palette.sTop
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(26.dp)
                                                .testTag("ieq_custom_band_slider_$idx")
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%+.1f dB", bandVal),
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = palette.primary,
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.width(52.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            EnhancerBlockSurface {
                val deFormant2kDb = 6.25f * (config.dialogueAmount / 16.0f)
                val deSubText = if (config.dialogueEnhancerEnabled) {
                    String.format(
                        Locale.US,
                        "Amount %d/16 · +%.1f dB @ 2 kHz",
                        config.dialogueAmount,
                        deFormant2kDb
                    )
                } else {
                    "Off"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BlockIconBox {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = "Dialogue Enhancer",
                                tint = palette.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dialogue Enhancer",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = palette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = deSubText,
                                fontSize = 12.sp,
                                color = palette.onVar,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    V7PillSwitch(
                        checked = config.dialogueEnhancerEnabled,
                        enabled = masterEnabled,
                        onCheckedChange = onToggleDialogueEnhancer,
                        modifier = Modifier.testTag("dialogue_enhancer_switch")
                    )
                }

                // Dialogue amount slider (0..16)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dialogue amount",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                        Text(
                            text = "${config.dialogueAmount} / 16",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = palette.primary
                        )
                    }
                    Slider(
                        value = config.dialogueAmount.toFloat(),
                        onValueChange = { onDialogueAmountChange(it.roundToInt()) },
                        valueRange = 0f..16f,
                        steps = 15,
                        enabled = masterEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            inactiveTrackColor = palette.sTop
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("dialogue_amount_slider")
                    )
                }

                // Ambient low-frequency ducking (<= 180 Hz)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Ambient low-frequency ducking (≤ 180 Hz)",
                        fontSize = 12.sp,
                        color = palette.onVar,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    V7SegmentedRow(
                        options = listOf(
                            0 to "Off (0 dB)",
                            2 to "Default (−2)",
                            4 to "Strong (−4)"
                        ),
                        selectedValue = config.dialogueDucking,
                        enabled = masterEnabled,
                        onSelect = onDialogueDuckingChange,
                        modifier = Modifier.testTag("dialogue_ducking_row")
                    )
                }
            }

            EnhancerBlockSurface {
                val peakSubShelfDb = EnhancerMath.bassEnhancerBandBoostDb(
                    bandIndex = 1,
                    strengthPct = config.bassEnhancerStrength,
                    virtualBassEnabled = config.virtualBassEnabled
                )
                val bassSubText = if (config.bassEnhancerEnabled) {
                    String.format(
                        Locale.US,
                        "+%.2f dB sub shelf · %d/1000 · No volume drop",
                        peakSubShelfDb,
                        config.bassBoostStrengthPermille.toInt()
                    )
                } else {
                    "Off"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BlockIconBox {
                            Icon(
                                imageVector = Icons.Default.Equalizer,
                                contentDescription = "Bass Enhancer",
                                tint = palette.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bass Enhancer",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = palette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = bassSubText,
                                fontSize = 12.sp,
                                color = palette.onVar,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    V7PillSwitch(
                        checked = config.bassEnhancerEnabled,
                        enabled = masterEnabled,
                        onCheckedChange = onToggleBassEnhancer,
                        modifier = Modifier.testTag("bass_enhancer_switch")
                    )
                }

                // Bass boost level slider (0..100% -> 0..1000 BassBoostMz strength, zero volume suppression)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bass boost level",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%d%% (+%.1f dB)",
                                config.bassEnhancerStrength,
                                peakSubShelfDb
                            ),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = palette.primary,
                            modifier = Modifier.testTag("bass_enhancer_strength_text")
                        )
                    }
                    Slider(
                        value = config.bassEnhancerStrength.toFloat(),
                        onValueChange = { onBassEnhancerStrengthChange(it.roundToInt()) },
                        valueRange = 0f..100f,
                        steps = 19,
                        enabled = masterEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            inactiveTrackColor = palette.sTop
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("bass_enhancer_strength_slider")
                    )
                }

                HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

                V7SwitchRow(
                    title = "Virtual bass harmonic synthesis",
                    subtitle = "H2 (−2 dB), H3 (−9 dB), H4 (−12 dB)",
                    checked = config.virtualBassEnabled,
                    enabled = masterEnabled,
                    onCheckedChange = onToggleVirtualBass,
                    testTag = "virtual_bass_switch"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // #calBtn
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.sHigh)
                        .border(1.dp, palette.outlineV, RoundedCornerShape(16.dp))
                        .clickable { onOpenCalibration() }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("open_calibration_button"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Calibration",
                        tint = palette.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Calibration",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = palette.onSurface,
                        maxLines = 1
                    )
                }

                // #abxBtn
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.sHigh)
                        .border(1.dp, palette.outlineV, RoundedCornerShape(16.dp))
                        .clickable { onOpenAbxTest() }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("open_abx_test_button"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "ABX test",
                        tint = palette.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ABX test",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = palette.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun EnhancerBlockSurface(
    content: @Composable () -> Unit
) {
    val palette = LocalMonetPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.sLow)
            .border(1.dp, palette.outlineV.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

@Composable
private fun BlockIconBox(
    content: @Composable () -> Unit
) {
    val palette = LocalMonetPalette.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.sHigh),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PillBadge(
    text: String,
    active: Boolean
) {
    val palette = LocalMonetPalette.current
    val bg by animateColorAsState(
        targetValue = if (active) palette.primaryC else palette.sTop,
        animationSpec = tween(160),
        label = "pillBadgeBg"
    )
    val fg by animateColorAsState(
        targetValue = if (active) palette.onPrimaryC else palette.onVar,
        animationSpec = tween(160),
        label = "pillBadgeFg"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
