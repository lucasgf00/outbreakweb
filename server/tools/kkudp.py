#!/usr/bin/env python3
"""Prototype of the US File #1 'kkClient Ver 2.1' UDP service (snap01:9090).

Wire format (read from SLUS_207.65, see reoutbreak docs/notes/protocol.md section 6.5):
  datagram = msg [msg...] + u32 BE trailer 0xBA4766xx
  msg      = 16-byte header (all big-endian) + payload
    u16 flags|len   len = header+payload (& 0x3FF); 0x8000 reliable (seq at +8),
                    0x4000 carries ack (+12), 0x0800 bundle, 0x1000/0x2000 seen on client msgs
    u16 type        low 7 bits = message type
    u32 +4          sender tag (client puts its IP low half + port)
    u32 +8          sequence
    u32 +12         ack
Login: client 0x2C (name, principal name@cei-auth, ...). Server 0x2D: payload
encrypted with Blowfish-ECB (big-endian words), key = the password string.
"""
import argparse, socket, struct, sys, time

from cryptography.hazmat.decrepit.ciphers.algorithms import Blowfish
from cryptography.hazmat.primitives.ciphers import Cipher, modes

TRAILER = 0xBA476611


def bf(key, data, enc):
    data = data + b"\0" * (-len(data) % 8)
    c = Cipher(Blowfish(key), modes.ECB())
    x = c.encryptor() if enc else c.decryptor()
    return x.update(data) + x.finalize()


def msg(typ, payload, seq, flags=0x1000, tag=0, ack=0):
    n = 16 + len(payload)
    return struct.pack(">HHIII", flags | n, typ, tag, seq, ack) + payload


def split(dgram):
    body, (tr,) = dgram[:-4], struct.unpack(">I", dgram[-4:])
    out = []
    while body:
        f, t, tag, seq, ack = struct.unpack(">HHIII", body[:16])
        n = f & 0x3FF
        out.append(dict(flags=f & 0xFC00, type=t & 0x7F, hi=t >> 8, tag=tag, seq=seq, ack=ack, payload=body[16:n]))
        body = body[n:]
    return tr, out


def cstr(b):
    return b.split(b"\0", 1)[0].decode("latin1")


