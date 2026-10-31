package com.audioeq

import com.audioeq.audio.AutoEq
import com.audioeq.audio.BiquadFilter
import com.audioeq.audio.Dsp
import com.audioeq.audio.FastRender
import com.audioeq.audio.Layers
import com.audioeq.audio.MidSideProcessor
import com.audioeq.audio.PeqFilter
import com.audioeq.model.EqConstants
import com.audioeq.model.FilterType
import com.audioeq.model.OutputRoute
import com.audioeq.model.SectionWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class AudioEqUnitTest {

    @Test
    fun v5Presets_matchMasterSpecification() {
        val presets = EqConstants.BUILT_IN_PRESETS.associateBy { it.id }
        assertEquals(12, presets.size)

        // Flat (bypass = true)
        val flat = presets.getValue("flat")
        assertTrue(flat.bypassChain)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), flat.gainsDb)

        // Balanced Deep: +3.5, +4.5, +2.5, -2.5, -1.5, 0, +1, +2.5, +2.5, +1.5
        val balanced = presets.getValue("balanced_deep")
        assertEquals(listOf(3.5f, 4.5f, 2.5f, -2.5f, -1.5f, 0f, 1f, 2.5f, 2.5f, 1.5f), balanced.gainsDb)
        assertEquals(20f, balanced.highPassHz, 0.01f)
        assertEquals(-12f, balanced.bassHarmonicMixDb ?: 0f, 0.01f)

        // Bass Boost: +5.5, +6.5, +4, -2, -1.5, -0.5, 0, 0, +0.5, +0.5
        val bassBoost = presets.getValue("bass_boost")
        assertEquals(listOf(5.5f, 6.5f, 4f, -2f, -1.5f, -0.5f, 0f, 0f, 0.5f, 0.5f), bassBoost.gainsDb)
        assertEquals(25f, bassBoost.highPassHz, 0.01f)
        assertEquals(-9f, bassBoost.bassHarmonicMixDb ?: 0f, 0.01f)

        // Vocal Clarity: -3.5, -2.5, -1, -3, -1.5, +1.5, +3.5, +3.5, +1.5, +0.5
        val vocal = presets.getValue("vocal_clarity")
        assertEquals(listOf(-3.5f, -2.5f, -1f, -3f, -1.5f, 1.5f, 3.5f, 3.5f, 1.5f, 0.5f), vocal.gainsDb)
        assertEquals(60f, vocal.highPassHz, 0.01f)
        assertEquals(null, vocal.bassHarmonicMixDb)

        // Percussion Clarity: -4, +1.5, +2.5, -3.5, -2, 0, +1.5, +3.5, +3, +1.5
        val percussion = presets.getValue("percussion_clarity")
        assertEquals(listOf(-4f, 1.5f, 2.5f, -3.5f, -2f, 0f, 1.5f, 3.5f, 3f, 1.5f), percussion.gainsDb)
        assertEquals(40f, percussion.highPassHz, 0.01f)
        assertEquals(-15f, percussion.bassHarmonicMixDb ?: 0f, 0.01f)
        assertEquals(1.0f, percussion.qOverrides[250] ?: 0f, 0.01f)
        assertEquals(2.0f, percussion.qOverrides[4000] ?: 0f, 0.01f)

        // Cinema: +4, +4, +2, -2, -1, +0.5, +2.5, +2.5, +2, +2
        val cinema = presets.getValue("cinema")
        assertEquals(listOf(4f, 4f, 2f, -2f, -1f, 0.5f, 2.5f, 2.5f, 2f, 2f), cinema.gainsDb)
        assertEquals(20f, cinema.highPassHz, 0.01f)
        assertEquals(-12f, cinema.bassHarmonicMixDb ?: 0f, 0.01f)

        // Meizu Square Sound Presets (Classical, Jazz, Rock, Pop, Pure Voice, Power Bass)
        assertEquals(
            listOf(0f, 0f, 0f, 0f, 0f, 0f, -5f, -5f, -5f, -8f),
            presets.getValue("classical").gainsDb
        )
        assertEquals(
            listOf(0f, 0f, 0f, 5f, 5f, 5f, 0f, 2f, 3f, 5f),
            presets.getValue("jazz").gainsDb
        )
        assertEquals(
            listOf(3f, 2f, 3f, 3f, -2f, 2f, 3f, 5f, 6f, 6f),
            presets.getValue("rock").gainsDb
        )
        assertEquals(
            listOf(2f, 3f, 0f, -3f, -6f, -5f, -3f, 0f, 2f, -2f),
            presets.getValue("pop").gainsDb
        )
        assertEquals(
            listOf(0f, 0f, 0f, 0f, -2f, 2f, 5f, 5f, 5f, 3f),
            presets.getValue("pure_voice").gainsDb
        )
        assertEquals(
            listOf(9f, 8f, 6f, 4f, 0f, 0f, 0f, 0f, 0f, 0f),
            presets.getValue("power_bass").gainsDb
        )
    }

    @Test
    fun dspRenderer31BandAndMbcCutoffs_matchV5Spec() {
        assertEquals(31, Dsp.RENDER_HZ.size)
        assertEquals(20.0, Dsp.RENDER_HZ[0], 0.01)
        assertEquals(6, Dsp.MBC_CUT.size)
        // Upper edges of render bands 6, 12, 18, 24, 27 (~90, 359, 1437, 5747, 11494, 20000 Hz)
        assertEquals(90f, Dsp.MBC_CUT[0], 1.5f)
        assertEquals(359f, Dsp.MBC_CUT[1], 2.0f)
        assertEquals(1437f, Dsp.MBC_CUT[2], 5.0f)
        assertEquals(5747f, Dsp.MBC_CUT[3], 15.0f)
        assertEquals(11494f, Dsp.MBC_CUT[4], 30.0f)
        assertEquals(20000f, Dsp.MBC_CUT[5], 1.0f)

        // PCHIP flat curve renders all zeros
        val flat31 = Dsp.renderCurve(DoubleArray(10) { 0.0 })
        assertTrue(flat31.all { abs(it) < 1e-6 })

        // Speaker route offsets keep low frequencies at 0.0 dB so bass is never suppressed
        assertEquals(0.0, OutputRoute.SPEAKER.offsetsDb[0], 0.01)
        assertEquals(0.0, OutputRoute.SPEAKER.offsetsDb[1], 0.01)
        assertEquals(1.5, OutputRoute.SPEAKER.offsetsDb[3], 0.01)
    }

    @Test
    fun autoEqGoldenVectorA_matchesReferenceWithin005dB() {
        // Section 16.7 Golden Vector A (ParametricEQ at 44.1 kHz, strength 0.8)
        val sample = """
            Preamp: -4.70 dB
            Filter 1: ON LSC Fc 105.0 Hz Gain -3.2 dB Q 0.70
            Filter 2: ON PK Fc 174.8 Hz Gain -4.3 dB Q 0.93
            Filter 3: ON PK Fc 718.6 Hz Gain 2.4 dB Q 1.85
            Filter 4: ON PK Fc 3464.3 Hz Gain 4.5 dB Q 1.99
            Filter 5: ON HSC Fc 10000.0 Hz Gain 3.4 dB Q 0.70
            Filter 6: OFF PK Fc 0 Hz Gain 0.0 dB Q 0.00
        """.trimIndent()

        val parsed = AutoEq.parsePeq(sample)
        assertEquals(-4.70, parsed.preampDb, 0.001)
        assertEquals(5, parsed.filters.size) // Disabled slot 6 ignored

        val expected = doubleArrayOf(
            -2.609, -2.635, -2.672, -2.722, -2.778, -2.828, -2.864, -2.965, -3.315, -3.731, -3.341,
            -2.263, -1.290, -0.552,  0.194,  1.324,  1.473,  0.674,  0.396,  0.403,  0.653,  1.437,
             3.337,  2.528,  1.056,  0.682,  0.876,  1.430,  0.560,  0.000,  0.000
        )

        val actual = AutoEq.peqToCorrection31(parsed.filters, strength = 0.8, fs = 44100.0)
        assertEquals(31, actual.size)
        for (i in 0 until 31) {
            assertEquals("Mismatch at band $i (${Dsp.RENDER_HZ[i]} Hz)", expected[i], actual[i], 0.05)
        }

        val maxCorr = actual.maxOrNull() ?: 0.0
        assertEquals(3.337, maxCorr, 0.01)
        val extraInputCut = max(0.0, maxCorr - 3.0)
        assertEquals(0.337, extraInputCut, 0.01)

        // Verify 44.1 kHz vs 48 kHz difference is under 0.06 dB
        for (i in 0 until 31) {
            val f = Dsp.RENDER_HZ[i]
            val r44 = parsed.filters.sumOf { AutoEq.biquadDb(it, f, 44100.0) }
            val r48 = parsed.filters.sumOf { AutoEq.biquadDb(it, f, 48000.0) }
            assertTrue("44.1 vs 48k diff at $f Hz was ${abs(r44 - r48)}", abs(r44 - r48) < 0.06)
        }
    }

    @Test
    fun autoEqGoldenVectorB_graphicEqMatchesReferenceWithin005dB() {
        // Section 16.7 Golden Vector B (GraphicEQ, strength 0.8)
        val gLine = "GraphicEQ: 20 -0.5; 25 -0.5; 32 -0.9; 40 -2.1; 63 -3.6; 100 -5.0; 125 -1.9; 250 -2.5; 500 -1.0; 1000 -0.5; 2000 -0.4; 4000 2.0; 8000 -3.0; 16000 -6.0; 20000 -6.0"
        val pts = AutoEq.parseGraphic(gLine)
        assertEquals(15, pts.size)

        val expected = doubleArrayOf(
             0.115,  0.104, -0.195, -1.165, -1.776, -2.384, -2.945, -3.398, -1.016, -1.176, -1.336,
            -1.458, -1.058, -0.658, -0.276, -0.143, -0.010,  0.116,  0.143,  0.170,  0.238,  0.878,
             1.518,  2.023,  0.690, -0.643, -1.940, -2.547, -0.860,  0.000,  0.000
        )

        val actual = AutoEq.graphicToCorrection31(pts, strength = 0.8)
        assertEquals(31, actual.size)
        for (i in 0 until 31) {
            assertEquals("GraphicEQ mismatch at band $i", expected[i], actual[i], 0.05)
        }
    }

    @Test
    fun autoEqGoldenVectorC_indexParserAndSecurityValidation() {
        // Section 16.7 Golden Vector C + Section 19 malformed input tests
        val indexSample = """
            # heading line
            - [Sennheiser HD 650](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650) by crinacle on GRAS 43AG-7
            - [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990
            - [Sony WH-1000XM4 (ANC)](./oratory1990/over-ear/Sony%20WH-1000XM4%20(ANC)) by oratory1990
            - [Odd name](./rtings/in-ear/Odd%20name)
            - [Evil traversal](./../etc/passwd) by hacker
            - [Evil scheme](./https://evil.com/test) by hacker
        """.trimIndent()

        val entries = AutoEq.parseIndex(indexSample)
        assertEquals(4, entries.size)

        assertEquals("Sennheiser HD 650", entries[0].name)
        assertEquals("crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650", entries[0].path)
        assertEquals("crinacle", entries[0].source)
        assertEquals("GRAS 43AG-7", entries[0].rig)

        assertEquals("Sennheiser HD 800", entries[1].name)
        assertEquals("oratory1990/over-ear/Sennheiser%20HD%20800", entries[1].path)
        assertEquals("oratory1990", entries[1].source)
        assertNull(entries[1].rig)

        assertEquals("Sony WH-1000XM4 (ANC)", entries[2].name)
        assertEquals("oratory1990/over-ear/Sony%20WH-1000XM4%20(ANC)", entries[2].path)
        assertEquals("oratory1990", entries[2].source)
        assertNull(entries[2].rig)

        assertEquals("Odd name", entries[3].name)
        assertEquals("rtings/in-ear/Odd%20name", entries[3].path)
        assertNull(entries[3].source)
        assertNull(entries[3].rig)
    }

    @Test
    fun autoEqStrengthZeroMalformAndTrimNotice_passSection19() {
        val filters = listOf(PeqFilter("LSC", 80.0, 9.5, 0.7))
        // Strength 0 must produce identical to no correction (all zeros)
        val zeroCorr = AutoEq.peqToCorrection31(filters, strength = 0.0)
        assertTrue(zeroCorr.all { abs(it) < 1e-9 })

        // Trim notice: +9.5 dB shelf exceeds +6 dB cap -> trimmedDb > 1.0 dB
        val trimmedDefault = AutoEq.computePeqTrimmedDb(filters, fullRange = false)
        assertTrue("Expected trimmedDb > 1.0 but got $trimmedDefault", trimmedDefault > 1.0)

        // Full range raises boost cap to +10 dB -> trimmedDb drops to ~0
        val trimmedFullRange = AutoEq.computePeqTrimmedDb(filters, fullRange = true)
        assertTrue(trimmedFullRange < trimmedDefault)

        // Malformed PEQ lines are safely ignored
        val malformed = """
            Filter 1: ON PK Fc 1000 Gain 3.0 dB Q 1.0
            Filter 2: ON PK Fc 1000 Hz Gain 3.0 dB Q 0.0
            Filter 3: ON PK Fc 99999 Hz Gain 3.0 dB Q 1.0
            Filter 4: ON PK Fc 1000 Hz Gain 45.0 dB Q 1.0
            Filter 5: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.4
        """.trimIndent()
        val parsedMalformed = AutoEq.parsePeq(malformed)
        assertEquals(1, parsedMalformed.filters.size)

        // Legacy Tier 1 band-averaging (Section 16.4 & 19)
        val highCurve = DoubleArray(31) { 6.0 }
        val ranges = listOf(
            intArrayOf(30_000, 120_000),
            intArrayOf(120_001, 460_000),
            intArrayOf(460_001, 1_800_000),
            intArrayOf(1_800_001, 7_000_000),
            intArrayOf(7_000_001, 20_000_000)
        )
        val nativeMapped = AutoEq.map31ToNativeBands(highCurve, ranges)
        assertEquals(5, nativeMapped.size)
        // Must be capped at +4.0 dB
        assertTrue(nativeMapped.all { abs(it - 4.0f) < 0.001f })
    }

    @Test
    fun volumeContourAndPinkGain_behaveCorrectly() {
        val cHigh = Dsp.contour(0.75)
        assertTrue(cHigh.all { abs(it) < 0.001 })

        val cLow = Dsp.contour(0.1)
        assertEquals(4.5, cLow[0], 0.01)
        assertEquals(2.0, cLow[9], 0.01)

        val zeroGain = Dsp.pinkGainDb(DoubleArray(31) { 0.0 })
        assertEquals(0.0, zeroGain, 0.01)

        // Independent L/R Channels in Layers (Section 17.2 F4)
        val layers = Layers(
            userDb = DoubleArray(10) { 2.0 },
            intensity = 1.0,
            routeDb = DoubleArray(10),
            contourDb = DoubleArray(10),
            rightUserDb = DoubleArray(10) { -2.0 },
            balanceDb = 1.5f
        )
        assertTrue(layers.hasIndependentChannels())
        val effL = Dsp.computeEffective31(layers, channel = 0)
        val effR = Dsp.computeEffective31(layers, channel = 1)
        assertEquals(2.0, effL[15], 0.1)
        assertEquals(-2.0, effR[15], 0.1)
    }

    @Test
    fun biquadAndMidSideProcessor_processPercussionClarity() {
        val filter = BiquadFilter().apply {
            configure(
                type = FilterType.PEAKING,
                frequencyHz = 4000.0,
                sampleRate = 48000.0,
                gainDb = 3.5,
                q = 2.0
            )
        }
        val magAt4k = filter.magnitudeDbAt(4000.0, 48000.0)
        assertEquals(3.5, magAt4k, 0.15)

        val processor = MidSideProcessor(sampleRate = 48000.0)
        val percussion = EqConstants.BUILT_IN_PRESETS.first { it.id == "percussion_clarity" }
        processor.updateParameters(
            gainsDb = percussion.gainsDb,
            preampDb = 0.0f,
            makeupDb = 1.5f,
            highPassHz = percussion.highPassHz,
            bassHarmonicMixDb = percussion.bassHarmonicMixDb,
            limiterThresholdDb = -0.5f,
            widths = SectionWidths(),
            qOverrides = percussion.qOverrides,
            boseProfile = com.audioeq.audio.BoseProfile.EFFECT_3D,
            bypassed = false,
            abCompare = false
        )

        val pcm = ShortArray(512) { i -> (sin((i / 2) * 0.15) * 16000.0).toInt().toShort() }
        processor.processStereoPcm16(pcm, 256)
        assertTrue(pcm.any { it.toInt() != 0 })
    }

    @Test
    fun fastRender_matchesFullPchipRenderCurve() {
        val rng = Random(7)

        // FastRender vs Dsp.renderCurve across 2000 random 10-band vectors (< 1e-9 dB)
        repeat(2000) {
            val y10 = DoubleArray(10) { rng.nextDouble(-12.0, 12.0) }
            val fast = FastRender.renderToNewArray(y10)
            val full = Dsp.renderCurve(y10)
            for (i in 0 until 31) {
                val diff = abs(fast[i] - full[i])
                assertTrue("FastRender mismatch at band $i: $diff", diff < 1e-9)
            }
        }
    }

    @Test
    fun v6MasterEqEngine_matchesDaxAndMathSpec() {
        // 1. Verify 20-Band Bark/ERB frequencies from dax-default.xml
        assertEquals(20, com.audioeq.audio.EqMath.DAX_20_HZ.size)
        assertEquals(47.0, com.audioeq.audio.EqMath.DAX_20_HZ[0], 0.01)
        assertEquals(19688.0, com.audioeq.audio.EqMath.DAX_20_HZ[19], 0.01)

        // 2. Verify 10-to-20 Band Quarter-dB Quantization & Midpoint Mapping (GeqView.java)
        val sliders10 = doubleArrayOf(3.5, 4.5, 2.5, -2.5, -1.5, 0.0, 1.0, 2.5, 2.5, 1.5)
        val dax20Int = com.audioeq.audio.EqMath.sliders10ToDax20Int(sliders10)
        assertEquals(20, dax20Int.size)
        assertEquals(56, dax20Int[0])  // 3.5 * 16 = 56
        assertEquals(64, dax20Int[1])  // midpoint(3.5, 4.5) = 4.0 * 16 = 64
        assertEquals(72, dax20Int[2])  // 4.5 * 16 = 72
        assertEquals(24, dax20Int[18]) // 1.5 * 16 = 24
        assertEquals(24, dax20Int[19]) // duplicated band 18 -> 24

        // 3. Verify 96-byte Little-Endian OEM DAX packet layout
        val pkt96 = com.audioeq.audio.OemDaxBridge.buildGeq96BytePacket(dax20Int)
        assertEquals(96, pkt96.size)
        val pkt12 = com.audioeq.audio.OemDaxBridge.buildProfile12BytePacket(2)
        assertEquals(12, pkt12.size)

        // 4. Verify 7-Band Cascaded High-Shelf Biquad Math (EqualizerView.java)
        val flat7 = DoubleArray(7) { 0.0 }
        assertEquals(0.0, com.audioeq.audio.EqMath.evaluateCascaded7BandDb(flat7, 1000.0), 0.01)

        // 5. Verify Personal Hearing Compensation (CustomizedSoundModel.java: INIT=-42, DIV=3)
        val zeroEar = listOf(0f, 0f, 0f, 0f, 0f, 0f)
        val steps = com.audioeq.audio.EqMath.earDbToCustomizedSoundSteps(zeroEar)
        assertTrue(steps.all { it == 14 }) // (0 - (-42)) / 3 = 14

        // 6. Verify 4 Acoustic Profiles (Dynamic, Movie, Music, Voice)
        assertEquals(4, com.audioeq.audio.DaxProfile.entries.size)
        assertEquals(0.75, com.audioeq.audio.DaxProfile.MOVIE.ieqAmountSpk, 0.001)
        assertEquals(6, com.audioeq.audio.DaxProfile.VOICE.dialogSteps)
    }

    @Test
    fun dolbyBoseNonRootDsp_matchesSpecification() {
        // 1. Q4 fixed-point conversion: dB = raw / 16.0
        assertEquals(7.4375f, com.audioeq.audio.DolbyBoseDspMath.q4RawToDb(119), 0.0001f)

        // 2. Intelligent Equalizer (IEQ) 4 subsets: Detailed, Balanced, Warm, Custom
        val ieqSubsets = com.audioeq.audio.IeqPreset.entries.filter { it != com.audioeq.audio.IeqPreset.OFF }
        assertEquals(
            listOf("Detailed", "Balanced", "Warm", "Custom"),
            ieqSubsets.map { it.title }
        )
        assertEquals(3.63f, com.audioeq.audio.DolbyBoseDspMath.IEQ_DETAILED_10[4], 0.01f)
        assertEquals(1.63f, com.audioeq.audio.DolbyBoseDspMath.IEQ_BALANCED_10[2], 0.01f)
        assertEquals(1.50f, com.audioeq.audio.DolbyBoseDspMath.IEQ_WARM_10[5], 0.01f)

        // 3. Bose Profiles (Off, Dynamic, Balanced, 3D, Live Music)
        assertEquals(
            listOf("Off", "Dynamic", "Balanced", "3D", "Live Music"),
            com.audioeq.audio.BoseProfile.entries.map { it.title }
        )
        assertEquals(4.0f, com.audioeq.audio.DolbyBoseDspMath.BOSE_DYNAMIC_10[0], 0.01f)
        assertEquals(4.0f, com.audioeq.audio.DolbyBoseDspMath.BOSE_DYNAMIC_10[9], 0.01f)
        assertEquals(-3.0f, com.audioeq.audio.DolbyBoseDspMath.BOSE_BALANCED_10[5], 0.01f)

        // 4. Bass Enhancer (with slider & Virtual Bass Harmonic Synthesis) + Dialogue Enhancer
        val cfg = com.audioeq.audio.DolbyBoseDspConfig(
            ieqPreset = com.audioeq.audio.IeqPreset.DETAILED,
            ieqAmount = 10,
            dialogueEnhancerEnabled = true,
            dialogueAmount = 16,
            dialogueDucking = 2,
            bassEnhancerEnabled = true,
            bassEnhancerStrength = 80,
            virtualBassEnabled = true,
            boseProfile = com.audioeq.audio.BoseProfile.DYNAMIC
        )
        val composite = com.audioeq.audio.DolbyBoseDspMath.computeComposite10BandDeltaDb(cfg)
        assertEquals(10, composite.size)
        // Band 1 (63 Hz) at 80% Bass Enhancer (+6.40 dB): Bose Dynamic (+1.0) + IEQ Detailed (-4.20 * 10/16 = -2.625) + Bass Enhancer (+6.40) - Dialogue Ducking (-2.0) = +2.775
        assertEquals(2.775, composite[1], 0.02)
        // Band 6 (2 kHz): Bose Dynamic (0.0) + IEQ Detailed (-0.44 * 10/16 = -0.275) + Dialogue Enhancer (+6.25) = +5.975
        assertEquals(5.975, composite[6], 0.02)

        // At 100% Bass Enhancer strength (Power Bass 1000/1000), band 0 = +9.0 dB, band 1 = +8.0 dB
        assertEquals(9.0f, com.audioeq.audio.DolbyBoseDspMath.bassEnhancerBandBoostDb(0, 100, true), 0.01f)
        assertEquals(8.0f, com.audioeq.audio.DolbyBoseDspMath.bassEnhancerBandBoostDb(1, 100, true), 0.01f)

        // 5. Volume-Preserving Headroom: never attenuates global input gain when bass increases
        val maxPos = composite.maxOf { it.toFloat() }
        val headroom = com.audioeq.audio.DolbyBoseDspMath.computeSafeHeadroomDb(maxPos)
        assertEquals(0.0f, headroom, 0.001f)
    }

    @Test
    fun squareSoundMeizuAndIndependentPower_matchSpecification() {
        // 1. Meizu 20-char preset decoding: G_dB[k] = parseInt(payload.substring(2k, 2k+2)) - 10
        assertEquals(
            listOf(0f, 0f, 0f, 0f, 0f, 0f, -4f, -4f, -4f, -6f),
            com.audioeq.audio.DolbyBoseDspMath.decodeMeizuPresetPayload("10101010101006060604")
        )
        assertEquals(
            listOf(3f, 3f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 4f),
            com.audioeq.audio.DolbyBoseDspMath.decodeMeizuPresetPayload("13131112090910111214")
        )
        assertEquals(
            listOf(4f, 3f, -2f, -4f, -1f, 2f, 5f, 6f, 6f, 6f),
            com.audioeq.audio.DolbyBoseDspMath.decodeMeizuPresetPayload("14130806091215161616")
        )
        assertEquals(
            listOf(-1f, 2f, 4f, 4f, 3f, 0f, -1f, -1f, -1f, -1f),
            com.audioeq.audio.DolbyBoseDspMath.decodeMeizuPresetPayload("09121414131009090909")
        )

        // 2. Woodworth Spherical Head ITD for 3D Effect (r = 0.0875 m, c = 343 m/s, theta = 35 deg)
        val itdSec = com.audioeq.audio.DolbyBoseDspMath.computeWoodworthItdSeconds()
        assertEquals(0.0003022, itdSec, 0.000001)
        val delaySamples48k = com.audioeq.audio.DolbyBoseDspMath.computeWoodworthDelaySamples(48000.0)
        assertEquals(15, delaySamples48k)

        // 3. 26-Byte Little-Endian EnvironmentalReverbMz Live Music binary packet
        val props = com.audioeq.audio.MeizuLiveMusicReverbProperties()
        val packet26 = props.to26ByteLittleEndianPacket()
        assertEquals(26, packet26.size)
        val decodedProps = com.audioeq.audio.MeizuLiveMusicReverbProperties.from26ByteLittleEndianPacket(packet26)
        assertEquals(props, decodedProps)

        // 4. Independent Power Controls:
        //    - Top button (masterEnabled = false) disables EVERYTHING (Equalizer + Tone + Enhancers)
        //    - Equalizer button (isEnabled = false, masterEnabled = true) disables ONLY Equalizer while Tone & Enhancers stay active!
        val stateBothOn = com.audioeq.ui.EqUiState(
            masterEnabled = true,
            isEnabled = true,
            gainsDb = List(10) { 4.0f },
            tiltBassDb = 3.0f,
            volumeContourEnabled = false,
            dolbyBoseConfig = com.audioeq.audio.DolbyBoseDspConfig(
                boseProfile = com.audioeq.audio.BoseProfile.DYNAMIC
            )
        )
        // Band 0 when both on: 4.0 (EQ) + 3.0 (Tilt) + 4.0 (Bose Dynamic) = 11.0 dB
        assertEquals(11.0f, stateBothOn.effectiveGainDb(0), 0.05f)

        // Equalizer off, Master on: EQ (4.0) is off, but Tilt (3.0) + Bose Dynamic (4.0) = 7.0 dB still active!
        val stateEqOffOnly = stateBothOn.copy(isEnabled = false)
        assertEquals(7.0f, stateEqOffOnly.effectiveGainDb(0), 0.05f)

        // Top Master off: EVERYTHING disabled -> 0.0 dB
        val stateMasterOff = stateBothOn.copy(masterEnabled = false)
        assertEquals(0.0f, stateMasterOff.effectiveGainDb(0), 0.01f)
    }
}



