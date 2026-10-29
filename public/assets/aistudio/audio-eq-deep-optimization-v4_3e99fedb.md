# Audio EQ: Deep Optimization v4

Self-contained reference for the Android equalizer. It replaces v3. From v1, keep only: setup steps (5.5), Magisk and recovery (6.3–6.5), mode detection (9) and the **width** section (6.2, untouched for now).

All numbers are tuned starting points. Nothing here was measured on a device, and the Kotlin was not compiled. Verify with section 14.

---

## 0. What changed from v3

| Area | v3 | v4 | Why |
|---|---|---|---|
| Volume and headroom | Said system volume is applied after the effect | **Corrected** (section 7) | On the common software-volume path the effect receives the volume-scaled signal, so low-volume boosts are safe there. Other paths differ. The ceilings cover both. |
| DP variant | Percussion Clarity used time-resolution | **Frequency-resolution for every preset** | **Correction:** the variant is fixed when the effect is created. Switching presets would rebuild the effect and cause a gap. Ceiling attack times already protect transients. |
| Compressor splits | 88, 354, 1414, 5657, 11314 Hz | **Upper edges of render bands 6, 12, 18, 24, 27** (90, 359, 1437, 5747, 11494 Hz) | Exact alignment with the 31-band edges |
| Boost tracking | Mean of sliders | **Mean of rendered bands** | More accurate |
| Makeup gain | Measured only | **Model estimate at preset load**, refined by measurement | Works before any measurement and never moves while dragging sliders |
| Device correction | None | **Headphone profiles and speaker self-calibration** | The Dirac-style layer that is possible without Root |
| Code | Snippets | **Complete engine class** | Paste-ready |
| Write load | All bands every tick | **Changed values only, about 33 updates per second** | Every `DynamicsProcessing` setter is a binder call |

---

## 1. Build order (most audible gain first)

1. **Fixed headroom, ceilings, no slider-dependent preamp.** This removes the cross-band coupling.
2. **31-band renderer** (section 5).
3. **v4 presets and Intensity** (section 4).
4. **Smoothing and write throttling** (sections 9, 10).
5. **Route profiles** (section 6).
6. **Volume contour** (section 7).
7. **Headphone profiles** (section 8.1).
8. **Speaker self-calibration** (section 8.2).
9. **Root DSP** (section 12).

Steps 1–4 deliver most of the "deep and clean" result. Steps 7–8 are the Dirac-style part.

---

## 2. Band layout and true category boundaries

10 sliders, no duplicates: **31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz**. Gain range −12 to +12 dB.

| Category | Sliders | True range | UI label |
|---|---|---|---|
| Bass | 31, 62, 125, 250 | 20–354 Hz | 20–350 Hz |
| Mids | 500, 1k, 2k | 354–2828 Hz | 350 Hz–2.8 kHz |
| Treble | 4k, 8k | 2828–11314 Hz | 2.8–11 kHz |
| Air | 16k | 11314–20000 Hz | 11–20 kHz |

Slider band edges (upper): 44, 88, 177, 354, 707, 1414, 2828, 5657, 11314, 20000 Hz. Update the UI labels to match.

---

## 3. Layers and signal chain

Three layers add together in dB:

| Layer | Source | Changes when |
|---|---|---|
| Correction | Headphone profile or speaker calibration (31 bands) | The output device or profile changes (rare) |
| Taste | 10 sliders × Intensity | The user moves a slider or picks a preset |
| Context | Route offsets + volume contour | The route or volume changes |

```
taste31    = renderCurve(user × intensity + routeOffset + contourOffset)   // 10 sliders -> 31 bands
effective  = clamp(taste31 + correction31, -15, +10)                       // 31 values
inputGain  = routeBaseGain - max(0, max(correction31) - 3)                 // changes only when the correction changes
makeup     = clamp(-inputGain - G, 0, 4)                                   // G: section 5.3, set at load, frozen while dragging
```

Chain:

```
input gain (fixed, normally -3 dB)
  -> pre-EQ: 31 bands from `effective`
  -> multiband ceilings: 6 bands, split on render-band edges
  -> limiter backstop (rarely engages)
```

| Stage | Value | Rule |
|---|---|---|
| Input gain | −3.0 dB (Bluetooth −4.0 dB), minus any profile boost above +3 dB | Never computed from the sliders |
| Ceilings | Section 5 | Per band, so bass peaks cannot duck other regions |
| Makeup | Per preset, section 5.3 | Equal on all 6 MBC bands, so tone does not shift |
| Limiter | −1.0 dB (Bluetooth −1.5 dB) | Backstop only |

Flat means the chain is bypassed (engine disabled), not "zeros through the chain".

**Headroom meter (UI):** `ceilingLoad = max(0, max(effective) − 3)` dB. It is the worst case at full volume. Warn above 4 dB. Never silently lower other bands.

---

## 4. Presets

### 4.1 Master gains (dB)

