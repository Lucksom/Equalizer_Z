# Audio EQ: Deep Optimization v5

Self-contained reference for the Android equalizer. It replaces v4: sections 1–15 are v4 with a few marked changes, and sections 16–19 are new. **APK-only:** nothing in this document is flashed, installed into `/system` or `/vendor`, or shipped as a module. Everything runs from the APK, from a helper process the APK starts (Shizuku or `su`), or through the normal Android audio-effect APIs. From v1, keep only: setup steps (5.5), mode detection (9) and the **width** section (6.2, untouched for now).

All numbers are tuned starting points. Nothing here was measured on a device, and the Kotlin was not compiled. Verify with section 14.

---

## 0. What changed

### 0.1 v4 → v5

| Area | v4 | v5 | Why |
|---|---|---|---|
| Packaging | Pointed to v1 install steps | **Removed. APK-only.** | Only what an APK, Shizuku or `su` can do is in scope. |
| Headphone profiles | Generic profile schema | **AutoEq fetched from GitHub and converted on the device** (section 16) | A large catalogue without bundling a dataset in the APK. |
| Parametric evaluation rate | 48 kHz | **44.1 kHz for AutoEq data** (section 8.1) | AutoEq defines its parameters at 44.1 kHz. On the test profile in 16.7 the difference between the two rates is under 0.06 dB. |
| Root mode | "Own DSP, same numbers" | **Open decision** (section 18) | A biquad, FIR or saturator has to run inside the audio path. Without a flashed native effect, the APK has no such host. Options are in 18.3. |
| Features | None listed | **Per-mode roadmap** (section 17) | What Legacy, ADB/Shizuku and Root can each add. |
| Tests | Section 14 | **Plus AutoEq tests** (section 19) | Parser, conversion, network and cache behaviour. |

Unchanged from v4: sections 2–5, 7, 9, 10, 13, 14 and 15 and every number in them.

### 0.2 v3 → v4 (kept for history)

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
7. **Headphone profiles** (section 8.1), fed by **AutoEq import** (section 16).
8. **Speaker self-calibration** (section 8.2).
9. **Per-mode features** (section 17), starting with per-device profiles.
10. **Root helper and the DSP-host decision** (sections 12 and 18).

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
| Speaker | `TYPE_BUILTIN_SPEAKER` | −9, −7, −2, +2, +1, 0, −1, −1, 0, 0 | −3 | −1.0 | Protects the small driver, restores 250 Hz, softens the 2–4 kHz resonance. High-pass 140 Hz and +3 dB harmonic mix need a DSP host (section 18). If a speaker calibration exists (section 8.2), use zero offsets instead. |
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

Ship or download per-model correction profiles (parametric filter lists, as published for headphone targets) and let the user pick one, or suggest a match from `AudioDeviceInfo.productName`. **AutoEq is the primary source** (section 16): the app downloads one profile on demand and converts it into the schema below. User imports and any bundled profiles use the same schema.

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
1. Evaluate each filter's magnitude (RBJ cookbook biquads, at the rate the profile was designed for: **44.1 kHz for AutoEq**, 48 kHz otherwise) at the 31 render centres and sum in dB.
2. Taper to zero between 10 kHz and 14 kHz. Ear-canal variation makes the top octave unreliable.
3. Limit to +6 dB boost (+3 dB above 6 kHz) and −12 dB cut.
4. Multiply by strength, default **0.8**.
5. Ignore `preampDb`. The engine's `inputGain` rule already handles headroom.

Check the license and attribution terms of whichever dataset you ship (see 16.8 for AutoEq). Show the profile name and source in the UI.

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
| 31-band rendered curve | No | Yes | Yes (same engine as ADB/Shizuku, section 18) |
| 6-band ceilings and limiter | No | Yes | Yes (same engine, same numbers) |
| Route profiles | Yes (5-band offsets) | Yes | Yes |
| Volume contour | Yes | Yes | Yes |
| Intensity | Yes | Yes | Yes |
| Headphone profiles (AutoEq) and speaker calibration | Approximate (native bands, 16.4) | Yes (31-band) | Yes (31-band; exact biquads or FIR need a DSP host, section 18) |
| Bass harmonic enhancer | No | No | Only with a DSP host (section 18) |
| Independent bands | **No** (wide overlap) | Mostly | Mostly (same engine), unless a DSP host exists |

Tier 1 cannot give clean separation. Show a "Basic engine" badge and use it only when `DynamicsProcessing` fails.

Not feasible here: object-based Atmos rendering and dialogue separation (licensed tech or ML models), and phase correction without Root.

---

## 12. Root-only additions (DSP-host reference)

