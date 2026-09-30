#!/usr/bin/env python3
"""Deterministic catalog validator (staged dry run for WO-AURUM-007 Phase 3).

Stdlib only. No network. Never prints stream URLs (they may carry client ids).
Exit code 0 = clean, 1 = failures. Network health probes are a separate concern
and are deliberately NOT part of this script.

Usage:
  catalog_validate.py remote <catalog.json> [--allowlist FILE] [--base-version N] [--base-file BASE.json] [--json]
  catalog_validate.py bundled <tracks.json> [--allowlist FILE] [--json]
  catalog_validate.py selftest
"""
import json, re, sys, unicodedata
from collections import Counter, defaultdict
from urllib.parse import urlparse


def norm_exact(s):
    return re.sub(r"\s+", " ", str(s or "").strip().casefold())


def norm_near(s):
    s = unicodedata.normalize("NFKD", str(s or "")).casefold()
    s = re.sub(r"[^\w\s]", " ", s)
    s = re.sub(r"^the\s+", "", s.strip())
    return re.sub(r"\s+", " ", s).strip()


def pair(t, fn):
    return (fn(t.get("artist")), fn(t.get("title")))


def dup_pairs(tracks, fn):
    groups = defaultdict(list)
    for i, t in enumerate(tracks):
        groups[pair(t, fn)].append(i)
    return {k: v for k, v in groups.items() if len(v) > 1 and k != ("", "")}


def url_problem(u):
    """Deterministic shape check only. Returns a short reason or None."""
    if not isinstance(u, str) or not u.strip():
        return "missing"
    if u.startswith("/"):
        return None  # relative app path, resolved by the app
    p = urlparse(u)
    if p.scheme not in ("http", "https") or not p.netloc:
        return "malformed"
    return None


