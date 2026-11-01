#!/usr/bin/env bash
# WO-AURUM-2.2 §7 device battery on the API-34 emulator. Every check appends PASS / FAIL / INFO to $OUT/results.txt;
# the workflow fails if any FAIL is present. Inputs: BASE_APK (2.1-deepspace debug), NEW_APK (2.2 debug), OUT dir.
set -uo pipefail
PKG=com.apexforge.genesisplayer
ACT=$PKG/.MainActivity
OUT=${OUT:-/tmp/aurum22}
mkdir -p "$OUT"
R="$OUT/results.txt"; : > "$R"
here=$(cd "$(dirname "$0")" && pwd)
pass() { echo "PASS  $*" | tee -a "$R"; }
fail() { echo "FAIL  $*" | tee -a "$R"; }
info() { echo "INFO  $*" | tee -a "$R"; }
probe() { python3 "$here/probe.py"; }
field() { echo "$1" | grep -oE "$2=[0-9]*" | cut -d= -f2; }
runas() { adb shell run-as $PKG sh -c "'$1'"; }
log() { adb logcat -d -s GenesisPlayer:* AurumVault:* | tail -n "${1:-40}"; }
wait_playing() { # $1 = seconds; succeeds once position advances while state=3
  local prev="" r s p
  for _ in $(seq 1 "$1"); do
    r=$(probe); s=$(field "$r" STATE); p=$(field "$r" POS)
    if [ "$s" = "3" ] && [ -n "$p" ] && [ -n "$prev" ] && [ "$p" -gt "$prev" ]; then return 0; fi
    prev=$p; sleep 1
  done
  return 1
}
snapshot() { runas 'cd /data/data/com.apexforge.genesisplayer && find . -type f ! -path "./cache/*" ! -path "./code_cache/*" | sort | xargs sha256sum' > "$1" 2>/dev/null; }

adb logcat -c
# ---------------------------------------------------------------- 1. clean update over 2.1
echo "=== 1. install 2.1-deepspace, create real app data ==="
adb uninstall $PKG >/dev/null 2>&1 || true
adb install "$BASE_APK" >/dev/null && info "installed 2.1 base: $(adb shell dumpsys package $PKG | grep -m1 -oE 'versionName=[^ ]+')"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell am start -n $ACT -a $PKG.TEST_PLAY --ei index 0 >/dev/null
sleep 25
adb shell am start -n $ACT -a $PKG.TEST_PLAY --ei index 1 >/dev/null
sleep 15
adb shell am force-stop $PKG; sleep 2
snapshot "$OUT/data_before.txt"
info "2.1 data files: $(wc -l < "$OUT/data_before.txt")"
cat "$OUT/data_before.txt" | awk '{print $2}' > "$OUT/files_before.txt"
echo "=== install 2.2 OVER it (adb install -r, same package, same signer) ==="
if adb install -r "$NEW_APK" > "$OUT/upgrade_install.txt" 2>&1; then pass "2.2 installs over 2.1 in place (no uninstall)"; else fail "upgrade install: $(cat "$OUT/upgrade_install.txt")"; fi
VC=$(adb shell dumpsys package $PKG | grep -m1 -oE 'versionCode=[0-9]+'); VN=$(adb shell dumpsys package $PKG | grep -m1 -oE 'versionName=[^ ]+')
[ "$VC" = "versionCode=5" ] && pass "$VC $VN" || fail "expected versionCode=5, got $VC $VN"
snapshot "$OUT/data_after_install.txt"
if diff -q "$OUT/data_before.txt" "$OUT/data_after_install.txt" >/dev/null; then
  pass "every 2.1 data file byte-identical after the update ($(wc -l < "$OUT/data_before.txt") files, sha256)"
