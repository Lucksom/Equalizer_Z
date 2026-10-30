package com.example.model

enum class FrequencySection(
    val id: String,
    val title: String,
    val rangeLabel: String,
    val trueRangeHz: String,
    val hint: String,
    val defaultWidthRange: String,
    val colorRole: SectionColorRole
) {
    BASS(
        id = "bass",
        title = "Bass",
        rangeLabel = "20–350 Hz",
        trueRangeHz = "20–354 Hz",
        hint = "Punch and low end.",
        defaultWidthRange = "0–20%",
        colorRole = SectionColorRole.PRIMARY
    ),
    MIDS(
        id = "mids",
        title = "Mids",
        rangeLabel = "350–2.8k Hz",
        trueRangeHz = "354–2828 Hz",
        hint = "Vocals, guitars, and clarity.",
        defaultWidthRange = "80–100%",
        colorRole = SectionColorRole.SECONDARY
    ),
    TREBLE(
        id = "treble",
        title = "Treble",
        rangeLabel = "2.8–11 kHz",
        trueRangeHz = "2828–11314 Hz",
        hint = "Definition, cymbals, and percussion strikes.",
        defaultWidthRange = "110–120%",
        colorRole = SectionColorRole.TERTIARY
    ),
    AIR(
        id = "air",
        title = "Air",
        rangeLabel = "11–20 kHz",
        trueRangeHz = "11314–20000 Hz",
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
    val bassWidthPct: Int = 100,
    val midsWidthPct: Int = 100,
    val trebleWidthPct: Int = 100,
    val airWidthPct: Int = 100
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
        title = "Legacy",
        shortTitle = "Legacy",
        subtitle = "Works globally across apps using Android AudioEffect (Session 0 + broadcast sessions) without needing ADB, Shizuku, or Root."
    ),
    ADB_SHIZUKU(
        id = "adb",
        title = "ADB / Shizuku",
        shortTitle = "ADB / Shizuku",
        subtitle = "Uses ADB DUMP or Shizuku to discover and attach 31-band DynamicsProcessing + 6-band ceilings to all active app sessions."
    ),
    ROOT(
        id = "root",
        title = "Root",
        shortTitle = "Root",
        subtitle = "APK-only su helper: global session discovery, start-on-boot, codec readout & BT absolute-volume control (no flashing)."
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

/**
 * Output-route profiles (v4 Section 6)
 */
enum class OutputRoute(
    val id: String,
    val label: String,
    val offsetsDb: DoubleArray,
    val baseInputGainDb: Float,
    val limiterThresholdDb: Float,
    val rootHighPassHz: Float = 0f,
    val harmonicMixDeltaDb: Float = 0f,
    val note: String? = null
) {
    SPEAKER(
        id = "speaker",
        label = "Speaker",
        offsetsDb = doubleArrayOf(-9.0, -7.0, -2.0, 2.0, 1.0, 0.0, -1.0, -1.0, 0.0, 0.0),
        baseInputGainDb = -3.0f,
        limiterThresholdDb = -1.0f,
        rootHighPassHz = 140f,
        harmonicMixDeltaDb = 3.0f,
        note = "Protects small driver, lifts 250 Hz, softens 2–4 kHz"
    ),
    WIRED(
        id = "wired",
        label = "Wired Headphones",
        offsetsDb = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        baseInputGainDb = -3.0f,
        limiterThresholdDb = -1.0f
    ),
    BLUETOOTH(
        id = "bluetooth",
        label = "Bluetooth",
        offsetsDb = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        baseInputGainDb = -4.0f,
        limiterThresholdDb = -1.5f,
        note = "-4 dB headroom & -1.5 dB ceiling for lossy codec overshoot"
    ),
    USB(
        id = "usb",
        label = "USB DAC",
        offsetsDb = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        baseInputGainDb = -3.0f,
        limiterThresholdDb = -1.0f,
        note = "Some USB DAC paths may bypass OS effects"
    )
}

/**
 * 6-band Multiband Compressor (Ceilings) configuration (v4 Section 5.2)
 */
data class MbcConfig(
    val attackMs: FloatArray,
    val releaseMs: FloatArray,
    val ratio: FloatArray,
    val baseThresholdDb: FloatArray = floatArrayOf(-6f, -6f, -6f, -7f, -9f, -8f),
    val kneeDb: FloatArray = floatArrayOf(6f, 6f, 6f, 6f, 4f, 6f)
)

data class PresetConfig(
    val id: String,
    val name: String,
    val description: String,
    val gainsDb: List<Float>, // 10 bands: 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    val largestBoostDb: Float,
    val highPassHz: Float,
    val bassHarmonicMixDb: Float?, // null = off (v4 Section 12.2)
    val widths: SectionWidths = SectionWidths(),
    val legacyNativeDb: List<Float>, // 5 bands: ~60, ~230, ~910, ~3600, ~14000 (v4 Section 13)
    val bassBoostStrength: Int, // 0..1000 (Legacy tier 1 only)
    val virtualizerStrength: Int, // 0..1000
    val qOverrides: Map<Int, Float> = emptyMap(),
    val mbcOverride: MbcConfig? = null,
    val bypassChain: Boolean = false,
    val isBuiltIn: Boolean = true
)

/**
 * Parametric filter entry for Headphone Correction Profiles (v4 Section 8.1)
 */
data class ParametricCorrectionFilter(
    val type: FilterType,
    val fcHz: Double,
    val gainDb: Double,
    val q: Double
)

data class HeadphoneProfile(
    val id: String,
    val name: String,
    val category: String, // "over_ear", "in_ear", "earbuds"
    val source: String,
    val license: String,
    val preampDb: Float,
    val filters: List<ParametricCorrectionFilter>,
    val rig: String? = null,
    val autoEqPath: String? = null,
    val rawText: String? = null,
    val isApproximation: Boolean = false
)

/**
 * Per-device profile memory (v5 Section 16.6 & 17.2 F2):
 * Keyed by `route + productName (+ address if available)` and auto-restored within 300 ms on connect.
 */
data class DeviceProfileMemory(
    val deviceKey: String,
    val routeId: String,
    val productName: String,
    val profileId: String,
    val profileName: String,
    val profileSource: String,
    val autoEqPath: String,
    val rawPeqOrGraphicText: String,
    val strength: Float = 0.8f,
    val fullRange: Boolean = false,
    val presetId: String = "percussion_clarity",
    val intensity: Float = 1.0f,
    val tiltBassDb: Float = 0.0f,
    val tiltTrebleDb: Float = 0.0f
)

/**
 * Personal Hearing Curve (v5 Section 17.2 F5):
 * Per-ear offsets at 6 test frequencies (250, 500, 1k, 2k, 4k, 8k Hz), capped at ±6 dB, strength 0.5.
 */
data class PersonalHearingCurve(
    val enabled: Boolean = false,
    val leftEarDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f, 0f),  // 250, 500, 1k, 2k, 4k, 8k Hz
    val rightEarDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f, 0f), // 250, 500, 1k, 2k, 4k, 8k Hz
    val strength: Float = 0.5f
)

