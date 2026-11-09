# Aurum 2.4 golden: keep playing when you leave the app. Handoff to Zane, then Brandon

Work order: WO-AURUM-GOLDEN-MAX (Zane, on Brandon's order of 2026-11-05).
Branch `claude/aurum-2.4-golden`, stacked on 2.3. Draft review PR: brandonduda8/genesis-player#22 (no merge).

**Status: TESTED on the emulator only. STAGED until it passes on Brandon's Moto G.** Done means Brandon installs it,
swipes the app away, turns the screen off, and the music keeps playing.

## Why 2.3 failed on the phone

There were two causes. The second one matters more.

1. **No notification permission and no battery exemption.**
   - The app never asked for notification permission (POST_NOTIFICATIONS). On Android 13+ that means the media
     foreground notification can't show.
   - The app never asked to be excluded from battery optimisation, so Motorola's power manager could kill playback.
   - **Fixed in charge 1 (2.4).**
2. **Most of the library played in the YouTube embed.**
   - Apollo's Play For You, Shuffle all and mix buttons sent every song to YouTube's embedded player.
   - That player pauses whenever the app leaves the screen. YouTube's terms forbid background play of the embed, so
     this was never fixable inside the embed.
   - **Fixed in charge 2 (2.4.1):** every play button now goes through one router.

## What's new for Brandon (2.4.1)

- **Aurum's engine first.**
  - A song with an Aurum source plays in Aurum's own player, which keeps going with the screen off and after a
    swipe-away. An Aurum source is a saved offline copy, a file you own, or an exact match to the real recording.
  - A whole-list button (Play For You, Shuffle all, a mix) queues those songs and tells you how many YouTube-only
    songs it left out.
- **The coverage number** (Apollo home).
  - It reads: "Keeps playing with the screen off: X of N".
  - It is counted from the same router the buttons use, never estimated.
  - To raise it: in the Crate, match songs to their exact recordings or save them offline.
- **Labels everywhere.**
  - Song tiles and Archive rows show AURUM ENGINE, YOUTUBE-ONLY or UNAVAILABLE.
  - The YouTube player says "YouTube-only · pauses when you leave the app".
- **Continue in YouTube.**
  - One tap hands the rest of a YouTube-only queue to YouTube's own app, through its public `watch_videos` queue link
    (up to 50 songs).
  - Whether that keeps playing in the background depends on your YouTube plan. Aurum promises nothing about it.
- **Lawful or labeled.**
  - No ripping, no stream extractors, no DRM work-arounds.
  - The embed is never played in the background.

## What's new in 2.4.2 (charge 3a)

- **Headphone correction inside Aurum's engine.**
  - Measured AutoEq profiles (MIT) now run as exact parametric filters on every song Aurum plays.
  - **Default: your JBL Tune 530BT, on at 50%, labelled *estimated*.** AutoEq has no measurement of the 530BT, so
    the profile comes from the measured Tune 520BT.
  - Change it in Sound Forge → Headphones: pick a profile, turn it off, or set the strength.
- **Now Playing format line.** For example "FLAC · 24-bit · 96 kHz · Lossless", read from the decoder, plus the
  correction in use. Anything the file doesn't report is left out, never guessed.

## What's new in 2.4.3 (charge 3b)

- **Two storage tiers in the Crate.**
  - Lossless (FLAC/ALAC/WAV) has its own 2 GB ceiling. The Crate shows how much of it is used.
  - Bulk holds lossy files, kept in their original format.
  - Converting lossless files to Opus is not built yet.
- **Hi-res lossless offline.** A 24-bit/96 kHz FLAC plays from the encrypted vault and shows its real format.
  Note that Bluetooth headphones resample anyway.
- **Songs the phone can't decode are skipped, not played silently.** Aurum says so and moves on. The emulator has no
  ALAC decoder, so check one ALAC file on the Moto G.

## What's new in 2.4.4 (charge 4)

- **Aurum has its own icon now.** It's an original gold mark: a vault ring, an "A" apex and an ember core on deep
  space. On Android 13+, themed icons get a matching single-colour version.
- **The lock-screen and notification player** show the AURUM mark.
- **On Android 12+, Aurum opens on a deep-space splash** instead of a white flash.

## Real-phone checklist (Brandon, Moto G). This is the only DONE gate

1. Install the AURUM-signed 2.4.4. It updates every earlier 2.x in place, including the signed 2.4.3
   (versionCode 12). Check that the AURUM icon shows in your app drawer.
2. First launch: allow notifications, then allow "don't optimise battery" (one dialog at a time).
3. Apollo: note the coverage number. If it is 0, save at least one song offline in the Crate first.
4. Tap Play For You. Check that the player that opens is Aurum's Now Playing screen, not the YouTube video.
5. Press Home, then lock the screen. Wait 5 minutes. **The music must still be playing.**
6. Unlock, swipe Aurum away in recents, and wait 2 minutes. **The music must still be playing**, and the lock-screen
   controls work.
7. Tap a YOUTUBE-ONLY song. It must show the label. Leave the app: it pauses (expected, and labeled). Tap
   "Continue in YouTube" and report whether YouTube keeps playing in the background on your plan.
8. On your JBLs, play a song you know well, then turn Headphones off and on in Sound Forge. Say whether 50% sounds
   better, worse, or should be stronger.
9. If you have a FLAC or ALAC file, add it in the Crate, play it, and read the format line on Now Playing.
10. Report pass or fail for each step, with the coverage number.

## Evidence

- **Charge 1 (2.4, versionCode 8), commit b7c1e49**
  - Run https://github.com/brandonduda8/genesis-player/actions/runs/42312748373: device battery 34/34, instrumented tests 9/9.
  - `aurum-2.4-release-unsigned.apk` sha256 `5e8b1fe5705d4ee28117c3e796d05bd387aa97ed93646020f440d332ae8524f2`.
- **Charge 2 (2.4.1, versionCode 9)**
  - JVM `PlayRouterTest`: 9/9, plus the 2.3 playlist tests (12/12).
  - Battery section 9 (on the emulator):
    - an Apollo play routes to Aurum's engine and keeps playing after Home plus screen-off for 60 s;
    - a YouTube-only play is labeled and has the hand-off.
  - The CI run, the hashes and the battery count are in the Commons receipt for this charge.

- **Charge 3a (2.4.2, versionCode 10), code commit 6e99a59; tests last changed at 93df629**
  - Run https://github.com/brandonduda8/genesis-player/actions/runs/42429301749: battery 41/41, instrumented tests 12/12.
  - `aurum-2.4.2-release-unsigned.apk` sha256 `8274dce90a53df311a8865241c134be5056f49405afdb417b5cc1dd2a4bd496b`,
    artifact aurum-release, id 13596349977.
  - The headphone processor's gain matches the analytic response within 0.25 dB on real Media3 buffers, and real
    playback runs through it.
  - The Visualizer FFT could not measure a 60 Hz lift reliably (+2.7 / +0.7 dB for the same +9 dB shelf), so it is
    logged as information only.

- **Charge 3b (2.4.3, versionCode 11), commit 9035bc7**
  - Run https://github.com/brandonduda8/genesis-player/actions/runs/42484050785: battery 48/48, instrumented tests 12/12.
  - `aurum-2.4.3-release-unsigned.apk` sha256 `23da3f8d59ec76ce61b7c37348376113d936d20b21d689975df6e8fdccf6b969`,
    artifact aurum-release, id 13618844822.
  - FLAC 24/96: proven end to end.
  - ALAC: the emulator has no decoder for it. Aurum reports this and skips the song. Not proven on a phone.

- **Charge 4 (2.4.4, versionCode 12), app code commit 2cbaff0; battery fixes up to 024853e**
  - Run https://github.com/brandonduda8/genesis-player/actions/runs/42577956403: battery 49/49, instrumented tests 12/12.
  - `aurum-2.4.4-release-unsigned.apk` sha256 `5539fd7703e1702497c185e6c9feb8d5ab003cc44bfde429362359f6b0e32d5f`. The hash
    is identical across three runs, and it is also published as a check-run annotation, so it can be checked without
    downloading anything. Artifact aurum-release, id 13656718656.

## Signing (Zane)

1. Fetch the `aurum-release` artifact from the charge-2 run.
2. Check that the APK's sha256 matches the receipt.
3. Sign it with the existing AURUM key, signer `2A:44:37:02:DF:C0:78:6F:47:E0:A1:81:57:CD:F0:EE:4A:1A:A7:05:63:30:F3:E5:ED:84:BE:0F:B3:A0:CD:5B`.
   Never use a new key.
4. Deliver it to Brandon with the checklist above.

## Not proven yet

- Anything on the Moto G itself. The emulator is not Motorola's power manager.
- Background play after "Continue in YouTube". It depends on Brandon's YouTube plan.
- How many of Brandon's real songs have an Aurum source today. The coverage card counts it on his phone.
- Whether the 530BT estimate sounds right on Brandon's own headphones.
- ALAC playback on the Moto G, which needs a platform decoder.
- Not built yet: converting lossless files to Opus. Charges 1–4 are all built and tested on the emulator.
