#!/usr/bin/env python3
"""Test client for the reo-web bioserver: speaks the JP Bioserver lobby protocol.

It plays the part of the PS2 client far enough to validate our understanding of
the protocol (12-byte header, XOR string cipher, session login, area/room lists)
and the gameserver relay. Only talks to the host given (default 127.0.0.1).
Never point this at a third-party server.

  tools/testclient.py                       # File #1 on 127.0.0.1, users test1/test2
  tools/testclient.py --game file2          # File #2 (lobby 8200, game 8590)
  tools/testclient.py --sessid 12345678     # skip the HTTP dev login

Exit code 0 = all checks passed.
"""
import argparse
import json
import socket
import struct
import sys
import time
import urllib.parse
import urllib.request

# ---- protocol constants (from Bioserver Commands.java) ---------------------
SERVER, CLIENT, GAMESERVER, GAMECLIENT = 0x18, 0x81, 0x28, 0x82
QUERY, TELL, BROADCAST = 0x01, 0x02, 0x10

LOGIN, CHECKVERSION, CHECKRND = 0x6101, 0x6103, 0x600E
UNKN61A0, IDHNPAIRS, HNSELECT, LOGIN_DONE = 0x61A0, 0x6131, 0x6132, 0x6104
MOTHEDAY, CHARSELECT = 0x614C, 0x6190
AREACOUNT, AREANAME, AREAPLAYERCNT, AREASTATUS, AREADESCRIPT, AREASELECT = 0x6203, 0x6204, 0x6205, 0x6206, 0x620A, 0x6207
ROOMSCOUNT, ROOMNAME, ROOMSTATUS, ENTERROOM = 0x6301, 0x6302, 0x6304, 0x6305
SLOTCOUNT, SLOTSTATUS = 0x6401, 0x6404
CONNCHECK, HEARTBEAT, LOGOUT = 0x6001, 0x6202, 0x6006
GSLOGIN = 0x1031

HDR = struct.Struct(">BBHHHB3s")   # who qsw cmd len pid err ff ff ff

# ---- cipher (Packet.calc_shift in Bioserver) ---------------------------------
FIXVAL = [21, 23, 10, 17, 23, 19, 6, 13]
MASKS = [0x33, 0x30, 0x3C, 0x34, 0x2D, 0x30, 0x3C, 0x34]


def sbyte(v):
    v &= 0xFF
    return v - 0x100 if v & 0x80 else v


def calc_shift(i, p):
    """Key byte for string index i and packet-id low byte p (Java signed-byte semantics)."""
    i, p = sbyte(i), sbyte(p)
    return (FIXVAL[i & 7] - (i & sbyte(0xF8)) - p + ((p - 9 + i) & MASKS[i & 7]) * 2) & 0xFF


def crypt(data, pid):
    return bytes(b ^ calc_shift(i, pid & 0xFF) for i, b in enumerate(data))


def enc_string(s, pid):
    """String field: len16 (= n + 2), sum16, n encrypted bytes.
    The checksum algorithm is unknown [HIPOTESIS]: the server never checks it; we send sum(bytes)."""
    return struct.pack(">HH", len(s) + 2, sum(s) & 0xFFFF) + crypt(s, pid)


