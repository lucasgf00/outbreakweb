#!/usr/bin/env python3
"""Local check of the room-list resync in KkService (needs a server on --port/--api, see kk4.py).

  python3 kkresync.py --port 9093 --api http://127.0.0.1:8108

A creates a room, E browses the area. After LIST_SYNC_MS (15 s) E must get the open room again (push type 4);
once the room starts E must get the removal (type 5) again, and a join to the started room is refused (0x27) and
followed by a type 5 push for that room."""
import argparse, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=9093); ap.add_argument("--api", default="http://127.0.0.1:8108")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
A, E = kk4.Client("rsa", addr, a.api), kk4.Client("rse", addr, a.api)
kk4.check("logins", A.login() and E.login())
for c in (A, E):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()
A.send(0x04, b"resync".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
A.pump(0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
kk4.check("create room", r)
oid = struct.unpack(">I", r[0][5][4:8])[0]
E.pump(0.3); E.take()
E.pump(17)
n4 = [m for m in E.take(0x04) if struct.unpack(">I", m[5][0x20:0x24])[0] == oid]
kk4.check("open room resent as type 4 after 15 s", len(n4) >= 1, str(len(n4)))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3)
E.pump(0.3); first = E.take(0x05)
kk4.check("removal pushed once at START", len(first) >= 1, str(len(first)))
E.pump(17)
again = [m for m in E.take(0x05) if struct.unpack(">I", m[5][:4])[0] == oid]
kk4.check("removal resent as type 5 after 15 s", len(again) >= 1, str(len(again)))
E.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); E.pump(0.5)
ref = E.take()
kinds = [m[2] & 0x7F for m in ref]
kk4.check("join refused (0x27) then list fixed (0x05)", 0x27 in kinds and 0x05 in kinds, str([hex(k) for k in kinds]))
sys.exit(0 if kk4.ok_all else 1)
