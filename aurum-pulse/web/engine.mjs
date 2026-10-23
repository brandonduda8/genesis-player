/** AURUM PULSE policy core. Pure, deterministic, no network. */
export const MODES=Object.freeze([
 {id:'get-money',label:'Get Money',emoji:'💸',color:'#ffbe70',desc:'Swagger, bounce, irresistible hooks',seeds:['Yung Gravy','Caskey','Mac Miller']},
 {id:'rage',label:'Rage / Dark',emoji:'⚡',color:'#ef5fba',desc:'Rattling bass and haunted distortion',seeds:['Witchouse 40k','Doc Gruesome','Diggy Graves']},
 {id:'sadboy',label:'Sad Boy',emoji:'🌙',color:'#8269ff',desc:'Guitars, honest lyrics, 2 a.m. energy',seeds:['Lil Peep','Doobie','Machine Gun Kelly']},
 {id:'underground',label:'Underground',emoji:'🪐',color:'#45d6ff',desc:'Weird gems you would never find otherwise',seeds:['Witchouse 40k','Diggy Graves','Doc Gruesome']}
]);
export const FAVORITE_SUGGESTIONS=Object.freeze(['Yung Gravy','Mac Miller','Witchouse 40k','Caskey','Lil Peep','Doobie','Diggy Graves','Machine Gun Kelly','Doc Gruesome']);
export const PRESETS=Object.freeze({Studio:[0,0,0],Impact:[4,-1,1],Night:[2,1,-2],Clarity:[-1,3,3]});
export const BASE_PROFILE=()=>({schema:2,onboarded:false,nickname:'Listener',modes:['get-money','rage','sadboy'],favoriteArtists:[],likes:[],blocks:[],volume:72,preset:'Studio',eqBypass:false});
export const clean=x=>String(x??'').normalize('NFKC').toLocaleLowerCase('en-US').replace(/[\u0300-\u036f]/g,'').replace(/[^\p{L}\p{N}]+/gu,' ').trim().replace(/\s+/g,' ');
export const artistKey=artist=>`artist:${clean(artist)}`;
export const songKey=(artist,title)=>`song:${clean(artist)}|${clean(title)}`;
export const isBlocked=(track,blocks)=>{const b=new Set(blocks);return b.has(songKey(track.artist,track.title))||b.has(artistKey(track.artist));};
export const accessibleQueue=(tracks,blocks)=>tracks.filter(t=>!isBlocked(t,blocks)&&t.playable===true);
export const filterTracks=(tracks,query,blocks)=>tracks.filter(t=>!isBlocked(t,blocks)&&`${clean(t.artist)} ${clean(t.title)}`.includes(clean(query)));
export function validateFavoriteArtist(artist){
 if(typeof artist!=='string'||/[\x00-\x1f\x7f]/.test(artist))return null;
 const value=artist.normalize('NFKC').replace(/\s+/g,' ').trim();
 return value.length>=2&&value.length<=80&&!/[\x00-\x1f\x7f]/.test(value)&&!value.startsWith('/')&&!value.startsWith('\\')?value:null;
}
export function normalizedFavorites(items,limit=8){
 const out=[],seen=new Set();
 for(const candidate of Array.isArray(items)?items:[]){
   const artist=validateFavoriteArtist(candidate);if(!artist)continue;
   const id=artistKey(artist);if(seen.has(id))continue;
   seen.add(id);out.push(artist);if(out.length>=limit)break;
 }
 return out;
}
export function safeProfile(input){
 const d=BASE_PROFILE();if(!input||typeof input!=='object'||Array.isArray(input))return d;
 const modes=Array.isArray(input.modes)?input.modes.filter(x=>MODES.some(m=>m.id===x)).slice(0,4):d.modes;
 const validateKeys=(values)=>Array.isArray(values)?[...new Set(values.filter(x=>typeof x==='string'&&x.length<=250&&/^(?:artist|song):/.test(x)))].slice(-3000):[];
 const blocks=validateKeys(input.blocks);
 return {schema:2,onboarded:input.onboarded===true,nickname:typeof input.nickname==='string'&&input.nickname.trim()?input.nickname.trim().slice(0,42):'Listener',modes:[...new Set(modes)],favoriteArtists:normalizedFavorites(input.favoriteArtists).filter(a=>!blocks.includes(artistKey(a))),likes:validateKeys(input.likes),blocks,volume:Number.isFinite(input.volume)?Math.max(0,Math.min(100,Math.round(input.volume))):72,preset:Object.hasOwn(PRESETS,input.preset)?input.preset:'Studio',eqBypass:input.eqBypass===true};
}
export const qualityLabel=(t)=> t?.qualityVerified===true?t.qualityLabel||'MEASURED':t?.playable?'LOCAL · FIDELITY UNKNOWN':'NOT VERIFIED';
export function describeService(s){const allowed=new Set(['CONFIGURED_NOT_TESTED','BLOCKED_CREDENTIALS','BLOCKED_ACCESS','INTEGRATION_PLANNED','UNKNOWN','TESTED','CONNECTED']);return allowed.has(s)?s:'UNKNOWN';}
export const clampDb=x=>Number.isFinite(Number(x))?Math.min(6,Math.max(-6,Math.round(Number(x)))):0;
export const percent=n=>Math.max(0,Math.min(100,Number(n)||0));
