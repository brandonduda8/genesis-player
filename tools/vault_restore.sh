#!/usr/bin/env bash
# Restore a vault backup into a DEBUGGABLE Aurum install (e.g. the side-by-side preview). Usage:
#   tools/vault_restore.sh com.apexforge.genesisplayer.preview aurum-vault-XXXX.tar
# A stable-signed release is not debuggable: run-as refuses it, and you re-import the Takeout there instead.
set -euo pipefail
PKG="$1"; IN="$2"
adb shell run-as "$PKG" true 2>/dev/null || { echo "run-as refused for $PKG (not debuggable / not installed). Nothing restored."; exit 2; }
adb shell am force-stop "$PKG"
adb push "$IN" /data/local/tmp/aurum-vault.tar >/dev/null
adb shell "cat /data/local/tmp/aurum-vault.tar | run-as $PKG tar -xf -"
adb shell rm -f /data/local/tmp/aurum-vault.tar
adb shell run-as "$PKG" ls -l files | grep -E 'aurum_|offline|owned' || true
echo "Restored into $PKG. Open the app: Apollo should show your library without re-importing."