> **APK-only note (v5):** the three items below need code running *inside* the audio path (a biquad, FIR or saturator). The standard effect APIs do not provide one, and a native effect loaded by the audio server would have to be flashed, which is out of scope. Keep this section as the design reference. It becomes buildable only if the decision in section 18.3 picks an in-app engine or another host.

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

---

## 16. AutoEq integration

**Goal:** the user searches for their headphone and taps once. The app downloads that model's AutoEq result from GitHub, converts it to the 31-band `correction31` layer (section 3) and applies it under the sliders. No dataset is bundled in the APK.

### 16.1 What AutoEq publishes (checked 2026-10-30)

| Item | Finding | Status |
|---|---|---|
| Repository | `jaakkopasanen/AutoEq`, branch `master`, MIT license | Verified (repo page, PyPI metadata) |
| Pre-computed results | A `results` folder exists. The README says normal users should use autoeq.app instead, so treat the folder as a developer-facing data source that can change | Verified (README) |
| Layout | `results/{source}/{form or rig}/{model}/`. Example from the README: `results/oratory1990/over-ear/Sennheiser HD 800/` | Verified |
| Layout change | Version 4.0.0 (2023-10-29) reorganised the directories, so older guides show different paths. **Never build paths from memory; take them from the index** | Verified (README) |
| Files per model | `{model} ParametricEQ.txt`, `{model} FixedBandEQ.txt`, `{model} GraphicEQ.txt`, `{model}.csv`, `{model}.png`, `README.md`, FIR WAVs. ParametricEQ and FixedBandEQ are optional, GraphicEQ is always written | Verified (third-party summary of the batch code) |
| Index | `results/INDEX.md`, one line per result: `- [Name](./source/rig/Name) by source on rig` (`on rig` is optional) | **Partly verified.** The line format comes from an open-source reader of that file. GitHub's web pages could not be fetched automatically here, so open the file once by hand and compare |
| Sources | oratory1990, crinacle, Innerfidelity, Rtings, legacy headphone.com | Verified (README) |
| ParametricEQ format | `Preamp: -4.70 dB`, then `Filter N: ON PK Fc 3464.3 Hz Gain 4.5 dB Q 1.99`. Types are `PK`, `LSC`, `HSC`. Unused slots read `OFF` | Verified (README and public posts quoting the files) |
| Sample rate the parameters assume | 44.1 kHz (the README says the values match EqualizerAPO at 44.1 kHz) | Verified |
| GraphicEQ format | One line, `GraphicEQ: 20 -0.5; 21 -0.5; ...`, frequency and gain pairs. The README says the preamp is already included in the values | Verified |
| FixedBandEQ format | A 10-band file of peaking filters with Q 1.41. Read the Fc values from the file instead of assuming centres. Assumed to use the same `Filter` line syntax as ParametricEQ | Partly verified (sample in a fork's README) |
| autoeq.app backend | `GET /entries`, `GET /targets`, `POST /equalize` exist in the web app's code. Not documented for third-party use | Read from a third-party code summary. Optional and unverified (16.9) |
| Catalogue size, file sizes, INDEX.md size | Not checked | **Unknown.** Log them on first run and set the caps in 16.3 from real numbers |

### 16.2 Architecture

```
SearchBar -> ProfileRepository
  index    : <mirror>/INDEX.md  -> fallback: raw GitHub results/INDEX.md   (same parser for both)
  profile  : <base>/<path>/<model> ParametricEQ.txt -> FixedBandEQ.txt -> GraphicEQ.txt
  convert  : file -> filters or curve -> correction31                      (16.4)
  apply    : Layers.correction31 -> engine.load()                          (sections 3 and 9)
  cache    : index (ETag, at most one refresh per 24 h), parsed profiles (kept until the user re-downloads)
```

**Where to fetch from.** The default base URL is `https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/`. Two constraints shape the design:

- GitHub's changelog of 2025-05-08 says it lowered the limits for unauthenticated requests and names `raw.githubusercontent.com`. The new numbers are not published. The documented REST limit for unauthenticated calls is 60 per hour per IP. Mobile carriers can put many users behind one IP, so one user's light usage does not guarantee they avoid a 429.
- So keep the request count low (one index fetch per day, one small file per profile) and add a **mirror** you control: a scheduled GitHub Action in your own repository that copies `INDEX.md` and the profile files you want to GitHub Pages or Cloudflare Pages, under the same relative paths. The app then only swaps the base URL. Keep the MIT notice and the source names with the mirrored data. The app tries the mirror first and falls back to raw GitHub.
- Keep the base URLs in a config value so they can change without an app update.

### 16.3 Fetch rules

- Permission: `INTERNET` only (optionally `ACCESS_NETWORK_STATE` to skip fetches while offline).
- HTTPS only, with an allow-list of hosts (raw GitHub and your mirror). Timeouts 10 s connect, 15 s read. All network work on `Dispatchers.IO`, never on the audio path.
- Index: send `If-None-Match` with the saved ETag, one ETag file per base URL. A 304 costs almost nothing. Refresh at most once per 24 hours, plus a manual refresh with a 15-minute minimum gap.
- On 403 or 429: keep serving the cache, honour `Retry-After`, otherwise back off 1 minute, then 10, then 60. Show a small "limited, using saved data" chip. Never loop.
- Size caps (placeholders until you have real numbers): profile files 64 KB, index 8 MB. Reject anything larger.
- Take `path` from the index as it is (already percent-encoded) and reject anything containing `..`, a backslash or a scheme.
- Parse defensively: skip lines that do not match, ignore filters outside Fc 10–24000 Hz, Q 0.1–20 and |gain| ≤ 30 dB, keep at most 32 filters.

### 16.4 Conversion rules

| Input | Conversion | Notes |
|---|---|---|
| ParametricEQ.txt | Evaluate every enabled filter's biquad magnitude at the 31 centres at **44.1 kHz**, sum in dB, taper 10–14 kHz, limit +6 / −12 dB (+3 dB above 6 kHz), multiply by strength (section 8.1). Ignore `Preamp`. | Best fidelity. Checked against a Python reference (16.7). |
| FixedBandEQ.txt | Same function with the file's peaking filters. | The ten Q 1.41 filters overlap, so the **gain column is not the response at that centre**. Never copy these gains onto the sliders. |
| GraphicEQ.txt | Interpolate in log-frequency to the 31 centres, subtract the mean of 500 Hz–2 kHz (the preamp is baked into the values, so the level must be re-centred), then taper, limit and apply strength as above. | Fallback when ParametricEQ is missing. |

The result goes into `Layers.correction31`. The `inputGain` rule in section 3 already lowers the input when the correction exceeds +3 dB.

**Trim notice.** `trimmedDb = max(raw) − max(limited)`. If it is above 1 dB, show "Trimmed by X dB to protect headroom" and offer a **Full range** switch that raises the boost limit to +10 dB (the effective clamp in section 3 stays −15 to +10) and shows the headroom warning. The limits in section 8.1 are v4's design choices, not AutoEq's, so some profiles may ask for more. Test with real profiles.

**Strength.** Default 0.8 (v4). 100% is the profile as published. 0% must be identical to "no correction".

**Tilt knobs (optional, same layer).** Two sliders that add to `correction31` before limiting, in the spirit of AutoEq's own bass and tilt options: Bass (low shelf at 105 Hz, Q 0.7, −6 to +6 dB) and Treble (high shelf at 6 kHz, Q 0.7, −4 to +4 dB). Evaluate both with the same biquad function. These are the app's own definitions, not AutoEq's.

**Legacy tier 1 (native `Equalizer`).** Read each native band's edges with `getBandFreqRange()` (millihertz, per the API) and use the **mean of the 31-band curve over that band**, not a single point, because the native bands are wide. Cap at +4 dB (section 13). Badge it "Approximate".

### 16.5 Kotlin (paste-ready, not compiled)

```kotlin
package com.yourapp.eq.autoeq

import com.yourapp.eq.Dsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.*

data class PeqFilter(val type: String, val fc: Double, val gainDb: Double, val q: Double)
data class PeqProfile(val preampDb: Double, val filters: List<PeqFilter>)
data class IndexEntry(val name: String, val source: String?, val rig: String?, val path: String)

private val FILTER_RE = Regex(
    """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+(PK|LSC|HSC)\s+Fc\s+([-+0-9.eE]+)\s*Hz\s+Gain\s+([-+0-9.eE]+)\s*dB\s+Q\s+([-+0-9.eE]+)""",
    RegexOption.IGNORE_CASE
)
private val PREAMP_RE = Regex("""^\s*Preamp\s*:\s*([-+0-9.eE]+)\s*dB""", RegexOption.IGNORE_CASE)
private val INDEX_RE = Regex("""^- \[(.+?)\]\(\./(.+?)\)(?= by | on |$)(?: by (.+?))?(?: on (.+))?$""")

fun parseIndex(text: String): List<IndexEntry> = text.lineSequence().mapNotNull { line ->
    val m = INDEX_RE.matchEntire(line.trim()) ?: return@mapNotNull null
    val path = m.groupValues[2]
    if (path.contains("..") || path.contains('\\') || path.contains("://")) return@mapNotNull null
    IndexEntry(m.groupValues[1], m.groups[3]?.value, m.groups[4]?.value, path)
}.toList()

fun parsePeq(text: String): PeqProfile {
    var preamp = 0.0
    val out = ArrayList<PeqFilter>()
    for (line in text.lineSequence()) {
        val p = PREAMP_RE.find(line)
        if (p != null) { preamp = p.groupValues[1].toDoubleOrNull() ?: 0.0; continue }
        val m = FILTER_RE.find(line) ?: continue
        if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
        val fc = m.groupValues[3].toDoubleOrNull() ?: continue
        val g = m.groupValues[4].toDoubleOrNull() ?: continue
        val q = m.groupValues[5].toDoubleOrNull() ?: continue
        if (fc in 10.0..24000.0 && q in 0.1..20.0 && abs(g) <= 30.0 && out.size < 32)
            out += PeqFilter(m.groupValues[2].uppercase(), fc, g, q)
    }
    return PeqProfile(preamp, out)
}

fun parseGraphic(text: String): List<Pair<Double, Double>> {
    val body = Regex("""GraphicEQ\s*:\s*(.+)""").find(text)?.groupValues?.get(1) ?: return emptyList()
    return body.split(';').mapNotNull { part ->
        val t = part.trim().split(Regex("\\s+"))
        val f = t.getOrNull(0)?.toDoubleOrNull()
        val g = t.getOrNull(1)?.toDoubleOrNull()
        if (t.size == 2 && f != null && g != null && f > 0) f to g else null
    }
}

/** RBJ cookbook magnitude in dB of one filter at atHz. */
fun biquadDb(f: PeqFilter, atHz: Double, fs: Double): Double {
    val a = 10.0.pow(f.gainDb / 40.0)
    val w0 = 2 * PI * f.fc / fs
    val cw = cos(w0)
    val alpha = sin(w0) / (2 * f.q)
    val s = 2 * sqrt(a) * alpha
    val b: DoubleArray
    val d: DoubleArray
    when (f.type) {
        "PK" -> {
            b = doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a)
            d = doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
        }
        "LSC" -> {
            b = doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s))
            d = doubleArrayOf((a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s)
        }
        else -> { // HSC
            b = doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s))
            d = doubleArrayOf((a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s)
        }
    }
    val w = 2 * PI * atHz / fs
    fun mag2(c: DoubleArray): Double {
        val re = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
        val im = -(c[1] * sin(w) + c[2] * sin(2 * w))
        return re * re + im * im
    }
    return 10 * log10(mag2(b) / mag2(d))
}

fun taper(f: Double): Double = when {
    f <= 10000.0 -> 1.0
    f >= 14000.0 -> 0.0
    else -> 1.0 - log2(f / 10000.0) / log2(1.4)
}

fun peqToCorrection31(
    filters: List<PeqFilter>, strength: Double = 0.8, fs: Double = 44100.0, fullRange: Boolean = false
): DoubleArray = DoubleArray(31) { i ->
    val f = Dsp.RENDER_HZ[i]
    val raw = filters.sumOf { biquadDb(it, f, fs) } * taper(f)
    val hi = if (fullRange) 10.0 else if (f > 6000.0) 3.0 else 6.0
    raw.coerceIn(-12.0, hi) * strength
}

fun graphicToCorrection31(points: List<Pair<Double, Double>>, strength: Double = 0.8): DoubleArray {
    val xs = points.map { log2(it.first) }
    fun at(f: Double): Double {
        val x = log2(f)
        if (x <= xs.first()) return points.first().second
        if (x >= xs.last()) return points.last().second
        val k = (0 until xs.size - 1).first { x <= xs[it + 1] }
        val t = (x - xs[k]) / (xs[k + 1] - xs[k])
        return points[k].second + (points[k + 1].second - points[k].second) * t
    }
    val cur = DoubleArray(31) { at(Dsp.RENDER_HZ[it]) }
    val mid = (0 until 31).filter { Dsp.RENDER_HZ[it] in 500.0..2000.0 }
    val ref = mid.map { cur[it] }.average()
    return DoubleArray(31) { i ->
        val f = Dsp.RENDER_HZ[i]
        ((cur[i] - ref) * taper(f)).coerceIn(-12.0, if (f > 6000.0) 3.0 else 6.0) * strength
    }
}

private val NOISE = setOf("wireless", "bluetooth", "headphones", "headphone", "earbuds", "anc", "edition")
private fun tokens(s: String) =
    s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.isNotEmpty() && it !in NOISE }

/** 0..1. Suggest only above about 0.6 and always let the user confirm. */
fun matchScore(deviceName: String, entryName: String): Double {
    val q = tokens(deviceName); val n = tokens(entryName).toSet()
    if (q.isEmpty() || n.isEmpty()) return 0.0
    return q.count { it in n }.toDouble() / max(q.size, n.size)
}

class AutoEqRepo(private val http: OkHttpClient, private val dir: File, private val bases: List<HttpUrl>) {
    private val maxIndex = 8_000_000L
    private val maxProfile = 64_000L

    suspend fun index(force: Boolean = false): List<IndexEntry> = withContext(Dispatchers.IO) {
        val f = File(dir, "index.md")
        val age = if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
        if (f.exists() && age < 24 * 3_600_000L && !(force && age > 15 * 60_000L))
            return@withContext parseIndex(f.readText())
        for (base in bases) {
            val tag = File(dir, "index-${base.host}.etag")
            val url = base.resolve("INDEX.md") ?: continue
            val req = Request.Builder().url(url)
                .apply { if (f.exists() && tag.exists()) header("If-None-Match", tag.readText()) }.build()
            try {
                http.newCall(req).execute().use { r ->
                    if (r.code == 304 && f.exists()) {
                        f.setLastModified(System.currentTimeMillis())
                        return@withContext parseIndex(f.readText())
                    }
                    val body = r.body
                    if (r.isSuccessful && body != null && body.contentLength() <= maxIndex) {
                        f.writeText(body.string())
                        r.header("ETag")?.let { tag.writeText(it) }
                        return@withContext parseIndex(f.readText())
                    }
                    // 403, 429, 5xx or oversized: try the next base, then fall back to the cache
                }
            } catch (_: IOException) { /* offline: try the next base */ }
        }
        if (f.exists()) parseIndex(f.readText()) else emptyList()
    }

    suspend fun profileText(e: IndexEntry, suffix: String): String? = withContext(Dispatchers.IO) {
        for (base in bases) {
            val dirUrl = base.newBuilder().addEncodedPathSegments(e.path).build()
            val url = dirUrl.newBuilder().addPathSegment("${dirUrl.pathSegments.last()} $suffix").build()
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) {
                        val text = r.peekBody(maxProfile + 1).string()
                        if (text.length <= maxProfile) return@withContext text
                    }
                }
            } catch (_: IOException) { }
        }
        null
    }

    /** ParametricEQ first, then FixedBandEQ, then GraphicEQ. Returns null when nothing usable was found. */
    suspend fun load(e: IndexEntry, strength: Double = 0.8, fullRange: Boolean = false): DoubleArray? {
        for (suffix in listOf("ParametricEQ.txt", "FixedBandEQ.txt")) {
            val p = profileText(e, suffix)?.let { parsePeq(it) }
            if (p != null && p.filters.isNotEmpty()) return peqToCorrection31(p.filters, strength, 44100.0, fullRange)
        }
        val g = profileText(e, "GraphicEQ.txt")?.let { parseGraphic(it) }
        if (g != null && g.size >= 8) return graphicToCorrection31(g, strength)
        return null
    }
}
```

Notes for the build:
- Needs OkHttp and kotlinx-coroutines. `Dsp.RENDER_HZ` is from section 9.
- Add the trim notice by computing the unlimited response once (same loop without `coerceIn`) and comparing maxima.
- Per-device memory: key `route + productName (+ address if the OS provides it)`. The address and Bluetooth names may be empty without the Bluetooth permission on newer Android versions, so fall back to the route alone.
- The `FixedBandEQ` branch assumes the same `Filter` line syntax as ParametricEQ. If a real file does not parse, `parsePeq` returns no filters and the code falls through to GraphicEQ.

### 16.6 Screens and flows (brief for the app builder)

1. **Sound calibration → Headphone correction card:** current profile name, source, strength; buttons Change and Remove.
2. **Picker:** search field (searches the local index, debounced 250 ms); rows show name, a source chip and the rig; keep the index's order for entries with the same name (the index lists the preferred source first, per the third-party reader); a **"Suggested for <connected device>"** row at the top when `matchScore` is at least 0.6.
3. **Detail:** before/after curve over the 31 bands, the filter list, a strength slider (default 80%), a Full range switch, the trim notice, and Apply.
4. **Per-device memory:** store `{route, productName, address?}` → `{index entry, strength, fullRange, preset, intensity}` and apply it when that device connects.
5. **Import and export:** paste or pick a ParametricEQ-style text or a `GraphicEQ:` line; export the current correction as the same text.
6. **States:** first run offline (no cache), rate-limited chip, ParametricEQ missing (badge "Graphic EQ approximation"), mode badges from section 10.
7. **About:** "Profile data: AutoEq (MIT). Measurements by <source>." with the MIT notice.

What AutoEq does in each mode:

| Mode | Behaviour |
|---|---|
| Legacy tier 1 | Band-averaged onto the native bands, +4 dB cap, "Approximate" badge, no ceilings |
| Legacy tier 2 / ADB / Shizuku | Full 31-band correction layer, ceilings, `inputGain` rule |
| Root | Same as ADB/Shizuku. Exact biquads only if a DSP host exists (18.3) |

### 16.7 Golden test vectors

Produced by `autoeq_reference.py` (pure Python, same formulas). The Kotlin port must match every value within 0.05 dB.

**Vector A, ParametricEQ.** Input is the AutoEq-format profile below, as quoted in a public forum post, plus one disabled slot. Settings: strength 0.8, 44.1 kHz, defaults.

```
Preamp: -4.70 dB
Filter 1: ON LSC Fc 105.0 Hz Gain -3.2 dB Q 0.70
Filter 2: ON PK Fc 174.8 Hz Gain -4.3 dB Q 0.93
Filter 3: ON PK Fc 718.6 Hz Gain 2.4 dB Q 1.85
Filter 4: ON PK Fc 3464.3 Hz Gain 4.5 dB Q 1.99
Filter 5: ON HSC Fc 10000.0 Hz Gain 3.4 dB Q 0.70
Filter 6: OFF PK Fc 0 Hz Gain 0.0 dB Q 0.00
```

Expected `correction31` (index 0 = 20 Hz, index 30 = 20.5 kHz):

```
-2.609, -2.635, -2.672, -2.722, -2.778, -2.828, -2.864, -2.965, -3.315, -3.731, -3.341,
-2.263, -1.290, -0.552,  0.194,  1.324,  1.473,  0.674,  0.396,  0.403,  0.653,  1.437,
 3.337,  2.528,  1.056,  0.682,  0.876,  1.430,  0.560,  0.000,  0.000
```

Max is 3.337 dB, so the input gain drops by an extra 0.337 dB (section 3). Evaluating the same filters at 48 kHz instead moves the raw response by at most 0.058 dB.

**Vector B, GraphicEQ (synthetic input).**

```
GraphicEQ: 20 -0.5; 25 -0.5; 32 -0.9; 40 -2.1; 63 -3.6; 100 -5.0; 125 -1.9; 250 -2.5; 500 -1.0; 1000 -0.5; 2000 -0.4; 4000 2.0; 8000 -3.0; 16000 -6.0; 20000 -6.0
```

Strength 0.8, expected:

```
 0.115,  0.104, -0.195, -1.165, -1.776, -2.384, -2.945, -3.398, -1.016, -1.176, -1.336,
-1.458, -1.058, -0.658, -0.276, -0.143, -0.010,  0.116,  0.143,  0.170,  0.238,  0.878,
 1.518,  2.023,  0.690, -0.643, -1.940, -2.547, -0.860,  0.000,  0.000
```

**Vector C, index lines.**

| Line | Parsed |
|---|---|
| `- [Sennheiser HD 650](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650) by crinacle on GRAS 43AG-7` | name `Sennheiser HD 650`, path `crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650`, source `crinacle`, rig `GRAS 43AG-7` |
| `- [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990` | source `oratory1990`, rig none |
| `- [Sony WH-1000XM4 (ANC)](./oratory1990/over-ear/Sony%20WH-1000XM4%20(ANC)) by oratory1990` | path keeps the `(ANC)` |
| `- [Odd name](./rtings/in-ear/Odd%20name)` | source none, rig none |
| `# heading line` | skipped |

These lines follow the format described in 16.1. Compare them with the real `INDEX.md` before trusting the parser.

### 16.8 Licensing and attribution

- AutoEq itself is MIT licensed (verified). The README says the measurements come from oratory1990, crinacle, Innerfidelity, Rtings and legacy headphone.com. Whether each source's terms allow redistribution inside an app was **not verified here**.
- Lowest-risk pattern: do not bundle the dataset in the APK, download one profile when the user asks, show the source on the profile, and show an "AutoEq (MIT)" notice on an About screen. A mirror republishes the data, so read each source's terms before running one. This is not legal advice.

### 16.9 Later options (not in this release)

- **Custom target, bass boost or filter count** through autoeq.app's `POST /equalize`. The endpoint appears in the web app's code, but nothing found says third-party apps may use it. Check CORS, rate limits and permission first. The on-device tilt knobs in 16.4 cover most of the need without it.
- **Compact `index.json`** on the mirror if the `INDEX.md` download turns out too large for slow connections.
- **Bundled starter pack** of the most popular models (a few hundred KB) so the first run works offline. Settle licensing (16.8) first.

---

## 17. Feature roadmap by mode (APK-only)

### 17.1 What each profile is allowed to add

| Profile | Identity the code runs as | What it unlocks | Needs from the user |
|---|---|---|---|
| Legacy | The app itself | Native `Equalizer`, `BassBoost`, `Virtualizer`, and `DynamicsProcessing` on audio sessions that apps announce | Normal permissions only. `RECORD_AUDIO` for speaker calibration |
| ADB / Shizuku | Shell identity through the Shizuku service | `DynamicsProcessing` on the global output mix (section 9), reading `dumpsys` output, other shell-level calls | Shizuku running (or ADB setup). Check its state at launch and show "Shizuku not running" instead of failing silently |
| Root | A helper process started with `su` | Everything in ADB/Shizuku, plus start-on-boot and a few device-specific controls (section 18) | Root granted. Still no flashing, no writes to `/system` or `/vendor` |

### 17.2 Features

Effort: S small, M medium, L large.

| # | Feature | Legacy | Tier 2 / ADB / Shizuku | Root | Effort | Why it helps |
|---|---|---|---|---|---|---|
| F1 | AutoEq correction (section 16) | Approximate | Yes | Yes | M | Fixes the headphone's own response. After headroom management it is the largest audible gain here |
| F2 | Per-device profile memory and auto-switch (route, product name, address if available) | Yes | Yes | Yes | S | The right correction loads when the headphone connects |
| F3 | Tilt knobs: bass shelf and treble shelf on top of the target (16.4) | Approximate | Yes | Yes | S | Taste on top of a neutral base without touching ten sliders |
| F4 | Independent left and right EQ and balance. The engine already creates two channels, and the API has per-channel EQ and gain setters. Replace the `...AllChannelsTo` calls with per-channel loops and keep the write budget by sending only changed bands | No | Yes | Yes | M | Compensates unequal hearing or unequal drivers |
| F5 | Personal hearing curve: in-app tone test per ear, capped at ±6 dB, strength 0.5, stored per route, engine bypassed during the test (like 8.2) | Approximate | Yes | Yes | L | Personalises on top of F1. Absolute level is uncalibrated, so keep it gentle. Not a medical test |
| F6 | ABX and blind A/B tool: random Off (matched loudness) versus On, with a score | Yes | Yes | Yes | S | Shows whether a change is audible and stops placebo tuning |
| F7 | USB DAC bit-perfect switch (Android 14+, `setPreferredMixerAttributes` with the bit-perfect behaviour). Android's docs say mixing, volume and effect processing are not applied in that mode, so the UI must read "bit-perfect on, EQ off" | Yes | Yes | Yes | S | Honest option for DAC users. Query the supported mixer attributes first, and hide the switch when none are returned |
| F8 | Night mode: lower the 6 ceiling thresholds and raise the ratios, makeup unchanged | No | Yes | Yes | S | Quiet listening without peaks |
| F9 | Import and export: ParametricEQ-style text, a `GraphicEQ:` line, app JSON | Yes | Yes | Yes | S | Moves settings between apps and devices |
| F10 | Codec and sample-rate readout from `dumpsys` to choose Bluetooth headroom (today a flat −4 dB) | No | Yes (read-only) | Yes | M | Experimental. Output formats differ by Android version and OEM |
| F11 | Per-app profiles: attach to one app's audio session instead of the global mix | No | Research | Research | L | Needs session and owner discovery that was not verified |
| F12 | Start on boot without re-pairing | No | Depends on how Shizuku was started | Yes | S | Root helper launched at boot. Show the state so the user knows when EQ is off |
| F13 | OEM effect control (vendor sound profiles through their services or properties) | No | Sometimes | Often | M per device | Optional plug-in per OEM. Never required |
| F14 | Live spectrum | Research | Research | Research | M | The `Visualizer` effect needs `RECORD_AUDIO`, and its use on the global mix on newer Android was not verified |
| F15 | Crossfeed and spatial widening | No | No | No | n/a | `DynamicsProcessing` has no cross-channel mixing. Needs a DSP host (18.3) |
| F16 | Linear-phase FIR, bass harmonic enhancer, high-pass | No | No | Only with a DSP host | L | Section 12 and 18.3 |

### 17.3 Suggested order

1. Small, high value, all modes: F2, F3, F6, F9.
2. Small, mode-specific: F7 (USB DAC users), F8 and F4 (ADB/Shizuku and Root).
3. Larger: F5, then F10 and F12.
4. Research only: F11, F14, and the DSP-host decision in section 18.

### 17.4 What to build for each profile

- **Legacy:** F1 (approximate), F2, F3, F5, F6, F7, F9. Permissions: `INTERNET`, foreground-service notification, `RECORD_AUDIO` only for speaker calibration.
- **ADB / Shizuku:** everything in Legacy at full resolution, plus F4, F8, F10 (read-only), and the global-mix engine from section 9.
- **Root:** everything in ADB/Shizuku, plus F12, F13 and the items in 18.2.

---

## 18. Root mode in APK-only form

### 18.1 Principle

Root mode is allowed to do what the app can do while running as root. It does not flash anything, remount partitions, install a module, edit audio configuration files in `/system` or `/vendor`, or inject a library into the audio server.

### 18.2 What root adds over ADB/Shizuku

| Addition | Confidence | Notes |
|---|---|---|
| A helper process started with `su` that gives the same binder access as Shizuku (Shizuku itself can also be started with root) | Known pattern | Works without a USB or Wi-Fi debugging session |
| Start on boot: a boot receiver launches the helper through `su` | Known pattern | The root manager may ask for consent. Android can delay the broadcast. Show the state |
| More privilege for attaching to the global mix and for audio-service calls | **Unverified** | Permission checks differ by Android version and OEM. Do not promise more than ADB/Shizuku can do until tested on the target phones. The fallback ladder in section 10 stays |
| Switching off Bluetooth absolute volume from the helper | Partly verified | The Developer option "Disable absolute volume" exists. The matching property name was not checked. This makes the section 7 assumption (the effect sees volume-scaled audio) hold on Bluetooth. Without root, tell the user to switch the Developer option on |
| OEM effect control (F13) | Device-specific | One small plug-in per OEM, off by default |

### 18.3 Open decision R-1: where would 12.1–12.3 run?

Sections 12.1–12.3 (exact biquads, bass harmonic enhancer, linear-phase FIR) need code in the audio path. The standard effect types give you equaliser, bass boost, virtualizer, loudness enhancer, `DynamicsProcessing` and whatever the OEM added. They do not give a custom biquad or FIR.

| Option | What it gives | Cost and risk | Verdict |
|---|---|---|---|
| a. Built-in effects only (`DynamicsProcessing` and friends) | The whole of sections 3–10 and 16. Root collapses to "ADB/Shizuku plus section 18.2" | None beyond what the doc already covers | **Use for v5** |
| b. In-app engine: the app plays audio itself through its own pipeline (biquads, FIR, harmonics) | Exact DSP, but only for audio the app plays | Large build. Needs an in-app player, which this brief does not describe | Later, if the app gets a player |
| c. Capture system audio, process it, play it again | System-wide custom DSP | Needs a capture permission flow, apps can opt out, the original stream may keep playing, latency. How other apps solve the original-stream problem was not verified here | Research only |
| d. A native effect library loaded by the audio server | The usual way dedicated DSP mods work | Needs files in `/vendor` or `/system` plus configuration, which means flashing | **Excluded** by the APK-only rule |

Until R-1 is settled, treat 12.1–12.3, the speaker-route high-pass and harmonic mix, and the "High-pass (Root)" row in 4.1 as design reference, not build items.

---

## 19. AutoEq acceptance tests

Unit tests run on the JVM with no network. Device tests need a phone.

| Test | Method | Pass |
|---|---|---|
| Parser, ParametricEQ | Vector A | Disabled slot ignored, 5 filters, preamp −4.7 |
| Conversion, ParametricEQ | Vector A | Every value within 0.05 dB of 16.7 |
| Conversion, GraphicEQ | Vector B | Every value within 0.05 dB |
| Index parser | Vector C, then the real `INDEX.md` | Entries equal the number of lines starting with `- [`, or the difference is explained by lines that do not match |
| Strength 0 | Apply any profile at strength 0 | Output identical to no correction |
| Malformed input | Missing `Hz`, `Q 0`, `Fc 99999`, a 70 KB file, a path with `..` | Rejected or skipped, no crash |
| Fallback order | Serve 404 for ParametricEQ, then also for FixedBandEQ | GraphicEQ is used and the badge shows |
| Cache and ETag | Fetch the index twice within a minute, then after 24 hours | No network call the first time, a conditional request the second time |
| Rate limit | Make the server return 429 | Cached data still works, one retry at most per minute, chip shown |
| Offline first run | Airplane mode, empty cache | Clear message, no crash, imports still work |
| Mirror fallback | Block the mirror host | The app falls back to raw GitHub |
| Trim notice | A profile with a boost above +6 dB | "Trimmed by X dB" shows the right number. Full range changes the limit and shows the headroom warning |
| Tier 1 mapping | Convert Vector A to the native bands | Equals the band-averaged curve of the 31 values, capped at +4 dB |
| Apply while playing | Select a profile during playback | No clicks (250 ms glide, section 10) |
| Per-device memory | Connect, disconnect, reconnect a headphone | The saved profile returns within 300 ms |
| Attribution | Open About and a profile detail | Source name and MIT notice are visible |
| On-device accuracy | Measure a known headphone with and without its profile (section 14 method) | The response moves toward the profile target without clipping |

---

Run sections 14 and 19, then tune the input gain (−3 dB), ceiling thresholds, makeup trims and correction strengths on your target devices until every test passes.
