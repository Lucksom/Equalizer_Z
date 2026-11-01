package com.audioeq

import com.audioeq.audio.AudioProfile
import com.audioeq.audio.AutoEqManager
import com.audioeq.audio.BassContour
import com.audioeq.audio.DialogueMode
import com.audioeq.audio.DspV4Engine
import com.audioeq.audio.EnhancerConfig
import com.audioeq.audio.EnhancerDsp
import com.audioeq.audio.EnhancerMath
import com.audioeq.audio.Enhancers
import com.audioeq.audio.IeqPreset
import com.audioeq.audio.MidSideProcessor
import com.audioeq.audio.ThreeDAudioMode
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
            profile = AudioProfile.DYNAMIC,
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
    fun verifyThreeDAudioDynamicProfileAndBalancedIeqSpecifications() {
        // Verify Live Music and Movie profiles are removed; only Off, Dynamic, Balanced, 3D exist
        val profileIds = AudioProfile.entries.map { it.id }
        assertEquals(listOf("profile_off", "profile_dynamic", "profile_balanced", "profile_3d"), profileIds)

        // 1. 3D Audio (Spatializer & Virtualizer Engine) Spec
        assertEquals(0, ThreeDAudioMode.NO_EFFECT.azimuthDeg)
        assertEquals(16, ThreeDAudioMode.NEAR.azimuthDeg)
        assertEquals(35, ThreeDAudioMode.MIDDLE.azimuthDeg)
        assertEquals(103, ThreeDAudioMode.FAR.azimuthDeg)

        assertEquals(0, ThreeDAudioMode.NO_EFFECT.itdDelaySamples48k)
        assertEquals(7, ThreeDAudioMode.NEAR.itdDelaySamples48k)
        assertEquals(14, ThreeDAudioMode.MIDDLE.itdDelaySamples48k)
        assertEquals(34, ThreeDAudioMode.FAR.itdDelaySamples48k)

        assertEquals(7, EnhancerMath.computeWoodworthItdSamples(16, 48000))
        assertEquals(0.141, EnhancerMath.computeWoodworthItdMs(16), 0.002)
        assertEquals(0.302, EnhancerMath.computeWoodworthItdMs(35), 0.002)
        assertEquals(34, EnhancerMath.computeWoodworthItdSamples(103, 48000))
        assertEquals(0.707, EnhancerMath.computeWoodworthItdMs(103), 0.002)

        assertEquals(0.9939, EnhancerMath.decodeQ15Linear(32568), 0.0002)
        assertEquals(0.2500, EnhancerMath.decodeQ15Linear(8192), 0.0002)
        assertEquals(0.3407, EnhancerMath.decodeQ15Linear(11164), 0.0002)
        assertEquals(0.1553, EnhancerMath.decodeQ15Linear(5090), 0.0002)

        val shadowBeta = EnhancerMath.computeCranialShadowBeta(850.0, 48000)
        assertTrue("850 Hz cranial shadow beta at 48 kHz should be ~0.105", shadowBeta in 0.09..0.12)

        // 2. Dynamic Profile (id="0") Media Intelligence Steering Spec
        assertEquals(4, EnhancerDsp.DYNAMIC_PROFILE_SPECS.size)
        assertEquals(0.0, EnhancerMath.computeDynamicMiVirtualizerGain(0.1, 0.1, 0.8), 1e-6)
        assertEquals(1.0, EnhancerMath.computeDynamicMiVirtualizerGain(0.7, 0.2, 0.1), 1e-6)
        assertEquals(0.35, EnhancerMath.computeDynamicMiVirtualizerGain(0.0, 1.0, 0.0), 1e-6)

        val (speechAtt, speechRel) = EnhancerMath.computeDynamicMiLevelerTimeConstantsMs(1.0)
        assertEquals(20.0, speechAtt, 1e-6)
        assertEquals(200.0, speechRel, 1e-6)
        val (musicAtt, musicRel) = EnhancerMath.computeDynamicMiLevelerTimeConstantsMs(0.0)
        assertEquals(150.0, musicAtt, 1e-6)
        assertEquals(800.0, musicRel, 1e-6)

        assertEquals(1.0, EnhancerMath.computeDynamicMiSurroundCompressorRatio(0.0), 1e-6)
        assertEquals(2.5, EnhancerMath.computeDynamicMiSurroundCompressorRatio(1.0), 1e-6)

        // 3. Balance (Balanced IEQ Profile) Spec
        assertEquals(20, EnhancerDsp.BALANCED_IEQ_CURVE_SPECS.size)
        assertEquals(9.81, EnhancerDsp.BALANCED_IEQ_CURVE_SPECS.first().targetGainDb, 0.01)
        assertEquals(13.63, EnhancerDsp.BALANCED_IEQ_CURVE_SPECS[2].targetGainDb, 0.01)
        assertEquals(-23.63, EnhancerDsp.BALANCED_IEQ_CURVE_SPECS.last().targetGainDb, 0.01)
        assertEquals(0.375, EnhancerDsp.BALANCED_ADAPTATION_FACTOR_HP, 1e-6)
        assertEquals(0.250, EnhancerDsp.BALANCED_ADAPTATION_FACTOR_SPK, 1e-6)

        // Steering gain clamp +-6.0 dB
        val hpGain = EnhancerMath.computeBalancedIeqSteeringGainDb(0, 0.0, isSpeaker = false)
        assertEquals(0.375 * (157.0 / 16.0), hpGain, 0.01)
        val spkGain = EnhancerMath.computeBalancedIeqSteeringGainDb(0, 0.0, isSpeaker = true)
        assertEquals(0.250 * (157.0 / 16.0), spkGain, 0.01)
        val clampedHigh = EnhancerMath.computeBalancedIeqSteeringGainDb(2, -20.0, isSpeaker = false)
        assertEquals(6.0, clampedHigh, 1e-6)
        val clampedLow = EnhancerMath.computeBalancedIeqSteeringGainDb(19, 10.0, isSpeaker = false)
        assertEquals(-6.0, clampedLow, 1e-6)
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
                profile = AudioProfile.EFFECT_3D,
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
    fun verifyRemainingProfilesHavePositiveEnhancementWithoutVolumeSuppression() {
        for (profile in listOf(AudioProfile.DYNAMIC, AudioProfile.BALANCED, AudioProfile.EFFECT_3D)) {
            assertTrue("Profile $profile must have positive bass boost strength", profile.bassBoostStrengthPermille > 0)
            val cfg = EnhancerConfig(profile = profile)
            val curve31 = EnhancerDsp.computeCombinedEnhancer31(cfg)
            assertTrue("Profile $profile must not cut any frequency band", curve31.all { it >= -0.01 })
            assertTrue("Profile $profile must provide strong audible positive enhancement", (curve31.maxOrNull() ?: 0.0) > 2.5)
        }

        val (profL3D, profR3D) = EnhancerDsp.computeProfileSpatialLRDelta31(AudioProfile.EFFECT_3D)
        assertTrue("3D profile must produce dichotic L/R spatial stage", profL3D.indices.any { abs(profL3D[it] - profR3D[it]) > 1.0 })
        assertTrue("3D profile must have positive energy compensation", (profL3D.sum() + profR3D.sum()) > 0.0)

        // Verify Bass Enhancer maximum boost is strictly 6.0 dB
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
