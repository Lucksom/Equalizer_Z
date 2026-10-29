# Audio EQ: Deep Optimization Values by Mode

Reference for the Android equalizer app. Every band in every category has values for each engine mode, plus setup steps, risks, and fallback rules. Paste sections into Google AI Studio as constants and requirements.

---

## 0. Read this first

- **No mode can be promised to work on every device.** OEM audio stacks, Android 14/15 changes, offloaded playback, and apps that bypass effects all affect results. The app must detect capabilities at runtime and fall back (Root → ADB/Shizuku → Legacy → Off).
- **Correction to an earlier statement:** attaching effects to audio session 0 (the global mix) has been deprecated since Android 9. It still works on some devices and not on others. Treat "Legacy / Global" as *best effort*: session 0 first, then per-app sessions from apps that broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`.
- **Per-band stereo width (Mid/Side) needs a custom DSP library, which only Root can install.** Legacy and ADB/Shizuku can only use the broadband Virtualizer.
- Values below are tuned starting points. Verify by ear and with a sweep or pink noise.

---

## 1. Final band layout (10 bands, no duplicates)

| Category | Range | Bands (Hz) | Default width (Root) |
|---|---|---|---|
| Bass | 20–250 Hz | 31, 62, 125, 250 | 10% (below 60 Hz forced mono) |
| Mids | 250 Hz–4 kHz | 500, 1k, 2k | 95–100% |
| Treble | 4–12 kHz | 4k, 8k | 110–115% |
| Air | 12–20 kHz | 16k | 120–130% |

UI note: the HTML mockups listed 250 Hz and 4 kHz twice (12 sliders). Use exactly these 10 sliders in the app, otherwise the response graph will be misaligned.

Gain range in the UI: **−12 to +12 dB**. Clamp to the device range where the API reports a smaller one.

---

## 2. What each mode can actually do

| Capability | Legacy / Global | ADB / Shizuku | Root |
|---|---|---|---|
| Native `Equalizer` (usually 5 bands) | Yes | Yes | Yes |
| 10-band EQ via `DynamicsProcessing` (API 28+) | Yes, on sessions it can attach to | Yes, on all discovered sessions | Yes |
| Multiband compressor and limiter | Yes (API 28+) | Yes | Yes |
| `BassBoost`, `Virtualizer`, `LoudnessEnhancer` | Yes | Yes | Yes |
| Per-band stereo width (Mid/Side) | No (Virtualizer only) | No (Virtualizer only) | **Yes** (custom effect library) |
| Linear-phase or custom biquad EQ, HPF, shelves | No | No | **Yes** |
| System-wide, independent of app | Partial | Mostly (re-scan sessions) | **Yes** (stream-level) |
| Per-app profiles | Only apps that broadcast sessions | **Yes** | **Yes** |
| Disable offload so effects apply | No | No | **Yes** |
| Audio policy / property tuning | No | No | **Yes** |
| Survives reboot automatically | App autostart | DUMP grant persists; Shizuku must be restarted | **Yes** (module) |
| Risk | None | Low | Medium to high |

UI rule: show per-section **Width** sliders only in Root mode. In other modes, hide them and show one **Spatial** slider mapped to the Virtualizer strength.

---

## 3. Master values: 10-band gains (dB) per preset

Used directly by **ADB/Shizuku** (`DynamicsProcessing` pre-EQ) and **Root** (custom biquads). **Legacy** uses the converted 5-band table in section 4.

| Category | Band | Flat | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity |
|---|---|---|---|---|---|---|
| Bass | 31 Hz | 0 | +1 | +4 | −3 | −4 |
| Bass | 62 Hz | 0 | +1.5 | +5 | −2 | 0 |
| Bass | 125 Hz | 0 | +0.5 | +3 | −1 | +1 |
| Bass | 250 Hz | 0 | −1 | −1 | −2 | −3 |
| Mids | 500 Hz | 0 | 0 | 0 | 0 | −1 |
| Mids | 1 kHz | 0 | 0 | 0 | +1.5 | 0 |
| Mids | 2 kHz | 0 | +0.5 | 0 | +2.5 | +1 |
| Treble | 4 kHz | 0 | +1 | 0 | +2 | +2 |
| Treble | 8 kHz | 0 | +1 | 0 | +1 | +1.5 |
| Air | 16 kHz | 0 | +0.5 | 0 | 0 | +0.5 |
| | **Preamp (headroom)** | 0 | −1.5 | −5 | −2.5 | −2 |
| | **High-pass (Root only)** | off | 20 Hz | 25 Hz | 60 Hz | 40 Hz |

Preamp equals the largest boost, applied as negative input gain, to prevent clipping.

Percussion Clarity follows the tabla notes: cut 250 Hz for boxiness, lift 4 kHz for stroke definition, add 8 kHz sizzle, keep 62–125 Hz for the bayan's low tone, high-pass at 40 Hz.

---

## 4. Legacy / Global values (no special permission)

### 4.1 Native `Equalizer` (5 bands)

Most devices expose 5 bands centered near **60, 230, 910, 3600, 14000 Hz**. Always read the real values with `getNumberOfBands()`, `getCenterFreq()`, and `getBandLevelRange()` and map to the nearest band. The table converts the 10-band master curve by log-frequency interpolation.

| Native band | Covers (your bands) | Flat | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity |
|---|---|---|---|---|---|---|
| ~60 Hz | 31, 62 | 0 | +1.5 | +4* | −2 | −0.5 |
| ~230 Hz | 125, 250 | 0 | −0.5 | 0 | −2 | −2 |
| ~910 Hz | 500, 1k | 0 | 0 | 0 | +1.5 | 0 |
| ~3.6 kHz | 2k, 4k | 0 | +1 | 0 | +2 | +2 |
| ~14 kHz | 8k, 16k | 0 | +0.5 | 0 | 0 | +0.5 |

\*Capped at +4 dB because the native equalizer has no preamp, so large boosts clip.

Level units are **millibels**: multiply dB by 100 (for example +1.5 dB = `150`).

### 4.2 Companion effects

| Effect | Flat | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity |
|---|---|---|---|---|---|
| `BassBoost` strength (0–1000) | 0 | 150 | 400 | 0 | 100 |
| `Virtualizer` strength (0–1000, headphones) | 0 | 150 | 0 | 100 | 200 |
| `LoudnessEnhancer` target gain (mB) | 0 | 0 | 0 | 0 | 0 |

Check `BassBoost.getStrengthSupported()` and `Virtualizer.canVirtualize()` first, and skip unsupported effects. Keep `LoudnessEnhancer` at 0 by default; allow at most +300 mB as a user option.

### 4.3 Legacy tier 2: `DynamicsProcessing` (API 28+)

Use the same 10-band values and compressor table as ADB/Shizuku (sections 5.1 and 5.2) when the effect attaches successfully. It gives a real 10-band EQ, multiband compression, and a limiter without special permissions, but only on sessions the app can reach.

### 4.4 Session strategy and limits

1. Try attaching to session `0`. If construction throws or the effect reports failure, mark global attach as unsupported.
2. Register a receiver for `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` and attach to each broadcast session.
3. Permission: `MODIFY_AUDIO_SETTINGS` (normal permission, automatic).
4. Known gaps: apps that do not broadcast sessions, offloaded playback, MMAP/AAudio exclusive streams, USB DAC bit-perfect output, and some OEM players.

---

## 5. ADB / Shizuku values (no root)

Same engine as Legacy tier 2, but the app can **discover every active audio session** and attach to each.

### 5.1 Pre-EQ bands (`DynamicsProcessing`)

Band edges are the geometric midpoints between centers. Gains come from the master table in section 3.

| Category | Band | Cutoff (Hz, upper edge) |
|---|---|---|
| Bass | 31 | 44 |
| Bass | 62 | 88 |
| Bass | 125 | 177 |
| Bass | 250 | 354 |
| Mids | 500 | 707 |
| Mids | 1k | 1414 |
| Mids | 2k | 2828 |
| Treble | 4k | 5657 |
| Treble | 8k | 11314 |
| Air | 16k | 20000 |

Apply the preset preamp with `setInputGainAllChannelsTo(preampDb)`.

`DynamicsProcessing` EQ bands behave like gain-per-band, not peaking filters. Adjacent bands blend smoothly but there is no Q control. Emulate a high-pass by setting the 31 Hz band negative.

### 5.2 Multiband compressor (4 bands matching the categories)

Cutoffs: **250 Hz, 4 kHz, 12 kHz, 20 kHz**.

| Band | Attack (ms) | Release (ms) | Ratio | Threshold (dB) | Knee (dB) | Noise gate (dB) | Expander ratio | Pre-gain | Post-gain |
|---|---|---|---|---|---|---|---|---|---|
| Bass (to 250) | 30 | 200 | 2.0 | −18 | 6 | −90 | 1.0 | 0 | 0 |
| Mids (to 4k) | 15 | 150 | 1.6 | −20 | 6 | −90 | 1.0 | 0 | 0 |
| Treble (to 12k) | 5 | 80 | 1.8 | −22 | 6 | −90 | 1.0 | 0 | 0 |
| Air (to 20k) | 3 | 60 | 1.5 | −24 | 6 | −90 | 1.0 | 0 | 0 |

**Percussion Clarity overrides** (slow attack lets the initial strike through):

| Band | Attack (ms) | Release (ms) | Ratio |
|---|---|---|---|
| Bass | 40 | 180 | 1.8 |
| Mids | 25 | 120 | 1.5 |
| Treble | 10 | 70 | 1.6 |
| Air | 5 | 50 | 1.4 |

### 5.3 Limiter

| Attack (ms) | Release (ms) | Ratio | Threshold (dB) | Post-gain (dB) |
|---|---|---|---|---|
| 1 | 60 | 10 | −1.0 | 0 |

### 5.4 Engine settings

- Variant: `VARIANT_FAVOR_FREQUENCY_RESOLUTION` for Balanced, Bass Boost, Vocal; **`VARIANT_FAVOR_TIME_RESOLUTION`** for Percussion Clarity (better transient handling for tabla).
- Channels: 2. Frame duration: 10 ms.
- Stereo width: not available. Use Virtualizer values from section 4.2 as the **Spatial** control.

### 5.5 Setup

**ADB (PC required once):**

```
adb shell pm grant <your.package.name> android.permission.DUMP
```

`DUMP` lets the app read `dumpsys media.audio_flinger` and `dumpsys audio` to list active sessions and map them to packages. Output format varies by Android version, so parse defensively. The grant survives reboots.

Optional, only if the app needs to change secure settings:

```
adb shell pm grant <your.package.name> android.permission.WRITE_SECURE_SETTINGS
```

**Shizuku (no PC after setup):**

1. Install Shizuku and start it with Wireless debugging (Android 11+) or ADB.
2. The app requests Shizuku permission and runs session discovery in a Shizuku user service (shell identity).
3. Shizuku started via ADB must be restarted after every reboot. Show this clearly in the capability panel.
4. A shell-identity service may reach more than a normal app, such as session 0. Behavior differs by ROM, so detect and fall back.

**Session loop:** scan every 2–3 seconds and on audio focus changes, attach effects to new sessions, release effects for closed ones.

**User-guided tweaks (deep links, not commands):** open Developer options for "Disable absolute volume" (Bluetooth) and the Bluetooth audio codec choice (prefer LDAC or aptX HD when supported). Do not disable safe-volume protection.

---

## 6. Root values

Everything in section 5, plus a custom system effect with biquad EQ, Mid/Side width, high-pass, and crossovers. This is what makes the width sliders work.

### 6.1 Biquad filters (per band)

| Category | Band | Type | Q | Gain (Balanced Deep) |
|---|---|---|---|---|
| Bass | 31 Hz | Low-shelf | 0.9 | +1 |
| Bass | 62 Hz | Peaking | 1.1 | +1.5 |
| Bass | 125 Hz | Peaking | 1.2 | +0.5 |
| Bass | 250 Hz | Peaking | 1.4 | −1 |
| Mids | 500 Hz | Peaking | 1.4 | 0 |
| Mids | 1 kHz | Peaking | 1.4 | 0 |
| Mids | 2 kHz | Peaking | 1.4 | +0.5 |
| Treble | 4 kHz | Peaking | 1.4 | +1 |
| Treble | 8 kHz | Peaking | 1.2 | +1 |
| Air | 16 kHz | High-shelf | 0.7 | +0.5 |

Use gains from the master table for other presets. **Percussion Clarity Q overrides:** 250 Hz Q 1.0 (broad cut), 4 kHz Q 2.0 (narrow, defined strokes).

High-pass: 2nd-order Butterworth (Q 0.707) at the frequency in the master table.

### 6.2 Crossovers and stereo width

Split the signal with **Linkwitz-Riley 4th-order** crossovers at **250 Hz, 4 kHz, 12 kHz**, convert each band to Mid/Side, scale the Side signal by the width, and sum.

Always fold everything **below 60 Hz to mono** (0% width) in every preset except Flat.

| Category | Flat | Balanced Deep | Bass Boost | Vocal Clarity | Percussion Clarity |
|---|---|---|---|---|---|
| Bass (below 250) | 100% | 10% | 0% | 0% | 10% |
| Mids (250–4k) | 100% | 95% | 85% | 85% | 100% |
| Treble (4k–12k) | 100% | 115% | 105% | 110% | 115% |
| Air (12k+) | 100% | 125% | 110% | 120% | 125% |

Keep lead vocals centered: do not exceed 100% in the mids for Vocal Clarity.

### 6.3 System integration (Magisk module)

Never edit `/system` or `/vendor` directly. Use a systemless Magisk module so removal restores the stock state.

```
/data/adb/modules/eq_deep/
  module.prop
  system.prop
  post-fs-data.sh
  sepolicy.rule
  system/vendor/lib64/soundfx/libdeepeq.so
  system/vendor/etc/audio_effects.xml     (merged copy of the stock file)
