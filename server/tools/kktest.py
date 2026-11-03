#!/usr/bin/env python3
"""Offline check of the US kk lobby (KkService or kkudp.py): login, connect, directory queries.
usage: kktest.py [host] [port] [password]   -> prints PASS/FAIL per check."""
import socket, struct, sys

sys.path.insert(0, __import__("os").path.dirname(__file__))
import kkudp

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 9090
pw = (sys.argv[3] if len(sys.argv) > 3 else "1111").encode()
LOGIN = bytes.fromhex(  # captured 0x2C from the game (docs/logs/net-us-kk-login-udp9090.txt)
    "3096002c020f07d00000000000000000746573743100000000000000000000000000000000000000000000000000000000000000"
    "000000007465737431406365692d617574680000000000000000000000000000000000000000000000000000000000000000000000"
    "00000000000000000000000000000000000000cae0000000000a00020f000007d00000006400024c43000006404d5200000030ba476611")
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(3)
fails = 0


def rx():
    d, _ = s.recvfrom(4096)
    return kkudp.split(d)[1][0]


def check(name, ok):
    global fails
    fails += not ok
    print(("PASS " if ok else "FAIL ") + name)


s.sendto(LOGIN, (host, port))
m = rx()
plain = kkudp.bf(pw, m["payload"], False)
check("0x2D login reply decrypts to the name", m["type"] == 0x2D and kkudp.cstr(plain[:0x28]) == "test1")
port2 = struct.unpack(">I", plain[0x2C:0x30])[0]
check("session server port = %d" % port2, port2 == port)
conn = bytes.fromhex("0a00020f000000f0f7e0000107d00064") + b"test1".ljust(15, b"-") + b"\0" * 0x200
s.sendto(kkudp.msg(0x01, conn, 0) + struct.pack(">I", kkudp.TRAILER), (host, port))
m = rx()
tag = struct.unpack(">III", m["payload"][:12])[2]
check("0x29 connect accepted, session %#x" % tag, m["type"] == 0x29 and tag != 0)


def query(conds, mx, seq):
    pl = struct.pack(">IB3x", mx, len(conds)) + b"".join(b"NAME" + op.encode() + v.encode().ljust(16, b"\0") for op, v in conds)
    s.sendto(kkudp.msg(0x48, pl, seq, flags=0xB000, tag=tag) + struct.pack(">I", kkudp.TRAILER), (host, port))
    r = rx()
    n = struct.unpack(">I", r["payload"][8:12])[0]
    names = [kkudp.cstr(r["payload"][12 + 0x24 * i:12 + 0x24 * i + 16]) for i in range(n)]
    return r, names


r, names = query([("A", "obmft")], 5, 2)
check("0x48 obmft (acked seq 2)", r["type"] == 0x48 and names == ["obmft"] and r["ack"] == 2 and r["flags"] & 0x4000)
r, names = query([("D", "obmf01"), ("F", "obmf99")], 99, 3)
check("0x48 areas obmf01..99 -> %s" % names, len(names) >= 1 and all(n.startswith("obmf") for n in names))
s.sendto(kkudp.msg(0x09, struct.pack(">I4s", 0x100, b"USER"), 4, flags=0xB000, tag=tag) + struct.pack(">I", kkudp.TRAILER), (host, port))
r = rx()
check("0x09 USER of obmft = %d" % struct.unpack(">I", r["payload"][8:12])[0], r["type"] == 0x09)
sys.exit(1 if fails else 0)