| Band | Flat | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity | Cinema |
|---|---|---|---|---|---|---|
| 31 Hz | 0 | +3.5 | +5.5 | −3.5 | −4 | +4 |
| 62 Hz | 0 | +4.5 | +6.5 | −2.5 | +1.5 | +4 |
| 125 Hz | 0 | +2.5 | +4 | −1 | +2.5 | +2 |
| 250 Hz | 0 | −2.5 | −2 | −3 | −3.5 | −2 |
| 500 Hz | 0 | −1.5 | −1.5 | −1.5 | −2 | −1 |
| 1 kHz | 0 | 0 | −0.5 | +1.5 | 0 | +0.5 |
| 2 kHz | 0 | +1 | 0 | +3.5 | +1.5 | +2.5 |
| 4 kHz | 0 | +2.5 | 0 | +3.5 | +3.5 | +2.5 |
| 8 kHz | 0 | +2.5 | +0.5 | +1.5 | +3 | +2 |
| 16 kHz | 0 | +1.5 | +0.5 | +0.5 | +1.5 | +2 |
| **Largest boost** | 0 | +4.5 | +6.5 | +3.5 | +3.5 | +4 |
| High-pass (Root) | off | 20 Hz | 25 Hz | 60 Hz | 40 Hz | 20 Hz |

Design logic:
- **Balanced Deep:** bass shelf for weight, a 250–500 Hz dip for tightness, ear-gain lift at 2–4 kHz, air at 8–16 kHz.
- **Bass Boost:** a deeper low end, kept clean by the 250 Hz dip.
- **Vocal Clarity:** body cut, presence peak at 2–4 kHz, restrained 8 kHz to avoid sibilance.
- **Percussion Clarity:** 62–125 Hz kept for the bayan, 250 Hz boxiness cut, 4 kHz stroke definition (Q 2 on Root), 8 kHz sizzle.
- **Cinema:** low-end weight plus dialogue presence at 2 kHz.

### 4.2 Intensity macro

`effective = user × intensity`, with intensity 0.5–1.5, default 1.0. Show as Light / Standard / Deep+ (0.6 / 1.0 / 1.3). Changing intensity counts as a preset-level change: recompute makeup, glide 250 ms.

---

## 5. Renderer, ceilings and limiter

### 5.1 31-band renderer (ADB/Shizuku and Legacy tier 2)

Interpolate the 10 slider values across 31 third-octave bands (centres `20 × 2^(i/3)` Hz) with a monotone cubic (PCHIP) curve. The response at each slider centre follows the slider, the curve between sliders is smooth, and there is no overshoot. Each band's cutoff is its upper edge, `centre × 2^(1/6)`, with the last clamped to 20000 Hz. Code is in section 9.

- Below 31 Hz and above 16 kHz the curve stays flat at the end slider's value.
- If construction throws with 31 bands, retry with 20, then 10 (with the fallback cutoffs in section 5.2).
- Use `VARIANT_FAVOR_FREQUENCY_RESOLUTION` for every preset. Offer time-resolution only as an optional "Transient mode" setting that rebuilds the effect once.

### 5.2 Multiband ceilings (6 bands)

| MBC band | Cutoff (Hz) | Render bands | Sliders inside | Attack (ms) | Release (ms) | Ratio | Base threshold (dB) | Knee (dB) |
|---|---|---|---|---|---|---|---|---|
| Sub | 90 | 0–6 | 31, 62 | 40 | 250 | 4.0 | −6 | 6 |
| Punch | 359 | 7–12 | 125, 250 | 15 | 160 | 3.5 | −6 | 6 |
| Body | 1437 | 13–18 | 500, 1k | 12 | 130 | 2.5 | −6 | 6 |
| Presence | 5747 | 19–24 | 2k, 4k | 6 | 90 | 3.0 | −7 | 6 |
| Sizzle (de-harsh) | 11494 | 25–27 | 8k | 1.5 | 45 | 3.5 | −9 | 4 |
| Air | 20000 | 28–30 | 16k | 2 | 50 | 2.5 | −8 | 6 |

All bands: noise gate −90 dB, expander ratio 1.0, pre-gain 0, post-gain = makeup.

10-band fallback cutoffs: 88, 354, 1414, 5657, 11314, 20000 Hz (slider band edges).

**Percussion Clarity overrides** (slow attack lets strokes through):

| MBC band | Attack (ms) | Release (ms) | Ratio |
|---|---|---|---|
| Sub | 50 | 250 | 3.0 |
| Punch | 30 | 150 | 2.8 |
| Body | 25 | 120 | 2.2 |
| Presence | 12 | 80 | 2.5 |
| Sizzle | 4 | 50 | 2.8 |
| Air | 5 | 50 | 2.0 |

**Boost-tracking threshold**, so the compressor does not undo the EQ:

```
threshold_band = baseThreshold_band + max(0, mean(effective[i] for render bands i in that MBC band))
```

### 5.3 Makeup and loudness parity

Goal: each preset within ±1 LU of bypass, so "On" never sounds weaker than "Off".

**Model estimate (set at load):** pink noise has equal energy per octave, so the curve's loudness change is the weighted power mean over the 31 bands:

```
G = 10 × log10( Σ w_i × 10^(effectiveNoContour_i / 10) / Σ w_i ),   w_i = 0.5 below 100 Hz, else 1
makeupDb = clamp(-inputGainDb - G, 0, 4)
```

Compute `effectiveNoContour` without the volume contour. Recompute makeup only on preset, route, profile or intensity changes. **Freeze it while sliders are dragged**, otherwise moving one band would change all the others.

**Refinement (measured):** play pink noise and a dense music clip at −20 dBFS through bypass and through each preset, compare integrated loudness, and store a per-preset trim. Apply the same value on all 6 MBC bands.

