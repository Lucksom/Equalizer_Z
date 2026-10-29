package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.model.EngineMode
import com.example.model.EqConstants
import com.example.model.PresetConfig
import com.example.model.SectionWidths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.eqDataStore by preferencesDataStore(name = "audio_eq_preferences")

data class PersistedEqPreferences(
    val isEnabled: Boolean = true,
    val selectedModeId: String = EngineMode.LEGACY.id,
    val selectedPresetId: String = "percussion_clarity",
    val gainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val highPassHz: Float = 40f,
    val intensity: Float = 1.0f,
    val volumeContourEnabled: Boolean = true,
    val selectedHeadphoneProfileId: String = "none",
    val headphoneStrength: Float = 0.8f,
    val speakerCalEnabled: Boolean = false,
    val speakerCalStrength: Float = 0.7f,
    val speakerMeas31Csv: String = "",
    val speakerValid31Csv: String = "",
    val compatibility10Band: Boolean = false,
    val transientMode: Boolean = false,
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
        val HIGH_PASS_HZ = floatPreferencesKey("high_pass_hz")
        val INTENSITY = floatPreferencesKey("intensity")
        val VOLUME_CONTOUR = booleanPreferencesKey("volume_contour_enabled")
        val HEADPHONE_PROFILE_ID = stringPreferencesKey("headphone_profile_id")
        val HEADPHONE_STRENGTH = floatPreferencesKey("headphone_strength")
        val SPEAKER_CAL_ENABLED = booleanPreferencesKey("speaker_cal_enabled")
        val SPEAKER_CAL_STRENGTH = floatPreferencesKey("speaker_cal_strength")
        val SPEAKER_MEAS_CSV = stringPreferencesKey("speaker_meas_31_csv")
        val SPEAKER_VALID_CSV = stringPreferencesKey("speaker_valid_31_csv")
        val COMPAT_10_BAND = booleanPreferencesKey("compatibility_10_band")
        val TRANSIENT_MODE = booleanPreferencesKey("transient_mode")
        val ROOT_WARNING_ACCEPTED = booleanPreferencesKey("root_warning_accepted")
    }

    val preferencesFlow: Flow<PersistedEqPreferences> = context.eqDataStore.data.map { prefs ->
        val defaultPreset = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        val gains = prefs[Keys.GAINS_CSV]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }
            ?: defaultPreset.gainsDb

        PersistedEqPreferences(
            isEnabled = prefs[Keys.IS_ENABLED] ?: true,
            selectedModeId = prefs[Keys.MODE_ID] ?: EngineMode.LEGACY.id,
            selectedPresetId = prefs[Keys.PRESET_ID] ?: defaultPreset.id,
            gainsDb = gains,
            highPassHz = prefs[Keys.HIGH_PASS_HZ] ?: defaultPreset.highPassHz,
            intensity = (prefs[Keys.INTENSITY] ?: 1.0f).coerceIn(0.5f, 1.5f),
            volumeContourEnabled = prefs[Keys.VOLUME_CONTOUR] ?: true,
            selectedHeadphoneProfileId = prefs[Keys.HEADPHONE_PROFILE_ID] ?: "none",
            headphoneStrength = (prefs[Keys.HEADPHONE_STRENGTH] ?: 0.8f).coerceIn(0.0f, 1.0f),
            speakerCalEnabled = prefs[Keys.SPEAKER_CAL_ENABLED] ?: false,
            speakerCalStrength = (prefs[Keys.SPEAKER_CAL_STRENGTH] ?: 0.7f).coerceIn(0.0f, 1.0f),
            speakerMeas31Csv = prefs[Keys.SPEAKER_MEAS_CSV] ?: "",
            speakerValid31Csv = prefs[Keys.SPEAKER_VALID_CSV] ?: "",
            compatibility10Band = prefs[Keys.COMPAT_10_BAND] ?: false,
            transientMode = prefs[Keys.TRANSIENT_MODE] ?: false,
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
        highPassHz: Float,
        intensity: Float,
        volumeContourEnabled: Boolean,
        headphoneProfileId: String,
        headphoneStrength: Float,
        speakerCalEnabled: Boolean,
        speakerCalStrength: Float,
        compatibility10Band: Boolean,
        transientMode: Boolean
    ) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.IS_ENABLED] = isEnabled
            prefs[Keys.MODE_ID] = modeId
            prefs[Keys.PRESET_ID] = presetId
            prefs[Keys.GAINS_CSV] = gainsDb.joinToString(",")
            prefs[Keys.HIGH_PASS_HZ] = highPassHz
            prefs[Keys.INTENSITY] = intensity
            prefs[Keys.VOLUME_CONTOUR] = volumeContourEnabled
            prefs[Keys.HEADPHONE_PROFILE_ID] = headphoneProfileId
            prefs[Keys.HEADPHONE_STRENGTH] = headphoneStrength
            prefs[Keys.SPEAKER_CAL_ENABLED] = speakerCalEnabled
            prefs[Keys.SPEAKER_CAL_STRENGTH] = speakerCalStrength
            prefs[Keys.COMPAT_10_BAND] = compatibility10Band
            prefs[Keys.TRANSIENT_MODE] = transientMode
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
            prefs[Keys.SPEAKER_MEAS_CSV] = measDb.joinToString(",") { "%.2f".format(java.util.Locale.US, it) }
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
}
