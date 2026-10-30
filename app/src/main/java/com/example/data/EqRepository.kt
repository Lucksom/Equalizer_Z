package com.example.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.model.DeviceProfileMemory
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.PersonalHearingCurve
import com.example.model.PresetConfig
import com.example.model.SectionWidths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.eqDataStore by preferencesDataStore(name = "audio_eq_preferences")

data class PersistedEqPreferences(
    val isEnabled: Boolean = true,
    val selectedModeId: String = EngineMode.LEGACY.id,
    val selectedPresetId: String = "percussion_clarity",
    val gainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val rightGainsDb: List<Float>? = null, // Independent Right channel 10-band EQ when splitLREnabled is true (F4)
    val splitLREnabled: Boolean = false,
    val balanceDb: Float = 0.0f, // -6..+6 dB (F4)
    val highPassHz: Float = 40f,
    val intensity: Float = 1.0f,
    val volumeContourEnabled: Boolean = true,
    val nightModeEnabled: Boolean = false, // F8
    val startOnBoot: Boolean = false, // F12
    val usbBitPerfectEnabled: Boolean = false, // F7
    val selectedHeadphoneProfileId: String = "none",
    val headphoneStrength: Float = 0.8f,
    val headphoneFullRange: Boolean = false, // Section 16.4 Full range (+10 dB boost limit)
    val tiltBassDb: Float = 0.0f, // Section 16.4 & F3 (-6..+6 dB)
    val tiltTrebleDb: Float = 0.0f, // Section 16.4 & F3 (-4..+4 dB)
    val customAutoEqName: String = "",
    val customAutoEqSource: String = "",
    val customAutoEqPath: String = "",
    val customAutoEqRawText: String = "",
    val speakerCalEnabled: Boolean = false,
    val speakerCalStrength: Float = 0.7f,
    val speakerMeas31Csv: String = "",
    val speakerValid31Csv: String = "",
    val hearingCurve: PersonalHearingCurve = PersonalHearingCurve(), // F5
    val deviceMemories: Map<String, DeviceProfileMemory> = emptyMap(), // F2
    val compatibility10Band: Boolean = false,
    val transientMode: Boolean = false,
    val enhancerEnabledFlags: List<Boolean> = listOf(false, false, false, false),
    val enhancerSliderPcts: List<Float> = listOf(0f, 0f, 0f, 0f),
    val tryDolbyControl: Boolean = false,
    val dolbyDeepBass: Boolean = false,
    val rootWarningAccepted: Boolean = false
)

class EqRepository(private val context: Context) {

