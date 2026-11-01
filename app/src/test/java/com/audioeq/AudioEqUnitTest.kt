package com.audioeq

import com.audioeq.audio.AudioProfile
import com.audioeq.audio.AutoEqManager
import com.audioeq.audio.BassContour
import com.audioeq.audio.DialogueMode
import com.audioeq.audio.DspV4Engine
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.EnhancerMath
import com.audioeq.audio.Enhancers
import com.audioeq.audio.IeqPreset
import com.audioeq.audio.MidSideProcessor
import com.audioeq.audio.StereoWideningMode
import com.audioeq.model.EqConstants
import com.audioeq.model.EqMode
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
            profile = AudioProfile.MOVIE,
            ieqPreset = IeqPreset.BALANCED,
            ieqIntensity = 1.0f,
            stereoWideningMode = StereoWideningMode.MIDDLE,
            reduceReverbAmount = 2,
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
    fun verifyStereoWideningAndReduceReverbSpecifications() {
        assertEquals(0, StereoWideningMode.NO_EFFECT.dapAmount)
        assertEquals(16, StereoWideningMode.NEAR.dapAmount)
        assertEquals(35, StereoWideningMode.MIDDLE.dapAmount)
        assertEquals(103, StereoWideningMode.FAR.dapAmount)

        assertEquals(1.000, EnhancerMath.computeStereoWidthScalar(0), 0.001)
        assertEquals(1.250, EnhancerMath.computeStereoWidthScalar(16), 0.001)
        assertEquals(1.546875, EnhancerMath.computeStereoWidthScalar(35), 0.001)
        assertEquals(2.609375, EnhancerMath.computeStereoWidthScalar(103), 0.001)

        assertEquals(0.00, EnhancerMath.computeStereoSideGainDb(0), 0.01)
        assertEquals(1.938, EnhancerMath.computeStereoSideGainDb(16), 0.02)
        assertEquals(3.789, EnhancerMath.computeStereoSideGainDb(35), 0.02)
        assertEquals(8.331, EnhancerMath.computeStereoSideGainDb(103), 0.02)

        assertEquals(0.00, EnhancerMath.reduceReverbGamma(0), 0.001)
        assertEquals(0.85, EnhancerMath.reduceReverbGamma(1), 0.001)
        assertEquals(1.20, EnhancerMath.reduceReverbGamma(2), 0.001)
        assertEquals(2.25, EnhancerMath.reduceReverbGamma(5), 0.001)
        assertEquals(3.65, EnhancerMath.reduceReverbGamma(9), 0.001)

        assertEquals(0.0, EnhancerMath.reduceReverbSuppressionFloorDb(0), 0.001)
        assertEquals(-12.0, EnhancerMath.reduceReverbSuppressionFloorDb(2), 0.001)
        assertEquals(-54.0, EnhancerMath.reduceReverbSuppressionFloorDb(9), 0.001)

        val wienerGainBypass = EnhancerMath.computeWienerReverbGain(0, 0.25, 1.0)
        assertEquals(1.0, wienerGainBypass, 1e-6)
        val wienerGainLv2 = EnhancerMath.computeWienerReverbGain(2, 0.25, 1.0)
        assertEquals(0.40, wienerGainLv2, 0.01)
    }

    @Test
    fun verify20BandIeqTargetVectorsAndBuiltInPresets() {
        assertEquals(20, EnhancerMath.IEQ_FREQS_20.size)
        assertEquals(20, EnhancerMath.IEQ_BALANCED_20_RAW.size)
        assertEquals(157, EnhancerMath.IEQ_BALANCED_20_RAW.first())
        assertEquals(-378, EnhancerMath.IEQ_BALANCED_20_RAW.last())

        assertEquals(114, EnhancerMath.IEQ_WARM_20_RAW.first())
        assertEquals(-275, EnhancerMath.IEQ_WARM_20_RAW.last())

        assertEquals(150, EnhancerMath.IEQ_DETAILED_20_RAW.first())
        assertEquals(-217, EnhancerMath.IEQ_DETAILED_20_RAW.last())

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
                profile = AudioProfile.MOVIE,
                ieqPreset = IeqPreset.BALANCED,
                stereoWideningMode = StereoWideningMode.FAR,
                reduceReverbAmount = 4,
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
    fun verifyProfilesDecoupledFromStereoWideningAndNoVolumeSuppression() {
        for (profile in listOf(AudioProfile.EFFECT_3D, AudioProfile.LIVE_MUSIC, AudioProfile.MOVIE, AudioProfile.DYNAMIC, AudioProfile.BALANCED)) {
            assertTrue("Profile $profile must have positive bass boost strength", profile.bassBoostStrengthPermille > 0)
            val cfg = EnhancerConfig(
                profile = profile,
                stereoWideningMode = StereoWideningMode.NO_EFFECT
            )
            assertEquals("Profile $profile must keep StereoWideningMode.NO_EFFECT untouched", StereoWideningMode.NO_EFFECT, cfg.stereoWideningMode)
            val (deltaL, deltaR) = com.audioeq.audio.EnhancerDsp.computeStereoWideningLRDelta31(cfg, 1.0f)
            assertTrue("Profile $profile with NO_EFFECT widening must have zero manual widening L delta", deltaL.all { abs(it) < 1e-6 })
            assertTrue("Profile $profile with NO_EFFECT widening must have zero manual widening R delta", deltaR.all { abs(it) < 1e-6 })

            val curve31 = com.audioeq.audio.EnhancerDsp.computeCombinedEnhancer31(cfg)
            assertTrue("Profile $profile must not cut any frequency band", curve31.all { it >= -0.01 })
            assertTrue("Profile $profile must provide strong audible positive enhancement", (curve31.maxOrNull() ?: 0.0) > 2.5)
        }

        for (spatialProfile in listOf(AudioProfile.EFFECT_3D, AudioProfile.LIVE_MUSIC, AudioProfile.MOVIE)) {
            assertTrue("Spatial profile $spatialProfile must enable hardware virtualizer", spatialProfile.virtualizerStrengthPermille >= 650)
            val (profL, profR) = com.audioeq.audio.EnhancerDsp.computeProfileSpatialLRDelta31(spatialProfile)
            assertTrue("Spatial profile $spatialProfile must produce dichotic L/R spatial stage", profL.indices.any { abs(profL[it] - profR[it]) > 1.0 })
            assertTrue("Spatial profile $spatialProfile must have positive energy compensation", (profL.sum() + profR.sum()) > 0.0)
        }

        for (mode in listOf(StereoWideningMode.NEAR, StereoWideningMode.MIDDLE, StereoWideningMode.FAR)) {
            val cfg = EnhancerConfig(
                profile = AudioProfile.OFF,
                stereoWideningMode = mode
            )
            val (deltaL, deltaR) = com.audioeq.audio.EnhancerDsp.computeStereoWideningLRDelta31(cfg, 1.0f)
            assertTrue("Widening mode $mode must produce dichotic L/R differences", deltaL.indices.any { abs(deltaL[it] - deltaR[it]) > 1.0 })
            assertTrue("Widening mode $mode must have positive average gain compensation", (deltaL.sum() + deltaR.sum()) > 0.0)
        }
    }
}
