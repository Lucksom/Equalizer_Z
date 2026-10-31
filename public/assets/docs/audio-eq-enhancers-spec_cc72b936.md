# Enhancers: Deeper Bass, Surround, Enhance Beats, Enhance Vocal

Add-on to `audio-eq-deep-optimization-v5.md` (called "the doc" below). APK-only. Numbers are tuned starting points from the reference script. Nothing was measured on a device, and the Kotlin was not compiled (no Kotlin compiler in this workspace). Verify with section 7.

Files in this pack:

| File | What it is |
|---|---|
| `Enhancers.kt` | Curve tables, mixer with the 6 dB budget, MBC blend. No Android calls, so it is unit-testable on the JVM |
| `EnhancerController.kt` | Drag / commit logic, `Virtualizer`, `BassBoost`, optional Enhancer-port properties |
| `EqEngine.kt` | Replacement for the `EqEngine` class in section 9 of the doc (own thread, no allocation per tick, adaptive write rate) |
| `enhancers_reference.py` | Generates every number below and checks the fast renderer |

---

## 1. What the user gets

Four controls, each an on/off switch with an amount slider (0–100 %, default 0 / off). They stack with presets, Intensity, route profiles, the volume contour and headphone correction, as one more layer in dB.

| Control | What it does | How |
|---|---|---|
| **Deeper bass** | More weight and depth, cleaner low end | EQ curve (route-aware), longer release on the Sub and Punch ceilings, optional Enhancer-port properties |
| **Surround audio** | Wider, more spacious image | Built-in `Virtualizer` (binaural on headphones, transaural on the speaker) plus a small air lift |
| **Enhance beats** | Punchier kick, snare and percussion | Kick and snare EQ, mud cut, slower ceiling attacks so transients pass |
| **Enhance vocal** | Clearer, more forward voices | Presence lift, body cleanup, a de-harsh cut at 7 kHz plus a tighter ceiling on that band |

Honest limits:
- **Deeper bass** is EQ and dynamics shaping. It does not create harmonics (the "virtual bass" trick of section 12.2), because nothing in the standard effect APIs can generate them. That needs the DSP host decision in section 18.3 of the doc.
- **Surround** depends on the device's own `Virtualizer`. Some devices ignore it, and the order against `DynamicsProcessing` in the chain was not verified. The UI shows "may not apply" when `canVirtualize` says no.
- It is not Enhancer Atmos or object-based audio (section 11 of the doc).

---

## 2. The curves (100 %, dB)

Each curve is a sum of gaussian bumps in log-frequency and shelves, sampled at the 31 render bands. Two variants: **Full** (wired, Bluetooth, USB) and **Small** (built-in speaker). The speaker route already cuts 31 and 62 Hz (section 6 of the doc), so on the speaker the bass and beats curves move their lift up to 110–170 Hz, where a small driver can actually play.

| Enhancer | Terms (Hz, dB, width in octaves; L = low shelf) |
|---|---|
| Deeper bass, Full | 55 Hz +4.5 (0.55), 140 Hz +1.5 (0.40), 300 Hz −1.0 (0.50), L 24 Hz −4.0 (0.25: protects the driver below 25 Hz) |
| Deeper bass, Small | 110 Hz +3.5 (0.45), 170 Hz +1.5 (0.40), 300 Hz −1.0 (0.50) |
| Surround | 10 kHz +1.0 (0.60) |
| Beats, Full | 70 Hz +2.5 (0.45), 200 Hz +1.5 (0.35), 320 Hz −1.5 (0.50), 4.2 kHz +2.5 (0.50), 9 kHz +1.0 (0.50) |
| Beats, Small | Same, with 120 Hz instead of 70 Hz |
| Vocal (both) | 300 Hz −1.5 (0.50), 1 kHz +1.0 (0.50), 2.8 kHz +3.0 (0.45), 5 kHz +1.0 (0.40), 7 kHz −1.5 (0.30), 11 kHz +1.0 (0.50) |

Resulting curves at the 31 render bands (index 0 = 20 Hz):

