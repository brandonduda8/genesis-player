#!/usr/bin/env bash
# WO-AURUM-2.2 §7 device battery on the API-34 emulator. Every check appends PASS / FAIL / INFO to $OUT/results.txt;
# the workflow fails if any FAIL is present. Inputs: BASE_APK (2.1-deepspace debug), PULSE_APK (2.2-aurum debug, the
# pre-vault Aurum Pulse line that kept PLAINTEXT offline/owned files), NEW_APK (2.2 debug), OUT dir.
set -uo pipefail
PKG=com.apexforge.genesisplayer
ACT=$PKG/.MainActivity
OUT=${OUT:-/tmp/aurum22}
mkdir -p "$OUT"
R="$OUT/results.txt"; : > "$R"
here=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$here/../../.." && pwd)
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

python3 "$here/find_downloadable.py" "$OUT/streams.txt" > "$OUT/downloadable.txt" 2> "$OUT/download_permission.txt" || true
STREAMS=$(cat "$OUT/streams.txt" 2>/dev/null)
DL=$(head -1 "$OUT/downloadable.txt" | awk '{print $1}'); DLBASE=$(head -1 "$OUT/downloadable.txt" | awk '{print $2}')
vault() { adb shell am start -n $ACT -a $PKG.TEST_VAULT "$@" >/dev/null; }

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
[ "$VC" = "versionCode=6" ] && pass "$VC $VN" || fail "expected versionCode=6, got $VC $VN"
snapshot "$OUT/data_after_install.txt"
if diff -q "$OUT/data_before.txt" "$OUT/data_after_install.txt" >/dev/null; then
  pass "every 2.1 data file byte-identical after the update ($(wc -l < "$OUT/data_before.txt") files, sha256)"
else fail "data changed during install: $(diff "$OUT/data_before.txt" "$OUT/data_after_install.txt" | head -5)"; fi
adb shell am start -n $ACT >/dev/null; sleep 12
snapshot "$OUT/data_after_launch.txt"
missing=$(awk '{print $2}' "$OUT/data_after_launch.txt" | sort | comm -23 <(sort "$OUT/files_before.txt") -)
[ -z "$missing" ] && pass "after first 2.2 launch: no 2.1 file removed" || fail "files lost after 2.2 launch: $missing"
info "files rewritten by normal app activity after launch: $(diff <(sort "$OUT/data_before.txt") <(sort "$OUT/data_after_launch.txt") | grep -c '^>' )"

# ---------------------------------------------------------------- 1b. update over the pre-vault Aurum Pulse build
echo "=== 1b. 2.2-aurum (plaintext offline files) -> 2.2: verified in-place encryption ==="
adb uninstall $PKG >/dev/null 2>&1 || true
adb install "$PULSE_APK" >/dev/null && info "installed pre-vault Aurum: $(adb shell dumpsys package $PKG | grep -m1 -oE 'versionName=[^ ]+')"
adb shell am start -n $ACT >/dev/null; sleep 8; adb shell am force-stop $PKG
python3 - "$OUT/legacy.wav" << 'PYEOF'
import math, struct, sys
n = 44100 * 3
d = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 523 * i / 44100))) for i in range(n))
open(sys.argv[1], "wb").write(b"RIFF" + struct.pack("<I", 36 + len(d)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 44100, 88200, 2, 16) + b"data" + struct.pack("<I", len(d)) + d)
PYEOF
LSHA=$(sha256sum "$OUT/legacy.wav" | cut -d' ' -f1)
adb push "$OUT/legacy.wav" /data/local/tmp/legacy.wav >/dev/null; adb shell chmod 644 /data/local/tmp/legacy.wav
runas 'mkdir -p files/owned && cp /data/local/tmp/legacy.wav "files/owned/My Song.wav"'
adb shell rm /data/local/tmp/legacy.wav
runas 'ls -la files/owned' | sed 's/^/INFO  before: /' | tee -a "$R"
adb install -r "$NEW_APK" >/dev/null && pass "2.2 installed in place over the pre-vault Aurum build" || fail "install over pre-vault Aurum"
adb shell am start -n $ACT >/dev/null; sleep 15
if runas 'ls files/owned 2>/dev/null' | grep -q "My Song"; then fail "plaintext owned file still present after 2.2 launch"
else pass "plaintext files/owned/My Song.wav is gone after first 2.2 launch"; fi
vault --es mode dump; sleep 3
adb logcat -d -s GenesisPlayer:* AurumVault:* | grep -E "migrated|migration|VaultCI: dump" | tail -3 | sed 's/^/INFO  /' | tee -a "$R"
adb logcat -d -s AurumVault:* | grep -q "migrated My Song.wav into the encrypted vault (verified)" \
  && pass "owned file encrypted into the vault; deleted only after decrypt-and-SHA-256 verify (src $LSHA)" || fail "owned-file migration not proven"
