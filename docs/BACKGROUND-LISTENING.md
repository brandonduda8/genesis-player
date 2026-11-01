# Aurum Pulse — Continue Listening / Background Playback

## What the app actually supports

Aurum Pulse 2.x has two different playback surfaces:

- **Aurum native audio**: imported files, phone music, legally downloaded
  recordings, and permitted Audius/Archive streams played with Media3
  `PlayerService`. Media3 manages a foreground notification and should keep
  playing when you press Home, lock the screen, or dismiss the UI.
- **YouTube IFrame embed**: displays the user's YouTube music-history videos.
  **Must remain visible.** YouTube developer rules prohibit embedded playback
  while the client's window is hidden or closed. Never extract audio, run the
  iframe invisibly, bypass permissions, or spoof playback.

## New feature

On the visible YouTube player, **Continue listening in background** only
appears if the **current** song has an offline or legitimately matched exact
audio source in Aurum. Tapping it explicitly pauses the YouTube iframe,
starts the matching source through Media3, and queues subsequent songs with
permitted audio sources (skipping unmatched songs).

When none exists, show **Continue in YouTube Music**. Playback in the
official YouTube Music app is subject to the user's account/region/plan and
that app's rules; Aurum does not claim or control it.

The source choice uses `BackgroundRoute`; no alternate versions, generic
catalog tracks, direct video URLs, or unmatched sources can silently replace
the currently playing video.

## How to prove it on a real Android phone

1. Import a real YouTube Takeout (on the phone, not into CI). In Crate,
   match artists, or import a known audio file. Confirm which songs are
   actually marked OFFLINE / EXACT; a catalog record is not proof of a
   playable network URL.
2. Start an *offline* file from Crate → Sanctuary. Verify sound. Press
   **Home**, open another app, lock the phone, then wake it.
   Playback and Android media notification should continue.
3. Swipe Aurum away from Recent Apps while its native audio is **playing**;
   playback should remain. Test the notification's play/pause and skip.
   Swiping away is **not** the same as Settings → Force stop (which stops
   playback by Android design). If device battery policies kill the foreground
   service, collect redacted `adb logcat` and manufacturer battery setting.
4. Start an **EXACT matched** permitted stream in Crate. With connectivity
   enabled, repeat Home/lock/swipe checks. Confirm interruption/headphone
   unplug/audio focus behavior.
5. Start a **YouTube-only** entry. The iframe pauses when hidden. Confirm
   the UI offers YouTube Music, not false background audio.
6. Start a song that actually has a permitted matching audio source.
   Tap **Continue listening in background**. Confirm audio switches to
   Media3, shows its Android notification, and keeps playing across Home/lock.
7. Switch to Airplane Mode: offline files continue; online-only matches may
   stop, and should be reported accurately.
8. Recheck package signing, APK checksum, and the installation ID. Prefer
   the separate preview ID until owner-approved stable signing exists.

## Evidence

Capture exact branch/commit, APK SHA-256, signing-certificate fingerprint,
Android version, test audio source/rights basis, MediaSession playback state,
logcat excerpt, and pass/fail for Home, lock, swipe and Force stop distinction.
No simulated Takeout may be represented as the owner's real listening data.

## Definition of done

Actual device passes 2, 3, 4, 5, 6, 7 with independent Zane review and owner
acceptance. A green JVM or emulator CI test is a **build gate**, not the
final device acceptance.
