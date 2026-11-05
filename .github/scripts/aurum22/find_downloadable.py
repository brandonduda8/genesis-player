"""Find catalog tracks whose Audius artist has enabled downloads (the only source 2.2 will copy offline).

Reads the live catalog and asks Audius itself, using the same rule as VaultIndex.audiusPermission.
Prints one track id per line (may print nothing: that is a real, reportable result)."""
import json, re, sys, urllib.request
CAT = "https://raw.githubusercontent.com/brandonduda8/genesis-catalog/main/catalog.json"
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "AurumPulse-CI"}), timeout=20) as r:
        return json.loads(r.read())
tracks = get(CAT).get("tracks", [])
rx = re.compile(r"^(https://[^/]+)/v1/tracks/([A-Za-z0-9]+)/stream")
checked = 0
for t in tracks:
    m = rx.match(t.get("audius_stream_url", "") or "")
    if not m:
        continue
    checked += 1
    try:
        d = get(f"{m.group(1)}/v1/tracks/{m.group(2)}?app_name=aurum_player").get("data") or {}
    except Exception as e:
        print(f"# {m.group(2)}: lookup failed {e}", file=sys.stderr); continue
    ok = (d.get("is_downloadable") or d.get("downloadable") or (d.get("download") or {}).get("is_downloadable")) \
        and not d.get("is_download_gated")
    print(f"# {m.group(2)} {t.get('artist')} - {t.get('title')}: {'DOWNLOADABLE' if ok else 'streaming only'}", file=sys.stderr)
    if ok:
        print(f"{m.group(2)} {m.group(1)}")
streams = [m.group(0) + "?app_name=aurum_player" for m in (rx.match(t.get("audius_stream_url", "") or "") for t in tracks) if m][:3]
open(sys.argv[1] if len(sys.argv) > 1 else "/tmp/aurum22/streams.txt", "w").write(",".join(streams))
print(f"# audius tracks checked: {checked} of {len(tracks)} catalog tracks", file=sys.stderr)
