#!/usr/bin/env python3
# reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
# (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.
"""Prototype of the US (SLUS-20765) "app01" service on TCP 10127, used to learn the protocol.

Framing, from the client in SLUS_207.65 (see docs/notes/protocol.md, "Cliente US"):
  header 8 bytes:  len16(BE, payload only)  qsw  cmd16(BE)  pid8  err8  0xFF
  qsw: 0x01 QUERY, 0x02 TELL (reply, copies the QUERY's pid), 0x10 BROADCAST
  strings: len16 (= n+2)  sum16  n bytes, each byte  b ^ key[i&7] ^ (seed + pid + i)
           key = base64decode("TUlOQU1JT0g=") = b"MINAMIOH", seed = u16 sent in QUERY 0x1002,
           sum = (sum(plain) + pid*257) & 0x7FFF   (the client checks it)
The server speaks first. Every frame is logged in hex with the header parsed.
Usage: usapp.py [--port 10127] [--log FILE] [--script NAME]
"""
import argparse, socket, struct, threading, time, datetime, base64, sys

KEY = base64.b64decode("TUlOQU1JT0g=")
QUERY, TELL, BCAST = 0x01, 0x02, 0x10
SEED = 0x0000

def crypt(data, pid, seed):
    return bytes(b ^ KEY[i & 7] ^ ((seed + pid + i) & 0xFF) for i, b in enumerate(data))

def checksum(plain, pid):
    return (sum(plain) + pid * 257) & 0x7FFF

def enc_string(s, pid, seed):
    return struct.pack(">HH", len(s) + 2, checksum(s, pid)) + crypt(s, pid, seed)

def dec_strings(payload, pid, seed, start=0):
    out, o = [], start
    while o + 4 <= len(payload):
        n, s = struct.unpack_from(">HH", payload, o)
        if n < 2 or o + 2 + n > len(payload): break
        plain = crypt(payload[o + 4:o + 2 + n], pid, seed)
        out.append((plain, s == checksum(plain, pid)))
        o += 2 + n
    return out, o

class Log:
    def __init__(self, path):
        self.f = open(path, "a") if path else None
    def __call__(self, msg):
        line = "%s %s" % (datetime.datetime.now().isoformat(timespec="milliseconds"), msg)
        print(line, flush=True)
        if self.f:
            self.f.write(line + "\n"); self.f.flush()

def hexdump(b):
    rows = []
    for o in range(0, len(b), 16):
        c = b[o:o + 16]
        rows.append("  %04x: %-48s |%s|" % (o, " ".join("%02x" % x for x in c), "".join(chr(x) if 32 <= x < 127 else "." for x in c)))
    return "\n".join(rows)

class Client:
    def __init__(self, sock, peer, log):
        self.s, self.peer, self.log = sock, peer, log
        self.pid = 0
        self.seed = SEED
        self.buf = b""

    def send(self, qsw, cmd, payload=b"", pid=None, err=0):
        if pid is None:
            self.pid = (self.pid + 1) & 0xFF
            pid = self.pid
        frame = struct.pack(">HBHBBB", len(payload), qsw, cmd, pid, err, 0xFF) + payload
        self.log("S>C %s qsw=%02x cmd=%04x pid=%02x err=%02x len=%d\n%s" % (self.peer, qsw, cmd, pid, err, len(payload), hexdump(frame)))
        self.s.sendall(frame)

    def frames(self):
        while True:
            while len(self.buf) >= 8:
                n = struct.unpack_from(">H", self.buf)[0]
                if len(self.buf) < 8 + n: break
                f, self.buf = self.buf[:8 + n], self.buf[8 + n:]
                yield f
            d = self.s.recv(65536)
            if not d:
                return
            self.buf += d

    def run(self):
        self.send(QUERY, 0x1001)
        for f in self.frames():
            n, qsw, cmd, pid, err, ff = struct.unpack_from(">HBHBBB", f)
            p = f[8:]
            self.log("C>S %s qsw=%02x cmd=%04x pid=%02x err=%02x ff=%02x len=%d\n%s" % (self.peer, qsw, cmd, pid, err, ff, n, hexdump(f)))
            strs, _ = dec_strings(p, pid, self.seed)
            if strs:
                self.log("    strings: %s" % ", ".join("%r%s" % (s, "" if ok else " (bad sum)") for s, ok in strs))
            self.handle(qsw, cmd, pid, p)

    def handle(self, qsw, cmd, pid, p):
        if qsw == TELL and cmd == 0x1001:
            self.send(QUERY, 0x1002, struct.pack(">H", self.seed))
        elif qsw == TELL and cmd == 0x1002:
            # u8 0 = no file to download (client: 0x1f50f0 -> minor state 0xc -> QUERY 0x6101)
            self.send(BCAST, 0x6001, b"\x00")
        elif qsw == QUERY and cmd == 0x6101:
            # Query: u32 (0 so far), string filename (e.g. "01/TOP_INFOR.HTM").
            # Tell (0x1f60b0): u8 found (0 -> client error -0x3e), u32 ?, string filename (must match), u32 size.
            #   size 0 -> done; else the client fetches chunks with QUERY 0x6102.
            strs, _ = dec_strings(p, pid, self.seed, 4)
            name = strs[0][0] if strs else b""
            data = FILES.get(name.decode("latin1"), b"")
            self.file = (name, data)
            self.send(TELL, 0x6101, b"\x01" + struct.pack(">I", 0) + enc_string(name, pid, self.seed)
                      + struct.pack(">I", len(data)), pid=pid)
        elif qsw == QUERY and cmd == 0x6102:
            # Tell (0x1f6420): string filename, u32 offset (must equal the client's), string chunk.
            # Chunk size: 0x2fe - (len(name) + 12); the last one is size - offset.
            name, data = self.file
            strs, o = dec_strings(p, pid, self.seed)
            off = struct.unpack_from(">I", p, o)[0] if o + 4 <= len(p) else 0
            n = 0x2FE - (len(name) + 12)
            chunk = data[off:off + n]
            self.send(TELL, 0x6102, enc_string(name, pid, self.seed) + struct.pack(">I", off)
                      + enc_string(chunk, pid, self.seed), pid=pid)
        elif qsw == QUERY and cmd == 0x1004:
            # Empty query; the TELL handler (0x1f5030) just returns 1 (next state).
            self.send(TELL, 0x1004, b"", pid=pid)
        elif qsw == QUERY:
            # Unknown so far: answer every client QUERY with an empty TELL, err 0, and see what comes next.
            self.send(TELL, cmd, b"", pid=pid)

FILES = {
    "01/TOP_INFOR.HTM": b"<HTML><BODY>reo-web test server<BR>Welcome to the Raccoon City network.</BODY></HTML>",
}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=10127)
    ap.add_argument("--log", default="")
    a = ap.parse_args()
    log = Log(a.log)
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((a.bind, a.port)); srv.listen(8)
    log("usapp listening on %s:%d" % (a.bind, a.port))
    while True:
        c, addr = srv.accept()
        log("connect %s:%d" % addr)
        def go(c=c, addr=addr):
            try:
                Client(c, "%s:%d" % addr, log).run()
            except Exception as e:
                log("error %r" % e)
            finally:
                c.close(); log("closed %s:%d" % addr)
        threading.Thread(target=go, daemon=True).start()

if __name__ == "__main__":
    main()
