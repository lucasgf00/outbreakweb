#!/usr/bin/env python3
"""Local check of us.kk.host_bye_secs (default 6): the HOST of a game in progress whose gateway flow closed is
dropped after that time, not after the 30-s reconnect grace (protocol.md 6.16). Needs a LOCAL server
(tests/KkLocal.java on loopback):

  python3 kkhostbye.py --port 19190 --api http://127.0.0.1:18188 [--secs 6]

A creates a room, B and C join, A starts, everybody sends game data. Then:
 1. A's flow closes (REOGW-BYE) and A is back 3 s later on a new flow (like the web runtime): A stays, B gets no
    REOGW-GONE, game data flows again.
 2. C (not the host) closes its flow for good: after secs + 3 s nobody was told (members keep the 30-s grace).
 3. A closes its flow for good: B gets REOGW-GONE + A's tag after secs..secs+3 s."""
import argparse, socket, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19190); ap.add_argument("--api", default="http://127.0.0.1:18188")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201); ap.add_argument("--secs", type=float, default=6)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C = (kk4.Client(n + sfx, addr, a.api) for n in ("hya", "hyb", "hyc"))
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

def gone(raw, who):
    return sum(1 for d in raw if d == b"REOGW-GONE" + struct.pack(">I", who.tag))

for c in (A, B, C):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); rx(c, 0.3); c.take()
A.send(0x04, b"hostbye".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
rx(A, 0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room created", oid)
for c in (B, C):
    c.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); rx(c, 0.3)
    kk4.check(c.name + " joins", any((m[2] & 0x7F) == 0x28 for m in c.take()))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); rx(A, 0.3)
for c in (A, B, C): c.send(0x0F, b"\x00\x04\x00\x10ready"); rx(c, 0.2)
for c in (A, B, C): rx(c, 0.2); c.take()

def play(secs, who):
    """Everybody in `who` keeps sending keepalives for secs; returns what B received (raw)."""
    got, end = [], time.time() + secs
    while time.time() < end:
        for c in who: c.send(0x0F, b"\x00\x10", reliable=False)
        got += rx(B, 0.25)
        for c in who:
            if c is not B: rx(c, 0.05)
    return got

# 1. quick reconnect of the host
A.s.sendto(b"REOGW-BYE", addr)
got = play(3.0, (B, C))
A.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); A.s.bind(("127.0.0.1", 0))
got += play(a.secs + 3, (A, B, C))
kk4.check("host back after 3 s: B not told", gone(got, A) == 0)
B.take(); A.take()
A.send(0x0F, b"\x00\x04\x00\x10again"); rx(A, 0.2); rx(B, 0.4)
kk4.check("host back after 3 s: its game data reaches B", [m for m in B.take(0x0F) if b"again" in m[5]])

# 2. a member (not the host) closes its flow: still the 30-s grace
C.s.sendto(b"REOGW-BYE", addr)
got = play(a.secs + 3, (A, B))
kk4.check("member gone: nobody told before the grace", gone(got, C) == 0)

# 3. the host closes its flow for good
t0 = time.time()
A.s.sendto(b"REOGW-BYE", addr)
got, told = [], None
while time.time() - t0 < a.secs + 5 and told is None:
    got += play(0.5, (B,))
    if gone(got, A): told = time.time() - t0
kk4.check("host gone: B told after %.1f s" % (told or -1), told is not None and a.secs - 0.5 <= told <= a.secs + 3)
for c in (B,): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
