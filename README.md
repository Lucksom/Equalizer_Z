# Audio EQ — Deep Optimization v5 (Material 3 System-Wide Equalizer)

A system-wide Android audio equalizer built with **Kotlin** and **Jetpack Compose (Material Design 3 / Monet Dynamic Color)** implementing the **Audio EQ: Deep Optimization v5** specification.

---

## 1. APK-Only Architecture & Engine Modes (Top Horizontal Selector)

Everything runs from the APK, Shizuku, or an APK-started `su` helper — **no Magisk module flashing and no `/system` or `/vendor` file modifications**.

1. **Legacy Mode**: Works globally without ADB, Shizuku, or Root by attaching `DynamicsProcessing` (Tier 2: 31-band Pre-EQ + 6-band MBC + Limiter) or 5-band `Equalizer` (Tier 1 Basic Engine fallback with band-averaged AutoEq mapping capped at `+4 dB`) to global output Session `0` plus all broadcast media sessions.
2. **ADB / Shizuku Mode**: Uses `android.permission.DUMP` or Shizuku to discover all active audio sessions across `dumpsys media.audio_flinger` (`AudioPlaybackConfiguration` + HAL session regex), attach per-session `EqEngine` instances, and inspect active Bluetooth codecs (`dumpsys bluetooth_manager`).
3. **Root Mode (APK-Only Helper)**: Uses an APK-launched `su -c` shell helper for universal session discovery across all UIDs, high-resolution biquad cascade + Bass Harmonic Enhancer (`40–100 Hz` soft saturation + `100 Hz` HPF), Bluetooth codec readout, and one-tap Bluetooth Absolute Volume toggle (`settings put global bluetooth_disable_absolute_volume`).

---

## 2. Frequency Sections & 10-Band Master Layout

The UI exposes a clean 10-band octave layout (`-12 dB` to `+12 dB`) with super-fluid zero-slop vertical drag sliders, mapped to four acoustic frequency categories and internally interpolated via **monotone cubic PCHIP** onto a **31-band third-octave renderer** (`20 * 2^(i/3)` Hz):

| Section | True Acoustic Range | UI Bands (Hz) | Root Biquad Filter Types & Default $Q$ |
|---|---|---|---|
| **Bass** | `20–350 Hz` | `31`, `62`, `125`, `250` | `31 Hz` Low-Shelf ($Q=0.9$), `62–250 Hz` Peaking ($Q=1.4, 1.6, 1.6$) |
| **Mids** | `350 Hz–2.8 kHz` | `500`, `1k`, `2k` | Peaking EQ ($Q=1.6, 1.4, 1.4$) |
| **Treble** | `2.8–11 kHz` | `4k`, `8k` | Peaking EQ ($Q=1.6, 1.4$) |
| **Air** | `11–20 kHz` | `16k` | High-Shelf ($Q=0.7$) |

---

## 3. Built-in v5 Master Presets

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

## 4. Deep Optimization v5 Feature Suite (F1–F12)

- **F1 — AutoEq Headphone & IEM Correction (Section 16)**:
  - Fetches `INDEX.md` and `{model} ParametricEQ.txt` → `FixedBandEQ.txt` → `GraphicEQ.txt` on-demand from `raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/` with HTTPS host allow-list, ETag conditional caching (`If-None-Match`, 24h TTL, 15m manual refresh gap), HTTP `403`/`429` exponential backoff, and offline starter catalog fallback.
  - Evaluates `PK` / `LS` / `HS` biquad magnitude at **44.1 kHz** (`biquadDb`), tapers logarithmically between `10 kHz` and `14 kHz` (`1.0 - log2(f / 10000) / log2(1.4)`), clamps to `[-12, +6]` (`+3` above `6 kHz`, or `+10` when **Full range** is enabled), and scales by `strength` (`default 80%`).
  - Displays `"Trimmed by X dB to protect headroom"` when `trimmedDb > 1.0 dB` and auto-suggests matching profiles when a connected headset's `matchScore >= 0.6`.
- **F2 — Per-Device Profile Memory & Auto-Switch**: Remembers headphone profile, strength, full-range toggle, preset, intensity, and tilt per audio device (`route + productName`) and restores within `<300 ms` on route change.
- **F3 — Bass & Treble Tilt Knobs**: Smooth low-shelf (`105 Hz`, $Q=0.7$, `-6..+6 dB`) and high-shelf (`6 kHz`, $Q=0.7$, `-4..+4 dB`) evaluated across the 31-band renderer before limiting.
- **F4 — Per-Channel L/R EQ & Balance**: Optional independent Left and Right 10-band EQ (`setPreEqBandByChannelTo`) and L/R channel balance (`setInputGainbyChannel`, `-6..+6 dB`).
- **F5 — Personal Hearing Curve**: Built-in per-ear hearing threshold tone test (`250, 500, 1k, 2k, 4k, 8k Hz`, capped at `±6 dB`, `50%` strength, with automatic EQ bypass while test tones play).
- **F6 — Blind ABX & Loudness-Matched A/B**: Instant A/B bypass compare plus randomized hidden `X` blind test with trial score counter (`inputGain + makeupDb` held constant).
- **F7 — USB DAC Bit-Perfect Switch**: Android 14+ `AudioManager.setPreferredMixerAttributes` (`MIXER_BEHAVIOR_BIT_PERFECT`) with clear `"Bit-perfect ON, EQ off"` status.
- **F8 — Night Mode**: Lowers the 6 MBC ceiling thresholds by `-6 dB` and raises compression ratios by `+1.5` for quiet late-night listening.
- **F9 — ParametricEQ & GraphicEQ Import / Export**: Paste any `ParametricEQ.txt` or `GraphicEQ:` profile directly or export the active curve to clipboard.
- **F10 — Bluetooth Codec Readout**: Displays active codec, sample rate, and bitrate via `dumpsys bluetooth_manager` in ADB/Shizuku and Root modes.
- **F12 — Start on Boot**: Optional `RECEIVE_BOOT_COMPLETED` receiver to restore the equalizer service automatically after device reboot.
