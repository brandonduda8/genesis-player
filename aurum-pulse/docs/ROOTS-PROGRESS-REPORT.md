# AURUM PULSE / AURUM ROOTS — Working Prototype Receipt

Date: 2026-10-23. Output status: **isolated prototype, not deployed**.

## Added in this iteration
- Four-step visual onboarding: welcome → 2–5 favorite artists → four taste moods → listening identity.
- Favorites stored in a browser-local, portable v2 profile; legacy v1 profiles migrate safely; blocking one artist removes them from auto-discovery seeds.
- Discover page artist pills, manual artist lookup, source-backed expandable artist family tree, transparent inspiration/relationship/similarity badges.
- `server/roots.py` read-only backend for MusicBrainz exact identity, linked Wikidata P737 claims and public ListenBrainz radio associations.
- Bounded non-redirecting outbound HTTPS to three allowlisted hosts, privacy-first explicit fetching, caching and MusicBrainz rate pacing.
- Text-only source links and metadata-only cards: no automatic downloads or fabricated music streaming.

## Evidence
- `node --test tests/engine.test.mjs` = **36 passed**.
- `python3 -m unittest discover -s tests -p 'test_*.py'` = **41 passed**.
- `python3 tests/render_smoke.py` = offline Chromium desktop/mobile script no JS page errors, first-run step and synthetic Roots data UI validated.
- Screenshots: `aurum-pulse-pick-artists.png` and `aurum-pulse-roots.png`. Synthetic Roots fixture was not included in screenshots.
- No live third-party metadata lookup or Android/streaming rights/quality proof run. No commercial API license verification; MusicBrainz non-commercial API use is free but commercial terms require separate review.

## Gates to reach 10/10
1. Recover canonical user-approved Gold Playlist and protect permanent exclusions from reappearing.
2. Independently validate the artist family tree against real upstream records and catch inaccurate/redundant artist identities.
3. Integrate consent-based cross-device save/sync in Genesis authentication and make user edits auditable.
4. Map discovery recordings to verifiably authorized high-quality playable sources or to honest 'find a legal source' actions.
5. Integrate native Aurum Android client and repair media3 DSP compile blocker #8, SSL taste-sync trust-all flaw, and catalog branch URL.
6. Prove mobile playback/reconnection/skip rate, recommendation acceptance, repeat listens and personal subjective listening satisfaction before claiming 10/10.

Read `docs/ROOTS.md` for external APIs, privacy and enterprise constraints.