object EqConstants {
    // v4 Section 2 & 12.1 Biquad types and Q values:
    // 31 Hz Low-shelf Q 0.9, 62 Hz Peaking Q 1.4, 125–4k Peaking Q 1.6, 8k Peaking Q 1.4, 16k High-shelf Q 0.7
    val MASTER_BANDS: List<EqBandSpec> = listOf(
        EqBandSpec(0, 31, "31", 44f, FrequencySection.BASS, FilterType.LOW_SHELF, 0.9f),
        EqBandSpec(1, 62, "62", 88f, FrequencySection.BASS, FilterType.PEAKING, 1.4f),
        EqBandSpec(2, 125, "125", 177f, FrequencySection.BASS, FilterType.PEAKING, 1.6f),
        EqBandSpec(3, 250, "250", 354f, FrequencySection.BASS, FilterType.PEAKING, 1.6f),
        EqBandSpec(4, 500, "500", 707f, FrequencySection.MIDS, FilterType.PEAKING, 1.6f),
        EqBandSpec(5, 1000, "1k", 1414f, FrequencySection.MIDS, FilterType.PEAKING, 1.6f),
        EqBandSpec(6, 2000, "2k", 2828f, FrequencySection.MIDS, FilterType.PEAKING, 1.6f),
        EqBandSpec(7, 4000, "4k", 5657f, FrequencySection.TREBLE, FilterType.PEAKING, 1.6f),
        EqBandSpec(8, 8000, "8k", 11314f, FrequencySection.TREBLE, FilterType.PEAKING, 1.4f),
        EqBandSpec(9, 16000, "16k", 20000f, FrequencySection.AIR, FilterType.HIGH_SHELF, 0.7f)
    )

