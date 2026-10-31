package com.audioeq.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioeq.ui.theme.JetBrainsMonoFontFamily
import com.audioeq.ui.theme.LocalMonetPalette

@Composable
fun FrequencyResponseGraph(
    displayGainsDb: List<Float>,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    val textMeasurer = rememberTextMeasurer()

    val wrapperAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.35f,
        animationSpec = tween(250),
        label = "curveWrapAlpha"
    )

    val labelStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        color = palette.onVar,
        fontSize = 10.sp
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .alpha(wrapperAlpha)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.sLow)
            .border(
                width = 1.dp,
                color = palette.outlineV.copy(alpha = 0.45f),
                shape = RoundedCornerShape(22.dp)
            )
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

            val midY = h * 0.5f
            val maxAmpY = h * (86f / 220f)

            // 1. Vertical log-frequency grid lines (130/720, 360/720, 590/720)
            val vGridRatios = listOf(
                (130f / 720f) to "100",
                (360f / 720f) to "1k",
                (590f / 720f) to "10k"
            )
            for ((ratio, label) in vGridRatios) {
                val x = w * ratio
                drawLine(
                    color = palette.outlineV.copy(alpha = 0.55f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.dp.toPx()
                )
                val measured = textMeasurer.measure(label, style = labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = x - 8.dp.toPx(),
                        y = h - measured.size.height - 6.dp.toPx()
                    )
                )
            }

            // 2. Dashed 0 dB center line
            drawLine(
                color = palette.outline.copy(alpha = 0.7f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1.25.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                    0f
                )
            )

            // 3. Left dB labels (+12, 0, −12)
            val leftX = 10.dp.toPx()
            val plus12Measured = textMeasurer.measure("+12", style = labelStyle)
            drawText(
                textLayoutResult = plus12Measured,
                topLeft = Offset(leftX, 8.dp.toPx())
            )
            val zeroMeasured = textMeasurer.measure("0", style = labelStyle)
            drawText(
                textLayoutResult = zeroMeasured,
                topLeft = Offset(leftX, midY - zeroMeasured.size.height - 4.dp.toPx())
            )
            val minus12Measured = textMeasurer.measure("−12", style = labelStyle)
            drawText(
                textLayoutResult = minus12Measured,
                topLeft = Offset(leftX, h - minus12Measured.size.height - 8.dp.toPx())
            )

            // 4. Compute 10 band spline points matching audio-eq-v7.html
            val xs = FloatArray(10) { i -> ((36f + i * 72f) / 720f) * w }
            val ys = FloatArray(10) { i ->
                val g = displayGainsDb.getOrElse(i) { 0f }.coerceIn(-12f, 12f)
                midY - (g / 12f) * maxAmpY
            }

            val curvePath = Path().apply {
                moveTo(xs[0], ys[0])
                for (i in 1 until 10) {
                    val mx = (xs[i - 1] + xs[i]) * 0.5f
                    cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i])
                }
            }

            val areaPath = Path().apply {
                addPath(curvePath)
                lineTo(xs[9], h)
                lineTo(xs[0], h)
                close()
            }

            // 5. Gradient fill under curve
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        palette.primary.copy(alpha = 0.38f),
                        palette.primary.copy(alpha = 0.0f)
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // 6. Drop-shadow glow stroke + primary curve stroke
            drawPath(
                path = curvePath,
                color = palette.glow,
                style = Stroke(
                    width = 7.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = curvePath,
                color = palette.primary,
                style = Stroke(
                    width = 2.75.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
