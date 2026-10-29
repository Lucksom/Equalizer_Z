package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "custom_presets")
data class CustomPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val gainsCsv: String, // 10 comma-separated float gains in dB
    val preampDb: Float,
    val highPassHz: Float,
    val bassWidthPct: Int,
    val midsWidthPct: Int,
    val trebleWidthPct: Int,
    val airWidthPct: Int,
    val bassBoostStrength: Int,
    val virtualizerStrength: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface CustomPresetDao {
    @Query("SELECT * FROM custom_presets ORDER BY timestamp DESC")
    fun getAllCustomPresets(): Flow<List<CustomPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: CustomPresetEntity)

    @Query("DELETE FROM custom_presets WHERE id = :id")
    suspend fun deletePresetById(id: Int)
}

@Database(entities = [CustomPresetEntity::class], version = 1, exportSchema = false)
abstract class EqDatabase : RoomDatabase() {
    abstract fun customPresetDao(): CustomPresetDao
}
