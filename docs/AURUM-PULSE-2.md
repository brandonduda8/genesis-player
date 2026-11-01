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

## 2.1: Offline Crate (approved direction, 2026-11-01)

The owner's history stays the only source of taste. The Crate asks, for each of the owner's songs: is THIS recording,
by THIS artist, legitimately available somewhere?

| Section | Meaning | Plays in |
|---|---|---|
| **Exact** | the same artist account, and the same core title (video dressing and "feat." credits removed) with the same version words | Aurum (Media3: EQ, bass, background) |
| **Alternate** | the same artist, but a different version (slowed, remix, live and so on), labelled with the difference. **Never auto-substituted.** | Aurum, only when picked explicitly |
| **Downloadable** | an Audius download enabled by the artist on a **verified** account, or an Internet Archive file under an explicit **Creative Commons / public-domain** licence | it saves to app-private storage |
| **Offline** | the saved files plus the music files already on the phone (music the owner owns) | Aurum, works offline |
| **Unavailable** | no legitimate copy found | the official YouTube player, or the YouTube Music hand-off |

**Rules:**
- **Same artist or nothing:** a song with the same title by anyone else is never offered.
- **Unverified same-name Audius accounts:** stream only, and labelled.
- **Archive items without an explicit licence:** ignored.

**In the Library:** songs with an exact or offline copy show **IN AURUM** or **OFFLINE**. Tapping one plays it in Aurum.
Every other song plays through the official YouTube player.

**Privacy:** matching runs on the phone, artists heaviest-first, and resumes where it stopped. The only data sent to the
APIs is an artist name; history never leaves the phone.

**Tests:**
- `CrateMatcherTest` (9, synthetic).
- `CrateContractTest`: live Audius and Archive with neutral queries; CI only, non-blocking.
- The emulator smoke test runs the real matcher on the 4 synthetic artists until it reads "Checked 4 of 4 artists".

## 2.2: Brand + offline evolution (WO-CLAUDE-AURUM-PULSE-BRAND-OFFLINE-002)

**Brand: "your living music vault"**
- **Palette:** molten gold `#F5B942`, ember `#FF7A2F`, obsidian `#0B0A0D`, charcoal, bronze labels, celestial violet
  (reserved). It's set in `Theme.kt`, so the older screens (Pulse Chamber, Sound Forge, mini-player) inherit it.
- **Type hierarchy:** heavy, tightened headlines; letter-spaced bronze overlines; gold emotional lines.
- **Kit:** `ui/mymusic/AurumBrand.kt` (`BrandHeader`, `GoldCard`, `Pill`, an animated `PulseMark`).
- **Names:**
  - Apollo (Intelligence) · Signal Archive · Crate (vault + Offline Sanctuary) · Pulse Chamber · Sound Forge.
  - Tab labels: Apollo · Archive · Crate · Chamber · Forge, with a crossfade between tabs.
- **Copy:**
  - The welcome screen: "Your sound. Turned alive.", then "Your music vault is ready."
  - Section lines: "Built from your signal." · "Music that remembers you." · "Your crate. Your pulse. Your world." ·
    "Offline when the world isn't."

**Offline**
- **Add music files:** the owner picks audio files they own. They're copied into app-private storage and play in
  Aurum (EQ, background, lock screen).
- **Sanctuary first:** saved permitted tracks, imported files and phone files. Everything there plays with no internet.
- **One honest status per song** (`SongStatus`, unit-tested): OFFLINE · CAN SAVE · ONLINE · EXACT · ALT VERSION ·
  YOUTUBE · NOT CHECKED. Badges appear on every row.
- **Rule tightened:** only the EXACT recording with permission can be saved. Alternates never enter the offline vault.
- **Live online/offline indicator.** Offline, a song that needs the internet says so instead of failing. The Archive's
  offline queue holds only what truly plays offline.

**Unchanged:** the Media3 engine (background, lock-screen controls, queue), the EQ, Apollo, the importer, the matching
rules, and the legal boundaries.

**Still needs work:**
- **The Pulse Chamber's own layout:** only renamed and recoloured here, not redesigned.
- **Celestial / "Golden Mode" theme switch:** not built.
- **Real-device check:** YouTube embeds, Audius streams, saving with EQ, and the lock screen with imported files.
- **Matching coverage:** for the owner's own artists. Their real numbers show on the phone.
