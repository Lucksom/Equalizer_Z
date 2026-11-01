package com.audioeq.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onToggleBassEnhancer: (Boolean) -> Unit = {},
    onBassStrengthChange: (Int) -> Unit = {},
    onToggleVirtualBass: (Boolean) -> Unit = {},
    onSelectStereoWideningMode: (StereoWideningMode) -> Unit = {},
    onReduceReverbAmountChange: (Int) -> Unit = {},
    onResetAllEnhancers: () -> Unit,
    onOpenCalibrationSheet: () -> Unit,
    onOpenAbxSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, palette.outlineV.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
            .testTag("enhancer_card"),
        shape = RoundedCornerShape(28.dp),
        color = palette.sCont
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.primaryC),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val w = size.width
                            val h = size.height
                            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            val topLayer = Path().apply {
                                moveTo(w * 0.50f, h * 0.14f)
                                lineTo(w * 0.88f, h * 0.34f)
                                lineTo(w * 0.50f, h * 0.54f)
                                lineTo(w * 0.12f, h * 0.34f)
                                close()
                            }
                            drawPath(topLayer, color = palette.onPrimaryC, style = stroke)
                            val midLayer = Path().apply {
                                moveTo(w * 0.12f, h * 0.52f)
                                lineTo(w * 0.50f, h * 0.72f)
                                lineTo(w * 0.88f, h * 0.52f)
                            }
                            drawPath(midLayer, color = palette.onPrimaryC, style = stroke)
                            val botLayer = Path().apply {
                                moveTo(w * 0.12f, h * 0.70f)
                                lineTo(w * 0.50f, h * 0.90f)
                                lineTo(w * 0.88f, h * 0.70f)
                            }
                            drawPath(botLayer, color = palette.onPrimaryC, style = stroke)
                        }
                    }
                    Column {
                        Text(
                            text = "Audio Enhancers & Profiles",
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSurface
                        )
                        Text(
                            text = "Profiles and enhancers",
                            fontSize = 12.sp,
                            color = palette.onVar
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.sHigh)
                        .border(1.dp, palette.outlineV, RoundedCornerShape(999.dp))
                        .clickable(enabled = enabled) { onResetAllEnhancers() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("reset_enhancers_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Canvas(modifier = Modifier.size(13.dp)) {
                            val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            drawArc(
                                color = palette.onVar,
                                startAngle = -210f,
                                sweepAngle = 290f,
                                useCenter = false,
                                style = s
                            )
                        }
                        Text(
                            text = "Reset",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = palette.onVar
                        )
                    }
                }
            }

            EnhancerModuleCard(
                title = "Profiles",
                subtitle = config.profile.subtitle,
                badgeText = config.profile.badgeLabel,
                isActive = config.profile != AudioProfile.OFF,
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val spk = Path().apply {
                            moveTo(w * 0.16f, h * 0.38f)
                            lineTo(w * 0.34f, h * 0.38f)
                            lineTo(w * 0.56f, h * 0.18f)
                            lineTo(w * 0.56f, h * 0.82f)
                            lineTo(w * 0.34f, h * 0.62f)
                            lineTo(w * 0.16f, h * 0.62f)
                            close()
                        }
                        drawPath(spk, color = tint, style = s)
                        drawArc(
                            color = tint,
                            startAngle = -45f,
                            sweepAngle = 90f,
                            useCenter = false,
                            style = s
                        )
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("audio_profile_flow_row"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val profiles = AudioProfile.entries
                    val row1 = profiles.take(3)
                    val row2 = profiles.drop(3)

                    listOf(row1, row2).forEach { rowProfiles ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.sHigh)
                                .border(1.dp, palette.outlineV.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowProfiles.forEach { prof ->
                                val isSelected = config.profile == prof
                                val bg by animateColorAsState(
                                    targetValue = if (isSelected) palette.primary else Color.Transparent,
                                    animationSpec = tween(160),
                                    label = "profileSegBg_${prof.id}"
                                )
                                val fg by animateColorAsState(
                                    targetValue = if (isSelected) palette.onPrimary else palette.onVar,
                                    animationSpec = tween(160),
                                    label = "profileSegFg_${prof.id}"
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(bg)
                                        .clickable(enabled = enabled) { onSelectProfile(prof) }
                                        .testTag("audio_profile_chip_${prof.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = prof.title,
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = fg,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            EnhancerModuleCard(
                title = "Stereo Widening",
                subtitle = config.stereoWideningMode.subtitle,
                badgeText = config.stereoWideningMode.title,
                isActive = config.stereoWideningMode != StereoWideningMode.NO_EFFECT,
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        drawCircle(color = tint, radius = w * 0.12f, center = Offset(w * 0.5f, h * 0.5f))
                        drawArc(
                            color = tint,
                            startAngle = 135f,
                            sweepAngle = 90f,
                            useCenter = false,
                            style = s
                        )
                        drawArc(
                            color = tint,
                            startAngle = -45f,
                            sweepAngle = 90f,
                            useCenter = false,
                            style = s
                        )
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.sHigh)
                        .border(1.dp, palette.outlineV.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(3.dp)
                        .testTag("stereo_widening_flow_row"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StereoWideningMode.entries.forEach { mode ->
                        val isSelected = config.stereoWideningMode == mode
                        val bg by animateColorAsState(
                            targetValue = if (isSelected) palette.primary else Color.Transparent,
                            animationSpec = tween(160),
                            label = "wideningSegBg_${mode.id}"
                        )
                        val fg by animateColorAsState(
                            targetValue = if (isSelected) palette.onPrimary else palette.onVar,
                            animationSpec = tween(160),
                            label = "wideningSegFg_${mode.id}"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(bg)
                                .clickable(enabled = enabled) { onSelectStereoWideningMode(mode) }
                                .testTag("stereo_widening_chip_${mode.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.title,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            val amt = config.reduceReverbAmount
            val reverbBadge = if (amt == 0) "Off" else "Lv $amt (-${amt * 6} dB)"
            EnhancerModuleCard(
                title = "Reduce Reverb",
                subtitle = "Wiener spectral subtraction damps room reflection decay",
                badgeText = reverbBadge,
                isActive = amt > 0,
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val sw = 1.8.dp.toPx()
                        drawLine(tint, Offset(w * 0.18f, h * 0.22f), Offset(w * 0.18f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.40f, h * 0.34f), Offset(w * 0.40f, h * 0.66f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.62f, h * 0.42f), Offset(w * 0.62f, h * 0.58f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.84f, h * 0.47f), Offset(w * 0.84f, h * 0.53f), strokeWidth = sw, cap = StrokeCap.Round)
                    }
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Slider(
                        value = amt.toFloat(),
                        onValueChange = { onReduceReverbAmountChange(it.roundToInt()) },
                        valueRange = 0f..9f,
                        steps = 8,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            inactiveTrackColor = palette.sHigh
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("reduce_reverb_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0 (Bypass)", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = palette.onVar)
                        Text("Near (2)", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = palette.onVar)
                        Text("Mid (5)", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = palette.onVar)
                        Text("Max (9)", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = palette.onVar)
                    }
                }
            }

            EnhancerModuleCard(
                title = "Bass Enhancer",
                subtitle = "Sub-bass punch exciter & missing-fundamental harmonics",
                badgeText = null,
                isActive = config.bassEnhancerEnabled || config.virtualBassEnabled,
                headerTrailing = {
                    V7PillSwitch(
                        checked = config.bassEnhancerEnabled,
                        enabled = enabled,
                        onCheckedChange = { onToggleBassEnhancer(it) },
                        modifier = Modifier.testTag("bass_enhancer_switch")
                    )
                },
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val s = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(color = tint, radius = w * 0.40f, center = Offset(w * 0.5f, h * 0.5f), style = s)
                        drawCircle(color = tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.5f))
                    }
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AnimatedVisibility(visible = config.bassEnhancerEnabled) {
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
                                    text = "Sub-bass punch & contour",
                                    fontSize = 12.sp,
                                    color = palette.onVar
                                )
                                Text(
                                    text = "${config.bassEnhancerStrength}% (+${String.format(Locale.US, "%.1f", config.bassEnhancerStrength * 0.062f)} dB)",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = palette.primary
                                )
                            }
                            Slider(
                                value = config.bassEnhancerStrength.toFloat(),
                                onValueChange = { onBassStrengthChange(it.roundToInt()) },
                                valueRange = 0f..100f,
                                enabled = enabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = palette.primary,
                                    activeTrackColor = palette.primary,
                                    inactiveTrackColor = palette.sHigh
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .testTag("bass_strength_slider")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.sHigh)
                            .border(1.dp, palette.outlineV.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled) { onToggleVirtualBass(!config.virtualBassEnabled) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Harmonic Virtual Bass",
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = palette.onSurface
                            )
                            Text(
                                text = "2nd/3rd/4th harmonic generator (30–90 Hz → 75–270 Hz)",
                                fontSize = 11.sp,
                                color = palette.onVar
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        V7PillSwitch(
                            checked = config.virtualBassEnabled,
                            enabled = enabled,
                            onCheckedChange = { onToggleVirtualBass(it) },
                            modifier = Modifier.testTag("virtual_bass_switch")
                        )
                    }
                }
            }

            EnhancerModuleCard(
                title = "Intelligent Equalizer",
                subtitle = config.ieqPreset.subtitle,
                badgeText = config.ieqPreset.title,
                isActive = config.ieqPreset != IeqPreset.OFF,
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val sw = 1.8.dp.toPx()
                        drawLine(tint, Offset(w * 0.20f, h * 0.72f), Offset(w * 0.20f, h * 0.35f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.40f, h * 0.78f), Offset(w * 0.40f, h * 0.20f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.60f, h * 0.70f), Offset(w * 0.60f, h * 0.42f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.80f, h * 0.75f), Offset(w * 0.80f, h * 0.26f), strokeWidth = sw, cap = StrokeCap.Round)
                    }
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ieq_preset_flow_row"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IeqPreset.entries.forEach { p ->
                            val isSelected = config.ieqPreset == p
                            val bg by animateColorAsState(
                                targetValue = if (isSelected) palette.primary else palette.sHigh,
                                animationSpec = tween(160),
                                label = "ieqChipBg_${p.id}"
                            )
                            val fg by animateColorAsState(
                                targetValue = if (isSelected) palette.onPrimary else palette.onVar,
                                animationSpec = tween(160),
                                label = "ieqChipFg_${p.id}"
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bg)
                                    .border(
                                        1.dp,
                                        if (isSelected) palette.primary else palette.outlineV.copy(alpha = 0.45f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable(enabled = enabled) { onSelectIeqPreset(p) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                                    .testTag("ieq_preset_chip_${p.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = p.title,
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontSize = 12.sp,
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Convergence strength",
                                    fontSize = 12.sp,
                                    color = palette.onVar
                                )
                                Text(
                                    text = "${config.ieqAmount} / 16 (${(config.ieqAmount * 100 / 16)}%)",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
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
                                    inactiveTrackColor = palette.sHigh
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
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable(enabled = enabled) {
                                                        val next = if (g >= 6f) -6f else g + 1.5f
                                                        onIeqCustomBandChange(i, next)
                                                    },
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
            }

            EnhancerModuleCard(
                title = "Dialogue Enhancer",
                subtitle = "1–4 kHz speech formant extraction & background ducking",
                badgeText = null,
                isActive = config.dialogueEnhancerEnabled,
                headerTrailing = {
                    V7PillSwitch(
                        checked = config.dialogueEnhancerEnabled,
                        enabled = enabled,
                        onCheckedChange = { onToggleDialogueEnhancer(it) },
                        modifier = Modifier.testTag("dialogue_enhancer_switch")
                    )
                },
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(w * 0.34f, h * 0.14f),
                            size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.44f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f, w * 0.16f),
                            style = s
                        )
                        drawLine(tint, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.86f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(tint, Offset(w * 0.34f, h * 0.86f), Offset(w * 0.66f, h * 0.86f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            ) {
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
                                fontSize = 11.sp,
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
                                inactiveTrackColor = palette.sHigh
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
                                fontSize = 11.sp,
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
                                inactiveTrackColor = palette.sHigh
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .testTag("dialogue_ducking_slider")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.sLow)
                        .border(1.dp, palette.outlineV.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.sLow)
                        .border(1.dp, palette.outlineV.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
}

@Composable
private fun EnhancerModuleCard(
    title: String,
    subtitle: String,
    badgeText: String?,
    isActive: Boolean,
    headerTrailing: (@Composable () -> Unit)? = null,
    iconContent: @Composable (Color) -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalMonetPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.sLow)
            .border(
                1.dp,
                if (isActive) palette.primary.copy(alpha = 0.38f) else palette.outlineV.copy(alpha = 0.45f),
                RoundedCornerShape(22.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isActive) palette.primaryC else palette.sHigh),
                    contentAlignment = Alignment.Center
                ) {
                    iconContent(if (isActive) palette.onPrimaryC else palette.onVar)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = palette.onSurface
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = palette.onVar,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (headerTrailing != null) {
                headerTrailing()
            } else if (badgeText != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isActive) palette.primaryC else palette.sHigh)
                        .border(
                            1.dp,
                            if (isActive) palette.primary.copy(alpha = 0.45f) else palette.outlineV.copy(alpha = 0.5f),
                            RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeText,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = if (isActive) palette.onPrimaryC else palette.onVar
                    )
                }
            }
        }

        content()
    }
}
