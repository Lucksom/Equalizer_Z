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
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
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
import com.audioeq.audio.EnhancerMath
import com.audioeq.audio.IeqPreset
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
    onSelectProfile: (AudioProfile) -> Unit = {},
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
    onVirtualBassStrengthChange: (Int) -> Unit = {},
    onResetAllEnhancers: () -> Unit,
    onOpenCalibrationSheet: () -> Unit,
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
                            text = "Audio Enhancers",
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSurface
                        )
                        Text(
                            text = "Dialogue & Bass Enhancers, IEQ, and Calibration",
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

            EnhancerModuleCard(
                title = "Bass Enhancer",
                subtitle = bassSubText,
                badgeText = null,
                isActive = config.bassEnhancerEnabled,
                headerTrailing = {
                    V7PillSwitch(
                        checked = config.bassEnhancerEnabled,
                        enabled = enabled,
                        onCheckedChange = { onToggleBassEnhancer(it) },
                        modifier = Modifier.testTag("bass_enhancer_switch")
                    )
                },
                iconContent = { tint ->
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Bass Enhancer",
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
                            onValueChange = { onBassStrengthChange(it.roundToInt()) },
                            valueRange = 0f..100f,
                            steps = 19,
                            enabled = enabled,
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
                }
            }

            EnhancerModuleCard(
                title = "Virtual Bass",
                subtitle = if (config.virtualBassEnabled) {
                    "${config.virtualBassStrength}% harmonic synthesis (H2, H3, H4 missing fundamental)"
                } else {
                    "Off"
                },
                badgeText = if (config.virtualBassEnabled) "Harmonic" else null,
                isActive = config.virtualBassEnabled,
                headerTrailing = {
                    V7PillSwitch(
                        checked = config.virtualBassEnabled,
                        enabled = enabled,
                        onCheckedChange = { onToggleVirtualBass(it) },
                        modifier = Modifier.testTag("virtual_bass_switch")
                    )
                },
                iconContent = { tint ->
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val sw = 1.8.dp.toPx()
                        val path = Path().apply {
                            moveTo(w * 0.10f, h * 0.50f)
                            cubicTo(w * 0.25f, h * 0.15f, w * 0.35f, h * 0.15f, w * 0.50f, h * 0.50f)
                            cubicTo(w * 0.65f, h * 0.85f, w * 0.75f, h * 0.85f, w * 0.90f, h * 0.50f)
                        }
                        drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
                    }
                }
            ) {
                AnimatedVisibility(visible = config.virtualBassEnabled) {
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
                                text = "Harmonic intensity",
                                fontSize = 12.sp,
                                color = palette.onVar
                            )
                            Text(
                                text = "${config.virtualBassStrength}%",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = palette.primary,
                                modifier = Modifier.testTag("virtual_bass_strength_text")
                            )
                        }
                        Slider(
                            value = config.virtualBassStrength.toFloat(),
                            onValueChange = { onVirtualBassStrengthChange(it.roundToInt()) },
                            valueRange = 0f..100f,
                            steps = 19,
                            enabled = enabled,
                            colors = SliderDefaults.colors(
                                thumbColor = palette.primary,
                                activeTrackColor = palette.primary,
                                inactiveTrackColor = palette.sTop
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .testTag("virtual_bass_strength_slider")
                        )
                        Text(
                            text = "Synthesizes psychoacoustic missing-fundamental harmonics (80 Hz, 125 Hz, 200 Hz) so deep bass is clearly heard without distorting small speakers or earbuds.",
                            fontSize = 11.sp,
                            color = palette.onVar
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
