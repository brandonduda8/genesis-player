import {MODES,FAVORITE_SUGGESTIONS,PRESETS,BASE_PROFILE,clean,artistKey,songKey,isBlocked,accessibleQueue,filterTracks,safeProfile,validateFavoriteArtist,normalizedFavorites,qualityLabel,clampDb,percent} from './engine.mjs';
import {rankCandidates,recordEvent,cleanEvents} from './taste-intelligence.mjs';
const $=id=>document.getElementById(id), qs=s=>document.querySelector(s), qsa=s=>Array.from(document.querySelectorAll(s));
let profile=BASE_PROFILE();try{profile=safeProfile(JSON.parse(localStorage.getItem('aurum.pulse.profile.v2')||localStorage.getItem('aurum.pulse.profile.v1')||'null'));}catch{}
const save=()=>{try{localStorage.setItem('aurum.pulse.profile.v2',JSON.stringify(profile));}catch{toast('Private browser storage is disabled; changes last only this session.');}};
let tasteHistory=[];try{tasteHistory=cleanEvents(JSON.parse(localStorage.getItem('aurum.pulse.taste-events.v1')||'[]'));}catch{}
function remember(track,type){tasteHistory=recordEvent(tasteHistory,track,type);try{localStorage.setItem('aurum.pulse.taste-events.v1',JSON.stringify(tasteHistory));}catch{}}

