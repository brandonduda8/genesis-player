"""Source contract and metadata safety gates for Aurum Roots; all upstreams mocked."""
import json
import sys
import unittest
import urllib.parse
import urllib.request
from pathlib import Path
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server.roots import (RootsError, RootsService, validate_artist, public_json,
    extract_mbid_artist, influence_ids, label_influences, parse_relations, parse_listenbrainz_radio)
from server.app import Handler
from http.server import ThreadingHTTPServer
import threading

MAC='83c5a6bc-7d02-4959-9b52-b7f48df39d2f'
PAL='c73ea1b5-1c44-45bf-8689-4687a055c91c'
WIKIDATA='Q411'

class RootsPolicy(unittest.TestCase):
    def test_unsafe_artists_denied(self):
        for name in ('', 'A', 'x'*81, '/etc/passwd', '\\evil', 'doobie\nheader', '\x00'):
            with self.subTest(name=repr(name)), self.assertRaises(ValueError):validate_artist(name)
    def test_normalization(self): self.assertEqual(validate_artist('  Mac   Miller  '),'Mac Miller')
    def test_nonexact_artist_does_not_impersonate(self):
        obj={'artists':[{'name':'Mac Millers Tribute','id':MAC,'score':100}]}
        self.assertIsNone(extract_mbid_artist(obj,'Mac Miller'))
    def test_exact_name_and_valid_mbid(self):
        x=extract_mbid_artist({'artists':[{'name':'Mac Miller','id':MAC,'score':90}]},'MAC MILLER')
        self.assertEqual(x['mbid'],MAC)
    def test_invalid_mbid_refused(self):
        self.assertIsNone(extract_mbid_artist({'artists':[{'name':'Mac Miller','id':'../../secret'}]},'Mac Miller'))
    def test_collaboration_never_fabricated_as_influence(self):
        data={'relations':[{'target-type':'artist','type':'collaboration','artist':{'name':'Friend','id':PAL}},
          {'target-type':'artist','type':'married','artist':{'name':'Wrong','id':PAL}},
          {'target-type':'url','type':'wikidata','url':{'resource':'https://www.wikidata.org/wiki/Q411'}}]}
        related,qid=parse_relations(data)
        self.assertEqual(qid,'Q411');self.assertEqual(len(related),1)
        self.assertEqual(related[0]['kind'],'documented-relationship')
        self.assertIn('not proof of musical influence',related[0]['why'])
    def test_claims_nonreferenced_still_labeled_wikidata(self):
        claims={'entities':{'Q411':{'claims':{'P737':[{'rank':'normal','mainsnak':{'datavalue':{'value':{'id':'Q5'}}}},
          {'rank':'deprecated','mainsnak':{'datavalue':{'value':{'id':'Q6'}}}},
          {'rank':'normal','mainsnak':{'datavalue':{'value':{'id':'Q5'}}}}]}}}}
        ids=influence_ids(claims,'Q411');self.assertEqual(ids,['Q5'])
        links=label_influences({'entities':{'Q5':{'labels':{'en':{'value':'Genius Artist'}}}}},ids,'Q411')
        self.assertEqual(links[0]['artist'],'Genius Artist')
        self.assertEqual(links[0]['kind'],'documented-influence')
        self.assertFalse(links[0]['playable'])
    def test_radio_association_is_not_influence(self):
        r=parse_listenbrainz_radio({'payload':[{'similar_artist_mbid':PAL,'similar_artist_name':'New Sound'},
          {'similar_artist_mbid':MAC,'similar_artist_name':'Mac Miller'}]},MAC,'Mac Miller')
        self.assertEqual(len(r),1);self.assertEqual(r[0]['kind'],'algorithmic-similar')
        self.assertIn('NOT a documented influence',r[0]['why'])
    def test_outbound_request_hosts_are_hard_allowlisted(self):
        for url in ('http://musicbrainz.org/x','https://evil.example/a',
          'https://musicbrainz.org.evil.example/','https://a:pass@musicbrainz.org/a',
          'https://musicbrainz.org:3434/x'):
            with self.subTest(url=url),self.assertRaises(RootsError):public_json(url)

