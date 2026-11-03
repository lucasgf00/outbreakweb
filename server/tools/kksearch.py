#!/usr/bin/env python3
"""Local check of Search for User (0x25) and the room lookup that follows (0x49 by OID), File #1 or #2
(hiihamster, 27/10: "Now searching" that never ends when the player is in a game). Needs a LOCAL server:

  python3 kksearch.py --port 19191 --api http://127.0.0.1:18188 [--title 2]

B in the lobby, in a waiting room, in a started room and in a room back to waiting; each time A searches B
(found, right area and room) and looks the room up (one entry, in-game flag only while started)."""
import argparse, struct, sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
import kk4

ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=19191); ap.add_argument("--api", default="http://127.0.0.1:18188")
ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201); ap.add_argument("--title", type=int, default=2)
a = ap.parse_args()
addr = ("127.0.0.1", a.port)
sfx = str(int(time.time()) % 100000)
A, B = (kk4.Client(n + sfx, addr, a.api) for n in ("sra", "srb"))
kk4.check("logins", A.login() and B.login())
for c in (A, B):
    c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area)); c.pump(0.3); c.take()

def search(where, room, in_game):
    A.send(0x25, B.name.encode().ljust(16, b"-") + struct.pack(">II", len(B.name), 1)); A.pump(0.4)
    r = A.take(0x25)
    pl = r[0][5] if r else b""
    found = len(pl) >= 12 + 0x20 and struct.unpack(">I", pl[4:8])[0] == 1
    area_oid, room_oid = struct.unpack(">II", pl[32:40]) if found else (None, None)
    kk4.check(f"{where}: found, area {area_oid:#x}, room {room_oid:#x}" if found else f"{where}: found",
              found and area_oid == a.area and room_oid == room)
    if room:
        A.send(0x49, struct.pack(">I", 1) + b"\x01\0\0\0" + b"OID\0" + b"\x01" + struct.pack(">II", room, 0)); A.pump(0.4)
        r = A.take(0x49)
        pl = r[0][5] if r else b""
        n = struct.unpack(">I", pl[8:12])[0] if len(pl) >= 12 else -1
        fl = struct.unpack(">I", pl[12 + 0x1C:12 + 0x20])[0] if n == 1 else 0
        kk4.check(f"{where}: lookup has the room, in-game flag {bool(fl & 0x40000000)}", n == 1 and bool(fl & 0x40000000) == in_game)

search("lobby", 0, False)
B.send(0x04, b"srch".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006)); B.pump(0.3)
r = [m for m in B.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
oid = struct.unpack(">I", r[0][5][4:8])[0] if r else 0
search("waiting room", oid, False)
B.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); B.pump(0.3)
B.send(0x0F, b"\x00\x04\x00\x10ready"); B.pump(0.3); B.take()
search("in game", oid, True)
B.send(0x0A, struct.pack(">I", oid), flags=0x2800); B.pump(0.3); B.take()
search("back to waiting", oid, False)
for c in (A, B): c.send(0x02, b"", reliable=False)
sys.exit(0 if kk4.ok_all else 1)