    private val database: EqDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            EqDatabase::class.java,
            "audio_eq_db"
        ).fallbackToDestructiveMigration(true).build()
    }

    private val dao: CustomPresetDao by lazy { database.customPresetDao() }

    private object Keys {
        val IS_ENABLED = booleanPreferencesKey("is_enabled")
        val MODE_ID = stringPreferencesKey("mode_id")
        val PRESET_ID = stringPreferencesKey("preset_id")
        val GAINS_CSV = stringPreferencesKey("gains_csv")
        val RIGHT_GAINS_CSV = stringPreferencesKey("right_gains_csv")
        val SPLIT_LR_ENABLED = booleanPreferencesKey("split_lr_enabled")
        val BALANCE_DB = floatPreferencesKey("balance_db")
        val HIGH_PASS_HZ = floatPreferencesKey("high_pass_hz")
        val INTENSITY = floatPreferencesKey("intensity")
        val VOLUME_CONTOUR = booleanPreferencesKey("volume_contour_enabled")
        val NIGHT_MODE = booleanPreferencesKey("night_mode_enabled")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val USB_BIT_PERFECT = booleanPreferencesKey("usb_bit_perfect_enabled")
        val HEADPHONE_PROFILE_ID = stringPreferencesKey("headphone_profile_id")
        val HEADPHONE_STRENGTH = floatPreferencesKey("headphone_strength")
        val HEADPHONE_FULL_RANGE = booleanPreferencesKey("headphone_full_range")
        val TILT_BASS_DB = floatPreferencesKey("tilt_bass_db")
        val TILT_TREBLE_DB = floatPreferencesKey("tilt_treble_db")
        val CUSTOM_AUTOEQ_NAME = stringPreferencesKey("custom_autoeq_name")
        val CUSTOM_AUTOEQ_SOURCE = stringPreferencesKey("custom_autoeq_source")
        val CUSTOM_AUTOEQ_PATH = stringPreferencesKey("custom_autoeq_path")
        val CUSTOM_AUTOEQ_RAW = stringPreferencesKey("custom_autoeq_raw")
        val SPEAKER_CAL_ENABLED = booleanPreferencesKey("speaker_cal_enabled")
        val SPEAKER_CAL_STRENGTH = floatPreferencesKey("speaker_cal_strength")
        val SPEAKER_MEAS_CSV = stringPreferencesKey("speaker_meas_31_csv")
        val SPEAKER_VALID_CSV = stringPreferencesKey("speaker_valid_31_csv")
        val HEARING_ENABLED = booleanPreferencesKey("hearing_curve_enabled")
        val HEARING_LEFT_CSV = stringPreferencesKey("hearing_left_csv")
        val HEARING_RIGHT_CSV = stringPreferencesKey("hearing_right_csv")
        val HEARING_STRENGTH = floatPreferencesKey("hearing_strength")
        val DEVICE_MEMORIES_RAW = stringPreferencesKey("device_memories_raw")
        val COMPAT_10_BAND = booleanPreferencesKey("compatibility_10_band")
        val TRANSIENT_MODE = booleanPreferencesKey("transient_mode")
        val ENHANCER_ENABLED_CSV = stringPreferencesKey("enhancer_enabled_csv")
        val ENHANCER_PCT_CSV = stringPreferencesKey("enhancer_pct_csv")
        val TRY_DOLBY_CONTROL = booleanPreferencesKey("try_dolby_control")
        val DOLBY_DEEP_BASS = booleanPreferencesKey("dolby_deep_bass")
        val ROOT_WARNING_ACCEPTED = booleanPreferencesKey("root_warning_accepted")
    }

    val preferencesFlow: Flow<PersistedEqPreferences> = context.eqDataStore.data.map { prefs ->
        val defaultPreset = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        val gains = prefs[Keys.GAINS_CSV]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }
            ?: defaultPreset.gainsDb

        val splitLR = prefs[Keys.SPLIT_LR_ENABLED] ?: false
        val rightGains = prefs[Keys.RIGHT_GAINS_CSV]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }

        val hearingLeft = prefs[Keys.HEARING_LEFT_CSV]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 6 }
            ?: List(6) { 0f }
        val hearingRight = prefs[Keys.HEARING_RIGHT_CSV]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 6 }
            ?: List(6) { 0f }

        val deviceMap = decodeDeviceMemories(prefs[Keys.DEVICE_MEMORIES_RAW] ?: "")
        val enhEnabled = prefs[Keys.ENHANCER_ENABLED_CSV]
            ?.split(",")
            ?.map { it == "1" }
            ?.takeIf { it.size == 4 }
            ?: listOf(false, false, false, false)
        val enhPcts = prefs[Keys.ENHANCER_PCT_CSV]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull()?.coerceIn(0f, 100f) }
            ?.takeIf { it.size == 4 }
            ?: listOf(0f, 0f, 0f, 0f)

        PersistedEqPreferences(
            isEnabled = prefs[Keys.IS_ENABLED] ?: true,
            selectedModeId = prefs[Keys.MODE_ID] ?: EngineMode.LEGACY.id,
            selectedPresetId = prefs[Keys.PRESET_ID] ?: defaultPreset.id,
            gainsDb = gains,
            rightGainsDb = rightGains,
            splitLREnabled = splitLR,
            balanceDb = (prefs[Keys.BALANCE_DB] ?: 0.0f).coerceIn(-6.0f, 6.0f),
            highPassHz = prefs[Keys.HIGH_PASS_HZ] ?: defaultPreset.highPassHz,
            intensity = (prefs[Keys.INTENSITY] ?: 1.0f).coerceIn(0.5f, 1.5f),
            volumeContourEnabled = prefs[Keys.VOLUME_CONTOUR] ?: true,
            nightModeEnabled = prefs[Keys.NIGHT_MODE] ?: false,
            startOnBoot = prefs[Keys.START_ON_BOOT] ?: false,
            usbBitPerfectEnabled = prefs[Keys.USB_BIT_PERFECT] ?: false,
            selectedHeadphoneProfileId = prefs[Keys.HEADPHONE_PROFILE_ID] ?: "none",
            headphoneStrength = (prefs[Keys.HEADPHONE_STRENGTH] ?: 0.8f).coerceIn(0.0f, 1.0f),
            headphoneFullRange = prefs[Keys.HEADPHONE_FULL_RANGE] ?: false,
            tiltBassDb = (prefs[Keys.TILT_BASS_DB] ?: 0.0f).coerceIn(-6.0f, 6.0f),
            tiltTrebleDb = (prefs[Keys.TILT_TREBLE_DB] ?: 0.0f).coerceIn(-4.0f, 4.0f),
            customAutoEqName = prefs[Keys.CUSTOM_AUTOEQ_NAME] ?: "",
            customAutoEqSource = prefs[Keys.CUSTOM_AUTOEQ_SOURCE] ?: "",
            customAutoEqPath = prefs[Keys.CUSTOM_AUTOEQ_PATH] ?: "",
            customAutoEqRawText = prefs[Keys.CUSTOM_AUTOEQ_RAW] ?: "",
            speakerCalEnabled = prefs[Keys.SPEAKER_CAL_ENABLED] ?: false,
            speakerCalStrength = (prefs[Keys.SPEAKER_CAL_STRENGTH] ?: 0.7f).coerceIn(0.0f, 1.0f),
            speakerMeas31Csv = prefs[Keys.SPEAKER_MEAS_CSV] ?: "",
            speakerValid31Csv = prefs[Keys.SPEAKER_VALID_CSV] ?: "",
            hearingCurve = PersonalHearingCurve(
                enabled = prefs[Keys.HEARING_ENABLED] ?: false,
                leftEarDb = hearingLeft,
                rightEarDb = hearingRight,
                strength = (prefs[Keys.HEARING_STRENGTH] ?: 0.5f).coerceIn(0.0f, 1.0f)
            ),
            deviceMemories = deviceMap,
            compatibility10Band = prefs[Keys.COMPAT_10_BAND] ?: false,
            transientMode = prefs[Keys.TRANSIENT_MODE] ?: false,
            enhancerEnabledFlags = enhEnabled,
            enhancerSliderPcts = enhPcts,
            tryDolbyControl = prefs[Keys.TRY_DOLBY_CONTROL] ?: false,
            dolbyDeepBass = prefs[Keys.DOLBY_DEEP_BASS] ?: false,
            rootWarningAccepted = prefs[Keys.ROOT_WARNING_ACCEPTED] ?: false
        )
    }

    val customPresetsFlow: Flow<List<PresetConfig>> = dao.getAllCustomPresets().map { entities ->
        entities.map { entity ->
            val gains = entity.gainsCsv.split(",")
                .mapNotNull { it.toFloatOrNull() }
                .let { if (it.size == 10) it else List(10) { 0f } }
            val maxBoost = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
            PresetConfig(
                id = "user_${entity.id}",
                name = entity.name,
                description = "Saved user preset · HPF ${entity.highPassHz.toInt()} Hz",
                gainsDb = gains,
                largestBoostDb = maxBoost,
                highPassHz = entity.highPassHz,
                bassHarmonicMixDb = if (maxBoost >= 2f) -12f else null,
                widths = SectionWidths(),
                legacyNativeDb = EqConstants.convert10BandTo5BandNative(gains),
                bassBoostStrength = entity.bassBoostStrength,
                virtualizerStrength = entity.virtualizerStrength,
                isBuiltIn = false
            )
        }
    }

    suspend fun saveState(
        isEnabled: Boolean,
        modeId: String,
        presetId: String,
        gainsDb: List<Float>,
        rightGainsDb: List<Float>?,
        splitLREnabled: Boolean,
        balanceDb: Float,
        highPassHz: Float,
        intensity: Float,
        volumeContourEnabled: Boolean,
        nightModeEnabled: Boolean,
        startOnBoot: Boolean,
        usbBitPerfectEnabled: Boolean,
        headphoneProfileId: String,
        headphoneStrength: Float,
        headphoneFullRange: Boolean,
        tiltBassDb: Float,
        tiltTrebleDb: Float,
        customAutoEqName: String,
        customAutoEqSource: String,
        customAutoEqPath: String,
        customAutoEqRawText: String,
        speakerCalEnabled: Boolean,
        speakerCalStrength: Float,
        compatibility10Band: Boolean,
        transientMode: Boolean,
        enhancerEnabledFlags: List<Boolean> = listOf(false, false, false, false),
        enhancerSliderPcts: List<Float> = listOf(0f, 0f, 0f, 0f),
        tryDolbyControl: Boolean = false,
        dolbyDeepBass: Boolean = false
    ) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.IS_ENABLED] = isEnabled
            prefs[Keys.MODE_ID] = modeId
            prefs[Keys.PRESET_ID] = presetId
            prefs[Keys.GAINS_CSV] = gainsDb.joinToString(",")
            if (rightGainsDb != null) {
                prefs[Keys.RIGHT_GAINS_CSV] = rightGainsDb.joinToString(",")
            }
            prefs[Keys.SPLIT_LR_ENABLED] = splitLREnabled
            prefs[Keys.BALANCE_DB] = balanceDb
            prefs[Keys.HIGH_PASS_HZ] = highPassHz
            prefs[Keys.INTENSITY] = intensity
            prefs[Keys.VOLUME_CONTOUR] = volumeContourEnabled
            prefs[Keys.NIGHT_MODE] = nightModeEnabled
            prefs[Keys.START_ON_BOOT] = startOnBoot
            prefs[Keys.USB_BIT_PERFECT] = usbBitPerfectEnabled
            prefs[Keys.HEADPHONE_PROFILE_ID] = headphoneProfileId
            prefs[Keys.HEADPHONE_STRENGTH] = headphoneStrength
            prefs[Keys.HEADPHONE_FULL_RANGE] = headphoneFullRange
            prefs[Keys.TILT_BASS_DB] = tiltBassDb
            prefs[Keys.TILT_TREBLE_DB] = tiltTrebleDb
            prefs[Keys.CUSTOM_AUTOEQ_NAME] = customAutoEqName
            prefs[Keys.CUSTOM_AUTOEQ_SOURCE] = customAutoEqSource
            prefs[Keys.CUSTOM_AUTOEQ_PATH] = customAutoEqPath
            prefs[Keys.CUSTOM_AUTOEQ_RAW] = customAutoEqRawText
            prefs[Keys.SPEAKER_CAL_ENABLED] = speakerCalEnabled
            prefs[Keys.SPEAKER_CAL_STRENGTH] = speakerCalStrength
            prefs[Keys.COMPAT_10_BAND] = compatibility10Band
            prefs[Keys.TRANSIENT_MODE] = transientMode
            prefs[Keys.ENHANCER_ENABLED_CSV] = enhancerEnabledFlags.joinToString(",") { if (it) "1" else "0" }
            prefs[Keys.ENHANCER_PCT_CSV] = enhancerSliderPcts.joinToString(",") { "%.1f".format(Locale.US, it) }
            prefs[Keys.TRY_DOLBY_CONTROL] = tryDolbyControl
            prefs[Keys.DOLBY_DEEP_BASS] = dolbyDeepBass
        }
    }

    suspend fun saveHearingCurve(curve: PersonalHearingCurve) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.HEARING_ENABLED] = curve.enabled
            prefs[Keys.HEARING_LEFT_CSV] = curve.leftEarDb.joinToString(",") { "%.2f".format(Locale.US, it) }
            prefs[Keys.HEARING_RIGHT_CSV] = curve.rightEarDb.joinToString(",") { "%.2f".format(Locale.US, it) }
            prefs[Keys.HEARING_STRENGTH] = curve.strength
        }
    }

    suspend fun saveDeviceMemory(memory: DeviceProfileMemory) {
        context.eqDataStore.edit { prefs ->
            val current = decodeDeviceMemories(prefs[Keys.DEVICE_MEMORIES_RAW] ?: "").toMutableMap()
            current[memory.deviceKey] = memory
            prefs[Keys.DEVICE_MEMORIES_RAW] = encodeDeviceMemories(current)
        }
    }

    suspend fun saveSpeakerCalibrationData(
        enabled: Boolean,
        measDb: DoubleArray,
        valid: BooleanArray,
        strength: Float
    ) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.SPEAKER_CAL_ENABLED] = enabled
            prefs[Keys.SPEAKER_CAL_STRENGTH] = strength
            prefs[Keys.SPEAKER_MEAS_CSV] = measDb.joinToString(",") { "%.2f".format(Locale.US, it) }
            prefs[Keys.SPEAKER_VALID_CSV] = valid.joinToString(",") { if (it) "1" else "0" }
        }
    }

    suspend fun setRootWarningAccepted(accepted: Boolean) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.ROOT_WARNING_ACCEPTED] = accepted
        }
    }

    suspend fun saveCustomPreset(
        name: String,
        gainsDb: List<Float>,
        preampDb: Float,
        highPassHz: Float,
        widths: SectionWidths,
        bassBoostStrength: Int,
        virtualizerStrength: Int
    ) {
        dao.insertPreset(
            CustomPresetEntity(
                name = name.trim().ifEmpty { "My Custom EQ" },
                gainsCsv = gainsDb.joinToString(","),
                preampDb = preampDb,
                highPassHz = highPassHz,
                bassWidthPct = widths.bassWidthPct,
                midsWidthPct = widths.midsWidthPct,
                trebleWidthPct = widths.trebleWidthPct,
                airWidthPct = widths.airWidthPct,
                bassBoostStrength = bassBoostStrength,
                virtualizerStrength = virtualizerStrength
            )
        )
    }

    suspend fun deleteCustomPreset(presetId: String) {
        val numericId = presetId.removePrefix("user_").toIntOrNull() ?: return
        dao.deletePresetById(numericId)
    }

    private fun encodeB64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeB64(s: String): String =
        try { String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8) } catch (_: Throwable) { "" }

    private fun encodeDeviceMemories(map: Map<String, DeviceProfileMemory>): String {
        return map.values.joinToString("\n") { m ->
            listOf(
                encodeB64(m.deviceKey),
                encodeB64(m.routeId),
                encodeB64(m.productName),
                encodeB64(m.profileId),
                encodeB64(m.profileName),
                encodeB64(m.profileSource),
                encodeB64(m.autoEqPath),
                encodeB64(m.rawPeqOrGraphicText),
                m.strength.toString(),
                if (m.fullRange) "1" else "0",
                encodeB64(m.presetId),
                m.intensity.toString(),
                m.tiltBassDb.toString(),
                m.tiltTrebleDb.toString()
            ).joinToString("|")
        }
    }

    private fun decodeDeviceMemories(raw: String): Map<String, DeviceProfileMemory> {
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<String, DeviceProfileMemory>()
        for (line in raw.lines()) {
            val p = line.split("|")
            if (p.size < 14) continue
            val key = decodeB64(p[0])
            if (key.isBlank()) continue
            out[key] = DeviceProfileMemory(
                deviceKey = key,
                routeId = decodeB64(p[1]),
                productName = decodeB64(p[2]),
                profileId = decodeB64(p[3]),
                profileName = decodeB64(p[4]),
                profileSource = decodeB64(p[5]),
                autoEqPath = decodeB64(p[6]),
                rawPeqOrGraphicText = decodeB64(p[7]),
                strength = p[8].toFloatOrNull() ?: 0.8f,
                fullRange = p[9] == "1",
                presetId = decodeB64(p[10]).ifEmpty { "percussion_clarity" },
                intensity = p[11].toFloatOrNull() ?: 1.0f,
                tiltBassDb = p[12].toFloatOrNull() ?: 0.0f,
                tiltTrebleDb = p[13].toFloatOrNull() ?: 0.0f
            )
        }
        return out
    }
}
