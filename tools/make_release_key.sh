#!/usr/bin/env bash
# One-time: create the STABLE Aurum release key on the OWNER's machine (issue #18, blocker 1).
# The key never enters git or chat. Losing it means future builds can't upgrade in place, so keep two private backups.
set -euo pipefail
OUT="${1:-$HOME/aurum-release.jks}"
[ -e "$OUT" ] && { echo "refusing: $OUT already exists"; exit 1; }
command -v keytool >/dev/null || { echo "keytool not found (install a JDK)"; exit 1; }
read -r -s -p "Choose a keystore password (16+ chars): " P; echo
[ ${#P} -ge 16 ] || { echo "too short"; exit 1; }
keytool -genkeypair -keystore "$OUT" -storetype JKS -alias aurum -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$P" -keypass "$P" -dname "CN=Aurum Pulse, O=Apex Forge"
chmod 600 "$OUT"
echo
echo "Created $OUT. Certificate (compare with CI's signing-report.txt):"
keytool -list -v -keystore "$OUT" -storepass "$P" -alias aurum | grep -E "SHA256:"
echo
echo "Now add 4 repository secrets in GitHub (Settings → Secrets and variables → Actions):"
echo "  AURUM_KEYSTORE_B64 = output of:  base64 -w0 '$OUT'   (macOS: base64 -i '$OUT')"
echo "  AURUM_KEYSTORE_PASS = the password you chose"
echo "  AURUM_KEY_ALIAS     = aurum"
echo "  AURUM_KEY_PASS      = the password you chose"
