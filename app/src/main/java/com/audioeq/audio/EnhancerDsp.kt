package com.audioeq.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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

enum class AudioProfile(
    val id: String,
    val title: String,
    val subtitle: String
) {
    OFF("profile_off", "Off", "Flat bypass"),
    DYNAMIC("profile_dynamic", "Dynamic", "Enhanced Sub & Air"),
    BALANCED("profile_balanced", "Balanced", "Smooth 1 kHz dip"),
    EFFECT_3D("profile_3d", "3D", "ITD 302µs + 850Hz Head-Shadow"),
    LIVE_MUSIC("profile_live_music", "Live Music", "Hall Reverb & Reflections");

    companion object {
        fun fromId(id: String): AudioProfile = entries.find { it.id == id } ?: OFF
    }
}

data class EnhancerConfig(
    val ieqPreset: IeqPreset = IeqPreset.OFF,
    val ieqAmount: Int = 10,
    val ieqCustom10Db: List<Float> = listOf(2.5f, 2.0f, 1.0f, 0.0f, -0.5f, 0.5f, 1.5f, 2.0f, 1.5f, 1.0f),
    val dialogueEnhancerEnabled: Boolean = false,
    val dialogueAmount: Int = 8,
    val dialogueDucking: Int = 2,
    val bassEnhancerEnabled: Boolean = false,
    val bassEnhancerStrength: Int = 80,
    val virtualBassEnabled: Boolean = false,
    val profile: AudioProfile = AudioProfile.OFF
) {
    val bassBoostStrengthPermille: Short
        get() = (bassEnhancerStrength.coerceIn(0, 100) * 10).toShort()
}