### 5.4 Limiter backstop

| Attack (ms) | Release (ms) | Ratio | Threshold (dB) | Post-gain | Link group |
|---|---|---|---|---|---|
| 1 | 50 | 20 | −1.0 (Bluetooth −1.5) | 0 | one shared group (keeps the stereo image) |

---

## 6. Output-route profiles

Detect the output with `AudioManager` device info. Offsets are **added** to the preset gains (dB, sliders 31 → 16k).

| Route | Device types | Slider offsets | Input gain | Limiter | Notes |
|---|---|---|---|---|---|
| Speaker | `TYPE_BUILTIN_SPEAKER` | −9, −7, −2, +2, +1, 0, −1, −1, 0, 0 | −3 | −1.0 | Protects the small driver, restores 250 Hz, softens the 2–4 kHz resonance. Root: high-pass 140 Hz and +3 dB harmonic mix. If a speaker calibration exists (section 8.2), use zero offsets instead. |
| Wired | `TYPE_WIRED_HEADPHONES`, `TYPE_WIRED_HEADSET` | 0 | −3 | −1.0 | A headphone profile (section 8.1) can apply here. |
| Bluetooth | `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET` (API 31+) | 0 | −4 | −1.5 | Lossy codecs can overshoot full scale. |
| USB | `TYPE_USB_HEADSET`, `TYPE_USB_DEVICE` | 0 | −3 | −1.0 | Some USB DAC paths bypass effects. Show "may not apply". |

Example: Bass Boost on the speaker becomes −3.5, −0.5, +2, 0 across the four bass sliders, so it lifts upper bass instead of trying to play 60 Hz. Glide 250 ms on route change.

---

## 7. Volume-adaptive contour

Hearing loses bass and treble sensitivity at low listening levels. The contour adds a gentle shelf as system volume drops. Values follow the general trend of equal-loudness contours (ISO 226) in simplified form, so tune by ear.

`v = getStreamVolume(STREAM_MUSIC) / getStreamMaxVolume(STREAM_MUSIC)`. Poll every 500 ms or listen to `android.media.VOLUME_CHANGED_ACTION` (undocumented). Interpolate linearly between rows.

| Volume v | 31 | 62 | 125 | 250 | 500 | 1k | 2k | 4k | 8k | 16k |
|---|---|---|---|---|---|---|---|---|---|---|
| ≥ 0.6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.4 | +1.5 | +1.5 | +1 | 0 | 0 | 0 | 0 | 0 | +0.5 | +0.5 |
| 0.25 | +3 | +3 | +2 | 0 | 0 | 0 | 0 | 0 | +1 | +1.5 |
| 0.1 | +4.5 | +4.5 | +3 | +0.5 | 0 | 0 | 0 | 0 | +1.5 | +2 |

Rules:
- Default on, with a user toggle. Not applied in Flat.
- Change at most 1 dB per 500 ms.
- The contour feeds `effective`, the ceilings and the headroom meter like any other gain.
- **Where volume is applied differs by device.** On the common software-volume path the effect receives the volume-scaled signal, so low-volume boosts have ample digital headroom. On hardware-volume, offload, USB or Bluetooth absolute-volume paths the effect may see the full-scale signal. The ceilings protect both cases, so no special handling is needed.
- It is a heuristic: headphone sensitivity varies.

---

## 8. Device correction (the Dirac-style layer, no Root)

Dirac corrects the measured response of a specific device. Without Root you cannot correct phase, but you can correct the magnitude response in two ways. Correction sits **under** the user's taste, so a slider still means the same thing on every device.

### 8.1 Headphone profiles (database lookup)

Ship or download per-model correction profiles (parametric filter lists, as published for headphone targets) and let the user pick one, or suggest a match from `AudioDeviceInfo.productName`.

Profile schema:

```json
{
  "id": "model-id", "name": "Model name", "type": "over_ear",
  "source": "dataset name", "license": "dataset license",
  "preampDb": -4.5,
  "filters": [
    { "type": "LSC", "fc": 105, "gain": 6.5, "q": 0.7 },
    { "type": "PK",  "fc": 3200, "gain": -2.0, "q": 2.0 },
    { "type": "HSC", "fc": 10000, "gain": -3.0, "q": 0.7 }
  ]
}
```

Conversion to `correction31`:
1. Evaluate each filter's magnitude (RBJ cookbook biquads at 48 kHz) at the 31 render centres and sum in dB.
2. Taper to zero between 10 kHz and 14 kHz. Ear-canal variation makes the top octave unreliable.
3. Limit to +6 dB boost (+3 dB above 6 kHz) and −12 dB cut.
4. Multiply by strength, default **0.8**.
5. Ignore `preampDb`. The engine's `inputGain` rule already handles headroom.

Check the license and attribution terms of whichever dataset you ship. Show the profile name and source in the UI.

### 8.2 Speaker self-calibration (guided measurement)

Uses the phone's own microphone to find big speaker resonances and dips. Expect **rough accuracy (about ±3 dB)**: the microphone is close to the speaker and its own response is unknown. That is why the limits below are conservative. Prefer shipped per-model speaker profiles when you have them, and use self-calibration as the fallback.

