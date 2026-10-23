# AURUM PULSE — enterprise-grade release acceptance (proposed)

A prototype having a vibrant interface is NOT production/enterprise acceptance. This release is **NOT DEPLOYED**.

## G0 — Asset truth
- Preserve original Android and catalog files, exact source SHAs, usable backups, restore rehearsal.
- Recover exact owner-approved complete-redo Gold playlist with human verification, and keep legacy catalog from silently rejoining.
- Security triage: repair TLS trust-all bug; evaluate SoundCloud resolver ToS/API; keep music rights provenance separate from metadata.

## G1 — Native Android player and sound
- Fix [issue #8](https://github.com/brandonduda8/genesis-player/issues/8) Kotlin Media3 custom AudioProcessor compile failures.
- CI: assemble APK, JVM+instrumented Media3 playback/seek/queue/dislike tests, emulator background/lockscreen, route changes, notifications, audio focus, headset/BT output, screen off.
- DSP: finite stable coefficients; peak/clipping checks; A/B bypass, volume matching, speaker limits; at least one owner phone/headphone listening acceptance. Do not fake Hi-Res from upscaled lossy.

## G2 — Streaming and catalog
- Navidrome deployed only after host resource, storage, persistence, license, auth and restore checklist; require private TLS/OIDC per real remote user, not dev loopback HTTP server.
- Full OpenSubsonic compatibility/integration tests with read-only music mount, seek/range/interrupt recovery, time-to-first-audio, status truth.
- Providers must be connected by approved legal access; never bypass DRM/streaming limitations; backend credentials never returned to clients.
- Quality labels measured from allowed codec metadata and/or original media, with honest UNKNOWN fallback.

## G3 — Taste and discovery
- 0 known rejected tracks in Gold, updates, queue, playlists, imports, backups or migration reintroduction. Positive/negative tastes survive restarts and track ID changes.
- AURUM Gold is always *owner approved*. Discovery suggestions have separate PENDING/REVIEW/ACCEPTED/DENIED states.
- Factual provenance on recommendations, 10-source sampling per lane, measured owner accept/skip/long-listen feedback.
- No invented trend positions, premium catalogs, tracks, stream success or copyright access.

## G4 — Trust, accessibility, operations
- Actual Genesis authentication, owner/user roles, audit logs, request size caps, CSP and CSRF/fetch protections; secure TLS and secret vault.
- Accessibility at WCAG 2.2 AA target, reduced motion and screen reader, responsive mobile tests, reconnection after phone lock and network switch.
- Versioned API contracts, source-pinned dependencies/OSS license review, canary rollback, backup restore testing, data export/delete/consent.
- Track reported vs measured quality, CPU/RAM/network at target host, p50/p95 startup/audio-start, underruns and real cost.

## Status at foundation v0.1
- **PASSED:** 27 JS pure-engine tests; 26 Python local/adapter tests; inline Chromium rendering smoke for welcome/mobile/Sound Lab without page exceptions.
- **NOT VERIFIED:** HTTP-network Chromium e2e due to sandbox navigation policy; real user phone sound, production server, Navidrome auth+playback, legal stream access, old DSP branch build, private login, audio quality measurement, native onboarding, Ruflo agent execution.
- **RELEASE VERDICT: NO-GO production.** Next stage requires G0 and Android G1 before announcing 10/10.


## Artist-first onboarding and Aurum Roots additional gates (2026-10-23)
- [x] Four-step browser onboarding; 2–5 favorite artists; editable mood selection and profile export; synthetically tested DOM flow.
- [x] Source-specific `documented-influence`, `documented-relationship`, `algorithmic-similar` provenance; no unverified claims of musical inspiration or guaranteed playback.
- [x] Backend uses strict HTTPS public metadata host allowlist, explicit user-triggered requests, bounded responses, no redirect, cache, MusicBrainz pacing, structured errors.
- [x] Offline mocked metadata tests plus mobile/desktop offline Chromium smoke (no JavaScript errors).
- [ ] Real third-party MusicBrainz + Wikidata + ListenBrainz data integration verified on deployment-approved host with rate-limit/terms review.
- [ ] Independent authenticated threat/privacy review, commercial API rights, cross-device sync and identity disambiguation.
- [ ] Authorized catalog and audio entitlements integrated; real phone audition/discovery metrics and user acceptance.
- [ ] Native Android Aurum updated with same onboarding and Roots feature; not installed or tested on user's phone.

**No source-data relationship or local fixture test establishes artist inspiration as objectively true, or establishes licensed playback.**
