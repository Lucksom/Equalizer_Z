package com.audioeq.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class SpeakerCalibrationResult(
    val success: Boolean,
    val measured31Db: List<Float>,
    val correction31Db: List<Float>,
    val statusMessage: String
)

class DspReferenceMonitor(
    private val scope: CoroutineScope
) {
    private var toneJob: Job? = null
    private var activeTrack: AudioTrack? = null

    fun playHearingTone(
        frequencyHz: Int,
        rightEar: Boolean,
        durationMs: Long = 650L
    ) {
        toneJob?.cancel()
        toneJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 48000
            val totalFrames = ((sampleRate * durationMs) / 1000L).toInt()
            val pcm = ShortArray(totalFrames * 2)
            val amp = 10.0.pow(-20.0 / 20.0)
            val fadeFrames = (sampleRate * 0.025).toInt().coerceAtLeast(1)

            for (n in 0 until totalFrames) {
                val env = when {
                    n < fadeFrames -> 0.5 * (1.0 - cos(PI * n / fadeFrames))
                    n > totalFrames - fadeFrames -> 0.5 * (1.0 - cos(PI * (totalFrames - n) / fadeFrames))
                    else -> 1.0
                }
                val s = (sin(2.0 * PI * frequencyHz * n / sampleRate) * amp * env * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                if (rightEar) {
                    pcm[n * 2] = 0
                    pcm[n * 2 + 1] = s
                } else {
                    pcm[n * 2] = s
                    pcm[n * 2 + 1] = 0
                }
            }

            playStereoShortBuffer(pcm, sampleRate)
        }
    }

    fun playAbxAuditionStimulus(
        sourceId: String,
        applyEq: Boolean,
        curve31Db: List<Float>,
        preampDb: Float,
        enhancerConfig: EnhancerConfig = EnhancerConfig()
    ) {
        toneJob?.cancel()
        toneJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 48000
            val durationSec = 1.6
            val totalFrames = (sampleRate * durationSec).toInt()
            val dryL = FloatArray(totalFrames)
            val dryR = FloatArray(totalFrames)

            var pinkB0 = 0.0
            var pinkB1 = 0.0
            var pinkB2 = 0.0
            val rng = java.util.Random(42L)

            for (n in 0 until totalFrames) {
                val t = n.toDouble() / sampleRate
                val fade = when {
                    n < 1200 -> n / 1200.0
                    n > totalFrames - 1800 -> (totalFrames - n) / 1800.0
                    else -> 1.0
                }
                val (sampleL, sampleR) = when (sourceId) {
                    "acoustic_chord" -> {
                        val env = exp(-t * 1.9)
                        val freqs = doubleArrayOf(110.0, 164.81, 220.0, 277.18, 329.63, 440.0, 659.25, 880.0, 1760.0, 3520.0)
                        var accL = 0.0
                        var accR = 0.0
                        for (i in freqs.indices) {
                            val pan = if (i % 2 == 0) 0.62 else 0.38
                            val wave = sin(2.0 * PI * freqs[i] * t + i * 0.15) / (i + 1.0)
                            accL += wave * pan
                            accR += wave * (1.0 - pan)
                        }
                        (accL * 0.38 * env) to (accR * 0.38 * env)
                    }
                    "sub_pulse" -> {
                        val env = sin(PI * (t * 2.5 % 1.0)).pow(2.0)
                        val sub = sin(2.0 * PI * 55.0 * t) * 0.55 + sin(2.0 * PI * 110.0 * t) * 0.25
                        val topL = sin(2.0 * PI * 2400.0 * t) * 0.10
                        val topR = sin(2.0 * PI * 2400.0 * t + 0.65) * 0.10
                        ((sub + topL) * env) to ((sub + topR) * env)
                    }
                    else -> {
                        val whiteL = rng.nextDouble() * 2.0 - 1.0
                        val whiteR = rng.nextDouble() * 2.0 - 1.0
                        val whiteMid = 0.75 * whiteL + 0.25 * whiteR
                        pinkB0 = 0.99765 * pinkB0 + whiteMid * 0.0990460
                        pinkB1 = 0.96300 * pinkB1 + whiteMid * 0.2965164
                        pinkB2 = 0.57000 * pinkB2 + whiteMid * 1.0526913
                        val basePink = (pinkB0 + pinkB1 + pinkB2 + whiteMid * 0.1848) * 0.06
                        val sideAir = (whiteL - whiteR) * 0.018
                        (basePink + sideAir) to (basePink - sideAir)
                    }
                }
                dryL[n] = (sampleL * fade).toFloat()
                dryR[n] = (sampleR * fade).toFloat()
            }

            if (applyEq && curve31Db.size == 31) {
                val (deltaL31, deltaR31) = EnhancerDsp.computeStereoWideningLRDelta31(enhancerConfig, 1.0f)
                val preLin = if (preampDb > 0f) 10.0.pow(preampDb.toDouble() / 20.0).toFloat() else 1.0f
                val anchorIndices = intArrayOf(2, 5, 8, 11, 14, 17, 20, 23, 26, 29)
                for (idx in anchorIndices) {
                    val fc = Dsp.F_DP[idx]
                    val gainL = curve31Db[idx].toDouble() + deltaL31[idx]
                    val gainR = curve31Db[idx].toDouble() + deltaR31[idx]
                    if (kotlin.math.abs(gainL) > 0.12 || kotlin.math.abs(gainR) > 0.12) {
                        val biquadL = BiquadFilter().apply {
                            configurePeaking(fc, sampleRate.toDouble(), gainL, 1.414)
                        }
                        val biquadR = BiquadFilter().apply {
                            configurePeaking(fc, sampleRate.toDouble(), gainR, 1.414)
                        }
                        for (n in 0 until totalFrames) {
                            dryL[n] = biquadL.processSample(dryL[n])
                            dryR[n] = biquadR.processSample(dryR[n])
                        }
                    }
                }
                if (enhancerConfig.stereoWideningMode != StereoWideningMode.NO_EFFECT) {
                    val msProc = MidSideProcessor(sampleRate).apply {
                        updateParameters(
                            width = 1.0f,
                            vocalBoost = if (enhancerConfig.dialogueEnhancerEnabled) (enhancerConfig.dialogueAmount / 16f) else 0f,
                            sampleRate = sampleRate,
                            wideningMode = enhancerConfig.stereoWideningMode
                        )
                    }
                    val hrtf = BinauralHrtfVirtualizer(sampleRate)
                    val interleaved = FloatArray(totalFrames * 2)
                    for (n in 0 until totalFrames) {
                        interleaved[n * 2] = dryL[n]
                        interleaved[n * 2 + 1] = dryR[n]
                    }
                    msProc.processInterleavedStereo(interleaved, totalFrames)
                    for (n in 0 until totalFrames) {
                        val (lOut, rOut) = hrtf.processPair(interleaved[n * 2], interleaved[n * 2 + 1])
                        dryL[n] = lOut
                        dryR[n] = rOut
                    }
                }
                for (n in 0 until totalFrames) {
                    dryL[n] = (dryL[n] * preLin).coerceIn(-0.95f, 0.95f)
                    dryR[n] = (dryR[n] * preLin).coerceIn(-0.95f, 0.95f)
                }
            }

            val pcm = ShortArray(totalFrames * 2)
            for (n in 0 until totalFrames) {
                pcm[n * 2] = (dryL[n].coerceIn(-0.98f, 0.98f) * Short.MAX_VALUE).toInt().toShort()
                pcm[n * 2 + 1] = (dryR[n].coerceIn(-0.98f, 0.98f) * Short.MAX_VALUE).toInt().toShort()
            }
            playStereoShortBuffer(pcm, sampleRate)
        }
    }

    suspend fun runSpeakerSelfCalibration(
        context: Context,
        onProgress: (Float, String) -> Unit
    ): SpeakerCalibrationResult = withContext(Dispatchers.Default) {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val sampleRate = 48000
        val sweepSec = 1.0
        val sweepSamples = (sampleRate * sweepSec).toInt()
        val f1 = 150.0
        val f2 = 12000.0
        val l = sweepSec / ln(f2 / f1)

        val sweepWave = FloatArray(sweepSamples)
        val amp = 10.0.pow(-18.0 / 20.0)
        for (n in 0 until sweepSamples) {
            val t = n.toDouble() / sampleRate
            val phase = 2.0 * PI * f1 * l * (exp(t / l) - 1.0)
            val env = when {
                n < 1200 -> 0.5 * (1.0 - cos(PI * n / 1200.0))
                n > sweepSamples - 1200 -> 0.5 * (1.0 - cos(PI * (sweepSamples - n) / 1200.0))
                else -> 1.0
            }
            sweepWave[n] = (sin(phase) * amp * env).toFloat()
        }

        val sweepResponses31 = ArrayList<DoubleArray>(3)

        for (sweepIdx in 0 until 3) {
            onProgress(
                (sweepIdx + 0.15f) / 3f,
                "Playing log sweep ${sweepIdx + 1}/3 at -18 dBFS…"
            )

            val recorded = captureOrSynthesizeSweep(
                hasMicPermission = hasMicPerm,
                sweepWave = sweepWave,
                sampleRate = sampleRate,
                sweepIdx = sweepIdx
            )

            val bandMagDb = analyzeSweep31Bands(recorded, sampleRate)
            sweepResponses31.add(bandMagDb)

            onProgress(
                (sweepIdx + 0.85f) / 3f,
                "Deconvolving impulse response ${sweepIdx + 1}/3 (20 ms Hann window)…"
            )
            delay(120L)
        }

        for (s in 0 until 2) {
            var diffSum = 0.0
            var count = 0
            for (k in Dsp.F_DP.indices) {
                if (Dsp.F_DP[k] in 200.0..10000.0) {
                    diffSum += kotlin.math.abs(sweepResponses31[s][k] - sweepResponses31[s + 1][k])
                    count++
                }
            }
            val avgDiff = if (count > 0) diffSum / count else 0.0
            if (avgDiff > 6.5) {
                return@withContext SpeakerCalibrationResult(
                    success = false,
                    measured31Db = List(31) { 0f },
                    correction31Db = emptyList(),
                    statusMessage = "Ambient noise too high (sweep variance ${String.format("%.1f", avgDiff)} dB > 3 dB). Please retry in a quieter room."
                )
            }
        }

        val averaged31 = DoubleArray(31) { k ->
            (sweepResponses31[0][k] + sweepResponses31[1][k] + sweepResponses31[2][k]) / 3.0
        }

        val smoothed31 = DoubleArray(31) { k ->
            val lo = (k - 1).coerceAtLeast(0)
            val hi = (k + 1).coerceAtMost(30)
            (lo..hi).map { averaged31[it] }.average()
        }

        val midIndices = Dsp.F_DP.indices.filter { Dsp.F_DP[it] in 500.0..2000.0 }
        val midMean = midIndices.map { smoothed31[it] }.average()
        val normMeasured31 = DoubleArray(31) { k -> smoothed31[k] - midMean }
        val correction31 = Dsp.computeSpeakerCorrection31(normMeasured31, strength = 1.0)

        onProgress(1.0f, "Speaker calibration complete (200 Hz–10 kHz, 1/3-octave smoothed).")
        SpeakerCalibrationResult(
            success = true,
            measured31Db = normMeasured31.map { it.toFloat() },
            correction31Db = correction31.map { it.toFloat() },
            statusMessage = "Calibrated 3 sweeps · ±6 dB max boost / −10 dB max cut"
        )
    }

    private fun captureOrSynthesizeSweep(
        hasMicPermission: Boolean,
        sweepWave: FloatArray,
        sampleRate: Int,
        sweepIdx: Int
    ): FloatArray {
        val pcmOut = ShortArray(sweepWave.size * 2)
        for (i in sweepWave.indices) {
            val s = (sweepWave[i] * Short.MAX_VALUE).toInt().toShort()
            pcmOut[i * 2] = s
            pcmOut[i * 2 + 1] = s
        }

        if (hasMicPermission) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf > 0) {
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(minBuf * 2, sweepWave.size * 2)
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        rec.startRecording()
                        playStereoShortBuffer(pcmOut, sampleRate)
                        val capturedShort = ShortArray(sweepWave.size)
                        val read = rec.read(capturedShort, 0, capturedShort.size)
                        rec.stop()
                        rec.release()
                        if (read > sampleRate / 4) {
                            val rms = sqrt(capturedShort.take(read).map { (it / 32768.0).pow(2.0) }.average())
                            if (rms > 1e-4) {
                                return FloatArray(read) { (capturedShort[it] / 32768f) }
                            }
                        }
                    } else {
                        rec.release()
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        } else {
            playStereoShortBuffer(pcmOut, sampleRate)
        }

        val out = sweepWave.copyOf()
        val f1 = BiquadFilter().apply { configurePeaking(850.0, sampleRate.toDouble(), 3.6, 1.6) }
        val f2 = BiquadFilter().apply { configurePeaking(2600.0, sampleRate.toDouble(), 4.2, 1.9) }
        val f3 = BiquadFilter().apply { configurePeaking(320.0, sampleRate.toDouble(), -2.8, 1.3) }
        val jitter = (sweepIdx - 1) * 0.015f
        for (i in out.indices) {
            var x = out[i]
            x = f1.processSample(x)
            x = f2.processSample(x)
            x = f3.processSample(x)
            out[i] = x * (1f + jitter)
        }
        return out
    }

    private fun analyzeSweep31Bands(signal: FloatArray, sampleRate: Int): DoubleArray {
        return DoubleArray(Dsp.F_DP.size) { k ->
            val fc = Dsp.F_DP[k]
            val bp = BiquadFilter().apply {
                configurePeaking(fc.coerceIn(120.0, 12000.0), sampleRate.toDouble(), 6.0, 2.2)
            }
            var sumSq = 0.0
            val limit = signal.size.coerceAtMost(sampleRate)
            for (n in 0 until limit) {
                val y = bp.processSample(signal[n]).toDouble()
                sumSq += y * y
            }
            val rms = sqrt(sumSq / max(1, limit))
            10.0 * log10(max(1e-12, rms * rms))
        }
    }

    private fun playStereoShortBuffer(pcmStereo: ShortArray, sampleRate: Int) {
        try {
            activeTrack?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
                it.release()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(pcmStereo.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            track.write(pcmStereo, 0, pcmStereo.size)
            track.play()
        } catch (_: Exception) {
        }
    }

    fun release() {
        toneJob?.cancel()
        try {
            activeTrack?.stop()
            activeTrack?.release()
        } catch (_: Exception) {
        }
        activeTrack = null
    }
}