| Band | Hz | Bass Full | Bass Small | Surround | Beats Full | Beats Small | Vocal |
|---|---|---|---|---|---|---|---|
| 0 | 20 | −2.83 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| 2 | 32 | 0.93 | 0.00 | 0.00 | 0.10 | 0.00 | 0.00 |
| 4 | 50 | 4.33 | 0.15 | 0.00 | 1.44 | 0.05 | 0.00 |
| 6 | 80 | 2.97 | 2.11 | 0.00 | 2.28 | 1.07 | 0.00 |
| 8 | 127 | 1.77 | 3.96 | 0.00 | 0.62 | 2.68 | −0.07 |
| 10 | 202 | 0.13 | 1.25 | 0.00 | 0.89 | 1.51 | −0.78 |
| 12 | 320 | −0.97 | −0.86 | 0.00 | −1.27 | −1.25 | −1.47 |
| 17 | 1016 | 0.00 | 0.00 | 0.00 | −0.01 | −0.01 | 1.01 |
| 21 | 2560 | 0.00 | 0.00 | 0.00 | 0.90 | 0.90 | 2.96 |
| 23 | 4064 | 0.00 | 0.00 | 0.10 | 2.56 | 2.56 | 2.19 |
| 25 | 6451 | 0.00 | 0.00 | 0.57 | 1.79 | 1.79 | −0.34 |
| 27 | 10240 | 0.00 | 0.00 | 1.00 | 1.02 | 1.02 | 0.73 |
| 30 | 20480 | 0.00 | 0.00 | 0.23 | 0.06 | 0.06 | 0.20 |

Peaks at 100 %: Bass 4.33 (Full) / 3.96 (Small), Surround 1.00, Beats 2.56 / 2.68, Vocal 3.00.

---

## 3. Mixing rules

1. **Linear in amount.** `enhancer31 = Σ amount_k × curve_k`.
2. **Budget.** If `max(enhancer31) > 6 dB`, scale all four by `6 / max`. All four at 100 % peak at exactly 6.00 dB on both variants (raw peak 6.58). The scale is continuous, so there is no jump: the largest step for a 0.01 change in amount on a bass + beats sweep is 0.066 dB. Show "Limited by headroom budget" when the scale is below 1.
3. **Layer position.** `effective = clamp(taste31 + correction31 + enhancer31, −15, +10)` (section 3 of the doc). The headroom meter, ceilings and makeup see the enhancers like any other gain.
4. **Flat plus enhancers.** In Flat the chain is normally bypassed. If any enhancer is above 0, the chain stays on with zero sliders. Bypass only when the preset is Flat **and** all four enhancers are 0 (`EnhancerMixer.anyActive`).
5. **Drag versus commit.** While a slider is dragged: EQ values only (60 ms glide). On release, toggle, preset or route change: recompute makeup, MBC parameters and the side effects (250 ms glide). This keeps the rule "makeup is frozen while dragging" and keeps the number of binder calls low.
6. **Route change.** Re-mix with the Full or Small variant, then commit.
7. **Preset change.** The amounts stay. Commit.
8. **Hearing safety.** Never raise the system volume. The existing warnings (total boost above +6 dB, ceiling load above 4 dB) apply to the enhanced curve.

### 3.1 MBC changes (commit only)

| Enhancer | Change at 100 % |
|---|---|
| Beats | Blend attack, release and ratio toward the Percussion Clarity override table (section 5.2 of the doc): times blended geometrically, ratios linearly. Slower attacks let strokes through |
| Deeper bass | Sub release +70 ms, Punch release +40 ms (steadier sustained bass) |
| Vocal | Sizzle band (5.7–11.5 kHz): threshold −2 dB, ratio +1.0 (tighter de-harsh) |
| Surround | None |

Example (base preset values; Sub / Punch / Body / Presence / Sizzle / Air):

