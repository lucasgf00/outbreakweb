#!/usr/bin/env python3
"""Local check: tracker data between web pages (protocol.md 6.18). Needs a LOCAL server (tests/KkLocal.java on
loopback):

  python3 kktrk.py --port 19190 --api http://127.0.0.1:18188

A and B play one game, C and D another, E stays in the area. Then:
 1. A's gateway sends "REOGW-TRK" + 11 bytes on A's flow: only B gets "REOGW-TRK" + A's tag + the same bytes (not A,
    not C/D of the other game, not E in the lobby).
 2. A second notice within 400 ms is dropped; one after the gap goes through.
 3. Invalid notices (slot 4, virus over 1000, wrong length) and a notice from a stranger address are not relayed.
 4. E (not in a game) sends one: nobody gets it.
 5. Game data still flows (B's 0x0F reaches A)."""
import argparse, socket, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19190); ap.add_argument("--api", default="http://127.0.0.1:18188")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C, D, E = (kk4.Client(n + sfx, addr, a.api) for n in ("tka", "tkb", "tkc", "tkd", "tke"))
kk4.check("logins", all(c.login() for c in (A, B, C, D, E)))

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

def body(slot, ch, st, virus, av=0, herb=0, bleed=0):
    return struct.pack(">BBBHHHH", slot, ch, st, virus, av, herb, bleed)

def trks(raw):
    return [d for d in raw if d.startswith(b"REOGW-TRK")]

for c in (A, B, C, D, E):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); rx(c, 0.3); c.take()

def game(host, guest, title):
    host.send(0x04, title.ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
    rx(host, 0.3)
    r = [m for m in host.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
    oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
    kk4.check(title.decode() + " created", oid)
    guest.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); rx(guest, 0.3)
    kk4.check(guest.name + " joins", any((m[2] & 0x7F) == 0x28 for m in guest.take()))
    host.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); rx(host, 0.3)
    for c in (host, guest): c.send(0x0F, b"\x00\x04\x00\x10ready"); rx(c, 0.2)
    for c in (host, guest): rx(c, 0.3); c.take()
    return oid

game(A, B, b"trk1"); game(C, D, b"trk2")
others = (A, C, D, E)

def everyone(wait=0.4):
    return {c.name: trks(rx(c, wait if c is B else 0.05)) for c in (B,) + others}

# 1. relay to the same game only
A.s.sendto(b"REOGW-TRK" + body(0, 0, 2, 343, 0, 600, 1115), addr)
got = everyone()
want = b"REOGW-TRK" + struct.pack(">I", A.tag) + body(0, 0, 2, 343, 0, 600, 1115)
kk4.check("B gets A's tracker data (tag + body)", got[B.name] == [want], str(got[B.name]))
kk4.check("nobody else gets it", not any(got[c.name] for c in others), str(got))

# 2. rate limit
time.sleep(0.5)
A.s.sendto(b"REOGW-TRK" + body(0, 0, 0, 350), addr)
A.s.sendto(b"REOGW-TRK" + body(0, 0, 0, 351), addr)
got = everyone()
kk4.check("two notices within 400 ms: one relayed", len(got[B.name]) == 1 and got[B.name][0][-11:] == body(0, 0, 0, 350), str(got[B.name]))
time.sleep(0.5)
A.s.sendto(b"REOGW-TRK" + body(0, 0, 0, 360), addr)
got = everyone()
kk4.check("after the gap: relayed", len(got[B.name]) == 1 and got[B.name][0][-11:] == body(0, 0, 0, 360), str(got[B.name]))

# 3. invalid notices, stranger
for bad in (body(4, 0, 0, 1), body(0, 0, 0, 1001), body(0, 8, 0, 1), body(0, 0, 0, 1) + b"\0"):
    time.sleep(0.45)
    A.s.sendto(b"REOGW-TRK" + bad, addr)
X = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); X.bind(("127.0.0.1", 0)); X.sendto(b"REOGW-TRK" + body(1, 1, 0, 5), addr)
got = everyone()
kk4.check("invalid and stranger notices not relayed", not any(got.values()), str(got))

# 4. E is not in a game
E.s.sendto(b"REOGW-TRK" + body(0, 3, 0, 5), addr)
got = everyone()
kk4.check("notice from a player in the lobby not relayed", not any(got.values()), str(got))

# 5. game data still flows
time.sleep(0.45)
B.s.sendto(b"REOGW-TRK" + body(1, 1, 0, 7), addr)
B.send(0x0F, b"\x00\x04\x00\x10fromB"); rx(B, 0.2)
ra = rx(A, 0.4)
kk4.check("B's tracker data reaches A", trks(ra) == [b"REOGW-TRK" + struct.pack(">I", B.tag) + body(1, 1, 0, 7)], str(trks(ra)))
kk4.check("B's game data reaches A", any(m[3] == B.tag and m[5].startswith(b"\x00\x04") for m in A.take(0x0F)))

for c in (A, B, C, D, E): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