class EndToEndMock(unittest.TestCase):
    def setUp(self):
        self.requests=[]
        self.responses={
          'musicbrainz.org/ws/2/artist/?': {'artists':[{'id':MAC,'name':'Mac Miller','score':100}]},
          'musicbrainz.org/ws/2/artist/'+MAC: {
              'id':MAC,'name':'Mac Miller','tags':[{'name':'hip-hop'}],
              'relations':[
                  {'target-type':'url','type':'wikidata','url':{'resource':'https://www.wikidata.org/wiki/Q411'}},
                  {'target-type':'artist','type':'collaboration','artist':{'id':PAL,'name':'A Friend'}}
              ]
          },
          'api.listenbrainz.org/1/lb-radio/artist/'+MAC: {
              'payload':[{'similar_artist_mbid':PAL,'similar_artist_name':'A Friend'}]
          }
        }
        self.responses['www.wikidata.org/w/api.php?claims']={
            'entities':{'Q411':{'claims':{'P737':[
                {'rank':'normal','mainsnak':{'datavalue':{'value':{'id':'Q5'}}}}
            ]}}}
        }
        self.responses['www.wikidata.org/w/api.php?labels']={
            'entities':{'Q5':{'labels':{'en':{'value':'An Inspiration'}}}}
        }
        def fake(url):
            self.requests.append(url)
            if 'musicbrainz.org/ws/2/artist/?' in url:return self.responses['musicbrainz.org/ws/2/artist/?']
            if 'musicbrainz.org/ws/2/artist/'+MAC in url:return self.responses['musicbrainz.org/ws/2/artist/'+MAC]
            if 'www.wikidata.org/' in url:
                props=urllib.parse.parse_qs(urllib.parse.urlsplit(url).query).get('props',[''])[0]
                return self.responses['www.wikidata.org/w/api.php?'+props]
            if 'api.listenbrainz.org/' in url:return self.responses['api.listenbrainz.org/1/lb-radio/artist/'+MAC]
            raise AssertionError('Unexpected upstream URL: '+url)
        self.service=RootsService(fake)
    def test_source_bounded_tree(self):
        with mock.patch('server.roots.time.sleep',return_value=None):
            result=self.service.explore('Mac Miller')
        self.assertEqual(result['status'],'SOURCE_LINKED_RESULTS')
        self.assertEqual(result['counts'],{'influences':1,'recorded-relationships':1,'similar':1})
        self.assertEqual(result['connections'][0]['artist'],'An Inspiration')
        self.assertTrue(all(x['playable'] is False for x in result['connections']))
        self.assertEqual(result['rights'],'METADATA_ONLY')
        self.assertEqual(len(self.requests),5)
        with mock.patch('server.roots.time.sleep',return_value=None):
            result=self.service.explore('Mac Miller')
        self.assertEqual(len(self.requests),5,'Cached lookup must avoid hammering metadata sources')
    def test_no_wikidata_graph_does_not_fake_influence(self):
        self.responses['musicbrainz.org/ws/2/artist/'+MAC]['relations']=[]
        with mock.patch('server.roots.time.sleep',return_value=None):
            result=self.service.explore('Mac Miller')
        self.assertEqual(result['counts']['influences'],0)
        self.assertTrue(any('No linked Wikidata' in s for s in result['partial']))
    def test_musicbrainz_unavailable_propagates_safely(self):
        x=RootsService(lambda url: (_ for _ in ()).throw(RootsError('No')))
        with self.assertRaises(RootsError):x.explore('Mac Miller')

class HTTPContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.s=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        cls.thread=threading.Thread(target=cls.s.serve_forever,daemon=True);cls.thread.start()
    @classmethod
    def tearDownClass(cls):cls.s.shutdown();cls.s.server_close();cls.thread.join(timeout=3)
    def request(self,path):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{self.s.server_port}'+path,timeout=2) as response:
                return response.status,json.loads(response.read())
        except urllib.error.HTTPError as e:
            return e.code,json.loads(e.read())
    def test_unsafe_names_are_rejected_before_fetch(self):
        with mock.patch('server.app.ROOTS') as mocked:
            mocked.explore.side_effect=ValueError('unsafe')
            status,payload=self.request('/api/roots/artist?q=A')
            self.assertEqual(status,400)
            mocked.explore.assert_called_once_with('A')
    def test_no_metadata_fetch_on_homepage_or_health(self):
        with mock.patch('server.app.ROOTS') as roots:
            status,_=self.request('/api/health');self.assertEqual(status,200)
            roots.explore.assert_not_called()
    def test_upstream_failure_does_not_leak_details(self):
        with mock.patch('server.app.ROOTS') as roots:
            roots.explore.side_effect=RootsError('private diagnostic')
            status,payload=self.request('/api/roots/artist?q=Mac%20Miller')
            self.assertEqual(status,503)
            self.assertNotIn('private diagnostic',str(payload))

if __name__=='__main__':unittest.main()