| Amounts (bass, surround, beats, vocal) | Attack ms | Release ms | Ratio | Sizzle threshold |
|---|---|---|---|---|
| 0, 0, 0, 0 | 40, 15, 12, 6, 1.5, 2 | 250, 160, 130, 90, 45, 50 | 4.0, 3.5, 2.5, 3.0, 3.5, 2.5 | −9 |
| 1, 0, 0, 0 | unchanged | **320, 200**, 130, 90, 45, 50 | unchanged | −9 |
| 0, 0, 1, 0 | 50, 30, 25, 12, 4, 5 | 250, 150, 120, 80, 50, 50 | 3.0, 2.8, 2.2, 2.5, 2.8, 2.0 | −9 |
| 0, 0, 0, 1 | unchanged | unchanged | …, **4.5**, 2.5 | **−11** |
| 0.5, 0, 0.5, 0.5 | 44.7, 21.2, 17.3, 8.5, 2.5, 3.2 | 285, 174.9, 124.9, 84.9, 47.4, 50 | 3.5, 3.15, 2.35, 2.75, 3.65, 2.25 | −10 |

---

## 4. Side effects and optional controls

### 4.1 Surround: `Virtualizer`

- Create with `Virtualizer(0, sessionId)`, the same session as the engine.
- Mode (API 28+): headphones → `VIRTUALIZATION_MODE_BINAURAL`, speaker → `VIRTUALIZATION_MODE_TRANSAURAL`. Check `canVirtualize(CHANNEL_OUT_STEREO, mode)` and show "may not apply" when false.
- Strength: `1000 × maxS × amount^1.3`, `maxS` 0.8 on headphones, 0.6 on the speaker. The exponent makes the first half of the slider gentle. If `strengthSupported` is false, the effect is on/off at 50 %.
- Debounce: strength is only sent when it moves by 20 or more, and only on commit.
- Tonal part: +1.0 dB around 10 kHz (curve above).
- Extra headroom: virtualizers can raise the level. If the effect sits before `DynamicsProcessing`, the input gain protects it. If after, the limiter backstop does. Verify the order on your device (section 7, test T9).

### 4.2 Deeper bass on Legacy tier 1

`BassBoost` strength = `600 × amount`, only when `DynamicsProcessing` is **not** active (section 13 of the doc says to set it to 0 otherwise). The EQ curve is averaged onto the native bands with the +4 dB cap.

### 4.3 Optional: Enhancer-port properties

Only for devices whose Enhancer port reads `enhancer.deepbass` and `enhancer.bass`. These properties come from the notes you supplied, not from Android, and I did not verify what they do on your phone.

- **Detect:** run `getprop enhancer.deepbass` and `getprop enhancer.bass` through the helper. Show the card only if either returns a value, or if the user switches it on by hand ("Try Enhancer control").
- **Controls:** "Enhancer deep bass" switch → `enhancer.deepbass` 1/0. The Deeper bass slider also sets `enhancer.bass = round(96 × amount)` while that switch is on. 0 means unmodified.
- **Limits:** the maximum is 96, half of the 192 point your notes mention for the 36 dB limit. Raise it only after listening tests.
- **Safety:** numbers only reach the shell. The first values are saved and `restore()` puts them back on Reset, and when the app exits. RAM-only: no writes to `/system` or `/vendor`.
- **Threading:** the shell runs on its own single thread and only the newest request runs (coalescing), so a fast slider cannot queue shell calls.
- **Needs:** root, or shell if the system allows setting those properties (SELinux may refuse; unverified).

---

## 5. Performance design (smooth, no lag)

| Problem in v4 | Fix in `EqEngine.kt` | Effect |
|---|---|---|
| Binder calls ran on the main thread | Own `HandlerThread` ("eq-writer", foreground priority). Public methods post to it | UI never waits on audioserver |
| `Layers` arrays shared with the UI thread | `Layers` copies its arrays on construction | No data races, no locks |
| `renderCurve` rebuilt PCHIP slopes for each of 31 bands (about 90 small arrays per call) | Slopes once per change, basis values precomputed per band (`FastRender`). Matches the v4 renderer: max difference 0 dB over 2000 random slider sets | Cheaper drags, less GC |
| `effective()` allocated arrays on every call | `effectiveInto` writes into reused buffers | No allocation in the update path |
| `"$inputDb\|$makeupDb\|$limiterDb"` string built on every write | `fixedDirty` / `mbcDirty` flags | No string garbage at 33 Hz |
| `Dsp.MBC_RANGE[b].map{}.average()` per band per tick | Plain loop | No lists in the tick |
| Fixed 30 ms tick regardless of cost | Tick = 2.5 × measured write time, between 30 and 100 ms | Backs off on slow devices instead of queueing binder calls |
| `setEnabled(false)` was undone by the enable-status listener | `wantEnabled` flag | Bypass works |
| Enhancer slider changed MBC on every move | Drag = EQ only, commit = MBC + makeup + effects | Fewer, cheaper calls |
| Shell calls for Enhancer | Single background thread, newest request only | No stalls |