MIG=$(adb logcat -d -s GenesisPlayer:* | grep "VaultCI: dump" | tail -1 | grep -oE "owned:legacy-[0-9a-f]+")
if [ -n "$MIG" ]; then
  vault --es mode play_entry --es id "$MIG"
  wait_playing 30 && pass "migrated file plays from the vault" || fail "migrated file did not play"
  adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
fi

# ---------------------------------------------------------------- 2. background + screen off
echo "=== 2. background + screen off ==="
adb logcat -c
[ -n "$STREAMS" ] || fail "no Audius stream URL in the catalog to test with"
vault --es mode play_url --es url "$STREAMS"
python3 "$here/probe.py" --raw >/dev/null
if wait_playing 60; then pass "network stream playing through Media3 ($(echo "$STREAMS" | tr '|' '\n' | wc -l) Audius URLs)"; else fail "stream never played"; log 30; echo "--- session block ---"; head -c 3000 "$OUT/session_raw.txt"; fi
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
# Bluetooth/headset buttons reach apps through MediaSessionService's media-key dispatch; `cmd media_session
# dispatch` drives exactly that path. `input keyevent` (the raw input path) is tried as a fallback and reported.
adb shell dumpsys media_session | grep -iE "media button session|Media button receiver|Sessions Stack" -A2 | head -12 > "$OUT/media_button_route.txt"
sed 's/^/INFO  route: /' "$OUT/media_button_route.txt" | head -6 | tee -a "$R"
key() { # $1 = dispatch name, $2 = KEYCODE; echoes the path that changed state
  local before; before=$(field "$(probe)" STATE)
  adb shell cmd media_session dispatch "$1" >/dev/null 2>&1; sleep 3
  [ "$(field "$(probe)" STATE)" != "$before" ] && { echo dispatch; return; }
  adb shell input keyevent "$2"; sleep 3
  [ "$(field "$(probe)" STATE)" != "$before" ] && { echo keyevent; return; }
  echo none
}
via=$(key pause KEYCODE_MEDIA_PAUSE); s=$(field "$(probe)" STATE)
[ "$s" = "2" ] && pass "media PAUSE -> paused (via $via)" || fail "media PAUSE: state=$s via=$via"
sleep 3
adb shell dumpsys power | grep -iE "PARTIAL_WAKE_LOCK" | grep -iE "genesis|ExoPlayer" > "$OUT/power_paused.txt"
if [ "$s" = "2" ]; then
  [ -s "$OUT/power_paused.txt" ] && fail "wake lock still held while paused: $(head -2 "$OUT/power_paused.txt")" || pass "no app wake lock while paused (battery-sane)"
fi
via=$(key play KEYCODE_MEDIA_PLAY); s=$(field "$(probe)" STATE)
[ "$s" = "3" ] && pass "media PLAY -> playing (via $via)" || fail "media PLAY: state=$s via=$via"
via=$(key headsethook KEYCODE_HEADSETHOOK); s=$(field "$(probe)" STATE)
[ "$s" = "2" ] && pass "HEADSETHOOK single press -> paused (via $via)" || fail "HEADSETHOOK: state=$s via=$via"
via=$(key play-pause KEYCODE_MEDIA_PLAY_PAUSE); s=$(field "$(probe)" STATE)
[ "$s" = "3" ] && pass "PLAY_PAUSE -> playing (via $via)" || fail "PLAY_PAUSE: state=$s via=$via"
t0=$(probe | sed 's/.*TITLE=//')
adb shell cmd media_session dispatch next >/dev/null 2>&1; sleep 8
t1=$(probe | sed 's/.*TITLE=//')
if [ "$t1" = "$t0" ]; then adb shell input keyevent KEYCODE_MEDIA_NEXT; sleep 8; t1=$(probe | sed 's/.*TITLE=//'); fi
[ -n "$t1" ] && [ "$t1" != "$t0" ] && pass "media NEXT -> next track ('$t0' -> '$t1')" || fail "media NEXT did not change track ('$t0' -> '$t1')"
adb logcat -d | grep -iE "MediaSessionService|MediaSessionStack|MediaButton|dispatchMediaKey|MediaSessionLegacyStub" | tail -15 > "$OUT/media_key_log.txt"
sed 's/^/INFO  keylog: /' "$OUT/media_key_log.txt" | cut -c1-220 | tee -a "$R"
adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent 82

