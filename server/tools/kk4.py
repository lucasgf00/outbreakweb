#!/usr/bin/env python3
"""Protocol-level 4 (+1) player test of the US kk lobby (KkService), LOCAL ONLY.

  python3 kk4.py --port 9093 --api http://127.0.0.1:8108 [--title 2] [--area 0x201]

Five simulated clients log in (ticket from the encrypted 0x2D), enter an area, A creates a 4-player room,
B/C/D join, everyone lists the members (0x0A: 4 records, split into parts), A starts (STAT 0x40000000),
game data 0x0F flows (held until each member sends its first 0x0F, relay latency measured), then the room
goes back to waiting (0x0A from a member after the scenario), D leaves; the room stays out of the lists (0x0B,
and 0x49 LOID/STAT for File #2) and E's join is refused (the host's game takes nobody new there: the join hung
60 s), while A can START again.
"""
import argparse, hashlib, json, socket, struct, sys, time, urllib.request, urllib.error
from cryptography.hazmat.decrepit.ciphers.algorithms import Blowfish
from cryptography.hazmat.primitives.ciphers import Cipher, modes

TRAILER = 0xBA476611
ok_all = True

def check(what, cond, extra=""):
    global ok_all
    ok_all &= bool(cond)
    print(("OK   " if cond else "FAIL ") + what + (("  " + extra) if extra else ""))

def bf(key, data):
    data = data + b"\0" * (-len(data) % 8)
    d = Cipher(Blowfish(key), modes.ECB()).decryptor()
    return d.update(data) + d.finalize()

