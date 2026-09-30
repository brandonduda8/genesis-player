# WO-AURUM-008 DSP evidence (branch dsp-v2, local only, nothing pushed or merged)

Measured 2026-09-30 on a cloud Linux box (Intel Xeon 2.8 GHz, JVM). Nothing here was run on an Android device.

## What was built
- **Engine (pure Kotlin, JVM-testable):** `dsp/` Biquad, ParametricEq (preamp -12..+6 dB, low shelf, 6 peaks, high shelf, per-band freq/gain/Q/enable, whole-DSP bypass, coefficient smoothing), Headroom (conservative worst-case boost -> auto preamp), Limiter (final transparent peak guard, -0.3 dBFS ceiling), Loudness (only from measured data, else UNKNOWN/off), RouteProfiles + RouteClassifier (per output class, persisted, route caps on phone speaker), EasyMapping (legacy 4-stage UI mapped onto the 8-band engine), Presets (6, values published in code).
- **Android glue (NOT compiled here):** `audio/EqAudioProcessor.kt` (Media3 AudioProcessor), `audio/DspController.kt` (state, fail-closed watchdog, legacy-effects gate), `audio/RouteDetector.kt`, `PlayerService.kt` (processor inserted in both ExoPlayers; separate processor for the crossfade player), `ui/AdvancedEqPanel.kt`, `ui/EqScreen.kt` (Easy/Advanced switch), `ui/FourStageEqPanel.kt` (Easy mode now drives the engine).
- **Bug found and fixed this session:** Easy sliders bled into each other (Bass Heavy "Mid -1 dB" measured +0.85 dB at 1 kHz). EasyMapping now refines stage values so each slider reads true at its reference frequency (80 Hz / 1 kHz / 8 kHz). A parameter-generation read-order race in EqAudioProcessor (found by an independent reviewer) was also fixed.

## Test evidence
- `test_log.txt`: 92 pure-JVM tests, pass=92 fail=0 (runner: local JUnit shim; real CI would use junit 4.13.2 via Gradle, which has not been run).
- Covers: biquad stability/impulse response, flat = unity, response checkpoints match measured sine gain (max error 2.6e-7 dB), smoothing without clicks (jump ratio ~1.0 vs hard-splice control 7.0), bypass bit-exact, limiter transparent below ceiling, headroom (worst output peak 0.968 vs limit 1.0), route detection/profiles/persistence, loudness never fakes data, Easy mapping monotonic and legal, presets.
- `preset_response.csv` / `preset_response.png`: response of every preset (96 log-spaced points).
- `preset_clipping.csv` / `clipping_before_after.png`: worst peak over 10 full-scale stress signals (8 sines incl. 30-120 Hz, swept-sum, pink).

| Preset | Unprotected peak | Protected peak | Auto preamp |
|---|---|---|---|
| Reference | 0.0 dBFS | -0.3 dBFS | 0.0 dB |
| FEEL IT | +5.8 | -0.5 | -6.3 dB |
| Night Drive | +3.9 | -0.4 | -4.3 dB |
| Emo/Vocal | +3.7 | -0.3 | -4.1 dB |
| Trap-Rock/Rage | +8.5 | -0.8 | -9.3 dB |
| Dark Cinematic | +6.4 | -0.9 | -7.3 dB |

Honest note: auto-headroom is conservative, so strong presets play quieter unless the listener raises volume. Loudness-matched A/B (`abMatchGainDb`) exists so comparisons are fair.

## CPU (JVM proxy, NOT a phone)
`cpu_bench.txt`: 20 s of 48 kHz stereo processed in 0.014 s (flat), 0.066 s (FEEL IT), 0.089 s (Trap-Rock/Rage) = 0.07% to 0.44% of one server core. A low-end phone core is perhaps 5-20x slower; that estimate is unmeasured. The controller has an on-device watchdog that fails closed to pass-through above a CPU budget.

## NOT VERIFIED (must be proven before this counts as done)
- Anything Android: no Gradle build, no APK, no emulator/device run (Maven/Google repos blocked here). Kotlin for PlayerService, DspController, UI and media3 API usage is reviewed by two independent reviewers but never compiled. EqAudioProcessor + RouteDetector compile only against hand-written stubs.
- Playback advances with DSP on; crossfade with two processors; legacy effects gate; route switching on real hardware.
- On-device CPU, latency, battery. Screenshots of Easy and Advanced screens (none exist).
- CI Gradle test run with real JUnit.
- Known gaps: limiter tail (~1.5 ms) is not drained at end of stream (possible tiny gap at gapless boundaries); multichannel (>2 ch) and 24-bit sources bypass the EQ.
- Audit vs Poweramp/Neutron/Wavelet-class EQs: NOT DONE. Web search returned only forum/changelog pages; no side-by-side visuals exist. Do not claim parity.
