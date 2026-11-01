#!/usr/bin/env bash
# Back up an installed Aurum's private vault over USB BEFORE any uninstall (issue #18, blocker 4).
# Works on debuggable builds (every Aurum CI debug/preview APK). Copies: files/ (import, feedback, crate, offline/,
# owned/) + shared_prefs/ (ratings, EQ). Usage: tools/vault_backup.sh [package] [out.tar]
set -euo pipefail
PKG="${1:-com.apexforge.genesisplayer}"
OUT="${2:-aurum-vault-$(date +%Y%m%d-%H%M%S).tar}"
adb get-state >/dev/null
adb shell run-as "$PKG" true 2>/dev/null || { echo "run-as refused: $PKG is not debuggable or not installed. Nothing copied."; exit 2; }
adb exec-out run-as "$PKG" tar -cf - files shared_prefs > "$OUT"
COUNT=$(tar -tf "$OUT" | grep -vc '/$' || true)
[ "$COUNT" -gt 0 ] || { echo "backup is EMPTY: do not uninstall"; exit 3; }
echo "Backed up $COUNT files from $PKG into $OUT"
tar -tf "$OUT" | grep -E 'aurum_(my_music|feedback|crate)\.json' || echo "note: no Aurum 2.x vault files (older app or nothing imported yet)"
sha256sum "$OUT"