# ---- framing ----------------------------------------------------------------
class Conn:
    def __init__(self, host, port, name, verbose):
        self.s = socket.create_connection((host, port), timeout=10)
        self.buf = b""
        self.name = name
        self.verbose = verbose
        self.pid = 0x0100
        self.pending = []

    def next_pid(self):
        self.pid = (self.pid + 1) & 0xFFFF
        return self.pid

    def send(self, who, qsw, cmd, payload=b"", pid=None):
        if pid is None:
            pid = self.next_pid()
        pkt = HDR.pack(who, qsw, cmd, len(payload), pid, 0, b"\xff\xff\xff") + payload
        if self.verbose:
            print(f"  {self.name} >> cmd={cmd:04x} qsw={qsw:02x} pid={pid:04x} len={len(payload)} {payload[:32].hex()}")
        self.s.sendall(pkt)
        return pid

    def _read_frame(self):
        while True:
            if len(self.buf) >= 12:
                who, qsw, cmd, ln, pid, err, tail = HDR.unpack_from(self.buf)
                if len(self.buf) >= 12 + ln:
                    payload = self.buf[12:12 + ln]
                    self.buf = self.buf[12 + ln:]
                    if tail != b"\xff\xff\xff":
                        raise AssertionError(f"header trailer is {tail.hex()}, expected ffffff")
                    f = dict(who=who, qsw=qsw, cmd=cmd, pid=pid, err=err, payload=payload)
                    if self.verbose:
                        print(f"  {self.name} << who={who:02x} cmd={cmd:04x} qsw={qsw:02x} pid={pid:04x} len={ln} {payload[:32].hex()}")
                    return f
            chunk = self.s.recv(65536)
            if not chunk:
                raise ConnectionError("server closed the connection")
            self.buf += chunk

    def expect(self, cmd, qsw=None, pid=None):
        """Return the next frame with this cmd (and qsw/pid); auto-answer CONNCHECK, queue the rest."""
        for i, f in enumerate(self.pending):
            if f["cmd"] == cmd and (qsw is None or f["qsw"] == qsw) and (pid is None or f["pid"] == pid):
                return self.pending.pop(i)
        while True:
            f = self._read_frame()
            if f["cmd"] == CONNCHECK and f["qsw"] == QUERY:
                self.send(CLIENT, TELL, CONNCHECK, pid=f["pid"])
                continue
            if f["cmd"] == cmd and (qsw is None or f["qsw"] == qsw) and (pid is None or f["pid"] == pid):
                return f
            self.pending.append(f)

    def query(self, cmd, payload=b""):
        pid = self.send(CLIENT, QUERY, cmd, payload)
        f = self.expect(cmd, TELL, pid)
        return f["payload"], pid

    def close(self):
        self.s.close()


# ---- helpers ----------------------------------------------------------------
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))
    return ok


def u16(b, o=0):
    return struct.unpack_from(">H", b, o)[0]


def dev_login(api, game, user, password):
    data = urllib.parse.urlencode({"game": game, "user": user, "password": password}).encode()
    with urllib.request.urlopen(urllib.request.Request(api + "/api/dev/login", data=data), timeout=5) as r:
        return json.load(r)["sessid"]


def api_rooms(api, game):
    with urllib.request.urlopen(api + "/api/rooms?game=" + game, timeout=5) as r:
        return json.load(r)


def session_digits(sessid, seed):
    """LOGIN/GSLOGIN obfuscation: two 4-digit halves, each + seed, as 5 ASCII digits."""
    a, b = int(sessid[:4]) + seed, int(sessid[4:]) + seed
    return f"{a:05d}{b:05d}".encode()


