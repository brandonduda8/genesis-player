#!/usr/bin/env bash
# Move your library from "Aurum 2.2 Preview" into Aurum 2.2 WITHOUT uninstalling anything.
#
# The Preview (com.apexforge.genesisplayer.preview) was signed by a throwaway CI key, so no build can update it in
# place. It is a debuggable build, so its own files can be read over USB. This copies ONLY listening data:
#   aurum_my_music.json (your imported Takeout plays/playlists), aurum_feedback.json (thumbs), aurum_crate.json
# into ONE file, /sdcard/Download/aurum-transfer.json. No audio is copied.
#
# Then, in Aurum 2.2: My Music -> Import -> pick aurum-transfer.json. Delete the file afterwards (this script
# prints the command). Saved offline tracks are re-saved from "Can save" (they were permitted downloads); files you
# imported yourself, add again from your phone with "Add music files".
set -euo pipefail
SRC=com.apexforge.genesisplayer.preview
adb get-state >/dev/null
adb shell run-as "$SRC" true 2>/dev/null || { echo "run-as refused: $SRC is not installed or not debuggable. Nothing copied."; exit 2; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for f in aurum_my_music.json aurum_feedback.json aurum_crate.json; do
  if adb shell run-as "$SRC" test -f "files/$f" 2>/dev/null; then adb exec-out run-as "$SRC" cat "files/$f" > "$tmp/$f"
  else : > "$tmp/$f"; fi
done
[ -s "$tmp/aurum_my_music.json" ] || { echo "The Preview has no imported library (files/aurum_my_music.json missing). Nothing to move."; exit 3; }
python3 - "$tmp" << 'PY'
import json, os, sys
d = sys.argv[1]
read = lambda n: open(os.path.join(d, n), encoding="utf-8").read()
def part(n, required=False):
    t = read(n).strip()
    if not t:
        if required: sys.exit(f"{n} is empty")
        return ""
    json.loads(t)  # every part must be valid JSON, or nothing is written
    return t
out = {"aurum_transfer": 1, "my_music": part("aurum_my_music.json", True),
       "feedback": part("aurum_feedback.json"), "crate": part("aurum_crate.json")}
json.dump(out, open(os.path.join(d, "aurum-transfer.json"), "w"))
PY
adb push "$tmp/aurum-transfer.json" /sdcard/Download/aurum-transfer.json >/dev/null
echo "Wrote /sdcard/Download/aurum-transfer.json ($(wc -c < "$tmp/aurum-transfer.json") bytes, listening data only)."
echo "In Aurum 2.2: My Music -> Import -> pick aurum-transfer.json. Then remove it:"
echo "  adb shell rm /sdcard/Download/aurum-transfer.json"
