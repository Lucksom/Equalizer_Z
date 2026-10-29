package com.example.model

enum class FrequencySection(
    val id: String,
    val title: String,
    val rangeLabel: String,
    val hint: String,
    val defaultWidthRange: String,
    val colorRole: SectionColorRole
) {
    BASS(
        id = "bass",
        title = "Bass",
        rangeLabel = "20–250 Hz",
        hint = "Punch and low end. Keep width near mono.",
        defaultWidthRange = "0–20%",
        colorRole = SectionColorRole.PRIMARY
    ),
    MIDS(
        id = "mids",
        title = "Mids",
        rangeLabel = "250 Hz–4 kHz",
        hint = "Vocals, guitars, and clarity.",
        defaultWidthRange = "80–100%",
        colorRole = SectionColorRole.SECONDARY
    ),
    TREBLE(
        id = "treble",
        title = "Treble",
        rangeLabel = "4–12 kHz",
        hint = "Definition, cymbals, and percussion strikes.",
        defaultWidthRange = "110–120%",
        colorRole = SectionColorRole.TERTIARY
    ),
    AIR(
        id = "air",
        title = "Air",
        rangeLabel = "12–20 kHz",
        hint = "Openness and sparkle.",
        defaultWidthRange = "110–150%",
        colorRole = SectionColorRole.SECONDARY
    )
}

enum class SectionColorRole {
    PRIMARY,
    SECONDARY,
    TERTIARY
}

enum class FilterType {
    LOW_SHELF,
    PEAKING,
    HIGH_SHELF,
    HIGH_PASS
}

data class EqBandSpec(
    val index: Int,
    val frequencyHz: Int,
    val label: String,
    val cutoffUpperHz: Float,
    val section: FrequencySection,
    val filterType: FilterType,
    val defaultQ: Float
)

data class SectionWidths(
    val bassWidthPct: Int = 10,
    val midsWidthPct: Int = 100,
    val trebleWidthPct: Int = 115,
    val airWidthPct: Int = 125
) {
    fun forSection(section: FrequencySection): Int = when (section) {
        FrequencySection.BASS -> bassWidthPct
        FrequencySection.MIDS -> midsWidthPct
        FrequencySection.TREBLE -> trebleWidthPct
        FrequencySection.AIR -> airWidthPct
    }

    fun withSection(section: FrequencySection, valuePct: Int): SectionWidths {
        val clamped = valuePct.coerceIn(0, 200)
        return when (section) {
            FrequencySection.BASS -> copy(bassWidthPct = clamped)
            FrequencySection.MIDS -> copy(midsWidthPct = clamped)
            FrequencySection.TREBLE -> copy(trebleWidthPct = clamped)
            FrequencySection.AIR -> copy(airWidthPct = clamped)
        }
    }
}

enum class EngineMode(
    val id: String,
    val title: String,
    val shortTitle: String,
    val subtitle: String
) {
    LEGACY(
        id = "legacy",
        title = "Legacy Mode",
        shortTitle = "Legacy",
        subtitle = "Standard Android AudioEffect (Equalizer, BassBoost, Virtualizer) for compatibility."
    ),
    GLOBAL(
        id = "global",
        title = "Global Mode",
        shortTitle = "Global",
        subtitle = "System-wide output mix (Session 0 + broadcast sessions) across all apps."
    ),
    ADB_SHIZUKU(
        id = "adb",
        title = "ADB / Shizuku",
        shortTitle = "ADB / Shizuku",
        subtitle = "No root. Needs Shizuku or one-time ADB DUMP permission grant."
    ),
    ROOT(
        id = "root",
        title = "Root Mode",
        shortTitle = "Root",
        subtitle = "Deep Biquad optimization, Mid/Side stereo crossovers, and per-app session capture."
    )
}

enum class CapabilityLevel {
    AVAILABLE,
    REQUIRES_PERMISSION,
    UNSUPPORTED
}

data class ModeStatusInfo(
    val mode: EngineMode,
    val level: CapabilityLevel,
    val summaryText: String,
    val detailReason: String,
    val setupSteps: List<String> = emptyList(),
    val activeSessionsCount: Int = 0
)

data class PresetConfig(
    val id: String,
    val name: String,
    val description: String,
    val gainsDb: List<Float>, // 10 bands: 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    val preampDb: Float,
    val highPassHz: Float,
    val widths: SectionWidths,
    val legacyNativeDb: List<Float>, // 5 bands: ~60, ~230, ~910, ~3600, ~14000
    val bassBoostStrength: Int, // 0..1000
    val virtualizerStrength: Int, // 0..1000
    val qOverrides: Map<Int, Float> = emptyMap(),
    val favorTimeResolution: Boolean = false,
    val isBuiltIn: Boolean = true
)

object EqConstants {
    val MASTER_BANDS: List<EqBandSpec> = listOf(
        EqBandSpec(0, 31, "31", 44f, FrequencySection.BASS, FilterType.LOW_SHELF, 0.9f),
        EqBandSpec(1, 62, "62", 88f, FrequencySection.BASS, FilterType.PEAKING, 1.1f),
        EqBandSpec(2, 125, "125", 177f, FrequencySection.BASS, FilterType.PEAKING, 1.2f),
        EqBandSpec(3, 250, "250", 354f, FrequencySection.BASS, FilterType.PEAKING, 1.4f),
        EqBandSpec(4, 500, "500", 707f, FrequencySection.MIDS, FilterType.PEAKING, 1.4f),
        EqBandSpec(5, 1000, "1k", 1414f, FrequencySection.MIDS, FilterType.PEAKING, 1.4f),
        EqBandSpec(6, 2000, "2k", 2828f, FrequencySection.MIDS, FilterType.PEAKING, 1.4f),
        EqBandSpec(7, 4000, "4k", 5657f, FrequencySection.TREBLE, FilterType.PEAKING, 1.4f),
        EqBandSpec(8, 8000, "8k", 11314f, FrequencySection.TREBLE, FilterType.PEAKING, 1.2f),
        EqBandSpec(9, 16000, "16k", 20000f, FrequencySection.AIR, FilterType.HIGH_SHELF, 0.7f)
    )