    val DEFAULT_MBC_CONFIG = MbcConfig(
        attackMs = floatArrayOf(40f, 15f, 12f, 6f, 1.5f, 2f),
        releaseMs = floatArrayOf(250f, 160f, 130f, 90f, 45f, 50f),
        ratio = floatArrayOf(4.0f, 3.5f, 2.5f, 3.0f, 3.5f, 2.5f),
        baseThresholdDb = floatArrayOf(-6f, -6f, -6f, -7f, -9f, -8f),
        kneeDb = floatArrayOf(6f, 6f, 6f, 6f, 4f, 6f)
    )

    val PERCUSSION_CLARITY_MBC_CONFIG = MbcConfig(
        attackMs = floatArrayOf(50f, 30f, 25f, 12f, 4f, 5f),
        releaseMs = floatArrayOf(250f, 150f, 120f, 80f, 50f, 50f),
        ratio = floatArrayOf(3.0f, 2.8f, 2.2f, 2.5f, 2.8f, 2.0f),
        baseThresholdDb = floatArrayOf(-6f, -6f, -6f, -7f, -9f, -8f),
        kneeDb = floatArrayOf(6f, 6f, 6f, 6f, 4f, 6f)
    )

    // v4 Section 4.1 & Section 13 & Section 15 exact preset tables
    val BUILT_IN_PRESETS: List<PresetConfig> = listOf(
        PresetConfig(
            id = "flat",
            name = "Flat",
            description = "Chain bypassed for bit-transparent reference.",
            gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            largestBoostDb = 0f,
            highPassHz = 0f,
            bassHarmonicMixDb = null,
            legacyNativeDb = listOf(0f, 0f, 0f, 0f, 0f),
            bassBoostStrength = 0,
            virtualizerStrength = 0,
            bypassChain = true
        ),
        PresetConfig(
            id = "balanced_deep",
            name = "Balanced Deep",
            description = "Bass shelf weight, 250–500 Hz dip for tightness, ear-gain lift at 2–4 kHz, and airy top end.",
            gainsDb = listOf(3.5f, 4.5f, 2.5f, -2.5f, -1.5f, 0f, 1f, 2.5f, 2.5f, 1.5f),
            largestBoostDb = 4.5f,
            highPassHz = 20f,
            bassHarmonicMixDb = -12f,
            legacyNativeDb = listOf(4f, -1.9f, -0.2f, 2.3f, 1.7f),
            bassBoostStrength = 150,
            virtualizerStrength = 150
        ),
        PresetConfig(
            id = "bass_boost",
            name = "Bass Boost",
            description = "Deep punchy low end (+6.5 dB @ 62 Hz) kept clean by a -2 dB @ 250 Hz dip.",
            gainsDb = listOf(5.5f, 6.5f, 4f, -2f, -1.5f, -0.5f, 0f, 0f, 0.5f, 0.5f),
            largestBoostDb = 6.5f,
            highPassHz = 25f,
            bassHarmonicMixDb = -9f,
            legacyNativeDb = listOf(4f, -1.3f, -0.6f, 0f, 0.5f),
            bassBoostStrength = 400,
            virtualizerStrength = 0
        ),
        PresetConfig(
            id = "vocal_clarity",
            name = "Vocal Clarity",
            description = "Body cut, presence peak at 2–4 kHz (+3.5 dB), restrained 8 kHz to avoid sibilance.",
            gainsDb = listOf(-3.5f, -2.5f, -1f, -3f, -1.5f, 1.5f, 3.5f, 3.5f, 1.5f, 0.5f),
            largestBoostDb = 3.5f,
            highPassHz = 60f,
            bassHarmonicMixDb = null,
            legacyNativeDb = listOf(-2.6f, -2.8f, 1.1f, 3.5f, 0.7f),
            bassBoostStrength = 0,
            virtualizerStrength = 100
        ),
        PresetConfig(
            id = "percussion_clarity",
            name = "Percussion Clarity",
            description = "Tuned for tabla: 62–125 Hz bayan weight, -3.5 dB @ 250 Hz boxiness cut, +3.5 dB @ 4 kHz stroke attack, +3 dB @ 8 kHz sizzle.",
            gainsDb = listOf(-4f, 1.5f, 2.5f, -3.5f, -2f, 0f, 1.5f, 3.5f, 3f, 1.5f),
            largestBoostDb = 3.5f,
            highPassHz = 40f,
            bassHarmonicMixDb = -15f,
            legacyNativeDb = listOf(1.2f, -2.8f, -0.3f, 3.2f, 1.8f),
            bassBoostStrength = 100,
            virtualizerStrength = 200,
            qOverrides = mapOf(250 to 1.0f, 4000 to 2.0f),
            mbcOverride = PERCUSSION_CLARITY_MBC_CONFIG
        ),
        PresetConfig(
            id = "cinema",
            name = "Cinema",
            description = "Low-end sub weight (+4 dB @ 31–62 Hz) plus clear dialogue presence at 2–4 kHz (+2.5 dB).",
            gainsDb = listOf(4f, 4f, 2f, -2f, -1f, 0.5f, 2.5f, 2.5f, 2f, 2f),
            largestBoostDb = 4f,
            highPassHz = 20f,
            bassHarmonicMixDb = -12f,
            legacyNativeDb = listOf(4f, -1.5f, 0.3f, 2.5f, 2.0f),
            bassBoostStrength = 200,
            virtualizerStrength = 250
        )
    )

