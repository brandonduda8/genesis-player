# AURUM Rebuild — Native Architecture

**Repo:** `~/workspace/genesis-player` · **Branch:** `aurum-rebuild` (cut from `dsp-v2`)
**Status:** DESIGN DOCUMENT — no code authorized by this file. Rebuild code lands only on Brandon's tap.
**Author role:** native Android architect, AURUM Alliance team
**Date:** 2026-10-02

## Design sources (read, in order)

1. `genesis-os/war-room/relay/bridge/outbox/zane/2026-09-29-astra-amaze-dj-packet.md` — Brandon's "I need to be amazed" brief, taste dossier, 16 taste dimensions, UX lanes (Rage Mode, Vibey Mode, Dark Drive, Unheard Bangers, Album Journey, why-lines).
2. `genesis-player/god-tier-design/VISION.md` — BRKN Vibes vision. **DESIGN ONLY** status; honest constraints adopted here: **$0, no fantasy hardware, no lossless claims (sources are ~128 kbps), no accounts.**
3. `genesis-os/work-orders/inbox/WO-AURUM-008-BUILD.md` — DSP scope. **Already built** on this branch: `ParametricDsp.kt` (pure-Kotlin 8-band parametric core, JVM-tested) + `ParametricEqPanel.kt` + `DspAndroidStore.kt`.
4. Existing code on this branch: `PlayerService.kt` (MediaSessionService + ExoPlayer + MediaSession, foreground service, audio focus), `PlaybackQueue.kt`, `CrossfadeMath.kt` (crossfade engage rule, unit-tested), `AudioFxController.kt` (legacy `android.media.audiofx` system-effects path).

## Non-goals (carried from the vision, restated so no one re-litigates them)

- No hi-res/lossless tier (sources are ~128 kbps MP3 — fidelity theater is a lie).
- No accounts, logins, cloud sync, social graph. The taste engine lives on the phone.
- No SoundCloud downloads / offline pinning of the SC shelf (stream-only, visibly badged).
- No YouTube stream ripping — hard no (see §4).
- No Android Auto, Chromecast, podcasts, gamification, in-player purchases, voice control inside the player (Apollo owns conversation).

---

## 1. Audio pipeline

### 1.1 The two engines — and the one that ships

Two DSP paths exist in this codebase. **Only one may be active at a time.** The rebuild ships the parametric engine; the system-fx engine becomes the legacy fallback.

| | Parametric engine (ships) | System-fx engine (legacy) |
|---|---|---|
| Code | `ParametricDsp.kt` (`ParametricEq`, `DspMath`, `DspPresets`, `EasyDspMapper`, `DspProfiles`) | `AudioFxController.kt` (`android.media.audiofx`: `Equalizer`, `BassBoost`, `LoudnessEnhancer`) |
| Where it runs | In-process, inside ExoPlayer's audio sink via a custom `AudioProcessor` (§1.2) | In AudioFlinger, attached to ExoPlayer's audio session id |
| Determinism | Fully deterministic; JVM unit-testable (already tested: impulse stability, preset response checkpoints, headroom stress) | Device-dependent (band count/centers vary by HAL; some devices expose 0 bands) |
| Honesty | Real dB/Hz/Q values, published presets, measured headroom | `BassBoost.setStrength(0..1000)` has **no dB scale** — must never be labeled as dB (WO-AURUM-008 §A/B trust) |

**Decision:** the rebuild wires `ParametricEq` into the pipeline via `ParametricDspAudioProcessor`. `AudioFxController` is retained as a **degraded fallback** for the (unlikely) case where float-output audio processing is unavailable on a device — surfaced in the Engine Room as "System FX mode (device fallback)", never silently. Easy mode (`FourStageEq.State`) maps onto the parametric engine via the existing `EasyDspMapper` — the old sliders keep working, the new engine does the work.

### 1.2 `ParametricDspAudioProcessor` — the adapter (TO BUILD)

New file: `android/app/src/main/java/com/apexforge/genesisplayer/audio/ParametricDspAudioProcessor.kt`

```kotlin
class ParametricDspAudioProcessor : BaseAudioProcessor() {
    // Owns one ParametricEq instance. All parameter changes arrive as
    // immutable commands through a lock-free queue (see §1.4).
}
```

