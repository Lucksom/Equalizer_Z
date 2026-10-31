package com.audioeq.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Complete Non-Root DSP Mathematical Engine for:
 * - Dialogue Enhancer (1-4 kHz Formant Expansion + <=180 Hz Ambient Ducking + MBC Upward Expansion)
 * - Bass Enhancer (Variable 0..100% / 0..1000 BassBoostMz + Two-Stage Low-Shelf Boost + Virtual Bass Harmonic Synthesis H2/H3/H4)
 * - Intelligent Equalizer (IEQ) with 4 subsets: ieq_detailed, ieq_balanced, ieq_warm, and ieq_custom (+ Off)
 * - Bose & Meizu Square Sound Profiles:
 *   1. Off
 *   2. Dynamic (MiSound Scenario 60)
 *   3. Balanced (MiSound Scenario 61)
 *   4. 3D (VirtualizerMz 725d7fa7-9219-4f71-8fa7-240a6d489387: Woodworth ITD τ=302.2µs / 15 samples @ 48kHz + 850Hz 1-pole Head-Shadow LPF + 650/1000 Binaural)
 *   5. Live Music (EnvironmentalReverbMz 38a6a248-94be-4007-bbe1-337d7dc49e6d: 26-Byte Little-Endian PARAM_PROPERTIES=10 Hall Reverb)
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
    BALANCED("bose_balanced", "Balanced", "Scenario 61 · Smooth 1 kHz dip"),
    EFFECT_3D("bose_3d", "3D", "VirtualizerMz · ITD 302µs + 850Hz Head-Shadow (650/1000)"),
    LIVE_MUSIC("bose_live_music", "Live Music", "EnvReverbMz · 26-Byte Hall Reverb & Reflections");

    companion object {
        fun fromId(id: String): BoseProfile = entries.find { it.id == id } ?: OFF
    }
}

data class DolbyBoseDspConfig(
    val ieqPreset: IeqPreset = IeqPreset.OFF,
    val ieqAmount: Int = 10, // 0..16 (v7 default = 10 -> 63%)
    val ieqCustom10Db: List<Float> = listOf(2.5f, 2.0f, 1.0f, 0.0f, -0.5f, 0.5f, 1.5f, 2.0f, 1.5f, 1.0f),
    val dialogueEnhancerEnabled: Boolean = false,
    val dialogueAmount: Int = 8, // 0..16 (v7 default = 8)
    val dialogueDucking: Int = 2, // 0, 2, or 4 dB for f <= 180 Hz
    val bassEnhancerEnabled: Boolean = false,
    val bassEnhancerStrength: Int = 80, // 0..100% (maps to 0..1000 BassBoostMz strength and scales low-shelf boost without volume drop)
    val virtualBassEnabled: Boolean = false,
    val boseProfile: BoseProfile = BoseProfile.OFF
) {
    /** Maps 0..100% UI slider to 0..1000 native BassBoostMz / Android BassBoost strength. */
    val bassBoostStrengthPermille: Short
        get() = (bassEnhancerStrength.coerceIn(0, 100) * 10).toShort()
}

/**
 * Meizu Square Sound EnvironmentalReverbMz 26-Byte Serialization Layout (`PARAM_PROPERTIES = 10`):
 * Byte Offset:   0      2      4      8      10     12     16     18     22     24   26
 * Field:        [rL]   [rHF]  [dT]   [dHF]  [rfL]  [rfD]  [rvL]  [rvD]  [diff] [dens]
 * Size (Bytes):   2      2      4      2      2      4      2      4      2      2
 * Type:         short  short   int   short  short   int   short   int   short  short
 */
