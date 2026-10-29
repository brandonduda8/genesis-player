import test from 'node:test';
import assert from 'node:assert/strict';
import {cleanEvents,recordEvent,rankCandidates,evidenceState} from '../web/taste-intelligence.mjs';
import {songKey,artistKey} from '../web/engine.mjs';
const t=(artist,title,extra={})=>({artist,title,playable:true,...extra});
test('blocks always outrank powerful likes and feedback',()=>{
 const x=t('Doobie','Never again',{rightsVerified:true,ownerApproved:true});
 const history=Array.from({length:30},()=>({type:'like',artist:x.artist,title:x.title,at:1}));
 assert.deepEqual(rankCandidates([x],{blocks:[songKey(x.artist,x.title)],likes:[songKey(x.artist,x.title)]},history),[]);
});
test('artist blocks exclude every track',()=>assert.deepEqual(rankCandidates([t('Mac Miller','A'),t('Doobie','B')],{blocks:[artistKey('Mac Miller')]}).map(x=>x.track.artist),['Doobie']));
test('unverified music cannot enter Gold',()=>{
 const x=t('A','B',{ownerApproved:true,rightsVerified:false});
 assert.equal(rankCandidates([x],{},[],{lane:'gold'}).length,0);
 assert.equal(rankCandidates([t('A','B',{ownerApproved:true})],{},[],{lane:'gold'}).length,0);
});
test('legacy boolean assertions alone cannot publish Gold',()=>{
 const x=t('A','B',{ownerApproved:true,rightsVerified:true});
 assert.equal(rankCandidates([x],{},[],{lane:'gold'}).length,0);
});
test('discovery never claims source is verified without evidence',()=>{
 const x=rankCandidates([t('A','B')])[0];
 assert.equal(x.evidence,'UNVERIFIED');assert.equal(x.requiresApproval,true);
});
test('queue rejects unavailable audio',()=>assert.deepEqual(rankCandidates([t('A','B',{playable:false})],{},[],{lane:'queue'}),[]));
test('identity survives ID changes, punctuation and case',()=>{
 const xs=[t('Mac Miller','GOOD News!',{id:'old'}),t('MAC MILLER','good news',{id:'new'})];
 assert.equal(rankCandidates(xs).length,1);
});
test('feedback actually reorders equal candidates',()=>{
 const xs=[t('A','One'),t('B','Two')];
 const events=recordEvent([],xs[1],'complete',1);
 assert.equal(rankCandidates(xs,{},events)[0].track.title,'Two');
});
test('ranking is deterministic and explainable',()=>{
 const xs=[t('B','Two'),t('A','One')];
 assert.deepEqual(rankCandidates(xs),rankCandidates(xs));
 assert.ok(rankCandidates(xs)[0].reasons.length>0);
});
test('favorited artists receive higher evidence-based ranking',()=>{
 const xs=[t('A','One'),t('B','Two')];
 assert.equal(rankCandidates(xs,{favoriteArtists:['B']})[0].track.artist,'B');
});
test('artist diversity protects against a single-artist flood',()=>{
 const xs=Array.from({length:6},(_,i)=>t('A','Track '+i)).concat([t('B','One')]);
 assert.equal(rankCandidates(xs,{},[],{limit:3}).filter(x=>x.track.artist==='B').length,1);
});
test('malformed feedback is discarded and bounded',()=>{
 const malformed=[null,{type:'hack',artist:'A',title:'B'},{type:'like',artist:'',title:'B'}];
 assert.equal(cleanEvents(malformed).length,0);
 const x=t('A','One');let h=[];
 for(let i=0;i<1100;i++)h=recordEvent(h,x,'like',i);
 assert.equal(h.length,1000);
});
test('rights failures explicitly block candidates',()=>assert.equal(evidenceState({rightsVerified:false}),'BLOCKED_RIGHTS'));
