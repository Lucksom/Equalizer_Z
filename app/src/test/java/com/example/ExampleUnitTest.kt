package com.example

import com.example.audio.BiquadFilter
import com.example.audio.MidSideProcessor
import com.example.model.EqConstants
import com.example.model.FilterType
import com.example.model.FrequencySection
import com.example.model.SectionWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun percussionClarityPreset_hasExpectedTablaValues() {
        val preset = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        assertEquals("Percussion Clarity", preset.name)
        assertEquals(40f, preset.highPassHz, 0.01f)
        // 250 Hz is index 3 (-3 dB), 4 kHz is index 7 (+2 dB), 8 kHz is index 8 (+1.5 dB)
        assertEquals(-3f, preset.gainsDb[3], 0.01f)
        assertEquals(2f, preset.gainsDb[7], 0.01f)
        assertEquals(1.5f, preset.gainsDb[8], 0.01f)
        assertEquals(100, preset.widths.forSection(FrequencySection.MIDS))
    }

    @Test
    fun biquadPeakingFilter_matchesCenterGain() {
        val filter = BiquadFilter()
        filter.configure(
            type = FilterType.PEAKING,
            frequencyHz = 1000.0,
            sampleRate = 48000.0,
            gainDb = 6.0,
            q = 1.4
        )
        val centerDb = filter.magnitudeDbAt(1000.0, 48000.0)
        assertEquals(6.0, centerDb, 0.15)
    }

    @Test
    fun midSideProcessor_computesResponseCurveAndProcessesBuffer() {
        val processor = MidSideProcessor(48000.0)
        val preset = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        processor.updateParameters(
            gainsDb = preset.gainsDb,
            preampDb = preset.preampDb,
            highPassHz = preset.highPassHz,
            widths = SectionWidths(0, 100, 120, 140),
            qOverrides = preset.qOverrides,
            bypassed = false
        )
        val curve = processor.computeFrequencyResponseCurve(64)
        assertEquals(64, curve.size)

        val pcm = ShortArray(128) { idx -> if (idx % 2 == 0) 4000 else -4000 }
        processor.processStereoPcm16(pcm, 64)
        assertTrue(pcm.any { it != 0.toShort() })
    }
}