**Media3 contract it must satisfy** (`androidx.media3.common.audio.AudioProcessor`):

- `onConfigure(AudioFormat) -> AudioFormat`: accept **only** `ENCODING_PCM_FLOAT`. If the input is not float PCM, return `AudioFormat.NOT_SET` (processor bypasses itself — never crash the sink). The sink is configured to *request* float output (§1.3), so in the normal path this is always float.
- `queueInput(ByteBuffer)`: reinterpret the buffer as float32 frames, run `ParametricEq.processBlock`-equivalent per channel. `ParametricEq` is currently mono-block oriented (`processBlock(FloatArray)`); the adapter keeps **one filter state per channel** (stereo = 2 `ParametricEq` instances sharing the same parameter object, or one instance processing channels interleaved — implement as per-channel instances; state must never be shared across channels).
- `queueEndOfStream()`, `isEnded()`, `flush()` (reset filter state on seek — call `resetState()` so a seek doesn't smear old filter memory into the new position), `reset()`.
- `isActive()`: return `!eq.bypassAll || protectionActive` — when the user enables true bypass AND no protection is engaged, the processor reports inactive so Media3 can skip it (zero CPU when flat-and-bypassed).

**Where smoothing / headroom / limiter live:**

- **Coefficient smoothing** lives inside `BiquadFilter` already (20 ms tau lerp toward target coefficients, no clicks on slider moves). The adapter does NOT reimplement it. Parameter changes from the UI call `eq.retarget(snap=false)`; preset loads call `applyPreset()` which snaps. This is the correct layering — the core owns the math, the adapter owns the plumbing.
- **Headroom (auto preamp cut)** is computed in `ParametricEq.effectivePreampDb()` — deterministic, runs on the parameter thread, not per-sample. The adapter reads the cached effective preamp per block.
- **Soft limiter** (`limitSample`, tanh at −1 dBFS) runs per-sample at the end of the chain, inside the audio thread. `protectionActive` / `limiterEngagedLastBlock` are exposed via a volatile snapshot the UI polls at ~4 Hz to light the "protection" indicator — never read per-frame from the UI thread.

**Sample-rate handling:** `ParametricEq` is constructed with a sample rate. ExoPlayer's output rate can change (Bluetooth resampling, etc.). The adapter must detect `onConfigure` rate changes and **rebuild the `ParametricEq` with the new rate** (coefficients are rate-dependent), preserving the current preset/bands. This is a real edge case — cheap to handle, expensive to debug later.

### 1.3 Float PCM — the one sink flag that matters

`DefaultAudioSink` defaults to 16-bit integer PCM. A float DSP fed with int16 would quantize twice (int→float→int per block) and the limiter math would be wrong.

**Required:** `DefaultAudioSink.Builder(...).setAudioProcessors(arrayOf(dspProcessor)).setEnableFloatOutput(true)` — this makes the sink negotiate `ENCODING_PCM_FLOAT` with the AudioTrack. Wire it in the `ExoPlayer.Builder.setAudioSink()` call inside `PlayerService`.

Fallback honesty: if a device's AudioTrack rejects float output (some old/low-end HALs), `onConfigure` receives non-float and the processor self-bypasses; the service then falls back to `AudioFxController` system FX and the Engine Room says so. **This fallback path is device-gated and NOT VERIFIED** (§7).

### 1.4 Thread model

| Thread | Owns | Must never |
|---|---|---|
| ExoPlayer playback thread (internal) | `queueInput` per-sample DSP, filter state, limiter | Touch UI, do I/O, allocate per block |
| Main thread (UI) | Compose state, sliders, preset buttons | Call `eq` methods directly, block on audio |
| Parameter thread (single-thread executor in the adapter) | Applies `DspCommand`s: `ApplyPreset`, `SetBand`, `SetPreamp`, `SetBypass`, `LoadRouteProfile` | — |

**Command flow:** UI → `DspViewModel` → `dspCommandQueue.offer(cmd)` (lock-free `ConcurrentLinkedQueue`) → the audio thread drains the queue at the top of each `queueInput` call and applies commands before processing the block. This gives sample-accurate-ish parameter updates with zero locks on the hot path and zero main-thread audio work. The existing 20 ms coefficient smoothing absorbs any block-boundary discontinuity, so draining per-block is click-free by construction.

**Route-profile switching** (`DspProfiles.effectivePreset(store, class)`): runs on the parameter thread; to avoid audible level jumps, the switch applies the new preset then ramps a 200 ms gain glide in the adapter (linear ramp on the block multiplier — simple, in the adapter, not in the DSP core). NOT VERIFIED on-device for audibility — device-gated (§7).

### 1.5 Output route detection → route profiles

New file: `audio/RouteMonitor.kt`

- Source of truth: `AudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)` + `AudioDeviceInfo.type`.
- Mapping to the existing `OutputClass` enum (`ParametricDsp.kt`):
  - `TYPE_BUILTIN_SPEAKER` → `PHONE_SPEAKER` (tiny-speaker policy: sub-150 Hz gains capped at +3 dB — already implemented in `DspProfiles.effectivePreset`)
  - `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`, `TYPE_USB_DEVICE` → `HEADPHONES`
  - `TYPE_BLUETOOTH_A2DP`, `TYPE_BLUETOOTH_SCO`, `TYPE_HEARING_AID` → `BLUETOOTH`
  - **Car:** Android exposes no reliable "this Bluetooth device is a car" signal. `CAR_EXTERNAL` stays a **manual override** — a toggle in the Engine Room ("I'm docked to my car") that pins the profile until unpinned. Honest framing, no fake detection.
  - Anything else / unknown → `DEFAULT`.
- Listen via `AudioManager.registerAudioDeviceCallback` (API 23+; minSdk is 26, fine). On route change: re-resolve class → load profile → 200 ms glide (§1.4).
- Profiles persist per class via `DspAndroidStore` (already built, JVM-tested).

### 1.6 Loudness normalization — honesty preserved

WO-AURUM-008 is explicit: normalization ONLY from honest measured data, never faked. The rebuild does **not** ship a loudness scanner in v1. The Engine Room toggle from the vision ("Loudness normalization") ships as **OFF / UNKNOWN** until a real measurement pipeline exists (catalog-build-time `loudnorm` values published into the track catalog, applied as a scalar at track transition — the vision's §9.6 design). The parametric core's `abBypassTrimDb()` (volume-matched A/B from the EQ's own measured response) is the only level-matching in v1, clearly labeled. **No ReplayGain/EBU-R128 values are ever invented.**

---

## 2. Background playback

Most of this **already exists** in `PlayerService.kt` and is gate-proven (background + screen-off playback passed the device gate on the legacy APK). The rebuild keeps the architecture and extends it.

### 2.1 Service + session (existing, keep)

- `PlayerService : MediaSessionService()` — ExoPlayer + `MediaSession`, foreground service while playing, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission (already in the manifest).
- Media3 manages the foreground-service lifecycle and the media notification automatically; `onUpdateNotification` is the customization point for BRKN Vibes branding (ember accent — verify the notification accent API on the target API level; `MediaNotification.Provider` customization is version-sensitive).
- **Lock screen:** the standard Media3 media notification carries artwork + controls + seek. Free with the session. Must never regress — it's a gate item.

### 2.2 Audio focus + becoming noisy (existing, keep; one verification)

- `setAudioAttributes(AudioAttributes, /* handleAudioFocus= */ true)` — ExoPlayer pauses on calls and ducks/transients per focus rules. Already in `PlayerService`.
- `ExoPlayer.setHandleAudioBecomingNoisy(true)` is the **default** — wired/BT disconnect pauses automatically with no manual receiver. **Verify the default is actually true in Media3 1.5.1 at build time** (it's documented default, but the gate must assert the pause happens on a real BT disconnect — device-gated, §7).

### 2.3 Sleep timer (extend)

New: `playback/SleepTimer.kt`, owned by `PlayerService`.

- Modes: 15/30/45/60 min · End of track · End of queue. (Vision §3.9.)
- Timed modes: `Handler` on the service; the **last 60 seconds ramp `player.volume` 1.0 → 0.0 smoothly**, then pause. The fade is the product difference — a cut is a bug.
- **End of track:** `player.setPauseAtEndOfMediaItems(true)` — real Media3 API — plus a one-shot flag the timer sets and clears. Honest and simple.
- **End of queue:** `Player.Listener.onPlaybackStateChanged(STATE_ENDED)` with repeat mode off → pause (already ended; the timer just ensures we don't loop). With repeat on, "end of queue" is meaningless — the UI disables that option unless repeat is off. Say it in the UI.
- Moon badge on the mini-player with remaining time; tap cancels. Timer survives screen-off (it lives in the service, not the Activity).

### 2.4 Gapless + crossfade — the honest version

- **Gapless:** ExoPlayer is gapless by default for consecutive items with matching format (it pre-buffers the next item and chains audio timestamps). Nothing to build; the gate asserts no audible gap on the APK's own catalog transitions.
- **Crossfade (the honest part):** Media3 ExoPlayer has **no first-party crossfade API**. The existing `CrossfadeMath.shouldEngage` (unit-tested) decides *when* the last N seconds begin. Three implementation options, ranked honestly:
  1. **Dual-player overlap (real crossfade):** a second ExoPlayer preloaded with the next item; at engage time, ramp player A volume down and player B up over the configured 0–12 s, then swap roles. True overlapping mix. Cost: 2× decoder + network buffers, more battery, and MediaSession must track the *logical* track while audio comes from two players (session metadata swap at the 50% point). This is the only option that deserves the name "crossfade."
  2. **Single-player fade-glide (honest fallback):** fade out over N/2 seconds, let ExoPlayer's natural gapless transition happen, fade in over N/2. No overlap — it is a *transition*, not a mix. Label it "Smooth transition" in the UI, never "Crossfade."
  3. **AudioProcessor pre-buffer mix:** not available — Media3's pipeline hands each media item to the sink as its own stream; a processor cannot see the next track's samples. Do not attempt.
- **Decision:** ship option 2 ("Smooth transition", 0–12 s slider) in v1; option 1 is a Phase-2 subsystem with a battery-cost measurement gate (the Engine Room must show measured drain before it ships — vision §9.5's "measured, never claimed" rule). The settings label must never say "Crossfade" for option 2.

---

## 3. Taste-learning / DJ intelligence — on-device data model

**Law:** no accounts, no cloud, nothing leaves the phone. The taste engine is a Room database + a scoring function. The machine-side radar (Apollo drops) only ever delivers *catalog candidates*; the phone decides what plays.

### 3.1 New dependency (required)

Room (`androidx.room:room-runtime`, `room-ktx`, compiler via KSP). Not currently in `app/build.gradle` — adding it is part of the rebuild's build plan (§6). $0, standard, reversible. (KSP plugin + `com.google.devtools.ksp` version aligned to Kotlin 1.9.24.)

The existing stores (`HistoryStore`, `RatingsStore` — SharedPreferences/JSON) stay as-is for v1 compatibility; the Room DB is the **new system of record for learning**, and a one-time migration imports existing counts at first launch (documented, idempotent, keeps the old files as backup — "delete nothing" house rule).

### 3.2 Schema — `AurumDatabase`

```kotlin
// 1. TASTE VECTOR — the 16 Astra dimensions, one row per dimension.
@Entity(tableName = "taste_dimensions")
data class TasteDimension(
    @PrimaryKey val dimId: String,      // e.g. "emo_rap_home", "rage", ...
    val name: String,                  // display name
    val weight: Float,                 // learned affinity, cold-start seeded
    val confidence: Float,             // 0..1 — grows with evidence
    val updatedAt: Long
)
```

**The 16 dimensions are Astra's design** (mission packet §C2). Their canonical ids/names come from Astra's system design — **do not invent them here**; the rebuild's first DB migration seeds exactly the 16 Astra published, and the seed values come from the cold-start dossier (§3.4). If Astra's list and this doc disagree, Astra wins and this doc gets corrected.

```kotlin
// 2. EVENT LOG — every signal, raw. "A play alone is never preference."
@Entity(tableName = "events")
data class TasteEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,               // catalog id (source-scoped: "audius:123", "sc:456")
    val type: String,                  // PLAY | SKIP | COMPLETE | HEART | DISLIKE | REPLAY
    val positionMs: Long,              // where in the track the event fired
    val durationMs: Long,              // track duration at the time
    val sessionId: String,             // listening session
    val moodLane: String?,             // BANGERS | SOFT_HOURS | IN_BETWEEN | EVERYTHING
    val hourOfDay: Int,
    val createdAt: Long
)
// SKIP with positionMs < 30_000 = early-skip (down-rank signal, per WO-AURUM-004).
// COMPLETE = played to >90% or natural end. REPLAY = manual restart within a session.

//
...[truncated 16665 chars]
---

## 4. Legitimate music access stack

**Rights-clean only. No stream ripping, no ToS violations, ever.** This is a governance line, not a technical preference.

### 4.1 Audius — the backbone (official public API)

- The documented public discovery API (`discoveryprovider.audius.co/v1/...`) with an `app_name` attribution parameter. **No API key required** — this is the sanctioned third-party path.
- Endpoints used: trending / search / track detail (metadata + `artwork` URLs harvested at catalog build), and the per-track `/stream` URL for playback.
- Existing code: `RemoteCatalog.kt` / `ApolloDrops.kt` already resolve Audius tracks; the rebuild keeps the resolution layer and adds the catalog-build harvesting (§5 of the vision: artwork capture).
- **Verify at build time:** the exact stream-URL shape and `app_name` requirement against the live Audius docs — the API has changed shape before. Mark NOT VERIFIED until a real stream plays from a fresh resolve (§7).

### 4.2 SoundCloud — public streams, official accounts only

- Public stream resolution via the `api-v2` stream endpoint with a `client_id`, **restricted to official artist accounts** (the Caskey/Honestav precedent — already proven in the current app via `SoundCloudResolver.kt`).
- The standing problem (vision §11): the current `client_id` is scraped — a single point of failure. The sanctioned fix is an **official SoundCloud API credential, which needs Brandon's developer tap**. Until then: keep the existing resolver, add client_id rotation across a small pool of observed ids, and quarantine failures visibly ("couldn't resolve" state, never a spinner of doom).
- **Stream-only, always.** The UI badges SC tracks "STREAM-ONLY" and never offers pinning. No downloads, no rips — the boundary is load-bearing for the whole product's legitimacy.

### 4.3 YouTube Data API v3 — metadata and discovery ONLY

- The project holds a read-only key (`Phoenix-youtube`). Used for: search, trending, related videos, channel uploads, playlist metadata — a **discovery and metadata layer** feeding the radar's candidate pool and artist pages.
- **NO stream ripping. Hard no.** Brandon's Premium Lite gives zero playback entitlement to any third-party app (verified 2026-09-29, mission packet Appendix B): Lite is ad-free *video*, excludes YouTube Music entirely, music content is carved out of background/offline, and no API pipes a consumer subscription into Aurum. The design must not depend on it, reference it, or work around it. Any code path that extracts YouTube audio is a veto-level violation.
- Honest UI: YouTube-sourced candidates appear as *discovery cards* ("found on YouTube — open in YouTube") with an outbound link, never as playable rows.

### 4.4 Verification pipeline (catalog build, machine-side)

Every candidate — from any source — passes before it enters the catalog:

1. **Resolves?** Fresh stream URL returns audio (Audius) / streamable flag true (SC).
2. **Official?** Uploader is the artist's official account (channel/official artist badge heuristics + the approved-accounts list).
3. **Rights-clean?** Source is Audius (artist-uploaded, stream licensed via the Audius API terms) or SoundCloud public stream (streamable flag). Anything else is dropped.
4. **Dedup** against catalog ids + audio fingerprint cheap-check (duration + title/artist normalized).
5. **Lane classification** against the taste DNA (which of the lanes/dimensions this track feeds) — used for radar slotting and why-lines.

### 4.5 The blocklist — "none of his rejected songs"

- `blocklist` table (§3.2): keyed on source-scoped track id, with reason + timestamp + undo.
- **Brandon's ear gates every addition to the catalog.** Radar candidates arrive as *cards*, never auto-added to the library. Tapping Play on a card does not add it; explicit "+" adds it. DISLIKE on any surface writes the blocklist.
- A blocklisted id can never re-enter via radar, radio, discovery, or queue-fill. The check runs at candidate time (cheap `IN` query), not at play time.
- Undo lives in Library → Not for me (vision §3.2). One mis-tap never exiles a track forever.

---

## 5. UI architecture

### 5.1 Stack

- **Jetpack Compose (Material3)** — already the app's UI framework; the rebuild stays 100% Compose, no Fragments, no Views except where the platform forces it (widgets).
- **State:** `ViewModel` + `StateFlow` per screen; the player surface binds via `MediaController` to `PlayerService`'s `MediaSession` (the existing pattern in `MainActivity.kt` — keep it). Player state flows one way: service → `MediaController` → ViewModels → Compose. The UI never owns playback state.
- **Navigation:** Compose Navigation with 5 bottom tabs (vision §3): **Crate · Playlists · Search · Stats · Apollo**. Persistent mini-player above the nav on every tab (the audit's #3 complaint, killed permanently).
- **DI:** manual constructor injection (no Hilt — the app is small enough that Hilt's build cost isn't justified; revisit if the graph grows past ~20 injectables).

### 5.2 Screens (map to vision §3)

| Screen | File (new/extend) | Notes |
|---|---|---|
| Ember Ignite (first launch + cold start) | `ui/IgniteScreen.kt` (new) | 600 ms ember bloom; one-tap RESUME THE NIGHT restores queue+position+EQ from the persisted session. Skippable — theater never holds him hostage. |
| Home — The Crate | `ui/CrateScreen.kt` (extend) | Live-dot status line, hero, energy pills (BANGERS/SOFT HOURS/IN BETWEEN/EVERYTHING re-seed the station), Fresh Signals rail, crate rows. |
| Now Playing deck | `ui/NowPlayingScreen.kt` (extend) | Expandable sheet; EQ drawer (parametric panel); lyrics tab; overflow (sleep timer, why-this, audio info, report). |
| Ember Mode | `ui/EmberMode.kt` (extend `EmberVisualizer.kt`) | Full-screen visualizer-as-weather + synced lyrics + queue journey + gestures. **Device-gated:** FFT via the player's own audio session (no RECORD_AUDIO — the proven Apache-2.0 pattern); gesture nav; TalkBack equivalents. |
| Up Next queue | `ui/QueueSheet.kt` (extend) | **The queue is the product.** Draggable reorder, provenance chips (`LOVED-ARTIST`/`RADAR`/`SHELF`/`REPLAY`/`FRESH`), swipe-to-remove, "Fill the night", queue persists across restarts. |
| DJ / Discovery feeds | `ui/DiscoveryScreen.kt` (new) | Daily Discovery cards + Track Radio builder + why-line sheets. Feeds from §3.6. |
| Album Journey | `ui/AlbumJourneyScreen.kt` (new) | Album-arc view: full album in sequence, arc visualization (Ashes→Ember→Phoenix framing from the hit-DNA canon), "play the journey" — he listens by the album, the UI finally does too. |
| Advanced EQ | `ui/ParametricEqPanel.kt` (extend — exists) | 8 bands, real dB/Hz/Q, preamp, bypass, A/B trust (volume-matched, one-tap bypass, reset-to-flat), protection indicator, per-route profile switcher. Easy mode stays via `EasyDspMapper`. |
| Stats | new `ui/StatsScreen.kt` | Week-in-sound, visible learning ("dismissed 4 radar drops → fewer guitar-loop tracks"), shareable week card (local render). |
| Engine Room (Settings) | extend settings | Sound / Data & storage / Offline pins / Playback (smooth-transition strength) / Accessibility / Diagnostics / About with real version. |
| Library | `ui/LibraryScreen.kt` (extend) | Recently played · Loved (undo) · Not for me (undo + why) · Saved offline (pin budget meter). |

### 5.3 BRKN Vibes brand language (locked — vision §1)

Palette `#070708` / `#ff6a00` / `#f5b942` / text `#f7f4ed` / dim `#a29d93`; condensed 900 display type; uppercase micro-kickers; ember radial glow. `ui/Theme.kt` owns it. **Don't redecorate it.**

### 5.4 What needs real device verification (UI)

- Ember Mode visualizer: FFT capture from the player's own audio session on his Motorola — emulator proves the render loop, never the audio path.
- All gestures + TalkBack equivalents, 200% font scaling, reduce-motion fallback.
- Mini-player persistence across all tabs under memory pressure (process death → session restore).
- Notification accent + lock-screen artwork on his API level.
- Cold-start resume: queue + position + EQ restored exactly, first note <1.5 s on LTE.

---

## 6. APK compilation path — brutal honesty

### 6.1 What does NOT work

- **The Gradle daemon cannot run on this box.** The sandbox transparently hijacks Java TCP sockets — even 127.0.0.1 loopback returns the "muse: Other TCP connections is turned off" denial page — so the Gradle client can never talk to its daemon. Verified 2026-09-24, documented in `~/AGENTS.md`. Do not retry; it is a sandbox wall, not a config bug.
- **The manual `javac`+`aapt2`+`d8` pipeline is not a real option for this app.** `AGENTS.md` notes it as a theoretical fallback, but it has **never been demonstrated** on this box for a Compose + Media3 app: it would require hand-resolving ~100 AARs (Media3 1.5.1, Compose BOM 2024.10.00, Coil, Guava, Room/KSP), running the Compose compiler plugin by hand, generating `R` classes via `aapt2`, D8 dexing with desugaring, and `apksigner`. Effort: days. Fragility: extreme. Verdict: listed for completeness, **not recommended**.

### 6.2 What CAN work

**Option A — penguin build farm (RECOMMENDED).**
Brandon's Chromebook (`penguin`, 100.77.115.93 via Tailscale) is x86_64, 4 CPU / 2 GB RAM / 60 GB free, **Docker-capable** — the only node in the fleet that can run a real build. Path: wake penguin (the hardened `chromebook-ssh` wrapper already handles the sleep-flap via phone wake-ping), install the Android SDK command-line tools + a JDK 17, sync the repo, run `./gradlew assembleDebug` with `--no-daemon` (daemon optional on a real machine, but `--no-daemon` is fine), pull the APK back over Tailscale. Caveats, stated plainly: penguin is **currently asleep** (power-manages its NIC; the wake path exists but is a mitigation, not a cure — the permanent fix is his Chromebook power-settings tap); 2 GB RAM makes Gradle slow (first build 15–30 min, needs `org.gradle.jvmargs=-Xmx1g` and configuration cache off); the API-34 emulator for instrumented tests likely won't run usefully under Crostini (KVM nested — **not verified**). Debug APKs work; release signing needs his keystore (§6.4).

**Option B — GitHub Actions CI.**
A workflow file (`.github/workflows/android.yml`: checkout → JDK 17 → Gradle `assembleDebug` + `testDebugUnitTest`) gives reproducible builds on every push. Cost: $0 for a public repo (`brandonduda8/genesis-player` is public). **Blockers, honestly named:** (1) pushing the workflow file to GitHub is a push to his repo — **needs Brandon's tap** (never push without it); (2) Actions can't install to his phone — the APK still comes back here for delivery; (3) instrumented tests need an emulator runner (macOS runners or `reactivecircus/android-emulator-runner` — slower, flakier, still $0 within free minutes); (4) release signing needs the keystore as an encrypted secret — **his keystore, his tap** (§6.4). CI is the right *long-term* answer for repeatability; it is not the fastest path to the *next* APK.

**Option C — Oracle ARM VM.**
The standing plan for a permanent 24/7 home (retry cron `oci-vm-retry` runs every 2h). If capacity lands, it becomes the build machine (12 GB RAM — comfortable for Gradle + emulator). **Today it does not exist.** Not a plan, a hope — listed so nobody confuses it with one.

### 6.3 Recommendation

1. **Next APK: Option A (penguin).** Wake → SDK install (one-time, ~1 GB) → `assembleDebug --no-daemon` → pull APK → run the existing device gate (install → launch → playback advances → DSP attaches → background+screen-off → no crash/ANR). Pure-JVM unit tests (`ParametricDspTest`, `FourStageEqMathTest`, `QueuePlannerUnitTest`) should be runnable on penguin too — they're the fast lane and must stay green before any device work.
2. **After the next APK lands: Option B (CI) on his tap.** One workflow file, debug + unit tests; emulator tests as a non-blocking job until proven stable.
3. **Keystore/signing is a separate decision** (§6.4) — debug builds unblock all testing; don't let signing block the build path.

### 6.4 Signing — his keystore, his decision (vision §11)

Today: debug APKs, `versionCode 2`, `versionName '1.1-apollo'`, manual sideload to his phone. A release key changes the trust story (updates, no "unknown developer" friction). The keystore must be **generated on hardware Brandon controls** (his Chromebook or his phone — never on this shared box, never in chat, never in the repo). The build reads it from environment variables at CI time or a local `keystore.properties` (0600, gitignored). Until he taps it: debug builds, versionCode increments every build, no promises about an update channel.

---

## 7. Explicit NOT VERIFIED list + device-gated items

**Nothing below is claimed as working. Each item names what would verify it.**

### NOT VERIFIED (must be proven before any claim)

1. `ParametricDspAudioProcessor` does not exist yet — the entire §1.2–§1.4 wiring (float PCM path, command queue, per-channel state, sample-rate rebuild, 200 ms route glide) is **design, not code**.
2. `setEnableFloatOutput(true)` accepted by his Motorola's AudioTrack — if the HAL rejects float, the DSP self-bypasses (§1.3 fallback). Verify: log the negotiated `AudioFormat` on his device.
3. Measured CPU overhead of the 8-band float DSP on his device class — WO-AURUM-008 requires a number; the design predicts "trivial" (<2% of one core at 48 kHz stereo) but **prediction is not measurement**.
4. Dual-player crossfade (option 1, §2.4) — not designed in detail, not built, battery cost unmeasured. The v1 "Smooth transition" label must not say "Crossfade."
5. Audius `/stream` URL shape + `app_name` requirement against the **live** API at build time.
6. Room schema migrations on his real data (v1→v2 with the SharedPreferences import, §3.1).
7. The 16 Astra dimension ids/names/seed values — **Astra's design is the source of truth**; this doc intentionally does not enumerate them (§3.2).
8. Exploration/exploitation ratio and diversity caps (§3.7) — hypothesized from the WO-AURUM-004 report, unmeasured against his real skip behavior.
9. Any battery-drain figure shown in the Engine Room — "measured, never claimed" (vision §9.5).

### Device-gated (needs his Motorola, cannot be proven on emulator/CI)

- D1. BT-disconnect auto-pause (`setHandleAudioBecomingNoisy` default in Media3 1.5.1).
- D2. Route detection across his real devices (phone speaker → his earbuds → his BT speaker → car): correct `OutputClass`, no audible level jump on switch, tiny-speaker bass cap audible-behavior sane.
- D3. Ember Mode visualizer FFT from the player's own audio session (no RECORD_AUDIO).
- D4. Sleep-timer 60-second fade — verify it's smooth, not stepped, on his hardware.
- D5. Notification accent + lock-screen controls on his API level.
- D6. Cold-start resume: queue + position + EQ restored exactly after process death.
- D7. Doze / battery-saver behavior: playback continues, timers fire, no ANR.
- D8. TalkBack pass over the new screens; 200% font scaling; reduce-motion fallbacks.
- D9. Real-device gate per the vision §9.2: the full gate runs against his device profile before any build reaches him.

---

## 8. New-file map (build order)

Phase 1 — audio engine (the critical path):
1. `audio/ParametricDspAudioProcessor.kt` — §1.2
2. `audio/RouteMonitor.kt` — §1.5
3. `audio/DspCommandQueue.kt` — §1.4 (or fold into the processor)
4. Wire `setAudioSink` + `setEnableFloatOutput(true)` in `PlayerService.kt`
5. `playback/SleepTimer.kt` — §2.3
6. "Smooth transition" fade-glide — §2.4 option 2

Phase 2 — taste engine:
7. Room: `taste/AurumDatabase.kt`, entities, DAOs, `TasteRepository.kt` — §3.2–§3.3
8. `taste/Scoring.kt` (pure JVM, unit-tested) + `taste/WhyLines.kt` — §3.5–§3.6
9. Discovery/Track Radio builders + `ui/DiscoveryScreen.kt`, `ui/AlbumJourneyScreen.kt` — §3.6–§3.7, §5.2
10. Blocklist enforcement in the candidate pipeline — §4.5

Phase 3 — catalog & UI completion:
11. `DiscoveryScreen`, `AlbumJourneyScreen`, `StatsScreen`, `IgniteScreen` — §5.2
12. Ember Mode device pass — §5.4
13. CI workflow file (his tap) — §6.2 option B

**Dependency additions** (`app/build.gradle`, part of the build plan): Room runtime + ktx + KSP compiler; no other new deps required — Media3 1.5.1, Compose BOM 2024.10.00, Coil, Guava already present.

---

*End of architecture. Design only — no code, no rebuild, no taps spent. Commit on `aurum-rebuild` locally; never merge, never push without Brandon.*
