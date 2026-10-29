/**
 * Aurum taste/curation policy v2. Pure, deterministic, dependency-free.
 * Evidence references are checked for structure and source binding, NOT authenticity.
 * Caller-supplied receipts never constitute device verification or a release approval.
 */
import {artistKey, songKey, clean} from './engine.mjs';

export const LIMITS = Object.freeze({tracks: 20000, blocks: 50000, events: 1000});
const TYPES = new Set(['like', 'unlike', 'complete', 'skip', 'dislike']);
const LANES = new Set(['discovery', 'queue', 'gold']);
const END_OF_TIME = 4102444800000;
const HALF_LIFE_MS = 30 * 86400000;
const record = x => x !== null && typeof x === 'object' && !Array.isArray(x);
const text = (x, n = 512) => typeof x === 'string' && x.length > 0 && x.length <= n && !/[\x00-\x1f\x7f]/.test(x) && x.trim().length > 0;
const stamp = x => Number.isSafeInteger(x) && x >= 0 && x <= END_OF_TIME;
const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const validTrack = t => record(t) && text(t.artist, 200) && text(t.title, 300) && clean(t.artist) !== '' && clean(t.title) !== '';
export const identity = t => validTrack(t) ? songKey(t.artist, t.title) : '';

/** Optional event IDs are idempotency keys within the retained 1,000-event window. */
export function cleanEvents(input) {
  if (!Array.isArray(input)) return [];
  const out = [], seen = new Set();
  for (const e of input.slice(-2 * LIMITS.events)) {
    if (!validTrack(e) || !TYPES.has(e.type) || !stamp(e.at)) continue;
    if (e.id !== undefined && !text(e.id, 160)) continue;
    const key = e.id === undefined ? JSON.stringify([e.type, identity(e), e.at]) : `id:${e.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({type: e.type, artist: e.artist, title: e.title, at: e.at, ...(e.id === undefined ? {} : {id: e.id})});
  }
  return out.slice(-LIMITS.events);
}

export function recordEvent(history, track, type, at = Date.now(), id) {
  const kept = cleanEvents(history);
  if (!validTrack(track) || !TYPES.has(type) || !stamp(at)) return kept;
  return cleanEvents([...kept, {type, artist: track.artist, title: track.title, at, ...(id === undefined ? {} : {id})}]);
}

function policyKey(k) {
  if (!text(k, 600)) return null;
  if (k.startsWith('artist:') && clean(k.slice(7))) return artistKey(k.slice(7));
  if (k.startsWith('song:')) {
    const parts = k.slice(5).split('|');
    if (parts.length === 2 && parts.every(x => clean(x))) return songKey(...parts);
  }
  return null;
}

/** Reject corrupt deny lists as a whole, never discard the oldest reject to fit a cap. */
function policy(profile) {
  if (!record(profile)) return null;
  const blocks = profile.blocks === undefined ? [] : profile.blocks;
  if (!Array.isArray(blocks) || blocks.length > LIMITS.blocks) return null;
  const keys = Array.from(blocks, policyKey);
  if (keys.some(k => k === null)) return null;
  return {
    blocks: new Set(keys),
    favorites: new Set((Array.isArray(profile.favoriteArtists) ? profile.favoriteArtists : []).filter(x => text(x, 200)).map(artistKey)),
    likes: new Set((Array.isArray(profile.likes) ? profile.likes : []).map(policyKey).filter(Boolean))
  };
}

function hasReceipt(t, kind, asOf = END_OF_TIME) {
  if (!record(t) || !text(t.sourceId)) return false;
  const receipt = t[`${kind}Evidence`];
  const timestamp = kind === 'quality' ? 'measuredAt' : 'checkedAt';
  const status = kind === 'quality' ? 'PASSED' : 'AUTHORIZED';
  return t[`${kind}Verified`] === true && record(receipt) && receipt.sourceId === t.sourceId &&
    receipt.status === status && text(receipt.reportRef) && stamp(receipt[timestamp]) &&
    receipt[timestamp] > 0 && receipt[timestamp] <= asOf;
}

function approved(t) {
  return t.ownerApproved === true && text(t.sourceId) && record(t.ownerApproval) &&
    t.ownerApproval.sourceId === t.sourceId && text(t.ownerApproval.receiptRef);
}

export function evidenceState(t) {
  if (t?.rightsVerified === false) return 'BLOCKED_RIGHTS';
  return hasReceipt(t, 'quality') && hasReceipt(t, 'rights') ? 'EVIDENCE_ATTACHED' : 'UNVERIFIED';
}

function classification(t, p, asOf) {
  if (!validTrack(t)) return {state: 'QUARANTINE', reasons: ['INVALID_METADATA']};
  if (p.blocks.has(identity(t)) || p.blocks.has(artistKey(t.artist))) return {state: 'REJECT', reasons: ['OWNER_BLOCK']};
  if (t.rightsVerified === false) return {state: 'REJECT', reasons: ['RIGHTS_DENIED']};
  const reasons = [];
  if (t.quarantined === true || t.qualityStatus === 'LOW_QUALITY') reasons.push('SOURCE_QUARANTINED');
  if (t.playable !== true) reasons.push('PLAYBACK_UNVERIFIED');
  if (!hasReceipt(t, 'quality', asOf)) reasons.push('QUALITY_EVIDENCE_MISSING');
  if (!hasReceipt(t, 'rights', asOf)) reasons.push('RIGHTS_EVIDENCE_MISSING');
  if (reasons.length) return {state: 'QUARANTINE', reasons};
  return approved(t) ? {state: 'GOLD_ELIGIBLE', reasons: []} : {state: 'SCOUT', reasons: ['OWNER_APPROVAL_REQUIRED']};
}

/** Feedback is preference state plus bounded engagement, not a count of button taps. */
function signals(history, asOf) {
  const events = cleanEvents(history).filter(e => e.at <= asOf);
  const clock = asOf === END_OF_TIME ? Math.max(0, ...events.map(e => e.at)) : asOf;
  const preferences = new Map(), engagement = new Map(), artists = new Map(), counts = new Map();
  for (const e of events) {
    const key = identity(e);
    artists.set(key, artistKey(e.artist));
    counts.set(key, (counts.get(key) || 0) + 1);
    if (e.type === 'complete' || e.type === 'skip') {
      const age = Math.max(0, clock - e.at);
      const delta = (e.type === 'complete' ? 3 : -4) * 2 ** (-age / HALF_LIFE_MS);
      engagement.set(key, (engagement.get(key) || 0) + delta);
    } else {
      const previous = preferences.get(key);
      if (!previous || e.at >= previous.at) preferences.set(key, e);
    }
  }
  const bySong = new Map(), byArtist = new Map();
  for (const [key, artist] of artists) {
    const preference = preferences.get(key)?.type;
    const explicit = preference === 'like' ? 8 : preference === 'dislike' ? -20 : 0;
    const total = clamp(explicit + (engagement.get(key) || 0), -30, 30);
    bySong.set(key, total);
    byArtist.set(artist, (byArtist.get(artist) || 0) + total / 4);
  }
  for (const [artist, value] of byArtist) byArtist.set(artist, clamp(value, -12, 12));
  return {bySong, byArtist, counts};
}

function score(t, p, weights, asOf) {
  const key = identity(t), artist = artistKey(t.artist), components = [];
  const add = (code, value, reason) => {if (value) components.push({code, value, reason});};
  add('FAVORITE_ARTIST', p.favorites.has(artist) ? 22 : 0, 'Favorite artist');
  add('EXPLICIT_LIKE', p.likes.has(key) ? 18 : 0, 'Previously liked');
  add('TRACK_FEEDBACK', weights.bySong.get(key) || 0, 'Your listening feedback');
  add('ARTIST_FEEDBACK', weights.byArtist.get(artist) || 0, 'Artist listening history');
  add('QUALITY_RECEIPT', hasReceipt(t, 'quality', asOf) ? 4 : 0, 'Source-bound quality report attached');
  return {
    score: clamp(components.reduce((sum, x) => sum + x.value, 0), -40, 80),
    reasons: components.length ? components.map(x => x.reason) : ['Unrated discovery candidate'],
    components, feedbackSamples: weights.counts.get(key) || 0
  };
}

function sortItems(a, b) {
  return b.score - a.score || compare(a.key, b.key) || compare(a.track.sourceId || '', b.track.sourceId || '') || compare(String(a.track.id || ''), String(b.track.id || ''));
}

function selectGold(items, manifest) {
  const blocked = reason => ({status: reason, tracks: []});
  if (!record(manifest) || manifest.schema !== 1 || !text(manifest.id) || !text(manifest.approvalRef) ||
      !Number.isSafeInteger(manifest.version) || manifest.version < 1 || !Array.isArray(manifest.entries) ||
      manifest.entries.length === 0 || manifest.entries.length > LIMITS.tracks) return blocked('MANIFEST_REQUIRED');
  const available = new Map();
  for (const item of [...items].sort(sortItems)) {
    if (item.curation !== 'GOLD_ELIGIBLE') continue;
    const k = JSON.stringify([item.key, item.track.sourceId]);
    if (!available.has(k)) available.set(k, item);
  }
  const selected = [], seen = new Set();
  for (const entry of manifest.entries) {
    if (!record(entry) || !text(entry.sourceId) || policyKey(entry.key) !== entry.key || !entry.key.startsWith('song:') || seen.has(entry.key)) return blocked('MANIFEST_INVALID');
    seen.add(entry.key);
    const item = available.get(JSON.stringify([entry.key, entry.sourceId]));
    if (!item) return blocked('MANIFEST_INCOMPLETE_OR_BLOCKED');
    selected.push(item);
  }
  return {status: 'MANIFEST_MATCHED_NOT_INDEPENDENTLY_VERIFIED', tracks: selected};
}

/**
 * Non-destructive catalog audit. Scores describe taste, NOT audio fidelity or rights.
 * Local queue previews may have unknown quality; Gold never inherits that exemption.
 * A Gold manifest is source-bound and all-or-nothing, retaining its exact ordered list.
 */
export function evaluateCatalog(tracks, profile = {}, history = [], options = {}) {
  const empty = status => ({schema: 2, status, ranked: [], quarantine: [], rejected: [], scout: [], gold: [], goldStatus: status, duplicates: 0});
  const p = policy(profile);
  if (!p) return empty('PROFILE_INVALID');
  if (!Array.isArray(tracks) || tracks.length > LIMITS.tracks) return empty('CATALOG_INVALID_OR_OVER_LIMIT');
  if (!record(options) || !LANES.has(options.lane === undefined ? 'discovery' : options.lane)) return empty('OPTIONS_INVALID');
  if (options.asOf !== undefined && !stamp(options.asOf)) return empty('OPTIONS_INVALID');
  if (options.limit !== undefined && (!Number.isFinite(options.limit) || options.limit < 0)) return empty('OPTIONS_INVALID');
  const asOf = options.asOf === undefined ? END_OF_TIME : options.asOf;
  const lane = options.lane === undefined ? 'discovery' : options.lane;
  const max = options.limit === undefined ? 50 : clamp(Math.floor(options.limit), 0, LIMITS.tracks);
  const weights = signals(history, asOf), items = [], quarantine = [], rejected = [], scout = [];
  // A denial for an identified source cannot be undone by a duplicate claiming success.
  const deniedSources = new Set(tracks.filter(t => record(t) && t.rightsVerified === false && text(t.sourceId)).map(t => t.sourceId));
  for (const track of tracks) {
    const c = classification(track, p, asOf);
    if (validTrack(track) && deniedSources.has(track.sourceId)) {c.state = 'REJECT'; c.reasons = ['RIGHTS_DENIED'];}
    const audit = {track, reasons: c.reasons};
    if (c.state === 'REJECT') {rejected.push(audit); continue;}
    if (c.state === 'QUARANTINE') quarantine.push(audit);
    if (c.state === 'SCOUT') scout.push(audit);
    if (!validTrack(track)) continue;
    items.push({track, key: identity(track), ...score(track, p, weights, asOf),
      evidence: hasReceipt(track, 'quality', asOf) && hasReceipt(track, 'rights', asOf) ? 'EVIDENCE_ATTACHED' : 'UNVERIFIED',
      curation: c.state, gateReasons: c.reasons, requiresApproval: !approved(track)});
  }
  const gold = selectGold(items, options.manifest);
  const base = {schema: 2, status: 'EVALUATED', quarantine, rejected, scout, gold: gold.tracks, goldStatus: gold.status};
  if (lane === 'gold') return {...base, ranked: gold.tracks, duplicates: 0};
  // Eligibility BEFORE deduplication: an unavailable first copy cannot hide a good one.
  const candidates = items.filter(x => !x.gateReasons.includes('SOURCE_QUARANTINED') && (lane !== 'queue' || x.track.playable === true)).sort(sortItems);
  const unique = [], seen = new Set();
  for (const item of candidates) {if (!seen.has(item.key)) {seen.add(item.key); unique.push(item);}}
  const selected = [], selectedKeys = new Set(), perArtist = new Map();
  for (const cap of options.diversity === false ? [Infinity] : [2, Infinity]) {
    for (const item of unique) {
      if (selected.length >= max) break;
      const artist = artistKey(item.track.artist), count = perArtist.get(artist) || 0;
      if (selectedKeys.has(item.key) || count >= cap) continue;
      selected.push(item); selectedKeys.add(item.key); perArtist.set(artist, count + 1);
    }
  }
  return {...base, ranked: selected, duplicates: candidates.length - unique.length};
}

/** Backward-compatible entrypoint used by the existing browser queue. */
export function rankCandidates(tracks, profile = {}, history = [], options = {}) {
  return evaluateCatalog(tracks, profile, history, options).ranked;
}
