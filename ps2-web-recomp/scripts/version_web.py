#!/usr/bin/env python3
"""Content-hashed names for the engine files of a web build, so they can be cached for a year (immutable).

usage: scripts/version_web.py build/web-<game>

Copies ps2web.js, ps2web.wasm (build) and loader.js, loader.css (web/) to <name>.<hash12>.<ext> (+ .gz), rewrites
the references in ps2web.html (the script and stylesheet tags and <meta name=reo-engine content="<wasm> <bytes>">)
and writes engine.json ({"js", "wasm", "bytes", "loader_js", "loader_css"}: deploy.sh and the prefetch read it).
Old hashed copies are removed. Idempotent: running it again on an already rewritten page works.
ps2web.data and the disc image are not touched (they keep their names and their revalidated caching).
"""
import gzip, hashlib, json, os, re, shutil, sys

out = os.path.abspath(sys.argv[1])
web = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'web')
HASHED = re.compile(r'^(ps2web|loader)\.[0-9a-f]{12}\.(js|wasm|css)(\.gz)?$')


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()[:12]


for name in os.listdir(out):
    if HASHED.match(name):
        os.remove(os.path.join(out, name))

names = {}
for src in (os.path.join(out, 'ps2web.js'), os.path.join(out, 'ps2web.wasm'), os.path.join(web, 'loader.js'), os.path.join(web, 'loader.css')):
    base, ext = os.path.splitext(os.path.basename(src))
    name = '%s.%s%s' % (base, digest(src), ext)
    shutil.copyfile(src, os.path.join(out, name))
    packed = src + '.gz'
    if os.path.isfile(packed) and os.path.getmtime(packed) >= os.path.getmtime(src):
        shutil.copyfile(packed, os.path.join(out, name + '.gz'))
    else:
        with open(src, 'rb') as f, open(os.path.join(out, name + '.gz'), 'wb') as g:
            with gzip.GzipFile(fileobj=g, mode='wb', compresslevel=9, mtime=0, filename='') as z:
                z.write(f.read())
    names[base + ext] = name

page = os.path.join(out, 'ps2web.html')
with open(page, encoding='utf-8') as f:
    html = f.read()
wasm_bytes = os.path.getsize(os.path.join(out, 'ps2web.wasm'))
rules = [
    (r'(src=["\']?)ps2web(?:\.[0-9a-f]{12})?\.js(?=["\'\s>])', r'\g<1>' + names['ps2web.js']),
    (r'(src=["\']?)loader(?:\.[0-9a-f]{12})?\.js(?=["\'\s>])', r'\g<1>' + names['loader.js']),
    (r'(href=["\']?)loader(?:\.[0-9a-f]{12})?\.css(?=["\'\s>])', r'\g<1>' + names['loader.css']),
    (r'ps2web(?:\.[0-9a-f]{12})?\.wasm \d+', '%s %d' % (names['ps2web.wasm'], wasm_bytes)),
]
for pattern, repl in rules:
    html, n = re.subn(pattern, repl, html)
    if n != 1:
        sys.exit('version_web: %d matches for %s in %s' % (n, pattern, page))
with open(page, 'w', encoding='utf-8') as f:
    f.write(html)
with open(os.path.join(out, 'engine.json'), 'w') as f:
    json.dump({'js': names['ps2web.js'], 'wasm': names['ps2web.wasm'], 'bytes': wasm_bytes,
               'loader_js': names['loader.js'], 'loader_css': names['loader.css']}, f)
    f.write('\n')
print('versioned %s: %s' % (out, ', '.join(sorted(names.values()))))
