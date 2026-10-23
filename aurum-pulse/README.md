# AURUM PULSE by Genesis

**Find your frequency.** A vibrant, first-class Genesis music-service foundation that builds on the existing Aurum Android player, Apollo taste engine, Genesis Catalog, and an optional self-hosted Navidrome library.

**Status: LOCAL PROTOTYPE / NOT DEPLOYED / NOT CONNECTED TO PRODUCTION.** Designed for a controlled Phase-1 pilot. No streaming subscriptions, unauthorized music downloads, live swarms, or paid tools.

## What works in this package
- Beautiful branded listening room and 4-step onboarding (welcome, 2–5 favorite artists, music moods, personal profile).
- Actual local-file playback with seek, play/pause, next/previous, browser Media Session controls, and volume.
- Web Audio low-shelf bass, peaking presence, high-shelf clarity, limiter-style dynamics compressor, A/B bypass and live spectrum, scoped to this browser player's audio.
- Persistent local taste profile, likes, track/artist blocking across title/case variants, profile export and reset. Original music files never changed or uploaded.
- Honest quality labels: local playback **does not mean verified lossless**. Original Gold playlist remains empty pending owner-approved tracklist recovery.
- Loopback-only Python stdlib service with health contract and **optional** Navidrome/OpenSubsonic read-only search and same-origin stream proxy; credentials stay server-side. It is intentionally not deployed.
- **Aurum Roots**: choose 2–5 artists on welcome, follow Wikidata-documented influences, MusicBrainz relationships, and ListenBrainz related-listening associations with explicit evidence and honest no-playback labels. Actual upstream availability is not yet live-verified.
- Discovery modes with **metadata-only artist ideas**. No fake playable tracks, secret API keys or reintroduced rejected giant catalogs.

## Local preview (no new packages required)

```bash
cd aurum-pulse-foundation
python3 server/app.py
# On the SAME computer only: http://127.0.0.1:8787/
```

Use the browser on that computer. **Do not bind to 0.0.0.0 or forward it to the internet.** This developer preview has no owner login and MUST be protected behind Genesis authentication before any remote deployment.

## Optional authorized Navidrome backend (NOT REQUIRED)

A real, separately approved Navidrome server must already exist, with a music library whose distribution and playback you are authorized to access. Set the following in the private process environment on the host, not GitHub or a frontend config:

- `AURUM_NAVIDROME_URL` = `https://your-private-navidrome.example` (or local `http://127.0.0.1:4533`)
- `AURUM_NAVIDROME_USER` = owner-approved read-only library account
- `AURUM_NAVIDROME_PASSWORD` = secret from a protected owner-managed store

Restart this **development** preview only after approving local use and verifying the server's identity. In *Services*, enter a search term; results are playable through a backend proxy and labeled **quality unknown**. No claims about live Navidrome were tested here. Do not copy this configuration into production without SSO, CSRF/cross-origin and session hardening.

## Tests

```bash
node --test tests/engine.test.mjs
python3 -m unittest discover -s tests -p 'test_*.py' -v
# Optional: python3 tests/render_smoke.py (needs Playwright+Chromium, offline rendering)
```

41 Python + 36 JavaScript tests pass after Roots feature addition (77 total). A synthetic Roots result is also rendered in browser smoke; no live artist lineage response is claimed. The browser inline layout check ran without page exceptions, but the browser's network sandbox blocked an end-to-end HTTP navigation probe. No hardware DAC/Android phone or live Navidrome/audio quality validation is implied.

## Product architecture

- **AURUM Gold** = only owner-approved canonical redo playlist; currently LOCKED until the source list is recovered.
- **AURUM Library** = authorized owned files/Navidrome; separate from curated Gold.
- **AURUM Roots** = artist-first onboarding, evidence-backed influenced-by connections, collaborators, similarity branches; metadata only, never auto-played.
- **AURUM Discover / Apollo** = candidate recommendations, never auto-published.
- **AURUM Sound Lab** = native Android DSP + scoped Web Audio EQ.
- **AURUM Connect** = capability-gated Navidrome, MusicBrainz/ListenBrainz, Audius/SoundCloud only where permitted, future Genesis MCP.
- **Genesis Command Center** = policy, user identities, jobs, updates and approvals; this prototype is not a second identity or mission ledger.

Read `docs/ROOTS.md`, `docs/INVENTORY.md`, `docs/BRAND.md`, `docs/ALLIANCE-RUFLO-WORK-ORDER.md`, and `docs/RELEASE-GATES.md` before merging or deploying.
