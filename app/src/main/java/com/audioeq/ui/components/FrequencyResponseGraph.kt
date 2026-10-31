package com.audioeq.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioeq.model.EqConstants
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * High-precision Live Frequency Response Graph matching `equalizer.html`:
 * - Rounded 20.dp surface container (`MaterialTheme.colorScheme.surface`)
 * - Left dB axis labels (`+12`, `+6`, `0`, `-6`, `-12`) and horizontal grid lines
 * - Bottom log-frequency ticks (`100`, `1k`, `10k`)
 * - Emphasized `0 dB` reference line
 * - 220-point Gaussian octave response curve (`BW = 0.9` octaves) with smooth quadratic midpoint spline
 * - Translucent primary fill between curve and `0 dB` baseline (`alpha = 0.18f`)
 * - 10 Band circular nodes (`radius = 5.dp`) with `2.dp` surface ring stroke
 */
@Composable
fun FrequencyResponseGraph(
    displayGainsDb: List<Float>,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    val enabledAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.4f,
        animationSpec = tween(250),
        label = "graphEnabledAlpha"
    )

    val labelTextStyle = TextStyle(
        color = onSurfaceVariantColor,
        fontSize = 11.sp
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(surfaceColor)
            .testTag("frequency_response_card")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("frequency_response_canvas")
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val padL = 36.dp.toPx()
            val padR = 14.dp.toPx()
            val padT = 14.dp.toPx()
            val padB = 22.dp.toPx()

            val plotW = (w - padL - padR).coerceAtLeast(1f)
            val plotH = (h - padT - padB).coerceAtLeast(1f)

            val minF = 20f
            val maxF = 20000f
            val minLog10 = log10(minF)
            val maxLog10 = log10(maxF)
            val minDb = -12f
            val maxDb = 12f
            val bwOctaves = 0.9f
            val invLn2 = (1.0 / ln(2.0)).toFloat()

            fun log2(x: Float): Float = ln(x) * invLn2

            fun xOf(f: Float): Float {
                val clamped = f.coerceIn(minF, maxF)
                return padL + ((log10(clamped) - minLog10) / (maxLog10 - minLog10)) * plotW
            }

            fun yOf(db: Float): Float {
                val clamped = db.coerceIn(minDb, maxDb)
                return padT + ((maxDb - clamped) / (maxDb - minDb)) * plotH
            }

            val bandFreqs = EqConstants.MASTER_BANDS.map { it.frequencyHz.toFloat() }
            val bandLog2 = FloatArray(bandFreqs.size) { i -> log2(bandFreqs[i]) }

            fun responseAt(f: Float): Float {
                val lf = log2(f)
                var sumDb = 0f
                for (i in bandFreqs.indices) {
                    val g = displayGainsDb.getOrElse(i) { 0f }
                    val d = (lf - bandLog2[i]) / bwOctaves
                    sumDb += g * exp(-0.5f * d * d)
                }
                return sumDb.coerceIn(minDb, maxDb)
            }

            // 1. Horizontal dB grid lines & left axis labels (-12, -6, 0, +6, +12)
            val dbSteps = listOf(-12, -6, 0, 6, 12)
            for (dbInt in dbSteps) {
                val y = yOf(dbInt.toFloat())
                val isZero = dbInt == 0
                drawLine(
                    color = outlineColor.copy(alpha = if (isZero) 0.6f else 0.25f),
                    start = Offset(padL, y),
                    end = Offset(w - padR, y),
                    strokeWidth = if (isZero) 1.4.dp.toPx() else 1.dp.toPx()
                )

                val label = if (dbInt > 0) "+$dbInt" else dbInt.toString()
                val measured = textMeasurer.measure(label, style = labelTextStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = (padL - 6.dp.toPx() - measured.size.width).coerceAtLeast(2f),
                        y = y - measured.size.height / 2f
                    )
                )
            }

            // 2. Bottom frequency tick labels (100, 1k, 10k)
            val freqTicks = listOf(100f to "100", 1000f to "1k", 10000f to "10k")
            for ((fVal, fText) in freqTicks) {
                val x = xOf(fVal)
                val measured = textMeasurer.measure(fText, style = labelTextStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = x - measured.size.width / 2f,
                        y = (h - padB + 4.dp.toPx()).coerceAtMost(h - measured.size.height)
                    )
                )
            }

            // 3. Build 220 curve points across log-frequency axis
            val numSamples = 200
            val pts = Array(numSamples + 1) { n ->
                val ratio = n.toFloat() / numSamples.toFloat()
                val f = minF * (maxF / minF).pow(ratio)
                Offset(xOf(f), yOf(responseAt(f)))
            }

            val zeroY = yOf(0f)

            // 4. Filled area between curve and 0 dB line
            val fillPath = Path().apply {
                moveTo(pts.first().x, zeroY)
                for (pt in pts) {
                    lineTo(pt.x, pt.y)
                }
                lineTo(pts.last().x, zeroY)
                close()
            }
            drawPath(
                path = fillPath,
                color = primaryColor.copy(alpha = 0.18f * enabledAlpha)
            )

            // 5. Smooth quadratic midpoint curve stroke
            val strokePath = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (n in 1 until pts.size - 1) {
                    val mx = (pts[n].x + pts[n + 1].x) * 0.5f
                    val my = (pts[n].y + pts[n + 1].y) * 0.5f
                    quadraticTo(pts[n].x, pts[n].y, mx, my)
                }
                lineTo(pts.last().x, pts.last().y)
            }
            drawPath(
                path = strokePath,
                color = primaryColor.copy(alpha = enabledAlpha),
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 6. 10 Band circular nodes with surface border ring
            val nodeRadius = 5.dp.toPx()
            val nodeBorder = 2.dp.toPx()
            for (i in bandFreqs.indices) {
                val cx = xOf(bandFreqs[i])
                val cy = yOf(displayGainsDb.getOrElse(i) { 0f })
                drawCircle(
                    color = primaryColor.copy(alpha = enabledAlpha),
                    radius = nodeRadius,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = surfaceColor,
                    radius = nodeRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = nodeBorder)
                )
            }
        }
    }
}
