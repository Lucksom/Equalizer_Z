package com.audioeq

import com.audioeq.audio.AutoEq
import com.audioeq.audio.AutoEqManager
import com.audioeq.audio.BassContour
import com.audioeq.audio.DialogueMode
import com.audioeq.audio.Dsp
import com.audioeq.audio.DspV4Engine
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.EnhancerDsp
import com.audioeq.audio.EnhancerMath
import com.audioeq.audio.Enhancers
import com.audioeq.audio.IeqPreset
import com.audioeq.audio.MidSideProcessor
import com.audioeq.audio.PeqFilter
import com.audioeq.model.EqConstants
import com.audioeq.model.EqMode
import com.audioeq.model.HeadphoneProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioEqUnitTest {

    @Test
    fun verifyAllModeBandCountsAndMonotonicFrequencies() {
        for (mode in EqMode.entries) {
            val freqs = EqConstants.frequenciesForMode(mode)
            assertEquals("Band count mismatch for $mode", mode.bandCount, freqs.size)
            for (i in 0 until freqs.size - 1) {
                assertTrue(
                    "Frequencies must be strictly increasing in $mode",
                    freqs[i] < freqs[i + 1]
                )
            }
        }
    }

    @Test
    fun verifyResampleCurvePreservesFlatAndEndpoints() {
        val flat10 = List(10) { 0f }
        val resampled64 = EqConstants.resampleCurve(
            EqConstants.FREQ_10_BAND,
            flat10,
            EqConstants.FREQ_64_BAND
        )
        assertEquals(64, resampled64.size)
        assertTrue(resampled64.all { abs(it) < 0.001f })

        val ramp10 = List(10) { idx -> -6f + idx * 1.5f }
        val resampled32 = EqConstants.resampleCurve(
            EqConstants.FREQ_10_BAND,
            ramp10,
            EqConstants.FREQ_32_BAND
        )
        assertEquals(32, resampled32.size)
        assertTrue(resampled32.first() <= resampled32.last())
    }

    @Test
    fun verifyEnhancerMathAndAutoHeadroomTrim() {
        val cfg = EnhancerConfig(
            ieqPreset = IeqPreset.BALANCED,
            ieqIntensity = 1.0f,
            dialogueEnhancerEnabled = true,
            dialogueGain = 4,
            dialogueMode = DialogueMode.BROADCAST,
            bassEnhancerEnabled = false,
            bassBoostLevel = 0,
            bassContour = BassContour.TIGHT_PUNCH
        )

        val speechOffset = EnhancerMath.computeTotalEnhancerOffsetDb(2400.0, cfg)
        assertTrue("Expected positive speech boost at 2.4 kHz", speechOffset > 2.0f)

        val baseHigh = List(10) { 12.0f }
        val (effective, trim) = EnhancerMath.computeCurveWithAutoHeadroom(
            EqConstants.FREQ_10_BAND,
            baseHigh,
            cfg,
            ceilingDb = 14.5f
        )
        assertEquals(10, effective.size)
        assertTrue("Expected positive auto-headroom trim when stacking high boosts", trim > 0f)
        assertTrue("Effective bands must stay within ceiling", effective.all { it <= 15.0f })
    }

    @Test
    fun verifyAutoEqAppliesAccuratelyAcrossBuiltInAndCustomProfiles() {
        // 1. Built-in headphone profiles must calculate non-zero 31-band correction curves
        val hd650 = EqConstants.BUILT_IN_HEADPHONE_PROFILES.find { it.id == "hd650" }
        assertTrue("HD 650 profile must exist in built-ins", hd650 != null)
        val hd650Curve = Dsp.computeHeadphoneCorrection31(
            profile = hd650,
            strength = 0.8,
            fullRange = false,
            tiltBassDb = 0.0,
            tiltTrebleDb = 0.0
        )
        assertEquals(31, hd650Curve.size)
        assertTrue("HD 650 correction must apply non-zero gains", hd650Curve.any { abs(it) > 0.5 })

        // 2. WH-1000XM5 built-in
        val xm5 = EqConstants.BUILT_IN_HEADPHONE_PROFILES.find { it.id == "wh1000xm5" }
        assertTrue("WH-1000XM5 profile must exist", xm5 != null)
        val xm5Curve = Dsp.computeHeadphoneCorrection31(xm5, strength = 1.0)
        assertEquals(31, xm5Curve.size)
        assertTrue("XM5 correction must apply non-zero gains", xm5Curve.any { abs(it) > 1.0 })

        // 3. Custom GraphicEQ profile without PEQ filters must calculate correction
        val geqProfile = HeadphoneProfile(
            id = "custom_geq",
            name = "Custom GraphicEQ",
            source = "AutoEq",
            rig = "HMS II.3",
            peqFilters = emptyList(),
            graphicPoints = listOf(
                20.0 to 3.0,
                100.0 to 2.5,
                1000.0 to 0.0,
                3000.0 to 4.0,
                10000.0 to 1.5
            ),
            correction31Db = List(31) { 0f }
        )
        val geqCurve = Dsp.computeHeadphoneCorrection31(geqProfile, strength = 1.0)
        assertEquals(31, geqCurve.size)
        assertTrue("GraphicEQ profile must produce non-zero correction", geqCurve.any { abs(it) > 0.5 })

        // 4. Precalculated 31-band profile must scale with strength
        val precalcCurve = List(31) { idx -> if (idx < 5) 4.0f else 0.0f }
        val precalcProfile = HeadphoneProfile(
            id = "custom_precalc",
            name = "Custom Precalc",
            source = "Measurement",
            rig = "711",
            correction31Db = precalcCurve
        )
        val appliedHalf = Dsp.computeHeadphoneCorrection31(precalcProfile, strength = 0.5)
        assertEquals(2.0, appliedHalf[0], 0.1)

        // 5. Off/None profile bypasses correction
        val noneProfile = EqConstants.BUILT_IN_HEADPHONE_PROFILES.find { it.id == "none" }
        val noneCurve = Dsp.computeHeadphoneCorrection31(noneProfile, strength = 1.0)
        assertTrue("None profile must produce all zeros", noneCurve.all { abs(it) < 0.001 })

        // 6. Tilt applies on top of headphone correction
        val tiltedCurve = Dsp.computeHeadphoneCorrection31(
            profile = hd650,
            strength = 0.8,
            tiltBassDb = 3.0,
            tiltTrebleDb = -2.0
        )
        assertTrue("Bass tilt must raise 31 Hz gain", tiltedCurve[2] > hd650Curve[2])
    }

    @Test
    fun verify20BandIeqTargetVectorsAndBuiltInPresets() {
        assertEquals(20, EnhancerMath.IEQ_FREQS_20.size)
        assertEquals(20, EnhancerDsp.IEQ_BALANCED_Q4.size)
        assertEquals(157, EnhancerDsp.IEQ_BALANCED_Q4.first())
        assertEquals(-378, EnhancerDsp.IEQ_BALANCED_Q4.last())

        assertEquals(114, EnhancerDsp.IEQ_WARM_Q4.first())
        assertEquals(-275, EnhancerDsp.IEQ_WARM_Q4.last())

        assertEquals(150, EnhancerDsp.IEQ_DETAILED_Q4.first())
        assertEquals(-217, EnhancerDsp.IEQ_DETAILED_Q4.last())

        val presetIds = EqConstants.BUILT_IN_PRESETS.map { it.id }
        assertFalse("bass_boost preset must be removed", "bass_boost" in presetIds)
        assertFalse("cinema preset must be removed", "cinema" in presetIds)
        assertEquals(10, EqConstants.BUILT_IN_PRESETS.size)
        for (preset in EqConstants.BUILT_IN_PRESETS) {
            assertEquals(10, preset.gains10Band.size)
        }
    }

    @Test
    fun verifyMidSideProcessorProcessesPcmCleanly() {
        val processor = MidSideProcessor(48000.0)
        processor.updateParameters(
            enabled = true,
            effective10BandDb = listOf(3f, 2f, 1f, 0f, -1f, 1f, 2f, 3f, 2f, 1f),
            preampDb = -1.5f,
            virtualizerPercent = 45,
            channelBalance = 0f,
            limiterThresholdDb = -0.8f,
            enhancerConfig = EnhancerConfig(
                ieqPreset = IeqPreset.BALANCED,
                dialogueEnhancerEnabled = true,
                dialogueGain = 4
            )
        )

        val pcm = ShortArray(256) { idx ->
            if (idx % 2 == 0) 8000.toShort() else (-6000).toShort()
        }
        processor.processInterleavedPcm16(pcm, pcm.size)
        assertTrue("Processed PCM must contain non-zero samples", pcm.any { it.toInt() != 0 })
    }

    @Test
    fun verifyAutoEqRbjBiquadTaperAndParsers() {
        val peqText = """
            Preamp: -5.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.8 dB Q 0.70
            Filter 2: ON PK Fc 1850 Hz Gain 2.4 dB Q 1.41
            Filter 3: ON HSC Fc 9500 Hz Gain -2.2 dB Q 0.71
        """.trimIndent()
        val parsed = AutoEqManager.parseParametricEq(peqText)
        assertEquals(-5.4, parsed.preamp, 0.001)
        assertEquals(3, parsed.filters.size)
        assertEquals("LSC", parsed.filters[0].type)
        assertEquals("PK", parsed.filters[1].type)
        assertEquals("HSC", parsed.filters[2].type)

        assertEquals(1.0, AutoEqManager.taper(10000.0), 1e-6)
        assertEquals(0.0, AutoEqManager.taper(14000.0), 1e-6)
        val midTaper = AutoEqManager.taper(12000.0)
        assertTrue(midTaper in 0.4..0.6)

        val (gains31Default, trimDefault) = AutoEqManager.peqToCorrection31WithTrim(
            filters = listOf(AutoEqManager.Flt("PK", 1000.0, 9.5, 1.0)),
            fullRange = false
        )
        assertEquals(31, gains31Default.size)
        assertTrue("Default cap is +6 dB", gains31Default.all { it <= 6.001f })
        assertTrue("Expected positive trim when 9.5 dB filter is capped at 6 dB", trimDefault > 3.0f)

        val (gains31Full, _) = AutoEqManager.peqToCorrection31WithTrim(
            filters = listOf(AutoEqManager.Flt("PK", 1000.0, 9.5, 1.0)),
            fullRange = true
        )
        assertTrue("Full range allows >6 dB boost", (gains31Full.maxOrNull() ?: 0f) > 8.5f)
        assertEquals("20 kHz band is zeroed by 14 kHz taper", 0.0f, gains31Full.last(), 0.001f)
    }

    @Test
    fun verifyDspV4EngineAndEnhancersSection15And17() {
        val gains = FloatArray(10) { idx -> if (idx < 3) 6.0f else 1.5f }
        val qs = FloatArray(10) { 1.41f }
        val autoPreamp = DspV4Engine.computeAutoPreamp(
            bandFreqs = DspV4Engine.STANDARD_10_BAND_HZ,
            bandGains = gains,
            bandQs = qs,
            bassGainDb = 4.0f,
            extraCeilingDb = -1.0f
        )
        assertTrue("Auto preamp should be negative to prevent clipping", autoPreamp < -5.0f)

        val smoothed = DspV4Engine.fractionalOctaveSmooth(gains.clone(), octaveFraction = 3)
        assertEquals(10, smoothed.size)

        val (dlgL, dlgR) = Enhancers.computeDialoguePreEqOffsets(
            bandFreqs = DspV4Engine.STANDARD_10_BAND_HZ,
            enabled = true,
            amount = 0.8f,
            virtualizerStrength = 500
        )
        assertEquals(10, dlgL.size)
        assertEquals(10, dlgR.size)
        assertTrue("1-4 kHz speech band should be boosted", dlgL[6] > 1.5f)
    }

    @Test
    fun verifyBassEnhancerStaysWithinSixDecibelsMaximum() {
        val maxBassCfg = EnhancerConfig(
            bassEnhancerEnabled = true,
            bassEnhancerStrength = 100,
            virtualBassEnabled = true
        )
        assertEquals(1000.toShort(), maxBassCfg.bassBoostStrengthPermille)
        assertEquals(6.0f, EnhancerMath.bassEnhancerBandBoostDb(0, 100, true), 0.001f)
        assertEquals(6.0f, EnhancerMath.bassEnhancerBandBoostDb(1, 100, true), 0.001f)
        assertEquals(6.0f, EnhancerMath.bassEnhancerBandBoostDb(2, 100, true), 0.001f)
        assertTrue(EnhancerMath.bassEnhancerBandBoostDb(3, 100, true) <= 6.0f)

        val bass31 = EnhancerDsp.computeBassEnhancer31(maxBassCfg)
        assertEquals(31, bass31.size)
        assertEquals(6.0, bass31.maxOrNull() ?: 0.0, 0.01)
        assertTrue("Bass Enhancer 31-band curve must never exceed 6.0 dB", bass31.all { it in 0.0..6.0 })
    }
}
