# Aurum

[![player-gate](https://github.com/brandonduda8/genesis-player/actions/workflows/gate.yml/badge.svg?branch=main)](https://github.com/brandonduda8/genesis-player/actions/workflows/gate.yml)
[![catalog-validate](https://github.com/brandonduda8/genesis-player/actions/workflows/catalog-validate.yml/badge.svg?branch=main)](https://github.com/brandonduda8/genesis-player/actions/workflows/catalog-validate.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A native Android music player in Kotlin and Jetpack Compose: always-on
foreground playback with genuine system DSP (BassBoost, multi-band Equalizer,
LoudnessEnhancer) and Apollo recommendations. A personal project, built for
its author's own listening. Ember/Genesis palette: ash black, ember
orange, phoenix gold.

> **Naming note:** this README and the Android `app_name` string
> (`android/app/src/main/res/values/strings.xml`) say "Aurum"; the
> `app_name` in `android/app/src/main/assets/config.json` says "Genesis Player".
> No rename is made here. Which name to use is Brandon's decision.

## What it is

- **Media3 ExoPlayer + MediaSessionService** — playback continues with the app
  backgrounded and the screen off (foreground `mediaPlayback` service).
- **Real audio effects** — `android.media.audiofx` BassBoost, Equalizer (with
  device presets + per-band control), and LoudnessEnhancer attached to the
  player's live audio session. Persisted across restarts.
- **Library** — 443 tracks across 14 playlists in the bundled catalog (counted
  2026-11-05 from `android/app/src/main/assets/tracks.json`): 381 with a direct
  Audius stream URL and 62 SoundCloud tracks (ids start with `sc_`) resolved to
  signed streams at play time via the SoundCloud adapter (stream-only, nothing
  ripped or stored; see SOUNDCLOUD_ADAPTER.md). Stream availability varies and is not asserted
  here; the remote catalog carries a `verified_206` flag, and `tools/catalog_validate.py` checks the catalog's
  structure.
- **For You (Apollo v1)** — 8 curated underground picks, each with a
  plain-language why-reason. On-device play/skip/completion counters are
  recorded; the full learning re-rank loop is v2.
- **Controls** — queue, shuffle, repeat (off/all/one), next/previous with
  skip bookkeeping, seek, notification + lock-screen + headset controls,
  audio-focus handling (pauses on calls), noisy-output handling.

## Verification gate

`.github/workflows/gate.yml` runs on every push to `main`. The hard gate:

1. APK builds and installs on an API-34 emulator
2. Headless playback starts via the DEBUG-only test hook
   (`am start ... -a com.apexforge.genesisplayer.TEST_PLAY`)
3. MediaSession reaches PLAYING and **position advances** (audio pipeline
   proven rendering through a live Audius stream)
4. BassBoost + Equalizer attach (log proof)
5. App backgrounded + screen off — position **keeps advancing**
6. No crash / no ANR

The test hook is DEBUG-only and activity-driven (API-34 foreground-service
exemption safe). Release builds ignore it.

**Stack:** Kotlin, Jetpack Compose (BOM 2024.10), Media3 ExoPlayer +
MediaSessionService, `android.media.audiofx`. minSdk 26, targetSdk 34.

## Build

```bash
cd android
gradle assembleDebug --no-daemon
```

No Gradle wrapper is committed; CI downloads Gradle 8.7 and runs it with
`--no-daemon`. The author's own build sandbox cannot run the Gradle daemon
(loopback sockets are intercepted), so CI builds are the source of truth.

## Tests

| What | Where | Runs on |
|---|---|---|
| JVM unit tests (EQ math, queue planner, crossfade math, energy rules) | `android/app/src/test/` | `unit-tests.yml`: pull requests to `main` (`gradle testDebugUnitTest`) |
| Instrumented tests (7 classes: queue planning and tap pipeline, shuffle, tap latency, Apollo, golden screens, and more) | `android/app/src/androidTest/` | `android-tests.yml`: pull requests to `main` |
| End-to-end playback gate on an API-34 emulator | `.github/workflows/gate.yml` | every push to `main` |
| Catalog validator self-test + catalog check | `tools/catalog_validate.py` | `catalog-validate.yml`: catalog changes |

```bash
cd android && gradle testDebugUnitTest --no-daemon
```

> **Current status:** the latest `player-gate` run on `main` (2026-09-30)
> failed at the "Golden Phase 1 screens" step; `catalog-validate` passed.

## Catalog validation

`tools/catalog_validate.py` is a stdlib-only, deterministic checker for the bundled catalog (no network, no stream probes):

```
python3 tools/catalog_validate.py selftest
python3 tools/catalog_validate.py bundled android/app/src/main/assets/tracks.json
```

It checks for duplicate track ids, playlist references to missing tracks, malformed stream URLs, missing artist or title, and duplicate normalized artist+title pairs (optionally allowlisted with `--allowlist FILE`). The `catalog-validate` workflow runs the self-test as a hard gate and the catalog check in report-only mode until the duplicate cleanup lands.

## License

Code is released under the [MIT License](LICENSE). Tracks are streamed from
their original sources at play time; no audio is bundled in this repo.
