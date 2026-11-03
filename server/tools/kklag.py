#!/usr/bin/env python3
"""Local check of the per-game lag line in KkService (needs a LOCAL server, e.g. tests/KkLocal.java on loopback):

  python3 kklag.py --port 19090 --api http://127.0.0.1:18088

Each client plays the gateway too: from its own UDP socket it sends "REOGW-RTT" + u32 ms, the datagram the
gateway writes on the flow after every WebSocket ping. A sends 30 before the game (taken as the first sample),
then 50; B sends 170 and 210. Both send game data (0x0F), then leave. The server log must show
"A rtt 40 ms (max 50, jitter 20, 2 samples), B rtt 190 ms (max 210, jitter 40, 2 samples); one-way est. worst pair 115 ms"."""
import argparse, struct, sys, time, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19090); ap.add_argument("--api", default="http://127.0.0.1:18088")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B = (kk4.Client(n + sfx, addr, a.api) for n in ("lga", "lgb"))
kk4.check("logins", A.login() and B.login())

def rtt(c, ms):
    c.s.sendto(b"REOGW-RTT" + struct.pack(">I", ms), addr)

rtt(A, 30)   # before the game: kept per address
for c in (A, B):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()
A.send(0x04, b"lagtest".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
A.pump(0.3)
r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else None
kk4.check("room created", oid)
B.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); B.pump(0.3)
kk4.check("B joins", any((m[2] & 0x7F) == 0x28 for m in B.take()))
A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3); B.pump(0.3); B.take()
for c in (A, B):
    c.send(0x0F, b"\x01\x02\x03\x04", reliable=False); c.pump(0.2)
rtt(A, 50); rtt(B, 170); time.sleep(0.2); rtt(B, 210); time.sleep(0.2)
for c in (A, B):
    c.send(0x0F, b"\x05\x06\x07\x08", reliable=False); c.pump(0.2)
B.send(0x02, b"", reliable=False); time.sleep(0.3)
A.send(0x02, b"", reliable=False); time.sleep(0.5)
print("sent; check the server log for 'stats after'")
