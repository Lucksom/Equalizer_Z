package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EqBandSpec
import com.example.model.FrequencySection
import com.example.model.SectionColorRole
import kotlin.math.roundToInt

/**
 * Renders one of the four Frequency Section Cards (Bass, Mids, Treble, Air) matching the HTML v3 mockup:
 * - Rounded 28.dp container with tonal role (`primaryContainer`, `secondaryContainer`, `tertiaryContainer`)
 * - Header with section title and frequency range pill (`20–250 Hz`, `250 Hz–4 kHz`, `4–12 kHz`, `12–20 kHz`)
 * - Vertical M3 sliders for every band in the section with tabular gain pill (`+3`, `-3`, `0`)
 * - Bottom Stereo Width Mid/Side control row (`0%` to `200%`)
 */
@Composable
fun FrequencySectionCard(
    section: FrequencySection,
    bands: List<EqBandSpec>,
    gainsDb: List<Float>,
    widthPct: Int,
    showWidthSlider: Boolean,
    isEnabled: Boolean,
    onBandGainChange: (Int, Float) -> Unit,
    onWidthChange: (FrequencySection, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = when (section.colorRole) {
        SectionColorRole.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SectionColorRole.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SectionColorRole.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

    val translucentOverlay = Color.White.copy(alpha = 0.28f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.45f)
            .testTag("section_card_${section.id}"),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(translucentOverlay)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = section.rangeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = section.hint,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Vertical Band Sliders Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (showWidthSlider) 16.dp else 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bands.forEach { band ->
                    val gain = gainsDb.getOrElse(band.index) { 0f }
                    VerticalEqBandSlider(
                        band = band,
                        gainDb = gain,
                        accentColor = contentColor,
                        trackBgColor = contentColor.copy(alpha = 0.25f),
                        thumbBorderColor = containerColor,
                        pillBgColor = translucentOverlay,
                        enabled = isEnabled,
                        onGainChange = { newDb -> onBandGainChange(band.index, newDb) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Stereo Width Row (0% to 200% Mid/Side Side*width)
            if (showWidthSlider) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(translucentOverlay)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.width_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.9f)
                        )
                        Text(
                            text = if (section == FrequencySection.BASS && widthPct < 100) "Sub <60Hz Mono" else "Default ${section.defaultWidthRange}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }

                    Slider(
                        value = widthPct.toFloat(),
                        onValueChange = { onWidthChange(section, it.roundToInt()) },
                        valueRange = 0f..200f,
                        enabled = isEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("width_slider_${section.id}")
                            .semantics {
                                contentDescription = "${section.title} stereo width slider"
                            },
                        colors = SliderDefaults.colors(
                            thumbColor = contentColor,
                            activeTrackColor = contentColor,
                            inactiveTrackColor = contentColor.copy(alpha = 0.25f)
                        )
                    )

                    Text(
                        text = "$widthPct%",
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .widthIn(min = 46.dp)
                            .testTag("width_value_${section.id}")
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalEqBandSlider(
    band: EqBandSpec,
    gainDb: Float,
    accentColor: Color,
    trackBgColor: Color,
    thumbBorderColor: Color,
    pillBgColor: Color,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedGain = remember(gainDb) {
        val rounded = (gainDb * 2).roundToInt() / 2f
        val text = if (rounded % 1f == 0f) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
        if (rounded > 0f) "+$text" else text
    }

    var dragHeightPx by remember { mutableFloatStateOf(1f) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Gain value badge
        Box(
            modifier = Modifier
                .widthIn(min = 38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(pillBgColor)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formattedGain,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                textAlign = TextAlign.Center
            )
        }

        // Custom accessible vertical M3 slider (48.dp minimum touch width, 134.dp height)
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(134.dp)
                .testTag("band_slider_${band.frequencyHz}")
                .semantics {
                    contentDescription = "${band.label} Hz equalizer band gain"
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            onGainChange(0f) // Double-tap resets band to 0 dB
                        },
                        onPress = { offset ->
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val pad = 12.dp.toPx()
                            val usable = (h - 2 * pad).coerceAtLeast(1f)
                            val norm = (1f - ((offset.y - pad) / usable)).coerceIn(0f, 1f)
                            val db = -12f + norm * 24f
                            onGainChange(db)
                        }
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragHeightPx = size.height.toFloat().coerceAtLeast(1f)
                            val pad = 12.dp.toPx()
                            val usable = (dragHeightPx - 2 * pad).coerceAtLeast(1f)
                            val norm = (1f - ((offset.y - pad) / usable)).coerceIn(0f, 1f)
                            onGainChange(-12f + norm * 24f)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val pad = 12.dp.toPx()
                            val usable = (h - 2 * pad).coerceAtLeast(1f)
                            val norm = (1f - ((change.position.y - pad) / usable)).coerceIn(0f, 1f)
                            onGainChange(-12f + norm * 24f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                val trackWidth = 6.dp.toPx()
                val pad = 12.dp.toPx()
                val usableHeight = (size.height - 2 * pad).coerceAtLeast(1f)
                val centerX = size.width * 0.5f

                val fraction = ((gainDb.coerceIn(-12f, 12f) + 12f) / 24f).coerceIn(0f, 1f)
                val thumbY = pad + (1f - fraction) * usableHeight
                val zeroY = pad + 0.5f * usableHeight

                // Inactive full track
                drawRoundRect(
                    color = trackBgColor,
                    topLeft = Offset(centerX - trackWidth / 2f, pad),
                    size = Size(trackWidth, usableHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )

                // Active filled track from bottom to thumb (matching HTML v3 --p linear-gradient)
                val fillTop = thumbY
                val fillHeight = (pad + usableHeight - fillTop).coerceAtLeast(0f)
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(centerX - trackWidth / 2f, fillTop),
                    size = Size(trackWidth, fillHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )

                // Center 0 dB tick mark
                drawLine(
                    color = accentColor.copy(alpha = 0.45f),
                    start = Offset(centerX - 7.dp.toPx(), zeroY),
                    end = Offset(centerX + 7.dp.toPx(), zeroY),
                    strokeWidth = 1.5.dp.toPx()
                )

                // M3 Thumb with container border ring
                val thumbRadius = 10.dp.toPx()
                drawCircle(
                    color = accentColor,
                    radius = thumbRadius,
                    center = Offset(centerX, thumbY)
                )
                drawCircle(
                    color = thumbBorderColor,
                    radius = thumbRadius,
                    center = Offset(centerX, thumbY),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // Frequency label
        Text(
            text = band.label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor.copy(alpha = 0.85f)
        )
    }
}