else fail "data changed during install: $(diff "$OUT/data_before.txt" "$OUT/data_after_install.txt" | head -5)"; fi
adb shell am start -n $ACT >/dev/null; sleep 12
snapshot "$OUT/data_after_launch.txt"
missing=$(awk '{print $2}' "$OUT/data_after_launch.txt" | sort | comm -23 <(sort "$OUT/files_before.txt") -)
[ -z "$missing" ] && pass "after first 2.2 launch: no 2.1 file removed" || fail "files lost after 2.2 launch: $missing"
info "files rewritten by normal app activity after launch: $(diff <(sort "$OUT/data_before.txt") <(sort "$OUT/data_after_launch.txt") | grep -c '^>' )"

# ---------------------------------------------------------------- 2. background + screen off
echo "=== 2. background + screen off ==="
adb logcat -c
adb shell am start -n $ACT -a $PKG.TEST_PLAY --ei index 0 >/dev/null
if wait_playing 60; then pass "streaming playback started"; else fail "stream never played"; log 30; fi
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell input keyevent KEYCODE_SLEEP; sleep 3
adb shell dumpsys power | grep -iE "PARTIAL_WAKE_LOCK.*(genesis|ExoPlayer|AudioMix)|mWakefulness" > "$OUT/power_playing.txt"
r0=$(probe); p0=$(field "$r0" POS)
info "screen off; sleeping 120 s. start: $r0"
sleep 120
r1=$(probe); p1=$(field "$r1" POS); s1=$(field "$r1" STATE)
info "after 120 s: $r1"
if [ "$s1" = "3" ] && [ -n "$p0" ] && [ -n "$p1" ] && [ $((p1 - p0)) -ge 100000 ]; then
  pass "backgrounded + screen off for 120 s: still playing, position advanced $(( (p1 - p0) / 1000 )) s"
else fail "background/screen-off playback stopped or stalled (p0=$p0 p1=$p1 state=$s1)"; fi
grep -qi "Asleep\|Dozing" "$OUT/power_playing.txt" && info "device wakefulness while playing: $(grep -i mWakefulness "$OUT/power_playing.txt" | head -1 | xargs)"
grep -qiE "PARTIAL_WAKE_LOCK" "$OUT/power_playing.txt" && pass "partial wake lock held while playing" || info "no partial wake lock line matched (see power_playing.txt)"
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" | head -20 > "$OUT/notification.txt"
grep -q "pkg=$PKG" "$OUT/notification.txt" && pass "media notification posted (lock-screen + shade controls)" || fail "no notification for $PKG"

# ---------------------------------------------------------------- 3. hardware / headset media keys (screen still off)
echo "=== 3. media keys ==="
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 3
s=$(field "$(probe)" STATE); [ "$s" = "2" ] && pass "KEYCODE_MEDIA_PAUSE -> paused" || fail "MEDIA_PAUSE gave state=$s"
sleep 3
adb shell dumpsys power | grep -iE "PARTIAL_WAKE_LOCK" | grep -iE "genesis|ExoPlayer" > "$OUT/power_paused.txt"
[ -s "$OUT/power_paused.txt" ] && fail "wake lock still held while paused: $(head -2 "$OUT/power_paused.txt")" || pass "no app wake lock while paused (battery-sane)"
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 3
s=$(field "$(probe)" STATE); [ "$s" = "3" ] && pass "KEYCODE_MEDIA_PLAY -> playing" || fail "MEDIA_PLAY gave state=$s"
adb shell input keyevent KEYCODE_HEADSETHOOK; sleep 3
s=$(field "$(probe)" STATE); [ "$s" = "2" ] && pass "KEYCODE_HEADSETHOOK (single press) -> toggles to paused" || fail "HEADSETHOOK gave state=$s"
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE; sleep 3
s=$(field "$(probe)" STATE); [ "$s" = "3" ] && pass "KEYCODE_MEDIA_PLAY_PAUSE -> playing" || fail "PLAY_PAUSE gave state=$s"
t0=$(probe | sed 's/.*TITLE=//')
adb shell input keyevent KEYCODE_MEDIA_NEXT; sleep 8
t1=$(probe | sed 's/.*TITLE=//')
[ -n "$t1" ] && [ "$t1" != "$t0" ] && pass "KEYCODE_MEDIA_NEXT -> next track ('$t0' -> '$t1')" || fail "MEDIA_NEXT did not change track ('$t0' -> '$t1')"
adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent 82

