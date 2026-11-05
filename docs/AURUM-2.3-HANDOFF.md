# Aurum 2.3: playlists + sound. Handoff to Zane, then Brandon

Branch `claude/aurum-2.3` (from `claude/aurum-2.2`, the build Zane signed and delivered). Commit `07ce72c`.
Status: **BUILT and TESTED (emulator)**. Not yet VERIFIED (Zane) or ACCEPTED (Brandon).

## What's new for Brandon

- **Your own playlists (Lists tab).**
  - New, rename, delete, reorder, remove, Play, Shuffle.
  - Each song is labelled OFFLINE / IN AURUM / YOUTUBE / UNAVAILABLE.
  - Long-press any song in the Archive or the Crate → *Add to playlist*. Save any Apollo mix as a playlist in one tap.
  - Back up / import through the phone's file picker; nothing uploads.
  - Works offline with no AI. A playlist plays in Aurum's player when a song has an exact or offline copy,
    otherwise through YouTube. Offline, only what really plays offline is queued.
- **Repeat for YouTube songs** (off / all / one). Songs that play in Aurum's own player already had repeat in the Pulse Chamber.
- **Aurum sound presets (Sound Forge):** Flat, Bass+, Vocal, Night.
  - Shaped for this phone's own EQ bands, in whole-dB steps, with no band boosted more than +6 dB.
  - A clip guard (peak limiter, Android 9+) runs while a boosting preset or loudness is on.
  - **Defaults are unchanged:** nothing about the sound changes until a preset is tapped.

## Install

**The release must be signed with the AURUM key first (Zane).** It is `com.apexforge.genesisplayer`, versionCode 7,
versionName 2.3, so it installs **in place over 2.2** (versionCode 6, same AURUM signer) and keeps 2.2's data.
- **What changes:** 2.3 adds only new files (`aurum_playlists.json` and its `.bak`) and new preference keys
  (`aurum_preset`). No existing format changed.
- **What the battery covers:** updates in place from 2.1-deepspace and from the pre-vault Aurum build. It does not
  test an update from 2.2 itself.

Rollback: Android won't install a lower versionCode over 7. To go back to 2.2 you'd have to uninstall, which wipes
data, so back up playlists first (Lists → Back up). Brandon does not need to roll back to test 2.3; the Preview stays
side by side, as before.

## Signing (Zane)

1. Fetch the artifact. Run https://github.com/brandonduda8/genesis-player/actions/runs/41763413035, artifact
   **aurum-release** (id 13292426767, 13,798,856 bytes, zip sha256
   `4e01fc8a8a2ddc7133fd5ba9c9b5669cdbd101c517917ad092b721bf950f20c9`).
2. Check the APK inside, `aurum-2.3-release-unsigned.apk`. Its sha256 must be
   `497200dd7ec1c97f6934c9c2ebb24eb22a0a0d18a3b68b7f6a06388290fd5cc0`.
3. Sign and verify:

```bash
apksigner sign --ks <AURUM keystore> --ks-key-alias <alias> --out aurum-2.3.apk aurum-2.3-release-unsigned.apk
apksigner verify --print-certs aurum-2.3.apk | grep "SHA-256"   # must be 2A:44:37:02:…:CD:5B
```

## Evidence (run 41763413035, success)

- **Device battery: 32 PASS, 0 FAIL.**
  - Update in place over 2.1 and over the pre-vault build.
  - 120 s screen-off on a real stream; the wake lock is released on pause.
  - Media keys (via MediaSessionService dispatch), swipe-away, encrypted vault, airplane mode, cap eviction.
  - Preview → main transfer.
- **Instrumented (API 34): 9 / 9.**
  - **Playlists:** create, fill, reload from disk, offline routing, real playback; a newer file stays read-only.
  - **Sound:** the device EQ holds each preset's levels and the clip guard follows. Bass+ lifts 60 Hz vs 1 kHz by ≥ 1 dB
    on the post-effect output (Visualizer, 60 Hz + 1 kHz test signal).
  - **Vault:** 4 tests. **Audio focus:** 1 test.
- **JVM:** playlist model + routing 12, sound curves 6, plus the existing suite.
- **Permission delta vs the Aurum Pulse build:** +WAKE_LOCK only (unchanged from 2.2).

**Fixed in this branch before release:**
- **EQ rounding:** the first measurement found the device EQ holds band levels in whole dB.
- **Bass+ strength:** the first Bass+ lifted 60 Hz by only 0.6 dB.

The curves were corrected and re-measured. A sound improvement is claimed only where it was measured; this is not hi-res/lossless.

## Zane's real-phone checklist (Moto G 2025)

- [ ] Signer is 2A:44…CD:5B; installs over 2.2 without uninstalling; library, Crate and vault intact.
- [ ] Make a playlist from the Archive and from the Crate, reorder it, kill the app, reopen: it's all still there.
- [ ] Play a mixed playlist online, then in airplane mode: offline songs play, and others are explained, not faked.
- [ ] Repeat one / all on a YouTube song.
- [ ] Bass+ / Vocal / Night audibly differ on wired and on Bluetooth; no distortion on loud tracks with Bass+ and Loudness both on.
- [ ] Back up playlists to a file, delete a playlist, import the file back.
- [ ] Everything from the 2.2 list still holds: screen-off, calls, Bluetooth buttons, swipe-away.