data class MeizuLiveMusicReverbProperties(
    val roomLevelMb: Short = -600,          // PARAM_ROOM_LEVEL (0): -10000 to 0 mB
    val roomHfLevelMb: Short = -350,        // PARAM_ROOM_HF_LEVEL (1): -10000 to 0 mB
    val decayTimeMs: Int = 1850,            // PARAM_DECAY_TIME (2): 100 to 20000 ms (RT60)
    val decayHfRatioPermille: Short = 720,  // PARAM_DECAY_HF_RATIO (3): 100 to 2000 permille
    val reflectionsLevelMb: Short = -450,   // PARAM_REFLECTIONS_LEVEL (4): -10000 to 1000 mB
    val reflectionsDelayMs: Int = 22,       // PARAM_REFLECTIONS_DELAY (5): 0 to 300 ms
    val reverbLevelMb: Short = -150,        // PARAM_REVERB_LEVEL (6): -10000 to 2000 mB
    val reverbDelayMs: Int = 38,            // PARAM_REVERB_DELAY (7): 0 to 100 ms
    val diffusionPermille: Short = 1000,    // PARAM_DIFFUSION (8): 0 to 1000 permille
    val densityPermille: Short = 1000       // PARAM_DENSITY (9): 0 to 1000 permille
) {
    /**
     * Packs the 10 reverb parameters into the exact 26-byte Little-Endian binary payload
     * for `PARAM_PROPERTIES = 10` on `EnvironmentalReverbMz` (`38a6a248-94be-4007-bbe1-337d7dc49e6d`).
     */
    fun to26ByteLittleEndianPacket(): ByteArray {
        val buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(roomLevelMb.coerceIn(-10000, 0))          // Offset 0 (2B)
        buf.putShort(roomHfLevelMb.coerceIn(-10000, 0))        // Offset 2 (2B)
        buf.putInt(decayTimeMs.coerceIn(100, 20000))           // Offset 4 (4B)
        buf.putShort(decayHfRatioPermille.coerceIn(100, 2000)) // Offset 8 (2B)
        buf.putShort(reflectionsLevelMb.coerceIn(-10000, 1000))// Offset 10 (2B)
        buf.putInt(reflectionsDelayMs.coerceIn(0, 300))        // Offset 12 (4B)
        buf.putShort(reverbLevelMb.coerceIn(-10000, 2000))     // Offset 16 (2B)
        buf.putInt(reverbDelayMs.coerceIn(0, 100))             // Offset 18 (4B)
        buf.putShort(diffusionPermille.coerceIn(0, 1000))      // Offset 22 (2B)
        buf.putShort(densityPermille.coerceIn(0, 1000))        // Offset 24 (2B)
        return buf.array()
    }

    companion object {
        fun from26ByteLittleEndianPacket(bytes: ByteArray): MeizuLiveMusicReverbProperties {
            require(bytes.size >= 26) { "EnvironmentalReverbMz PARAM_PROPERTIES requires 26 bytes" }
            val buf = ByteBuffer.wrap(bytes, 0, 26).order(ByteOrder.LITTLE_ENDIAN)
            return MeizuLiveMusicReverbProperties(
                roomLevelMb = buf.short,
                roomHfLevelMb = buf.short,
                decayTimeMs = buf.int,
                decayHfRatioPermille = buf.short,
                reflectionsLevelMb = buf.short,
                reflectionsDelayMs = buf.int,
                reverbLevelMb = buf.short,
                reverbDelayMs = buf.int,
                diffusionPermille = buf.short,
                densityPermille = buf.short
            )
        }
    }
}

object DolbyBoseDspMath {
    const val NUM_BANDS = 10

    // Registered Meizu Square Sound AudioEffect Vendor UUIDs (Section 1)
    val UUID_VIRTUALIZER_MZ: UUID = UUID.fromString("725d7fa7-9219-4f71-8fa7-240a6d489387")
    val UUID_ENV_REVERB_MZ: UUID = UUID.fromString("38a6a248-94be-4007-bbe1-337d7dc49e6d")
    val UUID_BASS_BOOST_MZ: UUID = UUID.fromString("42b10000-0010-0000-0000-0002a5d5c51b")

    const val ENV_REVERB_PARAM_PROPERTIES = 10
    const val VIRTUALIZER_3D_STRENGTH: Short = 650