def load_allow(path):
    allow = set()
    if path:
        try:
            lines = open(path, encoding="utf-8").read().splitlines()
        except OSError as e:
            sys.exit(f"allowlist not readable: {e}")
        for n, line in enumerate(lines, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "|" not in line:
                sys.exit(f"allowlist line {n} malformed (need artist|title)")
            a, t = line.split("|", 1)
            allow.add((norm_exact(a), norm_exact(t)))
    return allow


def validate_remote(doc, allow=frozenset(), base_version=None, base_doc=None):
    f, w, info = [], [], {}
    if not isinstance(doc.get("tracks"), list):
        return ["tracks missing or not a list"], w, info
    tracks = doc["tracks"]
    if base_doc is not None and isinstance(base_doc.get("tracks"), list):
        changed = json.dumps(base_doc["tracks"], sort_keys=True) != json.dumps(tracks, sort_keys=True)
        info["tracks_changed_vs_base"] = changed
        bumped = isinstance(doc.get("version"), int) and isinstance(base_doc.get("version"), int) \
            and doc["version"] > base_doc["version"]
        if changed and not bumped:
            f.append(f"tracks changed but version was not bumped above base ({base_doc.get('version')})")
    info["tracks"] = len(tracks)
    info["version"] = doc.get("version")
    if not isinstance(doc.get("version"), int):
        f.append("version missing or not an int")
    if doc.get("track_count") != len(tracks):
        f.append(f"track_count {doc.get('track_count')} != len(tracks) {len(tracks)}")
    if base_version is not None and isinstance(doc.get("version"), int) and doc["version"] <= base_version:
        f.append(f"version {doc['version']} not greater than base {base_version}")
    miss = Counter()
    bad_url = Counter()
    for t in tracks:
        for k in ("artist", "title"):
            if not str(t.get(k) or "").strip():
                miss[k] += 1
        r = url_problem(t.get("audius_stream_url"))
        if r == "missing" and str(t.get("soundcloud_url") or "").strip():
            sc = urlparse(str(t.get("soundcloud_url")).strip())
            if sc.scheme in ("http", "https") and (sc.hostname or "").endswith("soundcloud.com"):
                info["runtime_resolved_soundcloud"] = info.get("runtime_resolved_soundcloud", 0) + 1
            else:
                bad_url["bad_soundcloud_url"] += 1
        elif r:
            bad_url[r] += 1
    info.setdefault("runtime_resolved_soundcloud", 0)
    info["missing_required"] = dict(miss)
    info["bad_stream_url_shape"] = dict(bad_url)
    if miss:
        f.append(f"missing required metadata: {dict(miss)}")
    if bad_url:
        f.append(f"stream url shape problems: {dict(bad_url)}")
    ex = {k: v for k, v in dup_pairs(tracks, norm_exact).items() if k not in allow}
    nr = dup_pairs(tracks, norm_near)
    info["dup_pairs_exact"] = len(ex)
    info["dup_surplus_rows_exact"] = sum(len(v) - 1 for v in ex.values())
    info["dup_pairs_near"] = len(nr)
    info["dup_surplus_rows_near"] = sum(len(v) - 1 for v in nr.values())
    if ex:
        f.append(f"{len(ex)} duplicate normalized artist+title pairs (not allowlisted)")
    if len(nr) > len(ex):
        w.append("near-duplicate pairs exceed exact ones; review, not auto-fail (versions and remixes can differ)")
    fb_missing = sum(1 for t in tracks if not (isinstance(t.get("playlists"), list) and "Full Blend" in t["playlists"]))
    info["not_in_full_blend"] = fb_missing
    if fb_missing:
        f.append(f"{fb_missing} tracks not in the Full Blend playlist (catalog README invariant)")
    info["verified_206"] = dict(Counter(str(t.get("verified_206")) for t in tracks))
    info["no_track_id_field"] = all("id" not in t for t in tracks)
    if info["no_track_id_field"]:
        w.append("remote tracks carry no id field; duplicate-id check cannot run on this file")
    return f, w, info


def validate_bundled(doc, allow=frozenset()):
    f, w, info = [], [], {}
    tracks = doc.get("tracks", [])
    playlists = doc.get("playlists", [])
    info["tracks"], info["playlists"] = len(tracks), len(playlists)
    ids = [t.get("id") for t in tracks]
    dup_ids = [k for k, v in Counter(ids).items() if v > 1]
    info["duplicate_ids"] = len(dup_ids)
    if dup_ids:
        f.append(f"{len(dup_ids)} duplicate track ids")
    if any(not i for i in ids):
        f.append("track with missing id")
    idset = set(ids)
    miss = Counter()
    bad_url = Counter()
    for t in tracks:
        for k in ("artist", "title"):
            if not str(t.get(k) or "").strip():
                miss[k] += 1
        r = url_problem(t.get("stream_url"))
        if r == "missing" and str(t.get("id") or "").startswith("sc_"):
            info["runtime_resolved_soundcloud_id"] = info.get("runtime_resolved_soundcloud_id", 0) + 1
        elif r:
            bad_url[r] += 1
    info.setdefault("runtime_resolved_soundcloud_id", 0)
    info["missing_required"], info["bad_stream_url_shape"] = dict(miss), dict(bad_url)
    if miss:
        f.append(f"missing required metadata: {dict(miss)}")
    if bad_url:
        f.append(f"stream url shape problems: {dict(bad_url)}")
    dangling = []
    for pl in playlists:
        ids_ = pl.get("track_ids", [])
        if not isinstance(ids_, list):
            f.append(f"playlist {pl.get('name')!r}: track_ids is not a list")
            continue
        for tid in ids_:
            if tid not in idset:
                dangling.append((pl.get("name"), tid))
    info["dangling_playlist_refs"] = len(dangling)
    if dangling:
        f.append(f"{len(dangling)} playlist refs to missing tracks")
    fy = doc.get("for_you") or []
    if isinstance(fy, dict):
        fy_items = [x for v in fy.values() if isinstance(v, list) for x in v]
    elif isinstance(fy, list):
        fy_items = fy
    else:
        fy_items = []
    fy_ids = [x if isinstance(x, str) else (x.get("id") if isinstance(x, dict) else None) for x in fy_items]
    info["for_you_entries"] = len(fy_items)
    info["for_you_not_in_tracks"] = sum(1 for x in fy_ids if x not in idset)
    bad_fy = sum(1 for x in fy_items if isinstance(x, dict) and (
        not x.get("id") or not str(x.get("artist") or "").strip() or not str(x.get("title") or "").strip()))
    info["for_you_missing_fields"] = bad_fy
    if bad_fy:
        f.append(f"{bad_fy} for_you entries missing id, artist or title")
    if info["for_you_not_in_tracks"]:
        w.append(f"{info['for_you_not_in_tracks']} for_you entries are not in tracks[]; they carry their own fields, confirm this is by design")
    ex = {k: v for k, v in dup_pairs(tracks, norm_exact).items() if k not in allow}
    nr = dup_pairs(tracks, norm_near)
    info["dup_pairs_exact"] = len(ex)
    info["dup_pairs_near"] = len(nr)
    if ex:
        f.append(f"{len(ex)} duplicate normalized artist+title pairs (not allowlisted)")
    per_artist = Counter(norm_exact(t.get("artist")) for t in tracks)
    top = per_artist.most_common(3)
    info["top_artist_share_pct"] = round(100 * top[0][1] / max(1, len(tracks)), 1) if top else 0
    return f, w, info


def selftest():
    good = {"version": 2, "track_count": 2, "tracks": [
        {"artist": "A", "title": "One", "audius_stream_url": "https://x.test/a", "playlists": ["Full Blend"]},
        {"artist": "B", "title": "Two", "audius_stream_url": "/api/x?u=1", "playlists": ["Full Blend"]}]}
    assert validate_remote(good, base_version=1)[0] == [], "clean remote must pass"
    cases = {
        "dup pair": {**good, "track_count": 3, "tracks": good["tracks"] + [{"artist": " a ", "title": "ONE", "audius_stream_url": "https://x.test/z", "playlists": ["Full Blend"]}]},
        "count mismatch": {**good, "track_count": 5},
        "missing title": {**good, "tracks": [{"artist": "A", "title": "", "audius_stream_url": "https://x.test/a"}, good["tracks"][1]]},
        "bad url": {**good, "tracks": [{"artist": "A", "title": "One", "audius_stream_url": "ftp//nope"}, good["tracks"][1]]},
    }
    for name, doc in cases.items():
        assert validate_remote(doc)[0], f"remote case must fail: {name}"
    assert validate_remote(good, base_version=2)[0], "no version bump must fail"
    sc_ok = {**good, "tracks": good["tracks"] + [{"artist": "C", "title": "Three", "audius_stream_url": "", "soundcloud_url": "https://soundcloud.com/x/y", "playlists": ["Full Blend"]}], "track_count": 3}
    assert validate_remote(sc_ok)[0] == [], "empty stream url with a soundcloud permalink must pass"
    sc_bad = {**good, "tracks": good["tracks"] + [{"artist": "C", "title": "Three", "audius_stream_url": "", "soundcloud_url": ""}], "track_count": 3}
    assert validate_remote(sc_bad)[0], "empty stream url and no permalink must fail"
    sc_host = {**good, "tracks": good["tracks"] + [{"artist": "C", "title": "Three", "audius_stream_url": "", "soundcloud_url": "https://evil.test/x"}], "track_count": 3}
    assert validate_remote(sc_host)[0], "non-soundcloud permalink host must fail"
    assert validate_remote({"version": 1, "track_count": 0})[0], "missing tracks list must fail"
    changed = {**good, "tracks": [{**good["tracks"][0], "title": "Changed"}, good["tracks"][1]]}
    assert validate_remote(changed, base_doc=good)[0], "tracks changed with same version must fail"
    assert validate_remote({**changed, "version": 3}, base_doc=good)[0] == [], "bumped version must pass"
    assert validate_remote(good, base_doc=good)[0] == [], "unchanged tracks with unchanged version must pass"
    nofb = {**good, "tracks": [{**good["tracks"][0], "playlists": ["Other"]}, good["tracks"][1]]}
    assert validate_remote(nofb)[0], "track missing from Full Blend must fail"
    b = {"tracks": [{"id": "1", "artist": "A", "title": "One", "stream_url": "https://x.test/1"},
                    {"id": "2", "artist": "B", "title": "Two", "stream_url": "https://x.test/2"}],
         "playlists": [{"name": "p", "track_ids": ["1", "2"]}], "for_you": {"x": ["1"]}}
    assert validate_bundled(b)[0] == [], "clean bundled must pass"
    dupid = {**b, "tracks": b["tracks"] + [{"id": "1", "artist": "C", "title": "Three", "stream_url": "https://x.test/3"}]}
    assert validate_bundled(dupid)[0], "duplicate id must fail"
    badref = {**b, "playlists": [{"name": "p", "track_ids": ["1", "999"]}]}
    assert validate_bundled(badref)[0], "dangling playlist ref must fail"
    fy_bad = {**b, "for_you": [{"id": "7", "artist": "", "title": "x", "stream_url": "https://x.test/7"}]}
    assert validate_bundled(fy_bad)[0], "for_you entry with missing artist must fail"
    fy_ok = {**b, "for_you": [{"id": "7", "artist": "Z", "title": "x", "stream_url": "https://x.test/7"}]}
    fr = validate_bundled(fy_ok)
    assert fr[0] == [] and fr[1], "self-contained for_you entry passes but warns"
    badlist = {**b, "playlists": [{"name": "p", "track_ids": "1"}]}
    assert validate_bundled(badlist)[0], "track_ids that is not a list must fail"
    allow = {("a", "one")}
    dp = {**b, "tracks": b["tracks"] + [{"id": "9", "artist": "a", "title": "one", "stream_url": "https://x.test/9"}]}
    assert validate_bundled(dp)[0] and not validate_bundled(dp, allow)[0], "allowlist must suppress only the listed pair"
    print("selftest: all cases behaved as expected")


def main(argv):
    if len(argv) >= 2 and argv[1] == "selftest":
        selftest(); return 0
    if len(argv) < 3 or argv[1] not in ("remote", "bundled"):
        print(__doc__); return 2
    mode, path = argv[1], argv[2]
    allow = load_allow(argv[argv.index("--allowlist") + 1]) if "--allowlist" in argv else set()
    base = int(argv[argv.index("--base-version") + 1]) if "--base-version" in argv else None
    base_doc = json.load(open(argv[argv.index("--base-file") + 1], encoding="utf-8")) if "--base-file" in argv else None
    doc = json.load(open(path, encoding="utf-8"))
    f, w, info = validate_remote(doc, allow, base, base_doc) if mode == "remote" else validate_bundled(doc, allow)
    out = {"file": path.split("/")[-1], "mode": mode, "failures": f, "warnings": w, "measured": info}
    print(json.dumps(out, indent=2, ensure_ascii=False))
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
