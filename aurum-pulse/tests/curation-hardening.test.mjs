import test from 'node:test';
import assert from 'node:assert/strict';
import {rankCandidates, evaluateCatalog, cleanEvents, recordEvent, identity, evidenceState, LIMITS} from '../web/taste-intelligence.mjs';
import {songKey, artistKey, safeProfile, isBlocked, accessibleQueue, filterTracks, BLOCK_ALL} from '../web/engine.mjs';

// Synthetic fixtures only: these are not Brandon's playlist or real verification receipts.
const track = (id = 'one', extra = {}) => ({id, artist: 'Fixture Artist', title: id, playable: true, ...extra});
function qualified(id = 'one', extra = {}) {
  const sourceId = `fixture:${id}`;
  return track(id, {sourceId, ownerApproved: true, qualityVerified: true, rightsVerified: true,
    qualityEvidence: {sourceId, status: 'PASSED', reportRef: 'fixture-quality-report', measuredAt: 100},
    rightsEvidence: {sourceId, status: 'AUTHORIZED', reportRef: 'fixture-rights-report', checkedAt: 100},
    ownerApproval: {sourceId, receiptRef: 'fixture-owner-approval'}, ...extra});
}
const manifest = tracks => ({schema: 1, id: 'fixture-manifest', version: 1, approvalRef: 'fixture-approval',
  entries: tracks.map(t => ({key: identity(t), sourceId: t.sourceId}))});
const gold = (tracks, m = manifest(tracks), profile = {}) => rankCandidates(tracks, profile, [], {lane: 'gold', manifest: m});
const score = (t, history) => rankCandidates([t], {}, history, {diversity: false})[0].score;

