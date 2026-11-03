#!/usr/bin/env python3
"""Local check: the host leaves the scenario while the others keep playing (protocol.md 6.14/6.15). Needs a LOCAL
server (tests/KkLocal.java on loopback, metrics on for the last check):

  python3 kkhostout.py --port 19190 --api http://127.0.0.1:18188

A creates a room, B and C join, A starts; the three send game data (0x0F). Then:
 1. A is back in the waiting room (0x0A on the room): B and C get "REOGW-GONE"+A's tag once (not again on a second
    0x0A, nor when A makes a new room afterwards).
 2. B's game data reaches C but not A (A is in the waiting room).
 3. C is dropped (0x02) while still playing in the room back to waiting: B gets "REOGW-GONE"+C's tag.
 4. D, in the area, sends game data: nobody gets it (no relay outside a game).
 5. POST /api/metrics/event with a "bg" event is accepted."""
import argparse, json, socket, struct, sys, time, urllib.request
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19190); ap.add_argument("--api", default="http://127.0.0.1:18188")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C, D = (kk4.Client(n + sfx, addr, a.api) for n in ("hoa", "hob", "hoc", "hod"))
kk4.check("logins", A.login() and B.login() and C.login() and D.login())

def rx(c, wait):
    """Like Client.pump, but also returns the raw datagrams (the gateway notices are not kk messages)."""
    raw, end = [], time.time() + wait
    while True:
        c.s.settimeout(max(0.005, end - time.time()))
        try: d = c.s.recvfrom(65536)[0]
        except socket.timeout: return raw
        raw.append(d)
        if d.startswith(b"REOGW-"): continue
        body = d[:-4]; o = 0
        while o + 16 <= len(body):
            fl, ty, tag, seq, ack = struct.unpack(">HHIII", body[o:o + 16]); ln = fl & 0x3FF
            c.inbox.append((time.time(), fl & 0xFC00, ty, tag, seq, body[o + 16:o + ln]))
            if fl & 0x8000:
                c.s.sendto(struct.pack(">HHIII", 0x6000 | 16, 0, c.tag, 0, seq) + struct.pack(">I", kk4.TRAILER), c.addr)
            o += max(ln, 16)

def gone(raw, who):
    return sum(1 for d in raw if d == b"REOGW-GONE" + struct.pack(">I", who.tag))

def game_from(c, who):
    return [m for m in c.take(0x0F) if m[3] == who.tag and m[5].startswith(b"\x00\x04")]

for c in (A, B, C, D):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); rx(c, 0.3); c.take()
A.send(0x04, b"hostout".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
rx(A, 0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room created", oid)
for c in (B, C):
    c.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); rx(c, 0.3)
    kk4.check(c.name + " joins", any((m[2] & 0x7F) == 0x28 for m in c.take()))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); rx(A, 0.3)
for c in (A, B, C): c.send(0x0F, b"\x00\x04\x00\x10ready"); rx(c, 0.2)
for c in (A, B, C): rx(c, 0.3); c.take()

# 1. host back in the waiting room
A.send(0x0A, struct.pack(">I", oid), flags=0x2800); rx(A, 0.3); A.take()
rb, rc = rx(B, 0.6), rx(C, 0.3)
kk4.check("B told the host left the scenario", gone(rb, A) == 1, str([d[:14] for d in rb]))
kk4.check("C told the host left the scenario", gone(rc, A) == 1, str([d[:14] for d in rc]))
A.send(0x0A, struct.pack(">I", oid), flags=0x2800); rx(A, 0.3); A.take()
A.send(0x07, b""); rx(A, 0.2); A.take()
A.send(0x04, b"hostout2".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006)); rx(A, 0.3); A.take()
rb, rc = rx(B, 0.6), rx(C, 0.3)
kk4.check("no second notice (0x0A again, 0x07, new room)", gone(rb, A) == 0 and gone(rc, A) == 0)
lobby = [m for m in B.take() + C.take() if (m[2] & 0x7F) in (0x04, 0x05, 0x07, 0x10)]
kk4.check("B and C (in game) get no lobby message", not lobby, str([(hex(m[2]), m[5][:12].hex()) for m in lobby]))

# 2. game data between the ones still playing only
B.send(0x0F, b"\x00\x04\x00\x10fromB"); rx(B, 0.2)
rc, ra = rx(C, 0.4), rx(A, 0.3)
kk4.check("B's game data reaches C", len(game_from(C, B)) == 1)
kk4.check("B's game data does not reach A (waiting room)", not game_from(A, B))

# 3. a member still playing is dropped: the other one is told
C.send(0x02, b"", reliable=False)
rb = rx(B, 0.6)
kk4.check("B told C left the scenario", gone(rb, C) == 1, str([d[:14] for d in rb]))

# 4. game data from the area: nobody
D.send(0x0F, b"\x00\x04\x00\x10fromD"); rx(D, 0.2)
got = []
for c in (A, B): rx(c, 0.3); got += game_from(c, D)
kk4.check("game data outside a game not relayed", not got)

# 5. metrics "bg"
ev = {"vid": "0123456789abcdef", "page": "file1", "lang": "es", "br": "chrome",
      "ev": [{"t": "bg", "game": "file1", "dur": 42, "fps": 12, "rx_s": 3.25, "online": True}]}
req = urllib.request.Request(a.api + "/api/metrics/event", data=json.dumps(ev).encode(), headers={"Content-Type": "application/json"})
try: res = json.loads(urllib.request.urlopen(req, timeout=5).read())
except Exception as e: res = {"error": str(e)}
kk4.check("metrics bg accepted", res.get("accepted") == 1, str(res))

for c in (A, B, D): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