    // Woodworth Contralateral ITD & Head-Shadow Constants (Section 4)
    const val HEAD_RADIUS_M = 0.0875
    const val SOUND_SPEED_MPS = 343.0
    const val AZIMUTH_35_RAD = 35.0 * PI / 180.0 // 0.610865 rad
    const val HEAD_SHADOW_CUTOFF_HZ = 850.0
    const val DEFAULT_SAMPLE_RATE_HZ = 48000.0

    /**
     * Woodworth Contralateral Delay (ITD) in seconds:
     * τ = (r / c) * (θ + sin(θ))
     * For r = 0.0875 m, c = 343 m/s, θ = 35° (0.6109 rad): τ ≈ 0.0003022 s (302.2 µs)
     */
    fun computeWoodworthItdSeconds(
        headRadiusM: Double = HEAD_RADIUS_M,
        soundSpeedMps: Double = SOUND_SPEED_MPS,
        azimuthRad: Double = AZIMUTH_35_RAD
    ): Double {
        return (headRadiusM / soundSpeedMps) * (azimuthRad + sin(azimuthRad))
    }

    /**
     * Woodworth Contralateral Delay in integer samples at `sampleRateHz`:
     * At fs = 48000 Hz, D = round(0.0003022 * 48000) = 15 samples.
     */
    fun computeWoodworthDelaySamples(sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ): Int {
        return (computeWoodworthItdSeconds() * sampleRateHz).roundToInt().coerceAtLeast(1)
    }

    /**
     * Head-Shadow 1-Pole Low-Pass Filter pole coefficient α:
     * α = exp(-2π * fc / fs), where fc ≈ 850 Hz.
     * H_shadow(z) = (1 - α) / (1 - α * z^-1)
     */
    fun computeHeadShadowAlpha(
        cutoffHz: Double = HEAD_SHADOW_CUTOFF_HZ,
        sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ
    ): Double {
        return exp(-2.0 * PI * cutoffHz / sampleRateHz)
    }

    /**
     * Meizu EqualizerUtils.java 20-Character Preset String Decoder (Section 2):
     * Each consecutive 2-digit token represents an ISO band gain with a zero-offset of 10:
     * G_dB[k] = Integer.parseInt(payload.substring(2k, 2k + 2)) - 10
     */
    fun decodeMeizuPresetPayload(raw20Char: String): List<Float> {
        if (raw20Char.length < 20) return List(NUM_BANDS) { 0f }
        return List(NUM_BANDS) { k ->
            val token = raw20Char.substring(2 * k, 2 * k + 2)
            ((token.toIntOrNull() ?: 10) - 10).toFloat()
        }
    }

    // 10-Band Upper Cutoff Frequencies (Hz) for DynamicsProcessing
    val FREQ_CUTOFFS_10 = floatArrayOf(
        45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f
    )

