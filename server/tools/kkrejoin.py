#!/usr/bin/env python3
"""Local check of repeated room joins in KkService (needs a LOCAL server, e.g. tests/KkLocal.java on loopback):

  python3 kkrejoin.py --port 19090 --api http://127.0.0.1:18088

A creates a room for 2, B joins it and sends the same join again (once as a resend with the same reliable seq,
once with a new seq). Both repeats must get the 0x28 again (no 0x27, which hung the client in "Enter Room" when
the first join had filled the room) and A must get B's member push only once. C is then refused (room full), and
B leaving and joining again is a real join (A gets the push again)."""
import argparse, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19090); ap.add_argument("--api", default="http://127.0.0.1:18088")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B, C = (kk4.Client(n + sfx, addr, a.api) for n in ("rja", "rjb", "rjc"))
kk4.check("logins", A.login() and B.login() and C.login())
for c in (A, B, C):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()

A.send(0x04, b"rejoin".ljust(16, b"\0") + struct.pack(">I", 2) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
A.pump(0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room for 2 created", oid)
join = struct.pack(">II", oid, 1) + b"\0" * 16
kind = lambda msgs, t: [m for m in msgs if (m[2] & 0x7F) == t]

B.send(0x06, join); B.pump(0.3)
kk4.check("B joins", kind(B.take(), 0x28))
B.seq -= 1; B.send(0x06, join); B.pump(0.3)          # resend: same reliable seq
got = B.take()
kk4.check("resent join (same seq): 0x28 again, no 0x27", kind(got, 0x28) and not kind(got, 0x27),
          str([hex(m[2]) for m in got]))
B.send(0x06, join); B.pump(0.3)                      # repeat with a new seq
got = B.take()
kk4.check("repeated join (new seq): 0x28 again, no 0x27", kind(got, 0x28) and not kind(got, 0x27),
          str([hex(m[2]) for m in got]))
A.pump(0.3)
kk4.check("A got B's member push once", len(kind(A.take(), 0x06)) == 1)

C.send(0x06, join); C.pump(0.3)
kk4.check("C refused: room full", kind(C.take(), 0x27))

B.send(0x07, b""); B.pump(0.3); B.take(); A.pump(0.3); A.take()
B.send(0x06, join); B.pump(0.3)
kk4.check("B leaves and joins again", kind(B.take(), 0x28))
A.pump(0.3)
kk4.check("A got the push again", len(kind(A.take(), 0x06)) == 1)
for c in (A, B, C): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