data class LiveMusicReverbProperties(
    val roomLevelMb: Short = -600,
    val roomHfLevelMb: Short = -350,
    val decayTimeMs: Int = 1850,
    val decayHfRatioPermille: Short = 720,
    val reflectionsLevelMb: Short = -450,
    val reflectionsDelayMs: Int = 22,
    val reverbLevelMb: Short = -150,
    val reverbDelayMs: Int = 38,
    val diffusionPermille: Short = 1000,
    val densityPermille: Short = 1000
) {
    fun to26ByteLittleEndianPacket(): ByteArray {
        val buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(roomLevelMb.coerceIn(-10000, 0))
        buf.putShort(roomHfLevelMb.coerceIn(-10000, 0))
        buf.putInt(decayTimeMs.coerceIn(100, 20000))
        buf.putShort(decayHfRatioPermille.coerceIn(100, 2000))
        buf.putShort(reflectionsLevelMb.coerceIn(-10000, 1000))
        buf.putInt(reflectionsDelayMs.coerceIn(0, 300))
        buf.putShort(reverbLevelMb.coerceIn(-10000, 2000))
        buf.putInt(reverbDelayMs.coerceIn(0, 100))
        buf.putShort(diffusionPermille.coerceIn(0, 1000))
        buf.putShort(densityPermille.coerceIn(0, 1000))
        return buf.array()
    }

    companion object {
        fun from26ByteLittleEndianPacket(bytes: ByteArray): LiveMusicReverbProperties {
            require(bytes.size >= 26)
            val buf = ByteBuffer.wrap(bytes, 0, 26).order(ByteOrder.LITTLE_ENDIAN)
            return LiveMusicReverbProperties(
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

object EnhancerMath {
    const val NUM_BANDS = 10

    val UUID_VIRTUALIZER_MZ: UUID = UUID.fromString("725d7fa7-9219-4f71-8fa7-240a6d489387")
    val UUID_ENV_REVERB_MZ: UUID = UUID.fromString("38a6a248-94be-4007-bbe1-337d7dc49e6d")
    val UUID_BASS_BOOST_MZ: UUID = UUID.fromString("42b10000-0010-0000-0000-0002a5d5c51b")

    const val ENV_REVERB_PARAM_PROPERTIES = 10
    const val VIRTUALIZER_3D_STRENGTH: Short = 650

    const val HEAD_RADIUS_M = 0.0875
    const val SOUND_SPEED_MPS = 343.0
    const val AZIMUTH_35_RAD = 35.0 * PI / 180.0
    const val HEAD_SHADOW_CUTOFF_HZ = 850.0
    const val DEFAULT_SAMPLE_RATE_HZ = 48000.0

    fun computeWoodworthItdSeconds(
        headRadiusM: Double = HEAD_RADIUS_M,
        soundSpeedMps: Double = SOUND_SPEED_MPS,
        azimuthRad: Double = AZIMUTH_35_RAD
    ): Double {
        return (headRadiusM / soundSpeedMps) * (sin(azimuthRad) + azimuthRad)
    }

    fun computeWoodworthDelaySamplesExact(sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ): Double {
        return computeWoodworthItdSeconds() * sampleRateHz
    }

    fun computeWoodworthDelaySamples(sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ): Int {
        return computeWoodworthDelaySamplesExact(sampleRateHz).roundToInt().coerceAtLeast(1)
    }

    fun computeHeadShadowAlpha(
        cutoffHz: Double = HEAD_SHADOW_CUTOFF_HZ,
        sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ
    ): Double {
        return exp(-2.0 * PI * cutoffHz / sampleRateHz)
    }

    fun decodeMeizuPresetPayload(raw20Char: String): List<Float> {
        if (raw20Char.length < 20) return List(NUM_BANDS) { 0f }
        return List(NUM_BANDS) { k ->
            val token = raw20Char.substring(2 * k, 2 * k + 2)
            ((token.toIntOrNull() ?: 10) - 10).toFloat()
        }
    }

    val FREQ_CUTOFFS_10 = floatArrayOf(
        45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f
    )

    val FREQ_CENTERS_10 = doubleArrayOf(
        31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    val FREQ_LABELS_10 = listOf(
        "31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
    )

    fun q4RawToDb(xmlRaw: Int): Float = xmlRaw / 16.0f

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

    val SOURCE_20_TO_ISO_10_GROUPS: Array<IntArray> = arrayOf(
        intArrayOf(0),
        intArrayOf(0, 1),
        intArrayOf(1, 2),
        intArrayOf(2, 3),
        intArrayOf(4, 5),
        intArrayOf(6, 7, 8),
        intArrayOf(9, 10),
        intArrayOf(11, 12, 13),
        intArrayOf(14, 15, 16),
        intArrayOf(17, 18, 19)
    )

    fun decimate20BandRawTo10BandIso(raw20: IntArray): FloatArray {
        if (raw20.size < 20) return FloatArray(NUM_BANDS)
        val gRefDb = q4RawToDb(raw20[7]).toDouble()
        return FloatArray(NUM_BANDS) { k ->
            val indices = SOURCE_20_TO_ISO_10_GROUPS[k]
            var powerSum = 0.0
            for (j in indices) {
                val db = q4RawToDb(raw20[j]).toDouble()
                powerSum += 10.0.pow(db / 10.0)
            }
            val meanPowerDb = 10.0 * log10((powerSum / indices.size).coerceAtLeast(1e-12))
            (meanPowerDb - gRefDb).toFloat()
        }
    }

    val IEQ_OFF_10 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val IEQ_DETAILED_10 = floatArrayOf(-6.50f, -4.20f, -1.44f, 0.75f, 3.63f, 0.00f, -0.44f, -2.19f, -6.50f, -8.50f)
    val IEQ_BALANCED_10 = floatArrayOf(-2.19f, -1.56f, 1.63f, 1.63f, 0.19f, 0.00f, 1.63f, -2.00f, -5.94f, -9.50f)
    val IEQ_WARM_10 = floatArrayOf(-6.06f, -4.88f, -1.56f, -2.25f, -1.19f, 1.50f, -0.50f, -4.19f, -8.50f, -11.00f)
    val IEQ_CUSTOM_DEFAULT_10 = floatArrayOf(2.5f, 2.0f, 1.0f, 0.0f, -0.5f, 0.5f, 1.5f, 2.0f, 1.5f, 1.0f)

    val PROFILE_OFF_10 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val PROFILE_DYNAMIC_10 = floatArrayOf(4.0f, 1.0f, -2.0f, -0.25f, 0.0f, -2.0f, 0.0f, -2.0f, 0.5f, 4.0f)
    val PROFILE_BALANCED_10 = floatArrayOf(0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -3.0f, -0.5f, 0.0f, 0.0f, 0.0f)
    val PROFILE_3D_10 = floatArrayOf(1.5f, 1.2f, 0.5f, -0.5f, -1.0f, 0.0f, 1.8f, 2.5f, 3.0f, 2.5f)
    val PROFILE_LIVE_MUSIC_10 = floatArrayOf(2.0f, 2.5f, 2.0f, 1.0f, -1.0f, 0.5f, 2.0f, 2.5f, 2.0f, 1.5f)

    val DEFAULT_LIVE_MUSIC_REVERB = LiveMusicReverbProperties()

    const val BASS_MAX_31_DB = 9.0f
    const val BASS_MAX_62_DB = 8.0f
    const val BASS_MAX_125_DB = 6.0f
    const val BASS_MAX_250_DB = 4.0f

    const val BASS_ENHANCER_BOOST_RAW = 119
    const val BASS_ENHANCER_BOOST_DB = 119f / 16f
    const val BASS_ENHANCER_CUTOFF_HZ = 504f
    const val BASS_EXTRACTION_CUTOFF_HZ = 200f
    const val VIRTUAL_BASS_H2_DB = -2.0f
    const val VIRTUAL_BASS_H3_DB = -9.0f
    const val VIRTUAL_BASS_H4_DB = -12.0f

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

    fun profileCurve10For(profile: AudioProfile): FloatArray {
        return when (profile) {
            AudioProfile.OFF -> PROFILE_OFF_10
            AudioProfile.DYNAMIC -> PROFILE_DYNAMIC_10
            AudioProfile.BALANCED -> PROFILE_BALANCED_10
            AudioProfile.EFFECT_3D -> PROFILE_3D_10
            AudioProfile.LIVE_MUSIC -> PROFILE_LIVE_MUSIC_10
        }
    }

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

    fun computeComposite10BandDeltaDb(
        config: EnhancerConfig,
        userCustom10Db: DoubleArray? = null
    ): DoubleArray {
        val ieqStrength = config.ieqAmount.coerceIn(0, 16) / 16.0f
        val deFactor = if (config.dialogueEnhancerEnabled) {
            config.dialogueAmount.coerceIn(0, 16) / 16.0f
        } else {
            0.0f
        }

        val baseIeq = ieqCurve10For(config.ieqPreset, config.ieqCustom10Db, userCustom10Db)
        val prof = profileCurve10For(config.profile)

        return DoubleArray(NUM_BANDS) { i ->
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

            var postGainDb = prof[i]
            if (deFactor > 0f) {
                when (i) {
                    5 -> postGainDb += 4.00f * deFactor
                    6 -> postGainDb += 6.25f * deFactor
                    7 -> postGainDb += 4.50f * deFactor
                }
            }

            (preGainDb + postGainDb).toDouble()
        }
    }

    fun computeStage1PreEq10Db(
        config: EnhancerConfig,
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

    fun computeStage3PostEq10Db(
        config: EnhancerConfig,
        userGraphicEq10Db: DoubleArray,
        intensity: Double = 1.0
    ): FloatArray {
        val deFactor = if (config.dialogueEnhancerEnabled) {
            config.dialogueAmount.coerceIn(0, 16) / 16.0f
        } else {
            0.0f
        }
        val prof = profileCurve10For(config.profile)
        return FloatArray(NUM_BANDS) { i ->
            var postGainDb = prof[i] + (userGraphicEq10Db.getOrElse(i) { 0.0 } * intensity).toFloat()
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

    fun computeSafeHeadroomDb(maxPositiveGainDb: Float): Float = 0.0f

    fun defaultConfigForDaxProfile(profile: DaxProfile, currentProfile: AudioProfile = AudioProfile.OFF): EnhancerConfig {
        return when (profile) {
            DaxProfile.DYNAMIC -> EnhancerConfig(
                ieqPreset = IeqPreset.BALANCED,
                ieqAmount = 14,
                dialogueEnhancerEnabled = true,
                dialogueAmount = 5,
                dialogueDucking = 2,
                bassEnhancerEnabled = false,
                bassEnhancerStrength = 80,
                virtualBassEnabled = false,
                profile = currentProfile
            )
            DaxProfile.MOVIE -> EnhancerConfig(
                ieqPreset = IeqPreset.BALANCED,
                ieqAmount = 14,
                dialogueEnhancerEnabled = true,
                dialogueAmount = 5,
                dialogueDucking = 2,
                bassEnhancerEnabled = true,
                bassEnhancerStrength = 85,
                virtualBassEnabled = true,
                profile = currentProfile
            )
            DaxProfile.MUSIC -> EnhancerConfig(
                ieqPreset = IeqPreset.OFF,
                ieqAmount = 14,
                dialogueEnhancerEnabled = false,
                dialogueAmount = 0,
                dialogueDucking = 2,
                bassEnhancerEnabled = true,
                bassEnhancerStrength = 80,
                virtualBassEnabled = true,
                profile = currentProfile
            )
            DaxProfile.VOICE -> EnhancerConfig(
                ieqPreset = IeqPreset.CUSTOM,
                ieqAmount = 6,
                dialogueEnhancerEnabled = false,
                dialogueAmount = 0,
                dialogueDucking = 2,
                bassEnhancerEnabled = false,
                bassEnhancerStrength = 80,
                virtualBassEnabled = false,
                profile = currentProfile
            )
        }
    }
}