    val BUILT_IN_PRESETS: List<PresetConfig> = listOf(
        PresetConfig(
            id = "flat",
            name = "Flat",
            description = "Neutral studio reference with zero coloration.",
            gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            preampDb = 0f,
            highPassHz = 0f,
            widths = SectionWidths(100, 100, 100, 100),
            legacyNativeDb = listOf(0f, 0f, 0f, 0f, 0f),
            bassBoostStrength = 0,
            virtualizerStrength = 0
        ),
        PresetConfig(
            id = "balanced_deep",
            name = "Balanced Deep",
            description = "Warm sub-bass extension, clean lower-mids, and airy imaging.",
            gainsDb = listOf(1f, 1.5f, 0.5f, -1f, 0f, 0f, 0.5f, 1f, 1f, 0.5f),
            preampDb = -1.5f,
            highPassHz = 20f,
            widths = SectionWidths(10, 95, 115, 125),
            legacyNativeDb = listOf(1.5f, -0.5f, 0f, 1f, 0.5f),
            bassBoostStrength = 150,
            virtualizerStrength = 150
        ),
        PresetConfig(
            id = "bass_boost",
            name = "Bass Boost",
            description = "Deep punchy low-end with -5 dB headroom protection and mono sub.",
            gainsDb = listOf(4f, 5f, 3f, -1f, 0f, 0f, 0f, 0f, 0f, 0f),
            preampDb = -5f,
            highPassHz = 25f,
            widths = SectionWidths(0, 85, 105, 110),
            legacyNativeDb = listOf(4f, 0f, 0f, 0f, 0f),
            bassBoostStrength = 400,
            virtualizerStrength = 0
        ),
        PresetConfig(
            id = "vocal_clarity",
            name = "Vocal Clarity",
            description = "60 Hz high-pass cleanup with presence boost and centered vocal image.",
            gainsDb = listOf(-3f, -2f, -1f, -2f, 0f, 1.5f, 2.5f, 2f, 1f, 0f),
            preampDb = -2.5f,
            highPassHz = 60f,
            widths = SectionWidths(0, 85, 110, 120),
            legacyNativeDb = listOf(-2f, -2f, 1.5f, 2f, 0f),
            bassBoostStrength = 0,
            virtualizerStrength = 100
        ),
        PresetConfig(
            id = "percussion_clarity",
            name = "Percussion Clarity",
            description = "Tuned for tabla & percussion: 40 Hz HPF, -3 dB @ 250 Hz boxiness cut, +2 dB @ 4 kHz stroke attack, +1.5 dB @ 8 kHz sizzle.",
            gainsDb = listOf(-4f, 0f, 1f, -3f, -1f, 0f, 1f, 2f, 1.5f, 0.5f),
            preampDb = -2f,
            highPassHz = 40f,
            widths = SectionWidths(10, 100, 115, 125),
            legacyNativeDb = listOf(-0.5f, -2f, 0f, 2f, 0.5f),
            bassBoostStrength = 100,
            virtualizerStrength = 200,
            qOverrides = mapOf(250 to 1.0f, 4000 to 2.0f),
            favorTimeResolution = true
        ),
        PresetConfig(
            id = "custom",
            name = "Custom",
            description = "User-defined equalizer curve and stereo width profile.",
            gainsDb = listOf(3f, 2f, 0f, -3f, 0f, 1f, 2f, 2f, 1f, 0f),
            preampDb = -3f,
            highPassHz = 30f,
            widths = SectionWidths(10, 100, 115, 130),
            legacyNativeDb = listOf(2f, -1.5f, 0.5f, 2f, 0.5f),
            bassBoostStrength = 150,
            virtualizerStrength = 200
        )
    )

    fun calculateAutoPreamp(gainsDb: List<Float>): Float {
        val maxBoost = gainsDb.maxOrNull() ?: 0f
        return if (maxBoost > 0f) -maxBoost else 0f
    }

    /**
     * Converts 10-band master curve to 5 native bands (~60, ~230, ~910, ~3600, ~14000 Hz)
     * using log-frequency interpolation and caps at +4 dB if no preamp is available.
     */
    fun convert10BandTo5BandNative(gainsDb: List<Float>): List<Float> {
        if (gainsDb.size < 10) return List(5) { 0f }
        val b0 = ((gainsDb[0] + gainsDb[1]) / 2f).coerceIn(-12f, 4f)
        val b1 = ((gainsDb[2] + gainsDb[3]) / 2f).coerceIn(-12f, 4f)
        val b2 = ((gainsDb[4] + gainsDb[5]) / 2f).coerceIn(-12f, 4f)
        val b3 = ((gainsDb[6] + gainsDb[7]) / 2f).coerceIn(-12f, 4f)
        val b4 = ((gainsDb[8] + gainsDb[9]) / 2f).coerceIn(-12f, 4f)
        return listOf(b0, b1, b2, b3, b4)
    }
}
