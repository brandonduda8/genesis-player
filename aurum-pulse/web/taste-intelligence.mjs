/** AURUM Taste Intelligence v1. Deterministic, offline, no remote model or claims. */
import {artistKey, songKey, isBlocked, clean} from './engine.mjs';

const EVENTS=new Set(['like','unlike','complete','skip','dislike']);
const clamp=(x,min,max)=>Math.min(max,Math.max(min,x));
const finite=(x,defaultValue=0)=>typeof x==='number'&&Number.isFinite(x)?x:defaultValue;
export const identity=t=>songKey(t?.artist??'',t?.title??'');
export const evidenceState=t=>t?.qualityVerified===true&&t?.rightsVerified===true?'VERIFIED':t?.rightsVerified===false?'BLOCKED_RIGHTS':'UNVERIFIED';

/** Defensive parse: unknown feedback, malformed records, oversize histories are ignored. */
export function cleanEvents(input){
 if(!Array.isArray(input))return [];
 const out=[];
 for(const event of input.slice(-2000)){
  if(!event||typeof event!=='object'||!EVENTS.has(event.type))continue;
  const artist=typeof event.artist==='string'?event.artist.slice(0,120):'';
  const title=typeof event.title==='string'?event.title.slice(0,180):'';
  if(!clean(artist)||!clean(title))continue;
  const at=finite(event.at,0);
  out.push({type:event.type,artist,title,at:clamp(at,0,4102444800000)});
 }
 return out.slice(-1000);
}
export function recordEvent(history,track,type,at=Date.now()){
 if(!EVENTS.has(type)||!track||!clean(track.artist)||!clean(track.title))return cleanEvents(history);
 return cleanEvents([...cleanEvents(history),{type,artist:track.artist,title:track.title,at}]);
}
function signals(history){
 const bySong=new Map(),byArtist=new Map();
 for(const e of cleanEvents(history)){
  const key=songKey(e.artist,e.title),a=artistKey(e.artist);
  const delta={like:8,unlike:-8,complete:3,skip:-4,dislike:-20}[e.type];
  bySong.set(key,clamp((bySong.get(key)||0)+delta,-30,30));
  byArtist.set(a,clamp((byArtist.get(a)||0)+delta/4,-12,12));
 }
 return {bySong,byArtist};
}
function score(track,profile,weights){
 const a=artistKey(track.artist),key=identity(track);
 let total=0;const why=[];
 if((profile.favoriteArtists||[]).some(x=>artistKey(x)===a)){total+=22;why.push('Favorite artist');}
 if((profile.likes||[]).includes(key)){total+=18;why.push('Previously liked');}
 const recent=weights.bySong.get(key)||0,affinity=weights.byArtist.get(a)||0;
 if(recent){total+=recent;why.push('Your listening feedback');}
 if(affinity){total+=affinity;why.push('Artist listening history');}
 if(track.qualityVerified===true){total+=4;why.push('Audio quality measured');}
 return {value:clamp(total,-40,80),why};
}
/** Strictly separate playable local queue, discovery inbox, and owner-approved Gold. */
export function rankCandidates(tracks,profile={},history=[],options={}){
 const blocks=Array.isArray(profile.blocks)?profile.blocks:[];
 const seen=new Set(),weights=signals(history),eligible=[];
 const lane=options.lane||'discovery',max=clamp(Math.floor(finite(options.limit,50)),1,200);
 for(const t of Array.isArray(tracks)?tracks.slice(0,20000):[]){
  if(!t||typeof t.artist!=='string'||typeof t.title!=='string'||!clean(t.artist)||!clean(t.title))continue;
  if(isBlocked(t,blocks))continue;
  const key=identity(t);if(seen.has(key))continue;seen.add(key);
  if(t.rightsVerified===false)continue;
  if(lane==='queue'&&t.playable!==true)continue;
  if(lane==='gold'&&!(t.ownerApproved===true&&t.rightsVerified===true&&t.playable===true))continue;
  const s=score(t,profile,weights);
  eligible.push({track:t,score:s.value,reasons:s.why.length?s.why:['Unrated discovery candidate'],evidence:evidenceState(t),
    requiresApproval:lane!=='gold'&&t.ownerApproved!==true});
 }
 eligible.sort((a,b)=>b.score-a.score||identity(a.track).localeCompare(identity(b.track),'en'));
 if(options.diversity===false)return eligible.slice(0,max);
 const out=[],artistCounts=new Map();
 // Multi-pass diversity: first pass caps each artist at two, second fills remaining slots.
 for(const cap of [2,Infinity]){
  for(const item of eligible){
   if(out.length>=max)break;
   if(out.includes(item))continue;
   const key=artistKey(item.track.artist),count=artistCounts.get(key)||0;
   if(count>=cap)continue;
   out.push(item);artistCounts.set(key,count+1);
  }
 }
 return out;
}