const library=[];let queue=[];let currentId=null;let selectedView='home';let step=0;let pendingModes=new Set(profile.modes);let pendingName=profile.nickname;let pendingArtists=[...profile.favoriteArtists];let ctx=null;let analyser=null;let bass=null;let presence=null;let treble=null;let bypass=null;let compressor=null;let source=null;let visualFrame=0;let toastTimer=null;
const audio=$('audio');audio.volume=profile.volume/100;
function el(tag,className='',text=''){const e=document.createElement(tag);if(className)e.className=className;if(text)e.textContent=text;return e;}
function toast(msg){const e=$('toast');e.textContent=String(msg);e.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>e.classList.remove('show'),3900);}
function setView(view){if(!$(view))return;selectedView=view;qsa('.view').forEach(e=>e.classList.toggle('active',e.id===view));qsa('.nav-item').forEach(e=>{e.classList.toggle('active',e.dataset.view===view);if(e.dataset.view===view)e.setAttribute('aria-current','page');else e.removeAttribute('aria-current');});$('section-name').textContent={home:'THE LISTENING ROOM',library:'YOUR LIBRARY',discover:'DISCOVERY LAB',sound:'SOUND LAB',services:'CONNECTED SERVICES'}[view]||'AURUM';document.title='AURUM PULSE · '+view[0].toUpperCase()+view.slice(1);if(view==='sound')drawSpectrum();}
qsa('[data-view]').forEach(b=>b.addEventListener('click',()=>setView(b.dataset.view)));
qsa('[data-go]').forEach(b=>b.addEventListener('click',()=>setView(b.dataset.go)));
$('search').addEventListener('input',()=>{if(selectedView==='home')renderTracks();else if(selectedView!=='library')setView('library');renderTracks();});
function renderModes(){const g=$('mode-grid');g.replaceChildren();MODES.forEach(m=>{const b=el('button','mode');b.style.setProperty('--mood',m.color);b.classList.toggle('selected',profile.modes.includes(m.id));b.setAttribute('aria-pressed',String(profile.modes.includes(m.id)));b.append(el('div','mode-icon',m.emoji),el('h3','',m.label),el('p','',m.desc));b.addEventListener('click',()=>{const set=new Set(profile.modes);if(set.has(m.id))set.delete(m.id);else set.add(m.id);profile.modes=[...set];save();renderModes();renderDiscovery();updateMoodLabel();});g.append(b);});}
function updateMoodLabel(){$('mood-selection').textContent=profile.modes.length?profile.modes.map(x=>MODES.find(m=>m.id===x)?.label).join(' · '):'Taste not selected';}
function renderTracks(){for(const target of ['home-tracks','library-tracks']){let container=$(target);container.replaceChildren();const records=filterTracks(library,$('search').value,profile.blocks);if(!records.length){container.append(el('div','empty',library.length?'No playable tracks match. Adjust search or exclusions.':'No songs imported yet. Add audio you own or are authorized to play to start listening.'));continue;}records.slice(0,target==='home-tracks'?5:1000).forEach(t=>{const row=el('div','track-row');const cover=el('div','mini-cover','♫');const meta=el('div','track-text');meta.append(el('div','track-name',t.title),el('div','track-sub',t.artist));const badge=el('span','quality',qualityLabel(t));const play=el('button','row-btn',currentId===t.id&&!audio.paused?'Ⅱ':'▶');play.setAttribute('aria-label',`Play ${t.title}`);play.addEventListener('click',()=>playTrack(t.id));const like=el('button','row-btn',profile.likes.includes(songKey(t.artist,t.title))?'♥':'♡');like.setAttribute('aria-label',`Like ${t.title}`);like.addEventListener('click',()=>toggleLike(t));const block=el('button','row-btn block','⊘');block.setAttribute('aria-label',`Exclude ${t.title}`);block.addEventListener('click',()=>blockTrack(t));row.append(cover,meta,badge,play,like,block);container.append(row);});} $('track-count').textContent=String(accessibleQueue(library,profile.blocks).length);}
function toggleLike(t){const key=songKey(t.artist,t.title);const set=new Set(profile.likes);set.has(key)?set.delete(key):set.add(key);profile.likes=[...set];remember(t,set.has(key)?'like':'unlike');save();renderTracks();toast(set.has(key)?'Saved to your likes.':'Removed from likes.');}
function blockTrack(t){const key=songKey(t.artist,t.title);profile.blocks=[...new Set([...profile.blocks,key])];profile.likes=profile.likes.filter(x=>x!==key);remember(t,'dislike');if(currentId===t.id){audio.pause();audio.removeAttribute('src');audio.load();currentId=null;}save();updateNow();renderTracks();toast('Hidden from your player. Exclusion saved, original file untouched.');}
$('library-add').addEventListener('click',()=> $('file-picker').click());$('import-button').addEventListener('click',()=> $('file-picker').click());
$('file-picker').addEventListener('change',e=>{const files=Array.from(e.target.files||[]);let added=0;for(const file of files){if(!file.type.startsWith('audio/')&&!/\.(mp3|m4a|flac|ogg|wav|opus|aac)$/i.test(file.name))continue;const title=file.name.replace(/\.[^/.]+$/,'').replace(/_/g,' ').slice(0,180);const track={id:`local_${Date.now()}_${Math.random().toString(36).slice(2)}`,artist:'My files',title,fileName:file.name,localUrl:URL.createObjectURL(file),playable:true,qualityVerified:false,size:file.size};library.push(track);added++;}e.target.value='';renderTracks();setView('library');toast(added?`${added} audio file${added===1?'':'s'} ready. Stored only in this tab.`:'No supported audio files selected.');});
function currentTrack(){return library.find(t=>t.id===currentId)||null;}
function refreshQueue(){queue=rankCandidates(accessibleQueue(library,profile.blocks),profile,tasteHistory,{lane:'queue',limit:library.length,diversity:false}).map(x=>x.track.id);}
async function playTrack(id){const t=library.find(x=>x.id===id);if(!t||isBlocked(t,profile.blocks)){toast('This track is unavailable or excluded.');return;}refreshQueue();if(currentId!==id){currentId=id;audio.src=t.localUrl||t.streamUrl;}try{await initializeAudio();await ctx?.resume();await audio.play();}catch(e){toast('Cannot play this format in this browser. '+String(e?.name||'Playback error'));}updateNow();renderTracks();}
function updateNow(){const t=currentTrack();$('now-title').textContent=t?t.title:'Nothing playing yet';$('now-artist').textContent=t?t.artist:'Add your own music to begin';$('quality-label').textContent=qualityLabel(t);$('toggle-play').textContent=audio.paused?'▶':'Ⅱ';$('toggle-play').setAttribute('aria-label',audio.paused?'Play':'Pause');$('love-current').textContent=t&&profile.likes.includes(songKey(t.artist,t.title))?'♥':'♡';if('mediaSession'in navigator){try{navigator.mediaSession.metadata=t?new MediaMetadata({title:t.title,artist:t.artist,album:'AURUM PULSE · My Library'}):null;}catch{}}}
$('toggle-play').addEventListener('click',()=>{if(!currentTrack()){refreshQueue();if(queue.length)playTrack(queue[0]);else toast('Add audio files to listen.');}else if(audio.paused){playTrack(currentId);}else{audio.pause();updateNow();}});
function advance(dir){const prior=currentTrack();if(dir>0&&prior&&!audio.ended)remember(prior,'skip');refreshQueue();if(!queue.length){toast('Your library is empty.');return;}const idx=queue.indexOf(currentId);const next=idx<0?0:(idx+dir+queue.length)%queue.length;playTrack(queue[next]);}
$('next').addEventListener('click',()=>advance(1));$('prev').addEventListener('click',()=>advance(-1));audio.addEventListener('ended',()=>{const t=currentTrack();if(t)remember(t,'complete');advance(1);});audio.addEventListener('play',updateNow);audio.addEventListener('pause',updateNow);
function fmt(sec){if(!Number.isFinite(sec)||sec<0)return'0:00';const v=Math.floor(sec);return `${Math.floor(v/60)}:${String(v%60).padStart(2,'0')}`;}
audio.addEventListener('timeupdate',()=>{$('elapsed').textContent=fmt(audio.currentTime);$('duration').textContent=fmt(audio.duration);$('seek').value=Number.isFinite(audio.duration)&&audio.duration>0?String(Math.round(audio.currentTime/audio.duration*100)):'0';});audio.addEventListener('loadedmetadata',()=>{$('duration').textContent=fmt(audio.duration);});audio.addEventListener('error',()=>{if(audio.src)toast('Audio source failed. Check the file or browser codec support.');});
$('seek').addEventListener('input',()=>{if(Number.isFinite(audio.duration)&&audio.duration>0)audio.currentTime=audio.duration*percent($('seek').value)/100;});$('volume').value=String(profile.volume);$('volume').addEventListener('input',e=>{profile.volume=percent(e.target.value);audio.volume=profile.volume/100;save();});$('love-current').addEventListener('click',()=>{const t=currentTrack();if(t)toggleLike(t);});$('block-current').addEventListener('click',()=>{const t=currentTrack();if(t)blockTrack(t);});
function renderDiscovery(){const f=$('discovery-filters');f.replaceChildren();const g=$('discovery-grid');g.replaceChildren();const modes=profile.modes.length?MODES.filter(m=>profile.modes.includes(m.id)):MODES;for(const m of modes){const chip=el('button','filter-chip',m.emoji+' '+m.label);chip.addEventListener('click',()=>{profile.modes=[m.id];save();renderModes();renderDiscovery();updateMoodLabel();});f.append(chip);for(const artist of m.seeds){if(profile.blocks.includes(artistKey(artist)))continue;const card=el('article','discovery-card');card.append(el('div','icon',m.emoji),el('div','mood',m.label.toUpperCase()),el('h3','',artist),el('p','',`Seed artist for ${m.desc.toLowerCase()}. Artist metadata only — not a verified playable recording.`));const link=el('a','','Find artist releases ↗');link.href='https://musicbrainz.org/search?query='+encodeURIComponent(artist)+'&type=artist&method=indexed';link.target='_blank';link.rel='noopener noreferrer';const block=el('button','',"Don't recommend this artist");block.addEventListener('click',()=>{profile.blocks=[...new Set([...profile.blocks,artistKey(artist)])];save();renderDiscovery();toast(`${artist} blocked from discovery suggestions.`);});card.append(link,block);g.append(card);}}if(!g.children.length)g.append(el('div','empty','All current suggestion seeds are blocked. Change moods or reset your exclusions.'));}
function saveFavoriteArtist(name){
 const input=validateFavoriteArtist(name);
 if(!input){toast('Choose a complete artist name (2–80 characters).');return false;}
 if(profile.blocks.includes(artistKey(input))){toast('This artist is excluded. Restore exclusions first.');return false;}
 const favorites=normalizedFavorites([...profile.favoriteArtists,input],8);
 if(!favorites.some(x=>artistKey(x)===artistKey(input)))return false;
 profile.favoriteArtists=favorites;save();renderFavoriteRoots();return true;
}
function renderFavoriteRoots(){
 const box=$('roots-favorites');box.replaceChildren();
 if(!profile.favoriteArtists.length)box.append(el('p','note','Select two or more favorite artists in your sound identity to start a personalized journey.'));
 for(const artist of profile.favoriteArtists){
  if(profile.blocks.includes(artistKey(artist)))continue;
  const button=el('button','artist-pill','✦ '+artist);button.type='button';button.title=`Trace ${artist}'s musical roots`;
  button.addEventListener('click',()=>{ $('roots-query').value=artist;exploreRoots(artist); });box.append(button);
 }
}
const ROOT_KINDS={
 'documented-influence':['DOCUMENTED INFLUENCE','What shaped the sound'],
 'documented-relationship':['ARTIST CONNECTION','Related creative history'],
 'algorithmic-similar':['DISCOVERY LINK','A sound you might like']
};
let rootsGeneration=0;
function rootsExternalLink(url,label){
 if(typeof url!=='string'||!/^https:\/\/(?:musicbrainz\.org|www\.wikidata\.org|listenbrainz\.org)\//.test(url))return el('span','note','Source unavailable');
 const a=el('a','roots-source',label);a.href=url;a.target='_blank';a.rel='noopener noreferrer';return a;
}
async function exploreRoots(name){
 const artist=validateFavoriteArtist(name);
 if(!artist){toast('Enter a complete artist name.');return;}
 const container=$('roots-results');container.replaceChildren(el('div','roots-loading',`Tracing ${artist}'s roots from MusicBrainz, Wikidata and ListenBrainz…`));
 const gen=++rootsGeneration;
 $('roots-query').value=artist;
 try{
  const ac=new AbortController(),timer=setTimeout(()=>ac.abort(),23000);
  let r;try{r=await fetch('/api/roots/artist?q='+encodeURIComponent(artist),{signal:ac.signal,cache:'no-store'});}finally{clearTimeout(timer);}
  if(gen!==rootsGeneration)return;
  const data=await r.json();
  if(!r.ok)throw Error(data.error||'ROOTS_UNAVAILABLE');
  container.replaceChildren();
  if(data.status==='ARTIST_NOT_CONFIRMED'){
   container.append(el('p','info-strip','This artist was not uniquely confirmed in MusicBrainz. No influence history was invented. Try their full name or verify the artist identity first.'));
   const link=rootsExternalLink('https://musicbrainz.org/search?query='+encodeURIComponent(artist)+'&type=artist&method=indexed','Review artist search ↗');
   // MusicBrainz search link is intentionally a human-driven fallback, not autoplay.
   if(link.tagName==='A')container.append(link);
   return;
  }
  const top=el('div','roots-result-heading');const left=el('div');left.append(el('span','eyebrow','YOUR SONIC FAMILY TREE'),el('h3','',data.artist?.name||artist),el('p','note',`Artist identity: ${data.artist?.mbid||'unknown'} · External source relationships, not licensed audio.`));
  top.append(left,rootsExternalLink(data.artist?.source||'','View MusicBrainz source ↗'));container.append(top);
  if(!profile.favoriteArtists.some(x=>artistKey(x)===artistKey(artist))){const save=el('button','secondary','♡ Add to my artists');save.addEventListener('click',()=>{if(saveFavoriteArtist(artist))save.textContent='♥ Saved to favorites';});container.append(save);}
  if(data.artist?.tags?.length){const tags=el('div','artist-pills');for(const tag of data.artist.tags.slice(0,8))tags.append(el('span','tag-pill',tag));container.append(tags);}
  const relations=Array.isArray(data.connections)?data.connections.filter(c=>c?.artist&&!profile.blocks.includes(artistKey(c.artist))):[];
  for(const kind of Object.keys(ROOT_KINDS)){
    const members=relations.filter(c=>c.kind===kind);
    if(!members.length)continue;
    const [headline,desc]=ROOT_KINDS[kind];const section=el('section','root-family');const heading=el('div','section-head');
    const wrap=el('div');wrap.append(el('span','eyebrow',headline),el('h4','',desc));heading.append(wrap);section.append(heading);
    const grid=el('div','roots-cards');
    for(const found of members){
      const card=el('div','root-person');card.append(el('span','roots-badge',found.source||'Source'),el('h5','',found.artist),el('p','',found.why||'Artist connection from a linked source.'));
      const buttons=el('div','root-actions');const more=el('button','secondary','Explore deeper ↗');more.addEventListener('click',()=>{exploreRoots(found.artist);container.scrollIntoView({behavior:'smooth',block:'start'});});
      buttons.append(more,rootsExternalLink(found.sourceUrl,'Evidence'));card.append(buttons);grid.append(card);
    }
    section.append(grid);container.append(section);
  }
  if(!relations.length)container.append(el('div','info-strip','No source-backed connection data was available for this artist yet. Try another favorite or look through the verified artist page. Missing relationships are not invented.'));
  const disclosure=el('p','roots-disclosure',data.explanation||'Related music metadata is not automatically playable.');
  container.append(disclosure);
  if(Array.isArray(data.partial)&&data.partial.length)container.append(el('p','note','Some metadata sources were unavailable: '+data.partial.join('; ')));
 }catch(e){
  if(gen!==rootsGeneration)return;
  container.replaceChildren(el('div','info-strip','Aurum Roots could not reach an authorized metadata source. Your favorite artists remain saved locally; you can still browse your music or try again later.'));
  toast('Artist roots unavailable — nothing was changed in your library.');
 }
}
$('roots-form').addEventListener('submit',event=>{event.preventDefault();exploreRoots($('roots-query').value.trim());});

function renderServices(status){const data=[{name:'Aurum local player',state:'IMPLEMENTED_LOCAL',detail:'Real browser file playback, queue, audio effects and local taste controls.'},{name:'Navidrome / OpenSubsonic',state:status?.navidrome||'UNKNOWN',detail:'Owner-authorized personal library; server-side credential adapter only.'},{name:'Aurum Roots / MusicBrainz',state:'METADATA_ON_DEMAND',detail:'Consent-driven artist relationships and sonic family tree; no audio entitlement.'},{name:'Apollo discovery',state:'INTEGRATION_PLANNED',detail:'Existing Genesis Android curation and source-backed suggestions; awaiting safe adapter.'},{name:'Audius / SoundCloud',state:'INTEGRATION_PLANNED',detail:'Existing Android adapters require licensing, robustness and safety review.'},{name:'AudioMuse sonic search',state:'INTEGRATION_PLANNED',detail:'Analysis service only on separately qualified ≥8 GiB host.'},{name:'Genesis A2A / MCP',state:'INTEGRATION_PLANNED',detail:'Proposed capability adapter; no privileged agent access from player.'}];const g=$('service-grid');g.replaceChildren();for(const s of data){const card=el('div','service-card');card.append(el('span','badge',s.state),el('strong','',s.name),el('p','',s.detail));g.append(card);}}
async function health(){try{const r=await fetch('/api/health',{cache:'no-store'});if(!r.ok)throw Error('unavailable');const s=await r.json();$('server-state').textContent=`Local service ${s.service==='aurum-pulse'?'online':'unknown'}`;$('status-dot').classList.toggle('online',s.service==='aurum-pulse');const nav=s.navidrome||'UNKNOWN';renderServices(s);$('service-detail').textContent=`Local API: ${s.service==='aurum-pulse'?'RUNNING':'UNKNOWN'}\nNavidrome: ${nav}\nCatalog: ${s.catalog||'UNAVAILABLE'}\nNote: ${s.message||'No production streaming asserted.'}`;}catch{$('server-state').textContent='Static/offline mode';renderServices({navidrome:'UNKNOWN'});$('service-detail').textContent='Service backend unavailable; local file playback still works in the browser.';}}
$('refresh-service').addEventListener('click',health);
async function searchNavidrome(){const q=$('navidrome-query').value.trim();const target=$('navidrome-results');target.replaceChildren();if(!q){toast('Enter an artist, album, or song title to search your server.');return;}try{const r=await fetch('/api/navidrome/search?q='+encodeURIComponent(q),{cache:'no-store'});const data=await r.json();if(!r.ok)throw Error(data.error||'SEARCH_UNAVAILABLE');let count=0;for(const s of data.songs||[]){if(isBlocked(s,profile.blocks))continue;const t={id:'nd_'+s.id,artist:s.artist,title:s.title,streamUrl:s.streamPath,origin:'navidrome',playable:true,qualityVerified:false};let existing=library.find(x=>x.id===t.id);if(!existing)library.push(t);const b=el('button','track-row');b.append(el('div','mini-cover','♫'),el('div','track-text',s.artist+' — '+s.title),el('span','quality','UNVERIFIED QUALITY'));b.addEventListener('click',()=>playTrack(t.id));target.append(b);count++;}renderTracks();if(!count)target.append(el('div','empty','No matching authorized library tracks, or all results are excluded.'));toast(count+ ' song(s) found in your authorized server library. Not added to Gold.');}catch(e){target.append(el('div','empty','Navidrome search unavailable. Configure a private server and authorized credentials before connecting.'));toast('Navidrome search blocked: '+String(e.message||e));}}
$('navidrome-search').addEventListener('click',searchNavidrome);$('navidrome-query').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();searchNavidrome();}});
function setBypass(b){profile.eqBypass=Boolean(b);if(bypass){bypass.gain.value=profile.eqBypass?1:0;}if(ctx&&bass){let stage=ctx.currentTime; // A/B router: source -> dry and wet -> compressor -> destination
 if(wetGain)wetGain.gain.setTargetAtTime(profile.eqBypass?0:1,stage,.015);if(dryGain)dryGain.gain.setTargetAtTime(profile.eqBypass?1:0,stage,.015);
}$('eq-bypass').textContent=profile.eqBypass?'Enable EQ':'Bypass EQ';$('eq-bypass').setAttribute('aria-pressed',String(profile.eqBypass));save();}
let wetGain=null,dryGain=null;function initializeAudio(){if(ctx)return Promise.resolve();const AC=window.AudioContext||window.webkitAudioContext;if(!AC){toast('Web Audio effects are unavailable in this browser.');return Promise.resolve();}ctx=new AC();source=ctx.createMediaElementSource(audio);bass=ctx.createBiquadFilter();bass.type='lowshelf';bass.frequency.value=125;presence=ctx.createBiquadFilter();presence.type='peaking';presence.frequency.value=2400;presence.Q.value=.95;treble=ctx.createBiquadFilter();treble.type='highshelf';treble.frequency.value=6500;wetGain=ctx.createGain();dryGain=ctx.createGain();const comp=ctx.createDynamicsCompressor();comp.threshold.value=-13;comp.knee.value=12;comp.ratio.value=5;comp.attack.value=.003;comp.release.value=.2;analyser=ctx.createAnalyser();analyser.fftSize=256;source.connect(bass);bass.connect(presence);presence.connect(treble);treble.connect(wetGain);source.connect(dryGain);wetGain.connect(comp);dryGain.connect(comp);comp.connect(analyser);analyser.connect(ctx.destination);setBypass(profile.eqBypass);applyEQ();return ctx.resume();}
const controls={bass:$('bass'),presence:$('presence'),treble:$('treble')};function applyEQ(){if(!bass)return;bass.gain.value=clampDb(controls.bass.value);presence.gain.value=clampDb(controls.presence.value);treble.gain.value=clampDb(controls.treble.value);}
function setEQPreset(name){if(!Object.hasOwn(PRESETS,name))return;profile.preset=name;const [lo,mid,hi]=PRESETS[name];controls.bass.value=String(lo);controls.presence.value=String(mid);controls.treble.value=String(hi);updateEQLabels();save();}
function updateEQLabels(){for(const [id,input] of Object.entries(controls)){$(id+'-value').textContent=`${clampDb(input.value)>0?'+':''}${clampDb(input.value)} dB`;}}
const presets=$('preset-row');for(const name of Object.keys(PRESETS)){const b=el('button','preset',name);b.classList.toggle('active',profile.preset===name);b.addEventListener('click',()=>{setEQPreset(name);qsa('.preset').forEach(x=>x.classList.toggle('active',x.textContent===name));$('sound-mode').textContent=name+' / Tone';});presets.append(b);}setEQPreset(profile.preset);$('sound-mode').textContent=profile.preset+' / Tone';
for(const [id,input]of Object.entries(controls)){input.addEventListener('input',()=>{applyEQ();updateEQLabels();profile.preset='Custom';qsa('.preset').forEach(b=>b.classList.remove('active'));$('sound-mode').textContent='Custom / Tone';});}
$('eq-bypass').addEventListener('click',()=>setBypass(!profile.eqBypass));setBypass(profile.eqBypass);
function drawSpectrum(){cancelAnimationFrame(visualFrame);const canvas=$('visualizer');if(!canvas)return;const c=canvas.getContext('2d');if(!c)return;const w=canvas.width,h=canvas.height;const paint=()=>{if(selectedView!=='sound')return;c.clearRect(0,0,w,h);const grd=c.createLinearGradient(0,0,w,0);grd.addColorStop(0,'#ffca7d');grd.addColorStop(.45,'#f560bd');grd.addColorStop(1,'#3edfff');c.strokeStyle=grd;c.lineWidth=5;c.lineCap='round';if(analyser&&!audio.paused){const data=new Uint8Array(analyser.frequencyBinCount);analyser.getByteFrequencyData(data);const count=56;for(let i=0;i<count;i++){const sample=data[Math.floor(i/(count-1)*data.length*.8)]||0;const amp=Math.max(4,sample/255*h*.84);let x=24+i*(w-48)/(count-1);c.beginPath();c.moveTo(x,h/2-amp/2);c.lineTo(x,h/2+amp/2);c.stroke();}}else{c.globalAlpha=.6;c.beginPath();c.moveTo(15,h/2);c.lineTo(w-15,h/2);c.stroke();c.globalAlpha=1;c.font='18px sans-serif';c.fillStyle='#a1acc7';c.textAlign='center';c.fillText('Play audio for a live spectrum',w/2,h/2-26);}visualFrame=requestAnimationFrame(paint);};paint();}
function exportTaste(){const blob=new Blob([JSON.stringify({...profile,tasteHistory,exportedAt:new Date().toISOString(),note:'AURUM PULSE local preference profile and listening feedback only, no streamed audio.'},null,2)],{type:'application/json'});const u=URL.createObjectURL(blob);const a=document.createElement('a');a.href=u;a.download='aurum-pulse-taste-profile.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),2000);}
$('export-taste').addEventListener('click',exportTaste);$('reset-taste').addEventListener('click',()=>{if(!confirm('Restore hidden tracks and artists in this browser? No audio files are deleted.'))return;profile.blocks=[];save();renderTracks();renderDiscovery();toast('Exclusion filters cleared.');});
function showWelcome(){step=0;pendingModes=new Set(profile.modes);pendingName=profile.nickname;pendingArtists=[...profile.favoriteArtists];$('welcome').classList.add('open');$('welcome').setAttribute('aria-hidden','false');renderWelcome();}
function closeWelcome(){$('welcome').classList.remove('open');$('welcome').setAttribute('aria-hidden','true');$('profile').focus();}
function renderWelcome(){
 const root=$('welcome-content');root.replaceChildren();
 $('step-index').textContent=`0${step+1} / 04`;
 $('back-step').textContent=step===0?'Close':'Back';
 $('next-step').textContent=step===3?'Enter AURUM PULSE':'Continue';
 $('next-step').disabled=step===1&&pendingArtists.length<2;
 if(step===0){
  root.append(el('h2','','Your music. Your universe.'),el('p','','Pick a few artists you love. Aurum will help trace their roots and discover connections that go beyond ordinary genre recommendations. Your favorites stay yours, even without a streaming subscription.'));
  const features=el('div','welcome-options');for(const [a,b] of [['✦ Roots that run deep','Discover actual influence connections'],['⚡ No random filler','Your taste controls every direction'],['◉ Sound that moves you','Real EQ with honest fidelity labels'],['◈ Your own library','Your music, regardless of subscriptions']]){const c=el('div','welcome-option');c.append(el('strong','',a),el('small','',b));features.append(c);}root.append(features);
 }else if(step===1){
  root.append(el('h2','','Who made you love music?'),el('p','','Choose at least two artists and up to five. Aurum uses them as seeds for its musical family tree, not as permission to import or play random songs. Artist metadata is fetched only if you explore a branch.'));
  const selected=el('div','artist-pills');for(const a of pendingArtists){const b=el('button','artist-pill',a+'  ×');b.type='button';b.title='Remove '+a;b.addEventListener('click',()=>{pendingArtists=pendingArtists.filter(x=>artistKey(x)!==artistKey(a));renderWelcome();});selected.append(b);}root.append(selected);
  const form=el('form','roots-form welcome-artist-form');const label=el('label','sr-only','Artist name');label.htmlFor='welcome-artist';const input=el('input');input.id='welcome-artist';input.type='text';input.maxLength=80;input.placeholder='Add an artist you love';input.autocomplete='off';const submit=el('button','secondary','Add artist +');submit.type='submit';form.append(label,input,submit);
  form.addEventListener('submit',e=>{e.preventDefault();const value=validateFavoriteArtist(input.value);if(!value){toast('Enter a complete artist name.');return;}if(profile.blocks.includes(artistKey(value))){toast('Artist is excluded in your profile.');return;}if(pendingArtists.some(x=>artistKey(x)===artistKey(value)))return;if(pendingArtists.length>=5){toast('Choose up to five starting favorites.');return;}pendingArtists.push(value);renderWelcome();});
  root.append(form,el('p','note',`${pendingArtists.length} of 5 chosen · choose at least 2 to continue`));
  const suggestions=el('div','favorite-suggestions');for(const a of FAVORITE_SUGGESTIONS){if(pendingArtists.some(x=>artistKey(x)===artistKey(a))||profile.blocks.includes(artistKey(a)))continue;const b=el('button','suggestion-chip','＋ '+a);b.type='button';b.addEventListener('click',()=>{if(pendingArtists.length>=5){toast('Choose up to five artists.');return;}pendingArtists.push(a);renderWelcome();});suggestions.append(b);}root.append(suggestions);
 }else if(step===2){
  root.append(el('h2','','What hits hardest?'),el('p','','Choose your listening energies. The roots tree combines these preferences with your chosen artists, while keeping discoveries separate from your protected music.'));
  const opts=el('div','welcome-options');for(const m of MODES){const b=el('button','welcome-option');b.classList.toggle('selected',pendingModes.has(m.id));b.append(el('strong','',m.emoji+' '+m.label),el('small','',m.desc));b.setAttribute('aria-pressed',String(pendingModes.has(m.id)));b.addEventListener('click',()=>{pendingModes.has(m.id)?pendingModes.delete(m.id):pendingModes.add(m.id);renderWelcome();});opts.append(b);}root.append(opts);
 }else{
  root.append(el('h2','','Your soundtrack starts here.'),el('p','','Choose your listening name. Aurum saves favorite artists and exclusions in this browser, and you can export your profile whenever you want. No subscription is required to keep this taste map.'));
  const label=el('label','','Your listening name');label.setAttribute('for','listener-name');const input=el('input');input.id='listener-name';input.type='text';input.maxLength=42;input.value=pendingName;input.placeholder='Listener';input.addEventListener('input',()=>{pendingName=input.value;});root.append(label,input);
  const summary=el('div','info-strip','Your starting artists: '+pendingArtists.join(' · ')+'. Aurum will not add or play music without authorized audio access.');root.append(summary);
 }
}
$('back-step').addEventListener('click',()=>{if(step===0){closeWelcome();return;}if(step===3)pendingName=$('listener-name')?.value||pendingName;step--;renderWelcome();});
$('next-step').addEventListener('click',()=>{
 if(step===1&&pendingArtists.length<2){toast('Pick at least two favorite artists to personalize your discovery.');return;}
 if(step===3){
  pendingName=$('listener-name')?.value||pendingName;
  profile.nickname=pendingName.trim().slice(0,42)||'Listener';profile.favoriteArtists=normalizedFavorites(pendingArtists,5);
  profile.modes=[...pendingModes];profile.onboarded=true;save();closeWelcome();
  renderModes();renderDiscovery();renderFavoriteRoots();updateMoodLabel();setView('discover');toast(`Your journey starts here, ${profile.nickname}. Choose a favorite to explore its roots.`);return;
 }
 step++;renderWelcome();
});
$('open-onboarding').addEventListener('click',showWelcome);$('profile').addEventListener('click',showWelcome);
window.addEventListener('keydown',e=>{if(['INPUT','TEXTAREA','BUTTON'].includes(document.activeElement?.tagName)||$('welcome').classList.contains('open'))return;if(e.code==='Space'){e.preventDefault();$('toggle-play').click();}if(e.code==='ArrowRight'&&e.altKey){e.preventDefault();advance(1);}if(e.code==='ArrowLeft'&&e.altKey){e.preventDefault();advance(-1);}});
if('mediaSession'in navigator){try{navigator.mediaSession.setActionHandler('play',()=>{$('toggle-play').click();});navigator.mediaSession.setActionHandler('pause',()=>{audio.pause();updateNow();});navigator.mediaSession.setActionHandler('nexttrack',()=>advance(1));navigator.mediaSession.setActionHandler('previoustrack',()=>advance(-1));}catch{}}
window.addEventListener('pagehide',()=>{for(const t of library)if(t.localUrl?.startsWith('blob:'))URL.revokeObjectURL(t.localUrl);});
renderModes();renderDiscovery();renderFavoriteRoots();renderTracks();updateMoodLabel();updateNow();health();if(!profile.onboarded)showWelcome();
