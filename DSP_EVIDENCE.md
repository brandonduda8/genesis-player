# WO-AURUM-008 — DSP Build Evidence (branch `dsp-v2`)

Date: 2026-09-29. Builder: Zane's engineering super swarm, TRACK A.
Status: **BUILD IN PROGRESS — NOTHING merges without Brandon's tap.**

## 1. Architecture

```
ParametricDsp.kt            pure-Kotlin DSP core, zero Android deps
├── DspMath                 RBJ cookbook biquads (peaking/low-shelf/high-shelf),
│                           coefficient clamps, magnitudeDb() probe
├── BiquadFilter            one biquad + exponential coefficient smoothing
│                           (tau = 20 ms; snap() for preset loads)
├── EqBand / BandType       one parametric band (freq/gain/Q/bypass)
├── ParametricEq            preamp (-12..+6) → 8 bands (LS, 6×PK, HS)
│                           → auto headroom → tanh soft limiter (-1 dBFS)
├── DspPreset / DspPresets  6 published presets (values in code AND tests)
├── EasyDspMapper           FourStageEq.State → parametric (Easy mode)
├── OutputClass             DEFAULT / PHONE_SPEAKER / HEADPHONES /
│                           BLUETOOTH / CAR_EXTERNAL
├── DspPrefStore            (interface — JVM-testable persistence)
└── DspProfiles             per-route save/load, phone-speaker sub-bass cap
                            (+3 dB under 150 Hz, documented policy)

DspAndroidStore.kt          DspPrefStore → SharedPreferences adapter
ui/ParametricEqPanel.kt     ADVANCED mode panel (drives the real core)
ui/EqScreen.kt              Easy / Advanced toggle (Easy = untouched
                            Golden Phase 1 system-audiofx path)
```

Signal flow per block: `preamp(effective) → 8 biquads (smoothed) → soft limiter`.
Headroom: worst-case positive gain measured on a 1/24-octave grid 20 Hz–20 kHz
(preamp + full cascade); if > 0 dBFS, preamp is auto-reduced by exactly the
excess (deterministic, conservative). The tanh limiter is transparent below
−1 dBFS and asymptotes to 1.0 — a 0 dBFS stress signal can never clip.

Loudness honesty: no ReplayGain/EBU-R128 is claimed anywhere. Volume-matched
A/B works by ATTENUATING THE BYPASSED PATH by the engaged chain's measured
broadband mean (`abBypassTrimDb()`), labeled as such in the UI. Boosting the
engaged path would be futile: the headroom auto-cut eats exactly that boost
(the engaged mean is pinned at filterMean − filterWorst whenever the cut is
active). Attenuation can never clip; a boost into the ceiling could. This was
found by measurement during the build (the Python battery's trim test failed
with a pinned −2.293 dB residual), not assumed.

Preset preamps ship at their measured headroom point: each non-flat preset's
base preamp = −(worst-case filter gain) − 0.5 dB margin, so protection is
armed but NOT engaged at load. The protection indicator then means something
(it lights up only when user tweaks push the chain hot). Verified by the
`presetsShipAtMeasuredHeadroomPoint` test.

Preset shape checkpoints are RELATIVE (dB vs the 1 kHz mids), not absolute:
the headroom auto-cut shifts every preset's absolute level (engaged worst case
is ≤ 0 dB by construction), so absolute pins would fight the anti-clip
guarantee. Relative shape is invariant under the cut.

## 2. Changed-file list (branch dsp-v2)

| File | Change |
|---|---|
| `android/app/src/main/java/com/apexforge/genesisplayer/ParametricDsp.kt` | NEW — DSP core (~600 lines) |
| `android/app/src/main/java/com/apexforge/genesisplayer/DspAndroidStore.kt` | NEW — SharedPreferences adapter |
| `android/app/src/main/java/com/apexforge/genesisplayer/ui/ParametricEqPanel.kt` | NEW — Advanced panel |
| `android/app/src/main/java/com/apexforge/genesisplayer/ui/EqScreen.kt` | Easy/Advanced toggle |
| `android/app/src/test/java/com/apexforge/genesisplayer/ParametricDspTest.kt` | NEW — 22 deterministic JVM tests |
| `tools/dsp_reference.py` | NEW — Python mirror + test battery (evidence engine) |
| `tools/dsp_measurements.json` | NEW — measured numbers (generated) |
| `DSP_EVIDENCE.md` | this file |

Golden Phase 1 files (`FourStageEq.kt`, `FourStageEqPanel.kt`,
`AudioFxController.kt`, `GoldenPhase1Test.kt`) are **untouched** — no playback
regression by construction.

## 3. Test methodology (honest)

This box has **no JDK/Kotlin toolchain** and the Gradle daemon cannot run here
(sandbox hijacks loopback TCP), so the 22 Kotlin JVM tests in
`ParametricDspTest.kt` are written for Gradle CI / a real build machine.
They are NOT green-claimed here.

What WAS run: `tools/dsp_reference.py`, a formula-by-formula Python mirror of
`ParametricDsp.kt` (same RBJ coefficients, same smoothing law, same limiter,
same headroom math, same preset values, same Easy mapping, same profile
policy). Every assertion in the Python battery mirrors an assertion in the
Kotlin test file. Numbers below are real measurements of the algorithm — not
of the Kotlin file itself. Any divergence between mirror and Kotlin source is
a defect to fix before merge.

