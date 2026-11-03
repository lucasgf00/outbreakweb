#!/usr/bin/env python3
"""Local check of the host-gone handling in KkService (needs a LOCAL server, e.g. tests/KkLocal.java on loopback):

  python3 kkhostgone.py --port 19090 --api http://127.0.0.1:18088 --token <REO_ADMIN_TOKEN of that local server>

A creates a room, C creates one with the same title (it must get " 2"), B joins A's room, A starts and then
leaves (0x02). B must get the gateway datagram "REOGW-GONE"+A's tag (C must not), the room leaves the lists,
and B's 0x0A after the "scenario" must bring the host's disband (0x10 class 0x0D with A's tag).
Finally the admin action "release" frees C's name so another credential can register it."""
import argparse, json, socket, struct, sys, time, urllib.request, urllib.error
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19090); ap.add_argument("--api", default="http://127.0.0.1:18088")
ap.add_argument("--token", required=True); ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C = (kk4.Client(n + sfx, addr, a.api) for n in ("hga", "hgb", "hgc"))
kk4.check("logins", A.login() and B.login() and C.login())
for c in (A, B, C):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()

def create(c, title):
    c.send(0x04, title.ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
    c.pump(0.3)
    r = [m for m in c.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
    return struct.unpack(">I", r[0][5][4:8])[0] if r else None

oid = create(A, b"hostgone")
oid2 = create(C, b"HostGone")
kk4.check("two rooms created", oid and oid2 and oid != oid2)
B.pump(0.3); B.take(); B.send(0x0B, struct.pack(">I", a.area)); B.pump(0.5)
blob = b"".join(m[5] for m in B.take())
kk4.check("same title gets a suffix", b"HostGone 2" in blob and b"hostgone\0" in blob)

B.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); B.pump(0.3)
kk4.check("B joins", any((m[2] & 0x7F) == 0x28 for m in B.take()))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3)
for c in (B, C): c.pump(0.3); c.take()

def raw(c, wait):
    out, end = [], time.time() + wait
    while time.time() < end:
        c.s.settimeout(max(0.01, end - time.time()))
        try: out.append(c.s.recvfrom(65536)[0])
        except socket.timeout: break
    return out

A.send(0x02, b"", reliable=False)
gb, gc = raw(B, 0.8), raw(C, 0.8)
want = b"REOGW-GONE" + struct.pack(">I", A.tag)
kk4.check("B (member) told the host is gone", want in gb, str([d[:14] for d in gb]))
kk4.check("C (not a member) not told", not any(d.startswith(b"REOGW-") for d in gc))

C.send(0x0B, struct.pack(">I", a.area)); C.pump(0.5)
blob = b"".join(m[5] for m in C.take())
kk4.check("orphaned room out of the list", b"hostgone\0" not in blob and b"HostGone 2" in blob)
C.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); C.pump(0.3)
kk4.check("join to orphaned room refused", any((m[2] & 0x7F) == 0x27 for m in C.take()))

B.send(0x0A, struct.pack(">I", oid), flags=0x2800); B.pump(0.5)
got = B.take()
mem = [m for m in got if (m[2] & 0x7F) == 0x0A]
dis = [m for m in got if (m[2] & 0x7F) == 0x10 and m[5][8:9] == b"\x0d"]
kk4.check("B back in the room: member list then host's disband", mem and dis and dis[0][3] == A.tag,
          str([(hex(m[2]), hex(m[3]), m[5][:12].hex()) for m in got]))

def post(path, body, tok=None):
    h = {"Content-Type": "application/json"}
    if tok: h["X-Admin-Token"] = tok
    try:
        r = urllib.request.urlopen(urllib.request.Request(a.api + path, json.dumps(body).encode(), h))
        return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

st, _ = post("/api/kk/register", {"name": C.name, "credential": "0123456789ab"})
kk4.check("other credential refused before release", st != 200, str(st))
st, _ = post("/api/admin/mod/action", {"action": "release", "name": C.name})
kk4.check("release without token refused", st in (401, 403), str(st))
time.sleep(1.1)
st, body = post("/api/admin/mod/action", {"action": "release", "name": C.name}, a.token)
kk4.check("release", st == 200 and '"had":true' in body, f"{st} {body}")
st, _ = post("/api/kk/register", {"name": C.name, "credential": "0123456789ab"})
kk4.check("other credential accepted after release", st == 200, str(st))
for c in (B, C): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)