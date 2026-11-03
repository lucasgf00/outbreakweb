#!/usr/bin/env python3
"""Local check: a host dropped from a room "back to waiting" while a member is still in the scenario
(production 24/10, Melvo File #1 and MarckRE File #2). Needs a LOCAL server (tests/KkLocal.java on loopback):

  python3 kkbackgame.py --port 19090 --api http://127.0.0.1:18088

A creates a room, B and C join, A starts. B comes back to the waiting room (0x0A on the room = "back to waiting"),
C keeps playing. A drops (0x02). B must get the host's disband (0x10 class 0x0D) at once; C, still in game, must
get nothing for the lobby (no 0x10 0x0D, no 0x07 push, no 0x04/0x05 area list pushes for 6 s, more than a list
resync); back in the waiting room (0x0A) it gets the disband."""
import argparse, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19090); ap.add_argument("--api", default="http://127.0.0.1:18088")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C = (kk4.Client(n + sfx, addr, a.api) for n in ("bga", "bgb", "bgc"))
kk4.check("logins", A.login() and B.login() and C.login())
for c in (A, B, C):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()
A.send(0x04, b"backgame".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
A.pump(0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room created", oid)
for c in (B, C):
    c.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); c.pump(0.3)
    kk4.check(c.name + " joins", any((m[2] & 0x7F) == 0x28 for m in c.take()))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3)
for c in (A, B, C): c.pump(0.3); c.take()
B.send(0x0A, struct.pack(">I", oid), flags=0x2800); B.pump(0.4); B.take()     # B back in the waiting room
C.pump(0.3); C.take()

def disband(ms):
    return any((m[2] & 0x7F) == 0x10 and m[5][8:9] == b"\x0d" for m in ms)

def show(ms):
    return str([(hex(m[2]), m[5][:12].hex()) for m in ms])

A.send(0x02, b"", reliable=False)
B.pump(0.8); gb = B.take()
kk4.check("B (waiting room) gets the host disband", disband(gb), show(gb))
C.pump(6.5); gc = C.take()
lobby = [m for m in gc if (m[2] & 0x7F) in (0x04, 0x05, 0x07) or disband([m])]
kk4.check("C (in game) gets no lobby message", not lobby, show(lobby))
C.send(0x0A, struct.pack(">I", oid), flags=0x2800); C.pump(0.5); got = C.take()
kk4.check("C back in the room: host disband", disband(got), show(got))

# Case 2 (MarckRE, File #2 room 115f): the host comes back first, sends its own disband (0x10 class 0x0D to all,
# id 0), leaves (0x07) and creates a new room; D is still in game.
D = kk4.Client("bgd" + sfx, addr, a.api)
kk4.check("D login", D.login())
D.send(0x0C, bytes(0xF0)); D.send(0x0B, struct.pack(">I", a.area)); D.pump(0.3); D.take()
B.send(0x04, b"second".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006)); B.pump(0.3)
r = [m for m in B.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid2 = struct.unpack(">I", r[0][5][4:8])[0] if r else None
D.send(0x06, struct.pack(">II", oid2, 1) + b"\0" * 16); D.pump(0.3); D.take()
B.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); B.pump(0.3)
D.pump(0.3); D.take()
B.send(0x0A, struct.pack(">I", oid2), flags=0x2800); B.pump(0.3); B.take()    # host back first
B.send(0x10, struct.pack(">II", 1, 0) + b"\x0d\0\0\0"); B.pump(0.2)            # its own disband, to the room
B.send(0x07, b""); B.pump(0.2)
B.send(0x04, b"third".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006)); B.pump(0.3); B.take()
D.pump(6.5); gd = D.take()
lobby = [m for m in gd if (m[2] & 0x7F) in (0x04, 0x05, 0x07) or disband([m])]
kk4.check("D (in game) gets no lobby message after the host left and made a room", not lobby, show(lobby))
D.send(0x0F, b"\x00\x04\x00\x10gamedata"); D.pump(0.3)
D.send(0x0A, struct.pack(">I", oid2), flags=0x2800); D.pump(0.5); got = D.take()
kk4.check("D back in the room: host disband", disband(got), show(got))
for c in (B, C, D): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
