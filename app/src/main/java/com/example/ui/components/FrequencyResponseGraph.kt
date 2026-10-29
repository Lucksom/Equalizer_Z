package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EqConstants
import kotlin.math.log10

/**
 * Live Frequency Response Graph rendered on a logarithmic 20 Hz – 20 kHz axis.
 * Matches the HTML v3 mockup card (`--surface-container-high`, 20dp radius, 140dp SVG curve with gradient fill)
 * while plotting the true composite 10-band Biquad + High-Pass Filter transfer function and band anchor dots.
 */
@Composable
fun FrequencyResponseGraph(
    responsePoints: List<Pair<Float, Float>>,
    gainsDb: List<Float>,
    preampDb: Float,
    highPassHz: Float,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.4f,
        animationSpec = tween(250),
        label = "graphAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("frequency_response_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.frequency_response_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (highPassHz > 10f) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "HPF ${highPassHz.toInt()} Hz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        val preSign = if (preampDb > 0f) "+$preampDb" else "$preampDb"
                        Text(
                            text = "Preamp $preSign dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .testTag("frequency_response_canvas")
                    ) {
                        val w = size.width
                        val h = size.height
                        val midY = h * 0.5f
                        val minLog = log10(20f)
                        val maxLog = log10(20000f)

                        fun xForFreq(f: Float): Float {
                            val clamped = f.coerceIn(20f, 20000f)
                            return ((log10(clamped) - minLog) / (maxLog - minLog)) * w
                        }

                        fun yForDb(db: Float): Float {
                            val clamped = db.coerceIn(-13.5f, 13.5f)
                            return midY - (clamped / 13.5f) * (h * 0.45f)
                        }

                        // Subtle horizontal grid lines (+6 dB, 0 dB dashed, -6 dB)
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        drawLine(
                            color = outlineVariant.copy(alpha = 0.35f),
                            start = Offset(0f, yForDb(6f)),
                            end = Offset(w, yForDb(6f)),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = outlineVariant,
                            start = Offset(0f, midY),
                            end = Offset(w, midY),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = dashEffect
                        )
                        drawLine(
                            color = outlineVariant.copy(alpha = 0.35f),
                            start = Offset(0f, yForDb(-6f)),
                            end = Offset(w, yForDb(-6f)),
                            strokeWidth = 1.dp.toPx()
                        )

                        // Section boundary vertical guides at 250 Hz, 4 kHz, 12 kHz
                        listOf(250f, 4000f, 12000f).forEach { crossoverHz ->
                            val cx = xForFreq(crossoverHz)
                            drawLine(
                                color = outlineVariant.copy(alpha = 0.25f),
                                start = Offset(cx, 0f),
                                end = Offset(cx, h),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Build smooth cubic Bezier curve from 10 band control points (matching HTML v3 curve)
                        // merged with Biquad high-pass roll-off below 60 Hz when HPF is active
                        val bandFreqs = EqConstants.MASTER_BANDS.map { it.frequencyHz.toFloat() }
                        val pts = bandFreqs.mapIndexed { idx, freq ->
                            val gain = gainsDb.getOrElse(idx) { 0f }
                            Offset(xForFreq(freq), yForDb(gain))
                        }

                        val startDb = if (highPassHz > 10f && responsePoints.isNotEmpty()) {
                            responsePoints.first().second
                        } else {
                            gainsDb.firstOrNull() ?: 0f
                        }

                        val allPts = buildList {
                            add(Offset(0f, yForDb(startDb)))
                            addAll(pts)
                            add(Offset(w, pts.lastOrNull()?.y ?: midY))
                        }

                        val strokePath = Path()
                        val fillPath = Path()

                        if (allPts.isNotEmpty()) {
                            strokePath.moveTo(allPts[0].x, allPts[0].y)
                            fillPath.moveTo(allPts[0].x, allPts[0].y)

                            for (i in 1 until allPts.size) {
                                val p0 = allPts[i - 1]
                                val p1 = allPts[i]
                                val cx = (p0.x + p1.x) * 0.5f
                                strokePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                            }

                            fillPath.lineTo(w, h)
                            fillPath.lineTo(0f, h)
                            fillPath.close()

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        primaryColor.copy(alpha = 0.35f * animatedAlpha),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = h
                                )
                            )

                            // Optional true Biquad composite overlay (subtle secondary line)
                            if (responsePoints.size > 2) {
                                val biquadPath = Path()
                                responsePoints.forEachIndexed { idx, (f, db) ->
                                    val px = xForFreq(f)
                                    val py = yForDb(db)
                                    if (idx == 0) biquadPath.moveTo(px, py)
                                    else biquadPath.lineTo(px, py)
                                }
                                drawPath(
                                    path = biquadPath,
                                    color = tertiaryColor.copy(alpha = 0.45f * animatedAlpha),
                                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }

                            drawPath(
                                path = strokePath,
                                color = primaryColor.copy(alpha = animatedAlpha),
                                style = Stroke(
                                    width = 3.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )

                            // Draw band anchor dots
                            pts.forEach { pt ->
                                drawCircle(
                                    color = primaryColor.copy(alpha = animatedAlpha),
                                    radius = 3.5.dp.toPx(),
                                    center = pt
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("20", "100", "1k", "5k", "20k").forEach { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
