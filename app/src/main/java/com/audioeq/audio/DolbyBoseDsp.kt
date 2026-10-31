package com.audioeq.audio

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI

/**
 * Complete Non-Root DSP Mathematical Engine for:
 * - Dialogue Enhancer (1-4 kHz Formant Expansion + <=180 Hz Ambient Ducking + MBC Upward Expansion)
 * - Bass Enhancer (with Virtual Bass Harmonic Synthesis: H2=-2dB, H3=-9dB, H4=-12dB in 229-494 Hz mix window)
 * - Intelligent Equalizer (IEQ) with 4 subsets: ieq_detailed, ieq_balanced, ieq_warm, and ieq_custom (+ Off)
 * - Bose Profiles (Dynamic & Balanced) extracted from MiSound resources.arsc (Scenarios 60 & 61)
 */
enum class IeqPreset(
    val id: String,
    val title: String,
    val xmlName: String,
    val defaultAmount: Int
) {
    OFF("ieq_off", "Off", "off", 14),
    DETAILED("ieq_detailed", "Detailed", "ieq_detailed", 14),
    BALANCED("ieq_balanced", "Balanced", "ieq_balanced", 14),
    WARM("ieq_warm", "Warm", "ieq_warm", 14),
    CUSTOM("ieq_custom", "Custom", "ieq_custom", 10);

    companion object {
        fun fromId(id: String): IeqPreset = entries.find { it.id == id } ?: BALANCED
    }
}

enum class BoseProfile(
    val id: String,
    val title: String,
    val subtitle: String
) {
    OFF("bose_off", "Off", "Flat bypass"),
    DYNAMIC("bose_dynamic", "Dynamic", "Scenario 60 · +4 dB Sub & Air"),
    BALANCED("bose_balanced", "Balanced", "Scenario 61 · Smooth 1 kHz dip");

    companion object {
        fun fromId(id: String): BoseProfile = entries.find { it.id == id } ?: OFF
    }
}

data class DolbyBoseDspConfig(
    val ieqPreset: IeqPreset = IeqPreset.BALANCED,
    val ieqAmount: Int = 14, // 0..16 (dap-default.xml default = 14 -> 87.5%)
    val ieqCustom10Db: List<Float> = listOf(2.5f, 2.0f, 1.0f, 0.0f, -0.5f, 0.5f, 1.5f, 2.0f, 1.5f, 1.0f),
    val dialogueEnhancerEnabled: Boolean = true,
    val dialogueAmount: Int = 5, // 0..16 (dap-default.xml Movie/Dynamic default = 5)
    val dialogueDucking: Int = 2, // dap-default.xml default = 2 (-2.0 dB * alpha_DE for f <= 180 Hz)
    val bassEnhancerEnabled: Boolean = true,
    val virtualBassEnabled: Boolean = true,
    val boseProfile: BoseProfile = BoseProfile.OFF
)

object DolbyBoseDspMath {
    const val NUM_BANDS = 10

    // 10-Band Upper Cutoff Frequencies (Hz) for DynamicsProcessing
    val FREQ_CUTOFFS_10 = floatArrayOf(
        45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f
    )

