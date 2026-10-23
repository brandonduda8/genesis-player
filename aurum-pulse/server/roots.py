"""AURUM ROOTS: source-linked artist ancestry and related-listening metadata.

No scraping, paywall bypass, uploads, downloaders, or audio stream resolution.
MusicBrainz (noncommercial prototype; review terms before enterprise release),
Wikidata P737 and public ListenBrainz radio metadata are used separately.
If upstreams lack a relationship, it stays unknown; never fabricate influence.
"""
from __future__ import annotations

import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Callable

MBID = re.compile(r"^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$", re.I)
QID = re.compile(r"^Q[1-9][0-9]{0,11}$")
ARTIST = re.compile(r"^[^\x00-\x1f\x7f]{2,80}$")
_ALLOWED = frozenset({'musicbrainz.org', 'www.wikidata.org', 'api.listenbrainz.org'})
_AGENT = 'AurumPulseRoots/0.2 (personal non-commercial prototype; https://github.com/brandonduda8/genesis-player)'

class RootsError(RuntimeError):
    pass


def validate_artist(value: str) -> str:
    if not isinstance(value, str):
        raise ValueError('Artist must be text')
    if re.search(r'[\x00-\x1f\x7f]', value):
        raise ValueError('Unsafe artist name')
    value = ' '.join(value.split())
    if not ARTIST.fullmatch(value) or value.startswith(('/', '\\')):
        raise ValueError('Artist name must be 2-80 safe characters')
    return value


def public_json(url: str, timeout: float = 7.0) -> dict:
    """Fixed-host, HTTPS-only, no-redirect public metadata requests."""
    p = urllib.parse.urlsplit(url)
    if p.scheme != 'https' or p.hostname not in _ALLOWED or p.username or p.password or p.port:
        raise RootsError('Metadata URL not allowlisted')
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(url, headers={'User-Agent': _AGENT, 'Accept': 'application/json'})
    try:
        with opener.open(req, timeout=timeout) as response:
            body = response.read(750_001)
        if len(body) > 750_000:
            raise RootsError('Upstream metadata too large')
        payload = json.loads(body)
        if not isinstance(payload, dict):
            raise RootsError('Unexpected metadata shape')
        return payload
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, OSError) as exc:
        raise RootsError('Upstream music metadata unavailable') from None


def extract_mbid_artist(results: dict, requested: str) -> dict | None:
    matches = results.get('artists', [])
    for candidate in matches if isinstance(matches, list) else []:
        if not isinstance(candidate, dict):
            continue
        name = candidate.get('name', '')
        if not isinstance(name, str):
            continue
        match = re.sub(r'\W+', ' ', name.casefold()).strip() == re.sub(r'\W+', ' ', requested.casefold()).strip()
        if match and MBID.fullmatch(str(candidate.get('id',''))):
            return {'name': name, 'mbid': candidate['id'].lower(), 'source': f"https://musicbrainz.org/artist/{candidate['id'].lower()}"}
    return None


def parse_relations(artist: dict) -> tuple[list, str | None]:
    collaborators = []
    wikidata = None
    for r in artist.get('relations', []):
        if not isinstance(r, dict):
            continue
        if r.get('target-type') == 'url':
            s = r.get('url') or {}
            address = s.get('resource') if isinstance(s, dict) else None
            if isinstance(address, str):
                m = re.fullmatch(r'https://www\.wikidata\.org/wiki/(Q\d+)', address)
                if m and QID.fullmatch(m.group(1)):
                    wikidata = m.group(1)
        if r.get('target-type') != 'artist' or r.get('type') not in {'collaboration', 'member of band', 'subgroup'}:
            continue
        other = r.get('artist') or {}
        other_mbid = other.get('id','') if isinstance(other, dict) else ''
        name = other.get('name','') if isinstance(other, dict) else ''
        if name and isinstance(name, str) and MBID.fullmatch(str(other_mbid)):
            collaborators.append({'artist':name[:100], 'kind':'documented-relationship',
                'relation':r['type'], 'why': f"MusicBrainz records: {r['type']} (not proof of musical influence).",
                'source':'MusicBrainz', 'sourceUrl':f"https://musicbrainz.org/artist/{other_mbid}",
                'playable':False})
    return collaborators[:10], wikidata


def influence_ids(doc: dict, qid: str) -> list[str]:
    entity = (doc.get('entities') or {}).get(qid) or {}
    claims = (entity.get('claims') or {}).get('P737') or []
    ids = []
    for claim in claims:
        if not isinstance(claim, dict) or claim.get('rank') == 'deprecated':
            continue
        mainsnak = claim.get('mainsnak') or {}
        datavalue = mainsnak.get('datavalue') or {}
        value = datavalue.get('value') or {}
        other = value.get('id') if isinstance(value, dict) else None
        if isinstance(other, str) and QID.fullmatch(other) and other not in ids and other != qid:
            ids.append(other)
    return ids[:12]


def label_influences(labels: dict, ids: list[str], qid: str) -> list[dict]:
    out=[]
    for item in ids:
        details=(labels.get('entities') or {}).get(item) or {}
        name = ((details.get('labels') or {}).get('en') or {}).get('value')
        if not isinstance(name,str) or not name.strip():
            continue
        out.append({'artist':name[:100], 'kind':'documented-influence', 'relation':'influenced by',
            'why':'Wikidata lists this as an influence (community-contributed statement; check its references).',
            'source':'Wikidata P737', 'sourceUrl':f'https://www.wikidata.org/wiki/{qid}',
            'entityUrl':f'https://www.wikidata.org/wiki/{item}', 'playable':False})
    return out


