#!/usr/bin/env bash
# Device smoke for issue #18 blocker 2 (run by the verifier with the phone on USB). Automates what adb can and
# records evidence; the human steps are printed and confirmed y/n. Writes smoke-<timestamp>/ with logcat + screenshots.
set -uo pipefail
PKG="${1:-com.apexforge.genesisplayer.preview}"
APK="${2:-}"
D="smoke-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$D"
say() { echo; echo "== $*"; }
shot() { adb exec-out screencap -p > "$D/$1.png"; }
ask() { read -r -p "  $1 [y/n] " a; echo "$1 : $a" >> "$D/answers.txt"; }
if [ -n "$APK" ]; then
  say "APK identity"; sha256sum "$APK" | tee "$D/apk.sha256"
  AS=$(command -v apksigner || echo "$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/* 2>/dev/null | sort -V | tail -1)/apksigner")
  "$AS" verify --print-certs "$APK" 2>/dev/null | grep -E "DN|SHA-256" | tee "$D/cert.txt" || echo "apksigner not found (skip)"
  adb install -r "$APK" | tee "$D/install.txt"
fi
adb shell getprop ro.build.version.release | sed 's/^/Android /' | tee "$D/device.txt"
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | tee -a "$D/device.txt"
adb logcat -c; (adb logcat -v time GenesisPlayer:V AurumMyMusic:V AurumCrate:V AndroidRuntime:E ExoPlayerImpl:W '*:S' > "$D/logcat.txt" &) ; sleep 1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; shot 01-launch
say "1. Import: pick your REAL Takeout zip in the app (Pick my YouTube Takeout)."; ask "Audit card shows your real play count and date range"; shot 02-audit
say "2. YouTube: Apollo, then Play For You."; ask "Official YouTube player shows video AND you hear it"; shot 03-youtube
ask "If a song showed 'Skipped: owner doesn't allow this video to play inside apps', that is a SERVICE limit (embed blocked), not a player failure; it moved to the next song"
say "3. Audius: Crate, then Match my library (wait), then Exact · online, then tap a song."; ask "An exact match plays in Aurum (sound, not YouTube)"; shot 04-audius
ask "If the Crate showed 'couldn't be reached', that is a SERVICE/network issue, not a player failure"
say "4. Sound Forge: move the bass/EQ while the Aurum track plays."; ask "You hear the EQ change"
say "5. Lock screen: lock the phone while it plays."; ask "Lock screen and notification show the track with working play/pause/next"; shot 05-lock
say "6. Offline: Crate, then Sanctuary, then Add music files (a file you own) OR save one CAN SAVE track."
adb shell cmd connectivity airplane-mode enable 2>/dev/null || echo "  (turn on airplane mode by hand)"
sleep 3; ask "Crate shows OFFLINE, and the sanctuary file plays with airplane mode ON"; shot 06-offline
ask "Tapping a YouTube-only song offline shows a 'needs the internet' message (no crash)"
adb shell cmd connectivity airplane-mode disable 2>/dev/null || echo "  (turn airplane mode off by hand)"
sleep 5; ask "Back online: Crate shows ONLINE, and YouTube plays again"; shot 07-online
pkill -f "adb logcat -v time" 2>/dev/null
grep -E "FATAL EXCEPTION|AndroidRuntime" "$D/logcat.txt" > "$D/crashes.txt" && echo "CRASHES FOUND, see $D/crashes.txt" || echo "No crashes in logcat."
echo; echo "Evidence in $D/ : answers.txt, logcat.txt, screenshots. Attach it to genesis-player issue #18."
