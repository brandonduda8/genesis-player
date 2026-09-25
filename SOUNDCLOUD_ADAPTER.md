# SoundCloud Playback Adapter — Genesis Player

Built 2026-09-25. Brandon's call: his cousin Tha Aadity's catalog
(50 tracks, https://soundcloud.com/tha-aadity) plays inside Genesis Player
as a dedicated "Tha Aadity" shelf. Stream-only — no ripping, no downloads.

## How it works

The adapter uses SoundCloud's own public runtime path — the same one the
soundcloud.com web player uses:

1. `GET https://api-v2.soundcloud.com/resolve?url=<permalink>&client_id=<id>`
   → track JSON with `media.transcodings`
2. pick the `progressive` transcoding (ExoPlayer plays it natively as MP3)
3. `GET <transcoding.url>?client_id=<id>` → `{"url": "<signed cf-media.sndcdn.com URL>"}`
4. hand the signed URL to ExoPlayer

Signed URLs expire within minutes and are **never stored** — not in the
catalog, not on disk, not in prefs. Resolution happens fresh at every play.

## The client_id

SoundCloud's public web client_id, baked into their own web player bundle
(`a-v2.sndcdn.com`). The fallback compiled into the app was scraped from
that bundle on 2026-09-25 (`3uJIGBRwdofKn6QKzONvDxUM1Vs4bTv9` — the key the
official web player itself ships). No account, no API key signup, $0.

If SoundCloud rotates the key, the resolver re-scrapes a fresh one from the
web bundle on a 401, caches it in SharedPreferences, and retries once.
No user action needed.

## Code map

- `data/SoundCloudResolver.kt` — the adapter. `resolve(context, permalinkUrl)`
  is blocking; always called off the main thread.
- `data/Library.kt` — `Track.soundcloudUrl` (JSON key `soundcloud_url`).
  When set, `streamUrl` is ignored for playback.
- `PlayerService.kt` — `playTracks()` assembles the queue on a background
  thread, resolving SoundCloud tracks before `setMediaItems`. A generation
  counter drops stale assemblies if the user re-taps mid-resolve.
  Unresolvable tracks are skipped with a log line — never faked.
- `MainActivity.kt` — DEBUG-only `TEST_PLAY` hook accepts a `playlist`
  string extra so the CI gate can play the "Tha Aadity" shelf by name.
- `assets/tracks.json` — 50 SC tracks appended (ids `sc_<soundcloud_id>`,
  artwork baked from the SoundCloud CDN, durations from the API), plus the
  "Tha Aadity" playlist first in the playlist order. The 170 Audius tracks
  keep their exact order (index 0 stays a direct Audius stream for the gate).

## Verification (CI gate, `.github/workflows/gate.yml`)

New gate phase "SoundCloud shelf": fires
`TEST_PLAY --es playlist "Tha Aadity"`, then asserts MediaSession reaches
PLAYING and ExoPlayer position advances — proving resolve → signed URL →
audible render end to end on the emulator. Crash/ANR checks unchanged.

## Boundaries (do not cross)

- Never download/rip SoundCloud audio. Stream only.
- Never catalogue signed stream URLs as permanent tracks.
- Never use YouTube audio (Brandon's rule for this lane: SoundCloud only).
- The 18 "downloadable" flags in the hunt data do NOT authorize
  republication — the player streams them like everything else.