# ---- scenarios --------------------------------------------------------------
def lobby_login(args, sessid, nickname, char_id):
    c = Conn(args.host, args.lobby_port, "lobby", args.verbose)

    # 1. server opens with LOGIN query (payload = 2 byte seed in upstream: 28 37)
    f = c.expect(LOGIN, QUERY)
    check("server sends LOGIN 0x6101 query on connect", f["who"] == SERVER, f"payload={f['payload'].hex()}")

    # 2. answer with the obfuscated session. Payload layout: 2 bytes [HIPOTESIS: length 0x000a] + 10 digits.
    seed = c.next_pid()
    c.send(CLIENT, TELL, LOGIN, b"\x00\x0a" + session_digits(sessid, seed), pid=seed)

    # 3. session accepted -> server asks for the version
    f = c.expect(CHECKVERSION, QUERY)
    check("session accepted (server sends CHECKVERSION 0x6103)", True)

    # 4. version answer: 3 unknown bytes + string field (server only logs it)
    pid = c.next_pid()
    c.send(CLIENT, TELL, CHECKVERSION, b"\x00\x00\x00" + enc_string(b"101 0311172500", pid), pid=pid)

    # 5. ID/HN pairs broadcast
    f = c.expect(IDHNPAIRS, BROADCAST)
    p = f["payload"]
    check("IDHNPAIRS 0x6131 received", len(p) >= 1, f"{p[0]} existing handle(s)")
    handles, o = [], 1
    for _ in range(p[0]):
        hl = u16(p, o); h = p[o + 2:o + 2 + hl]; o += 2 + hl
        nl = u16(p, o); n = p[o + 2:o + 2 + nl]; o += 2 + nl + 2
        handles.append((h.decode("latin-1"), n.decode("shift_jis", "replace")))
    if handles:
        print(f"       existing handles: {handles}")

    # 6. timeout/latency query
    pl, _ = c.query(UNKN61A0)
    check("UNKN61A0 query answered", len(pl) == 8, pl.hex())

    # 7. CHECKRND validates the XOR cipher: server decrypts our string and echoes the first char
    ok = True
    for ch in b"0", b"7", b"Z":
        pid = c.next_pid()
        c.send(CLIENT, QUERY, CHECKRND, enc_string(ch + b"abc", pid), pid=pid)
        r = c.expect(CHECKRND, TELL, pid)["payload"]
        ok &= (r[2:3] == ch)
    check("CHECKRND 0x600E: server decrypts our XOR strings (index 0, 3 pids)", ok)

    # 8. choose handle: reuse first existing one, or "******" = create new
    handle = handles[0][0].encode() if handles else b"******"
    pid = c.next_pid()
    c.send(CLIENT, QUERY, HNSELECT, enc_string(handle, pid) + enc_string(nickname.encode(), pid), pid=pid)
    r = c.expect(HNSELECT, TELL, pid)["payload"]
    got = r[2:8].decode("latin-1")
    check("HNSELECT 0x6132: handle assigned", u16(r) == 6 and got != "******", got)
    c.expect(LOGIN_DONE, BROADCAST)
    check("login sequence finished (0x6104 broadcast)", True)

    # 9. message of the day
    pl, _ = c.query(MOTHEDAY)
    check("MOTD 0x614C answered", len(pl) > 0, pl[:60].decode("latin-1", "replace").replace("\x00", "."))

    # 10. character select: 4 unknown bytes + 0xD0 encrypted stats; stats[0xc8] = character
    stats = bytearray(0xD0)
    stats[0xC8] = char_id
    pid = c.next_pid()
    c.send(CLIENT, QUERY, CHARSELECT, b"\x00\x00\x00\xd0" + crypt(bytes(stats), pid), pid=pid)
    c.expect(CHARSELECT, TELL, pid)
    check("CHARSELECT 0x6190 acknowledged", True)
    return c, got


def bad_session_rejected(args):
    c = Conn(args.host, args.lobby_port, "bad", args.verbose)
    c.expect(LOGIN, QUERY)
    seed = c.next_pid()
    c.send(CLIENT, TELL, LOGIN, b"\x00\x0a" + session_digits("00000001", seed), pid=seed)
    try:
        f = c.expect(CHECKVERSION, QUERY)
        ok = False
    except socket.timeout:
        ok = False
    except (ConnectionError, OSError):
        ok = True
    c.close()
    check("unknown session is rejected (server closes the connection)", ok)


def area_room_tour(c):
    pl, _ = c.query(AREACOUNT)
    n = u16(pl)
    check("AREACOUNT 0x6203", n > 0, f"{n} areas")
    areas = []
    for a in range(1, n + 1):
        nm, _ = c.query(AREANAME, struct.pack(">H", a))
        st, _ = c.query(AREASTATUS, struct.pack(">H", a))
        cnt, _ = c.query(AREAPLAYERCNT, struct.pack(">H", a))
        de, _ = c.query(AREADESCRIPT, struct.pack(">H", a))
        name = nm[4:4 + u16(nm, 2)].decode("latin-1")
        areas.append((a, name, st[2], u16(cnt, 2), u16(cnt, 4)))
    print("       areas: " + ", ".join(f"{a}:{nm}(status={s},lobby={x},rooms={y})" for a, nm, s, x, y in areas))
    check("AREANAME/STATUS/PLAYERCNT/DESCRIPT for every area", len(areas) == n)

    pl, _ = c.query(AREASELECT, struct.pack(">H", 1))
    check("AREASELECT 0x6207 area 1", u16(pl) == 1)
    pl, _ = c.query(ROOMSCOUNT)
    nr = u16(pl)
    rooms = []
    for r in range(1, nr + 1):
        nm, _ = c.query(ROOMNAME, struct.pack(">H", r))
        st, _ = c.query(ROOMSTATUS, struct.pack(">H", r))
        rooms.append(f"{nm[4:4 + u16(nm, 2)].decode('latin-1')}(status={st[2]})")
    check("ROOMSCOUNT/ROOMNAME/ROOMSTATUS", len(rooms) == nr and nr > 0, ", ".join(rooms))
    pl, _ = c.query(ENTERROOM, struct.pack(">H", 1))
    check("ENTERROOM 0x6305 room 1", u16(pl) == 1)
    pl, _ = c.query(SLOTCOUNT)
    check("SLOTCOUNT 0x6401", u16(pl) > 0, f"{u16(pl)} slots")
    pl, _ = c.query(SLOTSTATUS, struct.pack(">H", 1))
    check("SLOTSTATUS 0x6404 slot 1", pl[2] == 1, f"status={pl[2]} (1=free)")


