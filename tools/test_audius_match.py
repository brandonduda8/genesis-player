"""Tests for tools/audius_match.py: a fake Audius API, so this runs without network and without personal data."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audius_match as am  # noqa: E402


def fake_api(users, tracks, fail=()):
    calls = []

    def fetch(url):
        calls.append(url)
        if any(f in url for f in fail):
            raise OSError("network down")
        if "/users/search?" in url:
            q = am.urllib.parse.parse_qs(am.urllib.parse.urlsplit(url).query)["query"][0]
            return {"data": users.get(q, [])}
        uid = url.split("/users/")[1].split("/")[0]
        return {"data": tracks.get(uid, [])}
    fetch.calls = calls
    return fetch


class AudiusMatch(unittest.TestCase):
    def test_only_the_artists_own_verified_account_is_taken(self):
        users = {"Witchouse40k": [
            {"id": "fan1", "name": "witchouse 40k", "handle": "fanreuploads", "is_verified": False, "follower_count": 900},
            {"id": "real", "name": "Witchouse 40k", "handle": "witchouse40k", "is_verified": True, "follower_count": 50}]}
        tracks = {"real": [{"id": "T1", "title": "Ghost", "user": {"name": "Witchouse 40k"},
                            "artwork": {"480x480": "https://a/480.jpg"}, "is_streamable": True},
                           {"id": "T2", "title": "Private", "user": {"name": "Witchouse 40k"}, "is_streamable": False}]}
        f = fake_api(users, tracks)
        r = am.match_artist("Witchouse40k", f)
        self.assertEqual((r["status"], r["account"]), ("OFFICIAL", "witchouse40k"))
        self.assertEqual([t["id"] for t in r["tracks"]], ["T1"])  # non-streamable dropped
        self.assertEqual(r["tracks"][0]["stream_url"], "https://api.audius.co/v1/tracks/T1/stream?app_name=aurum_player")
        self.assertFalse(any("/users/fan1/" in c for c in f.calls))  # the fan account's tracks are never fetched

    def test_unverified_same_name_needs_review_and_takes_no_tracks(self):
        users = {"Sample Artist": [{"id": "u", "name": "Sample Artist", "handle": "kk", "is_verified": False, "track_count": 40}]}
        r = am.match_artist("Sample Artist", fake_api(users, {"u": [{"id": "X"}]}))
        self.assertEqual((r["status"], r["tracks"]), ("NEEDS_REVIEW", []))
        self.assertEqual(r["unverified_same_name"], [{"handle": "kk", "track_count": 40}])

    def test_different_names_never_match_and_errors_are_reported(self):
        users = {"mgk": [{"id": "z", "name": "mgk fan page", "is_verified": True}]}
        self.assertEqual(am.match_artist("mgk", fake_api(users, {}))["status"], "NOT_ON_AUDIUS")
        err = am.match_artist("Anyone", fake_api({}, {}, fail=("/users/search",)))
        self.assertEqual(err["status"], "ERROR")
        self.assertIn("network down", err["error"])

    def test_run_summarises_and_is_polite(self):
        prof = {"artists": [{"rank": 1, "artist": "A", "plays": 9}, {"rank": 2, "artist": "B", "plays": 3}]}
        slept = []
        out = am.run(prof, top=2, fetch=fake_api({}, {}), delay_s=0.5, sleep=slept.append)
        self.assertEqual(out["status_counts"], {"NOT_ON_AUDIUS": 2})
        self.assertEqual(slept, [0.5])  # one pause between two artists, none before the first


if __name__ == "__main__":
    unittest.main()
