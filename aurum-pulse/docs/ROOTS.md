# AURUM ROOTS — first-run artist identity and honest sonic ancestry

**Status:** prototype implemented in Aurum Pulse preview; third-party metadata adapter and offline fixture tests written. **No production/API quota/streaming entitlement/device playback verified**. Gold remains locked until owner-approved tracklist is recovered.

## What a listener experiences
1. Welcome — authentic music identity, no account or subscription requirement for local taste preferences.
2. Pick **2–5 favorite artists** from supplied suggestions or enter custom names; no automatic network calls occur during onboarding.
3. Choose moods (get money, rage/dark, sad boy, underground).
4. Name the profile and open Discover. Saved artist seeds appear as clickable pills with an editable sound identity.

The first user gesture on **Trace roots** fetches source-linked artist metadata. An artist's page separates **documented influence** (Wikidata P737 community claims), **documented artistic relationships** (MusicBrainz), and **similar music according to listening data** (ListenBrainz LB Radio). Similarity is never relabeled as inspiration. Every result shows where its data came from and allows following another artist — a controlled, user-driven multi-level exploration.

## Artist identity / reliability
- Rejects ambiguous matches that are not an exact normalized display name; never substitute a random artist if stage names are unknown.
- Do not claim verification of unsupported influence ties. Wikidata's P737 is community-contributed and may be missing citation references, so labels must say **source-documented**, not independently proven.
- If a source is unavailable, mark that source partial/failed instead of manufacturing a graph.
- All artist objects are **METADATA_ONLY, playable=false**. A connected/owned and legally playable stream or audio file is a separate entitlement flow.
- URLs emitted by the server are fixed public-source HTTPS links. Frontend source links are allowlisted and use `rel=noopener noreferrer`.
- User data is in local browser storage initially. Export JSON provides portability. A future Genesis identity sync requires opt-in, authentication, and data deletion controls. No history is uploaded automatically.

## Server routes
- `GET /api/roots/artist?q=<name>`: validates 2–80-character name, searches MusicBrainz for exact-name identity, fetches artist tags/relations, optionally reads linked Wikidata P737 statements, and optionally reads ListenBrainz artist radio metadata.
- Requests use a fixed allowlist: `musicbrainz.org`, `www.wikidata.org`, `api.listenbrainz.org` — no arbitrary proxy URL or redirects. Bounded responses, timeout, MusicBrainz pacing (~1.1 s between uncached queries), ephemeral cache, no raw names logged.
- Success: `SOURCE_LINKED_RESULTS` with typed `connections[]`; no exact identity: `ARTIST_NOT_CONFIRMED`; missing graph: `NO_DOCUMENTED_RELATIONSHIPS`; upstream unavailable: HTTP 503 without leaking details.
- Local preview is loopback-only and has **NO remote-owner authentication**. Never expose it beyond local machine before Genesis SSO and privacy review.

## Enterprise/commercial gates
- MusicBrainz states non-commercial API use is free, commercial usage requires review of pricing/terms. Validate licenses/quotas before any commercial public deployment; migrate to approved dumps/hosted provider as needed.
- ListenBrainz recommended models / public endpoints are best-effort, not availability guarantees.
- Artist metadata is not audio rights. Do not scrape streaming sites, copy protected recommendation algorithms, rip audio, bypass DRM or promise access to music after a subscription expires.
- Durable discovery history and playlists can be owned independent of streaming credentials; offline audio playback requires locally owned/licensed media.

## Next engineering waves (all reviewable)
1. Human-reviewable identity disambiguation by location, alias, MusicBrainz UUID and official links for ambiguous artists.
2. Searchable artist graph with saved branches, automatic duplicate suppression, genre and era travel, and credible source weighting.
3. Personalized ranked tracks: authorized source available + measurable audio quality + user saves/skip history + influence/sonic paths; never auto-import into protected Gold.
4. Separate **Discovery Vault** and **Play Now** with a legal source capability matrix: local files, Navidrome, authorized Audius public streams, user-held subscriptions (if enabled).
5. Mobile/Android first-run and background playback validation, artist exclusion sync, provenance A/B evaluation and security gates.

## Official upstream documentation
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API
- MusicBrainz relationships: https://musicbrainz.org/relationships/artist-artist
- Wikidata P737: https://www.wikidata.org/wiki/Property:P737
- ListenBrainz LB Radio: https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