    // v4 Section 8.1 Built-in Headphone Correction Profiles (AutoEQ / Harman OE & IE open data)
    val HEADPHONE_PROFILES: List<HeadphoneProfile> = listOf(
        HeadphoneProfile(
            id = "none",
            name = "None (Flat Device)",
            category = "none",
            source = "Bypass",
            license = "Public Domain",
            preampDb = 0f,
            filters = emptyList()
        ),
        HeadphoneProfile(
            id = "sony_wh1000xm5",
            name = "Sony WH-1000XM5",
            category = "over_ear",
            source = "AutoEQ / Oratory1990 Harman OE",
            license = "MIT",
            preampDb = -4.8f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 105.0, -3.5, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 210.0, -4.2, 1.1),
                ParametricCorrectionFilter(FilterType.PEAKING, 1650.0, 2.8, 1.6),
                ParametricCorrectionFilter(FilterType.PEAKING, 3100.0, 3.6, 1.8),
                ParametricCorrectionFilter(FilterType.HIGH_SHELF, 8500.0, 2.0, 0.7)
            )
        ),
        HeadphoneProfile(
            id = "sennheiser_hd650",
            name = "Sennheiser HD 650 / HD 6XX",
            category = "over_ear",
            source = "AutoEQ / Oratory1990 Harman OE",
            license = "MIT",
            preampDb = -5.5f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 75.0, 5.5, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 190.0, -2.2, 1.2),
                ParametricCorrectionFilter(FilterType.PEAKING, 2150.0, 1.5, 2.0),
                ParametricCorrectionFilter(FilterType.PEAKING, 3300.0, 2.4, 2.1),
                ParametricCorrectionFilter(FilterType.HIGH_SHELF, 9500.0, 1.8, 0.7)
            )
        ),
        HeadphoneProfile(
            id = "airpods_pro_2",
            name = "Apple AirPods Pro (2nd Gen)",
            category = "in_ear",
            source = "AutoEQ / Crinacle IEF Neutral",
            license = "MIT",
            preampDb = -3.2f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 90.0, 1.8, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 320.0, -1.5, 1.4),
                ParametricCorrectionFilter(FilterType.PEAKING, 2600.0, 2.2, 1.9),
                ParametricCorrectionFilter(FilterType.PEAKING, 5200.0, -2.4, 2.2),
                ParametricCorrectionFilter(FilterType.HIGH_SHELF, 10000.0, 1.5, 0.7)
            )
        ),
        HeadphoneProfile(
            id = "galaxy_buds2_pro",
            name = "Samsung Galaxy Buds2 Pro",
            category = "in_ear",
            source = "AutoEQ / Harman IE Target",
            license = "MIT",
            preampDb = -3.8f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 105.0, -1.5, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 2900.0, -2.8, 1.8),
                ParametricCorrectionFilter(FilterType.PEAKING, 4800.0, -2.0, 2.0),
                ParametricCorrectionFilter(FilterType.HIGH_SHELF, 9500.0, -1.8, 0.7)
            )
        ),
        HeadphoneProfile(
            id = "pixel_buds_pro",
            name = "Google Pixel Buds Pro",
            category = "in_ear",
            source = "AutoEQ / RTINGS Harman IE",
            license = "MIT",
            preampDb = -4.2f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 95.0, -2.5, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 450.0, 1.8, 1.3),
                ParametricCorrectionFilter(FilterType.PEAKING, 1850.0, 3.2, 1.6),
                ParametricCorrectionFilter(FilterType.PEAKING, 4200.0, -2.0, 2.0)
            )
        ),
        HeadphoneProfile(
            id = "audiotechnica_m50x",
            name = "Audio-Technica ATH-M50x",
            category = "over_ear",
            source = "AutoEQ / Oratory1990 Harman OE",
            license = "MIT",
            preampDb = -4.5f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 80.0, 2.0, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 150.0, -3.8, 1.4),
                ParametricCorrectionFilter(FilterType.PEAKING, 410.0, 2.6, 1.5),
                ParametricCorrectionFilter(FilterType.PEAKING, 2100.0, 3.1, 1.8),
                ParametricCorrectionFilter(FilterType.PEAKING, 4500.0, -3.2, 2.2)
            )
        ),
        HeadphoneProfile(
            id = "bose_qc_ultra",
            name = "Bose QuietComfort Ultra",
            category = "over_ear",
            source = "AutoEQ / Harman OE Target",
            license = "MIT",
            preampDb = -4.0f,
            filters = listOf(
                ParametricCorrectionFilter(FilterType.LOW_SHELF, 110.0, -3.2, 0.7),
                ParametricCorrectionFilter(FilterType.PEAKING, 240.0, -2.5, 1.3),
                ParametricCorrectionFilter(FilterType.PEAKING, 1400.0, 2.2, 1.5),
                ParametricCorrectionFilter(FilterType.PEAKING, 3400.0, 2.5, 1.8)
            )
        )
    )

    val HEARING_TEST_FREQS_HZ = doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)

    /**
     * Returns 6-band MBC config for the preset, optionally applying Night Mode (v5 Section 17.2 F8):
     * Night mode lowers the 6 ceiling thresholds (-6 dB) and raises the ratios (+1.5), keeping makeup unchanged.
     */
    fun mbcForPreset(preset: PresetConfig?, nightMode: Boolean = false): MbcConfig {
        val base = preset?.mbcOverride ?: DEFAULT_MBC_CONFIG
        if (!nightMode) return base
        return MbcConfig(
            attackMs = base.attackMs.clone(),
            releaseMs = base.releaseMs.clone(),
            ratio = FloatArray(6) { (base.ratio[it] + 1.5f).coerceAtMost(8.0f) },
            baseThresholdDb = FloatArray(6) { base.baseThresholdDb[it] - 6.0f },
            kneeDb = base.kneeDb.clone()
        )
    }

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
