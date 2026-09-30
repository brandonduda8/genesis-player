#!/usr/bin/env python3
"""Deterministic catalog validator (WO-AURUM-007 Phase 3).

Stdlib only. No network. Never prints stream URLs (they may carry client ids).
Exit code: 0 = clean, 1 = failures, 2 = bad usage or unreadable input.
Network health probes are a separate concern and are deliberately NOT part of
this script.

Usage:
  catalog_validate.py remote <catalog.json> [--allowlist FILE] [--base-version N] [--base-file BASE.json]
  catalog_validate.py bundled <tracks.json> [--allowlist FILE]
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
    if u.startswith("//"):
        return "malformed"
    if u.startswith("/"):
        return None  # relative app path, resolved by the app
    p = urlparse(u)
    if p.scheme not in ("http", "https") or not p.netloc:
        return "malformed"
    return None


def is_soundcloud_permalink(u):
    p = urlparse(str(u or "").strip())
    h = (p.hostname or "").lower()
    return p.scheme in ("http", "https") and (h == "soundcloud.com" or h.endswith(".soundcloud.com"))


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
    if not isinstance(doc, dict):
        return ["top level is not an object"], w, info
    if not isinstance(doc.get("tracks"), list):
        return ["tracks missing or not a list"], w, info
    tracks = doc["tracks"]
    if not all(isinstance(t, dict) for t in tracks):
        return ["track entries must be objects"], w, info
    if isinstance(base_doc, dict) and isinstance(base_doc.get("tracks"), list):
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
            if is_soundcloud_permalink(t.get("soundcloud_url")):
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
    if len(nr) > len(ex) + len(allow):
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
    if not isinstance(doc, dict):
        return ["top level is not an object"], w, info
    if not isinstance(doc.get("tracks"), list):
        return ["tracks missing or not a list"], w, info
    if not isinstance(doc.get("playlists"), list):
        return ["playlists missing or not a list"], w, info
    tracks, playlists = doc["tracks"], doc["playlists"]
    if not all(isinstance(t, dict) for t in tracks) or not all(isinstance(p, dict) for p in playlists):
        return ["track and playlist entries must be objects"], w, info
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
        su = str(t.get("stream_url") or "")
        if su.startswith(("http://", "https://")) and not (urlparse(su).hostname or "").lower().endswith("audius.co"):
            info["non_audius_stream_hosts"] = info.get("non_audius_stream_hosts", 0) + 1
    info.setdefault("runtime_resolved_soundcloud_id", 0)
    info.setdefault("non_audius_stream_hosts", 0)
    info["missing_required"], info["bad_stream_url_shape"] = dict(miss), dict(bad_url)
    if miss:
        f.append(f"missing required metadata: {dict(miss)}")
    if bad_url:
        f.append(f"stream url shape problems: {dict(bad_url)}")
    if info["non_audius_stream_hosts"]:
        w.append(f"{info['non_audius_stream_hosts']} tracks stream from a non-Audius host; confirm this is intended")
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
    def has(res, needle):
        return any(needle in x for x in res[0])

    FB = ["Full Blend"]

    def rt(a, t, url="https://x.test/a", **kw):
        return {"artist": a, "title": t, "audius_stream_url": url, "playlists": FB, **kw}

    good = {"version": 2, "track_count": 2, "tracks": [rt("A", "One"), rt("B", "Two", "/api/x?u=1")]}

    def remote(tracks, **top):
        return {**good, "track_count": len(tracks), "tracks": tracks, **top}

    assert validate_remote(good, base_version=1)[0] == [], "clean remote must pass"
    extra = good["tracks"]
    cases = [
        ("duplicate pair", remote(extra + [rt(" a ", "ONE", "https://x.test/z")]), "duplicate normalized"),
        ("count mismatch", {**good, "track_count": 5}, "track_count"),
        ("missing title", remote([rt("A", ""), extra[1]]), "missing required"),
        ("malformed url", remote([rt("A", "One", "ftp//nope"), extra[1]]), "stream url shape"),
        ("protocol-relative url", remote([rt("A", "One", "//evil.test/x"), extra[1]]), "stream url shape"),
        ("no permalink", remote(extra + [rt("C", "Three", "", soundcloud_url="")]), "stream url shape"),
        ("non-soundcloud permalink", remote(extra + [rt("C", "Three", "", soundcloud_url="https://evil.test/x")]), "stream url shape"),
        ("lookalike soundcloud host", remote(extra + [rt("C", "Three", "", soundcloud_url="https://evilsoundcloud.com/x")]), "stream url shape"),
        ("missing from Full Blend", remote([rt("A", "One", playlists=["Other"]), extra[1]]), "Full Blend"),
        ("non-object track", remote([extra[0], "oops"]), "must be objects"),
        ("top level not an object", [], "not an object"),
        ("missing tracks list", {"version": 1, "track_count": 0}, "tracks missing"),
    ]
    for name, doc, needle in cases:
        res = validate_remote(doc)
        assert has(res, needle), f"remote case '{name}' must fail with '{needle}', got {res[0]}"
    assert has(validate_remote(good, base_version=2), "not greater"), "no version bump must fail"
    for host in ("https://soundcloud.com/x/y", "https://m.soundcloud.com/x"):
        ok = remote(extra + [rt("C", "Three", "", soundcloud_url=host)])
        assert validate_remote(ok)[0] == [], f"empty stream url with permalink {host} must pass"
    changed = remote([rt("A", "Changed"), extra[1]])
    assert has(validate_remote(changed, base_doc=good), "not bumped"), "tracks changed with same version must fail"
    assert validate_remote({**changed, "version": 3}, base_doc=good)[0] == [], "bumped version must pass"
    assert validate_remote(good, base_doc=good)[0] == [], "unchanged tracks with unchanged version must pass"
    dupdoc = remote(extra + [rt(" a ", "ONE", "https://x.test/z")])
    assert not has(validate_remote(dupdoc, {("a", "one")}), "duplicate normalized"), "allowlist must suppress the listed pair"
    assert has(validate_remote(dupdoc, {("b", "two")}), "duplicate normalized"), "allowlist must not suppress other pairs"

    def bt(i, a, t, url="https://x.audius.co/1"):
        return {"id": i, "artist": a, "title": t, "stream_url": url}

    b = {"tracks": [bt("1", "A", "One"), bt("2", "B", "Two")],
         "playlists": [{"name": "p", "track_ids": ["1", "2"]}], "for_you": {"x": ["1"]}}
    assert validate_bundled(b)[0] == [], "clean bundled must pass"
    bcases = [
        ("duplicate id", {**b, "tracks": b["tracks"] + [bt("1", "C", "Three")]}, "duplicate track ids"),
        ("missing id", {**b, "tracks": b["tracks"] + [bt("", "C", "Three")]}, "missing id"),
        ("missing artist", {**b, "tracks": [bt("1", "", "One"), b["tracks"][1]]}, "missing required"),
        ("malformed url", {**b, "tracks": [bt("1", "A", "One", "ftp//x"), b["tracks"][1]]}, "stream url shape"),
        ("empty url on non-sc id", {**b, "tracks": [bt("1", "A", "One", ""), b["tracks"][1]]}, "stream url shape"),
        ("dangling ref", {**b, "playlists": [{"name": "p", "track_ids": ["1", "999"]}]}, "missing tracks"),
        ("track_ids not a list", {**b, "playlists": [{"name": "p", "track_ids": "1"}]}, "not a list"),
        ("for_you missing artist", {**b, "for_you": [{"id": "7", "artist": "", "title": "x", "stream_url": "https://x.audius.co/7"}]}, "for_you"),
        ("duplicate pair", {**b, "tracks": b["tracks"] + [bt("9", " a ", "ONE")]}, "duplicate normalized"),
        ("tracks missing", {"playlists": []}, "tracks missing"),
        ("playlists missing", {"tracks": b["tracks"]}, "playlists missing"),
        ("non-object track", {**b, "tracks": [b["tracks"][0], 5]}, "must be objects"),
    ]
    for name, doc, needle in bcases:
        res = validate_bundled(doc)
        assert has(res, needle), f"bundled case '{name}' must fail with '{needle}', got {res[0]}"
    sc = {**b, "tracks": b["tracks"] + [bt("sc_5", "D", "Four", "")]}
    assert validate_bundled(sc)[0] == [], "empty stream url on an sc_ id must pass"
    other = validate_bundled({**b, "tracks": [bt("1", "A", "One", "https://other.test/1"), b["tracks"][1]]})
    assert other[0] == [] and any("non-audius" in x.lower() for x in other[1]), "non-Audius host passes but warns"
    fy_ok = validate_bundled({**b, "for_you": [{"id": "7", "artist": "Z", "title": "x", "stream_url": "https://x.audius.co/7"}]})
    assert fy_ok[0] == [] and fy_ok[1], "self-contained for_you entry passes but warns"
    dp = {**b, "tracks": b["tracks"] + [bt("9", "a", "one")]}
    assert validate_bundled(dp)[0] and not validate_bundled(dp, {("a", "one")})[0], "allowlist must suppress the listed pair"
    print("selftest: all cases behaved as expected")


def opt(argv, name):
    if name in argv:
        i = argv.index(name)
        if i + 1 >= len(argv):
            sys.exit(f"{name} needs a value")
        return argv[i + 1]
    return None


def read_json(path):
    try:
        return json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as e:
        sys.exit(f"cannot read JSON from {path}: {type(e).__name__}")


def main(argv):
    if len(argv) >= 2 and argv[1] == "selftest":
        selftest()
        return 0
    if len(argv) < 3 or argv[1] not in ("remote", "bundled"):
        print(__doc__)
        return 2
    mode, path = argv[1], argv[2]
    allow = load_allow(opt(argv, "--allowlist"))
    bv = opt(argv, "--base-version")
    try:
        base = int(bv) if bv is not None else None
    except ValueError:
        sys.exit("--base-version must be an integer")
    bf = opt(argv, "--base-file")
    base_doc = read_json(bf) if bf else None
    doc = read_json(path)
    f, w, info = validate_remote(doc, allow, base, base_doc) if mode == "remote" else validate_bundled(doc, allow)
    out = {"file": path.split("/")[-1], "mode": mode, "failures": f, "warnings": w, "measured": info}
    print(json.dumps(out, indent=2, ensure_ascii=False))
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