```

Effect registration in `audio_effects.xml` (generate your own unique UUID):

```xml
<libraries>
  <library name="deepeq" path="libdeepeq.so"/>
</libraries>
<effects>
  <effect name="deepeq" library="deepeq" uuid="GENERATE-A-UNIQUE-UUID"/>
</effects>
<postprocess>
  <stream type="music">
    <apply effect="deepeq"/>
  </stream>
</postprocess>
```

Attaching to the `music` stream applies the effect to all music playback regardless of app or session.

Before editing, locate the real file: `adb shell ls /vendor/etc | grep audio_effects`. The path differs by device (`/vendor/etc/`, `/vendor/etc/audio/`, `/odm/etc/`). Android 14/15 devices may use the AIDL effect HAL, which changes how effect libraries are loaded. Detect the HAL version first and disable Root-DSP mode if the setup does not match.

### 6.4 System tuning properties (verify with `getprop` first)

`system.prop` inside the module:

```
audio.offload.disable=1
```

- `audio.offload.disable=1` stops offloaded playback, which otherwise bypasses effects. Costs some battery.
- Resampler quality (`af.resampler.quality`) exists on some builds. Only set it if the property is present and test for glitches.
- Sample rate and format changes in `audio_policy_configuration.xml` are vendor-specific. Offer them as an advanced, backed-up option only.

### 6.5 Safety and recovery

- Back up the original `audio_effects.xml` and `audio_policy_configuration.xml` to storage before any change.
- Apply changes only via the Magisk module. To recover: boot to Magisk safe mode, or create `/data/adb/modules/eq_deep/disable`, or remove the module.
- Warn that Root mode can break Play Integrity, banking apps, and DRM-dependent apps.
- Include a one-tap **Restore stock audio config** button and a first-run warning dialog.

---

## 7. Ready-to-use preset JSON

```json
{
  "bandsHz": [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000],
  "presets": {
    "flat": {
      "gainsDb": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
      "preampDb": 0, "highPassHz": 0,
      "widthPct": { "bass": 100, "mids": 100, "treble": 100, "air": 100 },
      "legacy": { "nativeDb": [0, 0, 0, 0, 0], "bassBoost": 0, "virtualizer": 0 }
    },
    "balanced_deep": {
      "gainsDb": [1, 1.5, 0.5, -1, 0, 0, 0.5, 1, 1, 0.5],
      "preampDb": -1.5, "highPassHz": 20,
      "widthPct": { "bass": 10, "mids": 95, "treble": 115, "air": 125 },
      "legacy": { "nativeDb": [1.5, -0.5, 0, 1, 0.5], "bassBoost": 150, "virtualizer": 150 }
    },
    "bass_boost": {
      "gainsDb": [4, 5, 3, -1, 0, 0, 0, 0, 0, 0],
      "preampDb": -5, "highPassHz": 25,
      "widthPct": { "bass": 0, "mids": 85, "treble": 105, "air": 110 },
      "legacy": { "nativeDb": [4, 0, 0, 0, 0], "bassBoost": 400, "virtualizer": 0 }
    },
    "vocal_clarity": {
      "gainsDb": [-3, -2, -1, -2, 0, 1.5, 2.5, 2, 1, 0],
      "preampDb": -2.5, "highPassHz": 60,
      "widthPct": { "bass": 0, "mids": 85, "treble": 110, "air": 120 },
      "legacy": { "nativeDb": [-2, -2, 1.5, 2, 0], "bassBoost": 0, "virtualizer": 100 }
    },
    "percussion_clarity": {
      "gainsDb": [-4, 0, 1, -3, -1, 0, 1, 2, 1.5, 0.5],
      "preampDb": -2, "highPassHz": 40,
      "qOverrides": { "250": 1.0, "4000": 2.0 },
      "dpVariant": "time_resolution",
      "widthPct": { "bass": 10, "mids": 100, "treble": 115, "air": 125 },
      "legacy": { "nativeDb": [-0.5, -2, 0, 2, 0.5], "bassBoost": 100, "virtualizer": 200 }
    }
  }
}
```

---

## 8. Reference snippet: `DynamicsProcessing` setup (Kotlin)

```kotlin
val cfg = DynamicsProcessing.Config.Builder(
    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
    2,          // channels
    true, 10,   // pre-EQ in use, 10 bands
    true, 4,    // MBC in use, 4 bands
    false, 0,   // post-EQ not used
    true        // limiter in use
).build()