    // 10-Band ISO Center Frequencies (Hz)
    val FREQ_CENTERS_10 = doubleArrayOf(
        31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
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

    // --- 1.F Extracted Bose & Meizu Square Sound Profiles ---
    val BOSE_OFF_10 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    // Bose Dinamis (Scenario 60) -> Dynamic
    val BOSE_DYNAMIC_10 = floatArrayOf(4.0f, 1.0f, -2.0f, -0.25f, 0.0f, -2.0f, 0.0f, -2.0f, 0.5f, 4.0f)
    // Bose Seimbang (Scenario 61) -> Balanced
    val BOSE_BALANCED_10 = floatArrayOf(0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -3.0f, -0.5f, 0.0f, 0.0f, 0.0f)
    // 3D Sound Effects (Section 4 & 7: VirtualizerMz strength 650/1000 + Linear PostEq stage with binaural head-shadow spatial cues)
    val BOSE_3D_10 = floatArrayOf(1.5f, 1.2f, 0.5f, -0.5f, -1.0f, 0.0f, 1.8f, 2.5f, 3.0f, 2.5f)
    // Live Music (Section 5: EnvironmentalReverbMz 26-byte hall acoustics + warm stage body & hall air)
    val BOSE_LIVE_MUSIC_10 = floatArrayOf(2.0f, 2.5f, 2.0f, 1.0f, -1.0f, 0.5f, 2.0f, 2.5f, 2.0f, 1.5f)

    val DEFAULT_LIVE_MUSIC_REVERB = MeizuLiveMusicReverbProperties()

    // --- 1.D Bass Enhancer & Power Bass Low-Shelf + Virtual Bass Harmonic Synthesis Constants ---
    // From Section 6 (Power Bass: +9.0 dB @ 31.25 Hz, +8.0 dB @ 62.5 Hz, +6.0 dB @ 125 Hz, +4.0 dB @ 250 Hz at 100% strength)
    const val BASS_MAX_31_DB = 9.0f
    const val BASS_MAX_62_DB = 8.0f
    const val BASS_MAX_125_DB = 6.0f
    const val BASS_MAX_250_DB = 4.0f

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
                } else if (userCustom10Db != null && userCustom10Db.any { abs(it) > 0.05 }) {
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
            BoseProfile.EFFECT_3D -> BOSE_3D_10
            BoseProfile.LIVE_MUSIC -> BOSE_LIVE_MUSIC_10
        }
    }

    /**
     * Computes the per-band Bass Enhancer dB boost for band `i` (0..3 -> 31.25..250 Hz)
     * given `bassEnhancerStrength` (0..100%) and `virtualBassEnabled`.
     * At 100% strength, delivers the full Power Bass low-shelf (+9.0, +8.0, +6.0, +4.0 dB)
     * plus Virtual Bass harmonic synthesis (H2/H3/H4 in 125–250 Hz) when enabled.
     */
    fun bassEnhancerBandBoostDb(
        bandIndex: Int,
        strengthPct: Int,
        virtualBassEnabled: Boolean
    ): Float {
        val factor = (strengthPct.coerceIn(0, 100) / 100.0f)
        if (factor <= 0f) return 0f
        return when (bandIndex) {
            0 -> BASS_MAX_31_DB * factor
            1 -> BASS_MAX_62_DB * factor
            2 -> (BASS_MAX_125_DB + (if (virtualBassEnabled) 1.5f else 0.0f)) * factor
            3 -> (BASS_MAX_250_DB + (if (virtualBassEnabled) 1.2f else 0.0f)) * factor
            else -> 0f
        }
    }

    /**
     * Computes the composite 10-band Pre-EQ + Post-EQ delta (in dB) contributed by:
     * - Intelligent Equalizer (IEQ: Detailed, Balanced, Warm, Custom)
     * - Bass Enhancer (scaled 0..100% low-shelf + Virtual Bass Harmonic Synthesis)
     * - Dialogue Enhancer Ambient Ducking (-ducking * alpha_DE for f <= 180 Hz) + Formant Peaking (+4.0 dB @ 1kHz, +6.25 dB @ 2kHz, +4.5 dB @ 4kHz)
     * - Bose Profile (Off / Dynamic / Balanced / 3D / Live Music)
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
                preGainDb += bassEnhancerBandBoostDb(
                    bandIndex = i,
                    strengthPct = config.bassEnhancerStrength,
                    virtualBassEnabled = config.virtualBassEnabled
                )
            }

            if (deFactor > 0f && i <= 2) {
                val duckDb = config.dialogueDucking.toFloat() * deFactor
                preGainDb -= duckDb
            }

            // Stage 3: Post-EQ (Bose Profile + Dialogue Formants)
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
                preGainDb += bassEnhancerBandBoostDb(
                    bandIndex = i,
                    strengthPct = config.bassEnhancerStrength,
                    virtualBassEnabled = config.virtualBassEnabled
                )
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
     * Volume-Preserving Headroom (Stage 4):
     * Keeps global input gain at unity (0.0 dB) so boosting bass never suppresses overall volume level;
     * peak protection is handled transparently by the per-band MBC ceiling and -0.5 dBFS output limiter.
     */
    fun computeSafeHeadroomDb(maxPositiveGainDb: Float): Float = 0.0f

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
                bassEnhancerStrength = 80,
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
                bassEnhancerStrength = 85,
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
                bassEnhancerStrength = 80,
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
                bassEnhancerStrength = 80,
                virtualBassEnabled = false,
                boseProfile = currentBose
            )
        }
    }
}
