# Aurum 2.2: handoff to Zane, then Brandon

Work order: WO-AURUM-2.2-BACKGROUND-OFFLINE-2026-11-01
Branch: `claude/aurum-2.2` (built on `claude/aurum-pulse-2`, PR #15)
Status on delivery: **BUILT**. Zane still has to certify it, and Brandon still has to install it and give a verdict.

## 1. Corrections to the work order (read first)

| WO assumption | What's actually true (evidence) | What I did |
|---|---|---|
| Brandon runs **2.1-deepspace** (versionCode 4, AURUM key) | Brandon confirmed on 2026-11-01 that his phone runs **"Aurum 2.2 Preview"**: package `com.apexforge.genesisplayer.preview`, the Aurum Pulse line (PR #15), from CI run 41347750404. | Built 2.2 on the Aurum Pulse line, the line he uses. Building on deepspace would have removed his Takeout library, Apollo and the Crate. |
| 2.2 can update the installed app in place | The Preview is signed by that CI run's **throwaway debug key** (cert SHA-256 `d488db66…43d2d8`). The key no longer exists: debug keys are generated fresh on every run (cert audit run 41347750440 found 3 builds with 3 certs). Android refuses any update with a different signer. | 2.2 ships as the **main** package `com.apexforge.genesisplayer`, versionCode 6, for the AURUM key. It installs **next to** the Preview: nothing is uninstalled, nothing is wiped. The library moves over by a transfer file (§4). |
| "No foreground service / no media session" | False for both lines. Both already run a `MediaSessionService` (notification, lock screen, audio focus, becoming-noisy). They lacked a **wake mode**, so screen-off streams starved, and an explicit **task-removed** policy. | Added `setWakeMode(C.WAKE_MODE_NETWORK)` on both players, `+WAKE_LOCK`, and `onTaskRemoved`. |
| Offline already exists | The Preview saves offline tracks as **plaintext** `files/offline/<song>.mp3` and copies imported files in plaintext to `files/owned/<name>`. Both violate WO §4. | Both now go through the encrypted vault. Leftover plaintext from any earlier build in this package is encrypted, verified by decrypt + SHA-256, and only then deleted. |
| CI can sign with the AURUM key | The repo has **no AURUM signing secrets**: the release artifact of run 41347750404 is 408 bytes (empty). | CI uploads an **unsigned** release APK. Zane signs it (§3). The workflow would also sign automatically and refuse any other cert if the secrets are ever added. |
| — | `tools/make_release_key.sh` exists on PR #15. | **Do not run it.** The AURUM key already exists; a new key would break every future update. |

## 2. What's built

- **Background (§3):** wake mode on both players (CPU and Wi-Fi locks, held only while playing). `onTaskRemoved` keeps playing music playing and stops when idle. Media buttons, notification and lock-screen controls come from the existing MediaSession. Audio focus is `setAudioAttributes(…, true)`: pause on permanent loss and calls, duck on may-duck.
- **Vault (§4):**
  - **Encryption:** chunked AES-256-GCM in 64 KiB chunks. Each file has a random nonce prefix; each chunk's nonce is that prefix plus a counter, so no nonce repeats. The chunk index and a last-chunk flag go into the AAD, so tampering and truncation are detected.
  - **Key:** a 32-byte data key, wrapped by the non-exportable Android Keystore key `aurum_vault_kek`.
  - **No plaintext:** playback decrypts in memory through a Media3 `aurumvault://` DataSource. Crate downloads stream from the network straight into the encryptor; "Add music files" encrypts straight from the picker.
  - **Storage:** files have opaque uuid names; the index (`files/vault/index.bin`) is encrypted.
  - **Cap:** 2 GiB default, choosable 512 MB to 8 GB. It applies to downloads, oldest first, and evictions are shown. Files you imported yourself are never auto-evicted.
  - **Controls:** per-track remove, and "Clear all downloads".
  - **Sources:** only those the rights holder allows (Audius with artist downloads enabled; Archive with a CC/PD licence, as in the existing Crate rules).
- **Version:** `versionCode 6`, `versionName 2.2`, package `com.apexforge.genesisplayer`. No existing storage format changed; the vault is new files only.

## 3. Signing (Zane)

```bash
zipalign -p -f 4 aurum-2.2-release-unsigned.apk aligned.apk   # CI already aligned it; harmless
apksigner sign --ks <AURUM keystore> --ks-key-alias <alias> --out aurum-2.2.apk aligned.apk
apksigner verify --print-certs aurum-2.2.apk | grep "SHA-256"
```

The SHA-256 must equal
`2A:44:37:02:DF:C0:78:6F:47:E0:A1:81:57:CD:F0:EE:4A:1A:A7:05:63:30:F3:E5:ED:84:BE:0F:B3:A0:CD:5B`.
Anything else fails certification (WO §6).

Check `aapt2 dump badging aurum-2.2.apk`: `package: name='com.apexforge.genesisplayer' versionCode='6' versionName='2.2'`.

## 4. Brandon's install (no uninstall, no data loss)

1. Install `aurum-2.2.apk` (the AURUM-signed one). It appears as a separate app next to "Aurum 2.2 Preview".
   If 2.1-deepspace is still installed, 2.2 updates it in place: same signer, 4 → 6, and every 2.1 file is kept (CI §1 checks this).
2. Move the library. Pick one:
   - **With a computer (keeps your thumbs and Crate matches):** plug the phone in and run `tools/transfer_from_preview.sh`. It writes `Download/aurum-transfer.json`, which holds listening data only, no audio. In 2.2: My Music → Import → pick that file. Then delete it.
   - **Phone only:** in 2.2, re-import the same YouTube Takeout zip. This rebuilds the same library and Apollo; thumbs given inside the Preview do not carry over.
3. In 2.2's Crate, re-save "Can save" tracks (they are permitted downloads), and re-add your own music files. Both are now encrypted.
4. Once 2.2 is good, uninstall the Preview yourself. Uninstalling removes the Preview's plaintext offline files.

## 5. Device checks CI cannot do (Zane's checklist)

- [ ] A real phone call while playing: pauses, stays paused during the call.
- [ ] A Bluetooth headset or car: play/pause/next buttons, and pause on disconnect.
- [ ] Swipe Aurum away from Recents while playing: music continues and the notification stays. (CI tries this from the shell where the emulator image allows it.)
- [ ] Lock-screen controls on his phone model.
- [ ] 30+ minutes screen-off on battery, then check the battery stats entry for Aurum.
- [ ] Airplane mode: an offline track plays.
- [ ] The vault manager shows the 2 GB default cap and lists downloads.

## 6. CI evidence (run 41372091238, commit dd3dacf, conclusion: success)

https://github.com/brandonduda8/genesis-player/actions/runs/41372091238 . Artifacts: `aurum-2.2-release` (unsigned
release + SHA256SUMS), `aurum-2.2-evidence` (results.txt, build-log.txt, permission diffs, logcat).

- Release (unsigned, for AURUM signing): `aurum-2.2-release-unsigned.apk`
  sha256 `44f8b2378a089f3e7c500f856c934f332fae7cf63aefab71d7211f33a2181475`,
  `package: name='com.apexforge.genesisplayer' versionCode='6' versionName='2.2'`. After signing, the sha256 changes; record the new one.
- Host: GitHub Actions ubuntu-latest (free, $0). Not zane-box. Offline cap default: 2 GiB (2147483648).
- Permission delta vs the Aurum Pulse build: **+WAKE_LOCK only**.
- JVM unit tests: pass (including 11 vault crypto/index tests). Instrumented (API 34): 5/5 pass: Keystore key
  non-exportable, ciphertext on disk + ExoPlayer playback, cap evicts oldest, too-big refused, audio focus
  (duck keeps playing / call pauses and stays paused / permanent loss stays paused).
- Device battery: 32 PASS, 0 FAIL:
  update over 2.1 in place, versionCode 6, all 6 data files byte-identical; update over the pre-vault Aurum build
  with the plaintext file encrypted, decrypt+SHA-256 verified, then deleted, and it plays; 3 Audius streams play,
  **118 s of 120 s advanced with the screen off and app backgrounded**; partial wake lock while playing, none
  while paused; notification posted; PAUSE / PLAY / HEADSETHOOK / PLAY_PAUSE / NEXT through MediaSessionService
  dispatch (the Bluetooth/headset path); task removed while playing keeps playing (same process); vault files
  random (chi-square), index unreadable, no plaintext or .part anywhere, nothing in shared storage; airplane mode
  plays from the vault; 12 MB cap evicts the oldest first; clear-all keeps imported files; the Preview's library
  restored into 2.2 (240 plays) with the Preview byte-identical; no crash.
- Not exercised: a real permitted Audius download. Today none of the 3 Audius tracks in the catalog has
  downloads enabled by its artist, so it was correctly refused as "streaming only". The download path itself is
  the same encryptor proven above.
- CI can't simulate this, so Zane must check it on a phone: raw `input keyevent` media keys were ignored with the screen off on the emulator image;
  the dispatch path (what real headsets use) works. Confirm on a real Bluetooth headset (§5).
