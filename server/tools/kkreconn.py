#!/usr/bin/env python3
"""Reconnection and gateway close notice of the US kk lobby (KkService), LOCAL ONLY.

  python3 kkreconn.py --port 9093 --api http://127.0.0.1:8108

The web runtime reopens its WebSocket 3 s after a drop, so the gateway opens a new UDP flow (new source port)
for the same game session. A and B start a game; B "reconnects" (new socket, same tag): the server must offer
its pending reliable messages on the new address, move the session when B acks one, and relay A's game data
there. Then the gateway's close notice (b"REOGW-BYE" from the flow): from another address it does nothing; from
B's flow it drops B only after the grace period, unless B comes back first.
"""
import argparse, json, socket, struct, time, urllib.request
from kk4 import Client, check
import kk4

def rooms(api):
    return json.loads(urllib.request.urlopen(api + "/api/rooms").read())

def players_in(api, name):
    return name in json.dumps(rooms(api))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9093); ap.add_argument("--api", default="http://127.0.0.1:8108")
    ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201); ap.add_argument("--grace", type=int, default=30)
    a = ap.parse_args()
    addr = ("127.0.0.1", a.port)
    A, B = Client("rcA", addr, a.api), Client("rcB", addr, a.api)
    check("2 logins", A.login() and B.login())
    for c in (A, B):
        c.send(0x0D, struct.pack(">I", 0xF7E00001)); c.send(0x0C, bytes(0xF0)); c.send(0x0B, struct.pack(">I", a.area))
    for c in (A, B): c.pump(0.2); c.take()
    A.send(0x04, b"rcroom".ljust(16, b"\0") + struct.pack(">I", 2) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
    A.pump(0.3)
    oid = struct.unpack(">I", [m for m in A.take(0x28) if m[5][:4] == b"\0\0\0\x04"][0][5][4:8])[0]
    B.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); B.pump(0.3)
    for c in (A, B): c.pump(0.1); c.take()
    A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.2)
    for c in (A, B): c.send(0x0F, b"\0\0", reliable=False); c.pump(0.2); c.take()

    # B's WebSocket drops: A keeps sending game data to B's old flow (nobody reads it)
    old = B.s
    A.send(0x0F, b"\x00\x08\x00\x00GAME1"); A.pump(0.2)
    time.sleep(0.3)
    B.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); B.s.bind(("127.0.0.1", 0))
    B.send(0x0F, b"\x00\x10", reliable=False)       # first thing the game sends on the new flow: its keepalive
    B.pump(1.5)
    got = [m for m in B.take(0x0F) if b"GAME1" in m[5]]
    check("B gets the pending game data on its new address", got)
    A.send(0x0F, b"\x00\x08\x00\x00GAME2"); A.pump(0.2); B.pump(0.5)
    got2 = [m for m in B.take(0x0F) if b"GAME2" in m[5]]
    check("session moved: new game data goes to the new address", got2)
    old.settimeout(0.3)
    try: stale = old.recvfrom(4096)[0]
    except socket.timeout: stale = b""
    check("nothing new on the old address after the move", b"GAME2" not in stale)
    B.send(0x0F, b"\x00\x10\x00\x00FROMB"); B.pump(0.2); A.pump(0.4)
    check("B -> A still relayed", [m for m in A.take(0x0F) if b"FROMB" in m[5]])

    # close notices
    old.sendto(b"REOGW-BYE", addr)                   # B's dead old flow: not B's address any more
    A.pump(0.3)
    check("close notice of an old flow does not drop B", players_in(a.api, "rcB"))
    B.s.sendto(b"REOGW-BYE", addr)                   # B's flow closes; B comes back within the grace
    time.sleep(2)
    B.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); B.s.bind(("127.0.0.1", 0))
    B.send(0x0F, b"\x00\x10", reliable=False); B.pump(1.5); B.take()
    t0 = time.time()
    while time.time() - t0 < a.grace + 4:
        A.send(0x0F, b"\x00\x00", reliable=False); A.pump(0.5); B.send(0x0F, b"\x00\x10", reliable=False); B.pump(0.5)
    check("B came back within the grace: still in the room", players_in(a.api, "rcB"))
    B.s.sendto(b"REOGW-BYE", addr)                   # now for good
    t0 = time.time()
    while time.time() - t0 < a.grace + 4:
        A.send(0x0F, b"\x00\x00", reliable=False); A.pump(1.0)
    check(f"B dropped {a.grace} s after its close notice", not players_in(a.api, "rcB"))
    check("A still there", players_in(a.api, "rcA"))
    A.send(0x02, b"", reliable=False)
    print("ALL OK" if kk4.ok_all else "SOME FAILED")

if __name__ == "__main__":
    main()
