# AURUM — verified source/design inventory (2026-10-22)

Inventory documents *code seen*, not a claimed active service. Baseline repos: `brandonduda8/genesis-player`, `brandonduda8/genesis-catalog`, `brandonduda8/Agent-x`, `brandonduda8/astra-zane-bridge`.

| Existing work | Where | Decision | Proof or problem |
|---|---|---|---|
| Aurum / Genesis Player native Android | `genesis-player/main` | KEEP as Android playback engine | Media3 ExoPlayer, MediaSession foreground service, notification/lock-screen, queue and source adapters |
| BRKN Vibes / Golden player | `genesis-player` theming/UI | REBRAND under AURUM PULSE | Conflicting labels, stale themes; use one clear app name |
| Apollo discovery & decisions | `data/ApolloClient.kt`, `ApolloDrops.kt`, `ApolloStore.kt` | ADAPT to AURUM Discover | Separate approver queue, non-execution metadata, count/quality verification |
| Local taste sync | `data/RatingsStore.kt` | SECURITY REPAIR FIRST | Trust-all X509TrustManager, must revalidate real TLS identity |
| Audius streaming | player bundled catalog; 381 candidate Audius URLs | PRESERVE authorized adapter | Link existence != playback, fidelity, or track approval |
| SoundCloud resolution | `data/SoundCloudResolver.kt` 62 bundled, remote 29 | REVIEW BEFORE ACTIVATION | Scrapes rotating web client_id; compliance and reliability unknown |
| Apollo's For You | `data/Library.kt` | FIX | Bundled For You remains static after remote library updates |
| Taste/energy classifier | `data/Energy.kt`, `PlaybackQueue.kt` | REPLACE RULES | 440/443 bundled tracks have missing genre & mood. Simple artist/genre rank not adequate |
| EQ / SoundMax / rage-max | `genesis-player/aurum-rebuild@77c378c` | FIX BUILD, TEST DEVICE | [issue #8](https://github.com/brandonduda8/genesis-player/issues/8) documents Media3 compile failures. No claimed passing phone audio |
| Remote catalog | `genesis-catalog/main@29f3186` | REBUILD schema + gate | `catalog.json` v12=32 tracks, 29 SoundCloud, 29 missing genre; NOT owner-approved redo playlist |
| Embedded catalog | `genesis-player/main/android/app/src/main/assets/tracks.json` | LEGACY QUARANTINE | 443 tracks, 440 missing genre/mood; user explicitly rejects old huge list |
| Original approved redo playlist | Previous user-approved source needed | **BLOCKED_SOURCE_RECOVERY** | Do NOT guess from old catalog or randomly replace it |
| Navidrome OpenSubsonic | OSS external candidate | ADD as approved private-owned-file server | Lightweight personal streaming, not licensed Tidal/Spotify catalog; no server installed by this package |
| ListenBrainz/MusicBrainz | OSS services | DISCOVERY METADATA PILOT | Need user consent, provenance, rate-limits, actual playable-source match |
| AudioMuse-AI | external OSS analysis | DEFER separate qualified host | ~8GB RAM, 4 CPU and storage suggested; not in 4GB OpenClaw cgroup |
| Genesis mission/A2A | `astra-zane-bridge` + `Agent-x` | REUSE | Do not create competing state DB. Agent mailbox not verified live caller identity |
| Ruflo swarm | requested alliance orchestrator | OPTIONAL / VERIFY INSTALLED | No proof this current environment controls a real Ruflo worker. Prepare jobs, no fake task-run receipts |

**Important:** The `genesis-player/main` remote URLs still reference `genesis-catalog/master`, while catalog default branch is `main`; `aurum-rebuild` contains a candidate correction not verified live on owner's phone. The current source catalog version must be checked from actual running APK before any data migration. No raw audio, streaming rights, authenticated service, actual codec quality or production DSP results have been inspected.
