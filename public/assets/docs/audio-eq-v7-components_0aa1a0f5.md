# Audio EQ v7: Component Map

This document lists where each component lives in `audio-eq-v7.html`, the element IDs it uses, the state it reads or writes, and whether it is wired to behavior or is a placeholder.

---

## 1. Page layout

```
body
└── .page  (2-column grid on desktop, 1 column on phones)
    ├── header.top                    Top bar (full width)
    ├── #banner                       Status banner (full width, hidden by default)
    ├── aside.col                     LEFT column
    │   └── #presetCard               Presets
    └── div.col                       RIGHT column
        ├── main#eq                   Equalizer
        ├── section#toneCard          Tone (directly below Equalizer)
        └── section#enhanceCard       Audio Enhancers & Profiles
```

Outside `.page`, fixed-position layers:

```
#fab                  Save preset floating button
#scrim + #sheet       Settings sheet (DSP Engine Settings & Diagnostics)
#saveWrap             Save preset dialog
#snack                Toast message
```

---

## 2. Top bar (`header.top`)

| Component | Element | Behavior |
|---|---|---|
| App logo | `.brand` | Decorative |
| Title and subtitle | `h1`, `#sub` | Shows preset and engine name, or "All features are off" when master is off |
| Engine pill | `.engine-pill`, `#enginePillText` | Shows current engine: Legacy, ADB / Shizuku, or Root |
| Master power | `#master` | Toggles `st.master`. Dims Tone and Enhancers cards. Does not affect the Equalizer |
| Settings | `#settingsBtn` | Opens the settings sheet |

---

## 3. Banner (`#banner`)

| Component | Element | Behavior |
|---|---|---|
| Message | `#bannerText` | Shows `st.banner`, e.g. "Switched to Root" |
| Dismiss | `#bannerDismiss` | Sets `st.banner = null` |

---

## 4. Left column: Presets (`#presetCard`)

| Component | Element | Behavior |
|---|---|---|
| Section title | `.kicker`, `#presetTag` | Shows the active preset name |
| Preset chips | `#presets` (rendered by `renderPresets`) | Loads a built-in or custom preset into `st.gains`. Custom presets have a ✕ to delete |

Built-in presets: Flat, Balanced Deep, Bass Boost, Vocal Clarity, Percussion, Cinema.

---

## 5. Equalizer (`main#eq`)

| Component | Element | Behavior |
|---|---|---|
| Equalizer power | `#power` | Toggles `st.eq`. Dims the curve and bands when off |
| Title and status | `h2`, `#eqSub` | Shows intensity, or "Equalizer is off" |
| Route badge | `.route`, `#routeIcon`, `#routeText` | Shows Speaker, Wired Headphones, or Bluetooth based on the engine |
| Headroom meter | `#ceilBar`, `#ceilText` | Computes `max(effective gains) − 3` dB. Turns amber above 4 dB |
| Response curve | `#curve`, `#grid`, `#area`, `#line` | SVG. Smooth curve through the 10 effective band gains on a log frequency axis |
| Frequency zones | `.zones` | Labels only: Bass, Mids, Treble, Air |
| Band sliders | `#bands` containing 10 `.band` elements | Vertical drag controls for 31 Hz to 16 kHz, range −12 to +12 dB. Double-click resets a band to 0 |

Each `.band` contains:
- `.db`: effective gain readout
- `.rail`: pointer target
- `.fill`: colored bar from 0 dB to the knob
- `.knob`: draggable handle
- `.hz`: frequency label

---

## 6. Tone card (`section#toneCard`)

| Component | Element | State key | Behavior |
|---|---|---|---|
| Bass tilt | `#tb`, `#tbv` | `st.tb` (−6 to +6) | Adds up to ±4.8 dB to low bands and fades toward mids. Applies only when master and EQ are on |
| Treble tilt | `#tt`, `#ttv` | `st.tt` (−6 to +6) | Adds up to ±4.8 dB to high bands and fades toward mids. Applies only when master and EQ are on |
| Intensity | `[data-group=intensity]` | `st.intensity` (0.6, 1, 1.3) | Scales every band gain |
| Volume contour | `[data-toggle=contour]` | `st.contour` | Adds +1.5 dB to the outermost bands. Applies only when master and EQ are on |
| A/B compare | `[data-toggle=ab]` | `st.ab` | Toggle only. No loudness-matched comparison is implemented |

---

## 7. Audio Enhancers & Profiles (`section#enhanceCard`)

| Block | Element | State key | Behavior |
|---|---|---|---|
| Card header | `#enhanceSub`, `#enhanceReset` | | Reset restores all enhancers and tone to defaults |
| Profile Profiles | `[data-group=profile]`, `#profileBadge` | `st.profile` (off, dynamic, balanced) | Stored and displayed. Not applied to the curve |
| Intelligent EQ | `[data-group=ieq]` (two rows), `#ieqBadge`, `#ieqStrength`, `#ieqOut` | `st.ieq` (detailed, balanced, warm, custom, off), `st.ieqStrength` (0 to 16) | Stored and displayed. Not applied to the curve |
| Dialogue Enhancer | `[data-toggle=dialogueOn]`, `#dialAmt`, `#dialOut`, `#dialSub`, `[data-group=duck]` | `st.dialogueOn`, `st.dialogueAmt` (0 to 16), `st.duck` | Adds `amount × 0.4` dB to the 2 kHz band when on. Ducking is stored only |
| Bass Enhancer | `[data-toggle=bassOn]`, `#bassSub` | `st.bassOn` | Adds +7.44 dB to the 31 Hz band and +3.72 dB to the 62 Hz band when on |
| Virtual bass | `[data-toggle=virtualOn]` | `st.virtualOn` | Stored only. No effect on the curve |
| Enhancer Control | `#enhancerBlock` (hidden), `[data-toggle=enhancerDeep]` | `st.enhancerDeep` | Shows only when "Try Enhancer control" is on and the engine is not Legacy. Stored only |
| Calibration | `#cal` | | Placeholder: shows a toast |
| ABX test | `#abx` | | Placeholder: shows a toast |

