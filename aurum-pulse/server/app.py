"""AURUM PULSE preview — localhost only, no accounts or public listener.

Command: python3 server/app.py .
Optional, separately approved owner Navidrome environment variables may enable
read-only search + same-origin stream proxy. Never expose this server publicly.
"""
from __future__ import annotations
import json
import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
try:
    from .subsonic import Config, ConfigError, Subsonic, SAFE_ID, UpstreamError
except ImportError:
    from subsonic import Config, ConfigError, Subsonic, SAFE_ID, UpstreamError

WEB_ROOT=Path(__file__).resolve().parent.parent/'web'

class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,directory=str(WEB_ROOT),**kwargs)

    def end_headers(self):
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('X-Frame-Options','DENY')
        self.send_header('Cross-Origin-Resource-Policy','same-origin')
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        super().end_headers()

    def log_message(self,format,*args):
        # Deliberately do not log URLs/query params (media tokens and listening habits).
        return

    def json_response(self,payload,status=200):
        data=json.dumps(payload,ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parts=urlsplit(self.path)
        path=parts.path
        if path=='/api/health':
            try:
                cfg=Config.from_environment()
                state='CONFIGURED_NOT_TESTED' if cfg else 'BLOCKED_CREDENTIALS'
            except ConfigError:
                state='BLOCKED_CREDENTIALS'
            self.json_response({'service':'aurum-pulse','version':'0.1.0','mode':'LOOPBACK_PREVIEW',
                 'navidrome':state,'catalog':'ORIGINAL_GOLD_BLOCKED_RECOVERY',
                 'message':'Local audio playback ready; Navidrome live playback is not automatically implied.'})
            return
        if path=='/api/navidrome/search':
            try:
                cfg=Config.from_environment()
                if not cfg:
                    self.json_response({'error':'BLOCKED_CREDENTIALS'},503);return
                args=parse_qs(parts.query,keep_blank_values=True)
                q=args.get('q',[''])[0]
                result=Subsonic(cfg).search(q)
                self.json_response({'source':'NAVIDROME_OWNER_LIBRARY','quality':'UNKNOWN','songs':result})
            except (ValueError,ConfigError) as e:
                self.json_response({'error':'INVALID_SEARCH'},400)
            except UpstreamError:
                self.json_response({'error':'UPSTREAM_UNAVAILABLE'},502)
            return
        if path=='/api/navidrome/stream':
            args=parse_qs(parts.query,keep_blank_values=True)
            songid=args.get('id',[''])[0]
            if not SAFE_ID.fullmatch(songid):
                self.json_response({'error':'INVALID_TRACK_ID'},400);return
            try:
                cfg=Config.from_environment()
                if not cfg:
                    self.json_response({'error':'BLOCKED_CREDENTIALS'},503);return
                range_header=self.headers.get('Range')
                if range_header and not re.fullmatch(r'bytes=\d+-\d*',range_header):
                    self.json_response({'error':'INVALID_RANGE'},400);return
                with Subsonic(cfg).request('stream',{'id':songid},range_header) as resp:
                    status=getattr(resp,'status',200)
                    if status not in (200,206):
                        self.json_response({'error':'UPSTREAM_UNAVAILABLE'},502);return
                    self.send_response(status)
                    # Do not forward upstream auth/cookie/server/location headers.
                    for header in ('Content-Type','Content-Length','Content-Range','Accept-Ranges'):
                        value=resp.headers.get(header)
                        if value and '\r' not in value and '\n' not in value:
                            self.send_header(header,value)
                    self.end_headers()
                    while True:
                        buf=resp.read(65536)
                        if not buf:break
                        self.wfile.write(buf)
            except (BrokenPipeError,ConnectionResetError):
                pass
            except (ValueError,ConfigError):
                self.json_response({'error':'INVALID_STREAM_REQUEST'},400)
            except UpstreamError:
                self.json_response({'error':'UPSTREAM_UNAVAILABLE'},502)
            return
        if path.startswith('/api/'):
            self.json_response({'error':'NOT_FOUND'},404);return
        super().do_GET()


def main():
    port=int(os.getenv('AURUM_PORT','8787'))
    if port<=0 or port>65535:raise ValueError('Invalid AURUM_PORT')
    httpd=ThreadingHTTPServer(('127.0.0.1',port),Handler)
    print(f'AURUM PULSE local preview at http://127.0.0.1:{port} — never expose publicly')
    try:httpd.serve_forever()
    except KeyboardInterrupt:pass
    finally:httpd.server_close()

if __name__=='__main__':main()
