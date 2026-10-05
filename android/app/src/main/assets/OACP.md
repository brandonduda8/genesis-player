# AURUM — OACP semantic context

AURUM is Brandon's personal music player (package `com.apexforge.genesisplayer`).
It plays a local catalog of ~444 tracks (emo-rap / trap-rock lane: Lil Peep,
Caskey, Doobie, Pouya, Terror Reid, and more) with a Deep Space themed UI.

## How to talk about playback

- "play" with NO song/artist name → `playback_resume` (continue paused music).
- "play <name>" where <name> is a song title or artist → `playback_play_query`
  with `query` = the full name as spoken. Keep word order; do not strip
  artist/title words. Examples:
  - "play Star Shopping" → query "Star Shopping"
  - "play Lil Peep" → query "Lil Peep" (plays the best-matching Peep track)
  - "play some Caskey" → query "Caskey"
- "pause" / "stop the music" → `playback_pause`. Never treat pause as destructive.
- "next" / "skip" → `playback_next`. "previous" / "go back" → `playback_previous`.
- "what's playing" / "what song is this" → `playback_now_playing`.

## Catalog notes

Matching is case-insensitive against track title first, then artist.
If several tracks match an artist query, the best title match wins and the
result message names the exact track chosen.

## Result callbacks

Capabilities with `async_result` report back on the broadcast
`org.oacp.ACTION_RESULT`. The broadcast carries:

- `org.oacp.extra.REQUEST_ID` — echoes your request id
- `org.oacp.extra.RESULT` — the full v0.3 result envelope as a JSON string:
  `{"requestId","status":"completed|failed","capabilityId","message","result":{...}}`
- Flat extras `requestId`, `status`, `capabilityId`, `message` for convenience.

`playback_now_playing` result object: `{"title","artist","is_playing"}`.
`playback_play_query` result object: `{"track_id","title","artist"}`.
On failure: `status` is `failed` and the envelope has
`error: {"code","message","retryable"}`. Known codes: `not_found`
(no catalog match for the query), `internal_error`.

## Safety

All six capabilities are `confirmation: never`, `sensitivity: low`:
playback control changes nothing outside the music session.
There is no purchase, delete, publish, or account surface exposed via OACP.
