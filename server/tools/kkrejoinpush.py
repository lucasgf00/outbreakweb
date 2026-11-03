#!/usr/bin/env python3
"""Local check of us.kk.rejoin_push (protocol.md 6.16). Needs a LOCAL server started with us.kk.rejoin_push=true:

  python3 kkrejoinpush.py --port 19190 --api http://127.0.0.1:18188

A creates a room, B joins, A starts. B is back in the waiting room first (0x0A on the room): its member list has
only B (A is still in the scenario). A comes back: B gets the push 0x06 with A's record, and A's list has both."""
import argparse, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19190); ap.add_argument("--api", default="http://127.0.0.1:18188")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B = (kk4.Client(n + sfx, addr, a.api) for n in ("rpa", "rpb"))
kk4.check("logins", A.login() and B.login())
for c in (A, B):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()
A.send(0x04, b"rejoin".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006)); A.pump(0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room created", oid)
B.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); B.pump(0.3); B.take(); A.pump(0.3); A.take()
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3)
for c in (A, B): c.send(0x0F, b"\x00\x04\x00\x10ready"); c.pump(0.2)
for c in (A, B): c.pump(0.2); c.take()

def names(msgs):
    out = []
    for m in msgs:
        pl = m[5]
        for o in range(12, len(pl) - 0xF, 0x108):
            out.append(pl[o:o + 16].split(b"\0")[0].decode(errors="replace"))
    return out

B.send(0x0A, struct.pack(">I", oid), flags=0x2800); B.pump(0.5)
lb = names(B.take(0x0A))
kk4.check("B back first: its list has only B", lb == [B.name], str(lb))
A.send(0x0A, struct.pack(">I", oid), flags=0x2800); A.pump(0.5); B.pump(0.5)
la = names(A.take(0x0A))
kk4.check("A back: its list has both", sorted(la) == sorted([A.name, B.name]), str(la))
p6 = [m for m in B.take(0x06) if A.name.encode() in m[5]]
kk4.check("B got the 0x06 push for A", len(p6) == 1)
for c in (A, B): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