| Item | Spec |
|---|---|
| Preconditions | Speaker route active, engine bypassed, quiet room, user sets media volume to about 50% |
| Permission | `RECORD_AUDIO`. Use `AudioSource.UNPROCESSED` when `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` is `"true"`, otherwise `VOICE_RECOGNITION`. 48 kHz, mono. |
| Signal | Exponential sine sweep 20 Hz–20 kHz, 4 s, −18 dBFS, 0.5 s fades. Record 1 s of silence first for the noise floor. Run 3 sweeps. |
| Analysis | Deconvolve with the inverse sweep, keep the first **20 ms** of the impulse response (rejects room reflections), take the FFT, smooth into the 31 third-octave bands, average the 3 runs in dB. |
| Valid range | 200 Hz–10 kHz. Correction tapers to zero one octave outside that range. Never boost below 200 Hz. |
| Band validity | SNR at least 20 dB and the 3 runs agree within 3 dB. Invalid bands get zero correction. |
| Correction | `computeCorrection()` below. Strength default **0.7**. |
| Verification | Re-measure with the correction active. Keep it only if the RMS error to target over 200 Hz–8 kHz improves by at least 20%. Otherwise discard it. |
| Storage | Per `Build.MODEL` and route. Offer Reset and an on/off toggle. |
| Safety | Warn the user before playing. Never exceed the −18 dBFS sweep level, and refuse to start above 60% volume. |

Target curve (dB relative to 1 kHz, linear in log-frequency between anchors): 200 Hz −2, 250 Hz −1, 315 Hz 0, 2 kHz 0, 4 kHz −1.5, 8 kHz −3, 10 kHz −3.5. A gentle downward tilt avoids harshness.

```kotlin
val SPK_ANCHORS = arrayOf(200.0 to -2.0, 250.0 to -1.0, 315.0 to 0.0, 2000.0 to 0.0,
                          4000.0 to -1.5, 8000.0 to -3.0, 10000.0 to -3.5)

fun speakerTarget(f: Double): Double {
    val a = SPK_ANCHORS
    if (f <= a.first().first) return a.first().second
    if (f >= a.last().first) return a.last().second
    val k = (0 until a.size - 1).first { f <= a[it + 1].first }
    val t = (log2(f) - log2(a[k].first)) / (log2(a[k + 1].first) - log2(a[k].first))
    return a[k].second + (a[k + 1].second - a[k].second) * t
}

/** measDb: measured response in dB at the 31 render centres (any absolute level). */
fun computeCorrection(measDb: DoubleArray, valid: BooleanArray, strength: Double = 0.7): DoubleArray {
    val ref = (0 until 31).filter { Dsp.RENDER_HZ[it] in 500.0..2000.0 }.map { measDb[it] }.average()
    val raw = DoubleArray(31) { speakerTarget(Dsp.RENDER_HZ[it]) - (measDb[it] - ref) }
    return DoubleArray(31) { i ->
        val f = Dsp.RENDER_HZ[i]
        val taper = when {
            f < 200 -> max(0.0, 1 + log2(f / 200))       // zero at 100 Hz
            f > 10000 -> max(0.0, 1 - log2(f / 10000))   // zero at 20 kHz
            else -> 1.0
        }
        val narrowDip = raw[i] > 0 && i in 1..29 && !(raw[i - 1] > 0 && raw[i + 1] > 0)
        val v = if (narrowDip) min(raw[i], 1.5) else raw[i]   // do not fill narrow dips
        if (!valid[i]) 0.0 else (v * strength * taper).coerceIn(-12.0, if (f > 6000) 3.0 else 6.0)
    }
}
```

Dirac-style phase and time-domain correction (a linear-phase FIR) still needs Root DSP and per-device data. See section 12.3.

---

## 9. Kotlin engine (paste-ready)

Covers rendering, layers, boost tracking, makeup, smoothing, throttled writes and re-assert on control loss. Not compiled here.