Budgets to check on a mid-range phone (section 7, T6–T8): at most 33 EQ updates per second while dragging, `writeCostMs()` average under 8 ms, UI frame time unaffected, app CPU under 5 %, no clicks.

Two things to measure, not assume:
1. Cost of a `setPreEqBandAllChannelsTo` call. `writeCostMs()` reports it, so log it in a debug build.
2. Whether `setPreEqBandAllChannelsTo` is one binder call per channel or per band group on your Android versions. If a full 31-band write exceeds about 20 ms, lower the glide resolution (write every second tick) before reducing band count.

---

## 6. App-builder brief (for Google AI Studio)

**Screen "Enhancers"** (under the Sound tab):

- Four rows, each: icon, name, one-line description, switch, slider 0–100 %. Defaults: all off, slider at 60 % when first switched on.
- A thin bar under the list: "Enhancer budget: X of 6 dB". Amber when scaled ("Limited by headroom budget").
- Per row badges: Surround → "May not apply" when `VirtualizerFx.supported()` is false. Deeper bass → "Speaker mode: lifts upper bass" when the route is the built-in speaker.
- Enhancer card (hidden unless detected or switched on in Advanced): switch "Enhancer deep bass", note "Experimental, device-specific". Reset button restores the original values.
- A/B button: compares with all enhancers at 0 at matched loudness (section 10 of the doc).
- Slider events: `onDrag` while moving, `onCommit` on release and on switch changes.
- State shown for each mode: Legacy tier 1 → bass and beats approximate, vocal approximate, surround via `Virtualizer` only, no ceilings. Tier 2 / ADB / Shizuku / Root → all four at full resolution.

**ViewModel wiring**

```kotlin
val controller = EnhancerController(
    engine = engine,
    virt = VirtualizerFx(sessionId),
    bassBoost = if (tier1) BassBoostFx(sessionId) else null,
    enhancer = if (enhancerDetected) EnhancerControl(shell) else null,
    buildLayers = { enh31 -> Layers(user, intensity, routeOffsets, contour, correction31, enh31) },
    ctx = { PresetCtx(mbcFor(preset), routeBaseDb, limiterDb, preset == FLAT) },
    route = { currentRoute(audioManager) }
)
// slider onValueChange:        val r = controller.onDrag(Enhancer.BEATS, value / 100.0)
// slider onValueChangeFinished: controller.onCommit()
// preset, route, intensity or correction change: controller.onCommit()
```

Replace the `EqEngine` class from section 9 of the doc with `EqEngine.kt`. Keep `Dsp`, `Route`, `Mbc` and `currentRoute()` as they are, and change the `Layers` constructor calls (one new optional parameter).

---

## 7. Tests

JVM unit tests (no device):

| # | Test | Pass |
|---|---|---|
| T1 | Curves | Peaks 4.33 / 3.96 (bass), 1.00 (surround), 2.56 / 2.68 (beats), 3.00 (vocal), each ±0.02 dB |
| T2 | Golden mix A: bass 1.0 + surround 0.5, Full | Equals the vector below ±0.01 dB |
| T3 | Golden mix B: beats 1.0 + vocal 1.0, Small | Equals the vector below ±0.01 dB, scale 1.0 |
| T4 | Budget | All four at 1.0 → peak 6.00 on both variants. Sweep amounts 0→1: no step above 0.1 dB per 0.01 |
| T5 | `FastRender` against `Dsp.renderCurve` | Max difference below 1e-9 dB on random slider sets |

