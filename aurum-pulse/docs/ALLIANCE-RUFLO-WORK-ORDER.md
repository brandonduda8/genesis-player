# WO-AURUM-PULSE-011 — Alliance + Ruflo Swarm Convergence

**Owner intent:** unify every existing Genesis/Aurum music implementation in ONE professional music service. **Canonical engineering scope** follows `brandonduda8/genesis-player/issues/9`. **Budget:** $0 incremental. **Status:** STAGED ENGINEERING PACKAGE; recipient acknowledgment not yet established.

## Directives
1. **Claude/Forge (lead):** source/branch inventory of Android + Aurum rebuild, unblock Media3 Kotlin issue #8, preserve current foreground playback, repair catalog/main URL, static For You, secure TasteSync, improve the original app using this design as a *companion*, not a fork. Create only an isolated draft PR with logs and build evidence.
2. **Zane/SRE:** verify actual host RAM, CPU, disk, music ownership and Navidrome resource suitability; recommend private TLS and backup/restore; never cohost AudioMuse model on memory-constrained 4 GiB OpenClaw container; separate studio analytics from streamer. Report authenticated operator identity, not GitHub author name alone.
3. **Aadity/Music Council:** source the exact previously approved redo playlist; evaluate quality and musical selection with owner review. No 32-track/443-track substitution, no junk autofill. Propose 10 audition-ready candidate discoveries outside Gold with track and source proof.
4. **Muse/Apollo (curation):** compare current genre/mood/artist-only rules to consented listening signals and explainable candidate ranking. Enforce global negative blocks, no fabricated trend scores.
5. **Argon/Research:** OSS/license comparisons (Navidrome, MusicBrainz, ListenBrainz, AudioMuse, beets, FFmpeg, Chromaprint). Do not copy TIDAL code, bypass paywalls, scrape protected music, or assume API entitlement.
6. **Guardian:** independent TLS/fidelity/streaming-secret threat model, SSP/MCP permissions, rights, resource limits, rollback. Full host shell privilege in OpenClaw is NOT a music-service permission.
7. **Astra/QA:** pin exact SHA, rerun tests, inspect UI access and phone audio, present `ACCEPT/REVISE/NO-GO` with named reproduced findings.

## Ruflo adapter: proposed 7 logical job profiles, NOT 7 unbounded processes

aurum.architect, aurum.android-dsp, aurum.streaming, aurum.discovery, aurum.brand-ux, aurum.security, aurum.qa. Each job requires `mission_id`, `source_commit`, `branch`, `scope`, `memory_budget`, `permissions`, `output_artifact`, `tests`, `cost_cap=0`, `retries<=1`, cancellation and source-cited receipt. Use one canonical Genesis mission ledger. Execute in isolated branches/containers, one heavy worker on 4 GiB nodes; roles may be sequential and do not imply extra running agents.

### Pilot jobs (first actual action after an agent ACK and verified access)
- **A1** Android compile issue #8: independently reproduce and repair API mismatch; no live merge.
- **A2** canonical Gold recovery: produce source-linked exact owner-approved ordered list or BLOCKED_SOURCE.
- **A3** provider compatibility: Navidrome OpenSubsonic mock and phased live security test with actual owner sign-in, after approval.
- **A4** feedback gate: 0 unwanted/resurfaced songs in static fixtures+runtime refresh.
- **A5** 10-song recommendation candidates with actual track names, authorization and source availability; approval queue only.
- **A6** asset branding/onboarding: 3-stage Android experience and resilient privacy/quality controls.
- **A7** independent security/QA verdict, reproducible full tests and measured phone playback.

## Permissions and communications
Present the full work order in issue #9 and explicit recipient mailboxes where available. **Do not impersonate recipient ACK**; a GitHub write alone doesn't wake Claude or Zane. No paid services, deployments, installs, publishing, credential operations or broad shell grants without owner action. Ask for evidence that Ruflo is actually installed and running before dispatch; do not fake swarm consensus.

## Completion bar
One phone install accepted by owner, native player+web/service synchronized only through authenticated APIs, actual Navidrome playback or clearly blocked, explainable approved Gold, quality badges truly measured, no filler, no broken playlists, tested mobile audio route, credential security, backup/rollback, and one independent repeatable QA report. Call 10/10 only when measured quality & satisfaction gates actually pass.


## New owner requirement — AURUM ROOTS (2026-10-23)
The user clarified the core product: **pick a few favorite artists at onboarding and discover the artists who influenced them, their collaborations, roots, descendants and surprising adjacent sounds**, with practical access to good music even when a streaming subscription lapses. This is more important than generic genre cards.

**Claude / Ruflo coding specialist:** review the real `aurum-pulse/web` favorite-artist onboarding and `server/roots.py` source relationship adapter. Fix real defects in a narrow draft patch, add identity disambiguation and CI tests, and preserve the existing Android Aurum Media3/DSP repairs in issue #8.
**Zane / data engineer:** independently confirm MusicBrainz API/Wikidata P737/ListenBrainz radio rate limits, entitlements, caching, legal source search and server allocations. Do not assume any streaming catalog is free.
**Aadity / music researcher:** curate an owner-reviewable positive seed list; identify roots and successors through verifiable artist sources, and choose ten top candidate songs each with a reason and legal listen path. No auto-append to Gold.
**Muse / Apollo / curator:** design progressive taste learning that uses repeated skips, favorites, manual exclusions, and artist graph hops; disclose recommendation uncertainty.
**Guardian + Astra / QA:** inspect SSRF/CSP/HTTPS, privacy, commercial licensing and false-influence assertions; use synthetic fixture tests, independently verify one real pilot family tree before claiming accuracy.
**Swarm/Ruflo caution:** coordinate via existing Genesis mission contract; tasks staged in a GitHub draft PR are not evidence an agent received or executed them. No installs, deployments, merges, spend or extra host permissions authorized.
