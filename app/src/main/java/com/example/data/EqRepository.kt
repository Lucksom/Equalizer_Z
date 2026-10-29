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
    val selectedModeId: String = EngineMode.GLOBAL.id,
    val selectedPresetId: String = "percussion_clarity",
    val gainsDb: List<Float> = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }.gainsDb,
    val preampDb: Float = -2f,
    val highPassHz: Float = 40f,
    val widths: SectionWidths = SectionWidths(10, 100, 115, 125),
    val spatialStrength: Int = 200,
    val bassBoostStrength: Int = 100,
    val alwaysShowSectionWidths: Boolean = true,
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
        val PREAMP_DB = floatPreferencesKey("preamp_db")
        val HIGH_PASS_HZ = floatPreferencesKey("high_pass_hz")
        val WIDTH_BASS = intPreferencesKey("width_bass")
        val WIDTH_MIDS = intPreferencesKey("width_mids")
        val WIDTH_TREBLE = intPreferencesKey("width_treble")
        val WIDTH_AIR = intPreferencesKey("width_air")
        val SPATIAL_STRENGTH = intPreferencesKey("spatial_strength")
        val BASS_BOOST_STRENGTH = intPreferencesKey("bass_boost_strength")
        val ALWAYS_SHOW_SECTION_WIDTHS = booleanPreferencesKey("always_show_section_widths")
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
            selectedModeId = prefs[Keys.MODE_ID] ?: EngineMode.GLOBAL.id,
            selectedPresetId = prefs[Keys.PRESET_ID] ?: defaultPreset.id,
            gainsDb = gains,
            preampDb = prefs[Keys.PREAMP_DB] ?: defaultPreset.preampDb,
            highPassHz = prefs[Keys.HIGH_PASS_HZ] ?: defaultPreset.highPassHz,
            widths = SectionWidths(
                bassWidthPct = prefs[Keys.WIDTH_BASS] ?: defaultPreset.widths.bassWidthPct,
                midsWidthPct = prefs[Keys.WIDTH_MIDS] ?: defaultPreset.widths.midsWidthPct,
                trebleWidthPct = prefs[Keys.WIDTH_TREBLE] ?: defaultPreset.widths.trebleWidthPct,
                airWidthPct = prefs[Keys.WIDTH_AIR] ?: defaultPreset.widths.airWidthPct
            ),
            spatialStrength = prefs[Keys.SPATIAL_STRENGTH] ?: defaultPreset.virtualizerStrength,
            bassBoostStrength = prefs[Keys.BASS_BOOST_STRENGTH] ?: defaultPreset.bassBoostStrength,
            alwaysShowSectionWidths = prefs[Keys.ALWAYS_SHOW_SECTION_WIDTHS] ?: true,
            rootWarningAccepted = prefs[Keys.ROOT_WARNING_ACCEPTED] ?: false
        )
    }

    val customPresetsFlow: Flow<List<PresetConfig>> = dao.getAllCustomPresets().map { entities ->
        entities.map { entity ->
            val gains = entity.gainsCsv.split(",")
                .mapNotNull { it.toFloatOrNull() }
                .let { if (it.size == 10) it else List(10) { 0f } }
            PresetConfig(
                id = "user_${entity.id}",
                name = entity.name,
                description = "Saved user preset · Preamp ${entity.preampDb} dB · HPF ${entity.highPassHz.toInt()} Hz",
                gainsDb = gains,
                preampDb = entity.preampDb,
                highPassHz = entity.highPassHz,
                widths = SectionWidths(
                    bassWidthPct = entity.bassWidthPct,
                    midsWidthPct = entity.midsWidthPct,
                    trebleWidthPct = entity.trebleWidthPct,
                    airWidthPct = entity.airWidthPct
                ),
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
        preampDb: Float,
        highPassHz: Float,
        widths: SectionWidths,
        spatialStrength: Int,
        bassBoostStrength: Int
    ) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.IS_ENABLED] = isEnabled
            prefs[Keys.MODE_ID] = modeId
            prefs[Keys.PRESET_ID] = presetId
            prefs[Keys.GAINS_CSV] = gainsDb.joinToString(",")
            prefs[Keys.PREAMP_DB] = preampDb
            prefs[Keys.HIGH_PASS_HZ] = highPassHz
            prefs[Keys.WIDTH_BASS] = widths.bassWidthPct
            prefs[Keys.WIDTH_MIDS] = widths.midsWidthPct
            prefs[Keys.WIDTH_TREBLE] = widths.trebleWidthPct
            prefs[Keys.WIDTH_AIR] = widths.airWidthPct
            prefs[Keys.SPATIAL_STRENGTH] = spatialStrength
            prefs[Keys.BASS_BOOST_STRENGTH] = bassBoostStrength
        }
    }

    suspend fun setAlwaysShowSectionWidths(alwaysShow: Boolean) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.ALWAYS_SHOW_SECTION_WIDTHS] = alwaysShow
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