---

## 8. Save preset (`#fab` and `#saveWrap`)

| Component | Element | Behavior |
|---|---|---|
| Floating button | `#fab` | Opens the dialog |
| Name field | `#presetName` | Required, max 24 characters |
| Cancel and Save | `#dlgCancel`, `#dlgSave` | Save writes the current gains into `st.custom[name]` |

---

## 9. Settings sheet (`#sheet`)

Opened by `#settingsBtn`. Closed by `#closeSettings`, a tap on `#scrim`, or Escape. Slides from the right on desktop and up from the bottom on phones.

| Section | Element | State key | Status |
|---|---|---|---|
| Audio engine | `.engine[data-mode]` (legacy, adb, root), `#engineText` | `st.engine` | Changes the top-bar pill, route badge, and diagnostics. Does not switch the real engine |
| Channel balance | `[data-toggle=channelOn]`, `#balance`, `#balOut` | `st.channelOn`, `st.balance` (−6 to +6) | Balance applies a rough approximation when on. Dual-channel EQ is not implemented |
| Start on boot | `[data-toggle=startBoot]` | `st.startBoot` | Stored only |
| Compatibility mode | `[data-toggle=compat]` | `st.compat` | Stored only |
| Transient mode | `[data-toggle=transient]` | `st.transient` | Stored only |
| Try Enhancer control | `[data-toggle=enhancerExp]` | `st.enhancerExp` | Controls whether the Enhancer block shows in Enhancers |
| Sub-bass high-pass | `#hpf`, `#hpfOut` | `st.hpf` (0 to 200 Hz, 0 = Off) | Stored and displayed. Not applied to the curve |
| Diagnostics | `#diag` (rendered by `renderDiagnostics`) | derived from `st.engine` | Read-only text. Most values are fixed sample strings |
| Keep alive | `#keepAlive` | | Placeholder: shows a toast |
| Appearance | `#themeSeg` (system, light, dark) | `themeMode` | Switches light and dark |
| Dynamic color | `#swatches` (8 seeds plus custom `#picker`), `#tones`, `#cap`, `#roles` | `seedHex` | Changes the Monet palette |
| Wallpaper color | `#wallpaper`, `#file` | | Picks the most saturated mid-tone pixel from an image as the seed |

---

## 10. State object (`st`)

```
st = {
  eq, master, engine,                       // power and engine
  gains[10], preset, custom,                // EQ bands and presets
  banner,
  intensity, tb, tt, contour, ab,           // Tone
  profile, ieq, ieqStrength,                   // Enhancers: profiles and IEQ
  dialogueOn, dialogueAmt, duck,            // Enhancers: dialogue
  bassOn, virtualOn, enhancerDeep,             // Enhancers: bass and Enhancer
  channelOn, balance, startBoot, compat,    // Settings: channel and boot
  transient, enhancerExp, hpf                  // Settings: renderer and filter
}
```

Monet and theme state live outside `st`: `seedHex` and `themeMode`.

---

## 11. Functions

| Function | Purpose |
|---|---|
| `effective(i)` | Computes the displayed gain for band `i` from gains, intensity, tilts, contour, bass, dialogue, and balance. Enhancers apply only when master and EQ are on |
| `renderBands()` | Updates knob positions, fills, and dB readouts |
| `renderCurve()` | Rebuilds the curve path and the on/off styling |
| `drawGrid()` | Draws the axes once at boot |
| `renderHeadroom()` | Updates the headroom meter |
| `renderPresets()` | Rebuilds preset chips |
| `renderControls()` | Syncs toggles, segmented buttons, sliders, and labels to state |
| `renderDiagnostics()` | Rebuilds the diagnostics list for the current engine |
| `renderStatus()` | Updates top bar, power buttons, dimming, banner, route, and engine list |
| `refresh()` | Full update. Used for discrete actions and when a drag ends |
| `liveEq()` | Frame-throttled update of bands, curve, headroom, and controls. Used during drags and slider moves |
| `attachDrag(band)` | Pointer capture for band knobs. Calls `liveEq()` during drag and `refresh()` on release |
| `applyMonet(hex)` | Generates the palette from a seed and sets CSS variables |
| `buildScheme(seed)` | Builds light and dark tonal ramps in OKLCH |
| `seedFromImage(img)` | Picks the seed color from an uploaded image |

---

## 12. What is connected and what is not

**Connected (changes the UI and the curve):**
- Equalizer power, band sliders, presets, reset, save preset
- Master power, which dims Tone and Enhancers
- Tilts, intensity, volume contour, dialogue amount and toggle, bass shelf
- Engine selection, which updates the pill, route badge, and diagnostics
- Theme, Monet seed colors, wallpaper color

**Stored and displayed only (no effect on audio or curve):**
- Profile profiles, IEQ mode and strength, dialogue ducking, virtual bass, Enhancer deep bass
- A/B compare, start on boot, compatibility mode, transient mode, sub-bass high-pass
- Channel balance is approximated on the curve. Dual-channel EQ is not implemented

**Placeholders (show a toast only):**
- Calibration, ABX test, keep alive

**Not connected to real audio:** Every control affects the on-screen preview only. The engine, route detection, and diagnostics are sample values. Connecting to the real engine would require the Android side or a native bridge.