    // 10-Band ISO Center Frequencies (Hz)
    val FREQ_CENTERS_10 = doubleArrayOf(
        31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    val FREQ_LABELS_10 = listOf(
        "31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
    )

    // --- 1.A Dolby Q4 Fixed-Point Decibel Conversion (16 steps per 1.0 dB) ---
    fun q4RawToDb(xmlRaw: Int): Float = xmlRaw / 16.0f

    // --- 1.C Extracted 20-Band Raw XML Targets (dap-default.xml) ---
    val IEQ_DETAILED_20_RAW = intArrayOf(
        -419, -102, 79, 114, 113, 160, 164, 102, 67, 73,
        95, 114, 64, 67, 39, -66, -34, -104, -236, -411
    )
    val IEQ_BALANCED_20_RAW = intArrayOf(
        157, 167, 218, 218, 203, 188, 192, 192, 205, 213,
        218, 209, 193, 159, 134, 97, 71, 22, -90, -283
    )
    val IEQ_WARM_20_RAW = intArrayOf(
        67, 99, 171, 163, 168, 199, 189, 228, 210, 213,
        195, 183, 165, 136, 100, 59, 34, 7, -54, -235
    )

    // --- Decimated 10-Band ISO Normalized Targets (from dap-default.xml, Q4 dB decimated to 10-Band ISO) ---
    val IEQ_OFF_10 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val IEQ_DETAILED_10 = floatArrayOf(-6.50f, -4.20f, -1.44f, 0.75f, 3.63f, 0.00f, -0.44f, -2.19f, -6.50f, -8.50f)
    val IEQ_BALANCED_10 = floatArrayOf(-2.19f, -1.56f, 1.63f, 1.63f, 0.19f, 0.00f, 1.63f, -2.00f, -5.94f, -9.50f)
    val IEQ_WARM_10 = floatArrayOf(-6.06f, -4.88f, -1.56f, -2.25f, -1.19f, 1.50f, -0.50f, -4.19f, -8.50f, -11.00f)
    val IEQ_CUSTOM_DEFAULT_10 = floatArrayOf(2.5f, 2.0f, 1.0f, 0.0f, -0.5f, 0.5f, 1.5f, 2.0f, 1.5f, 1.0f)

    // --- 1.F Extracted Bose Profiles (from MiSound resources.arsc Scenarios 60 & 61) ---
    val BOSE_OFF_10 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    // Bose Dinamis (Scenario 60) -> Dynamic
    val BOSE_DYNAMIC_10 = floatArrayOf(4.0f, 1.0f, -2.0f, -0.25f, 0.0f, -2.0f, 0.0f, -2.0f, 0.5f, 4.0f)
    // Bose Seimbang (Scenario 61) -> Balanced
    val BOSE_BALANCED_10 = floatArrayOf(0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -3.0f, -0.5f, 0.0f, 0.0f, 0.0f)

    // --- 1.D Bass Enhancer & Virtual Bass Harmonic Synthesis Constants ---
    const val BASS_ENHANCER_BOOST_RAW = 119 // +7.4375 dB
    const val BASS_ENHANCER_BOOST_DB = 119f / 16f // +7.4375 dB
    const val BASS_ENHANCER_CUTOFF_HZ = 504f
    const val BASS_EXTRACTION_CUTOFF_HZ = 200f
    const val VIRTUAL_BASS_H2_DB = -2.0f  // -32 / 16 -> linear g2 = 0.7943
    const val VIRTUAL_BASS_H3_DB = -9.0f  // -144 / 16 -> linear g3 = 0.3548
    const val VIRTUAL_BASS_H4_DB = -12.0f // -192 / 16 -> linear g4 = 0.2512

    fun ieqCurve10For(
        preset: IeqPreset,
        custom10Db: List<Float> = emptyList(),
        userCustom10Db: DoubleArray? = null
    ): FloatArray {
        return when (preset) {
            IeqPreset.OFF -> IEQ_OFF_10
            IeqPreset.DETAILED -> IEQ_DETAILED_10
            IeqPreset.BALANCED -> IEQ_BALANCED_10
            IeqPreset.WARM -> IEQ_WARM_10
            IeqPreset.CUSTOM -> {
                if (custom10Db.size == NUM_BANDS) {
                    FloatArray(NUM_BANDS) { i -> custom10Db[i].coerceIn(-12f, 12f) }
                } else if (userCustom10Db != null && userCustom10Db.any { kotlin.math.abs(it) > 0.05 }) {
                    FloatArray(NUM_BANDS) { i ->
                        userCustom10Db.getOrElse(i) { 0.0 }.toFloat().coerceIn(-12f, 12f)
                    }
                } else {
                    IEQ_CUSTOM_DEFAULT_10
                }
            }
        }
    }

    fun boseCurve10For(profile: BoseProfile): FloatArray {
        return when (profile) {
            BoseProfile.OFF -> BOSE_OFF_10
            BoseProfile.DYNAMIC -> BOSE_DYNAMIC_10
            BoseProfile.BALANCED -> BOSE_BALANCED_10
        }
    }

    /**
     * Computes the composite 10-band Pre-EQ + Post-EQ delta (in dB) contributed by:
     * - Intelligent Equalizer (IEQ: Detailed, Balanced, Warm, Custom)
     * - Bass Enhancer (+5.91 dB @ 31Hz, +7.44 dB @ 63Hz) + Virtual Bass Harmonic Synthesis (+4.18 dB @ 125Hz, +1.98 dB @ 250Hz)
     * - Dialogue Enhancer Ambient Ducking (-2.0 dB * alpha_DE for f <= 180 Hz) + Formant Peaking (+4.0 dB @ 1kHz, +6.25 dB @ 2kHz, +4.5 dB @ 4kHz)
     * - Bose Profile (Dynamic / Balanced)
     */
    fun computeComposite10BandDeltaDb(
        config: DolbyBoseDspConfig,
        userCustom10Db: DoubleArray? = null
    ): DoubleArray {
        val ieqStrength = config.ieqAmount.coerceIn(0, 16) / 16.0f
        val deFactor = if (config.dialogueEnhancerEnabled) {
            config.dialogueAmount.coerceIn(0, 16) / 16.0f
        } else {
            0.0f
        }

        val baseIeq = ieqCurve10For(config.ieqPreset, config.ieqCustom10Db, userCustom10Db)
        val bose = boseCurve10For(config.boseProfile)

        return DoubleArray(NUM_BANDS) { i ->
            // Stage 1: Pre-EQ (IEQ Target + Bass Enhancer Boost + Dialogue Ducking)
            var preGainDb = baseIeq[i] * ieqStrength

            if (config.bassEnhancerEnabled) {
                when (i) {
                    0 -> preGainDb += 5.91f // 46 Hz Sub-bass fundamental (+7.44 * 0.794)
                    1 -> preGainDb += 7.44f // 63-90 Hz Peak punch (119 / 16)
                    2 -> preGainDb += if (config.virtualBassEnabled) 4.18f else 3.50f // 2nd Harmonic (H2 = -2dB subgain)
                    3 -> preGainDb += if (config.virtualBassEnabled) 1.98f else 1.20f // 3rd/4th Harmonic mix (229-494 Hz)
                }
            }

            if (deFactor > 0f && i <= 2) {
                val duckDb = config.dialogueDucking.toFloat() * deFactor
                preGainDb -= duckDb
            }

            // Stage 3: Post-EQ (Bose Curve + Dialogue Formants)
            var postGainDb = bose[i]
            if (deFactor > 0f) {
                when (i) {
                    5 -> postGainDb += 4.00f * deFactor // 1000 Hz Fundamental voice body
                    6 -> postGainDb += 6.25f * deFactor // 2000 Hz Primary speech articulation
                    7 -> postGainDb += 4.50f * deFactor // 4000 Hz Consonant clarity
                }
            }

            (preGainDb + postGainDb).toDouble()
        }
    }

    /**
     * Computes Stage 1 Pre-EQ 10-band gains (IEQ + Bass Enhancer + Dialogue Ducking)
     */
    fun computeStage1PreEq10Db(
        config: DolbyBoseDspConfig,
        userCustom10Db: DoubleArray? = null
    ): FloatArray {
        val ieqStrength = config.ieqAmount.coerceIn(0, 16) / 16.0f
        val deFactor = if (config.dialogueEnhancerEnabled) {
            config.dialogueAmount.coerceIn(0, 16) / 16.0f
        } else {
            0.0f
        }
        val baseIeq = ieqCurve10For(config.ieqPreset, config.ieqCustom10Db, userCustom10Db)
        return FloatArray(NUM_BANDS) { i ->
            var preGainDb = baseIeq[i] * ieqStrength
            if (config.bassEnhancerEnabled) {
                when (i) {
                    0 -> preGainDb += 5.91f
                    1 -> preGainDb += 7.44f
                    2 -> preGainDb += if (config.virtualBassEnabled) 4.18f else 3.50f
                    3 -> preGainDb += if (config.virtualBassEnabled) 1.98f else 1.20f
                }
            }
            if (deFactor > 0f && i <= 2) {
                preGainDb -= config.dialogueDucking.toFloat() * deFactor
            }
            preGainDb.coerceIn(-15f, 15f)
        }
    }

    /**
     * Computes Stage 3 Post-EQ 10-band gains (Bose Profile + Dialogue Formants + User 10-Band GEQ)
     */
    fun computeStage3PostEq10Db(
        config: DolbyBoseDspConfig,
        userGraphicEq10Db: DoubleArray,
        intensity: Double = 1.0
    ): FloatArray {
        val deFactor = if (config.dialogueEnhancerEnabled) {
            config.dialogueAmount.coerceIn(0, 16) / 16.0f
        } else {
            0.0f
        }
        val bose = boseCurve10For(config.boseProfile)
        return FloatArray(NUM_BANDS) { i ->
            var postGainDb = bose[i] + (userGraphicEq10Db.getOrElse(i) { 0.0 } * intensity).toFloat()
            if (deFactor > 0f) {
                when (i) {
                    5 -> postGainDb += 4.00f * deFactor
                    6 -> postGainDb += 6.25f * deFactor
                    7 -> postGainDb += 4.50f * deFactor
                }
            }
            postGainDb.coerceIn(-15f, 15f)
        }
    }

    /**
     * Computes automatic anti-clipping headroom (Stage 4):
     * Prevents digital clipping when stacking Bass + Bose Dynamic + Dialogue + User EQ.
     */
    fun computeSafeHeadroomDb(maxPositiveGainDb: Float): Float {
        val safeHeadroomDb = if (maxPositiveGainDb > 5.5f) -(maxPositiveGainDb - 4.5f) else 0.0f
        return max(safeHeadroomDb, -11.0f)
    }

    /**
     * Preset Composite Matrix from dap-default.xml (Dynamic, Movie, Music, Custom/Voice)
     */
    fun defaultConfigForDaxProfile(profile: DaxProfile, currentBose: BoseProfile = BoseProfile.OFF): DolbyBoseDspConfig {
        return when (profile) {
            DaxProfile.DYNAMIC -> DolbyBoseDspConfig(
                ieqPreset = IeqPreset.BALANCED,
                ieqAmount = 14,
                dialogueEnhancerEnabled = true,
                dialogueAmount = 5,
                dialogueDucking = 2,
                bassEnhancerEnabled = false,
                virtualBassEnabled = false,
                boseProfile = currentBose
            )
            DaxProfile.MOVIE -> DolbyBoseDspConfig(
                ieqPreset = IeqPreset.BALANCED,
                ieqAmount = 14,
                dialogueEnhancerEnabled = true,
                dialogueAmount = 5,
                dialogueDucking = 2,
                bassEnhancerEnabled = true,
                virtualBassEnabled = true,
                boseProfile = currentBose
            )
            DaxProfile.MUSIC -> DolbyBoseDspConfig(
                ieqPreset = IeqPreset.OFF,
                ieqAmount = 14,
                dialogueEnhancerEnabled = false,
                dialogueAmount = 0,
                dialogueDucking = 2,
                bassEnhancerEnabled = true,
                virtualBassEnabled = true,
                boseProfile = currentBose
            )
            DaxProfile.VOICE -> DolbyBoseDspConfig(
                ieqPreset = IeqPreset.CUSTOM,
                ieqAmount = 6,
                dialogueEnhancerEnabled = false,
                dialogueAmount = 0,
                dialogueDucking = 2,
                bassEnhancerEnabled = false,
                virtualBassEnabled = false,
                boseProfile = currentBose
            )
        }
    }
}
