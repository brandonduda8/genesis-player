#!/usr/bin/env python3
"""Hi-Fi P4: find Brandon's artists on Audius, keeping only the artists' OWN verified accounts.

  python3 -I tools/audius_match.py taste_profile.json --top 100 --out audius_candidates.json

For each of the top artists in the taste profile:
  1. GET https://api.audius.co/v1/users/search?query=<artist>      (public API; no key)
  2. An account counts only if its name matches the artist (case-, space- and accent-insensitive) AND
     is_verified is true; that makes it OFFICIAL. Same-name unverified accounts are listed as NEEDS_REVIEW and their
     tracks are NOT taken, because fan re-uploads live there (see the 8 "Lil Peep" uploads in the current catalog).
  3. For OFFICIAL accounts: GET /v1/users/<id>/tracks, keep streamable tracks, and emit catalog-shaped entries with
     the same stream URL format the app already uses.
The output is a *candidate* list with provenance. It never edits tracks.json; adding to the catalog is a reviewed
step. Requests have a 10 s timeout, there is a polite delay between artists, and failures are reported, not hidden.
Needs network access to api.audius.co (zane-box egress door, or any normal connection), not a build container.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, List, Optional

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
from taste_import import key  # noqa: E402

API = "https://api.audius.co/v1"
APP = "aurum_player"


def http_json(url: str) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": "Aurum/2 (taste match)"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def match_artist(name: str, fetch: Callable[[str], Any] = http_json, max_tracks: int = 25) -> Dict[str, Any]:
    q = urllib.parse.urlencode({"query": name, "app_name": APP})
    try:
        users = (fetch(f"{API}/users/search?{q}") or {}).get("data") or []
    except Exception as exc:  # network, HTTP, JSON: report it, never invent a match
        return {"artist": name, "status": "ERROR", "error": f"{type(exc).__name__}: {exc}", "tracks": []}
    same = [u for u in users if key(u.get("name", "")) == key(name)]
    official = [u for u in same if u.get("is_verified") is True]
    if not official:
        return {"artist": name, "status": "NEEDS_REVIEW" if same else "NOT_ON_AUDIUS", "tracks": [],
                "unverified_same_name": [{"handle": u.get("handle"), "track_count": u.get("track_count")} for u in same]}
    u = max(official, key=lambda x: x.get("follower_count") or 0)
    try:
        tq = urllib.parse.urlencode({"app_name": APP, "limit": max_tracks})
        tracks = (fetch(f"{API}/users/{u['id']}/tracks?{tq}") or {}).get("data") or []
    except Exception as exc:
        return {"artist": name, "status": "ERROR", "error": f"{type(exc).__name__}: {exc}", "tracks": []}
    out = []
    for t in tracks:
        if t.get("is_streamable") is False or not t.get("id"):
            continue
        art = (t.get("artwork") or {})
        out.append({"id": t["id"], "title": t.get("title", ""), "artist": (t.get("user") or {}).get("name", u["name"]),
                    "album": "", "artwork_url": art.get("480x480") or art.get("150x150") or "",
                    "stream_url": f"{API}/tracks/{t['id']}/stream?app_name={APP}", "playlists": [],
                    "why": f"You played {name} a lot on YouTube Music",
                    "provenance": {"source": "audius", "account": u.get("handle"), "verified": True}})
    return {"artist": name, "status": "OFFICIAL", "account": u.get("handle"), "tracks": out}


def run(profile: Dict[str, Any], top: int, fetch: Callable[[str], Any] = http_json, delay_s: float = 0.5,
        sleep: Callable[[float], None] = time.sleep) -> Dict[str, Any]:
    results = []
    for i, a in enumerate(profile.get("artists", [])[:top]):
        if i:
            sleep(delay_s)
        r = match_artist(a["artist"], fetch)
        r["rank"], r["plays"] = a.get("rank"), a.get("plays")
        results.append(r)
    counts: Dict[str, int] = {}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    return {"schema": "aurum-audius-candidates/1", "artists_checked": len(results), "status_counts": counts,
            "official_tracks": sum(len(r["tracks"]) for r in results), "results": results}


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("profile")
    ap.add_argument("--top", type=int, default=100)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    out = run(json.load(open(a.profile, encoding="utf-8")), a.top)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: out[k] for k in ("artists_checked", "status_counts", "official_tracks")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