```kotlin
package com.yourapp.eq

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.*

object Dsp {
    val SLIDER_HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val RENDER_HZ = DoubleArray(31) { 20.0 * 2.0.pow(it / 3.0) }
    val RENDER_CUT = FloatArray(31) { min(RENDER_HZ[it] * 2.0.pow(1.0 / 6.0), 20000.0).toFloat() }
    val MBC_RANGE = arrayOf(0..6, 7..12, 13..18, 19..24, 25..27, 28..30)
    val MBC_CUT = floatArrayOf(RENDER_CUT[6], RENDER_CUT[12], RENDER_CUT[18], RENDER_CUT[24], RENDER_CUT[27], 20000f)

    fun pchip(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = DoubleArray(n)                       // end slopes stay 0: flat beyond the ends
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0.0) 0.0 else {
            val w1 = 2 * h[i] + h[i - 1]; val w2 = h[i] + 2 * h[i - 1]
            (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
        }
        val k = (0 until n - 1).first { x <= xs[it + 1] }
        val t = (x - xs[k]) / h[k]; val t2 = t * t; val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h[k] * m[k] +
               (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h[k] * m[k + 1]
    }

    fun renderCurve(sliderDb: DoubleArray): DoubleArray {
        val xs = DoubleArray(10) { log2(SLIDER_HZ[it]) }
        return DoubleArray(31) { pchip(xs, sliderDb, log2(RENDER_HZ[it])) }
    }

    /** Pink-noise power gain of a curve (dB); low bands count half. */
    fun pinkGainDb(db: DoubleArray): Double {
        var s = 0.0; var w = 0.0
        for (i in 0 until 31) { val wi = if (RENDER_HZ[i] < 100) 0.5 else 1.0; s += wi * 10.0.pow(db[i] / 10); w += wi }
        return 10 * log10(s / w)
    }

    /** vs descending, e.g. 0.6, 0.4, 0.25, 0.1; rows hold the 10 slider offsets for each. */
    fun contour(v: Double, vs: DoubleArray, rows: Array<DoubleArray>): DoubleArray {
        if (v >= vs[0]) return rows[0]
        if (v <= vs.last()) return rows.last()
        val k = (0 until vs.size - 1).first { v >= vs[it + 1] }
        val t = (vs[k] - v) / (vs[k] - vs[k + 1])
        return DoubleArray(10) { rows[k][it] + (rows[k + 1][it] - rows[k][it]) * t }
    }
}

enum class Route { SPEAKER, WIRED, BLUETOOTH, USB }

/** getDevices lists connected outputs, not the active one. On API 33+ prefer getAudioDevicesForAttributes. */
fun currentRoute(am: AudioManager): Route {
    val t = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    val ble = Build.VERSION.SDK_INT >= 31 && t.contains(AudioDeviceInfo.TYPE_BLE_HEADSET)
    return when {
        ble || t.contains(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) -> Route.BLUETOOTH
        t.contains(AudioDeviceInfo.TYPE_USB_HEADSET) || t.contains(AudioDeviceInfo.TYPE_USB_DEVICE) -> Route.USB
        t.contains(AudioDeviceInfo.TYPE_WIRED_HEADPHONES) || t.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET) -> Route.WIRED
        else -> Route.SPEAKER
    }
}

class Mbc(
    val attackMs: FloatArray, val releaseMs: FloatArray, val ratio: FloatArray,
    val baseThresholdDb: FloatArray, val kneeDb: FloatArray
)

class Layers(
    val userDb: DoubleArray, val intensity: Double,
    val routeDb: DoubleArray, val contourDb: DoubleArray,
    val correction31: DoubleArray = DoubleArray(31)
)

class EqEngine(private val sessionId: Int) {
    private var dp: DynamicsProcessing? = null
    private val handler = Handler(Looper.getMainLooper())
    private val cur = DoubleArray(31)
    private var goal = DoubleArray(31)
    private var tauMs = 60.0
    private var mbc: Mbc? = null
    private var inputDb = -3f
    private var makeupDb = 0f
    private var limiterDb = -1f
    private val sentEq = DoubleArray(31) { Double.NaN }
    private val sentThr = DoubleArray(6) { Double.NaN }
    private var sentFixed = ""

    /** False means this session cannot host DynamicsProcessing: fall back through the mode order. */
    fun start(): Boolean = try {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, 31,      // pre-EQ: rendered curve
            true, 6,       // MBC: ceilings
            false, 0,
            true           // limiter backstop
        ).build()
        dp = DynamicsProcessing(0, sessionId, cfg).also { d ->
            d.setControlStatusListener { _, granted -> if (granted) { invalidate(); write(cur) } }
            d.setEnableStatusListener { _, on -> if (!on) d.enabled = true }
            d.enabled = true
        }
        true
    } catch (e: Exception) { dp = null; false }

    fun effective(l: Layers, withContour: Boolean = true): DoubleArray {
        val slider = DoubleArray(10) {
            l.userDb[it] * l.intensity + l.routeDb[it] + (if (withContour) l.contourDb[it] else 0.0)
        }
        val taste = Dsp.renderCurve(slider)
        return DoubleArray(31) { (taste[it] + l.correction31[it]).coerceIn(-15.0, 10.0) }
    }

    /** Call on preset, route, profile or intensity change. Not while a slider is being dragged. */
    fun load(l: Layers, m: Mbc, routeBaseDb: Float, limiter: Float, glideMs: Double = 250.0) {
        val base = effective(l, withContour = false)
        inputDb = routeBaseDb - max(0.0, (l.correction31.maxOrNull() ?: 0.0) - 3.0).toFloat()
        makeupDb = (-inputDb - Dsp.pinkGainDb(base)).coerceIn(0.0, 4.0).toFloat()
        limiterDb = limiter; mbc = m
        sentThr.fill(Double.NaN); sentFixed = ""
        update(l, glideMs)
    }

    /** Call on slider drags and volume changes. Never touches input gain or makeup. */
    fun update(l: Layers, glideMs: Double = 60.0) {
        goal = effective(l); tauMs = glideMs
        handler.removeCallbacks(tick); handler.post(tick)
    }

    fun setEnabled(on: Boolean) { try { dp?.enabled = on } catch (e: Exception) {} }
    fun release() { handler.removeCallbacks(tick); try { dp?.release() } catch (e: Exception) {}; dp = null }

    private fun invalidate() { sentEq.fill(Double.NaN); sentThr.fill(Double.NaN); sentFixed = "" }

    private val tick = object : Runnable {
        override fun run() {
            val k = 1 - exp(-30.0 / tauMs)
            var settled = true
            for (i in 0 until 31) {
                cur[i] += (goal[i] - cur[i]) * k
                if (abs(goal[i] - cur[i]) < 0.03) cur[i] = goal[i] else settled = false
            }
            write(cur)
            if (!settled) handler.postDelayed(this, 30)       // about 33 updates per second
        }
    }

    /** Every setter is a binder call, so send only what changed. */
    private fun write(db: DoubleArray) {
        val d = dp ?: return
        val m = mbc ?: return
        try {
            val sig = "$inputDb|$makeupDb|$limiterDb"
            val fixed = sig != sentFixed
            if (fixed) {
                d.setInputGainAllChannelsTo(inputDb)
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 50f, 20f, limiterDb, 0f))
                sentFixed = sig
            }
            for (i in 0 until 31) if (sentEq[i].isNaN() || abs(db[i] - sentEq[i]) > 0.05) {
                d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, Dsp.RENDER_CUT[i], db[i].toFloat()))
                sentEq[i] = db[i]
            }
            for (b in 0 until 6) {
                val thr = m.baseThresholdDb[b] + max(0.0, Dsp.MBC_RANGE[b].map { db[it] }.average())
                if (fixed || sentThr[b].isNaN() || abs(thr - sentThr[b]) > 0.1) {
                    d.setMbcBandAllChannelsTo(b, DynamicsProcessing.MbcBand(
                        true, Dsp.MBC_CUT[b], m.attackMs[b], m.releaseMs[b], m.ratio[b],
                        thr.toFloat(), m.kneeDb[b], -90f, 1f, 0f, makeupDb))
                    sentThr[b] = thr
                }
            }
        } catch (e: Exception) { /* log it; the caller falls back through the mode order */ }
    }
}
```

