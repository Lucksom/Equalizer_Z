package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.Enhancer
import com.example.audio.EnhancerItemState
import com.example.audio.EnhancerMixer
import com.example.model.OutputRoute
import java.util.Locale

/**
 * Dedicated Audio Enhancers card placed directly below the Equalizer (Enhancers Spec Section 1–6):
 * - 4 individual enhancer bars:
 *   1. Enable Deeper Bass (`DEEP_BASS`)
 *   2. Surround Audio (`SURROUND`)
 *   3. Enhance Beats (`BEATS`)
 *   4. Enhance Vocal (`VOCAL`)
 * - Each bar has an icon, title, one-line description, contextual route/capability badges, on/off switch,
 *   and 0–100% amount slider (`onDrag` while moving, `onCommit` on release and switch toggle).
 * - Ordered headroom budget bar:
 *   Row 1: "Enhancer budget: X.X of 6.0 dB" + percentage/scale readout
 *   Row 2: Smooth progress bar
 *   Row 3: Full-width status ("Within 6.0 dB headroom budget" or "Limited by headroom budget")
 * - Loudness-matched A/B Enhancers compare button + Reset button.
 * - Optional Dolby-port properties card (shown when detected or enabled in Advanced Settings).
 */
@Composable
fun EnhancersCard(
    enhancers: List<EnhancerItemState>,
    enhancerPeakDb: Float,
    enhancerScale: Float,
    enhancerAbBypass: Boolean,
    activeRoute: OutputRoute,
    isLegacyTier1: Boolean,
    virtualizerSupported: Boolean,
    dolbyDetected: Boolean,
    tryDolbyControl: Boolean,
    dolbyDeepBass: Boolean,
    isEnabled: Boolean,
    onToggleEnhancer: (Enhancer, Boolean) -> Unit,
    onDragEnhancer: (Enhancer, Float) -> Unit,
    onCommitEnhancers: () -> Unit,
    onToggleAbBypass: (Boolean) -> Unit,
    onResetEnhancers: () -> Unit,
    onToggleDolbyDeepBass: (Boolean) -> Unit,
    onResetDolby: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSpeaker = activeRoute == OutputRoute.SPEAKER
    val isBudgetLimited = enhancerScale < 0.999f
    val amberColor = Color(0xFFF59E0B)
    val anyEnhancerEnabled = enhancers.any { it.enabled && it.sliderPercent > 0.5f }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.6f)
            .testTag("enhancers_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header Row: Title + Mode Summary + Compact A/B & Reset Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Audio Enhancers",
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Audio Enhancers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isLegacyTier1) {
                                "Tier 1 · Approximate · Virtualizer"
                            } else {
                                "31-Band + MBC Ceilings · Stacks with EQ"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val abEnabled = isEnabled && anyEnhancerEnabled
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            enhancerAbBypass -> MaterialTheme.colorScheme.tertiaryContainer
                            abEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        },
                        contentColor = when {
                            enhancerAbBypass -> MaterialTheme.colorScheme.onTertiaryContainer
                            abEnabled -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (enhancerAbBypass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = abEnabled) { onToggleAbBypass(!enhancerAbBypass) }
                            .testTag("enhancers_ab_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CompareArrows,
                                contentDescription = "Compare Enhancers A/B",
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (enhancerAbBypass) "A/B Off" else "A/B",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    val resetEnabled = isEnabled && (anyEnhancerEnabled || dolbyDeepBass)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (resetEnabled) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        },
                        contentColor = if (resetEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = resetEnabled) { onResetEnhancers() }
                            .testTag("enhancers_reset_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset Enhancers",
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Reset",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 2. Four Individual Enhancer Bars
            enhancers.forEach { item ->
                EnhancerBarItem(
                    item = item,
                    isSpeaker = isSpeaker,
                    isLegacyTier1 = isLegacyTier1,
                    virtualizerSupported = virtualizerSupported,
                    isEnabled = isEnabled,
                    onToggle = { onToggleEnhancer(item.enhancer, it) },
                    onDrag = { onDragEnhancer(item.enhancer, it) },
                    onCommit = onCommitEnhancers
                )
            }

            // 3. Ordered Headroom Budget Bar:
            // Row 1: "Enhancer budget: X.X of 6.0 dB" on left + percentage/scale pill on right
            // Row 2: Progress bar
            // Row 3: Full-width status text ("Within 6.0 dB headroom budget" / "Limited by headroom budget")
            val fillFraction = (enhancerPeakDb / EnhancerMixer.BUDGET_DB.toFloat()).coerceIn(0f, 1f)
            val barColor by animateColorAsState(
                targetValue = if (isBudgetLimited) amberColor else MaterialTheme.colorScheme.primary,
                label = "enhancerBudgetColor"
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isBudgetLimited) {
                    amberColor.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
                },
                border = if (isBudgetLimited) {
                    BorderStroke(1.dp, amberColor.copy(alpha = 0.5f))
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enhancer_budget_bar")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Enhancer budget: %.1f of %.1f dB",
                                enhancerPeakDb,
                                EnhancerMixer.BUDGET_DB
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isBudgetLimited) amberColor else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = if (isBudgetLimited) {
                                String.format(Locale.US, "×%.2f", enhancerScale)
                            } else {
                                "${(fillFraction * 100f).toInt()}%"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isBudgetLimited) amberColor else MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fillFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(barColor)
                        )
                    }

                    Text(
                        text = if (isBudgetLimited) {
                            String.format(
                                Locale.US,
                                "Limited by headroom budget (scaled ×%.2f)",
                                enhancerScale
                            )
                        } else if (enhancerAbBypass) {
                            "A/B Bypass Active (Loudness Matched)"
                        } else {
                            "Within 6.0 dB headroom budget"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isBudgetLimited) FontWeight.Bold else FontWeight.Normal,
                        color = if (isBudgetLimited) amberColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4. Optional Dolby-port Control Card (hidden unless detected or switched on in Advanced Settings)
            AnimatedVisibility(visible = dolbyDetected || tryDolbyControl) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dolby_control_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Dolby Deep Bass",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ) {
                                    Text(
                                        text = "Experimental · Device-Specific",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = "Sets dolby.deepbass (1/0) and dolby.bass (0..96) via background shell. RAM-only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = dolbyDeepBass,
                                onCheckedChange = onToggleDolbyDeepBass,
                                enabled = isEnabled,
                                modifier = Modifier.testTag("dolby_deep_bass_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val deepBassPct = enhancers.firstOrNull { it.enhancer == Enhancer.DEEP_BASS }
                                ?.let { if (it.enabled) it.sliderPercent else 0f } ?: 0f
                            val dolbyVal = if (dolbyDeepBass) ((96f * deepBassPct) / 100f).toInt() else 0
                            Text(
                                text = "dolby.deepbass=${if (dolbyDeepBass && deepBassPct > 0f) 1 else 0} · dolby.bass=$dolbyVal",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            OutlinedButton(
                                onClick = onResetDolby,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("dolby_restore_button")
                            ) {
                                Text(
                                    text = "Restore Original",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnhancerBarItem(
    item: EnhancerItemState,
    isSpeaker: Boolean,
    isLegacyTier1: Boolean,
    virtualizerSupported: Boolean,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val enhancer = item.enhancer
    val icon: ImageVector = when (enhancer) {
        Enhancer.DEEP_BASS -> Icons.Default.GraphicEq
        Enhancer.SURROUND -> Icons.Default.SurroundSound
        Enhancer.BEATS -> Icons.Default.Bolt
        Enhancer.VOCAL -> Icons.Default.RecordVoiceOver
    }

    val labelTitle = when (enhancer) {
        Enhancer.DEEP_BASS -> "Enable Deeper Bass"
        Enhancer.SURROUND -> "Surround Audio"
        Enhancer.BEATS -> "Enhance Beats"
        Enhancer.VOCAL -> "Enhance Vocal"
    }

    val amberColor = Color(0xFFF59E0B)

    val containerBg by animateColorAsState(
        targetValue = if (item.enabled && item.sliderPercent > 0.5f) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.42f)
        },
        label = "enhancerRowBg_${enhancer.id}"
    )

    val borderStroke = if (item.enabled && item.sliderPercent > 0.5f) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    }

    // Collect contextual badges cleanly so they never crowd the title row
    val badges = buildList {
        if (enhancer == Enhancer.DEEP_BASS && isSpeaker) {
            add("Speaker mode: lifts upper bass" to false)
        }
        if (enhancer == Enhancer.SURROUND && !virtualizerSupported) {
            add("May not apply" to true)
        } else if (enhancer == Enhancer.SURROUND) {
            add((if (isSpeaker) "Transaural" else "Binaural") to false)
        }
        if (isLegacyTier1) {
            val tier1Text = when (enhancer) {
                Enhancer.SURROUND -> "Virtualizer only"
                else -> "Approximate"
            }
            add(tier1Text to false)
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerBg,
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("enhancer_bar_${enhancer.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top row: Icon + Title/Subtitle + Fixed-Width Amount Pill + Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (item.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (item.enabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = labelTitle,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = labelTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = enhancer.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (item.enabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (item.enabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.widthIn(min = 48.dp)
                    ) {
                        Text(
                            text = if (item.enabled) "${item.sliderPercent.toInt()}%" else "OFF",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Switch(
                        checked = item.enabled,
                        onCheckedChange = onToggle,
                        enabled = isEnabled,
                        modifier = Modifier.testTag("enhancer_switch_${enhancer.id}")
                    )
                }
            }

            // Contextual badges row (aligned cleanly below header when present)
            if (badges.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 46.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    badges.forEach { (badgeText, isWarn) ->
                        EnhancerBadge(
                            text = badgeText,
                            isWarning = isWarn,
                            warningColor = amberColor
                        )
                    }
                }
            }

            // Bottom row: 0-100% Amount Slider (onDrag while moving, onCommit on release)
            Slider(
                value = if (item.enabled) item.sliderPercent else 0f,
                onValueChange = onDrag,
                onValueChangeFinished = onCommit,
                valueRange = 0f..100f,
                enabled = isEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    activeTrackColor = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enhancer_slider_${enhancer.id}")
            )
        }
    }
}

@Composable
private fun EnhancerBadge(
    text: String,
    isWarning: Boolean,
    warningColor: Color = Color(0xFFF59E0B)
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isWarning) {
            warningColor.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isWarning) {
            warningColor
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
