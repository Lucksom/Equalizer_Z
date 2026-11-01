package com.audioeq.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.audioeq.audio.AudioProfile
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.EnhancerDsp
import com.audioeq.audio.IeqPreset
import com.audioeq.audio.StereoWideningMode
import com.audioeq.model.EqConstants
import com.audioeq.model.EqPreset
import com.audioeq.model.OutputRoute
import com.audioeq.model.PersonalHearingCurve
import com.audioeq.model.RouteMemoryProfile
import org.json.JSONArray

class EqualizerDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "audio_eq_studio.db"
        const val DATABASE_VERSION = 7
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS presets (
                preset_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                is_custom INTEGER NOT NULL DEFAULT 0,
                gains_json TEXT NOT NULL,
                recommended_preamp_db REAL NOT NULL DEFAULT -3.0,
                recommended_hp_hz REAL NOT NULL DEFAULT 0.0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS route_profiles (
                device_key TEXT PRIMARY KEY,
                route_id TEXT NOT NULL,
                preset_id TEXT NOT NULL,
                gains_json TEXT NOT NULL,
                right_gains_json TEXT NOT NULL DEFAULT '[0,0,0,0,0,0,0,0,0,0]',
                split_lr_enabled INTEGER NOT NULL DEFAULT 0,
                balance_db REAL NOT NULL DEFAULT 0.0,
                preamp_db REAL NOT NULL,
                auto_gain_enabled INTEGER NOT NULL DEFAULT 1,
                limiter_enabled INTEGER NOT NULL DEFAULT 1,
                limiter_ceiling_db REAL NOT NULL DEFAULT -1.0,
                sub_bass_hp_hz REAL NOT NULL DEFAULT 0.0,
                iso226_enabled INTEGER NOT NULL DEFAULT 0,
                mid_side_width REAL NOT NULL DEFAULT 1.0,
                vocal_presence_boost REAL NOT NULL DEFAULT 0.0,
                headphone_profile_id TEXT NOT NULL DEFAULT 'none',
                headphone_strength REAL NOT NULL DEFAULT 0.8,
                headphone_full_range INTEGER NOT NULL DEFAULT 0,
                tilt_bass_db REAL NOT NULL DEFAULT 0.0,
                tilt_treble_db REAL NOT NULL DEFAULT 0.0,
                speaker_cal_enabled INTEGER NOT NULL DEFAULT 0,
                speaker_cal_strength REAL NOT NULL DEFAULT 0.7,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS speaker_calibration (
                route_id TEXT PRIMARY KEY,
                measured_31_json TEXT NOT NULL,
                correction_31_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hearing_curve (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                enabled INTEGER NOT NULL DEFAULT 0,
                left_boost_6_json TEXT NOT NULL,
                right_boost_6_json TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS enhancer_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                ieq_preset_id TEXT NOT NULL DEFAULT 'ieq_off',
                ieq_amount INTEGER NOT NULL DEFAULT 10,
                ieq_custom_10_json TEXT NOT NULL,
                dialogue_enabled INTEGER NOT NULL DEFAULT 0,
                dialogue_amount INTEGER NOT NULL DEFAULT 8,
                dialogue_ducking INTEGER NOT NULL DEFAULT 2,
                bass_enabled INTEGER NOT NULL DEFAULT 0,
                bass_strength INTEGER NOT NULL DEFAULT 80,
                virtual_bass_enabled INTEGER NOT NULL DEFAULT 0,
                audio_profile_id TEXT NOT NULL DEFAULT 'profile_off',
                stereo_widening_id TEXT NOT NULL DEFAULT 'widening_off',
                reduce_reverb_amount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """.trimIndent()
        )

        createAndSeedSpecificationTables(db)
        seedDefaultData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS route_profiles")
            db.execSQL("DROP TABLE IF EXISTS presets")
            db.execSQL("DROP TABLE IF EXISTS app_settings")
            onCreate(db)
            return
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS enhancer_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    ieq_preset_id TEXT NOT NULL DEFAULT 'ieq_off',
                    ieq_amount INTEGER NOT NULL DEFAULT 10,
                    ieq_custom_10_json TEXT NOT NULL,
                    dialogue_enabled INTEGER NOT NULL DEFAULT 0,
                    dialogue_amount INTEGER NOT NULL DEFAULT 8,
                    dialogue_ducking INTEGER NOT NULL DEFAULT 2,
                    bass_enabled INTEGER NOT NULL DEFAULT 0,
                    bass_strength INTEGER NOT NULL DEFAULT 80,
                    virtual_bass_enabled INTEGER NOT NULL DEFAULT 0,
                    audio_profile_id TEXT NOT NULL DEFAULT 'profile_off',
                    stereo_widening_id TEXT NOT NULL DEFAULT 'widening_off',
                    reduce_reverb_amount INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            seedDefaultEnhancerSettings(db)
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE enhancer_settings ADD COLUMN stereo_widening_id TEXT NOT NULL DEFAULT 'widening_off'")
            } catch (_: Exception) {
            }
            try {
                db.execSQL("ALTER TABLE enhancer_settings ADD COLUMN reduce_reverb_amount INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
            }
            db.execSQL("DELETE FROM presets WHERE preset_id IN ('bass_boost', 'cinema') AND is_custom = 0")
            createAndSeedSpecificationTables(db)
        }
        if (oldVersion < 7) {
            db.execSQL("UPDATE route_profiles SET preamp_db = 0.0, auto_gain_enabled = 0, limiter_ceiling_db = -0.3, sub_bass_hp_hz = 0.0")
            db.execSQL("UPDATE presets SET recommended_preamp_db = 0.0, recommended_hp_hz = 0.0 WHERE is_custom = 0")
        }
    }

    private fun createAndSeedSpecificationTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS movie_profile (
                param_key TEXT PRIMARY KEY,
                raw_value TEXT NOT NULL,
                physical_unit TEXT NOT NULL,
                dsp_stage TEXT NOT NULL,
                mathematical_role TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("DELETE FROM movie_profile")
        val movieRows = listOf(
            arrayOf("profile_id", "0", "Enum ID", "Profile Router", "Selects Movie processing graph"),
            arrayOf("profile_name", "Movie", "String", "UI / HAL", "Cinematic surround & dialogue clarity profile"),
            arrayOf("reverb-suppression-enable", "1", "Boolean (true)", "De-Reverberation", "Enables WPE + spectral reverb tail suppression"),
            arrayOf("reverb-suppression-amount", "2", "Index [0..9] (gamma = 1.20)", "De-Reverberation", "Over-subtraction factor gamma=1.20, floor G_min=-12.0 dB"),
            arrayOf("headphone-virtualizer-mode", "2", "Enum (Binaural HRTF)", "Spatial Virtualizer", "Activates 5.1 -> 2.0 HRTF crossfeed & early reflections"),
            arrayOf("virtualizer-Stage-enable", "1", "Boolean (true)", "Spatial Virtualizer", "Enables soundstage width & reflection room model"),
            arrayOf("advanced-headphone-virtualizer-rendering-config", "35,32568,11164,5090,0,3,3,3", "Tuple [Azimuth, G_dir, G_cross, G_refl, ...]", "HRTF Matrix", "theta=+-35 deg, G_direct=0.9939, G_cross=0.3407, G_refl=0.1553"),
            arrayOf("surround-decoder-enable", "1", "Boolean (true)", "Matrix Upmixer", "Enables 2.0 -> 5.1 Mid/Side phase matrix decoding"),
            arrayOf("dialog-enhancer-enable", "1", "Boolean (true)", "Voice Presence", "Enables 1-4 kHz speech band isolation & boost"),
            arrayOf("dialog-enhancer-amount", "4", "Q2 Index (+2.50 dB)", "Voice Presence", "Applies +2.50 dB dynamic peaking gain to speech bands"),
            arrayOf("dialog-enhancer-ducking", "0", "Index (0 = Off)", "Non-Voice Ducking", "0 dB attenuation on non-speech bed channels"),
            arrayOf("volume-leveler-enable", "1", "Boolean (true)", "Dynamic Loudness", "Enables ITU-R BS.1770 K-weighted loudness leveler"),
            arrayOf("volume-leveler-amount", "1", "Index [0..10]", "Dynamic Loudness", "Gentle compression ratio for cinematic dynamic range"),
            arrayOf("volume-leveler-in-target", "-256", "Q4 LUFS (-16.0 LUFS)", "Dynamic Loudness", "Input reference loudness anchor (-256 / 16 = -16.0 LUFS)"),
            arrayOf("volume-leveler-out-target", "-256", "Q4 LUFS (-16.0 LUFS)", "Dynamic Loudness", "Output target loudness anchor (-256 / 16 = -16.0 LUFS)"),
            arrayOf("volmax-boost_headphone", "32", "Q4 dB (+2.00 dB)", "Output Limiter", "Post-leveler makeup boost (+32 / 16 = +2.00 dB)"),
            arrayOf("volmax-boost_speaker", "64", "Q4 dB (+4.00 dB)", "Output Limiter", "Speaker makeup boost (+64 / 16 = +4.00 dB)"),
            arrayOf("ieq-enable", "0", "Boolean (false)", "Intelligent EQ", "Bypassed by default in Movie; preset=2 (Balanced) on standby"),
            arrayOf("ieq-amount", "10", "Q4 Index (0.625)", "Intelligent EQ", "62.5% target convergence strength when IEQ is toggled on"),
            arrayOf("bass-enhancer-enable", "0", "Boolean (false)", "Sub-Bass Exciter", "Disabled in Movie to prevent LFE boom masking dialogue")
        )
        for (row in movieRows) {
            val cv = ContentValues().apply {
                put("param_key", row[0])
                put("raw_value", row[1])
                put("physical_unit", row[2])
                put("dsp_stage", row[3])
                put("mathematical_role", row[4])
            }
            db.insertWithOnConflict("movie_profile", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS music_profile (
                param_key TEXT PRIMARY KEY,
                raw_value TEXT NOT NULL,
                physical_unit TEXT NOT NULL,
                dsp_stage TEXT NOT NULL,
                mathematical_role TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("DELETE FROM music_profile")
        val musicRows = listOf(
            arrayOf("profile_id", "1", "Enum ID", "Profile Router", "Selects Music processing graph"),
            arrayOf("profile_name", "Music", "String", "UI / HAL", "High-fidelity tonal balance & customizable spatial profile"),
            arrayOf("reverb-suppression-enable", "0", "Boolean (false)", "De-Reverberation", "Default Off (user-controllable via Reduce Reverb slider 0..9)"),
            arrayOf("reverb-suppression-amount", "5", "Index [0..9] (gamma = 2.25)", "De-Reverberation", "Default standby amount when enabled (G_min = -30.0 dB)"),
            arrayOf("headphone-virtualizer-mode", "0", "Enum (Off / User Select)", "Spatial Virtualizer", "Controlled by Stereo Widening selector (0, 16, 35, 103)"),
            arrayOf("advanced-headphone-virtualizer-rendering-config", "35,32568,11164,5090,0,3,3,3", "Tuple [Azimuth, G_dir, G_cross, G_refl, ...]", "HRTF Matrix", "Default Middle widening coefficient table"),
            arrayOf("surround-decoder-enable", "1", "Boolean (true)", "Matrix Upmixer", "Extracts Mid/Side spatial components for widening"),
            arrayOf("dialog-enhancer-enable", "0", "Boolean (false)", "Voice Presence", "Disabled by default to preserve natural studio vocal mix"),
            arrayOf("dialog-enhancer-amount", "8", "Q2 Index (+5.00 dB)", "Voice Presence", "Standby vocal boost if enabled by user"),
            arrayOf("volume-leveler-enable", "0", "Boolean (false)", "Dynamic Loudness", "Disabled to preserve full musical crest factor"),
            arrayOf("ieq-enable", "0", "Boolean (false / User)", "Intelligent EQ", "User-selectable: Off(0), Detailed(1), Balanced(2), Warm(3)"),
            arrayOf("ieq-amount", "10", "Q4 Index (0.625)", "Intelligent EQ", "alpha = 10/16 = 0.625 spectral target convergence"),
            arrayOf("bass-enhancer-enable", "0", "Boolean (false)", "Bass Enhancer", "Removed from UI per clean studio profile specification")
        )
        for (row in musicRows) {
            val cv = ContentValues().apply {
                put("param_key", row[0])
                put("raw_value", row[1])
                put("physical_unit", row[2])
                put("dsp_stage", row[3])
                put("mathematical_role", row[4])
            }
            db.insertWithOnConflict("music_profile", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stereo_widening (
                mode_index INTEGER PRIMARY KEY,
                ui_label TEXT NOT NULL,
                dax_amount INTEGER NOT NULL,
                virtual_azimuth_deg INTEGER NOT NULL,
                width_scalar_w REAL NOT NULL,
                side_gain_db REAL NOT NULL,
                allpass_delay_ms REAL NOT NULL,
                delay_samples_48k INTEGER NOT NULL,
                hrtf_direct_q15 INTEGER NOT NULL,
                hrtf_cross_q15 INTEGER NOT NULL,
                hrtf_refl_q15 INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("DELETE FROM stereo_widening")
        val wideningRows = listOf(
            arrayOf<Any>(0, "No effect", 0, 0, 1.000, 0.00, 0.00, 0, 32767, 0, 0),
            arrayOf<Any>(1, "Near", 16, 16, 1.250, 1.94, 0.20, 10, 32680, 6850, 2800),
            arrayOf<Any>(2, "Middle", 35, 35, 1.547, 3.79, 0.45, 22, 32568, 11164, 5090),
            arrayOf<Any>(3, "Far", 103, 103, 2.609, 8.33, 0.75, 36, 31900, 16384, 8192)
        )
        for (row in wideningRows) {
            val cv = ContentValues().apply {
                put("mode_index", row[0] as Int)
                put("ui_label", row[1] as String)
                put("dax_amount", row[2] as Int)
                put("virtual_azimuth_deg", row[3] as Int)
                put("width_scalar_w", row[4] as Double)
                put("side_gain_db", row[5] as Double)
                put("allpass_delay_ms", row[6] as Double)
                put("delay_samples_48k", row[7] as Int)
                put("hrtf_direct_q15", row[8] as Int)
                put("hrtf_cross_q15", row[9] as Int)
                put("hrtf_refl_q15", row[10] as Int)
            }
            db.insertWithOnConflict("stereo_widening", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reduce_reverb (
                slider_level INTEGER PRIMARY KEY,
                enable_flag INTEGER NOT NULL,
                dax_amount INTEGER NOT NULL,
                oversubtraction_gamma REAL NOT NULL,
                min_gain_floor_db REAL NOT NULL,
                late_delay_frames INTEGER NOT NULL,
                early_to_late_scale_b REAL NOT NULL,
                subjective_effect TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("DELETE FROM reduce_reverb")
        val reverbRows = listOf(
            arrayOf<Any>(0, 0, 0, 0.00, 0.0, 12, 0.00, "Bypass (100% original room acoustics)"),
            arrayOf<Any>(1, 1, 1, 0.85, -6.0, 12, 0.08, "Subtle tightening of long hall decay"),
            arrayOf<Any>(2, 1, 2, 1.20, -12.0, 12, 0.08, "Movie Profile Default — balanced dialogue de-reverb"),
            arrayOf<Any>(3, 1, 3, 1.55, -18.0, 12, 0.08, "Moderate room reflection damping"),
            arrayOf<Any>(4, 1, 4, 1.90, -24.0, 12, 0.08, "Studio booth dry vocal focus"),
            arrayOf<Any>(5, 1, 5, 2.25, -30.0, 12, 0.08, "Music Profile Standby — strong echo suppression"),
            arrayOf<Any>(6, 1, 6, 2.60, -36.0, 12, 0.08, "Deep late-field tail removal"),
            arrayOf<Any>(7, 1, 7, 2.95, -42.0, 12, 0.08, "Aggressive reverberation stripping"),
            arrayOf<Any>(8, 1, 8, 3.30, -48.0, 12, 0.08, "Near-anechoic transient isolation"),
            arrayOf<Any>(9, 1, 9, 3.65, -54.0, 12, 0.08, "Maximum suppression (-54 dB floor)")
        )
        for (row in reverbRows) {
            val cv = ContentValues().apply {
                put("slider_level", row[0] as Int)
                put("enable_flag", row[1] as Int)
                put("dax_amount", row[2] as Int)
                put("oversubtraction_gamma", row[3] as Double)
                put("min_gain_floor_db", row[4] as Double)
                put("late_delay_frames", row[5] as Int)
                put("early_to_late_scale_b", row[6] as Double)
                put("subjective_effect", row[7] as String)
            }
            db.insertWithOnConflict("reduce_reverb", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ieq_profiles (
                band_index INTEGER PRIMARY KEY,
                center_freq_hz INTEGER NOT NULL,
                balanced_q4 INTEGER NOT NULL,
                balanced_db REAL NOT NULL,
                warm_q4 INTEGER NOT NULL,
                warm_db REAL NOT NULL,
                detailed_q4 INTEGER NOT NULL,
                detailed_db REAL NOT NULL,
                off_q4 INTEGER NOT NULL,
                off_db REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("DELETE FROM ieq_profiles")
        for (i in 0 until 20) {
            val balQ4 = EnhancerDsp.IEQ_BALANCED_Q4[i]
            val warmQ4 = EnhancerDsp.IEQ_WARM_Q4[i]
            val detQ4 = EnhancerDsp.IEQ_DETAILED_Q4[i]
            val cv = ContentValues().apply {
                put("band_index", i)
                put("center_freq_hz", EnhancerDsp.IEQ_FREQS_20[i])
                put("balanced_q4", balQ4)
                put("balanced_db", balQ4 / 16.0)
                put("warm_q4", warmQ4)
                put("warm_db", warmQ4 / 16.0)
                put("detailed_q4", detQ4)
                put("detailed_db", detQ4 / 16.0)
                put("off_q4", 0)
                put("off_db", 0.0)
            }
            db.insertWithOnConflict("ieq_profiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dsp_mathematical_models (
                module_id TEXT PRIMARY KEY,
                module_name TEXT NOT NULL,
                domain TEXT NOT NULL,
                governing_equation TEXT NOT NULL,
                constants_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("DELETE FROM dsp_mathematical_models")
        val modelRows = listOf(
            arrayOf(
                "stereo_widening_ms",
                "Stereo Widening (M/S + All-Pass Decorrelator)",
                "Time / Z-Domain",
                "M=(L+R)/2; S=(L-R)/2; S_wide = (1 + amount/64) * H_ap(S); L_out=M+S_wide, R_out=M-S_wide",
                """{"pole_radius_a":0.65,"delays_48k":[0,10,22,36],"amounts":[0,16,35,103]}"""
            ),
            arrayOf(
                "reduce_reverb_wiener",
                "Reduce Reverb (Late-Reverb Spectral Subtraction)",
                "STFT Frequency Domain (N=512, R=256)",
                "Phi_rr(k,m)=A*Phi_rr(k,m-1)+B*|X(k,m-D)|^2; G(k,m)=max(G_min, 1 - gamma*sqrt(Phi_rr/Phi_xx))",
                """{"D_frames":12,"B_scale":0.08,"gamma_base":0.50,"gamma_step":0.35,"floor_step_db":-6.0}"""
            ),
            arrayOf(
                "movie_binaural_hrtf",
                "Movie Profile Binaural HRTF Virtualizer",
                "Time / IIR Filter Domain",
                "L_out = G_dir*L + G_cross*H_shadow(R(n-D_itd)) + G_refl*S_dec",
                """{"azimuth_deg":35,"G_dir":0.9939,"G_cross":0.3407,"G_refl":0.1553,"itd_ms":0.3022,"shadow_fc_hz":850}"""
            ),
            arrayOf(
                "ieq_spectral_balancer",
                "20-Band Intelligent Equalizer (IEQ)",
                "Log-Frequency Biquad Cascade",
                "Delta_b(m) = clamp(alpha * (T_p(b) - (E_b(m) - E_bar(m))), -12.0, +9.0)",
                """{"bands":20,"q_factor":2.15,"alpha_default":0.625,"attack_ms":15.0,"release_ms":120.0}"""
            )
        )
        for (row in modelRows) {
            val cv = ContentValues().apply {
                put("module_id", row[0])
                put("module_name", row[1])
                put("domain", row[2])
                put("governing_equation", row[3])
                put("constants_json", row[4])
            }
            db.insertWithOnConflict("dsp_mathematical_models", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun seedDefaultEnhancerSettings(db: SQLiteDatabase) {
        val defaultCustom10 = EnhancerDsp.ieqDefaultCustom10Db(IeqPreset.BALANCED)
        val cv = ContentValues().apply {
            put("id", 1)
            put("ieq_preset_id", IeqPreset.OFF.id)
            put("ieq_amount", 10)
            put("ieq_custom_10_json", floatsToJson(defaultCustom10))
            put("dialogue_enabled", 0)
            put("dialogue_amount", 8)
            put("dialogue_ducking", 2)
            put("bass_enabled", 0)
            put("bass_strength", 80)
            put("virtual_bass_enabled", 0)
            put("audio_profile_id", AudioProfile.OFF.id)
            put("stereo_widening_id", StereoWideningMode.NO_EFFECT.id)
            put("reduce_reverb_amount", 0)
        }
        db.insertWithOnConflict("enhancer_settings", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun seedDefaultData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        EqConstants.BUILT_IN_PRESETS.forEach { preset ->
            val values = ContentValues().apply {
                put("preset_id", preset.id)
                put("name", preset.name)
                put("is_custom", 0)
                put("gains_json", floatsToJson(preset.gainsDb))
                put("recommended_preamp_db", preset.recommendedPreampDb)
                put("recommended_hp_hz", preset.recommendedHighPassHz)
                put("created_at", now)
            }
            db.insertWithOnConflict("presets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        OutputRoute.entries.forEach { route ->
            val defaultKey = when (route) {
                OutputRoute.SPEAKER -> "speaker:builtin"
                OutputRoute.HEADPHONES -> "wired:3.5mm"
                OutputRoute.BLUETOOTH -> "bt:default"
                OutputRoute.USB_DAC -> "usb:dac"
            }
            insertDefaultRouteRow(db, route, defaultKey, now)
        }

        val hearingCv = ContentValues().apply {
            put("id", 1)
            put("enabled", 0)
            put("left_boost_6_json", floatsToJson(List(6) { 0f }))
            put("right_boost_6_json", floatsToJson(List(6) { 0f }))
        }
        db.insertWithOnConflict("hearing_curve", null, hearingCv, SQLiteDatabase.CONFLICT_REPLACE)

        seedDefaultEnhancerSettings(db)
    }

    private fun insertDefaultRouteRow(
        db: SQLiteDatabase,
        route: OutputRoute,
        deviceKey: String,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("device_key", deviceKey)
            put("route_id", route.id)
            put("preset_id", "flat")
            put("gains_json", floatsToJson(List(10) { 0f }))
            put("right_gains_json", floatsToJson(List(10) { 0f }))
            put("split_lr_enabled", 0)
            put("balance_db", 0.0f)
            put("preamp_db", route.defaultSafeCutDb)
            put("auto_gain_enabled", 1)
            put("limiter_enabled", 1)
            put("limiter_ceiling_db", route.defaultLimiterCeilingDb)
            put("sub_bass_hp_hz", route.defaultHighPassHz)
            put("iso226_enabled", 0)
            put("mid_side_width", 1.0f)
            put("vocal_presence_boost", 0.0f)
            put("headphone_profile_id", "none")
            put("headphone_strength", 0.8f)
            put("headphone_full_range", 0)
            put("tilt_bass_db", 0.0f)
            put("tilt_treble_db", 0.0f)
            put("speaker_cal_enabled", 0)
            put("speaker_cal_strength", 0.7f)
            put("updated_at", now)
        }
        db.insertWithOnConflict("route_profiles", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun loadEnhancerConfig(): EnhancerConfig {
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT ieq_preset_id, ieq_amount, ieq_custom_10_json,
                   dialogue_enabled, dialogue_amount, dialogue_ducking,
                   bass_enabled, bass_strength, virtual_bass_enabled, audio_profile_id,
                   stereo_widening_id, reduce_reverb_amount
            FROM enhancer_settings WHERE id = 1
            """.trimIndent(),
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val preset = IeqPreset.fromId(cursor.getString(0))
                val ieqAmt = cursor.getInt(1).coerceIn(0, 16)
                val custom10 = jsonToFloats(cursor.getString(2), 10)
                val dlgOn = cursor.getInt(3) == 1
                val dlgAmt = cursor.getInt(4).coerceIn(0, 16)
                val dlgDuck = cursor.getInt(5).coerceIn(0, 16)
                val bassOn = cursor.getInt(6) == 1
                val bassStr = cursor.getInt(7).coerceIn(0, 100)
                val vBassOn = cursor.getInt(8) == 1
                val prof = AudioProfile.fromId(cursor.getString(9))
                val widening = StereoWideningMode.fromId(cursor.getString(10))
                val reduceReverb = cursor.getInt(11).coerceIn(0, 9)
                return EnhancerConfig(
                    ieqPreset = preset,
                    ieqAmount = ieqAmt,
                    ieqCustom10Db = custom10,
                    dialogueEnhancerEnabled = dlgOn,
                    dialogueAmount = dlgAmt,
                    dialogueDucking = dlgDuck,
                    bassEnhancerEnabled = bassOn,
                    bassEnhancerStrength = bassStr,
                    virtualBassEnabled = vBassOn,
                    profile = prof,
                    stereoWideningMode = widening,
                    reduceReverbAmount = reduceReverb
                )
            }
        }
        return EnhancerConfig(
            ieqCustom10Db = EnhancerDsp.ieqDefaultCustom10Db(IeqPreset.BALANCED)
        )
    }

    fun saveEnhancerConfig(config: EnhancerConfig) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", 1)
            put("ieq_preset_id", config.ieqPreset.id)
            put("ieq_amount", config.ieqAmount.coerceIn(0, 16))
            put("ieq_custom_10_json", floatsToJson(config.ieqCustom10Db))
            put("dialogue_enabled", if (config.dialogueEnhancerEnabled) 1 else 0)
            put("dialogue_amount", config.dialogueAmount.coerceIn(0, 16))
            put("dialogue_ducking", config.dialogueDucking.coerceIn(0, 16))
            put("bass_enabled", if (config.bassEnhancerEnabled) 1 else 0)
            put("bass_strength", config.bassEnhancerStrength.coerceIn(0, 100))
            put("virtual_bass_enabled", if (config.virtualBassEnabled) 1 else 0)
            put("audio_profile_id", config.profile.id)
            put("stereo_widening_id", config.stereoWideningMode.id)
            put("reduce_reverb_amount", config.reduceReverbAmount.coerceIn(0, 9))
        }
        db.insertWithOnConflict("enhancer_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadAllPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT preset_id, name, is_custom, gains_json, recommended_preamp_db, recommended_hp_hz FROM presets ORDER BY is_custom ASC, created_at ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                val isCustom = cursor.getInt(2) == 1
                val gains = jsonToFloats(cursor.getString(3), 10)
                val preamp = cursor.getFloat(4)
                val hp = cursor.getFloat(5)
                list.add(
                    EqPreset(
                        id = id,
                        name = name,
                        gainsDb = gains,
                        isCustom = isCustom,
                        recommendedPreampDb = preamp,
                        recommendedHighPassHz = hp
                    )
                )
            }
        }
        return if (list.isEmpty()) EqConstants.BUILT_IN_PRESETS else list
    }

    fun saveCustomPreset(preset: EqPreset) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("preset_id", preset.id)
            put("name", preset.name)
            put("is_custom", 1)
            put("gains_json", floatsToJson(preset.gainsDb))
            put("recommended_preamp_db", preset.recommendedPreampDb)
            put("recommended_hp_hz", preset.recommendedHighPassHz)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("presets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteCustomPreset(presetId: String) {
        writableDatabase.delete("presets", "preset_id = ? AND is_custom = 1", arrayOf(presetId))
    }

    fun loadRouteProfile(route: OutputRoute, deviceKey: String = route.id): RouteMemoryProfile {
        val db = readableDatabase
        val querySql = """
            SELECT preset_id, gains_json, right_gains_json, split_lr_enabled, balance_db,
                   preamp_db, auto_gain_enabled, limiter_enabled, limiter_ceiling_db,
                   sub_bass_hp_hz, iso226_enabled, mid_side_width, vocal_presence_boost,
                   headphone_profile_id, headphone_strength, headphone_full_range,
                   tilt_bass_db, tilt_treble_db, speaker_cal_enabled, speaker_cal_strength
            FROM route_profiles WHERE device_key = ?
        """.trimIndent()

        db.rawQuery(querySql, arrayOf(deviceKey)).use { cursor ->
            if (cursor.moveToFirst()) {
                return parseRouteCursor(route, deviceKey, cursor)
            }
        }
        val fallbackKey = when (route) {
            OutputRoute.SPEAKER -> "speaker:builtin"
            OutputRoute.HEADPHONES -> "wired:3.5mm"
            OutputRoute.BLUETOOTH -> "bt:default"
            OutputRoute.USB_DAC -> "usb:dac"
        }
        db.rawQuery(querySql, arrayOf(fallbackKey)).use { cursor ->
            if (cursor.moveToFirst()) {
                return parseRouteCursor(route, deviceKey, cursor)
            }
        }
        return RouteMemoryProfile(route = route, deviceKey = deviceKey)
    }

    private fun parseRouteCursor(
        route: OutputRoute,
        deviceKey: String,
        cursor: android.database.Cursor
    ): RouteMemoryProfile {
        return RouteMemoryProfile(
            route = route,
            deviceKey = deviceKey,
            presetId = cursor.getString(0),
            bandGainsDb = jsonToFloats(cursor.getString(1), 10),
            rightBandGainsDb = jsonToFloats(cursor.getString(2), 10),
            splitLREnabled = cursor.getInt(3) == 1,
            balanceDb = cursor.getFloat(4),
            preampDb = cursor.getFloat(5),
            autoGainEnabled = cursor.getInt(6) == 1,
            limiterEnabled = cursor.getInt(7) == 1,
            limiterCeilingDb = cursor.getFloat(8),
            subBassHighPassHz = cursor.getFloat(9),
            iso226ContourEnabled = cursor.getInt(10) == 1,
            midSideWidth = cursor.getFloat(11),
            vocalPresenceBoost = cursor.getFloat(12),
            headphoneProfileId = cursor.getString(13),
            headphoneCorrectionStrength = cursor.getFloat(14),
            headphoneFullRange = cursor.getInt(15) == 1,
            tiltBassDb = cursor.getFloat(16),
            tiltTrebleDb = cursor.getFloat(17),
            speakerCalibrationEnabled = cursor.getInt(18) == 1,
            speakerCalStrength = cursor.getFloat(19)
        )
    }

    fun saveRouteProfile(profile: RouteMemoryProfile) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("device_key", profile.deviceKey)
            put("route_id", profile.route.id)
            put("preset_id", profile.presetId)
            put("gains_json", floatsToJson(profile.bandGainsDb))
            put("right_gains_json", floatsToJson(profile.rightBandGainsDb))
            put("split_lr_enabled", if (profile.splitLREnabled) 1 else 0)
            put("balance_db", profile.balanceDb)
            put("preamp_db", profile.preampDb)
            put("auto_gain_enabled", if (profile.autoGainEnabled) 1 else 0)
            put("limiter_enabled", if (profile.limiterEnabled) 1 else 0)
            put("limiter_ceiling_db", profile.limiterCeilingDb)
            put("sub_bass_hp_hz", profile.subBassHighPassHz)
            put("iso226_enabled", if (profile.iso226ContourEnabled) 1 else 0)
            put("mid_side_width", profile.midSideWidth)
            put("vocal_presence_boost", profile.vocalPresenceBoost)
            put("headphone_profile_id", profile.headphoneProfileId)
            put("headphone_strength", profile.headphoneCorrectionStrength)
            put("headphone_full_range", if (profile.headphoneFullRange) 1 else 0)
            put("tilt_bass_db", profile.tiltBassDb)
            put("tilt_treble_db", profile.tiltTrebleDb)
            put("speaker_cal_enabled", if (profile.speakerCalibrationEnabled) 1 else 0)
            put("speaker_cal_strength", profile.speakerCalStrength)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("route_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveSpeakerCalibration(
        routeId: String,
        measured31Db: List<Float>,
        correction31Db: List<Float>
    ) {
        val cv = ContentValues().apply {
            put("route_id", routeId)
            put("measured_31_json", floatsToJson(measured31Db))
            put("correction_31_json", floatsToJson(correction31Db))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("speaker_calibration", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadSpeakerCalibration(routeId: String): Pair<List<Float>, List<Float>>? {
        readableDatabase.rawQuery(
            "SELECT measured_31_json, correction_31_json FROM speaker_calibration WHERE route_id = ?",
            arrayOf(routeId)
        ).use { c ->
            if (c.moveToFirst()) {
                val m = jsonToFloats(c.getString(0), 31)
                val corr = jsonToFloats(c.getString(1), 31)
                return m to corr
            }
        }
        return null
    }

    fun deleteSpeakerCalibration(routeId: String) {
        writableDatabase.delete("speaker_calibration", "route_id = ?", arrayOf(routeId))
    }

    fun loadHearingCurve(): PersonalHearingCurve {
        readableDatabase.rawQuery(
            "SELECT enabled, left_boost_6_json, right_boost_6_json FROM hearing_curve WHERE id = 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                return PersonalHearingCurve(
                    enabled = c.getInt(0) == 1,
                    leftEarBoostDb = jsonToFloats(c.getString(1), 6),
                    rightEarBoostDb = jsonToFloats(c.getString(2), 6)
                )
            }
        }
        return PersonalHearingCurve()
    }

    fun saveHearingCurve(curve: PersonalHearingCurve) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("enabled", if (curve.enabled) 1 else 0)
            put("left_boost_6_json", floatsToJson(curve.leftEarBoostDb))
            put("right_boost_6_json", floatsToJson(curve.rightEarBoostDb))
        }
        writableDatabase.insertWithOnConflict("hearing_curve", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSetting(key: String, defaultValue: String): String {
        val db = readableDatabase
        db.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key = ?",
            arrayOf(key)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return defaultValue
    }

    fun setSetting(key: String, value: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("setting_key", key)
            put("setting_value", value)
        }
        db.insertWithOnConflict("app_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun floatsToJson(values: List<Float>): String {
        val array = JSONArray()
        values.forEach { array.put(it.toDouble()) }
        return array.toString()
    }

    private fun jsonToFloats(json: String, expectedCount: Int): List<Float> {
        return try {
            val array = JSONArray(json)
            List(expectedCount) { index ->
                if (index < array.length()) array.getDouble(index).toFloat() else 0f
            }
        } catch (_: Exception) {
            List(expectedCount) { 0f }
        }
    }
}
