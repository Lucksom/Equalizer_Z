package com.example

import com.example.audio.BiquadFilter
import com.example.audio.Dsp
import com.example.audio.Layers
import com.example.audio.MidSideProcessor
import com.example.model.EqConstants
import com.example.model.FilterType
import com.example.model.SectionWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExampleUnitTest {

    @Test
    fun v4Presets_matchExactSpecificationTable() {
        val balanced = EqConstants.BUILT_IN_PRESETS.first { it.id == "balanced_deep" }
        assertEquals(listOf(3.5f, 4.5f, 2.5f, -2.5f, -1.5f, 0f, 1f, 2.5f, 2.5f, 1.5f), balanced.gainsDb)
        assertEquals(20f, balanced.highPassHz, 0.01f)
        assertEquals(-12f, balanced.bassHarmonicMixDb ?: 0f, 0.01f)

        val bassBoost = EqConstants.BUILT_IN_PRESETS.first { it.id == "bass_boost" }
        assertEquals(listOf(5.5f, 6.5f, 4f, -2f, -1.5f, -0.5f, 0f, 0f, 0.5f, 0.5f), bassBoost.gainsDb)
        assertEquals(25f, bassBoost.highPassHz, 0.01f)
        assertEquals(-9f, bassBoost.bassHarmonicMixDb ?: 0f, 0.01f)

        val vocal = EqConstants.BUILT_IN_PRESETS.first { it.id == "vocal_clarity" }
        assertEquals(listOf(-3.5f, -2.5f, -1f, -3f, -1.5f, 1.5f, 3.5f, 3.5f, 1.5f, 0.5f), vocal.gainsDb)
        assertEquals(60f, vocal.highPassHz, 0.01f)

        val percussion = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        assertEquals(listOf(-4f, 1.5f, 2.5f, -3.5f, -2f, 0f, 1.5f, 3.5f, 3f, 1.5f), percussion.gainsDb)
        assertEquals(40f, percussion.highPassHz, 0.01f)
        assertEquals(-15f, percussion.bassHarmonicMixDb ?: 0f, 0.01f)

        val cinema = EqConstants.BUILT_IN_PRESETS.first { it.id == "cinema" }
        assertEquals(listOf(4f, 4f, 2f, -2f, -1f, 0.5f, 2.5f, 2.5f, 2f, 2f), cinema.gainsDb)
        assertEquals(20f, cinema.highPassHz, 0.01f)
        assertEquals(-12f, cinema.bassHarmonicMixDb ?: 0f, 0.01f)
    }

    @Test
    fun pchip31BandRenderer_andMbcCutoffs_matchV4Section5() {
        // Verify MBC cutoffs align with upper edges of render bands 6, 12, 18, 24, 27
        assertEquals(90f, Dsp.MBC_CUT[0], 1.0f)
        assertEquals(359f, Dsp.MBC_CUT[1], 1.5f)
        assertEquals(1437f, Dsp.MBC_CUT[2], 3.0f)
        assertEquals(5747f, Dsp.MBC_CUT[3], 10.0f)
        assertEquals(11494f, Dsp.MBC_CUT[4], 20.0f)
        assertEquals(20000f, Dsp.MBC_CUT[5], 1.0f)

        // Flat sliders -> 0 dB across all 31 bands
        val flat31 = Dsp.renderCurve(DoubleArray(10) { 0.0 })
        assertTrue(flat31.all { abs(it) < 1e-6 })

        // Single slider at +6 dB -> no overshoot beyond +6 dB (monotone PCHIP property)
        val singleSlider = DoubleArray(10) { if (it == 5) 6.0 else 0.0 }
        val rendered = Dsp.renderCurve(singleSlider)
        assertTrue(rendered.all { it in -0.001..6.001 })
    }

    @Test
    fun volumeContour_interpolatesExpectedOffsets() {
        val highVol = Dsp.contour(0.75)
        assertTrue(highVol.all { abs(it) < 1e-6 })

        val lowVol = Dsp.contour(0.1)
        assertEquals(4.5, lowVol[0], 0.01)
        assertEquals(4.5, lowVol[1], 0.01)
        assertEquals(3.0, lowVol[2], 0.01)
        assertEquals(2.0, lowVol[9], 0.01)
    }

    @Test
    fun midSideProcessor_runsWithHarmonicEnhancerAndAbCompare() {
        val processor = MidSideProcessor(48000.0)
        val preset = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        processor.updateParameters(
            gainsDb = preset.gainsDb,
            preampDb = -3f,
            makeupDb = 2.0f,
            highPassHz = preset.highPassHz,
            bassHarmonicMixDb = preset.bassHarmonicMixDb,
            limiterThresholdDb = -1.0f,
            widths = SectionWidths(),
            qOverrides = preset.qOverrides,
            bypassed = false,
            abCompare = false
        )
        val pcm = ShortArray(128) { idx -> if (idx % 2 == 0) 4000 else -4000 }
        processor.processStereoPcm16(pcm, 64)
        assertTrue(pcm.any { it != 0.toShort() })
    }
}