# ---------------------------------------------------------------- 4. swipe away from Recents while playing
echo "=== 4. task removed while playing ==="
TASK=$(adb shell am stack list 2>/dev/null | grep -m1 "$PKG" | grep -oE 'taskId=[0-9]+' | cut -d= -f2)
[ -z "$TASK" ] && TASK=$(adb shell dumpsys activity recents | grep -m1 -B2 "$PKG" | grep -oE '#[0-9]+' | head -1 | tr -d '#')
removed=0
if [ -n "$TASK" ]; then
  adb shell am stack remove "$TASK" 2>/dev/null | grep -qi "error\|unknown" || removed=1
  [ "$removed" = 1 ] || { adb shell cmd activity remove-task "$TASK" 2>&1 | grep -qi "error\|unknown" || removed=1; }
fi
if [ "$removed" = 1 ]; then
  sleep 6
  s=$(field "$(probe)" STATE)
  [ "$s" = "3" ] && pass "task $TASK removed: playback continues in the foreground service" || fail "after task removal state=$s"
  adb logcat -d -s GenesisPlayer:* | grep -m1 "task removed" | sed 's/^/INFO  log: /' | tee -a "$R"
else info "could not remove the task from the shell on this image (task=$TASK); swipe-away is on Zane's device checklist"; fi
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2

# ---------------------------------------------------------------- 5. permitted download -> encrypted vault -> airplane mode
echo "=== 5. offline vault ==="
adb logcat -c
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode clear >/dev/null; sleep 4
DL=$(python3 "$here/find_downloadable.py" 2> "$OUT/download_permission.txt" | head -1)
cat "$OUT/download_permission.txt" | sed 's/^# /INFO  source: /' | tail -8 | tee -a "$R"
if [ -n "$DL" ]; then
  adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode download --es id "$DL" >/dev/null
  for _ in $(seq 1 90); do adb logcat -d -s GenesisPlayer:* | grep -q "VaultCI: download $DL ->" && break; sleep 2; done
  res=$(adb logcat -d -s GenesisPlayer:* | grep -m1 "VaultCI: download $DL ->" | sed 's/.*-> //')
  [ "$res" = "stored" ] && pass "permitted Audius track $DL downloaded straight into the encrypted vault" || fail "download of permitted $DL: $res"
else
  info "no catalog track currently has downloads enabled by its artist: real-track download not exercisable; the encrypted path is proven with the generated tone below"
fi
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode seed_play --ei seconds 240 >/dev/null
for _ in $(seq 1 30); do adb logcat -d -s GenesisPlayer:* | grep -q "VaultCI: seeded+playing" && break; sleep 1; done
adb logcat -d -s GenesisPlayer:* | grep -m1 "VaultCI: seeded" | sed 's/^/INFO  /' | tee -a "$R"
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2

echo "--- ciphertext inspection (run-as, debuggable build only) ---"
runas 'ls -la files/vault' > "$OUT/vault_ls.txt"; cat "$OUT/vault_ls.txt"
FILES=$(runas 'ls files/vault' | tr -d '\r' | grep -E '^[0-9a-f-]{36}$')
n=0; bad=0
for f in $FILES; do
  n=$((n+1))
  runas "head -c 4 files/vault/$f" | grep -q AVLT || { bad=1; info "$f lacks AVLT header"; }
  if runas "cat files/vault/$f" | LC_ALL=C grep -aqE 'RIFF....WAVE|ID3|fLaC|OggS|ftypM4A'; then bad=1; info "$f contains an audio signature"; fi
