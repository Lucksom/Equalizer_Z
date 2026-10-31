package com.audioeq.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioeq.model.EngineMode
import com.audioeq.model.PresetConfig
import com.audioeq.ui.theme.JetBrainsMonoFontFamily
import com.audioeq.ui.theme.LocalMonetPalette
import com.audioeq.ui.theme.SpaceGroteskFontFamily

/**
 * Top Bar (`header.top`) matching `audio-eq-v7.html`:
 * - `.brand` 46x46dp icon box (`primaryC` background, `onPrimaryC` 3-band EQ icon)
 * - `h1` "Audio EQ" + `#sub` subtitle (`"<Preset> · <Engine>"` or `"All features are off"`)
 * - `.engine-pill` (`#enginePill`) with glowing green LED dot and `#engineText`
 * - `#master` 46x46dp power button (toggles `masterEnabled` — global power for Equalizer and every effect)
 * - `#settingsBtn` 46x46dp gear button (opens DSP Engine Settings & Diagnostics sheet)
 */
@Composable
fun TopBarHeader(
    masterEnabled: Boolean,
    activePresetName: String,
    selectedMode: EngineMode,
    onToggleMaster: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current

    val masterBg by animateColorAsState(
        targetValue = if (masterEnabled) palette.primary else palette.sHigh,
        animationSpec = tween(200),
        label = "masterBg"
    )
    val masterTint by animateColorAsState(
        targetValue = if (masterEnabled) palette.onPrimary else palette.onVar,
        animationSpec = tween(200),
        label = "masterTint"
    )
    val masterCorner by animateDpAsState(
        targetValue = if (masterEnabled) 23.dp else 16.dp,
        animationSpec = tween(220),
        label = "masterCorner"
    )

    val subtitleText = if (masterEnabled) {
        "$activePresetName · ${selectedMode.shortTitle}"
    } else {
        "All features are off"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: .title-wrap (.brand + h1 + #sub)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .shadow(
                        elevation = if (masterEnabled) 8.dp else 0.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = palette.glow,
                        spotColor = palette.glow
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.primaryC),
                contentAlignment = Alignment.Center
            ) {
                val iconColor = palette.onPrimaryC
                Canvas(modifier = Modifier.size(24.dp)) {
                    val w = size.width
                    val h = size.height
                    val strokeW = 2.2.dp.toPx()
                    // 3 vertical bars + 3 circle thumbs matching audio-eq-v7.html SVG
                    val x1 = w * (6f / 24f)
                    val x2 = w * (12f / 24f)
                    val x3 = w * (18f / 24f)
                    drawLine(
                        color = iconColor,
                        start = Offset(x1, h * (4f / 24f)),
                        end = Offset(x1, h * (20f / 24f)),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = iconColor,
                        start = Offset(x2, h * (4f / 24f)),
                        end = Offset(x2, h * (20f / 24f)),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = iconColor,
                        start = Offset(x3, h * (4f / 24f)),
                        end = Offset(x3, h * (20f / 24f)),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = iconColor,
                        radius = 2.5.dp.toPx(),
                        center = Offset(x1, h * (9f / 24f))
                    )
                    drawCircle(
                        color = iconColor,
                        radius = 2.5.dp.toPx(),
                        center = Offset(x2, h * (15f / 24f))
                    )
                    drawCircle(
                        color = iconColor,
                        radius = 2.5.dp.toPx(),
                        center = Offset(x3, h * (11f / 24f))
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Audio EQ",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    letterSpacing = (-0.5).sp,
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onVar,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("top_subtitle_text")
                )
            }
        }

        // Right: .top-actions (#enginePill + #master + #settingsBtn)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // .engine-pill (#enginePill)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.sHigh)
                    .border(1.dp, palette.outlineV, RoundedCornerShape(999.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .testTag("engine_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val dotColor = if (masterEnabled) Color(0xFF6DD58C) else palette.outline
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .shadow(
                            elevation = if (masterEnabled) 6.dp else 0.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFF6DD58C),
                            spotColor = Color(0xFF6DD58C)
                        )
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = selectedMode.shortTitle,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = palette.onVar,
                    maxLines = 1
                )
            }

            // #master power icon button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .shadow(
                        elevation = if (masterEnabled) 8.dp else 0.dp,
                        shape = RoundedCornerShape(masterCorner),
                        ambientColor = palette.glow,
                        spotColor = palette.glow
                    )
                    .clip(RoundedCornerShape(masterCorner))
                    .background(masterBg)
                    .border(
                        width = 1.dp,
                        color = if (masterEnabled) Color.Transparent else palette.outlineV,
                        shape = RoundedCornerShape(masterCorner)
                    )
                    .clickable { onToggleMaster(!masterEnabled) }
                    .testTag("top_power_icon_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (masterEnabled) {
                        "Master power on (disable Equalizer and all effects)"
                    } else {
                        "Master power off (enable Equalizer and all effects)"
                    },
                    tint = masterTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // #settingsBtn gear icon button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.sHigh)
                    .border(1.dp, palette.outlineV, RoundedCornerShape(16.dp))
                    .clickable { onOpenSettings() }
                    .testTag("settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings & Diagnostics",
                    tint = palette.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Dismissible Tonal Status Banner (`#banner`) matching `audio-eq-v7.html`.
 */
@Composable
fun StatusBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.secondaryC)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("status_feedback_banner"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = palette.onSecondaryC,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("banner_dismiss_button")
        ) {
            Text(
                text = "Dismiss",
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = palette.primary
            )
        }
    }
}

/**
 * Presets Card (`aside#presetCard`) matching `audio-eq-v7.html`:
 * - `.kicker`: "PRESETS" on left + monospace `#presetTag` on right
 * - `.chips` (`#presetChips`): Flex-wrap FlowRow of preset chips (`Flat`, `Balanced Deep`,
 *   `Bass Boost`, `Vocal Clarity`, `Percussion`, `Cinema`, plus user custom presets with `✕` delete icon)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsCard(
    masterEnabled: Boolean = true,
    presets: List<PresetConfig>,
    selectedPresetId: String,
    activePresetName: String,
    onSelectPreset: (PresetConfig) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalMonetPalette.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = palette.outlineV.copy(alpha = 0.55f),
                shape = RoundedCornerShape(28.dp)
            )
            .testTag("preset_card"),
        shape = RoundedCornerShape(28.dp),
        color = palette.sCont.copy(alpha = if (masterEnabled) 1f else 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // .kicker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRESETS",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.3.sp,
                    color = palette.onVar
                )
                Text(
                    text = activePresetName,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = palette.primary,
                    modifier = Modifier.testTag("preset_tag")
                )
            }

            // .chips (#presetChips)
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preset_chip_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = preset.id == selectedPresetId
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) palette.secondaryC else palette.sHigh,
                        animationSpec = tween(160),
                        label = "presetChipBg_${preset.id}"
                    )
                    val chipContent by animateColorAsState(
                        targetValue = if (isSelected) palette.onSecondaryC else palette.onSurface,
                        animationSpec = tween(160),
                        label = "presetChipContent_${preset.id}"
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(chipBg)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color.Transparent else palette.outlineV,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(enabled = masterEnabled) { onSelectPreset(preset) }
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                            .testTag("preset_chip_${preset.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = preset.name,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 13.sp,
                            color = chipContent,
                            maxLines = 1
                        )
                        if (!preset.isBuiltIn) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete preset ${preset.name}",
                                tint = chipContent.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onDeleteCustomPreset(preset.id) }
                                    .testTag("delete_preset_${preset.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}
