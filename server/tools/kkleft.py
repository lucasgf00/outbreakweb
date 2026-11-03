#!/usr/bin/env python3
"""Local check: a member leaves the scenario while its session goes on (dead or zombified, on the results screen;
protocol.md 6.17). Needs a LOCAL server (tests/KkLocal.java on loopback):

  python3 kkleft.py --port 19190 --api http://127.0.0.1:18188

A creates a room, B and C join, A starts; the three send game data (0x0F). Then:
 1. A's gateway says "REOGW-LEFT" + slot 0 on A's flow: B and C get "REOGW-LEFT" + A's tag + slot 0 once
    (not again on a repeated notice, nor when A is back in the waiting room with 0x0A).
 2. A's game data no longer reaches B or C; B's still reaches C (and A).
 3. A REOGW-LEFT from an address that is no session changes nothing."""
import argparse, socket, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19190); ap.add_argument("--api", default="http://127.0.0.1:18188")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C = (kk4.Client(n + sfx, addr, a.api) for n in ("lfa", "lfb", "lfc"))
kk4.check("logins", A.login() and B.login() and C.login())

def rx(c, wait):
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

def left(raw, who, slot):
    return sum(1 for d in raw if d == b"REOGW-LEFT" + struct.pack(">IB", who.tag, slot))

def notices(raw):
    return [d for d in raw if d.startswith(b"REOGW-GONE") or d.startswith(b"REOGW-LEFT")]

for c in (A, B, C):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); rx(c, 0.3); c.take()
A.send(0x04, b"left".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
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

# 1. A left the scenario (its gateway's notice on A's own flow)
A.s.sendto(b"REOGW-LEFT\x00", addr); rx(A, 0.2)
rb, rc = rx(B, 0.5), rx(C, 0.3)
kk4.check("B told A left (tag + slot 0)", left(rb, A, 0) == 1, str([d[:16] for d in rb]))
kk4.check("C told A left (tag + slot 0)", left(rc, A, 0) == 1, str([d[:16] for d in rc]))
A.s.sendto(b"REOGW-LEFT\x00", addr); rx(A, 0.2)
A.send(0x0A, struct.pack(">I", oid), flags=0x2800); rx(A, 0.3); A.take()
rb, rc = rx(B, 0.5), rx(C, 0.3)
kk4.check("no second notice (LEFT again, 0x0A)", not notices(rb) and not notices(rc), str([d[:16] for d in rb + rc]))

# 2. game data
for c in (B, C): c.take()
A.send(0x0F, b"\x00\x04\x00\x10fromA"); rx(A, 0.2)
B.send(0x0F, b"\x00\x04\x00\x10fromB"); rx(B, 0.2)
rx(C, 0.4); rx(B, 0.2)
gb, gc = B.take(0x0F), C.take(0x0F)
frm = lambda ms, who: [m for m in ms if m[3] == who.tag and m[5].startswith(b"\x00\x04")]
kk4.check("A's game data does not reach B or C", not frm(gb, A) and not frm(gc, A))
kk4.check("B's game data reaches C", len(frm(gc, B)) == 1)

# 3. a notice from a stranger address
X = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); X.bind(("127.0.0.1", 0)); X.sendto(b"REOGW-LEFT\x01", addr)
rb, rc = rx(B, 0.4), rx(C, 0.3)
kk4.check("stranger notice ignored", not notices(rb) and not notices(rc))

for c in (A, B, C): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