def parse_listenbrainz_radio(payload: dict, source_mbid: str, source_name: str) -> list[dict]:
    # API versions have returned flat lists and artist-grouped dictionaries.
    candidates = payload.get('payload', payload)
    if isinstance(candidates, dict):
        values = list(candidates.values())
        rows = [v for val in values for v in (val if isinstance(val,list) else [val])]
    elif isinstance(candidates,list):
        rows=candidates
    else:
        rows=[]
    result=[]
    seen=set()
    for record in rows:
        if not isinstance(record,dict):
            continue
        mbid = record.get('similar_artist_mbid', '')
        name=record.get('similar_artist_name','')
        if not isinstance(mbid,str) or not MBID.fullmatch(mbid) or not isinstance(name,str):
            continue
        if mbid.lower() == source_mbid.lower() or name.casefold() == source_name.casefold() or mbid in seen:
            continue
        seen.add(mbid)
        result.append({'artist':name[:100], 'kind':'algorithmic-similar', 'relation':'listeners and recordings',
            'why':'ListenBrainz music-discovery association; NOT a documented influence.',
            'source':'ListenBrainz', 'sourceUrl':f'https://musicbrainz.org/artist/{mbid}',
            'playable':False})
        if len(result)==10: break
    return result


class RootsService:
    def __init__(self, fetch: Callable[[str],dict] = public_json, clock: Callable[[],float] = time.monotonic):
        self.fetch = fetch
        self.clock = clock
        self.lock = threading.Lock()
        self.cache = {}
        self.mb_last = None

    def get(self, url: str) -> dict:
        cached = self.cache.get(url)
        now = self.clock()
        if cached and now - cached[0] < 3600:
            return cached[1]
        # Public MusicBrainz asks client to stay at <= 1 call / sec.
        with self.lock:
            now = self.clock()
            cached = self.cache.get(url)
            if cached and now-cached[0]<3600:
                return cached[1]
            if urllib.parse.urlsplit(url).hostname=='musicbrainz.org':
                if self.mb_last is not None:
                    time.sleep(max(0, 1.1-(now-self.mb_last)))
                self.mb_last = self.clock()
            result=self.fetch(url)
            self.cache[url]=(self.clock(),result)
            # Bound cache memory, no persistent log of personal listening history.
            if len(self.cache)>160: self.cache.pop(next(iter(self.cache)))
            return result

    def explore(self, artist_name: str) -> dict:
        name=validate_artist(artist_name)
        query=urllib.parse.urlencode({'query':f'artist:"{name}"','fmt':'json','limit':'6'})
        search=self.get('https://musicbrainz.org/ws/2/artist/?'+query)
        matched=extract_mbid_artist(search,name)
        if not matched:
            return {'status':'ARTIST_NOT_CONFIRMED','query':name,'artist':None,
               'connections':[],'sources':['MusicBrainz'],'playable':False,
               'explanation':'No exact artist identity confirmed. Try a more precise stage name.'}
        uid=matched['mbid']
        detail=self.get('https://musicbrainz.org/ws/2/artist/'+uid+'?inc=artist-rels%2Burl-rels%2Btags&fmt=json')
        others,wikidata=parse_relations(detail)
        influences=[]
        partial=[]
        if wikidata:
            try:
                query=urllib.parse.urlencode({'action':'wbgetentities','ids':wikidata,'props':'claims','format':'json'})
                claims=self.get('https://www.wikidata.org/w/api.php?'+query)
                roots=influence_ids(claims,wikidata)
                if roots:
                    query=urllib.parse.urlencode({'action':'wbgetentities','ids':'|'.join(roots),'props':'labels','languages':'en','format':'json'})
                    influences=label_influences(self.get('https://www.wikidata.org/w/api.php?'+query), roots, wikidata)
            except RootsError:
                partial.append('Wikidata influence lookup unavailable')
        else:
            partial.append('No linked Wikidata entity in MusicBrainz artist record')
        similar=[]
        try:
            params=urllib.parse.urlencode({'mode':'medium','max_similar_artists':'8','max_recordings_per_artist':'1'})
            radio=self.get(f'https://api.listenbrainz.org/1/lb-radio/artist/{uid}?{params}')
            similar=parse_listenbrainz_radio(radio,uid,matched['name'])
        except RootsError:
            partial.append('ListenBrainz related-discovery lookup unavailable')
        tags=[x.get('name') for x in detail.get('tags',[]) if isinstance(x,dict) and isinstance(x.get('name'),str)][:12]
        connections=influences+others+similar
        return {'status':'SOURCE_LINKED_RESULTS' if connections else 'NO_DOCUMENTED_RELATIONSHIPS',
            'query':name,'artist':{**matched,'tags':tags}, 'connections':connections,
            'counts':{'influences':len(influences),'recorded-relationships':len(others),'similar':len(similar)},
            'partial':partial, 'playable':False,'rights':'METADATA_ONLY',
            'explanation':'Connections are source-specific. Similar listening does not prove musical influence.',
            'retrievedAt':datetime.now(timezone.utc).isoformat(),
            'sources':['MusicBrainz','Wikidata P737','ListenBrainz LB Radio']}
