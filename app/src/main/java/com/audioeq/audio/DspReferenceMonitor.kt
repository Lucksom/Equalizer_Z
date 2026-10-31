package com.audioeq.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.audioeq.model.SectionWidths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class ReferenceSignalType(val label: String, val description: String) {
    TABLA_LOOP("Tabla Groove", "Synthesized Bayan (62–125 Hz) + Dayan (250 Hz & 4–8 kHz stroke) stereo pattern"),
    PINK_NOISE("Pink Noise", "Equal-energy-per-octave reference noise (-20 dBFS) for loudness parity & tone testing"),
    SINE_SWEEP("Log Sweep", "20 Hz – 16 kHz logarithmic frequency sweep across left/right field")
}

class DspReferenceMonitor(
    private val scope: CoroutineScope
) {
    private val sampleRate = 48000
    val processor = MidSideProcessor(sampleRate.toDouble())

    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var currentSignal: ReferenceSignalType = ReferenceSignalType.TABLA_LOOP
        private set

    fun updateDspState(
        gainsDb: List<Float>,
        preampDb: Float,
        makeupDb: Float = 0f,
        highPassHz: Float = 0f,
        bassHarmonicMixDb: Float? = null,
        limiterThresholdDb: Float = -0.5f,
        widths: SectionWidths = SectionWidths(),
        qOverrides: Map<Int, Float> = emptyMap(),
        profile: AudioProfile = AudioProfile.OFF,
        bypassed: Boolean = false,
        abCompare: Boolean = false
    ) {
        processor.updateParameters(
            gainsDb = gainsDb,
            preampDb = preampDb,
            makeupDb = makeupDb,
            highPassHz = highPassHz,
            bassHarmonicMixDb = bassHarmonicMixDb,
            limiterThresholdDb = limiterThresholdDb,
            widths = widths,
            qOverrides = qOverrides,
            profile = profile,
            bypassed = bypassed,
            abCompare = abCompare
        )
    }

    fun getAudioSessionId(): Int {
        return audioTrack?.audioSessionId ?: 0
    }

    fun selectSignal(signal: ReferenceSignalType) {
        currentSignal = signal
    }

    fun start(onSessionCreated: (Int) -> Unit = {}) {
        if (isPlaying) return
        isPlaying = true

        playbackJob = scope.launch(Dispatchers.Default) {
            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = try {
                AudioTrack.Builder()
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
                    .setBufferSizeInBytes(minBufBytes * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                isPlaying = false
                return@launch
            }

            audioTrack = track
            onSessionCreated(track.audioSessionId)

            try {
                track.play()
                val framesPerBlock = 512
                val pcmBuffer = ShortArray(framesPerBlock * 2)
                var sampleCounter = 0L

                var b0L = 0.0; var b1L = 0.0; var b2L = 0.0; var b3L = 0.0; var b4L = 0.0; var b5L = 0.0; var b6L = 0.0
                var b0R = 0.0; var b1R = 0.0; var b2R = 0.0; var b3R = 0.0; var b4R = 0.0; var b5R = 0.0; var b6R = 0.0
                val rng = Random(42)

                while (isActive && isPlaying) {
                    val mode = currentSignal
                    var idx = 0
                    for (f in 0 until framesPerBlock) {
                        val t = sampleCounter.toDouble() / sampleRate.toDouble()
                        sampleCounter++

                        val pair: Pair<Double, Double> = when (mode) {
                            ReferenceSignalType.TABLA_LOOP -> synthesizeTablaStep(t)
                            ReferenceSignalType.PINK_NOISE -> {
                                val whiteL = rng.nextDouble(-1.0, 1.0)
                                b0L = 0.99886 * b0L + whiteL * 0.0555179
                                b1L = 0.99332 * b1L + whiteL * 0.0750759
                                b2L = 0.96900 * b2L + whiteL * 0.1538520
                                b3L = 0.86650 * b3L + whiteL * 0.3104856
                                b4L = 0.55000 * b4L + whiteL * 0.5329522
                                b5L = -0.7616 * b5L - whiteL * 0.0168980
                                val pinkL = (b0L + b1L + b2L + b3L + b4L + b5L + b6L + whiteL * 0.5362) * 0.04
                                b6L = whiteL * 0.115926

                                val whiteR = rng.nextDouble(-1.0, 1.0)
                                b0R = 0.99886 * b0R + whiteR * 0.0555179
                                b1R = 0.99332 * b1R + whiteR * 0.0750759
                                b2R = 0.96900 * b2R + whiteR * 0.1538520
                                b3R = 0.86650 * b3R + whiteR * 0.3104856
                                b4R = 0.55000 * b4R + whiteR * 0.5329522
                                b5R = -0.7616 * b5R - whiteR * 0.0168980
                                val pinkR = (b0R + b1R + b2R + b3R + b4R + b5R + b6R + whiteR * 0.5362) * 0.04
                                b6R = whiteR * 0.115926
                                pinkL to pinkR
                            }
                            ReferenceSignalType.SINE_SWEEP -> {
                                val cycle = (t % 6.0) / 6.0
                                val freq = 25.0 * Math.pow(640.0, cycle)
                                val phase = 2.0 * PI * freq * t
                                val sigL = sin(phase) * 0.35
                                val sigR = sin(phase + 0.55) * 0.35
                                sigL to sigR
                            }
                        }

                        pcmBuffer[idx] = (pair.first * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                        pcmBuffer[idx + 1] = (pair.second * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                        idx += 2
                    }

                    processor.processStereoPcm16(pcmBuffer, framesPerBlock)
                    track.write(pcmBuffer, 0, pcmBuffer.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {
                }
                audioTrack = null
                isPlaying = false
            }
        }
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun synthesizeTablaStep(t: Double): Pair<Double, Double> {
        val stepDuration = 0.32
        val stepIndex = (t / stepDuration).toLong() % 8L
        val localT = t % stepDuration

        val hasBayan = stepIndex == 0L || stepIndex == 2L || stepIndex == 3L || stepIndex == 6L
        val bayanEnv = if (hasBayan) kotlin.math.exp(-localT * 9.5) else 0.0
        val pitchMod = if (stepIndex == 2L) 88.0 + 18.0 * sin(localT * 14.0) else 72.0
        val bayanSig = bayanEnv * (
            0.50 * sin(2.0 * PI * pitchMod * localT) +
                0.22 * sin(2.0 * PI * (pitchMod * 1.98) * localT)
            )

        val hasDayan = stepIndex == 0L || stepIndex == 1L || stepIndex == 4L || stepIndex == 5L || stepIndex == 7L
        val bodyEnv = if (hasDayan) kotlin.math.exp(-localT * 18.0) else 0.0
        val attackEnv = if (hasDayan) kotlin.math.exp(-localT * 65.0) else 0.0

        val dayanBody = bodyEnv * (
            0.28 * sin(2.0 * PI * 255.0 * localT) +
                0.24 * sin(2.0 * PI * 540.0 * localT) +
                0.15 * sin(2.0 * PI * 1080.0 * localT)
            )
        val dayanStroke = attackEnv * (
            0.22 * sin(2.0 * PI * 4050.0 * localT) +
                0.15 * sin(2.0 * PI * 8100.0 * localT) +
                0.08 * sin(2.0 * PI * 14800.0 * localT)
            )

        val left = 0.72 * bayanSig + 0.38 * (dayanBody + dayanStroke)
        val right = 0.42 * bayanSig + 0.76 * (dayanBody + dayanStroke)
        return (left * 0.75) to (right * 0.75)
    }
}