# ---------------------------------------------------------------- 4. swipe away from Recents while playing
echo "=== 4. task removed while playing ==="
s=$(field "$(probe)" STATE); [ "$s" = "3" ] || { adb shell cmd media_session dispatch play >/dev/null 2>&1; sleep 3; }
PID0=$(adb shell pidof $PKG | tr -d '\r'); info "before removal: state=$(field "$(probe)" STATE) pid=$PID0"
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
  info "after removal: pid=$(adb shell pidof $PKG | tr -d '\r') (was $PID0); task=$TASK"
  adb logcat -d | grep -E "ActivityManager.*(genesisplayer|Killing|has died)|ActivityTaskManager.*genesisplayer" | tail -6 | sed 's/^/INFO  am: /' | tee -a "$R"
  [ "$s" = "3" ] && pass "task $TASK removed: playback continues in the foreground service" || fail "after task removal state=$s"
  adb logcat -d -s GenesisPlayer:* | grep -m1 "task removed" | sed 's/^/INFO  log: /' | tee -a "$R"
else info "could not remove the task from the shell on this image (task=$TASK); swipe-away is on Zane's device checklist"; fi
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2

# ---------------------------------------------------------------- 5. permitted download -> encrypted vault -> airplane mode
echo "=== 5. offline vault ==="
adb logcat -c
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode clear >/dev/null; sleep 4
cat "$OUT/download_permission.txt" | sed 's/^# /INFO  source: /' | tail -8 | tee -a "$R"
res=""
if [ -n "$DL" ]; then
  vault --es mode download_url --es id "offline:ci-$DL" --es url "$DLBASE/v1/tracks/$DL/download?app_name=aurum_player"
  for _ in $(seq 1 90); do adb logcat -d -s GenesisPlayer:* | grep -q "VaultCI: download offline:ci-$DL ->" && break; sleep 2; done
  res=$(adb logcat -d -s GenesisPlayer:* | grep -m1 "VaultCI: download offline:ci-$DL ->" | sed 's/.*-> //')
  [ "$res" = "stored" ] && pass "permitted Audius track $DL streamed straight into the encrypted vault (Offline Crate path)" || fail "download of permitted $DL: $res"
else
  info "no catalog track currently has downloads enabled by its artist: real download not exercisable today; the encrypted path is proven with the generated tone and the migrated file"
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
  adb exec-out run-as $PKG cat "files/vault/$f" > "$OUT/vf.bin"
  # Header must be AVLT; the body must be statistically random. Audio (even compressed) is far from uniform,
  # and a plaintext leak would put a container magic right after the 17-byte header. (Searching a 20 MB random
  # file for 3-byte strings like "ID3" finds them by pure chance, so that is NOT used as the test.)
  if ! python3 - "$OUT/vf.bin" << 'PYEOF'
import collections, math, sys
b = open(sys.argv[1], "rb").read()
assert b[:4] == b"AVLT", "no AVLT header"
body = b[17:]
assert not any(body.startswith(m) for m in (b"RIFF", b"ID3", b"fLaC", b"OggS", b"\x00\x00\x00\x20ftyp")), "container magic after header"
c = collections.Counter(body); n = len(body); e = n / 256
chi = sum((c.get(i, 0) - e) ** 2 / e for i in range(256))
# uniform bytes: chi-square ~ 255 +/- 23; generous bound 400. A WAV/MP3 gives tens of thousands or more.
print(f"{sys.argv[1]}: {n} bytes, chi-square vs uniform = {chi:.0f}")
assert chi < 400, f"not random-looking (chi-square {chi:.0f})"
PYEOF
  then bad=1; info "$f failed the ciphertext check"; fi
done
rm -f "$OUT/vf.bin"
[ "$n" -ge 1 ] && [ "$bad" = "0" ] && pass "$n vault file(s): opaque uuid names, AVLT header, bytes indistinguishable from random (chi-square)" || fail "ciphertext check: files=$n bad=$bad"
runas 'grep -l "Vault test tone" files/vault/index.bin' >/dev/null 2>&1 && fail "index.bin is not encrypted" || pass "index.bin holds no readable titles"
leak=$(runas 'find . -type f -path "*/owned/*" -o -type f -path "*/offline/*"' | tr -d '\r')
[ -z "$leak" ] && pass "no legacy plaintext offline/ or owned/ files remain" || fail "legacy plaintext remains: $leak"
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
  vault --es mode play_entry --es id "offline:ci-$DL"
  if wait_playing 40 && adb logcat -d -s GenesisPlayer:* | grep -q "vault: playing offline copy of offline:ci-$DL"; then
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
grep -q "evicted=\[vault-ci-fill-1\]" "$OUT/cap.txt" && grep -E "VaultCI: dump" "$OUT/cap.txt" | tail -1 | grep -qE "ids=\[vault-ci-fill-3, vault-ci-fill-2[],]" \
  && pass "12 MB cap: the oldest download (fill-1) was evicted first; newest two kept; eviction recorded for the UI" \
  || fail "eviction order wrong (see cap.txt)"
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode cap --el bytes 2147483648 >/dev/null
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode clear >/dev/null; sleep 3
adb shell am start -n $ACT -a $PKG.TEST_VAULT --es mode dump >/dev/null; sleep 3
adb logcat -d -s GenesisPlayer:* | grep "VaultCI: dump" | tail -1 | grep -qE "cap=2147483648 ids=\[(owned:legacy-[0-9a-f]+)?\]" \
  && pass "clear-all removes every download (your own imported file stays); cap back to the 2 GB default" || fail "clear-all/default cap (see log)"

