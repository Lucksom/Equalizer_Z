package com.audioeq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audioeq.data.EqualizerDatabaseHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioEqRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Audio EQ", appName)
    }

    @Test
    fun `verify sqlite database seeds 3d audio dynamic profile and balanced ieq tables`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = EqualizerDatabaseHelper(context)
        val db = dbHelper.readableDatabase

        db.rawQuery("SELECT COUNT(*) FROM three_d_audio_spec", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4, c.getInt(0))
        }

        db.rawQuery("SELECT COUNT(*) FROM dynamic_profile_spec", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4, c.getInt(0))
        }

        db.rawQuery("SELECT COUNT(*), MIN(target_gain_db), MAX(target_gain_db) FROM balanced_ieq_curve", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(20, c.getInt(0))
            assertEquals(-23.63, c.getDouble(1), 0.02)
            assertEquals(13.63, c.getDouble(2), 0.02)
        }
        dbHelper.close()
    }
}
