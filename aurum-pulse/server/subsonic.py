"""Minimal owner-local OpenSubsonic *read-only* integration.

No external lookup runs at import. Nothing stores/prints credentials.
Production use requires a real Genesis authentication proxy and network review.
"""
from __future__ import annotations
import hashlib
import json
import os
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable

SAFE_ID = re.compile(r"^[\w.:-]{1,160}$", re.ASCII)

class ConfigError(ValueError):
    pass

class UpstreamError(RuntimeError):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

NO_REDIRECT_OPENER = urllib.request.build_opener(NoRedirect())

@dataclass(frozen=True)
class Config:
    base: str
    username: str
    password: str

    @staticmethod
    def from_environment(env: dict | None = None) -> 'Config | None':
        env = os.environ if env is None else env
        raw = (env.get('AURUM_NAVIDROME_URL') or '').rstrip('/')
        user = env.get('AURUM_NAVIDROME_USER') or ''
        passwd = env.get('AURUM_NAVIDROME_PASSWORD') or ''
        if not (raw or user or passwd):
            return None
        if not (raw and user and passwd):
            raise ConfigError('Navidrome configured incompletely; provide URL, username, and password privately')
        p = urllib.parse.urlparse(raw)
        is_loopback = p.hostname in ('localhost', '127.0.0.1', '::1')
        if p.scheme != 'https' and not (p.scheme == 'http' and is_loopback):
            raise ConfigError('Remote Navidrome must use HTTPS; HTTP permitted only for local loopback')
        if not p.hostname or p.username or p.password or p.query or p.fragment or p.params:
            raise ConfigError('Navidrome URL must be a plain origin without embedded credentials or query')
        try:
            port = p.port
        except ValueError:
            raise ConfigError('Invalid port') from None
        if port is not None and not 1 <= port <= 65535:
            raise ConfigError('Invalid port')
        if '\\' in raw or '\n' in raw or '\r' in raw:
            raise ConfigError('Unsafe Navidrome URL')
        return Config(base=raw, username=user, password=passwd)

class Subsonic:
    def __init__(self, cfg: Config, opener: Callable | None = None):
        self.cfg = cfg
        self.opener = opener or NO_REDIRECT_OPENER.open

    def url(self, endpoint: str, params: dict | None = None) -> str:
        if endpoint not in ('ping', 'search3', 'stream', 'getSong'):
            raise ValueError('Endpoint not allowlisted')
        salt=secrets.token_hex(10)
        token=hashlib.md5((self.cfg.password+salt).encode('utf-8')).hexdigest()
        p={'u':self.cfg.username,'t':token,'s':salt,'v':'1.16.1','c':'aurum-pulse','f':'json'}
        p.update(params or {})
        # Never allow caller to replace security identifiers or change the API routing.
        for k in ('u','t','s','v','c','f'):
            if params and k in params:
                raise ValueError('Reserved parameter')
        return self.cfg.base+'/rest/'+endpoint+'.view?'+urllib.parse.urlencode(p)

    def request(self,endpoint: str,params: dict | None = None,range_header: str | None = None):
        url=self.url(endpoint,params)
        headers={'User-Agent':'AurumPulse/0.1 (local personal audio)','Accept':'application/json'}
        if range_header:
            if not re.fullmatch(r'bytes=\d+-\d*',range_header):
                raise ValueError('Invalid byte range')
            headers['Range']=range_header
        req=urllib.request.Request(url,headers=headers)
        try:
            return self.opener(req,timeout=20)
        except (urllib.error.HTTPError,urllib.error.URLError,TimeoutError) as e:
            raise UpstreamError(f'Navidrome request unsuccessful ({type(e).__name__})') from None

    def get_json(self,endpoint: str,params: dict | None = None):
        with self.request(endpoint,params) as response:
            raw=response.read(2_000_001)
        if len(raw)>2_000_000:
            raise UpstreamError('Upstream response too large')
        try:
            body=json.loads(raw)
            root=body['subsonic-response']
            if root.get('status')!='ok':
                raise UpstreamError('Navidrome returned unsuccessful API status')
            return root
        except (ValueError,KeyError,TypeError) as exc:
            raise UpstreamError('Navidrome returned invalid JSON contract') from exc

    def search(self,query:str):
        if not isinstance(query,str) or len(query)>100 or not query.strip():
            raise ValueError('Search term required (1-100 chars)')
        root=self.get_json('search3',{'query':query,'songCount':35,'albumCount':0,'artistCount':0})
        songs=root.get('searchResult3',{}).get('song',[]) or []
        if not isinstance(songs,list):
            raise UpstreamError('Invalid search songs shape')
        out=[]
        for s in songs[:35]:
            if not isinstance(s,dict):continue
            sid=s.get('id');
            if not isinstance(sid,str) or not SAFE_ID.fullmatch(sid):continue
            artist=str(s.get('artist') or 'Unknown')[:150]
            title=str(s.get('title') or 'Untitled')[:220]
            out.append({'id':sid,'artist':artist,'title':title,'source':'NAVIDROME_OWNER_LIBRARY',
                        'quality':'UNKNOWN','streamPath':'/api/navidrome/stream?id='+urllib.parse.quote(sid,safe='')})
        return out