Usage:
- Slider drag or volume change: `engine.update(layers)`.
- Preset, route, profile or intensity change: `engine.load(layers, mbcFor(preset), routeBaseDb, limiterDb)`.
- Percussion Clarity uses the override table in section 5.2 for `Mbc`.

---

## 10. Smoothing, robustness and UI requirements

**Smoothing (no zipper noise, no clicks):**
- The engine glides 60 ms for slider moves and 250 ms for preset, route, profile and intensity changes.
- `inputGain` and `makeup` change only inside `load()`. Add a 100 ms ramp if you hear a step.
- **Write load:** each `DynamicsProcessing` setter is a binder call. Send only changed values, at most about 33 updates per second.

**Robustness:**
- Run as a foreground media-playback service and ask for a battery-optimisation exemption.
- The engine re-asserts its configuration when control is regained or another app disables the effect.
- Attach on `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and release on `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` so effects never leak.
- Wrap every effect call in try/catch and fall back through the mode order (Root, ADB/Shizuku, Legacy tier 2, Legacy tier 1).
- Offer a "Compatibility mode (10 bands)" switch for devices that glitch with 31 bands.

**UI requirements:**
- Headroom meter (section 3) instead of any automatic preamp.
- **A/B compare at matched loudness:** the Off state applies the same `makeupDb` offset so the user compares tone, not volume.
- Intensity control (Light / Standard / Deep+) and a "Volume contour" toggle.
- **Sound calibration** screen: headphone profile picker with search, speaker calibration wizard, strength slider, before/after graph, Reset.
- Show the active route and mode with **Available**, **Requires permission** or **Unsupported**.

---

## 11. What each mode gets

| Feature | Legacy tier 1 (native 5-band) | Legacy tier 2 / ADB / Shizuku (`DynamicsProcessing`) | Root |
|---|---|---|---|
| 31-band rendered curve | No | Yes | n/a (biquads) |
| 6-band ceilings and limiter | No | Yes | Yes (own DSP, same numbers) |
| Route profiles | Yes (5-band offsets) | Yes | Yes |
| Volume contour | Yes | Yes | Yes |
| Intensity | Yes | Yes | Yes |
| Headphone profiles and speaker calibration | No | Yes | Yes (exact biquads or FIR) |
| Bass harmonic enhancer | No | No | Yes |
| Independent bands | **No** (wide overlap) | Mostly | Yes |

Tier 1 cannot give clean separation. Show a "Basic engine" badge and use it only when `DynamicsProcessing` fails.

Not feasible here: object-based Atmos rendering and dialogue separation (licensed tech or ML models), and phase correction without Root.

---

## 12. Root-only additions

### 12.1 Biquads

| Band | Type | Q |
|---|---|---|
| 31 Hz | Low-shelf | 0.9 |
| 62 Hz | Peaking | 1.4 |
| 125 Hz | Peaking | 1.6 |
| 250 Hz | Peaking | 1.6 |
| 500 Hz – 4 kHz | Peaking | 1.6 |
| 8 kHz | Peaking | 1.4 |
| 16 kHz | High-shelf | 0.7 |

Percussion Clarity overrides: 250 Hz Q 1.0, 4 kHz Q 2.0. Do not exceed Q 2 on neighbouring bands, or dips appear between centres. Use Linkwitz-Riley 4th-order splits at the section 5.2 cutoffs for the ceilings. High-pass: 2nd-order Butterworth (Q 0.707) at the section 4.1 value. Headphone profiles can be applied as their own biquads instead of the 31-band approximation.

**Width crossovers:** leave as in v1 for now. When you return to width, move them to 354, 2828 and 11314 Hz so they match the category edges.

### 12.2 Bass harmonic enhancer

Take 40–100 Hz, soft-saturate it for 2nd and 3rd harmonics, high-pass the result at 100 Hz and mix it back. The brain infers deep bass from the harmonics, so bass feels bigger on small drivers without extra sub-bass energy.

| Preset | Mix |
|---|---|
| Flat | off |
| Balanced Deep | −12 dB |
| Bass Boost | −9 dB |
| Vocal Clarity | off |
| Percussion Clarity | −15 dB |
| Cinema | −12 dB |

Speaker route adds +3 dB to the mix.

### 12.3 Linear-phase correction (separate phase)

A FIR under the user curve, built from per-device measurements, is the step that matches Dirac's time-domain correction. It needs Root DSP and data for each output device. Not part of this release.

---

## 13. Legacy tier 1 values (native `Equalizer`)

Read the real band centres with `getCenterFreq()` and map to the nearest band. Values are log-frequency interpolations at 60, 230, 910, 3600 and 14000 Hz, capped at +4 dB because the native equalizer has no headroom stage. Units are millibels (×100).

| Native band | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity | Cinema |
|---|---|---|---|---|---|
| ~60 Hz | +4 | +4 | −2.6 | +1.2 | +4 |
| ~230 Hz | −1.9 | −1.3 | −2.8 | −2.8 | −1.5 |
| ~910 Hz | −0.2 | −0.6 | +1.1 | −0.3 | +0.3 |
| ~3.6 kHz | +2.3 | 0 | +3.5 | +3.2 | +2.5 |
| ~14 kHz | +1.7 | +0.5 | +0.7 | +1.8 | +2.0 |

| Effect | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity | Cinema |
|---|---|---|---|---|---|
| `BassBoost` (tier 1 only) | 150 | 400 | 0 | 100 | 200 |
| `Virtualizer` | 150 | 0 | 100 | 200 | 250 |

When `DynamicsProcessing` is active, set `BassBoost` to 0. It is a vendor effect stacked on a real EQ and adds unpredictable overlap.

---

## 14. Acceptance tests

Measure with a sweep or pink noise and an external rig (REW with a measurement mic or ear simulator, or a USB DAC loopback). Run music tests with loud material peaking near −3 dBFS.

| Test | Method | Pass |
|---|---|---|
| Slider centre accuracy | Flat, set each slider alone to +6 dB | Response at its own centre within ±0.5 dB of the slider |
| Band independence | Same setup, measure all 10 centres | ≥3 octaves away ≤ 0.2 dB. 2 octaves ≤ 0.5 dB. 1 octave ≤ 2.4 dB |
| No cross-ducking | Loud music, raise bass +6 dB, measure RMS from 500 Hz to 8 kHz | Change ≤ 0.5 dB |
| Slider-order independence | Reach the same curve through two different move orders | Identical output |
| Makeup frozen | Drag any slider while measuring overall loudness of an untouched band | No change in that band |
| Loudness parity | Section 5.3 material, bypass vs each preset | Within ±1 LU |
| Ceiling load | Bass Boost at full volume | Limiter gain reduction under 1 dB, no audible distortion |
| Distortion | 1 kHz sine at −6 dBFS with each preset | THD+N under 1% |
| Smoothness | Drag sliders fast, switch presets, switch route | No clicks, pops or zipper noise |
| Write load | Drag a slider for 10 s | At most 33 updates per second, app CPU under 5% on a mid-range device |
| Route and volume | Plug and unplug headphones, change volume | Profile changes within 300 ms, contour moves in gentle steps |
| Speaker calibration | Measure before and after correction | RMS error to target over 200 Hz–8 kHz improves by at least 20%, otherwise discarded |
| Headphone profile | Measure a known headphone with a profile | Response moves toward the profile target without clipping |
| Hearing safety | Never auto-raise volume | Warn above +6 dB total boost and above ceiling load 4 dB |

---

## 15. Preset JSON v4 (width omitted)

```json
{
  "bandsHz": [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000],
  "layers": {
    "effectiveClampDb": [-15, 10],
    "inputGainRule": "routeBase - max(0, max(correction31) - 3)",
    "makeup": { "model": "pinkPowerMean", "lowBandWeight": 0.5, "lowBandBelowHz": 100, "clampDb": [0, 4], "freezeWhileDragging": true }
  },
  "chain": {
    "render": { "bands": 31, "centresHz": "20 * 2^(i/3)", "interpolation": "pchip", "fallbackBands": [20, 10], "variant": "frequency_resolution" },
    "mbc": {
      "cutoffRenderBands": [6, 12, 18, 24, 27],
      "cutoffsHz": [90, 359, 1437, 5747, 11494, 20000],
      "renderRanges": [[0, 6], [7, 12], [13, 18], [19, 24], [25, 27], [28, 30]],
      "fallback10BandCutoffsHz": [88, 354, 1414, 5657, 11314, 20000],
      "attackMs": [40, 15, 12, 6, 1.5, 2],
      "releaseMs": [250, 160, 130, 90, 45, 50],
      "ratio": [4.0, 3.5, 2.5, 3.0, 3.5, 2.5],
      "baseThresholdDb": [-6, -6, -6, -7, -9, -8],
      "kneeDb": [6, 6, 6, 6, 4, 6],
      "boostTracking": true
    },
    "limiter": { "attackMs": 1, "releaseMs": 50, "ratio": 20, "thresholdDb": -1, "postGainDb": 0 },
    "writeLimits": { "maxUpdatesPerSecond": 33, "minEqDeltaDb": 0.05, "minThresholdDeltaDb": 0.1 },
    "smoothing": { "sliderGlideMs": 60, "presetGlideMs": 250, "gainChangeRampMs": 100 },
    "intensity": { "min": 0.5, "default": 1.0, "max": 1.5, "labels": { "Light": 0.6, "Standard": 1.0, "Deep+": 1.3 } }
  },
  "routes": {
    "speaker":   { "offsetsDb": [-9, -7, -2, 2, 1, 0, -1, -1, 0, 0], "inputGainDb": -3, "limiterDb": -1.0, "rootHighPassHz": 140, "harmonicMixDeltaDb": 3, "zeroOffsetsIfCalibrated": true },
    "wired":     { "offsetsDb": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], "inputGainDb": -3, "limiterDb": -1.0 },
    "bluetooth": { "offsetsDb": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], "inputGainDb": -4, "limiterDb": -1.5 },
    "usb":       { "offsetsDb": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], "inputGainDb": -3, "limiterDb": -1.0, "note": "may bypass effects" }
  },
  "volumeContour": {
    "enabledByDefault": true,
    "maxStepDbPer500ms": 1,
    "points": [
      { "v": 0.6,  "db": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
      { "v": 0.4,  "db": [1.5, 1.5, 1, 0, 0, 0, 0, 0, 0.5, 0.5] },
      { "v": 0.25, "db": [3, 3, 2, 0, 0, 0, 0, 0, 1, 1.5] },
      { "v": 0.1,  "db": [4.5, 4.5, 3, 0.5, 0, 0, 0, 0, 1.5, 2] }
    ]
  },
  "correction": {
    "limitsDb": { "maxBoost": 6, "maxBoostAbove6kHz": 3, "maxCut": -12 },
    "defaultStrength": { "speaker": 0.7, "headphone": 0.8 },
    "speaker": {
      "validHz": [200, 10000],
      "taperToZeroHz": [100, 20000],
      "targetAnchors": [[200, -2], [250, -1], [315, 0], [2000, 0], [4000, -1.5], [8000, -3], [10000, -3.5]],
      "sweep": { "startHz": 20, "endHz": 20000, "seconds": 4, "levelDbfs": -18, "runs": 3 },
      "irWindowMs": 20,
      "minSnrDb": 20,
      "runAgreementDb": 3,
      "keepIfErrorImprovesPct": 20,
      "maxStartVolume": 0.6
    },
    "headphone": {
      "taperToZeroHz": [10000, 14000],
      "filterTypes": ["PK", "LSC", "HSC"],
      "sampleRateHz": 48000
    }
  },
  "presets": {
    "flat": { "bypass": true, "gainsDb": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
    "balanced_deep": {
      "gainsDb": [3.5, 4.5, 2.5, -2.5, -1.5, 0, 1, 2.5, 2.5, 1.5],
      "highPassHz": 20, "bassHarmonicMixDb": -12,
      "legacy": { "nativeDb": [4, -1.9, -0.2, 2.3, 1.7], "bassBoost": 150, "virtualizer": 150 }
    },
    "bass_boost": {
      "gainsDb": [5.5, 6.5, 4, -2, -1.5, -0.5, 0, 0, 0.5, 0.5],
      "highPassHz": 25, "bassHarmonicMixDb": -9,
      "legacy": { "nativeDb": [4, -1.3, -0.6, 0, 0.5], "bassBoost": 400, "virtualizer": 0 }
    },
    "vocal_clarity": {
      "gainsDb": [-3.5, -2.5, -1, -3, -1.5, 1.5, 3.5, 3.5, 1.5, 0.5],
      "highPassHz": 60, "bassHarmonicMixDb": null,
      "legacy": { "nativeDb": [-2.6, -2.8, 1.1, 3.5, 0.7], "bassBoost": 0, "virtualizer": 100 }
    },
    "percussion_clarity": {
      "gainsDb": [-4, 1.5, 2.5, -3.5, -2, 0, 1.5, 3.5, 3, 1.5],
      "highPassHz": 40, "bassHarmonicMixDb": -15,
      "qOverrides": { "250": 1.0, "4000": 2.0 },
      "mbcOverrides": {
        "attackMs": [50, 30, 25, 12, 4, 5],
        "releaseMs": [250, 150, 120, 80, 50, 50],
        "ratio": [3.0, 2.8, 2.2, 2.5, 2.8, 2.0]
      },
      "legacy": { "nativeDb": [1.2, -2.8, -0.3, 3.2, 1.8], "bassBoost": 100, "virtualizer": 200 }
    },
    "cinema": {
      "gainsDb": [4, 4, 2, -2, -1, 0.5, 2.5, 2.5, 2, 2],
      "highPassHz": 20, "bassHarmonicMixDb": -12,
      "legacy": { "nativeDb": [4, -1.5, 0.3, 2.5, 2.0], "bassBoost": 200, "virtualizer": 250 }
    }
  }
}
```

Run section 14, then tune the input gain (−3 dB), ceiling thresholds, makeup trims and correction strengths on your target devices until every test passes.