class Client:
    def __init__(self, name, addr, api):
        self.name, self.addr = name, addr
        self.cred = hashlib.sha256(f"reo-web:{name}:tok".encode()).hexdigest()[:12]
        req = urllib.request.Request(api + "/api/kk/register", json.dumps({"name": name, "credential": self.cred}).encode(),
                                     {"Content-Type": "application/json"})
        try: urllib.request.urlopen(req).read()
        except urllib.error.HTTPError as e: print("register", name, e.code)
        self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.s.bind(("127.0.0.1", 0))
        self.tag, self.seq, self.inbox = 0, 0, []

    def send(self, typ, pl=b"", reliable=True, flags=0x2000):
        fl = flags | (0x8000 if reliable else 0)
        seq = self.seq if reliable else int(time.time() * 100) & 0x3FFFFFFF
        if reliable: self.seq += 1
        d = struct.pack(">HHIII", fl | (16 + len(pl)), typ, self.tag, seq, 0) + pl + struct.pack(">I", TRAILER)
        self.s.sendto(d, self.addr)

    def send_multi(self, msgs):
        """One datagram with several messages, like the game's START packet: the first has flag 0x0800 and the real
        seq; the others carry stale seq/ack values (here 0 and 5, which this client already used)."""
        d = b""
        for k, (typ, pl) in enumerate(msgs):
            fl = 0xA000 | (0x0800 if k == 0 else 0)
            seq = self.seq if k == 0 else (0, 5)[k % 2]
            d += struct.pack(">HHIII", fl | (16 + len(pl)), typ, self.tag, seq, 0x06000000) + pl
        self.seq += 1
        self.s.sendto(d + struct.pack(">I", TRAILER), self.addr)

    def pump(self, wait=0.3):
        end = time.time() + wait
        while True:
            self.s.settimeout(max(0.005, end - time.time()))
            try:
                d = self.s.recvfrom(65536)[0]
            except socket.timeout:
                return
            t = time.time()
            body = d[:-4]; o = 0
            while o + 16 <= len(body):
                fl, ty, tag, seq, ack = struct.unpack(">HHIII", body[o:o + 16]); ln = fl & 0x3FF
                pl = body[o + 16:o + ln]
                self.inbox.append((t, fl & 0xFC00, ty, tag, seq, pl))
                if fl & 0x8000:   # bare ack like the game (type 0, flags 0x6000)
                    a = struct.pack(">HHIII", 0x6000 | 16, 0, self.tag, 0, seq) + struct.pack(">I", TRAILER)
                    self.s.sendto(a, self.addr)
                o += max(ln, 16)

    def take(self, typ=None):
        out = [m for m in self.inbox if typ is None or (m[2] & 0x7F) == typ]
        self.inbox = [m for m in self.inbox if m not in out]
        return out

    def login(self):
        self.send(0x2C, self.name.encode().ljust(0x28, b"\0") + (self.name + "@cei-auth").encode().ljust(0x48, b"\0"), reliable=False, flags=0x3000)
        self.pump(0.5)
        r = [m for m in self.take(0x2D)]
        if not r: return False
        plain = bf(self.cred.encode(), r[0][5])
        ticket = plain[0x3C:0x4C]
        pl = b"\0" * 0x10 + self.name.encode()[:15].ljust(0x10, b"-") + b"\0" * 4 + struct.pack(">I", 16) + ticket
        self.send(0x01, pl, reliable=False, flags=0x3000)
        self.pump(0.5)
        r = self.take(0x29)
        if not r: return False
        self.tag = struct.unpack(">I", r[0][5][8:12])[0]
        self.seq = 0
        return True

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9093); ap.add_argument("--api", default="http://127.0.0.1:8108")
    ap.add_argument("--title", type=int, default=1); ap.add_argument("--area", type=lambda x: int(x, 0), default=0x201)
    a = ap.parse_args()
    addr = ("127.0.0.1", a.port)
    cl = [Client(n, addr, a.api) for n in ("kk4a", "kk4b", "kk4c", "kk4d", "kk4e")]
    A, B, C, D, E = cl
    check("5 logins with ticket", all(c.login() for c in cl))
    for k, c in enumerate(cl):
        c.send(0x0D, struct.pack(">I", 0xF7E00001))
        prof = bytearray(0xF0); prof[0xC8] = k
        c.send(0x0C, bytes(prof))
        c.send(0x0B, struct.pack(">I", a.area))
    for c in cl: c.pump(0.2); c.take()
    A.send(0x04, b"kk4room".ljust(16, b"\0") + struct.pack(">I", 4) + b"\0" * 16 + struct.pack(">II", 1, 0x00080006))
    A.pump(0.3)
    r = [m for m in A.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
    check("create room", r)
    oid = struct.unpack(">I", r[0][5][4:8])[0]
    for c in (B, C, D):
        c.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16)
        c.pump(0.3)
        rr = [m for m in c.take() if (m[2] & 0x7F) in (0x27, 0x28)]
        check(f"{c.name} joins", rr and (rr[0][2] & 0x7F) == 0x28, str([(hex(m[2]), m[5].hex()) for m in rr]))
    for c in cl: c.pump(0.1); c.take()
    for c in (A, D):
        c.send(0x0A, struct.pack(">I", oid), flags=0x2800)
        c.pump(0.3)
        parts = c.take(0x0A)
        n = sum(struct.unpack(">I", m[5][8:12])[0] for m in parts)
        mx = max(16 + len(m[5]) for m in parts)
        last = parts[-1][1] & 0x2000 if parts else 0
        mids = all((m[1] & 0x2000) == 0 and (m[2] >> 8) != 0 for m in parts[:-1])
        check(f"{c.name} member list: 4 records", n == 4 and mx <= 0x3FF and (len(parts) == 1 or (last and mids)),
              f"{len(parts)} parts, largest {mx} bytes, last 0x2000={bool(last)}")
    # start: one packet = 0x10 class 3 to A itself (0x0800, real seq), to B, C, D (stale seqs) + STAT in game
    for c in cl: c.pump(0.1); c.take()
    msgs = [(0x10, struct.pack(">II", 1, x.tag) + bytes([3, 0, 0, 0])) for x in (A, B, C, D)]
    A.send_multi(msgs + [(0x08, b"STAT" + struct.pack(">I", 0x40000000))])
    for c in cl: c.pump(0.2)
    got3 = [len([m for m in c.take(0x10) if m[5][8:9] == b"\x03"]) for c in (B, C, D)]
    check("START packet: class 3 reaches B, C and D once", got3 == [1, 1, 1], str(got3))
    A.seq -= 1; A.send_multi(msgs)   # the same packet again (client resend)
    for c in cl: c.pump(0.2)
    dup = [len(c.take(0x10)) for c in (B, C, D)]
    check("resent START packet not relayed twice", dup == [0, 0, 0], str(dup))
    for c in cl: c.take()
    E.take(); E.send(0x0B, struct.pack(">I", a.area)); E.pump(0.3)
    lst = E.take(0x0B)
    check("started room not listed", lst and struct.unpack(">I", lst[0][5][8:12])[0] == 0)
    for c in cl: c.take()
    # game data: A sends 3 reliable 0x0F before the others have loaded; they get them after their own first 0x0F
    for i in range(3): A.send(0x0F, struct.pack(">HH", 6, 0) + bytes([0x10 + i, 0]), reliable=True)
    for i in range(10): A.send(0x0F, struct.pack(">HH", 0, 0), reliable=False)
    for c in cl: c.pump(0.2)
    early = [len(c.take(0x0F)) for c in (B, C, D)]
    check("game data held until loaded", early == [0, 0, 0], str(early))
    for c in (B, C, D): c.send(0x0F, struct.pack(">HH", 0, 0x10), reliable=False)
    for c in cl: c.pump(0.3)
    got = [len([m for m in c.take(0x0F) if m[3] == A.tag and m[1] & 0x8000]) for c in (B, C, D)]
    check("held reliable game data flushed to each member", got == [3, 3, 3], str(got))
    # relay latency: 50 unreliable 0x0F from A
    lat = []
    for i in range(50):
        t0 = time.time(); A.send(0x0F, struct.pack(">HHI", 4, 0, i), reliable=False)
        B.pump(0.05)
        for m in B.take(0x0F):
            lat.append((m[0] - t0) * 1000)
        A.pump(0.0); A.take()
    lat.sort()
    check("relay A->B unreliable", len(lat) >= 45, f"n={len(lat)} median {lat[len(lat)//2]:.2f} ms, max {lat[-1]:.2f} ms" if lat else "")
    for c in cl: c.pump(0.1); c.take()
    # back to the waiting room (a member lists the room again), D leaves: members only, out of the lists, E refused
    B.send(0x0A, struct.pack(">I", oid), flags=0x2800)
    for c in cl: c.pump(0.2)
    D.send(0x07)
    for c in cl: c.pump(0.2)
    for c in cl: c.take()
    E.send(0x0B, struct.pack(">I", a.area)); E.pump(0.3)
    lst = E.take(0x0B)
    n = struct.unpack(">I", lst[0][5][8:12])[0] if lst else -1
    users = struct.unpack(">I", lst[0][5][12 + 16:12 + 20])[0] if n > 0 else -1
    check("room back from a game stays out of the list (0x0B)", n == 0, f"n={n} users={users}")
    if a.title == 2:
        q = struct.pack(">IB3x", 30, 2) + b"LOID" + bytes([1]) + struct.pack(">I", a.area) + b"STAT" + bytes([0x21]) + struct.pack(">II", 0x40000000, 0)
        E.send(0x49, q); E.pump(0.3)
        l2 = E.take(0x49)
        n2 = struct.unpack(">I", l2[0][5][8:12])[0] if l2 else -1
        check("room back from a game stays out of the File #2 list (0x49)", n2 == 0, f"n={n2}")
    E.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); E.pump(0.3)
    rr = [m for m in E.take() if (m[2] & 0x7F) in (0x27, 0x28)]
    check("E refused by the room back from a game (0x27)", rr and (rr[0][2] & 0x7F) == 0x27, str([(hex(m[2]), m[5].hex()) for m in rr]))
    A.send(0x08, b"STAT" + struct.pack(">I", 0x40000000)); A.pump(0.3); A.take()
    E.send(0x06, struct.pack(">II", oid, 1) + b"\0" * 16); E.pump(0.3)
    rr = [m for m in E.take() if (m[2] & 0x7F) in (0x27, 0x28)]
    check("A starts again; the room is still closed to E", rr and (rr[0][2] & 0x7F) == 0x27, str([hex(m[2]) for m in rr]))
    # password room: wrong password -> 0x27 {6, 0x0F} ("Incorrect password"), right one -> 0x28 {6, 0}
    E.send(0x07); E.pump(0.2); E.take()
    E.send(0x04, b"pwroom".ljust(16, b"\0") + struct.pack(">I", 2) + b"111".ljust(16, b"\0") + struct.pack(">II", 1, 0x00080000 | 0x100))
    E.pump(0.3)
    r = [m for m in E.take(0x28) if struct.unpack(">I", m[5][:4])[0] == 4]
    pw_oid = struct.unpack(">I", r[0][5][4:8])[0] if r else 0
    D.send(0x06, struct.pack(">II", pw_oid, 1) + b"11".ljust(16, b"\0")); D.pump(0.3)
    rr = [m for m in D.take() if (m[2] & 0x7F) in (0x27, 0x28)]
    check("wrong password refused with 0x27 {6, 0x0F}", rr and (rr[0][2] & 0x7F) == 0x27 and rr[0][5][:8] == struct.pack(">II", 6, 0x0F))
    D.send(0x06, struct.pack(">II", pw_oid, 1) + b"111".ljust(16, b"\0")); D.pump(0.3)
    rr = [m for m in D.take() if (m[2] & 0x7F) in (0x27, 0x28)]
    check("right password accepted", rr and (rr[0][2] & 0x7F) == 0x28)
    for c in cl: c.send(0x02, reliable=False)
    print("ALL OK" if ok_all else "SOME FAILED")
    sys.exit(0 if ok_all else 1)

if __name__ == "__main__":
    main()
