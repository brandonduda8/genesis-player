# Aurum Pulse 2.2: install, upgrade, rollback, verify (genesis-player issue #18)

The shipped code is **Aurum Pulse 2.2**, PR #15. **Nothing in the player is rebuilt for this.** This change only adds
signing, packaging, scripts and checks.

## Why an upgrade can fail today (blocker 1)

Each CI run signs its debug APK with the runner's own throwaway debug key. Two APKs from different runs can therefore
carry **different certificates**, and Android refuses to install one over the other (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
The `aurum-cert-audit` workflow proves this from the real artifacts: it downloads each one through GitHub's artifact API,
prints its SHA-256 and its signing certificate, and compares the file hash with the hash CI logged at build time.

## Fix: one stable key, held only by the owner (a one-time owner step)

1. On your own computer (needs a JDK): `tools/make_release_key.sh`. It prints the certificate fingerprint.
2. Add the 4 repository secrets it lists (GitHub → Settings → Secrets and variables → Actions). **The key never goes
   into git or chat.** Keep two private backups of the `.jks`; losing it means no more in-place upgrades.
3. From then on, every `aurum-apk` run also uploads **`aurum-pulse-2-release-apk`**, signed with your key. Its
   fingerprint is in `signing-report.txt`, and future upgrades install in place.

## Install paths

| Path | What happens to the installed Aurum | Data | When |
|---|---|---|---|
| **A. Side-by-side preview** (`aurum-pulse-2-preview-apk`, app "Aurum 2.2 Preview") | untouched: both apps live side by side | copy your vault in with `tools/vault_restore.sh` (it's debuggable), or import fresh | **now** |
| **B. Stable release** (`aurum-pulse-2-release-apk`) | must be uninstalled **once** (old key) | **back up first** (below); then re-import your Takeout in the release. Likes, skips and saved files are NOT carried over automatically. The backup `.tar` stays as your copy. | after the key exists |

## Back up before ANY uninstall (blocker 4)

```bash
tools/vault_backup.sh com.apexforge.genesisplayer aurum-vault.tar   # phone on USB, USB debugging on
```

- It copies `files/` (your import, feedback, crate, `offline/`, `owned/`) and `shared_prefs/` (ratings, EQ).
- It refuses to report success on an empty backup.
- Restore into the preview:
  ```bash
  tools/vault_restore.sh com.apexforge.genesisplayer.preview aurum-vault.tar
  ```

**Rollback:** reinstall the previous artifact from its CI run (artifacts are kept 90 days), with the same app id and
the same key. A debug-key APK can only replace one from the same run's key.

## Check the file you downloaded (blocker 3)

```bash
sha256sum app-*.apk
```

It must equal the hash in that run's `signing-report.txt` / `apk.sha256`. To check the certificate:

```bash
apksigner verify --print-certs app-*.apk
```

## Device smoke, by the verifier (blocker 2)

```bash
tools/device_smoke.sh com.apexforge.genesisplayer.preview path/to/app-preview.apk
```

It installs the APK, records the device, the version, the SHA-256 and the certificate, captures logcat, and toggles
airplane mode. Then it walks the verifier through these steps, saving screenshots and y/n answers:

1. a real Takeout import (the audit card shows the real play count);
2. official YouTube playback;
3. a real Audius exact stream in Aurum;
4. EQ / bass audible;
5. lock-screen and notification controls;
6. **airplane mode**: a sanctuary file plays; a YouTube-only song says "needs the internet" (no crash);
7. back online.

**Service vs player:** "embed blocked" (YouTube errors 101/150) and "couldn't be reached" (Audius/Archive down) are
**service limits**. The app shows them and moves on, and they're recorded as such. A crash, silence on a playing
Aurum track, or a frozen UI is a **player failure**.

## Certification (blocker 5)

- **Zane** certifies: the exact commit, the APK SHA-256 and certificate, the CI test results, and the device-smoke
  evidence folder.
- **Brandon** certifies the actual behaviour on his phone.
- **No automatic merge, no public release.**
