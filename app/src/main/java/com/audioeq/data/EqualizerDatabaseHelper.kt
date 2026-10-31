package com.audioeq.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.audioeq.audio.DaxProfile
import com.audioeq.audio.EqMath
import com.audioeq.audio.PeqFilter
import com.audioeq.model.DeviceProfileMemory
import com.audioeq.model.EqConstants
import com.audioeq.model.PersonalHearingCurve
import com.audioeq.model.PresetConfig
import com.audioeq.model.SectionWidths
import java.util.Locale

data class DaxProfileRow(
    val profileId: Int,
    val name: String,
    val ieqTargetId: String?,
    val ieqAmountSpk: Double,
    val ieqAmountHp: Double,
    val dialogStepsSpk: Int,
    val dialogStepsHp: Int,
    val levelerEnabled: Boolean,
    val virtualBassSpk: Boolean,
    val virtualizerStrength: Int
)

data class CachedAutoEqRow(
    val modelId: String,
    val modelName: String,
    val sourceName: String,
    val rigName: String,
    val preampDb: Float,
    val filtersJson: String,
    val correction31Csv: String,
    val fetchedAtMs: Long,
    val etag: String?
)

class EqualizerDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "equalizer.db"
        const val DATABASE_VERSION = 6

        @Volatile
        private var instance: EqualizerDatabaseHelper? = null

        fun getInstance(context: Context): EqualizerDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: EqualizerDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Musical EQ Presets (Built-in + User Custom)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS eq_presets (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                is_builtin INTEGER NOT NULL DEFAULT 0,
                bypass_chain INTEGER NOT NULL DEFAULT 0,
                gains_10_csv TEXT NOT NULL,
                dax_gains_20_csv TEXT NOT NULL,
                tier1_gains_5_csv TEXT NOT NULL,
                tier1_bass_boost INTEGER NOT NULL DEFAULT 0,
                tier1_virtualizer INTEGER NOT NULL DEFAULT 0,
                highpass_hz REAL NOT NULL DEFAULT 20.0,
                harmonic_mix_db REAL,
                mbc_profile TEXT NOT NULL DEFAULT 'default',
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 2. Reverse-Engineered Acoustic Profiles (Dynamic, Movie, Music, Voice)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dax_profiles (
                profile_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                ieq_target_id TEXT,
                ieq_amount_spk REAL NOT NULL,
                ieq_amount_hp REAL NOT NULL,
                dialog_steps_spk INTEGER NOT NULL,
                dialog_steps_hp INTEGER NOT NULL,
                leveler_enabled INTEGER NOT NULL,
                virtual_bass_spk INTEGER NOT NULL,
                virtualizer_strength INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 3. Per-Device Routing & Profile Memory (Auto-restored on Bluetooth / USB / Wired connect)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device_routing_memory (
                device_key TEXT PRIMARY KEY,
                route_type TEXT NOT NULL,
                product_name TEXT NOT NULL,
                active_preset_id TEXT NOT NULL,
                active_profile_id INTEGER NOT NULL DEFAULT 2,
                intensity REAL NOT NULL DEFAULT 1.0,
                autoeq_model_id TEXT,
                autoeq_strength REAL NOT NULL DEFAULT 0.8,
                volume_contour_on INTEGER NOT NULL DEFAULT 1,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 4. Cached AutoEq Headphone Profiles (MIT License)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS autoeq_cache (
                model_id TEXT PRIMARY KEY,
                model_name TEXT NOT NULL,
                source_name TEXT NOT NULL,
                rig_name TEXT,
                preamp_db REAL NOT NULL,
                filters_json TEXT NOT NULL,
                correction_31_csv TEXT NOT NULL,
                fetched_at_ms INTEGER NOT NULL,
                etag TEXT
            )
            """.trimIndent()
        )

        // 5. Speaker Self-Calibration & Personal Hearing Compensation Curves
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS acoustic_calibrations (
                cal_id TEXT PRIMARY KEY,
                cal_type TEXT NOT NULL,
                measured_31_csv TEXT,
                valid_mask_31_csv TEXT,
                correction_31_csv TEXT NOT NULL,
                ear_left_6_csv TEXT,
                ear_right_6_csv TEXT,
                strength REAL NOT NULL DEFAULT 0.7,
                enabled INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        seedMasterData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    private fun seedMasterData(db: SQLiteDatabase) {
        // Seed the 4 Acoustic Profiles (Section 8)
        db.execSQL(
            """
            INSERT OR REPLACE INTO dax_profiles VALUES
            (0, 'Dynamic',       'ieq_balanced', 0.375, 0.3750, 0, 0, 1, 1, 150),
            (1, 'Video / Movie', 'ieq_balanced', 0.750, 0.8125, 5, 4, 1, 1, 250),
            (2, 'Music',         NULL,           0.000, 0.0000, 0, 0, 0, 1, 0),
            (3, 'Voice',         'ieq_warm',     0.375, 0.3750, 6, 6, 1, 0, 0)
            """.trimIndent()
        )

        // Seed the 6 Musical EQ Presets (Section 8)
        db.execSQL(
            """
            INSERT OR REPLACE INTO eq_presets VALUES
            ('flat', 'Flat', 1, 1,
             '0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0',
             '0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0',
             '0.0,0.0,0.0,0.0,0.0', 0, 0, 0.0, NULL, 'default', 0),

            ('balanced_deep', 'Balanced Deep', 1, 0,
             '3.5,4.5,2.5,-2.5,-1.5,0.0,1.0,2.5,2.5,1.5',
             '56,64,72,56,40,0,-40,-32,-24,-12,0,8,16,28,40,40,40,32,24,24',
             '4.0,-1.9,-0.2,2.3,1.7', 150, 150, 20.0, -12.0, 'default', 0),

            ('bass_boost', 'Bass Boost', 1, 0,
             '5.5,6.5,4.0,-2.0,-1.5,-0.5,0.0,0.0,0.5,0.5',
             '88,96,104,84,64,16,-32,-28,-24,-16,-8,-4,0,0,0,4,8,8,8,8',
             '4.0,-1.3,-0.6,0.0,0.5', 400, 0, 25.0, -9.0, 'default', 0),

            ('vocal_clarity', 'Vocal Clarity', 1, 0,
             '-3.5,-2.5,-1.0,-3.0,-1.5,1.5,3.5,3.5,1.5,0.5',
             '-56,-48,-40,-28,-16,-32,-48,-36,-24,0,24,40,56,56,56,40,24,16,8,8',
             '-2.6,-2.8,1.1,3.5,0.7', 0, 100, 60.0, NULL, 'default', 0),

            ('percussion_clarity', 'Percussion Clarity', 1, 0,
             '-4.0,1.5,2.5,-3.5,-2.0,0.0,1.5,3.5,3.0,1.5',
             '-64,-20,24,32,40,-8,-56,-44,-32,-16,0,12,24,40,56,52,48,36,24,24',
             '1.2,-2.8,-0.3,3.2,1.8', 100, 200, 40.0, -15.0, 'percussion', 0),

            ('cinema', 'Cinema', 1, 0,
             '4.0,4.0,2.0,-2.0,-1.0,0.5,2.5,2.5,2.0,2.0',
             '64,64,64,48,32,0,-32,-24,-16,-4,8,24,40,40,40,36,32,32,32,32',
             '4.0,-1.5,0.3,2.5,2.0', 200, 250, 20.0, -12.0, 'default', 0)
            """.trimIndent()
        )

        // Seed built-in AutoEq headphone profiles into autoeq_cache so they are always available offline
        for (hp in EqConstants.HEADPHONE_PROFILES) {
            if (hp.id == "none") continue
            val peqList = hp.filters.map { f ->
                val typeStr = when (f.type.name) {
                    "LOW_SHELF" -> "LSC"
                    "HIGH_SHELF" -> "HSC"
                    else -> "PK"
                }
                PeqFilter(typeStr, f.fcHz, f.gainDb, f.q)
            }
            val c31 = EqMath.evaluateAutoEq31(peqList, strength = 0.8, fs = 44100.0)
            val filtersJson = peqList.joinToString(prefix = "[", postfix = "]") { f ->
                String.format(
                    Locale.US,
                    """{"type":"%s","fc":%.1f,"gainDb":%.2f,"q":%.2f}""",
                    f.type,
                    f.fc,
                    f.gainDb,
                    f.q
                )
            }
            val cv = ContentValues().apply {
                put("model_id", hp.id)
                put("model_name", hp.name)
                put("source_name", hp.source)
                put("rig_name", hp.rig ?: "GRAS 43AG-7")
                put("preamp_db", hp.preampDb.toDouble())
                put("filters_json", filtersJson)
                put("correction_31_csv", c31.joinToString(",") { "%.2f".format(Locale.US, it) })
                put("fetched_at_ms", 0L)
                putNull("etag")
            }
            db.insertWithOnConflict("autoeq_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun getAllDaxProfiles(): List<DaxProfileRow> {
        val out = mutableListOf<DaxProfileRow>()
        val db = readableDatabase
        db.rawQuery("SELECT * FROM dax_profiles ORDER BY profile_id ASC", null).use { c ->
            while (c.moveToNext()) {
                out += DaxProfileRow(
                    profileId = c.getInt(c.getColumnIndexOrThrow("profile_id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    ieqTargetId = c.getString(c.getColumnIndexOrThrow("ieq_target_id")),
                    ieqAmountSpk = c.getDouble(c.getColumnIndexOrThrow("ieq_amount_spk")),
                    ieqAmountHp = c.getDouble(c.getColumnIndexOrThrow("ieq_amount_hp")),
                    dialogStepsSpk = c.getInt(c.getColumnIndexOrThrow("dialog_steps_spk")),
                    dialogStepsHp = c.getInt(c.getColumnIndexOrThrow("dialog_steps_hp")),
                    levelerEnabled = c.getInt(c.getColumnIndexOrThrow("leveler_enabled")) != 0,
                    virtualBassSpk = c.getInt(c.getColumnIndexOrThrow("virtual_bass_spk")) != 0,
                    virtualizerStrength = c.getInt(c.getColumnIndexOrThrow("virtualizer_strength"))
                )
            }
        }
        return out
    }

    fun getAllPresets(): List<PresetConfig> {
        val out = mutableListOf<PresetConfig>()
        val db = readableDatabase
        db.rawQuery("SELECT * FROM eq_presets ORDER BY is_builtin DESC, created_at_ms ASC", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val isBuiltIn = c.getInt(c.getColumnIndexOrThrow("is_builtin")) != 0
                val bypassChain = c.getInt(c.getColumnIndexOrThrow("bypass_chain")) != 0
                val gains10 = c.getString(c.getColumnIndexOrThrow("gains_10_csv"))
                    .split(",")
                    .mapNotNull { it.trim().toFloatOrNull() }
                    .let { if (it.size == 10) it else List(10) { 0f } }
                val tier1Gains5 = c.getString(c.getColumnIndexOrThrow("tier1_gains_5_csv"))
                    .split(",")
                    .mapNotNull { it.trim().toFloatOrNull() }
                    .let { if (it.size == 5) it else EqConstants.convert10BandTo5BandNative(gains10) }
                val bassBoost = c.getInt(c.getColumnIndexOrThrow("tier1_bass_boost"))
                val virtualizer = c.getInt(c.getColumnIndexOrThrow("tier1_virtualizer"))
                val hpf = c.getFloat(c.getColumnIndexOrThrow("highpass_hz"))
                val harmIdx = c.getColumnIndexOrThrow("harmonic_mix_db")
                val harmMix = if (c.isNull(harmIdx)) null else c.getFloat(harmIdx)
                val mbcProf = c.getString(c.getColumnIndexOrThrow("mbc_profile"))

                val builtInMatch = EqConstants.BUILT_IN_PRESETS.find { it.id == id }
                val maxBoost = (gains10.maxOrNull() ?: 0f).coerceAtLeast(0f)

                out += PresetConfig(
                    id = id,
                    name = name,
                    description = builtInMatch?.description ?: "Saved custom preset · HPF ${hpf.toInt()} Hz",
                    gainsDb = gains10,
                    largestBoostDb = maxBoost,
                    highPassHz = hpf,
                    bassHarmonicMixDb = harmMix,
                    widths = SectionWidths(),
                    legacyNativeDb = tier1Gains5,
                    bassBoostStrength = bassBoost,
                    virtualizerStrength = virtualizer,
                    qOverrides = builtInMatch?.qOverrides ?: emptyMap(),
                    mbcOverride = if (mbcProf == "percussion") EqConstants.PERCUSSION_CLARITY_MBC_CONFIG else null,
                    bypassChain = bypassChain,
                    isBuiltIn = isBuiltIn
                )
            }
        }
        return if (out.isNotEmpty()) out else EqConstants.BUILT_IN_PRESETS
    }

    fun saveCustomPreset(
        name: String,
        gains10Db: List<Float>,
        highPassHz: Float,
        bassBoostStrength: Int,
        virtualizerStrength: Int
    ): String {
        val cleanName = name.trim().ifEmpty { "My Custom EQ" }
        val id = "user_${System.currentTimeMillis()}"
        val double10 = DoubleArray(10) { gains10Db.getOrElse(it) { 0f }.toDouble() }
        val dax20 = EqMath.sliders10ToDax20Int(double10)
        val tier1Gains = EqConstants.convert10BandTo5BandNative(gains10Db)
        val maxBoost = (gains10Db.maxOrNull() ?: 0f).coerceAtLeast(0f)

        val cv = ContentValues().apply {
            put("id", id)
            put("name", cleanName)
            put("is_builtin", 0)
            put("bypass_chain", 0)
            put("gains_10_csv", gains10Db.joinToString(",") { "%.1f".format(Locale.US, it) })
            put("dax_gains_20_csv", dax20.joinToString(","))
            put("tier1_gains_5_csv", tier1Gains.joinToString(",") { "%.1f".format(Locale.US, it) })
            put("tier1_bass_boost", bassBoostStrength)
            put("tier1_virtualizer", virtualizerStrength)
            put("highpass_hz", highPassHz.toDouble())
            if (maxBoost >= 2f) put("harmonic_mix_db", -12.0) else putNull("harmonic_mix_db")
            put("mbc_profile", "default")
            put("created_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("eq_presets", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun deleteCustomPreset(presetId: String) {
        writableDatabase.delete("eq_presets", "id = ? AND is_builtin = 0", arrayOf(presetId))
    }

    fun saveDeviceRoutingMemory(
        memory: DeviceProfileMemory,
        activeProfileId: Int = DaxProfile.MUSIC.id,
        volumeContourOn: Boolean = true
    ) {
        val cv = ContentValues().apply {
            put("device_key", memory.deviceKey)
            put("route_type", memory.routeId)
            put("product_name", memory.productName)
            put("active_preset_id", memory.presetId)
            put("active_profile_id", activeProfileId)
            put("intensity", memory.intensity.toDouble())
            put("autoeq_model_id", memory.profileId)
            put("autoeq_strength", memory.strength.toDouble())
            put("volume_contour_on", if (volumeContourOn) 1 else 0)
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("device_routing_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDeviceRoutingMemory(deviceKey: String): Pair<DeviceProfileMemory, Int>? {
        readableDatabase.rawQuery(
            "SELECT * FROM device_routing_memory WHERE device_key = ? LIMIT 1",
            arrayOf(deviceKey)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val routeType = c.getString(c.getColumnIndexOrThrow("route_type"))
            val productName = c.getString(c.getColumnIndexOrThrow("product_name"))
            val presetId = c.getString(c.getColumnIndexOrThrow("active_preset_id"))
            val profileId = c.getInt(c.getColumnIndexOrThrow("active_profile_id"))
            val intensity = c.getFloat(c.getColumnIndexOrThrow("intensity"))
            val autoEqModelId = c.getString(c.getColumnIndexOrThrow("autoeq_model_id")) ?: "none"
            val autoEqStrength = c.getFloat(c.getColumnIndexOrThrow("autoeq_strength"))
            val mem = DeviceProfileMemory(
                deviceKey = deviceKey,
                routeId = routeType,
                productName = productName,
                profileId = autoEqModelId,
                profileName = productName,
                profileSource = "equalizer.db",
                autoEqPath = "",
                rawPeqOrGraphicText = "",
                strength = autoEqStrength,
                fullRange = false,
                presetId = presetId,
                intensity = intensity
            )
            return mem to profileId
        }
    }

    fun cacheAutoEqProfile(
        modelId: String,
        modelName: String,
        sourceName: String,
        rigName: String?,
        preampDb: Double,
        filtersJson: String,
        correction31: DoubleArray,
        etag: String? = null
    ) {
        val cv = ContentValues().apply {
            put("model_id", modelId)
            put("model_name", modelName)
            put("source_name", sourceName)
            put("rig_name", rigName ?: "")
            put("preamp_db", preampDb)
            put("filters_json", filtersJson)
            put("correction_31_csv", correction31.joinToString(",") { "%.2f".format(Locale.US, it) })
            put("fetched_at_ms", System.currentTimeMillis())
            if (etag != null) put("etag", etag) else putNull("etag")
        }
        writableDatabase.insertWithOnConflict("autoeq_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveSpeakerCalibration(
        enabled: Boolean,
        meas31: DoubleArray,
        valid31: BooleanArray,
        correction31: DoubleArray,
        strength: Float
    ) {
        val cv = ContentValues().apply {
            put("cal_id", "speaker_builtin")
            put("cal_type", "speaker")
            put("measured_31_csv", meas31.joinToString(",") { "%.2f".format(Locale.US, it) })
            put("valid_mask_31_csv", valid31.joinToString(",") { if (it) "1" else "0" })
            put("correction_31_csv", correction31.joinToString(",") { "%.2f".format(Locale.US, it) })
            putNull("ear_left_6_csv")
            putNull("ear_right_6_csv")
            put("strength", strength.toDouble())
            put("enabled", if (enabled) 1 else 0)
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("acoustic_calibrations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun savePersonalHearingCalibration(curve: PersonalHearingCurve) {
        val corr31 = EqMath.computePersonalHearing31(
            curve.leftEarDb,
            curve.rightEarDb,
            curve.strength.toDouble()
        )
        val cv = ContentValues().apply {
            put("cal_id", "hearing_user")
            put("cal_type", "hearing")
            putNull("measured_31_csv")
            putNull("valid_mask_31_csv")
            put("correction_31_csv", corr31.joinToString(",") { "%.2f".format(Locale.US, it) })
            put("ear_left_6_csv", EqMath.formatCustomizedSoundStepsCsv(curve.leftEarDb))
            put("ear_right_6_csv", EqMath.formatCustomizedSoundStepsCsv(curve.rightEarDb))
            put("strength", curve.strength.toDouble())
            put("enabled", if (curve.enabled) 1 else 0)
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("acoustic_calibrations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