class Server:
    def __init__(self, a):
        self.a = a
        self.seq = {}
        self.log = open(a.log, "a") if a.log else sys.stdout

    def p(self, *x):
        print(time.strftime("%H:%M:%S"), *x, file=self.log, flush=True)

    def send(self, s, addr, typ, payload, **kw):
        q = self.seq.get(addr, 0) + 1
        self.seq[addr] = q
        d = msg(typ, payload, q, **kw) + struct.pack(">I", TRAILER)
        self.p("S>C", addr, "type=0x%02x" % typ, len(d), d.hex())
        s.sendto(d, addr)

    def login(self, s, addr, m):
        pl = m["payload"]
        name, principal = cstr(pl[0:0x28]), cstr(pl[0x28:0x64])
        ip = pl[0x6C:0x70]
        self.p("login name=%r principal=%r ip=%s" % (name, principal, socket.inet_ntoa(ip)))
        pw = self.a.password.encode()
        r = bytearray(0x3C)
        r[0:len(name)] = name.encode()
        # session server the client connects to next with msg 0x01 (ticket): IP -> ctx+0x500, port -> ctx+0x504
        r[0x28:0x2C] = socket.inet_aton(self.a.public_ip)
        r[0x2C:0x30] = struct.pack(">I", self.a.port)
        r[0x30:0x34] = struct.pack(">HH", 0, self.a.port)  # +0x32: next server port (same IP)
        ticket = b"REOWEB-TICKET-01"
        r[0x34:0x38] = struct.pack(">I", len(ticket))     # -> ctx+0x84+0x24, data copied from +0x3C
        r[0x38:0x3C] = struct.pack(">I", 0x2A)            # -> ctx+0x508
        r += ticket
        self.send(s, addr, 0x2D, bf(pw, bytes(r), True))

    # Directory objects (name -> 5 u32 fields at +0x10..+0x20 of a 0x24-byte entry).
    # obmsNN = lobby servers/areas listed by BIN_3 (index from the last 2 digits); obmft = ?
    OBJECTS = dict([("obmft", (0xF7E00002, 0, 100, 0, 0x100))] +
                   [("obms%02d" % i, (0xF7E00010 + i, 0, 100, 0, 0x100 + i)) for i in range(1, 6)] +
                   [("obmf%02d" % i, (0xF7E00020 + i, 0, 4, 0, 0x200 + i)) for i in range(1, 4)])
    ATTRS = {"USER": 1, "MAXI": 100}

    def query(self, s, addr, m, ackf):
        pl = m["payload"]
        maxn, nc = struct.unpack(">IB", pl[:5])
        conds = []
        for k in range(nc):
            e = pl[8 + 21 * k: 8 + 21 * (k + 1)]
            conds.append((e[:4].decode("latin1"), chr(e[4]), cstr(e[5:])))
        ops = {"A": lambda a, b: a == b, "D": lambda a, b: a >= b, "F": lambda a, b: a <= b,
               "B": lambda a, b: a != b, "C": lambda a, b: a > b, "E": lambda a, b: a < b}
        hits = [n for n in sorted(self.OBJECTS) if all(ops.get(op, lambda a, b: False)(n, v) for _, op, v in conds)][:maxn]
        self.p("  query max=%d %r -> %r" % (maxn, conds, hits))
        out = struct.pack(">III", 0x48, 0, len(hits))
        for n in hits:
            out += n.encode().ljust(16, b"\0") + struct.pack(">5I", *self.OBJECTS[n])
        self.send(s, addr, 0x48, out, **ackf)

    def run(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind((self.a.host, self.a.port))
        self.p("listening udp", self.a.host, self.a.port, "password", self.a.password)
        while True:
            d, addr = s.recvfrom(4096)
            self.p("C>S", addr, len(d), d.hex())
            try:
                tr, ms = split(d)
            except Exception as e:
                self.p("parse error", e)
                continue
            for m in ms:
                self.p("  msg type=0x%02x flags=%04x tag=%08x seq=%d ack=%d len=%d" % (
                    m["type"], m["flags"], m["tag"], m["seq"], m["ack"], len(m["payload"])))
                ackf = {}
                if m["flags"] & 0x8000:      # reliable: acknowledge its sequence
                    ackf = dict(flags=0x5000, ack=m["seq"])
                if m["type"] == 0x2C:
                    self.login(s, addr, m)
                elif m["type"] == 0x01:      # session connect with the ticket from 0x2D
                    self.p("  connect ticket=%r" % m["payload"][0x28:0x28 + 16])
                    # 0x29 = connect accepted: u32 new port (0 keeps), u32 (forced to 1), u32 -> ctx+0x44
                    self.send(s, addr, 0x29, struct.pack(">III", 0, 1, 0x1234), **ackf)
                elif m["type"] == 0x48:      # directory query -> 0x48 with matching objects
                    self.query(s, addr, m, ackf)
                elif m["type"] == 0x09 and len(m["payload"]) >= 8:   # get attribute -> 0x09 {OID, attr, value}
                    oid, attr = struct.unpack(">I4s", m["payload"][:8])
                    val = self.ATTRS.get(attr.decode("latin1"), 0)
                    self.p("  getattr oid=%#x %r -> %d" % (oid, attr, val))
                    self.send(s, addr, 0x09, struct.pack(">I4sI", oid, attr, val), **ackf)
                elif m["type"] == 0x06 and len(m["payload"]) >= 4:   # enter object (area) -> 0x06 (0x18 bytes)
                    oid, = struct.unpack(">I", m["payload"][:4])
                    self.p("  enter oid=%#x" % oid)
                    # a type-0x06 reply (6 x u32) blacks out the lobby (run k11): keep the generic 0x28
                    self.send(s, addr, 0x28, struct.pack(">II", 0x06, 0), **ackf)
                elif self.a.generic_ok and m["type"] not in (0x29,):
                    # 0x28 = generic reply: u32 request type, u32 status
                    self.send(s, addr, 0x28, struct.pack(">II", m["type"], 0), **ackf)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9090)
    ap.add_argument("--public-ip", default="198.18.0.10")
    ap.add_argument("--password", default="1111")
    ap.add_argument("--log")
    ap.add_argument("--generic-ok", type=int, default=1)
    Server(ap.parse_args()).run()
