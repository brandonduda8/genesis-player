# Aurum Hi-Fi: design note (P0)

**Brandon's ask (2026-10-31):**

> "a tidal like streaming service with the best open source everything … sound engineers and JBL and beats like
> technology … an intelligent data base to help me find music like in my YouTube music playlist … without paying $15
> a month."

**Grade: P0 design + P1 engine BUILT** (6/6 JVM tests locally; CI on this PR). Not on Brandon's phone yet.

## 1. The honest boundary (read first)

TIDAL's catalog of major-label music exists because TIDAL pays licence fees to labels. **No free app can legally play
that same catalog.** An app that pulled it from YouTube, or ripped it from somewhere, would break the law and the
platforms' terms, and could be taken down or get Brandon's accounts banned. So this design does not do that.

It does three things:

1. **Beats TIDAL on sound.** It applies a *measured* correction profile for Brandon's exact headphones, adds a sound
   signature on top, and normalises loudness the way TIDAL does. TIDAL doesn't correct for your headphones at all.
2. **Plays everything that is legally free**, and plays it at the best quality each source offers:
   - Audius tracks uploaded by the artists themselves;
   - lossless FLAC from the Internet Archive's Live Music Archive and its netlabels;
   - Free Music Archive and Jamendo (Creative Commons; Jamendo needs a free API key, which is an account action, so
     it's Brandon's tap);
   - SoundCloud via its official widget, pending policy question Q-POL-001;
   - **Brandon's own files** (FLAC, ALAC, MP3) on the phone. The only $0, legal way to get a favourite major-label song
     at TIDAL quality is to own the file.
3. **Learns his taste from his YouTube Music playlist.** The playlist is used as a *list of songs and artists*, never
   as audio:
   - each song is matched to open music databases (MusicBrainz for identity, ListenBrainz for similar artists and
     tags);
   - a song that is legally available free gets played;
   - a song that isn't gets an honest "not free — here's where it lives" card, never a fake;
   - the taste profile drives "For You" and the radio.

**Also flagged in the current catalog:** some Audius tracks under famous names (for example 8 "Lil Peep" uploads) may
be fan re-uploads rather than the artists' own accounts. The P3 catalog audit keeps only verified artist uploads.

## 2. Architecture

```
            ┌───────────── Taste DB (on-device SQLite) ─────────────┐
YouTube     │ songs · artists · tags · similar-artist graph ·       │
Music list ─┤ play/skip/love history (existing HistoryStore,       ├─► For You · Radio · "More like this"
(import)    │ RatingsStore) · availability per source               │
            └────────────────────────────────────────────────────────┘
Sources ──► Resolver (per-source, verified-only) ──► Media3 ExoPlayer ──► Hi-Fi DSP chain ──► output
 Audius ✓  IA FLAC  FMA/Jamendo  SC widget  Local files       │
                                                              ├─ 1. headphone correction (AutoEq, parametric)
                                                              ├─ 2. sound signature (Studio / Club Bass / Vocal / Flat)
                                                              ├─ 3. loudness normalisation (ReplayGain / EBU R128 -14 LUFS)
                                                              └─ 4. safe preamp + true-peak limiter (never clips)
```

## 3. Phases

| Phase | What | Evidence |
|---|---|---|
| **P1 (this PR)** | `ParametricEq`: exact RBJ biquads (peak / low-shelf / high-shelf), AutoEq profile parser, clipping-safe preamp, a stateful multichannel processing chain. Bundled profiles for **Beats Studio3 Wireless** and **Beats Studio Buds** (AutoEq, MIT; measurements by oratory1990), pinned to AutoEq commit `7ae0f56`. | 6 JVM tests. The computed headroom matches AutoEq's published preamp (−6.91 vs −6.9 dB; −4.22 vs −4.3 dB). A full-scale sine through the chain matches the analytic response within 0.1 dB and never clips. |
| P2 | Wire the chain into Media3 as an `AudioProcessor` (float pipeline). Headphone picker, signature presets, loudness normalisation, limiter. Replaces the coarse 5-band hardware Equalizer path, which stays as the fallback. | Emulator gate: playback advances and `[HIFI] chain active` is logged. A/B null test with Flat is bit-transparent. |
| P3 | Catalog rebuild: audit Audius to keep verified artists only; add IA FLAC and netlabels; local-file library. A quality badge on every track (FLAC 16/44, MP3 320, …), stated honestly. | `catalog_validate.py` extended with source and licence fields. |
| P4 | YouTube Music import: parse the Google Takeout CSV or a pasted list → MusicBrainz match → availability → taste DB. | The import report shows matched, free-available and not-free counts. |
| P5 | TIDAL-style UI: Home, Explore, My Collection, artist pages with credits, quality badges. | UI tests plus screenshots on Brandon's phone (IN BRANDON'S HANDS). |

## 4. What Brandon needs to give

1. **His YouTube Music playlist.** Either:
   - a Google Takeout export (takeout.google.com → "YouTube and YouTube Music" → playlists, as CSV); or
   - the songs pasted as text.

   A public link alone isn't enough, because this build environment can't reach YouTube.
2. **His exact headphones and speakers** (for example "JBL Tune 760NC", "Beats Studio Pro"). AutoEq covers thousands
   of models. If his exact model isn't measured, the closest measured sibling is used and labelled as such.

## 5. Fences

- $0.
- Stream-only for third-party sources: nothing is ripped or downloaded.
- No YouTube audio.
- No scraped credentials in new code (Q-POL-001 pending).
- API key signups (Jamendo, SoundCloud) are account actions, so they need Brandon's tap.