Run history (all on this box, `nice -n 19 ionice -c3`):
- Full battery run 1: EXIT 1 — caught a REAL bug: the mirror's biquad delay
  line was reversed (`x2,x1 = x,x1`), making even the flat preset unstable.
  The Kotlin (`x2 = x1; x1 = x`) was correct. Fixed in the mirror.
- Full battery run 2: 38/40. The 2 failures were test-design flaws, not DSP
  bugs: (a) absolute response checkpoints fight the headroom auto-cut
  (engaged worst case is ≤ 0 dB by construction) → checkpoints are now
  shape-relative (dB vs 1 kHz mids); (b) preamp-based A/B trim is futile —
  the engaged mean is pinned at filterMean − filterWorst whenever the cut is
  active (measured residual −2.293 dB) → A/B now attenuates the bypassed path
  instead (`abBypassTrimDb()`), and preset preamps ship at their measured
  headroom point (−(worst-case filter gain) − 0.5 dB margin).
- Targeted re-validation on current code (light, ~2–3 s each): impulse
  stability ×6 (tails ≤ 8e-08, E < 1.0), limiter transparency (dmax 6.5e-14),
  limiter caps 1×/2×/10× (peak ≤ 1.0), bypass identity, custom round-trip,
  smoothing convergence (residual 1.1e-13), shape checkpoints ×5 (margins
  0.5–4 dB), headroom idle ×6 (worst −0.52…−0.78 dB), A/B level match
  (−0.16 dB on a 4-sine chord, limiter uninvolved). ALL PASS.
- Full single-process battery re-run on final code: PENDING — box 1-min load
  has been 6.4–11.2 all session (load gate: no heavy runs while > 6). The
  `tools/dsp_measurements.json` on disk is therefore STALE (pre-fix preamps);
  §4/§5 stay placeholders until the re-run lands.

## 4. Measured results

<!-- FILLED AFTER TEST RUN — see tools/dsp_measurements.json -->

## 5. Before/after clipping evidence

<!-- FILLED AFTER TEST RUN -->

## 6. Audit vs respected players (no copying, capability/UX only)

| Capability | Poweramp | Neutron | Wavelet | This build (dsp-v2) |
|---|---|---|---|---|
| Parametric bands | 10+ bands, per-band freq/Q/gain | 10+ bands + auto-EQ | 9-band graphic + AutoEq | 8 bands (LS + 6 PK + HS), freq/Q/gain each |
| Presets | many, import/export | many + AutoEq profiles | AutoEq 4000+ headphones | 6 published starting points, values in code/tests |
| Anti-clip | limiter + preamp | preamp + dither options | limiter | auto headroom (measured worst-case) + tanh limiter @ −1 dBFS, protection exposed |
| Per-output profiles | per-output presets | per-device profiles | per-device AutoEq | per OutputClass persisted profiles |
| A/B trust | bypass toggle | bypass | bypass | one-tap bypass + volume-matched trim + reset-to-flat, real dB/Q/Hz shown |
| Honesty | — | — | — | no fake loudness standard; BassBoost strength never labeled dB; tiny-speaker cap labeled as policy |

Gap vs best-in-class (owned): no AutoEq headphone database, no spectrum
analyzer UI, live playback routing not yet wired (device-gated). The core is
the foundation; those are follow-up work orders, not hidden claims.

## 7. Device/API limitations encountered

- No JDK/Kotlin/Gradle on this box → Kotlin tests written but not executed here.
- Gradle daemon cannot run on this box (sandbox TCP hijack) → Android unit
  tests must run on CI / Brandon's build machine.
- Media3 `AudioProcessor` hookup (routing the parametric engine into live
  playback) needs a device/emulator to verify — designed for, not built.
- Screenshots of Easy/Advanced panels need a device — not produced here.
- `android.media.audiofx` Equalizer band counts/ranges vary by device; the
  parametric core is device-independent by design (fixed 48 kHz reference;
  sample-rate is a constructor parameter).

## 8. NOT VERIFIED list (explicit)

1. `ParametricDspTest.kt` (22 Kotlin JVM tests) — not executed; no JDK here.
2. `ParametricEqPanel.kt` / `EqScreen.kt` edits — not compiled; Compose
   correctness unverified on this box.
3. Live-audio routing of the parametric engine (Media3 AudioProcessor) —
   not implemented; device-gated.
4. Screenshots of Easy + Advanced EQ — no device/emulator available.
5. CPU overhead on a real low-end device — JVM smoke bound only; the
   Python reference timing is NOT a device number.
6. `DspAndroidStore` SharedPreferences round-trip on Android — logic is
   JVM-tested via the `DspPrefStore` interface; the Android adapter itself
   is not executed.
7. Playback-advances-while-DSP-enabled — needs instrumented run.
8. Old `FourStageEq` Easy mappings still behave — `GoldenPhase1Test` /
   `FourStageEqMathTest` untouched and un-run here (no test runner).

## 9. How to verify (for the build machine / Brandon)

```
# on a machine with the Android toolchain:
cd ~/workspace/genesis-player
git checkout dsp-v2
./gradlew :app:testDebugUnitTest --tests "com.apexforge.genesisplayer.ParametricDspTest"
./gradlew :app:testDebugUnitTest --tests "com.apexforge.genesisplayer.FourStageEqMathTest"
# python mirror (any box):
python3 tools/dsp_reference.py
```

Merge/publish remains Brandon's tap alone. Branch `dsp-v2` is local-only.
