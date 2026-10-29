# Aurum Pulse: curation and taste hardening, 2026-10-29

**State: IMPLEMENTED / LOCAL_TESTED; NOT DEPLOYED; NOT DEVICE_VERIFIED.**
Base: `f13a61e8ca2c663d37c83fd0fc3dc1c9cbe810f8`, existing draft PR #13.
This is the browser Pulse policy layer, not a native Android DSP patch or a completed library rebuild.

## Reproduced defects and changes
- The previous Gold lane admitted ownerApproved/rightsVerified/playable flags without measured-quality evidence. Gold now needs matching source-bound quality, rights and owner-approval references AND an exact ordered manifest. Missing, denied or substituted entries block the complete Gold output. Ranking never invents, reorders or fills the canonical playlist.
- An unplayable first duplicate could suppress a later playable copy. Eligibility is evaluated before deduplication; a rights denial for the same source overrides conflicting assertions.
- Replayed feedback could inflate track/artist scores. Optional event IDs are idempotent inside the retained window; identical legacy events deduplicate; Like/Unlike are latest explicit preference state, not additive button-tap counts. Engagement has deterministic 30-day half-life decay against an explicit asOf clock or the latest retained event.
- Unknown lanes, zero limits and malformed deny policies were permissive or unstable. Invalid policy fails closed; oversize catalogs/deny lists report a specific status rather than silently omitting entries.
- safeProfile previously retained only the last 3,000 rejects, allowing older rejects to return after reload. Valid rejects are retained up to the explicit 50,000-input bound; overflow/corrupt non-array lists lock playback until an explicit reset. Long valid identity keys are retained. Like-history limits remain separate.
- Block/favorite lookup sets are compiled once per evaluation, and diversity selection uses a Set rather than repeated linear membership scans.

## API and evidence contract
`rankCandidates(tracks, profile, history, options)` remains the browser queue entrypoint.
`evaluateCatalog(...)` additionally returns quarantine/rejection/scout reasons, ordered Gold output, manifest status and duplicate counts. Quarantine is non-destructive; originals are not deleted.

For a source `sourceId`, quality evidence is `{sourceId, status:'PASSED', reportRef, measuredAt}` and rights evidence is `{sourceId, status:'AUTHORIZED', reportRef, checkedAt}`. Corresponding explicit verification flags must also be true. Owner approval is `{sourceId, receiptRef}` plus ownerApproved. All references must match the selected source. Timestamps must be valid and no later than a supplied asOf time.

Gold manifest: `{schema:1, id, version, approvalRef, entries:[{key: songKey(artist,title), sourceId}]}`. Gold output is all-or-nothing and preserves the full manifest order; discovery limit/diversity settings do not trim it. A complete manifest match is labeled `MANIFEST_MATCHED_NOT_INDEPENDENTLY_VERIFIED`.

**Important:** this policy validates receipt structure and source binding only. It does not authenticate the approver, fetch reports, verify signatures, grant rights, measure audio or prove the receipts are true. Only trusted verified ingestion may provide these inputs. Evidence is labeled `EVIDENCE_ATTACHED`, never an independent verification claim. Fixtures are synthetic; no approved Brandon playlist was manufactured.

The local preview queue may include owner-selected files with unknown fidelity, but those files remain quarantined for Gold qualification. This is not a high-fidelity auto-DJ authorization.

## Actual local verification
Node v22.16.0. Original source files were reconstructed from connector-fetched text and verified against Git blob hashes before baseline execution. Network cloning was unavailable in this execution container.

- Original taste suite: 13/13 passed before hardening, despite reproduced defects.
- Final combined JavaScript suite: **103/103 passed**, zero skipped/failing (36 existing profile tests, 13 taste tests with one deliberately stricter Gold assertion, 54 new hardening tests).
- Targeted mutation checks: **5/5 detected**, after syntax-valid intentional regressions to receipts, owner blocks, event replay, old-reject retention and incomplete Gold publication. This is not an independent audit.
- Coverage: taste-intelligence.mjs 99.55% lines, 96.75% branches, 100% functions; engine.mjs 100% lines, 92.42% branches, 100% functions. Coverage is not a correctness proof.
- Syntax checks: changed JavaScript modules passed.
- Synthetic ranking benchmark, 7,500 records plus 5,000 unrelated rejects, three measured runs per version: original median 3300.81 ms; hardened median 118.63 ms, 27.82x ratio. Both returned 7,500 records. This is a sandbox CPU result, not a phone/browser/audio performance claim.

Reproduce current tests:
```sh
node --test --experimental-test-coverage aurum-pulse/tests/*.test.mjs
python3 aurum-pulse/tests/mutation_gate.py
```
The existing CI workflow now runs both commands plus app/module syntax checks. Local results above do not pre-claim CI completion.

## Open requirements and integration risks
- Exact owner-approved Gold playlist is still missing. No actual authorized source/quality/approval receipts were recovered here. Missing manifest means empty Gold, not an invented replacement.
- app.mjs still rebuilds learned queue order on advance. A stable playback-session queue, stale-play cancellation and measured played-time feedback remain required; ended events alone are not proof of a complete listen. The existing browser does not yet provide event IDs; legacy duplicate-tuple handling applies.
- Corrupt JSON before safeProfile is called, browser storage erasure, cross-device/reinstall restore and metadata aliases need separate persistence/recovery work. This patch does not claim universal permanent-reject durability.
- Quarantine diagnostics are available in the core API; a new quarantine UI has not been integrated or browser-tested here.
- Native Android DSP, real-phone audio quality, live authorized streaming, premium UI acceptance, playback/reconnection soak tests and independent review remain outstanding. Python server tests and live browser/phone E2E were not run in this patch.
- No deployment, merge, accounts, secrets, production data or audio files changed. No 10/10 release acceptance is implied.

Rollback: leave draft unmerged or revert this single hardening commit. Do not replace live user profiles with test fixtures.
