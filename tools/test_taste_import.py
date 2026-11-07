"""Tests for tools/taste_import.py: synthetic fixtures only, so no personal data lives in this public repo."""
import os
import sys
import unittest
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import taste_import as ti  # noqa: E402


def play(title, artist, when, header="YouTube Music"):
    return {"header": header, "title": f"Watched {title}", "titleUrl": "https://music.youtube.com/watch?v=x",
            "subtitles": [{"name": f"{artist} - Topic", "url": "https://www.youtube.com/channel/y"}], "time": when}


NOW = datetime(2026, 10, 31, tzinfo=timezone.utc)


class TasteImport(unittest.TestCase):
    def test_keys_ignore_case_spaces_accents_and_topic_suffix(self):
        self.assertEqual(ti.key("Witchouse40k"), ti.key("Witchouse 40k"))
        self.assertEqual(ti.key("Beyoncé - Topic"), ti.key("beyonce"))
        self.assertEqual(ti.key("$ample $tar"), "$ample$tar")
        self.assertNotEqual(ti.key("Some Star"), ti.key("Some Star 2"))

    def test_only_youtube_music_entries_count_and_nothing_private_is_kept(self):
        hist = [play("Song A", "Artist One", "2026-10-30T10:00:00Z"),
                play("Song A", "Artist One", "2026-10-29T10:00:00Z"),
                play("Cat video", "SomeChannel", "2026-10-29T10:00:00Z", header="YouTube")]
        p = ti.build_profile(hist, now=NOW)
        self.assertEqual((p["music_plays"], p["distinct_artists"], p["distinct_songs"]), (2, 1, 1))
        blob = str(p)
        self.assertNotIn("watch?v=", blob)          # no URLs
        self.assertNotIn("SomeChannel", blob)       # non-music viewing is never read
        self.assertNotIn("2026-10-29T10", blob)     # no per-play timestamps

    def test_recent_plays_outrank_old_plays_at_equal_counts(self):
        hist = [play("x", "Spring Artist", "2026-04-01T00:00:00Z")] * 3 + \
               [play("y", "Autumn Artist", "2026-10-25T00:00:00Z")] * 3
        p = ti.build_profile(hist, now=NOW)
        self.assertEqual([a["artist"] for a in p["artists"]], ["Autumn Artist", "Spring Artist"])
        self.assertAlmostEqual(p["artists"][1]["score"], 3 * 0.5 ** (213 / 90), places=1)

    def test_catalog_coverage_matches_across_spellings_and_features(self):
        hist = [play("a", "Witchouse40k", "2026-10-30T00:00:00Z")] * 4 + [play("b", "Nobody Here", "2026-10-30T00:00:00Z")]
        cat = {"tracks": [{"artist": "Witchouse 40k & Somebody"}, {"artist": "Other (feat. Witchouse 40k)"}]}
        p = ti.build_profile(hist, cat, now=NOW)
        self.assertEqual(p["catalog_coverage"]["plays_by_artists_in_catalog"], 4)
        self.assertEqual(p["catalog_coverage"]["share"], 0.8)
        self.assertEqual(p["artists"][0]["in_catalog_tracks"], 2)


if __name__ == "__main__":
    unittest.main()
