package com.example

import com.example.audio.AutoEq
import com.example.audio.BiquadFilter
import com.example.audio.Dsp
import com.example.audio.Enhancer
import com.example.audio.EnhancerCurves
import com.example.audio.EnhancerMbc
import com.example.audio.EnhancerMixer
import com.example.audio.FastRender
import com.example.audio.Layers
import com.example.audio.MidSideProcessor
import com.example.audio.PeqFilter
import com.example.model.EqConstants
import com.example.model.FilterType
import com.example.model.OutputRoute
import com.example.model.SectionWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class ExampleUnitTest {

    @Test
    fun v5Presets_matchMasterSpecification() {
        val presets = EqConstants.BUILT_IN_PRESETS.associateBy { it.id }
        assertEquals(6, presets.size)

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

        // Speaker route offsets (-9, -7, -2, +2, +1, 0, -1, -1, 0, 0)
        assertEquals(-9.0, OutputRoute.SPEAKER.offsetsDb[0], 0.01)
        assertEquals(-7.0, OutputRoute.SPEAKER.offsetsDb[1], 0.01)
        assertEquals(2.0, OutputRoute.SPEAKER.offsetsDb[3], 0.01)
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
            preampDb = -3.0f,
            makeupDb = 1.5f,
            highPassHz = percussion.highPassHz,
            bassHarmonicMixDb = percussion.bassHarmonicMixDb,
            limiterThresholdDb = -1.0f,
            widths = SectionWidths(),
            qOverrides = percussion.qOverrides,
            bypassed = false,
            abCompare = false
        )

        val pcm = ShortArray(512) { i -> (sin((i / 2) * 0.15) * 16000.0).toInt().toShort() }
        processor.processStereoPcm16(pcm, 256)
        assertTrue(pcm.any { it.toInt() != 0 })
    }

    @Test
    fun enhancersT1_curvePeaksMatchSection7Spec() {
        assertEquals(4.33, EnhancerCurves.CURVE[Enhancer.DEEP_BASS.ordinal][0].maxOrNull() ?: 0.0, 0.02)
        assertEquals(3.96, EnhancerCurves.CURVE[Enhancer.DEEP_BASS.ordinal][1].maxOrNull() ?: 0.0, 0.02)
        assertEquals(1.00, EnhancerCurves.CURVE[Enhancer.SURROUND.ordinal][0].maxOrNull() ?: 0.0, 0.02)
        assertEquals(1.00, EnhancerCurves.CURVE[Enhancer.SURROUND.ordinal][1].maxOrNull() ?: 0.0, 0.02)
        assertEquals(2.56, EnhancerCurves.CURVE[Enhancer.BEATS.ordinal][0].maxOrNull() ?: 0.0, 0.02)
        assertEquals(2.68, EnhancerCurves.CURVE[Enhancer.BEATS.ordinal][1].maxOrNull() ?: 0.0, 0.02)
        assertEquals(3.00, EnhancerCurves.CURVE[Enhancer.VOCAL.ordinal][0].maxOrNull() ?: 0.0, 0.02)
        assertEquals(3.00, EnhancerCurves.CURVE[Enhancer.VOCAL.ordinal][1].maxOrNull() ?: 0.0, 0.02)
    }

    @Test
    fun enhancersT2AndT3_goldenMixVectorsMatchSection7Spec() {
        // T2: Golden mix A: bass 1.0 + surround 0.5, Full
        val (outA, resA) = EnhancerMixer.mixToNewArray(
            amounts = doubleArrayOf(1.0, 0.5, 0.0, 0.0),
            smallDriver = false
        )
        val expectedA = doubleArrayOf(
            -2.832, -1.167,  0.929,  2.975,  4.330,  4.203,  2.967,  2.009,  1.768,  1.232,  0.127, -0.740,
            -0.965, -0.694, -0.315, -0.092, -0.017, -0.002,  0.000,  0.000,  0.000,  0.002,  0.012,  0.048,
             0.137,  0.287,  0.442,  0.499,  0.414,  0.253,  0.113
        )
        assertEquals(1.0, resA.scale, 0.001)
        for (i in 0 until 31) {
            assertEquals("Golden Mix A mismatch at band $i", expectedA[i], outA[i], 0.01)
        }

        // T3: Golden mix B: beats 1.0 + vocal 1.0, Small
        val (outB, resB) = EnhancerMixer.mixToNewArray(
            amounts = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
            smallDriver = true
        )
        val expectedB = doubleArrayOf(
             0.000,  0.000,  0.000,  0.005,  0.052,  0.312,  1.074,  2.150,  2.607,  2.124,  0.733, -1.475,
            -2.722, -2.187, -0.940,  0.096,  0.757,  1.006,  0.911,  1.070,  2.176,  3.861,  4.880,  4.755,
             3.451,  1.448,  1.113,  1.758,  1.479,  0.764,  0.260
        )
        assertEquals(1.0, resB.scale, 0.001)
        for (i in 0 until 31) {
            assertEquals("Golden Mix B mismatch at band $i", expectedB[i], outB[i], 0.01)
        }
    }

    @Test
    fun enhancersT4AndT5_budgetCapContinuityAndFastRenderEquivalence() {
        val rng = Random(7)

        // T4a: All four at 1.0 -> peak 6.00 on both variants
        for (small in listOf(false, true)) {
            val (outAll, resAll) = EnhancerMixer.mixToNewArray(doubleArrayOf(1.0, 1.0, 1.0, 1.0), small)
            assertEquals(6.00, resAll.peakDb, 0.01)
            assertEquals(6.00, outAll.maxOrNull() ?: 0.0, 0.01)
            assertTrue(resAll.isBudgetLimited)
        }

        // T4b: 1000 random vectors must never exceed 6.0001 dB
        repeat(1000) {
            val amounts = DoubleArray(4) { rng.nextDouble(0.0, 1.0) }
            val small = rng.nextBoolean()
            val (out, _) = EnhancerMixer.mixToNewArray(amounts, small)
            val maxGain = out.maxOrNull() ?: 0.0
            assertTrue("Exceeded 6.0 dB budget: $maxGain", maxGain <= 6.0001)
        }

        // T4c: Continuity across peak = 6.0 boundary (sweep amounts 0->1: no step above 0.1 dB per 0.01)
        for (step in 0 until 100) {
            val a = step * 0.01
            val aNext = (step + 1) * 0.01
            val (out1, _) = EnhancerMixer.mixToNewArray(doubleArrayOf(a, 0.0, a, 0.0), false)
            val (out2, _) = EnhancerMixer.mixToNewArray(doubleArrayOf(aNext, 0.0, aNext, 0.0), false)
            for (i in 0 until 31) {
                assertTrue("Step jump >= 0.1 dB at band $i", abs(out2[i] - out1[i]) < 0.1)
            }
        }

        // T5: FastRender vs Dsp.renderCurve across 2000 random 10-band vectors (< 1e-9 dB)
        repeat(2000) {
            val y10 = DoubleArray(10) { rng.nextDouble(-12.0, 12.0) }
            val fast = FastRender.renderToNewArray(y10)
            val full = Dsp.renderCurve(y10)
            for (i in 0 until 31) {
                val diff = abs(fast[i] - full[i])
                assertTrue("FastRender mismatch at band $i: $diff", diff < 1e-9)
            }
        }

        // Verify EnhancerMbc.blend behaves according to spec
        val mbcBlended = EnhancerMbc.blend(
            base = EqConstants.DEFAULT_MBC_CONFIG,
            amounts = doubleArrayOf(1.0, 0.0, 1.0, 1.0)
        )
        assertEquals(3.0f, mbcBlended.ratio[0], 0.01f)
        assertEquals(25f, mbcBlended.attackMs[2], 0.01f)
        assertEquals(320f, mbcBlended.releaseMs[0], 0.1f)
        assertEquals(3.8f, mbcBlended.ratio[4], 0.01f)
        assertEquals(-11f, mbcBlended.baseThresholdDb[4], 0.01f)
    }

    @Test
    fun v6MasterEqEngine_matchesDaxAndMathSpec() {
        // 1. Verify 20-Band Bark/ERB frequencies from dax-default.xml
        assertEquals(20, com.example.audio.EqMath.DAX_20_HZ.size)
        assertEquals(47.0, com.example.audio.EqMath.DAX_20_HZ[0], 0.01)
        assertEquals(19688.0, com.example.audio.EqMath.DAX_20_HZ[19], 0.01)

        // 2. Verify 10-to-20 Band Quarter-dB Quantization & Midpoint Mapping (GeqView.java)
        val sliders10 = doubleArrayOf(3.5, 4.5, 2.5, -2.5, -1.5, 0.0, 1.0, 2.5, 2.5, 1.5)
        val dax20Int = com.example.audio.EqMath.sliders10ToDax20Int(sliders10)
        assertEquals(20, dax20Int.size)
        assertEquals(56, dax20Int[0])  // 3.5 * 16 = 56
        assertEquals(64, dax20Int[1])  // midpoint(3.5, 4.5) = 4.0 * 16 = 64
        assertEquals(72, dax20Int[2])  // 4.5 * 16 = 72
        assertEquals(24, dax20Int[18]) // 1.5 * 16 = 24
        assertEquals(24, dax20Int[19]) // duplicated band 18 -> 24

        // 3. Verify 96-byte Little-Endian OEM DAX packet layout
        val pkt96 = com.example.audio.OemDaxBridge.buildGeq96BytePacket(dax20Int)
        assertEquals(96, pkt96.size)
        val pkt12 = com.example.audio.OemDaxBridge.buildProfile12BytePacket(2)
        assertEquals(12, pkt12.size)

        // 4. Verify 7-Band Cascaded High-Shelf Biquad Math (EqualizerView.java)
        val flat7 = DoubleArray(7) { 0.0 }
        assertEquals(0.0, com.example.audio.EqMath.evaluateCascaded7BandDb(flat7, 1000.0), 0.01)

        // 5. Verify Personal Hearing Compensation (CustomizedSoundModel.java: INIT=-42, DIV=3)
        val zeroEar = listOf(0f, 0f, 0f, 0f, 0f, 0f)
        val steps = com.example.audio.EqMath.earDbToCustomizedSoundSteps(zeroEar)
        assertTrue(steps.all { it == 14 }) // (0 - (-42)) / 3 = 14

        // 6. Verify 4 Acoustic Profiles (Dynamic, Movie, Music, Voice)
        assertEquals(4, com.example.audio.DaxProfile.entries.size)
        assertEquals(0.75, com.example.audio.DaxProfile.MOVIE.ieqAmountSpk, 0.001)
        assertEquals(6, com.example.audio.DaxProfile.VOICE.dialogSteps)
    }
}