Device tests:

| # | Test | Pass |
|---|---|---|
| T6 | Drag an enhancer slider for 10 s | At most 33 EQ writes per second, `writeCostMs()` under 8 ms, no frame drops |
| T7 | Switch each enhancer on and off, fast | No clicks, pops or zipper noise |
| T8 | Commit during playback (release slider) | Makeup and MBC change without an audible step (250 ms glide). If a step is heard, add the 100 ms ramp from section 10 of the doc |
| T9 | Surround with the engine on | Spatial effect audible, level change below 1 dB, no clipping. Note whether the `Virtualizer` sits before or after the engine |
| T10 | Deeper bass, wired and speaker | Sweep: bump near 55 Hz (wired) or 110 Hz (speaker) matches section 2 within 1 dB, 20 Hz not boosted |
| T11 | Beats | Kick drum recording: transient peak-to-RMS ratio does not drop (slower attacks) |
| T12 | Vocal | 7 kHz region reduced, no extra sibilance on a dry vocal recording |
| T13 | Loudness parity | Each enhancer at 100 % within ±1 LU of off (makeup, section 5.3) |
| T14 | Flat plus enhancers | Chain stays on. All off in Flat → chain bypassed |
| T15 | Enhancer (if present) | Values read back by `getprop`; Reset restores the originals; nothing runs on the main thread |
| T16 | Route change | Variant switches within 300 ms |
| T17 | Bypass | `setEnabled(false)` stays off (v4's listener bug fixed) |

Golden vectors (index 0 = 20 Hz … index 30 = 20.5 kHz):

```
A: bass 1.0, surround 0.5, Full
-2.832, -1.167, 0.929, 2.975, 4.330, 4.203, 2.967, 2.009, 1.768, 1.232, 0.127, -0.740,
-0.965, -0.694, -0.315, -0.092, -0.017, -0.002, 0.000, 0.000, 0.000, 0.002, 0.012, 0.048,
0.137, 0.287, 0.442, 0.499, 0.414, 0.253, 0.113

B: beats 1.0, vocal 1.0, Small
0.000, 0.000, 0.000, 0.005, 0.052, 0.312, 1.074, 2.150, 2.607, 2.124, 0.733, -1.475,
-2.722, -2.187, -0.940, 0.096, 0.757, 1.006, 0.911, 1.070, 2.176, 3.861, 4.880, 4.755,
3.451, 1.448, 1.113, 1.758, 1.479, 0.764, 0.260
```

---

## 8. Tuning by ear (after the tests pass)

1. Set the preset to Flat, route to your main headphones, volume moderate.
2. **Deeper bass:** raise to 100 %. If boomy, reduce the 55 Hz bump (4.5 → 3.5) before touching the 140 Hz term. If thin on speaker, raise the 110 Hz term to +4.5.
3. **Beats:** use a track with a dry kick and snare. If harsh, reduce the 4.2 kHz term (2.5 → 1.8).
4. **Vocal:** use a close-miked voice. If sibilant, deepen the 7 kHz cut (−1.5 → −2.5). If nasal, reduce the 1 kHz term.
5. **Surround:** raise the amount until the image widens but centre vocals do not move. Lower `maxS` if bass thins out.
6. Re-run T13 and keep every enhancer within ±1 LU of off.

---

## 9. Unknowns (to settle on the device)

| Item | Status |
|---|---|
| Curve values and MBC deltas | Starting points from design logic, no measurement |
| `Virtualizer` strength curve and chain order against `DynamicsProcessing` | Not verified. Test T9 |
| `Virtualizer` on the global session without root | Not verified. Same requirements as the engine (section 9 of the doc) |
| `enhancer.*` property effects and whether setting them is allowed | Taken from your notes, not verified. Test T15 |
| Binder cost of band writes | Measure with `writeCostMs()` |
| Harmonic virtual bass, crossfeed, true surround rendering | Needs a DSP host (doc section 18.3) |
