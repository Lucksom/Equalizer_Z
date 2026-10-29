# Audio EQ — System-Wide Material 3 Equalizer & Mid/Side DSP

A system-wide Android audio equalizer built with **Kotlin** and **Jetpack Compose (Material Design 3 / Monet Dynamic Color)**. Features four frequency sections (**Bass**, **Mids**, **Treble**, **Air**), independent **Mid/Side Stereo Width** processing, a live **Frequency Response Graph**, a real-time **DSP Reference Monitor**, and a **Percussion Clarity** preset tuned specifically for tabla and acoustic percussion.

---

## 1. Frequency Sections & 10-Band Master Layout

To ensure accurate logarithmic spacing and avoid duplicate crossover sliders, the equalizer uses a 10-band master layout (-12 dB to +12 dB) grouped into four tonal sections:

| Section | Range | Bands (Hz) | Filter Types (Root / DSP) | Default Width |
|---|---|---|---|---|
| **Bass** | 20–250 Hz | `31`, `62`, `125`, `250` | 31 Hz Low-Shelf ($Q=0.9$), 62–250 Hz Peaking ($Q=1.1\text{–}1.4$) | `0–20%` (`10%`, sub-60 Hz folded to mono) |
| **Mids** | 250 Hz–4 kHz | `500`, `1k`, `2k` | Peaking EQ ($Q=1.4$) | `80–100%` (`95–100%`) |
| **Treble** | 4–12 kHz | `4k`, `8k` | Peaking EQ ($Q=1.4, 1.2$) | `110–120%` (`115%`) |
| **Air** | 12–20 kHz | `16k` | High-Shelf ($Q=0.7$) | `110–150%` (`125–130%`) |

### Mid/Side Stereo Width Processing
Each frequency section splits stereo audio using **4th-order Linkwitz-Riley crossovers** at `250 Hz`, `4 kHz`, and `12 kHz`:
$$\text{Mid} = \frac{L + R}{2}, \quad \text{Side} = \frac{L - R}{2} \times \left(\frac{\text{Width\%}}{100}\right)$$
$$L_{\text{out}} = \text{Mid} + \text{Side}, \quad R_{\text{out}} = \text{Mid} - \text{Side}$$
Everything below `60 Hz` is folded to mono (`0%` width) in all presets except `Flat` to keep low-end phase coherent.

---

## 2. Built-in Presets & Master Values

| Category | Band | Flat | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity |
|---|---|---|---|---|---|---|
| **Bass** | 31 Hz | `0` | `+1` | `+4` | `-3` | `-4` |
| **Bass** | 62 Hz | `0` | `+1.5` | `+5` | `-2` | `0` |
| **Bass** | 125 Hz | `0` | `+0.5` | `+3` | `-1` | `+1` |
| **Bass** | 250 Hz | `0` | `-1` | `-1` | `-2` | `-3` ($Q=1.0$) |
| **Mids** | 500 Hz | `0` | `0` | `0` | `0` | `-1` |
| **Mids** | 1 kHz | `0` | `0` | `0` | `+1.5` | `0` |
| **Mids** | 2 kHz | `0` | `+0.5` | `0` | `+2.5` | `+1` |
| **Treble** | 4 kHz | `0` | `+1` | `0` | `+2` | `+2` ($Q=2.0$) |
| **Treble** | 8 kHz | `0` | `+1` | `0` | `+1` | `+1.5` |
| **Air** | 16 kHz | `0` | `+0.5` | `0` | `0` | `+0.5` |
| **Preamp** | Headroom | `0 dB` | `-1.5 dB` | `-5.0 dB` | `-2.5 dB` | `-2.0 dB` |
| **High-Pass** | Butterworth | `Off` | `20 Hz` | `25 Hz` | `60 Hz` | `40 Hz` |