test('Gold refuses boolean-only quality, rights and owner assertions', () => {
  const t = track('one', {sourceId: 'fixture:one', ownerApproved: true, rightsVerified: true, qualityVerified: true});
  assert.deepEqual(gold([t]), []);
});
test('Gold requires an exact manifest even with attached receipts', () => assert.deepEqual(rankCandidates([qualified()], {}, [], {lane: 'gold'}), []));
test('Gold matches receipts and preserves exact manifest order, not ranking order', () => {
  const a = qualified('a'), b = qualified('b');
  assert.deepEqual(gold([a, b], manifest([b, a]), {likes: [identity(a)]}).map(x => x.track.id), ['b', 'a']);
});
test('Gold cannot silently substitute an alternate recording', () => {
  const t = qualified(); const m = manifest([t]); m.entries[0].sourceId = 'fixture:other';
  assert.deepEqual(gold([t], m), []);
});
test('Gold is all-or-nothing when an approved entry is unavailable', () => {
  const a = qualified('a'), b = qualified('b'); assert.deepEqual(gold([a], manifest([a, b])), []);
});
test('Gold blocks override every approval and measured-quality score', () => {
  const t = qualified(); assert.deepEqual(gold([t], manifest([t]), {blocks: [identity(t)]}), []);
});
test('Gold rejects duplicated manifest identities', () => {const t = qualified(); assert.deepEqual(gold([t], manifest([t,t])), []);});
test('Gold rejects malformed manifests without throwing', () => {
  for (const m of [null, [], {}, {schema:1}, {...manifest([qualified()]), entries:[null]}]) assert.deepEqual(gold([qualified()], m), []);
});
test('Gold keeps complete canonical membership beyond default discovery limits', () => {
  const ts = Array.from({length: 60}, (_, i) => qualified(`song${i}`)); assert.equal(gold(ts).length, 60);
});
for (const field of ['qualityEvidence', 'rightsEvidence', 'ownerApproval']) {
  test(`Gold refuses missing ${field}`, () => {const t=qualified(); delete t[field]; assert.deepEqual(gold([t]), []);});
  test(`Gold refuses source-mismatched ${field}`, () => {const t=qualified(); t[field].sourceId='other'; assert.deepEqual(gold([t]), []);});
}
test('Future-dated measurement cannot satisfy an as-of gate', () => {
  const t=qualified(); assert.deepEqual(rankCandidates([t],{},[],{lane:'gold',manifest:manifest([t]),asOf:99}),[]);
});
test('A reference is labeled attached evidence, never independent verification', () => assert.equal(evidenceState(qualified()), 'EVIDENCE_ATTACHED'));
test('Flags alone never produce a verified evidence badge', () => assert.equal(evidenceState({qualityVerified:true,rightsVerified:true}), 'UNVERIFIED'));
test('Quarantine records precise missing-evidence reasons without mutating inputs', () => {
  const t=Object.freeze(track()); const out=evaluateCatalog(Object.freeze([t]));
  assert.equal(out.quarantine.length,1); assert.ok(out.quarantine[0].reasons.includes('QUALITY_EVIDENCE_MISSING')); assert.equal(t.playable,true);
});
test('Unapproved qualified candidate stays in Scout instead of Gold', () => {
  const t=qualified('one',{ownerApproved:false});const out=evaluateCatalog([t]);assert.equal(out.scout.length,1);assert.equal(out.gold.length,0);
});
test('Explicit low-quality quarantine is absent from ordinary queues and discovery', () => {
  const t=qualified('one',{qualityStatus:'LOW_QUALITY'});
  for(const lane of ['discovery','queue','gold']) assert.deepEqual(rankCandidates([t],{},[],{lane,manifest:manifest([t])}),[]);
});
test('An unavailable duplicate cannot shadow a playable copy', () => {
  const t=track(); assert.equal(rankCandidates([{...t,playable:false}, {...t,id:'good'}],{},[],{lane:'queue'})[0].track.id,'good');
});
test('Rights-denied alternate source cannot shadow an allowed source', () => {
  const t=track();assert.equal(rankCandidates([{...t,sourceId:'bad',rightsVerified:false},{...t,sourceId:'good'}],{},[],{lane:'queue'}).length,1);
});
test('Conflicting rights claims for the same source fail closed', () => {
  const t=qualified();assert.deepEqual(gold([t,{...t,rightsVerified:false}],manifest([t])),[]);
});
test('Duplicate choice and score tie ordering are independent of catalog traversal', () => {
  const ts=[track('b'),track('a'),track('a',{id:'a2'})];assert.deepEqual(rankCandidates(ts),rankCandidates([...ts].reverse()));
});
test('Unknown lane cannot fall through to a less restrictive mode', () => assert.deepEqual(rankCandidates([track()],{},[],{lane:'GOLD_TYPO'}),[]));
test('Zero limit really returns zero candidates', () => assert.deepEqual(rankCandidates([track()],{},[],{limit:0}),[]));
for (const invalid of [null, [], {blocks:'corrupt'}, {blocks:[null]}, {blocks:new Array(2)}]) {
  test(`Invalid deny policy fails closed: ${JSON.stringify(invalid)}`, () => {
    assert.equal(evaluateCatalog([track()],invalid).status,'PROFILE_INVALID');assert.deepEqual(rankCandidates([track()],invalid),[]);
  });
}
test('Over-budget deny list fails closed without discarding oldest reject', () => {
  assert.equal(evaluateCatalog([track()],{blocks:Array(LIMITS.blocks+1).fill('artist:no')}).status,'PROFILE_INVALID');
});
test('Oversized catalogs are rejected explicitly rather than silently truncated', () => {
  assert.equal(evaluateCatalog(Array(LIMITS.tracks+1).fill(track())).status,'CATALOG_INVALID_OR_OVER_LIMIT');
});
test('Full 1,001-track local queue is not cut to 200', () => {
  const ts=Array.from({length:1001},(_,i)=>track(`song${i}`));assert.equal(rankCandidates(ts,{},[],{lane:'queue',limit:ts.length}).length,1001);
});
test('Invalid numeric options cannot become implicit permissive defaults', () => {
  for(const options of [{limit:NaN},{limit:Infinity},{limit:-1},{asOf:Infinity},null]) assert.equal(evaluateCatalog([track()],{},[],options).status,'OPTIONS_INVALID');
});
test('Invalid metadata does not coerce objects into song identities', () => {
  assert.deepEqual(rankCandidates([null,{},track('one',{artist:{}}),track('one',{title:''})]),[]);
});
test('Feedback replay by event ID is idempotent inside the retained window', () => {
  const t=track(), h=recordEvent([],t,'complete',10,'event-1');assert.deepEqual(recordEvent(h,t,'complete',11,'event-1'),h);
});
test('Legacy identical feedback is deduplicated without inventing new event IDs', () => {
  const e={...track(),type:'complete',at:10};assert.equal(cleanEvents([e,e,e]).length,1);
});
test('Distinct completion events are retained', () => {
  const t=track();assert.equal(recordEvent(recordEvent([],t,'complete',10,'one'),t,'complete',10,'two').length,2);
});
test('Like then unlike returns to neutral, even after repeated like events', () => {
  const t=track();let h=[];for(let i=0;i<20;i++)h=recordEvent(h,t,'like',i);h=recordEvent(h,t,'unlike',21);
  assert.equal(score(t,h),score(t,[]));
});
test('Repeated like events cannot inflate preference scores', () => {
  const t=track();let h=[];for(let i=0;i<20;i++)h=recordEvent(h,t,'like',i);assert.equal(score(t,h),score(t,recordEvent([],t,'like',0)));
});
test('Latest timestamp determines explicit preference, not replay arrival order', () => {
  const t=track();assert.equal(score(t,[{...t,type:'unlike',at:20},{...t,type:'like',at:10}]),0);
});
test('Feedback replay cannot inflate artist affinity', () => {
  const t=track();const e={...t,type:'complete',at:10,id:'one'};assert.equal(score(t,[e,e,e]),score(t,[e]));
});
test('Future feedback is excluded by an explicit as-of boundary', () => {
  const t=track();assert.equal(rankCandidates([t],{},recordEvent([],t,'like',100),{asOf:99})[0].score,0);
});
test('Engagement decay is deterministic and measured against explicit time', () => {
  const t=track(), h=recordEvent([],t,'complete',100);const a=rankCandidates([t],{},h,{asOf:100})[0].score;
  const b=rankCandidates([t],{},h,{asOf:100+30*86400000})[0].score;assert.equal(b,a/2);
});
test('Malformed feedback metadata, IDs and timestamps are rejected, not truncated', () => {
  const t=track();assert.deepEqual(cleanEvents([{...t,type:'like',at:NaN},{...t,type:'like',at:-1},{...t,type:'like',at:1,id:{}},{...t,type:'like',at:1,artist:'a'.repeat(201)}]),[]);
});
test('Explainable ranking exposes finite components, not an invented quality percentage', () => {
  const t=track();const x=rankCandidates([t],{favoriteArtists:[t.artist]})[0];assert.ok(x.components.every(c=>Number.isFinite(c.value)));assert.equal(x.score,22);assert.equal(x.evidence,'UNVERIFIED');
});
test('Profile reload preserves the first reject after 3,000 subsequent rejects', () => {
  const t=track();const blocks=[identity(t),...Array.from({length:3000},(_,i)=>songKey('Other',`song${i}`))];
  const p=safeProfile(JSON.parse(JSON.stringify({blocks})));assert.ok(isBlocked(t,p.blocks));assert.deepEqual(accessibleQueue([t],p.blocks),[]);assert.deepEqual(filterTracks([t],'',p.blocks),[]);
});
test('Long valid identity rejects survive profile reload', () => {
  const t=track('x',{artist:'A'.repeat(180),title:'T'.repeat(180)});const p=safeProfile({blocks:[identity(t)]});assert.ok(isBlocked(t,p.blocks));
});
test('Overflow deny-list locks manual playback and stays locked after reload', () => {
  const p=safeProfile({blocks:Array(50001).fill('artist:x')});assert.deepEqual(p.blocks,[BLOCK_ALL]);
  assert.ok(isBlocked(track(),safeProfile(JSON.parse(JSON.stringify(p))).blocks));
});
test('Malformed persisted deny-list locks playback instead of clearing rejects', () => assert.ok(isBlocked(track(),safeProfile({blocks:'bad-data'}).blocks)));
test('Explicit owner reset can release the locked policy', () => assert.equal(isBlocked(track(),safeProfile({blocks:[]}).blocks),false));
test('Generated catalogs never resurface blocked identities under ID changes and reranking', () => {
  let seed=123456789;const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed;};
  for(let round=0;round<100;round++) {
    const ts=Array.from({length:35},(_,i)=>track(`song${i}`,{id:`provider-${random()}`,artist:`Artist${i%7}`}));
    const blocked=ts.filter(()=>random()%3===0).map(identity);const p={blocks:blocked,favoriteArtists:ts.map(t=>t.artist),likes:ts.map(identity)};
    for(const item of rankCandidates(ts,p,[],{lane:'queue',limit:35}))assert.ok(!blocked.includes(identity(item.track)));
  }
});
