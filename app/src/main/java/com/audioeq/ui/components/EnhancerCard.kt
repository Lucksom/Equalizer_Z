package com.audioeq.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioeq.audio.AudioProfile
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.IeqPreset
import com.audioeq.audio.StereoWideningMode
import com.audioeq.model.EqConstants
import com.audioeq.ui.theme.JetBrainsMonoFontFamily
import com.audioeq.ui.theme.LocalMonetPalette
import com.audioeq.ui.theme.SpaceGroteskFontFamily
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnhancerCard(
    config: EnhancerConfig,
    enabled: Boolean,
    onSelectProfile: (AudioProfile) -> Unit,
    onSelectIeqPreset: (IeqPreset) -> Unit,
    onIeqAmountChange: (Int) -> Unit,
    onIeqCustomBandChange: (Int, Float) -> Unit,
    onResetIeqCustomBands: (Boolean) -> Unit,
    onToggleDialogueEnhancer: (Boolean) -> Unit,
    onDialogueAmountChange: (Int) -> Unit,
    onDialogueDuckingChange: (Int) -> Unit,
    onSelectStereoWideningMode: (StereoWideningMode) -> Unit = {},
    onReduceReverbAmountChange: (Int) -> Unit = {},
    onResetAllEnhancers: () -> Unit,
    onOpenCalibrationSheet: () -> Unit,
    onOpenAbxSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.sLow)
            .border(1.dp, palette.outlineV, RoundedCornerShape(24.dp))
            .padding(18.dp)
            .testTag("enhancer_card"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AUDIO ENHANCEMENT & PROFILES",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp,
                    color = palette.primary
                )
                Text(
                    text = "Intelligent EQ, dialogue boost, stereo widening & profiles",
                    fontSize = 12.sp,
                    color = palette.onVar
                )
            }

            if (config.isAnyActive()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.sHigh)
                        .border(1.dp, palette.outlineV, RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { onResetAllEnhancers() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("reset_enhancers_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Reset",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = palette.onSurface
                    )
                }
            }
        }

        EnhancerSubSection(
            title = "Profiles",
            caption = "Presets with matched spatial stage & dynamic EQ"
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio_profile_flow_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioProfile.entries.forEach { prof ->
                    val isSelected = config.profile == prof
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) palette.primaryC else palette.sHigh,
                        animationSpec = tween(160),
                        label = "profileChipBg_${prof.id}"
                    )
                    val border by animateColorAsState(
                        targetValue = if (isSelected) palette.primary else Color.Transparent,
                        animationSpec = tween(160),
                        label = "profileChipBorder_${prof.id}"
                    )
                    val fg = if (isSelected) palette.onPrimaryC else palette.onSurface

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(1.dp, border, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled) { onSelectProfile(prof) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("audio_profile_chip_${prof.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = prof.title,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = fg
                        )
                    }
                }
            }

            Text(
                text = config.profile.subtitle,
                fontSize = 11.sp,
                color = palette.onVar,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

        EnhancerSubSection(
            title = "Stereo widening",
            caption = "Phase decorrelator expands soundstage width"
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stereo_widening_flow_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StereoWideningMode.entries.forEach { mode ->
                    val isSelected = config.stereoWideningMode == mode
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) palette.primaryC else palette.sHigh,
                        animationSpec = tween(160),
                        label = "wideningChipBg_${mode.id}"
                    )
                    val border by animateColorAsState(
                        targetValue = if (isSelected) palette.primary else Color.Transparent,
                        animationSpec = tween(160),
                        label = "wideningChipBorder_${mode.id}"
                    )
                    val fg = if (isSelected) palette.onPrimaryC else palette.onSurface

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(1.dp, border, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled) { onSelectStereoWideningMode(mode) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("stereo_widening_chip_${mode.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.title,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = fg
                        )
                    }
                }
            }

            Text(
                text = config.stereoWideningMode.subtitle,
                fontSize = 11.sp,
                color = palette.onVar,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

        EnhancerSubSection(
            title = "Reduce reverb",
            caption = "Wiener spectral subtraction damps room reflection decay"
        ) {
            val amt = config.reduceReverbAmount
            val amtLabel = if (amt == 0) "No effect" else "Level $amt (-${amt * 6} dB)"

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
                        text = "Suppression floor",
                        fontSize = 12.sp,
                        color = palette.onVar
                    )
                    Text(
                        text = amtLabel,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = if (amt > 0) palette.primary else palette.onVar
                    )
                }

                Slider(
                    value = amt.toFloat(),
                    onValueChange = { onReduceReverbAmountChange(it.roundToInt()) },
                    valueRange = 0f..9f,
                    steps = 8,
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primary,
                        activeTrackColor = palette.primary,
                        inactiveTrackColor = palette.sTop
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("reduce_reverb_slider")
                )
            }
        }

        HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

        EnhancerSubSection(
            title = "Intelligent equalizer",
            caption = "Matches 20 target bands in real time"
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ieq_preset_flow_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IeqPreset.entries.forEach { p ->
                    val isSelected = config.ieqPreset == p
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) palette.primaryC else palette.sHigh,
                        animationSpec = tween(160),
                        label = "ieqChipBg_${p.id}"
                    )
                    val border by animateColorAsState(
                        targetValue = if (isSelected) palette.primary else Color.Transparent,
                        animationSpec = tween(160),
                        label = "ieqChipBorder_${p.id}"
                    )
                    val fg = if (isSelected) palette.onPrimaryC else palette.onSurface

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(1.dp, border, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled) { onSelectIeqPreset(p) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("ieq_preset_chip_${p.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = p.title,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = fg
                        )
                    }
                }
            }

            AnimatedVisibility(visible = config.ieqPreset != IeqPreset.OFF) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = config.ieqPreset.subtitle,
                        fontSize = 11.sp,
                        color = palette.onVar
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Convergence amount",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                        Text(
                            text = "${config.ieqAmount} / 16 (${(config.ieqAmount * 100 / 16)}%)",
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
                        enabled = enabled,
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

                    if (config.ieqPreset == IeqPreset.CUSTOM) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.sHigh)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Customized target curve (10 bands)",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = palette.onSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(palette.sTop)
                                            .clickable(enabled = enabled) { onResetIeqCustomBands(false) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Balanced",
                                            fontSize = 10.sp,
                                            color = palette.onSurface
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(palette.sTop)
                                            .clickable(enabled = enabled) { onResetIeqCustomBands(true) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Flat",
                                            fontSize = 10.sp,
                                            color = palette.onSurface
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                for (i in 0 until 10) {
                                    val fHz = EqConstants.TEN_BAND_FREQUENCIES_HZ[i]
                                    val fLabel = if (fHz >= 1000) "${fHz / 1000}k" else "$fHz"
                                    val g = config.ieqCustom10Db.getOrElse(i) { 0f }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = fLabel,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 9.sp,
                                            color = palette.onVar
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%+.1f", g),
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 9.sp,
                                            color = if (g != 0f) palette.primary else palette.onVar
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

        EnhancerSubSection(
            title = "Dialogue enhancer",
            caption = "Dynamic speech presence (1–4 kHz) & background ducking"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Vocal boost & clarity",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = palette.onSurface
                    )
                    Text(
                        text = "Extracts speech fundamental & overtone bands",
                        fontSize = 12.sp,
                        color = palette.onVar
                    )
                }

                Switch(
                    checked = config.dialogueEnhancerEnabled,
                    onCheckedChange = onToggleDialogueEnhancer,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.onPrimary,
                        checkedTrackColor = palette.primary,
                        uncheckedThumbColor = palette.outline,
                        uncheckedTrackColor = palette.sHigh
                    ),
                    modifier = Modifier.testTag("dialogue_enhancer_switch")
                )
            }

            AnimatedVisibility(visible = config.dialogueEnhancerEnabled) {
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
                            text = "Dialogue boost amount",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                        Text(
                            text = "${config.dialogueAmount} / 16 (${String.format(Locale.US, "+%.1f dB", config.dialogueAmount * 0.47f)})",
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
                        enabled = enabled,
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Background ducking",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                        Text(
                            text = "${config.dialogueDucking} / 16",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = palette.primary
                        )
                    }

                    Slider(
                        value = config.dialogueDucking.toFloat(),
                        onValueChange = { onDialogueDuckingChange(it.roundToInt()) },
                        valueRange = 0f..16f,
                        steps = 15,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            inactiveTrackColor = palette.sTop
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("dialogue_ducking_slider")
                    )
                }
            }
        }

        HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.sHigh)
                    .border(1.dp, palette.outlineV, RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled) { onOpenCalibrationSheet() }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("open_sound_calibration_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "Sound Calibration",
                        tint = palette.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "AutoEq & Calibrate",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = palette.onSurface
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.sHigh)
                    .border(1.dp, palette.outlineV, RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled) { onOpenAbxSheet() }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("open_abx_test_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "ABX Double Blind",
                        tint = palette.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ABX Blind Test",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = palette.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun EnhancerSubSection(
    title: String,
    caption: String,
    content: @Composable () -> Unit
) {
    val palette = LocalMonetPalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column {
            Text(
                text = title,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = palette.onSurface
            )
            Text(
                text = caption,
                fontSize = 12.sp,
                color = palette.onVar
            )
        }
        content()
    }
}
