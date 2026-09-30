# Aurum

A real native Android music player — always-on foreground playback with genuine
system DSP (BassBoost, multi-band Equalizer, LoudnessEnhancer) and Apollo
recommendations. Built for Brandon. Ember/Genesis palette: ash black, ember
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
- **Library** — 427 tracks across 13 playlists in the bundled catalog (measured
  2026-09-30 from `android/app/src/main/assets/tracks.json`): 365 with a direct
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

## Build

```bash
cd android
gradle assembleDebug --no-daemon
```

Do not run the Gradle daemon in the sandbox — it cannot work there (loopback
sockets are intercepted). CI builds are the source of truth.

## Catalog validation

`tools/catalog_validate.py` is a stdlib-only, deterministic checker for the bundled catalog (no network, no stream probes):

```
python3 tools/catalog_validate.py selftest
python3 tools/catalog_validate.py bundled android/app/src/main/assets/tracks.json
```

It checks for duplicate track ids, playlist references to missing tracks, malformed stream URLs, missing artist or title, and duplicate normalized artist+title pairs (optionally allowlisted with `--allowlist FILE`). The `catalog-validate` workflow runs the self-test as a hard gate and the catalog check in report-only mode until the duplicate cleanup lands.
