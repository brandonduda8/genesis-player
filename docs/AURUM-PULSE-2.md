# Aurum Pulse 2: the owner's music, from the owner's data

**Order:** Brandon's priority WO "Rebuild Aurum Pulse" (2026-11-01). **Builder:** Claude. **Verifier:** Zane.
**Certifier:** Brandon, on his phone. Grades: BUILT, and it stays BUILT, not DONE, until it plays on Brandon's device.

## What changed

| Before | Aurum Pulse 2 |
|---|---|
| A bundled `tracks.json` catalog (≈440 tracks from Audius and SoundCloud, picked for him), a remote catalog, "Fresh Signals" drops | **No catalog.** The library is the owner's own Google Takeout (YouTube + YouTube Music history and playlists), imported on the phone, plus the audio files on the phone. `tracks.json` is deleted, and RemoteCatalog and ApolloDrops are no longer called. |
| Apollo recommendations from a server | **Apollo v2 runs on the phone** (`data/mymusic/ApolloEngine.kt`). Its signals: recency heat (half-life 90 days); session co-play (plays less than 30 min apart, songs up to 3 apart, weight 1/distance) for "More like this"; hour of day; rediscovery (played 3+ times, quiet for 45+ days); artist essentials; the owner's real playlists; "searched, never played". It **learns** from likes, dislikes (never played by Apollo again), skips (under 30 s) and completed plays. It never adds a song the owner hasn't played. |
| History synced to the Apollo server | Ids from the new library (song keys and phone files) **never leave the phone** (`HistorySync` guard) |

## Import rules (`data/mymusic/Takeout.kt`, identical to the desktop audit)

- **Skipped:**
  - ads ("From Google Ads");
  - entries with no video id (visits, removed items);
  - entries with no channel (deleted or private videos);
  - regular YouTube that isn't music.
- **Music:** a play from the YouTube Music app, a "- Topic" or VEVO channel, or a video also played in YouTube Music.
- **One video id = one song:** a re-upload channel never splits a song. Topic, VEVO and plain channels merge into one
  artist with one display name (VEVO-derived names weigh 0.2). Titles lose video dressing such as "(Official Video)"
  and a leading "Artist - " prefix.
- **Re-import merges** a newer Takeout without double-counting: each (video, time) play is kept once.

## Playback, honestly

| Source | How it plays | Background | EQ / bass / visualizer |
|---|---|---|---|
| Songs from YouTube history | **The official YouTube IFrame Player API** in a visible player (YouTube's terms require it to be visible). Collapsing the player pauses it. | via **"Open in YouTube Music"** (the YouTube Music app, under the owner's own subscription) | **No.** YouTube's audio isn't available to apps, and extracting it would break YouTube's terms. |
| Audio files on the phone | the existing **Media3 engine** | **yes** | **yes**: 4-stage EQ, headphone AutoEq profiles, bass, Ember visualizer |

**Embedding:** some label videos refuse embedding (errors 101/150). The player says so, skips after 2.5 s, and offers
"Open in YouTube Music". No other track is ever substituted for it.

## Tests

- **JVM:** `MyMusicTest`, 7 tests on synthetic fixtures. The same importer, engine and codec were also run privately
  against the owner's real Takeout in the build session (results reported to him, not committed).
- **CI** (`.github/workflows/aurum-apk.yml`, `claude/**` pushes):
  1. unit tests;
  2. the debug APK (uploaded with its sha256);
  3. an emulator smoke test: install; import a **synthetic** 240-play Takeout through the production code; restart;
     the Apollo home shows "Play For You"; the Library tab shows the synthetic artists; no crash.
- **Not provable in CI:** YouTube playback on a real device and embed availability. That proof is Zane's device check
  and Brandon's.

## Not done (next)

- Genesis and StarNet link: the order asks for it only "where useful, without compromising playback or private data".
  It is deliberately **not** wired in 2.0. Proposal: an opt-in, anonymous "Now playing" tile for the StarNet app, sent
  over the comms fabric.
- Discovery of **new** artists beyond the history needs a legitimate source. Candidates: Audius verified-artist search
  (open API, full EQ path), or YouTube Data API search with the owner's own key in the vault (never in the APK).
- The old Crate / server-Apollo screens are still compiled for the old CI gate (`gate.yml` on main), but they aren't
  in the navigation.