val dp = DynamicsProcessing(0, sessionId, cfg)
dp.setInputGainAllChannelsTo(preampDb)

val cutoffs = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
for (i in cutoffs.indices) {
    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], gainsDb[i]))
}
dp.enabled = true
```

Wrap construction in try/catch. A failure means "unsupported on this session", and the app should fall back to the native `Equalizer`.

---

## 9. Mode detection and fallback logic

1. **Root available?** Check for `su`, a working Magisk module setup, and a compatible effect HAL → enable Root mode.
2. **Shizuku running with permission, or `DUMP` granted?** → enable ADB/Shizuku mode.
3. **`DynamicsProcessing` constructs on a test session?** → Legacy tier 2 (10-band).
4. **Native `Equalizer` constructs?** → Legacy tier 1 (5-band).
5. Otherwise show **Unsupported** and disable the equalizer.

Each mode card shows one of: **Available**, **Requires permission** (with setup steps), or **Unsupported** (with the reason).

---

## 10. Test checklist

1. Log `getNumberOfBands()`, band centers, and level range on the target device.
2. Play a sine sweep or pink noise and confirm each band moves the right region.
3. Confirm no clipping at the highest-boost preset (Bass Boost) at full volume. Limiter should engage, not distort.
4. Test with several players (local files, YouTube, Spotify, a browser) to see which sessions attach.
5. Test Bluetooth, wired, and speaker output separately. Width and Virtualizer are best judged on headphones.
6. For Root: confirm the module can be disabled from safe mode before shipping.
7. Test hearing safety: never auto-raise volume and cap total boost.
