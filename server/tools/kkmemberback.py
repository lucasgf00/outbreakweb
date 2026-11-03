#!/usr/bin/env python3
"""Local check: a member (not the host) back in the waiting room while the others still play the scenario
(production 26/10, room 193d: d34n came back, the others' games dropped it only by the 300-s timeout).
Needs a LOCAL server (tests/KkLocal.java on loopback):

  python3 kkmemberback.py --port 19090 --api http://127.0.0.1:18088

A creates a room, B and C join, A starts, all send game data. B comes back (0x0A on the room): A and C, still in
game, must get REOGW-GONE + B's tag on their flow. Then C drops (0x02) while still in game: the server log must
show its trace ("C last messages before leaving game")."""
import argparse, socket, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19090); ap.add_argument("--api", default="http://127.0.0.1:18088")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C = (kk4.Client(n + sfx, addr, a.api) for n in ("mba", "mbb", "mbc"))
kk4.check("logins", A.login() and B.login() and C.login())
for c in (A, B, C):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()
A.send(0x04, b"memberback".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
A.pump(0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room created", oid)
for c in (B, C):
    c.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); c.pump(0.3)
    kk4.check(c.name + " joins", any((m[2] & 0x7F) == 0x28 for m in c.take()))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3)
for c in (A, B, C):
    c.send(0x0F, b"\x00\x04\x00\x10gamedata"); c.pump(0.2)
for c in (A, B, C): c.pump(0.3); c.take()


def gone(c, wait=1.0):
    """REOGW-GONE datagrams on c's socket (kk4.pump drops them: too short for a kk message)."""
    tags, end = [], time.time() + wait
    while time.time() < end:
        c.s.settimeout(max(0.005, end - time.time()))
        try:
            d = c.s.recvfrom(65536)[0]
        except socket.timeout:
            break
        if d.startswith(b"REOGW-GONE") and len(d) >= 14:
            tags.append(struct.unpack(">I", d[10:14])[0])
    return tags


B.send(0x0A, struct.pack(">I", oid), flags=0x2800); B.pump(0.2); B.take()     # B back in the waiting room
ga, gc = gone(A), gone(C)
kk4.check("A (host, in game) told B is gone", B.tag in ga, str(ga))
kk4.check("C (in game) told B is gone", B.tag in gc, str(gc))
C.send(0x02, b"", reliable=False); time.sleep(0.5)
for c in (A, B): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
