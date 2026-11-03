#!/usr/bin/env python3
"""Serves web builds and disc images with HTTP range requests and the COOP/COEP headers.

usage: serve.py <build dir> <disc image> [port] [--record <dir for unrecorded VU1 programs>] [--api <url>]
       serve.py <name>=<build dir>,<disc image> [<name>=<build dir>,<disc image> ...] [port]

The second form serves several games from one server: each one under /<name>/
(for example /file1/ and /file2/), /games.json lists them and / is the game
picker page (web/index.html).

Each disc image is also served as production serves it: 64 MiB pieces disc/000.bin, disc/001.bin ...
(immutable, like the Caddy route), so the page reads by pieces; ?disc=disc.iso still reads the whole image.

--api http://127.0.0.1:8088 forwards /api/* (GET and POST) to the lobby server's HTTP API,
so the page can register its player name on its own origin (no CORS under COEP).
"""

import http.server
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request

RECORD = None
if '--record' in sys.argv:
    at = sys.argv.index('--record')
    RECORD = os.path.abspath(sys.argv[at + 1])
    del sys.argv[at:at + 2]
API = None
if '--api' in sys.argv:
    at = sys.argv.index('--api')
    API = sys.argv[at + 1].rstrip('/')
    del sys.argv[at:at + 2]
TITLES = {'SLUS-20765': 'File #1', 'SLUS-20984': 'File #2'}
INDEX = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'web', 'index.html')
ADMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'web', 'admin.html')   # owner's metrics panel
SITES = {}  # name -> (build dir, disc image); '' is the single-game form at /
args = sys.argv[1:]
PORT = int(args.pop()) if args and args[-1].isdigit() else 8620
if args and '=' in args[0]:
    for arg in args:
        name, _, rest = arg.partition('=')
        build, _, disc = rest.partition(',')
        if not re.fullmatch(r'[a-z0-9_-]+', name) or not disc:
            sys.exit('bad game argument %r, expected name=build_dir,disc_image' % arg)
        SITES[name] = (os.path.abspath(build), os.path.abspath(disc))
else:
    SITES[''] = (os.path.abspath(args[0]), os.path.abspath(args[1]))


HASHED = re.compile(r'^(ps2web|loader)\.[0-9a-f]{12}\.(js|wasm|css)$')


def games_json():
    games = []
    for name, (build, disc) in SITES.items():
        serial = ''
        try:
            m = re.search(r'S([A-Z]{3})_(\d{3})\.(\d{2})', open(os.path.join(build, 'boot.txt')).read())
            serial = 'S%s-%s%s' % m.groups() if m else ''
        except OSError:
            pass
        games.append({'path': name, 'serial': serial, 'title': TITLES.get(serial, serial or name),
                      'ready': os.path.isfile(os.path.join(build, 'ps2web.wasm')) and os.path.isfile(disc)})
    return json.dumps(games).encode()


PIECE = 64 << 20   # production piece size (shell.html DISC_PIECE)


class Piece:
    """One 64 MiB slice of a disc image, served as disc/NNN.bin."""
    def __init__(self, disc, index):
        self.disc, self.base = disc, index * PIECE
        self.size = max(0, min(PIECE, os.path.getsize(disc) - self.base))


TYPES = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.wasm': 'application/wasm',
         '.data': 'application/octet-stream', '.json': 'application/json', '.css': 'text/css', '.txt': 'text/plain; charset=utf-8'}