done
[ "$n" -ge 1 ] && [ "$bad" = "0" ] && pass "$n vault file(s): opaque uuid names, AVLT header, no RIFF/ID3/fLaC/OggS/M4A signature anywhere" || fail "ciphertext check: files=$n bad=$bad"
runas 'grep -l "Vault test tone" files/vault/index.bin' >/dev/null 2>&1 && fail "index.bin is not encrypted" || pass "index.bin holds no readable titles"
leak=$(runas 'find . -type f \( -name "*.mp3" -o -name "*.wav" -o -name "*.flac" -o -name "*.m4a" -o -name "*.ogg" -o -name "*.part" \)' | tr -d '\r')
[ -z "$leak" ] && pass "no plaintext audio or .part file anywhere in the app sandbox" || fail "plaintext candidates in sandbox: $leak"
ext=$(adb shell "find /sdcard /storage/emulated/0 -newer /data/local/tmp -type f 2>/dev/null | grep -iE 'aurum|genesis|vault|\.(mp3|wav|flac|m4a|ogg)$'" | tr -d '\r')
[ -z "$ext" ] && pass "nothing written to shared storage" || fail "files in shared storage: $ext"

echo "--- airplane mode ---"
adb shell cmd connectivity airplane-mode enable; sleep 5
adb shell svc wifi disable; adb shell svc data disable; sleep 3
net=$(adb shell "ping -c1 -W2 8.8.8.8 >/dev/null 2>&1 && echo up || echo down" | tr -d '\r')
info "network with airplane mode on: $net"
adb logcat -c
if [ -n "$DL" ] && [ "${res:-}" = "stored" ]; then
  adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode play --es id "$DL" >/dev/null
  if wait_playing 40 && adb logcat -d -s GenesisPlayer:* | grep -q "vault: playing offline copy of $DL"; then
    pass "airplane mode ($net): the downloaded catalog track $DL plays from its encrypted copy"
  else fail "downloaded track $DL did not play offline"; log 30; fi
  adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
fi
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode seed_play --ei seconds 240 >/dev/null
if wait_playing 40; then pass "airplane mode ($net): vault tone plays, position advancing (decrypted in memory)"; else fail "vault playback in airplane mode"; log 30; fi
adb shell input keyevent KEYCODE_MEDIA_PAUSE
adb shell cmd connectivity airplane-mode disable; adb shell svc wifi enable; adb shell svc data enable; sleep 5

# ---------------------------------------------------------------- 6. cap: oldest evicted first, visible
echo "=== 6. cap + eviction ==="
adb logcat -c
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode clear >/dev/null; sleep 3
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode cap --el bytes 12000000 >/dev/null; sleep 2  # ~12 MB
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode fill --ei count 3 --ei seconds 60 >/dev/null # 3 x ~5.3 MB
for _ in $(seq 1 40); do adb logcat -d -s GenesisPlayer:* | grep -q "VaultCI: fill 3" && break; sleep 1; done
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode dump >/dev/null; sleep 3
adb logcat -d -s GenesisPlayer:* AurumVault:* | grep -E "VaultCI: (fill|dump)|evicted" | tee "$OUT/cap.txt"
grep -q "evicted=\[vault-ci-fill-1\]" "$OUT/cap.txt" && grep -E "VaultCI: dump" "$OUT/cap.txt" | tail -1 | grep -q "ids=\[vault-ci-fill-3, vault-ci-fill-2\]" \
  && pass "12 MB cap: the oldest download (fill-1) was evicted first; newest two kept; eviction recorded for the UI" \
  || fail "eviction order wrong (see cap.txt)"
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode cap --el bytes 2147483648 >/dev/null
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode clear >/dev/null; sleep 3
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode dump >/dev/null; sleep 3
adb logcat -d -s GenesisPlayer:* | grep "VaultCI: dump" | tail -1 | grep -q "count=0 used=0 cap=2147483648" \
  && pass "clear-all empties the vault; cap restored to the 2 GB default" || fail "clear-all/default cap (see log)"

adb logcat -d > "$OUT/logcat.txt"
grep -i "FATAL EXCEPTION" -A8 "$OUT/logcat.txt" | grep -q "$PKG" && fail "a crash was logged" || pass "no crash during the battery"
echo; echo "===== RESULTS ====="; cat "$R"
! grep -q '^FAIL' "$R"