def gameserver_relay(args, sess_a, sess_b):
    conns = []
    for name, sess in (("gsA", sess_a), ("gsB", sess_b)):
        g = Conn(args.host, args.game_port, name, args.verbose)
        f = g.expect(GSLOGIN, QUERY)
        if f["who"] != GAMESERVER:
            check(f"{name}: GSLOGIN query from gameserver", False, f"who={f['who']:02x}")
        seed = g.next_pid()
        # GSLOGIN answer: who=0x82 qsw=0x02, payload = 10 digits, no length prefix
        g.send(GAMECLIENT, TELL, GSLOGIN, session_digits(sess, seed), pid=seed)
        conns.append(g)
    check("gameserver: GSLOGIN 0x1031 query received by both clients", True)
    time.sleep(0.5)
    # in-game frames: first byte = total length (opaque for the server)
    msg = bytes([12]) + b"RELAYTEST!!"
    conns[0].s.sendall(msg)
    conns[1].s.settimeout(5)
    data = b""
    try:
        while len(data) < len(msg):
            chunk = conns[1].s.recv(4096)
            if not chunk:
                break
            data += chunk
    except socket.timeout:
        pass
    check("gameserver relays an opaque in-game frame A -> B", data == msg, data.hex())
    for g in conns:
        g.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--game", default="file1", choices=["file1", "file2"])
    ap.add_argument("--lobby-port", type=int)
    ap.add_argument("--game-port", type=int)
    ap.add_argument("--api", default="http://127.0.0.1:8088")
    ap.add_argument("--user", default="test1")
    ap.add_argument("--password", default="test1")
    ap.add_argument("--user2", default="test2")
    ap.add_argument("--password2", default="test2")
    ap.add_argument("--sessid", help="use this session instead of /api/dev/login")
    ap.add_argument("--nickname", default="Tester Uno")
    ap.add_argument("--no-gameserver", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    if args.lobby_port is None:
        args.lobby_port = 8300 if args.game == "file1" else 8200
    if args.game_port is None:
        args.game_port = 8690 if args.game == "file1" else 8590

    sess = args.sessid or dev_login(args.api, args.game, args.user, args.password)
    check("dev login -> session id", len(sess) == 8 and sess.isdigit(), sess)
    bad_session_rejected(args)

    char_id = 3
    c, handle = lobby_login(args, sess, args.nickname, char_id)
    area_room_tour(c)

    # the lobby state must now be visible over HTTP, with the nickname decrypted correctly
    time.sleep(0.3)
    snap = api_rooms(args.api, args.game)
    game = snap["games"][0]
    room1 = game["areas"][0]["rooms"][0]
    me = [p for p in room1["players"] if p["handle"] == handle]
    check("/api/rooms shows us in area 1 / room 1", len(me) == 1, json.dumps(me))
    check("/api/rooms nickname == ours (XOR, all indices)", bool(me) and me[0]["nickname"] == args.nickname)
    check("/api/rooms character == stats[0xc8] (XOR, index 200 / negative byte)", bool(me) and me[0]["character"] == char_id)

    if not args.no_gameserver:
        sess2 = dev_login(args.api, args.game, args.user2, args.password2)
        gameserver_relay(args, sess, sess2)

    c.send(CLIENT, QUERY, LOGOUT)
    c.close()
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed" + (f"; FAILED: {failed}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