class Handler(http.server.BaseHTTPRequestHandler):
    def target(self):
        """Returns a file path, bytes for a generated reply, or None."""
        path = self.path.split('?', 1)[0]
        site = ''
        if path in ('/admin', '/admin/'):
            return ADMIN if os.path.isfile(ADMIN) else None
        if '' not in SITES:
            if path in ('/', '/index.html'):
                return INDEX if os.path.isfile(INDEX) else None
            if path == '/games.json':
                return games_json()
            site, _, rest = path.lstrip('/').partition('/')
            if site not in SITES:
                return None
            path = '/' + rest
        build, disc = SITES[site]
        if path in ('/', '/index.html'):
            path = '/ps2web.html'
        if path == '/disc.iso':
            return disc
        piece = re.fullmatch(r'/disc/(\d{3})\.bin', path)
        if piece and os.path.isfile(disc):
            piece = Piece(disc, int(piece.group(1)))
            return piece if piece.size else None
        name = os.path.normpath(path.lstrip('/'))
        if name.startswith('..') or os.sep in name:
            return None
        full = os.path.join(build, name)
        return full if os.path.isfile(full) else None

    def redirect_dir(self):
        """/file1 -> /file1/ so that the page's relative URLs resolve inside the game."""
        path, _, query = self.path.partition('?')
        if '' in SITES or path.strip('/') not in SITES or path.endswith('/'):
            return False
        self.send_response(301)
        self.send_header('Location', path + '/' + ('?' + query if query else ''))
        self.send_header('Content-Length', '0')
        self.end_headers()
        return True

    def send_bytes(self, data, content_type, head=False):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
        self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
        self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
        self.end_headers()
        if not head:
            self.wfile.write(data)

    def headers_for(self, path, length, encoding=None):
        if isinstance(path, Piece):
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(length))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Cache-Control', 'public, max-age=31536000, immutable')
            self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
            self.end_headers()
            return
        self.send_header('Content-Type', TYPES.get(os.path.splitext(path)[1], 'application/octet-stream'))
        if encoding:
            self.send_header('Content-Encoding', encoding)
            self.send_header('Vary', 'Accept-Encoding')
        self.send_header('Content-Length', str(length))
        self.send_header('Accept-Ranges', 'bytes')
        # content-hashed engine files (scripts/version_web.py) never change; everything else is revalidated
        self.send_header('Cache-Control', 'public, max-age=31536000, immutable' if HASHED.match(os.path.basename(path)) else 'no-cache')
        self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
        self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
        self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
        self.end_headers()

    def do_HEAD(self):
        if self.redirect_dir():
            return
        path = self.target()
        if isinstance(path, bytes):
            self.send_bytes(path, TYPES['.json'], head=True)
            return
        if not path:
            self.send_error(404)
            return
        self.send_response(200)
        self.headers_for(path, path.size if isinstance(path, Piece) else os.path.getsize(path))

    def proxy_api(self):
        """Forwards /api/* to the lobby server (--api). Returns False if the path is not an API path."""
        if not self.path.startswith('/api/'):
            return False
        if not API:
            self.send_error(404)
            return True
        length = min(int(self.headers.get('Content-Length', '0') or 0), 4096)
        data = self.rfile.read(length) if self.command == 'POST' else None
        headers = {'Content-Type': self.headers.get('Content-Type', 'application/json'),
                   'X-Forwarded-For': self.client_address[0]}
        for h in ('Authorization', 'X-Admin-Token', 'CF-IPCountry'):   # admin token (/api/admin/*) and Cloudflare's country for metrics
            if self.headers.get(h):
                headers[h] = self.headers.get(h)
        req = urllib.request.Request(API + self.path, data=data, method=self.command, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=5) as res:
                status, body, ctype = res.status, res.read(), res.headers.get('Content-Type', 'application/json')
        except urllib.error.HTTPError as e:
            status, body, ctype = e.code, e.read(), e.headers.get('Content-Type', 'application/json')
        except OSError:
            status, body, ctype = 502, b'{"error":"lobby server unavailable"}', 'application/json'
        self.send_response(status)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
        self.end_headers()
        self.wfile.write(body)
        return True

    def do_POST(self):
        if not self.proxy_api():
            self.send_error(405)

    def do_GET(self):
        if self.proxy_api():
            return
        if self.redirect_dir():
            return
        path = self.target()
        if isinstance(path, bytes):
            self.send_bytes(path, TYPES['.json'])
            return
        if not path:
            self.send_error(404)
            return
        if isinstance(path, Piece):
            self.send_range(path, path.disc, path.base, path.size)
            return
        packed = path + '.gz'
        if ('Range' not in self.headers and 'gzip' in self.headers.get('Accept-Encoding', '') and os.path.isfile(packed)
                and os.path.getmtime(packed) >= os.path.getmtime(path)):
            self.send_response(200)
            self.headers_for(path, os.path.getsize(packed), 'gzip')
            with open(packed, 'rb') as f:
                shutil.copyfileobj(f, self.wfile, 1 << 20)
            return
        self.send_range(path, path, 0, os.path.getsize(path))

    def send_range(self, path, file, base, size):
        match = re.match(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        start, end = 0, size - 1
        if match:
            start = int(match.group(1))
            end = min(int(match.group(2)) if match.group(2) else size - 1, size - 1)
            self.send_response(206)
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
        else:
            self.send_response(200)
        self.headers_for(path, end - start + 1)
        with open(file, 'rb') as f:
            f.seek(base + start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(left, 1 << 20))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def do_PUT(self):
        match = re.match(r'^/vu1/([0-9A-F]{16}_[0-9A-F]{4}\.bin)$', self.path.split('?', 1)[0])
        length = int(self.headers.get('Content-Length', '0'))
        if not RECORD or not match or length != 16384:
            self.send_error(501 if not RECORD else 400)
            return
        data = self.rfile.read(length)
        target = os.path.join(RECORD, match.group(1))
        if not os.path.exists(target):
            os.makedirs(RECORD, exist_ok=True)
            with open(target, 'wb') as f:
                f.write(data)
            print('recorded', match.group(1), flush=True)
        self.send_response(201)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, fmt, *args):
        pass


for name, (build, disc) in SITES.items():
    print('serving %s and %s on http://127.0.0.1:%d/%s' % (build, disc, PORT, name + '/' if name else ''))
http.server.ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
