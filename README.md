# Audio EQ — Deep Optimization v4 (Material 3 System-Wide Equalizer)

A system-wide Android audio equalizer built with **Kotlin** and **Jetpack Compose (Material Design 3 / Monet Dynamic Color)** implementing the **Audio EQ: Deep Optimization v4** specification.

---

## 1. Frequency Sections & 10-Band Master Layout

The UI exposes a clean 10-band octave layout (`-12 dB` to `+12 dB`) with super-fluid zero-slop vertical drag sliders, mapped to four acoustic frequency categories and internally interpolated via **monotone cubic PCHIP** onto a **31-band third-octave renderer** (`20 * 2^(i/3)` Hz):

| Section | True Acoustic Range | UI Bands (Hz) | Root Biquad Filter Types & Default $Q$ |
|---|---|---|---|
| **Bass** | `20–350 Hz` | `31`, `62`, `125`, `250` | `31 Hz` Low-Shelf ($Q=0.9$), `62–250 Hz` Peaking ($Q=1.4, 1.6, 1.6$) |
| **Mids** | `350 Hz–2.8 kHz` | `500`, `1k`, `2k` | Peaking EQ ($Q=1.6, 1.4, 1.4$) |
| **Treble** | `2.8–11 kHz` | `4k`, `8k` | Peaking EQ ($Q=1.6, 1.4$) |
| **Air** | `11–20 kHz` | `16k` | High-Shelf ($Q=0.7$) |

---

## 2. Built-in v4 Master Presets

| Category | Band | Flat (Bypassed) | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity | Cinema |
|---|---|---|---|---|---|---|---|
| **Bass** | 31 Hz | `0` | `+1.5` | `+3.5` | `-3` | `-4` | `+3` |
| **Bass** | 62 Hz | `0` | `+2` | `+4.5` | `-2` | `+0.5` | `+3.5` |
| **Bass** | 125 Hz | `0` | `+0.5` | `+2` | `-1` | `+1` | `+1` |
| **Bass** | 250 Hz | `0` | `-1.5` | `-2` | `-2.5` | `-3` ($Q=1.0$) | `-2` |
| **Mids** | 500 Hz | `0` | `-0.5` | `-0.5` | `0` | `-1` | `-0.5` |
| **Mids** | 1 kHz | `0` | `0` | `0` | `+1.5` | `0` | `+1` |
| **Mids** | 2 kHz | `0` | `+1` | `+0.5` | `+2.5` | `+1.5` | `+2` |
| **Treble** | 4 kHz | `0` | `+1.5` | `+0.5` | `+2` | `+2.5` ($Q=2.0$) | `+1.5` |
| **Treble** | 8 kHz | `0` | `+1` | `0` | `+0.5` | `+1.5` | `+1` |
| **Air** | 16 kHz | `0` | `+1` | `0` | `0` | `+0.5` | `+1` |
| **Root HPF** | Butterworth | `Off` | `22 Hz` | `25 Hz` | `65 Hz` | `40 Hz` | `22 Hz` |
| **Harmonic** | `40–100 Hz` | `Off` | `-22 dB` | `-16 dB` | `Off` | `-24 dB` | `-18 dB` |

---

## 3. Deep Optimization v4 DSP Pipeline

- **31-Band PCHIP Renderer (`DynamicsProcessing`)**: Uses `VARIANT_FAVOR_FREQUENCY_RESOLUTION` (with automatic fallback to 20 or 10 bands if rejected by HAL, plus a 10-band Compatibility toggle).
- **6-Band Multiband Ceilings (MBC)**: Cutoff frequencies aligned to third-octave upper edges (`90, 359, 1437, 5747, 11494, 20000 Hz`) with boost-tracking thresholds (`baseThreshold + max(0, mean(effective))`) and `1:1` post-gain so MBC acts purely as a dynamic ceiling.
- **Fixed Headroom & Loudness-Balanced Makeup Gain**: Applies `-3.0 dB` input gain (`-4.0 dB` on Bluetooth, minus any calibration boost above `+3 dB`) and pink-noise power-mean `makeupDb` (`clamp(-inputGain - G, 0, 4)`), computed on preset/route/intensity load and frozen while dragging sliders.
- **Intensity Macro (`0.5x–1.5x`)**: Quick chips (`Light 0.6x`, `Standard 1.0x`, `Deep+ 1.3x`) and continuous slider scaling the user EQ curve (`user[k] * intensity`).
- **Output-Route Profiles**: Auto-detects `Speaker`, `Wired Headphones`, `Bluetooth`, and `USB DAC`, applying driver protection (`-9, -7, -2 dB` sub-bass cut on phone speakers) and Bluetooth SBC/AAC headroom.
- **Volume-Adaptive ISO 226 Contour**: Gently compensates low-level bass and treble when system media volume drops below `60%` (`max 1 dB` step per `500 ms`).
- **Sound Calibration (Dirac-Style Layer)**:
  - **Headphone Correction**: Searchable built-in headphone profile database with adjustable strength (`0–100%`, default `80%`).
  - **Speaker Self-Calibration Wizard**: Plays 3 log sine sweeps at `-18 dBFS`, windows the impulse response (`20 ms`), computes a smoothed inverse over `200 Hz–10 kHz`, and keeps the correction if RMS flatness improves by `>= 20%`.
- **A/B Loudness-Matched Compare**: Temporarily zeroes the EQ curve while keeping `inputGain + makeupDb` constant.

---

## 4. Audio Engine Modes (Top Horizontal Selector)

1. **Legacy Mode**: Works globally without ADB, Shizuku, or Root by attaching `DynamicsProcessing` (or 5-band `Equalizer` Basic Engine fallback) to global output Session `0` plus all broadcast media sessions.
2. **ADB / Shizuku Mode**: Uses `android.permission.DUMP` or Shizuku to discover all active audio sessions across `dumpsys media.audio_flinger` (`AudioPlaybackConfiguration` + HAL session regex) and attach per-session `EqEngine` instances.
3. **Root Mode**: Full Biquad cascade + Bass Harmonic Enhancer (`40–100 Hz` soft saturation + `100 Hz` HPF) and systemless Magisk module backup/restore.
