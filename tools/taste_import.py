#!/usr/bin/env python3
"""Hi-Fi P4: turn a Google Takeout YouTube export into Aurum's taste profile. Metadata only, never audio.

  python3 -I tools/taste_import.py "Takeout/YouTube and YouTube Music/history/watch-history.json" \
      --catalog android/app/src/main/assets/tracks.json --out taste_profile.json

Reads only entries whose header is "YouTube Music". Each play gets a recency weight (half-life of 90 days, so this
autumn counts more than spring). The output is aggregates: artists with plays and score, top songs, and coverage
against the current catalog. Watch URLs, search history and timestamps of individual plays are NOT kept.

PRIVACY: the profile describes Brandon's listening. Keep it out of public repos (genesis-player is public). It belongs
on the device or in a private repo. This tool only writes where --out points.
Stdlib only; run with `python3 -I` on untrusted exports.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import re
import sys
import unicodedata
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional

HALF_LIFE_DAYS = 90.0
_SPLIT = re.compile(r"\s*(?:,|&|\bfeat\.?|\bft\.?|\bwith\b|\(|\)|\bx\b)\s*", re.I)


def key(name: str) -> str:
    """Match key: case-, accent-, punctuation- and SPACE-insensitive ("Witchouse40k" == "Witchouse 40k")."""
    s = unicodedata.normalize("NFKD", name or "")
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = re.sub(r"\s*-\s*topic$", "", s)
    return re.sub(r"[^\w$]+", "", s).replace("_", "")


def artist_of(entry: Dict[str, Any]) -> str:
    name = ((entry.get("subtitles") or [{}])[0] or {}).get("name", "")
    return re.sub(r"\s*-\s*Topic$", "", name).strip()


def title_of(entry: Dict[str, Any]) -> str:
    return re.sub(r"^Watched\s+", "", entry.get("title", "")).strip()


def _when(entry: Dict[str, Any]) -> Optional[datetime]:
    try:
        return datetime.fromisoformat(entry["time"].replace("Z", "+00:00"))
    except (KeyError, ValueError, AttributeError):
        return None


def catalog_artist_keys(catalog: Dict[str, Any]) -> collections.Counter:
    c: collections.Counter = collections.Counter()
    for t in catalog.get("tracks", []):
        for part in _SPLIT.split(t.get("artist", "")):
            if key(part):
                c[key(part)] += 1
    return c


def build_profile(history: Iterable[Dict[str, Any]], catalog: Optional[Dict[str, Any]] = None,
                  now: Optional[datetime] = None, top_n: int = 200) -> Dict[str, Any]:
    music = [e for e in history if e.get("header") == "YouTube Music" and artist_of(e)]
    times = [w for w in (_when(e) for e in music) if w]
    now = now or (max(times) if times else datetime.now(timezone.utc))
    weight = lambda e: (0.5 ** (max(0.0, (now - _when(e)).total_seconds() / 86400) / HALF_LIFE_DAYS)  # noqa: E731
                        if _when(e) else 0.5)
    artists: Dict[str, Dict[str, Any]] = {}
    songs: Dict[tuple, Dict[str, Any]] = {}
    for e in music:
        a, k, w = artist_of(e), key(artist_of(e)), weight(e)
        rec = artists.setdefault(k, {"artist": a, "plays": 0, "score": 0.0})
        rec["plays"] += 1
        rec["score"] += w
        s = songs.setdefault((title_of(e).lower(), k), {"title": title_of(e), "artist": a, "plays": 0, "score": 0.0})
        s["plays"] += 1
        s["score"] += w
    ranked = sorted(artists.items(), key=lambda kv: (-kv[1]["score"], kv[1]["artist"]))
    cat = catalog_artist_keys(catalog) if catalog else collections.Counter()
    total = sum(r["plays"] for r in artists.values())
    covered = sum(r["plays"] for k, r in artists.items() if k in cat)
    out_artists = [{"rank": i + 1, "artist": r["artist"], "plays": r["plays"], "score": round(r["score"], 2),
                    "in_catalog_tracks": cat.get(k, 0)} for i, (k, r) in enumerate(ranked[:top_n])]
    top_songs = sorted(songs.values(), key=lambda s: (-s["score"], s["title"]))[:top_n]
    return {
        "schema": "aurum-taste/1",
        "source": "Google Takeout YouTube watch-history (YouTube Music entries only; metadata only)",
        "window": {"from": min(times).date().isoformat() if times else None,
                   "to": max(times).date().isoformat() if times else None},
        "half_life_days": HALF_LIFE_DAYS,
        "music_plays": total, "distinct_artists": len(artists), "distinct_songs": len(songs),
        "catalog_coverage": None if not catalog else {
            "plays_by_artists_in_catalog": covered, "share": round(covered / total, 4) if total else 0.0,
            "top100_artists_in_catalog": sum(1 for a in out_artists[:100] if a["in_catalog_tracks"])},
        "artists": out_artists,
        "songs": [{"title": s["title"], "artist": s["artist"], "plays": s["plays"], "score": round(s["score"], 2)}
                  for s in top_songs],
    }


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("watch_history")
    ap.add_argument("--catalog")
    ap.add_argument("--out", required=True)
    ap.add_argument("--top", type=int, default=200)
    a = ap.parse_args(argv)
    hist = json.load(open(a.watch_history, encoding="utf-8"))
    cat = json.load(open(a.catalog, encoding="utf-8")) if a.catalog else None
    prof = build_profile(hist, cat, top_n=a.top)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(prof, f, ensure_ascii=False, indent=1)
    cov = prof["catalog_coverage"]
    print(f"{prof['music_plays']} music plays, {prof['distinct_artists']} artists, {prof['distinct_songs']} songs "
          f"({prof['window']['from']} -> {prof['window']['to']})"
          + (f"; catalog covers {100 * cov['share']:.1f}% of plays, {cov['top100_artists_in_catalog']}/100 top artists"
             if cov else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
