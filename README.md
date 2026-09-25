# Genesis Player

A real native Android music player — always-on foreground playback with genuine
system DSP (BassBoost, multi-band Equalizer, LoudnessEnhancer) and Apollo
recommendations. Built for Brandon. Ember/Genesis palette: ash black, ember
orange, phoenix gold.

## What it is

- **Media3 ExoPlayer + MediaSessionService** — playback continues with the app
  backgrounded and the screen off (foreground `mediaPlayback` service).
- **Real audio effects** — `android.media.audiofx` BassBoost, Equalizer (with
  device presets + per-band control), and LoudnessEnhancer attached to the
  player's live audio session. Persisted across restarts.
- **Library** — 170 verified native Audius streams across 5 playlists
  (Peep Forever, Deep Poetry Trap, Cloud Nine). Streams re-verified
  2026-09-25 (HTTP 206, audio/mpeg).
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