- **Percussion Clarity**: Specifically tuned for tabla and hand percussion. Applies a 2nd-order Butterworth High-Pass filter at `40 Hz`, cuts boxiness at `250 Hz` (`-3 dB`, $Q=1.0$), boosts `4 kHz` (`+2 dB`, $Q=2.0$) for crisp *dayan* stroke definition, adds `+1.5 dB` at `8 kHz` for skin sizzle, preserves `62–125 Hz` for *bayan* resonance, and uses `VARIANT_FAVOR_TIME_RESOLUTION` in `DynamicsProcessing` for transient preservation.

---

## 3. Audio Engine Modes: Requirements, Capabilities & Risks

The app automatically detects device capabilities at runtime and falls back gracefully (`Root → ADB/Shizuku → Global → Legacy`).

### Mode 1: Legacy Mode
- **How it works**: Uses standard Android `android.media.audiofx.Equalizer` (5 bands interpolated in log-frequency space, capped at `+4 dB` to prevent clipping), `BassBoost`, and `Virtualizer`.
- **Requirements**: `android.permission.MODIFY_AUDIO_SETTINGS` (granted automatically at install time).
- **Limitations**: Only affects apps that broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. Stereo width uses the broadband hardware `Virtualizer`.
- **Risk**: None.

### Mode 2: Global Mode
- **How it works**: Attaches `DynamicsProcessing` (API 28+ 10-band Pre-EQ, 4-band Multiband Compressor, and -1.0 dB Limiter) and `Equalizer` to **Audio Session `0`** (system global output mix) as well as all broadcast media sessions.
- **Requirements**: Standard install permissions.
- **Limitations**: Attaching effects to Session `0` is deprecated on Android 9+ and blocked by some OEM audio HALs or offloaded streams. If Session `0` is rejected by the ROM, the engine automatically falls back to broadcast sessions.
- **Risk**: None.

### Mode 3: ADB / Shizuku Mode
- **How it works**: Uses `android.permission.DUMP` or Shizuku shell privileges to inspect `dumpsys media.audio_flinger` every 3 seconds, discovering active audio session IDs from all apps (even players that do not broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`) and attaching 10-band `DynamicsProcessing` + Multiband Compression.
- **Setup Steps**:
  1. Connect your Android device to a PC with USB Debugging enabled (or use Shizuku with Wireless Debugging on Android 11+).
  2. Run the one-time ADB command:
     ```bash
     adb shell pm grant com.aistudio.audioeq.kxmptq android.permission.DUMP
     ```
  3. *(Optional)* Grant secure settings if needed:
     ```bash
     adb shell pm grant com.aistudio.audioeq.kxmptq android.permission.WRITE_SECURE_SETTINGS
     ```
- **Limitations**: Hardware-offloaded streams, MMAP/AAudio exclusive streams, or bit-perfect USB DAC modes may bypass OS session effects. Shizuku started via ADB must be restarted after device reboot (`DUMP` permission grant persists across reboots).
- **Risk**: Low.

### Mode 4: Root Mode
- **How it works**: Enables full Biquad cascade filtering (Low-shelf, Peaking with custom $Q$, High-shelf, 2nd-order Butterworth High-Pass Filter) and 4-section Linkwitz-Riley Mid/Side stereo width processing across the `music` stream via a systemless Magisk module (`/data/adb/modules/eq_deep`) and sets `audio.offload.disable=1` so hardware offload does not bypass DSP.
- **Setup & Safety**:
  - **Never edit `/system` or `/vendor` directly.** Always use the built-in **Backup & Stage** button to back up stock `audio_effects.xml` before staging the systemless module.
  - **Recovery**: Tap **Restore Stock** inside the app, boot into Magisk Safe Mode, or create `/data/adb/modules/eq_deep/disable` and reboot.
- **Risks**: Medium to High. Root access and system audio modifications can break **Google Play Integrity**, banking apps, and DRM-restricted streaming apps, and disabling offload slightly increases battery usage.

---

## 4. GitHub Actions APK Release Workflow

This repository includes `.github/workflows/build.yml` which compiles a signed Release APK (`./gradlew assembleRelease`) and publishes the `.apk` file directly to **GitHub Releases** (via `softprops/action-gh-release@v2`) rather than uploading it only as a temporary workflow action artifact.
