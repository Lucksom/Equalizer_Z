package com.audioeq.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioeq.model.EqConstants
import com.audioeq.model.OutputRoute
import com.audioeq.ui.theme.JetBrainsMonoFontFamily
import com.audioeq.ui.theme.LocalMonetPalette
import com.audioeq.ui.theme.SpaceGroteskFontFamily
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val FREQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/**
 * Equalizer Card (`main#eq`) matching `audio-eq-v7.html`:
 * 1. `eq-head`: 56x56dp `#power` button + "Equalizer" title & `#eqSub` + `.route` pill (`tertiaryC`)
 * 2. `.headroom`: "Headroom" label + `.meter` fill bar + `#ceilText` dB readout (warns above 4.0 dB)
 * 3. `.curve` (`#curveWrap`): 220dp live cubic-spline response curve with gradient area fill
 * 4. `.zones`: Proportional `Bass` (1.9), `Mids` (2.6), `Treble` (2.4), `Air` (1.2) top-bordered strip
 * 5. `.bands` (`#bands`): 10 vertical band sliders (`31`..`16k` Hz) with `.db` readout, bipolar `.rail-fill`,
 *    `.knob` handle, smooth vertical pointer dragging, and double-tap to reset to 0.0 dB.
 */
@Composable
fun EqualizerCard(
    masterEnabled: Boolean = true,
    isEnabled: Boolean,
    isAbCompare: Boolean,
    usbBitPerfectActive: Boolean,
    intensity: Float,
    activeRoute: OutputRoute,
    ceilingDb: Float,
    sliderGainsDb: List<Float>,
    effectiveGainsDb: List<Float>,
    splitLREnabled: Boolean,
    editingRightChannel: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectEditingChannel: (Boolean) -> Unit,
    onBandGainChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    val eqActive = masterEnabled && isEnabled

    val cardAlpha by animateFloatAsState(
        targetValue = if (masterEnabled) 1f else 0.45f,
        animationSpec = tween(220),
        label = "eqCardAlpha"
    )

    val powerBg by animateColorAsState(
        targetValue = if (eqActive) palette.primary else palette.sHigh,
        animationSpec = tween(220),
        label = "eqPowerBg"
    )
    val powerTint by animateColorAsState(
        targetValue = if (eqActive) palette.onPrimary else palette.onVar,
        animationSpec = tween(220),
        label = "eqPowerTint"
    )
    val powerRadius by animateDpAsState(
        targetValue = if (eqActive) 26.dp else 20.dp,
        animationSpec = tween(220),
        label = "eqPowerRadius"
    )

    val subtitleText = when {
        !masterEnabled -> "Master is off · all effects disabled"
        !isEnabled -> "Equalizer is off · Tone & Enhancers active"
        usbBitPerfectActive -> "USB Bit-Perfect ON · EQ bypassed"
        isAbCompare -> "A/B bypass comparison active"
        else -> String.format(Locale.US, "%.1f× intensity", intensity)
    }

    val isWarn = ceilingDb > 4.0f
    val meterFillFraction = (ceilingDb / 9.0f).coerceIn(0f, 1f)
    val meterColor by animateColorAsState(
        targetValue = if (isWarn) palette.warn else palette.primary,
        animationSpec = tween(180),
        label = "meterColor"
    )
    val ceilTextColor by animateColorAsState(
        targetValue = if (isWarn) palette.warn else palette.onSurface,
        animationSpec = tween(180),
        label = "ceilTextColor"
    )

    val bandsAlpha by animateFloatAsState(
        targetValue = if (eqActive) 1f else 0.38f,
        animationSpec = tween(220),
        label = "bandsAlpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .border(
                width = 1.dp,
                color = palette.outlineV.copy(alpha = 0.55f),
                shape = RoundedCornerShape(28.dp)
            )
            .testTag("unified_equalizer_card"),
        shape = RoundedCornerShape(28.dp),
        color = palette.sCont
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. eq-head
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
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // #power (56x56dp)
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(
                                elevation = if (eqActive) 10.dp else 0.dp,
                                shape = RoundedCornerShape(powerRadius),
                                ambientColor = palette.glow,
                                spotColor = palette.glow
                            )
                            .clip(RoundedCornerShape(powerRadius))
                            .background(powerBg)
                            .border(
                                width = 1.dp,
                                color = if (eqActive) Color.Transparent else palette.outlineV,
                                shape = RoundedCornerShape(powerRadius)
                            )
                            .clickable(enabled = masterEnabled) { onToggleEnabled(!isEnabled) }
                            .testTag("eq_power_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = if (eqActive) "Disable Equalizer only" else "Enable Equalizer",
                            tint = powerTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Equalizer",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.2).sp,
                            color = palette.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = subtitleText,
                            fontSize = 12.sp,
                            color = palette.onVar,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("eq_subtitle_text")
                        )
                    }
                }

                // .route pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.tertiaryC)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("route_badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (activeRoute == OutputRoute.SPEAKER) {
                            Icons.Default.VolumeUp
                        } else {
                            Icons.Default.Headphones
                        },
                        contentDescription = activeRoute.label,
                        tint = palette.onTertiaryC,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = activeRoute.label,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = palette.onTertiaryC,
                        maxLines = 1
                    )
                }
            }

            // 2. .headroom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.sLow)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("headroom_ceiling_meter"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Headroom",
                    fontSize = 12.sp,
                    color = palette.onVar
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.sTop)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(meterFillFraction)
                            .clip(RoundedCornerShape(999.dp))
                            .background(meterColor)
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f dB", ceilingDb),
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = ceilTextColor,
                    modifier = Modifier.testTag("ceiling_db_text")
                )
            }

            // 3. .curve (#curveWrap)
            FrequencyResponseGraph(
                displayGainsDb = effectiveGainsDb,
                isEnabled = masterEnabled && !isAbCompare && !usbBitPerfectActive
            )

            // 4. .zones
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val zones = listOf(
                    "BASS" to 1.9f,
                    "MIDS" to 2.6f,
                    "TREBLE" to 2.4f,
                    "AIR" to 1.2f
                )
                zones.forEach { (title, weightVal) ->
                    Column(
                        modifier = Modifier.weight(weightVal),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(palette.outlineV)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = title,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            letterSpacing = 0.9.sp,
                            color = palette.onVar,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Optional Left / Right Channel Selector when Independent L/R EQ is enabled in Settings
            AnimatedVisibility(visible = splitLREnabled) {
                V7SegmentedRow(
                    options = listOf(false to "Left Channel (10-Band)", true to "Right Channel (10-Band)"),
                    selectedValue = editingRightChannel,
                    enabled = eqActive,
                    onSelect = onSelectEditingChannel
                )
            }

            // 5. .bands (#bands)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(bandsAlpha)
                    .padding(top = 4.dp)
                    .testTag("eq_bands_row"),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in 0 until 10) {
                    val userGain = sliderGainsDb.getOrElse(i) { 0f }
                    val effGain = effectiveGainsDb.getOrElse(i) { 0f }
                    val freqLabel = FREQ_LABELS.getOrElse(i) { EqConstants.MASTER_BANDS[i].label }
                    V7VerticalBandSlider(
                        bandIndex = i,
                        freqLabel = freqLabel,
                        userGainDb = userGain,
                        effectiveGainDb = if (eqActive) effGain else userGain,
                        intensity = intensity,
                        enabled = eqActive,
                        onGainChange = { newDb -> onBandGainChange(i, newDb) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun V7VerticalBandSlider(
    bandIndex: Int,
    freqLabel: String,
    userGainDb: Float,
    effectiveGainDb: Float,
    intensity: Float,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    var isDragging by remember { mutableStateOf(false) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    val currentOnGainChange by rememberUpdatedState(onGainChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentUserGain by rememberUpdatedState(userGainDb)
    val currentEffGain by rememberUpdatedState(effectiveGainDb)
    val currentIntensity by rememberUpdatedState(intensity)

    val activeDbText = if (effectiveGainDb >= 0f) {
        String.format(Locale.US, "+%.1f", effectiveGainDb)
    } else {
        String.format(Locale.US, "%.1f", effectiveGainDb)
    }
    val hasGain = abs(effectiveGainDb) > 0.05f

    val knobScale by animateFloatAsState(
        targetValue = if (isDragging) 1.08f else 1.0f,
        animationSpec = tween(120),
        label = "knobScale_$bandIndex"
    )

    Column(
        modifier = modifier.testTag("band_column_$bandIndex"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // .db
        Text(
            text = activeDbText,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            color = if (hasGain) palette.primary else palette.onVar,
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        // .rail (height 172dp)
        BoxWithConstraints(
            modifier = Modifier
                .width(32.dp)
                .height(172.dp)
                .pointerInput(bandIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentEnabled) return@awaitEachGesture

                        val now = System.currentTimeMillis()
                        if (now - lastTapTimeMs <= 280L) {
                            lastTapTimeMs = 0L
                            currentOnGainChange(0f)
                            return@awaitEachGesture
                        }
                        lastTapTimeMs = now

                        val hPx = size.height.toFloat().coerceAtLeast(1f)
                        // Keep enhancer delta steady while dragging so knob tracks finger
                        val enhancerDelta = currentEffGain - (currentUserGain * currentIntensity)

                        fun updateFromY(yPx: Float) {
                            val p = (1f - (yPx / hPx).coerceIn(0f, 1f))
                            val targetEffDb = (p * 24f) - 12f
                            val rawUserDb = if (currentIntensity > 0.1f) {
                                (targetEffDb - enhancerDelta) / currentIntensity
                            } else {
                                targetEffDb
                            }
                            val rounded = ((rawUserDb * 10f).roundToInt() / 10f).coerceIn(-12f, 12f)
                            currentOnGainChange(rounded)
                        }

                        isDragging = true
                        updateFromY(down.position.y)

                        try {
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                if (change != null && change.pressed) {
                                    change.consume()
                                    updateFromY(change.position.y)
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            isDragging = false
                        }
                    }
                }
                .testTag("band_slider_$bandIndex"),
            contentAlignment = Alignment.Center
        ) {
            val railHeight = maxHeight
            val pct = ((effectiveGainDb.coerceIn(-12f, 12f) + 12f) / 24f).coerceIn(0f, 1f)
            val topFraction = min(0.5f, 1f - pct)
            val fillFraction = abs(pct - 0.5f)
            val knobYOffset = railHeight * (0.5f - pct)

            // .rail-track (6dp wide)
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.sTop)
            )

            // .rail-zero (14dp x 1dp at 50%)
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(1.dp)
                    .background(palette.outline)
            )

            // .rail-fill (6dp wide between 50% and pct)
            if (fillFraction > 0.002f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = railHeight * topFraction)
                        .width(6.dp)
                        .height(railHeight * fillFraction)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.primary)
                )
            }

            // .knob (28dp x 16dp pill, rounded 9dp, 2dp primary border)
            Box(
                modifier = Modifier
                    .offset(y = knobYOffset)
                    .scale(knobScale)
                    .width(28.dp)
                    .height(16.dp)
                    .shadow(4.dp, RoundedCornerShape(9.dp))
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isDragging) palette.primary else palette.onPrimary)
                    .border(2.dp, palette.primary, RoundedCornerShape(9.dp))
            )
        }

        // .hz
        Text(
            text = freqLabel,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            color = palette.onVar,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Tone Card (`section#toneCard`) placed directly below Equalizer, matching `audio-eq-v7.html`:
 * - `.enhance-head`: 40x40dp icon badge + "Tone" + "Tilt, intensity and contour"
 * - `.tone-grid` (2 columns): Bass tilt (`-6..+6 dB`, step `0.5`) and Treble tilt (`-6..+6 dB`, step `0.5`)
 * - Intensity 3-segment control (`Light 0.6×`, `Standard 1.0×`, `Deep+ 1.3×`)
 * - `.hairline` divider
 * - Volume contour switch (`"Lifts bass and treble at low volume"`)
 * - A/B loudness compare switch (`"Level-matched bypass comparison"`)
 */
@Composable
fun ToneCard(
    masterEnabled: Boolean,
    tiltBassDb: Float,
    tiltTrebleDb: Float,
    intensity: Float,
    volumeContourEnabled: Boolean,
    isAbCompare: Boolean,
    onTiltBassChange: (Float) -> Unit,
    onTiltTrebleChange: (Float) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onToggleVolumeContour: (Boolean) -> Unit,
    onToggleAbCompare: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    val cardAlpha by animateFloatAsState(
        targetValue = if (masterEnabled) 1f else 0.45f,
        animationSpec = tween(220),
        label = "toneCardAlpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .border(
                width = 1.dp,
                color = palette.outlineV.copy(alpha = 0.55f),
                shape = RoundedCornerShape(28.dp)
            )
            .testTag("tone_card"),
        shape = RoundedCornerShape(28.dp),
        color = palette.sCont
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header (.enhance-head)
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        val p = Path().apply {
                            moveTo(w * (4f / 24f), h * (12f / 24f))
                            lineTo(w * (8f / 24f), h * (12f / 24f))
                            lineTo(w * (11f / 24f), h * (5f / 24f))
                            lineTo(w * (14f / 24f), h * (19f / 24f))
                            lineTo(w * (17f / 24f), h * (12f / 24f))
                            lineTo(w * (20f / 24f), h * (12f / 24f))
                        }
                        drawPath(
                            path = p,
                            color = iconColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tone",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.2).sp,
                        color = palette.onSurface
                    )
                    Text(
                        text = "Tilt, intensity and contour",
                        fontSize = 12.sp,
                        color = palette.onVar
                    )
                }
            }

            // .tone-grid (2 columns: Bass tilt & Treble tilt)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToneTiltMiniCard(
                    label = "Bass tilt",
                    valueDb = tiltBassDb,
                    enabled = masterEnabled,
                    onValueChange = { raw ->
                        val stepped = (raw * 2f).roundToInt() / 2f
                        onTiltBassChange(stepped)
                    },
                    testTag = "bass_tilt_slider",
                    modifier = Modifier.weight(1f)
                )
                ToneTiltMiniCard(
                    label = "Treble tilt",
                    valueDb = tiltTrebleDb,
                    enabled = masterEnabled,
                    onValueChange = { raw ->
                        val stepped = (raw * 2f).roundToInt() / 2f
                        onTiltTrebleChange(stepped)
                    },
                    testTag = "treble_tilt_slider",
                    modifier = Modifier.weight(1f)
                )
            }

            // Intensity Segmented Row
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Intensity",
                        fontSize = 12.sp,
                        color = palette.onVar
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f×", intensity),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = palette.primary,
                        modifier = Modifier.testTag("intensity_value_text")
                    )
                }
                val selectedIntensityBucket = when {
                    intensity < 0.8f -> 0.6f
                    intensity > 1.15f -> 1.3f
                    else -> 1.0f
                }
                V7SegmentedRow(
                    options = listOf(
                        0.6f to "Light 0.6×",
                        1.0f to "Standard 1.0×",
                        1.3f to "Deep+ 1.3×"
                    ),
                    selectedValue = selectedIntensityBucket,
                    enabled = masterEnabled,
                    onSelect = onIntensityChange,
                    modifier = Modifier.testTag("intensity_segmented_row")
                )
            }

            HorizontalDivider(color = palette.outlineV.copy(alpha = 0.5f))

            // Switch Row 1: Volume contour
            V7SwitchRow(
                title = "Volume contour",
                subtitle = "Lifts bass and treble at low volume",
                checked = volumeContourEnabled,
                enabled = masterEnabled,
                onCheckedChange = onToggleVolumeContour,
                testTag = "volume_contour_switch"
            )

            // Switch Row 2: A/B loudness compare
            V7SwitchRow(
                title = "A/B loudness compare",
                subtitle = "Level-matched bypass comparison",
                checked = isAbCompare,
                enabled = masterEnabled,
                onCheckedChange = onToggleAbCompare,
                testTag = "ab_compare_switch"
            )
        }
    }
}

