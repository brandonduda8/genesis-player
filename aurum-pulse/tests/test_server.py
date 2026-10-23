import io
import json
import os
import re
import sys
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server.app import Handler
from server.subsonic import Config, ConfigError, SAFE_ID, Subsonic, UpstreamError
from http.server import ThreadingHTTPServer

class FakeResponse:
    def __init__(self,data,status=200):
        self._io=io.BytesIO(data)
        self.headers={'Content-Type':'application/json'}
        self.status=status
    def read(self,n=-1):return self._io.read(n)
    def __enter__(self):return self
    def __exit__(self,*args):pass

class ConfigTest(unittest.TestCase):
    def test_not_configured(self):self.assertIsNone(Config.from_environment({}))
    def test_partial_configuration_refused(self):
        with self.assertRaises(ConfigError):Config.from_environment({'AURUM_NAVIDROME_URL':'https://example.org'})
    def test_plain_remote_http_denied(self):
        with self.assertRaises(ConfigError):Config.from_environment({'AURUM_NAVIDROME_URL':'http://example.org','AURUM_NAVIDROME_USER':'u','AURUM_NAVIDROME_PASSWORD':'p'})
    def test_loopback_http_allowed(self):
        c=Config.from_environment({'AURUM_NAVIDROME_URL':'http://127.0.0.1:4533','AURUM_NAVIDROME_USER':'u','AURUM_NAVIDROME_PASSWORD':'p'})
        self.assertEqual(c.base,'http://127.0.0.1:4533')
    def test_https_allowed(self):
        c=Config.from_environment({'AURUM_NAVIDROME_URL':'https://music.example.org','AURUM_NAVIDROME_USER':'u','AURUM_NAVIDROME_PASSWORD':'p'})
        self.assertEqual(c.base,'https://music.example.org')
    def test_embedded_credentials_denied(self):
        with self.assertRaises(ConfigError):Config.from_environment({'AURUM_NAVIDROME_URL':'https://bad:password@example.org','AURUM_NAVIDROME_USER':'u','AURUM_NAVIDROME_PASSWORD':'p'})
    def test_query_credentials_denied(self):
        with self.assertRaises(ConfigError):Config.from_environment({'AURUM_NAVIDROME_URL':'https://example.org?key=secret','AURUM_NAVIDROME_USER':'u','AURUM_NAVIDROME_PASSWORD':'p'})
    def test_invalid_port_denied(self):
        with self.assertRaises(ConfigError):Config.from_environment({'AURUM_NAVIDROME_URL':'https://example.org:nope','AURUM_NAVIDROME_USER':'u','AURUM_NAVIDROME_PASSWORD':'p'})
    def test_no_redirects(self):
        from server.subsonic import NoRedirect
        self.assertIsNone(NoRedirect().redirect_request(None,None,302,'Redirect',{},'https://other.org'))

class SubsonicTests(unittest.TestCase):
    def setUp(self):self.cfg=Config('https://music.example.org','user','secret')
    def test_token_generated_but_password_not_in_url(self):
        u=Subsonic(self.cfg).url('ping');self.assertIn('/rest/ping.view?',u);self.assertNotIn('secret',u);self.assertIn('t=',u);self.assertIn('s=',u)
    def test_unknown_endpoint_denied(self):
        with self.assertRaises(ValueError):Subsonic(self.cfg).url('deleteUser')
    def test_auth_parameter_override_denied(self):
        with self.assertRaises(ValueError):Subsonic(self.cfg).url('stream',{'u':'hacker'})
    def test_songs_shape_sanitized(self):
        data=json.dumps({'subsonic-response':{'status':'ok','searchResult3':{'song':[{'id':'good:3','artist':'A','title':'Title'},{'id':'../secrets','title':'Bad'}]}}}).encode()
        sub=Subsonic(self.cfg,opener=lambda req,timeout:FakeResponse(data))
        songs=sub.search('A')
        self.assertEqual(len(songs),1);self.assertEqual(songs[0]['quality'],'UNKNOWN');self.assertEqual(songs[0]['streamPath'],'/api/navidrome/stream?id=good%3A3')
    def test_large_response_denied(self):
        sub=Subsonic(self.cfg,opener=lambda req,timeout:FakeResponse(b'x'*2_000_010))
        with self.assertRaises(UpstreamError):sub.get_json('ping')
    def test_bad_json_denied(self):
        sub=Subsonic(self.cfg,opener=lambda req,timeout:FakeResponse(b'{bad}'))
        with self.assertRaises(UpstreamError):sub.get_json('ping')
    def test_not_ok_denied(self):
        d=b'{"subsonic-response":{"status":"failed"}}'
        sub=Subsonic(self.cfg,opener=lambda req,timeout:FakeResponse(d))
        with self.assertRaises(UpstreamError):sub.get_json('ping')
    def test_blank_search_denied(self):
        with self.assertRaises(ValueError):Subsonic(self.cfg).search(' ')
    def test_long_search_denied(self):
        with self.assertRaises(ValueError):Subsonic(self.cfg).search('a'*200)
    def test_disallowed_range_denied(self):
        with self.assertRaises(ValueError):Subsonic(self.cfg,opener=lambda req,timeout:None).request('stream',{'id':'one'},'bytes=1-2,4-5')
    def test_safe_track_ids(self):
        for v in ('song-2','abc:def','X_2'):
            self.assertIsNotNone(SAFE_ID.fullmatch(v))
        for v in ('../x','x/y','', 'x'*300):
            self.assertIsNone(SAFE_ID.fullmatch(v))

class LocalHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        cls.port=cls.server.server_port
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True)
        cls.thread.start()
    @classmethod
    def tearDownClass(cls):cls.server.shutdown();cls.server.server_close();cls.thread.join(timeout=5)
    def request(self,path):
        url=f'http://127.0.0.1:{self.port}'+path
        try:
            with urllib.request.urlopen(url,timeout=3) as r:return r.status,dict(r.headers),r.read()
        except urllib.error.HTTPError as e:return e.code,dict(e.headers),e.read()
    def test_health_without_key_blocked_not_ready(self):
        with mock.patch.dict(os.environ,{},clear=True):
            status,headers,data=self.request('/api/health')
            self.assertEqual(status,200);j=json.loads(data);self.assertEqual(j['navidrome'],'BLOCKED_CREDENTIALS');self.assertEqual(j['catalog'],'ORIGINAL_GOLD_BLOCKED_RECOVERY');self.assertIn('nosniff',headers.get('X-Content-Type-Options',''))
    def test_index_served(self):
        s,h,data=self.request('/');self.assertEqual(s,200);self.assertIn(b'AURUM PULSE',data);self.assertIn('script-src',h.get('Content-Security-Policy',''))
    def test_missing_api_404(self):self.assertEqual(self.request('/api/wipe')[0],404)
    def test_nav_search_does_not_contact_when_no_key(self):
        with mock.patch.dict(os.environ,{},clear=True):self.assertEqual(self.request('/api/navidrome/search?q=test')[0],503)
    def test_stream_invalid_id_denied(self):self.assertEqual(self.request('/api/navidrome/stream?id=..%2Fetc%2Fpasswd')[0],400)
    def test_stream_denied_without_key(self):
        with mock.patch.dict(os.environ,{},clear=True):self.assertEqual(self.request('/api/navidrome/stream?id=song42')[0],503)

if __name__=='__main__':unittest.main()
