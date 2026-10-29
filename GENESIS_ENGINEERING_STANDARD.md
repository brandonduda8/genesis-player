# Genesis Engineering Standard — governed intelligence (v1)

**Scope:** Every Genesis/Phoenix/Aurum worker, operator, service, model, UI, and code change. This document defines a proposed cross-project engineering policy; it is not proof that other repositories have adopted it.

## Prime rule
Advanced is **correct, secure, understandable, testable and useful**, not merely large. No invented integrations, agent acknowledgments, financial outcomes, device tests, quality readings, or completion claims. Prefer a small measured system over an elaborate unverified one.

## Required development contract
1. **Source-of-truth first:** inspect existing code, current SHA, tests, platform versions, operational constraints, and upstream interfaces before changing anything.
2. **Architecture:** separate policy, data access, orchestration, UI and side effects. Each component has a bounded contract, failure behavior and owner.
3. **Safety:** no trust-all TLS, secret logging, unapproved account access, silent data deletion, irreversible changes, illegal content acquisition, or destructive migrations. Risky actions require explicit, scoped human authorization.
4. **Intelligence:** prefer evidence-linked signals and measurable improvements. Decision outputs must identify inputs, score/reasons, evidence state (VERIFIED/UNVERIFIED/UNKNOWN) and confidence limits. Never let a model bypass a hard rule.
5. **Reliable execution:** bounded memory/network usage, idempotent commands, timeouts, retries with budgets, backpressure, cancellation, atomic updates, rollback, and audit receipts as appropriate.
6. **Testing:** pure unit + adversarial + regression tests; integration tests against real dependencies where available; real-device/hardware tests where required. Report what ran, what did not, and why.
7. **Security and privacy:** data minimization, input validation, trusted TLS, least privileges, credentials from proper secret storage, explicit consent for cross-device sync; no raw secrets in issues/PRs.
8. **Performance:** measure latency, memory, CPU, cost, and scalability before claiming gains. Respect zero-incremental-spend requirement.
9. **UX/accessibility:** mobile-first, graceful empty/error states, accessible navigation and contrast, fast feedback; do not invent functionality in UI.
10. **Release governance:** commit to isolated branch, preserve main and user data, CI first, independent review, owner approval for deploy/merge where required, real acceptance evidence. **No GO from green unit tests alone.**

## Minimum delivery receipt
Source revision and branch; actual changed files; tests with commands and outcomes; security/performance impacts; regressions/unknowns; rollback path; approver and decision. Status values: IMPLEMENTED, TESTED, DEVICE_VERIFIED, BLOCKED, NOT_TESTED. Only claim statuses backed by receipts.

## Aurum-specific nonnegotiables
Original Gold playlist must be sourced and owner-approved, separate from discovery. Permanent dislikes outrank model scores; unknown rights and audio quality must stay explicitly unknown. DSP requires objective measurements and owner phone listening evidence. Playback must use authorized sources, survive network changes, and satisfy actual Android CI. A prototype or successful unit tests are never a 10/10 acceptance.

## Quality review checklist
- [ ] Real need and measurable acceptance defined
- [ ] Threat model, permission and privacy boundaries checked
- [ ] API contracts and migrations reviewed
- [ ] Tests include failure, corrupt data, boundary and replay cases
- [ ] Baseline and result verified independently
- [ ] Observability and rollback available
- [ ] No real-device/production success claimed without proof
