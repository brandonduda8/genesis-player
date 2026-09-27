#!/usr/bin/env python3
"""Merge the 207-track hunt dataset into the player's tracks.json.
- Appends all 207 tracks (zero id overlap with the 220 existing).
- GHOSTLANDS 10-track album inserted as one contiguous block in album order.
- Rebuilds the top-level playlists array: existing 7 untouched + 6 new.
- LEGENDS (36) sequenced exactly per dataset_report.md maker's order.
- Ghostlands playlist: album 1..10 + soul scars bonus.
- Validates: unique ids, every playlist id resolves, JSON round-trips.
"""
import json, re, shutil, unicodedata, sys, os

ASSETS = os.path.expanduser("~/workspace/genesis-player/android/app/src/main/assets")
CUR = os.path.join(ASSETS, "tracks.json")
NEW = os.path.expanduser("~/workspace/genesis-player/work-orders/new_tracks_dataset.json")
REPORT = os.path.expanduser("~/workspace/genesis-player/work-orders/dataset_report.md")

def norm(s):
    s = unicodedata.normalize("NFKC", s)
    s = s.replace("\u201c", '"').replace("\u201d", '"').replace("\u2018", "'").replace("\u2019", "'")
    s = s.replace("\u2013", "-").replace("\u2014", "-")
    return s.strip().lower()

cur = json.load(open(CUR))
new = json.load(open(NEW))
report = open(REPORT).read()

# ---- extract LEGENDS order from the report ----
leg_sec = report.split("## LEGENDS")[1].split("## GHOSTLANDS")[0]
entries = []
for m in re.finditer(r"^\d+\.\s+\*\*(.+?)\*\*", leg_sec, re.M):
    txt = m.group(1)
    # form: Artist — "Title"  (em dash or hyphen separator)
    parts = re.split(r"\s+[—–-]\s+", txt, maxsplit=1)
    artist = parts[0].strip()
    title = re.sub(r'^"(.*)"$', r"\1", parts[1].strip()) if len(parts) > 1 else ""
    entries.append((artist, title))
assert len(entries) == 36, f"expected 36 LEGENDS entries, got {len(entries)}"

# Explicit overrides: report (artist, title) -> exact dataset title, for entries
# whose wording differs between dataset_report.md and new_tracks_dataset.json.
OVERRIDES = {
    ('☭$ov!et k!d☭ (e$kay)', '"venom" (unotheactivist x yeat rage type beat)'):
        '"Venom" UnoTheActivist x Yeat | RAGE Type Beat',
    ('luca lush', 'serotonin'):
        'LUCA LUSH - SEROTONIN',
    ('aadysi x sonace', 'abyss'):
        'Aadysi x SONACE - Abyss',
    ('pritty', 'soldier, like me'):
        'Soldier, Like Me feat. Ravenna Golden (prod. Dylan Brady)',
    ('rome in silver', 'cloud9high (feat. vaarwell)'):
        'Rome in Silver - cloud9high (feat. Vaarwell)',
}

by_title = {norm(t["title"]): t for t in new}
by_artist_title = {(norm(t["artist"]), norm(t["title"])): t for t in new}
legends_ids, misses = [], []
for artist, title in entries:
    key = (norm(artist), norm(title))
    t = None
    if key in OVERRIDES:
        t = by_title.get(norm(OVERRIDES[key]))
    if t is None:
        t = by_artist_title.get(key) or by_title.get(norm(title))
    if t:
        legends_ids.append(t["id"])
    else:
        misses.append(f"{artist} — {title}")
if misses:
    print("LEGENDS MISSES:"); [print("  ", m) for m in misses]; sys.exit(1)

# ---- ghostlands album block (contiguous, album order) ----
album = sorted([t for t in new if t.get("album") == "GHOSTLANDS"], key=lambda t: t["album_order"])
assert len(album) == 10, f"expected 10 album tracks, got {len(album)}"
assert [t["album_order"] for t in album] == list(range(1, 11))
soul_scars = next(t for t in new if norm(t["title"]).startswith("soul scars"))
ghostlands_pl_ids = [t["id"] for t in album] + [soul_scars["id"]]

# ---- other playlists in dataset order ----
def ids_for(tag):
    return [t["id"] for t in new if tag in t.get("playlists", [])]
sad_boy_ids = ids_for("Sad Boy")
phoenix_ids = ids_for("Phoenix Rising")
dark_ids = ids_for("Dark Cinematic")
night_ids = ids_for("Night Drive")
print(f"playlist sizes: Legends {len(legends_ids)}, Sad Boy {len(sad_boy_ids)}, "
      f"Ghostlands {len(ghostlands_pl_ids)}, Phoenix Rising {len(phoenix_ids)}, "
      f"Dark Cinematic {len(dark_ids)}, Night Drive {len(night_ids)}")

# ---- merge tracks ----
cur_ids = {t["id"] for t in cur["tracks"]}
assert not (cur_ids & {t["id"] for t in new}), "id overlap!"
album_ids = {t["id"] for t in album}
rest = [t for t in new if t["id"] not in album_ids]

def clean(t):
    return {
        "id": t["id"], "title": t["title"], "artist": t["artist"],
        "stream_url": t.get("stream_url", ""), "artwork_url": t.get("artwork_url", ""),
        "duration_s": t.get("duration_s", 0), "soundcloud_url": t.get("soundcloud_url", ""),
        "genre": t.get("genre", ""), "mood": t.get("mood", ""),
        "playlists": t.get("playlists", []), "vibe": t.get("vibe", ""),
        "album": t.get("album", ""), "album_order": t.get("album_order", 0),
        "album_artist": t.get("album_artist", ""), "label": t.get("label", ""),
        "discovery": bool(t.get("discovery", False)),
        "playback_count": t.get("playback_count", 0),
    }

merged_tracks = cur["tracks"] + [clean(t) for t in album] + [clean(t) for t in rest]

# ---- playlists ----
new_playlists = [
    {"name": "Legends", "track_ids": legends_ids},
    {"name": "Sad Boy", "track_ids": sad_boy_ids},
    {"name": "Ghostlands", "track_ids": ghostlands_pl_ids},
    {"name": "Phoenix Rising", "track_ids": phoenix_ids},
    {"name": "Dark Cinematic", "track_ids": dark_ids},
    {"name": "Night Drive", "track_ids": night_ids},
]
merged = {
    "tracks": merged_tracks,
    "playlists": cur["playlists"] + new_playlists,
    "for_you": cur["for_you"],
}

# ---- validate ----
all_ids = {t["id"] for t in merged_tracks}
assert len(all_ids) == len(merged_tracks), "duplicate track ids!"
for p in merged["playlists"]:
    bad = [i for i in p["track_ids"] if i not in all_ids]
    assert not bad, f"playlist {p['name']} has {len(bad)} dangling ids"
    assert len(set(p["track_ids"])) == len(p["track_ids"]), f"dup ids in {p['name']}"
# ghostlands contiguity
order = [t["id"] for t in merged_tracks]
gpos = sorted(order.index(i) for i in ghostlands_pl_ids[:10])
assert gpos == list(range(gpos[0], gpos[0] + 10)), "GHOSTLANDS not contiguous!"

shutil.copy2(CUR, CUR + ".pre-207.bak")
json.dump(merged, open(CUR, "w"), ensure_ascii=False, indent=1)
# round-trip
json.load(open(CUR))
print(f"OK: {len(cur['tracks'])} -> {len(merged_tracks)} tracks, "
      f"{len(cur['playlists'])} -> {len(merged['playlists'])} playlists")
print("GHOSTLANDS block at positions", gpos[0], "-", gpos[-1])