@Composable
private fun ToneTiltMiniCard(
    label: String,
    valueDb: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    val formattedVal = if (valueDb >= 0f) {
        String.format(Locale.US, "+%.1f dB", valueDb)
    } else {
        String.format(Locale.US, "%.1f dB", valueDb)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.sLow)
            .border(1.dp, palette.outlineV.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = palette.onVar,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formattedVal,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = palette.primary,
                maxLines = 1
            )
        }
        Slider(
            value = valueDb,
            onValueChange = onValueChange,
            valueRange = -6f..6f,
            steps = 23,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = palette.primary,
                activeTrackColor = palette.primary,
                inactiveTrackColor = palette.sTop
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .testTag(testTag)
        )
    }
}

/**
 * Reusable Segmented Pill Row (`.seg`) matching `audio-eq-v7.html`.
 */
@Composable
fun <T> V7SegmentedRow(
    options: List<Pair<T, String>>,
    selectedValue: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.sLow)
            .border(1.dp, palette.outlineV.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val selected = value == selectedValue
            val bg by animateColorAsState(
                targetValue = if (selected) palette.primaryC else Color.Transparent,
                animationSpec = tween(160),
                label = "segBg_$label"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) palette.onPrimaryC else palette.onVar,
                animationSpec = tween(160),
                label = "segFg_$label"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.5.sp,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Reusable Switch Row (`.switch-row` + `.sw`) matching `audio-eq-v7.html`.
 */
@Composable
fun V7SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = palette.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = palette.onVar
            )
        }
        V7PillSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

/**
 * M3 Pill Switch (`.sw`, 50x30dp) matching `audio-eq-v7.html`.
 */
@Composable
fun V7PillSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    val trackBg by animateColorAsState(
        targetValue = if (checked) palette.primary else palette.sTop,
        animationSpec = tween(180),
        label = "swTrackBg"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) palette.onPrimary else palette.outline,
        animationSpec = tween(180),
        label = "swThumbColor"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 4.dp,
        animationSpec = tween(180),
        label = "swThumbOffset"
    )

    Box(
        modifier = modifier
            .width(50.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackBg)
            .border(
                width = 1.dp,
                color = if (checked) palette.primary else palette.outline,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