# ---------------------------------------------------------------- 7. move a library from the Preview, nothing uninstalled
echo "=== 7. Preview -> 2.2 transfer (tools/transfer_from_preview.sh) ==="
if [ -n "${PREVIEW_APK:-}" ] && [ -f "$PREVIEW_APK" ]; then
  PRE=$PKG.preview
  adb install -r "$PREVIEW_APK" >/dev/null && info "installed the Aurum 2.2 Preview ($PRE) next to 2.2"
  adb push "$ROOT/.github/fixtures/synthetic-watch-history.json" /data/local/tmp/watch-history.json >/dev/null
  adb shell "cat /data/local/tmp/watch-history.json | run-as $PRE sh -c 'mkdir -p files && cat > files/watch-history.json'"
  adb logcat -c
  adb shell am start -S -n $PRE/$PKG.MainActivity -a $PKG.TEST_IMPORT --es path /data/data/$PRE/files/watch-history.json >/dev/null
  for _ in $(seq 1 40); do adb logcat -d -s GenesisPlayer:* | grep -q "TEST_IMPORT" && break; sleep 1; done
  adb logcat -d -s GenesisPlayer:* | grep -m1 TEST_IMPORT | sed 's/^/INFO  preview: /' | tee -a "$R"
  sleep 3; adb shell am force-stop $PRE
  PRE_SHA=$(adb exec-out run-as $PRE cat files/aurum_my_music.json | sha256sum | cut -d' ' -f1)
  if bash "$ROOT/tools/transfer_from_preview.sh" > "$OUT/transfer.txt" 2>&1; then
    pass "transfer script: $(grep -m1 Wrote "$OUT/transfer.txt")"
    adb shell cat /sdcard/Download/aurum-transfer.json > "$OUT/transfer.json"
    LC_ALL=C grep -aqE 'RIFF....WAVE|ID3|fLaC|OggS|ftypM4A' "$OUT/transfer.json" && fail "transfer file carries audio" || pass "transfer file is listening data only (no audio signature)"
    rm -f "$OUT/transfer.json" # personal-data shaped; not kept as CI evidence
    adb shell "cat /sdcard/Download/aurum-transfer.json | run-as $PKG sh -c 'cat > files/aurum-transfer.json'"
    adb shell rm /sdcard/Download/aurum-transfer.json
    adb logcat -c
    adb shell am start -S -n $ACT -a $PKG.TEST_IMPORT --es path /data/data/$PKG/files/aurum-transfer.json >/dev/null
    for _ in $(seq 1 40); do adb logcat -d -s GenesisPlayer:* | grep -q "TEST_IMPORT" && break; sleep 1; done
    imp=$(adb logcat -d -s GenesisPlayer:* | grep -m1 TEST_IMPORT)
    echo "$imp" | grep -q "TEST_IMPORT ok plays=240" && pass "2.2 restored the Preview's library from the transfer file (240 plays)" || fail "restore: $imp"
    runas 'rm -f files/aurum-transfer.json'
  else fail "transfer script: $(tail -3 "$OUT/transfer.txt")"; fi
  adb shell pm list packages | grep -q "^package:$PRE$" && [ "$(adb exec-out run-as $PRE cat files/aurum_my_music.json | sha256sum | cut -d' ' -f1)" = "$PRE_SHA" ] \
    && pass "the Preview is still installed and its library is byte-identical" || fail "the Preview or its data changed"
else info "no PREVIEW_APK given: transfer test skipped"; fi

adb logcat -d > "$OUT/logcat.txt"
grep -i "FATAL EXCEPTION" -A8 "$OUT/logcat.txt" | grep -q "$PKG" && fail "a crash was logged" || pass "no crash during the battery"
echo; echo "===== RESULTS ====="; cat "$R"
! grep -q '^FAIL' "$R"
