#!/usr/bin/env python3
"""Make the PS2 network configuration ("Your Network Configuration") that
NETCNF.IRX reads from the memory card, and put it on a .ps2 card image.

The memory card layout (mc0:/BWNETCNF/):
  BWNETCNF     index, plain text: "<type>,<flag>,<file>,<display name>" per line
               type 0 = combination (net), 1 = ISP/interface (ifc), 2 = device (dev)
  net000.cnf   combination, plain text: interface "<name>" "<ifc file>" "<dev file>"
  ifc000.dat   interface settings (type eth, dhcp ...), obfuscated
  dev000.dat   device settings (vendor/product of the adapter), obfuscated
  icon.sys, SYS_NET.ICO   the save icon (taken from the game disc, NTGUI2/CONF/<lang>/)

The .dat obfuscation (read from NETCNF.IRX v1.20 on the Outbreak disc): every
16-bit little-endian word w is stored as rotl16(w, k) ^ 0xFFFF (a trailing odd
byte as rotl8(b, k) ^ 0xFF), where k cycles through a 24-entry table built from
the console's 8-byte i.LINK ID (sceCdRI): for each ID byte b,
(b >> 5) + 1, ((b >> 2) & 7) + 1, (b & 3) + 1. The runtime's sceCdRI returns
an all-zero ID, so every k is 1 there. Use --ilink-id for a real console.

Card image: raw 8 MiB card with 16 spare bytes per 512-byte page (ECC), the
format ps2-web-recomp's --memcard and PCSX2 use.

usage:
  ps2netcnf.py files OUTDIR [--static IP/PREFIX,GATEWAY,DNS] [--icons DIR]
  ps2netcnf.py install CARD.ps2 [--static ...] [--icons DIR]   (formats the card if needed)
  ps2netcnf.py ls CARD.ps2 [PATH]
  ps2netcnf.py cat CARD.ps2 PATH [--decode]
  ps2netcnf.py decode FILE.dat
"""
import argparse
import os
import struct
import sys
import time

HEADER = "# <Sony Computer Entertainment Inc.>\n\n"
DIR = "BWNETCNF"

# ---------------------------------------------------------------------------
# configuration text


def ilink_shifts(ilink_id: bytes):
    table = []
    for b in ilink_id[:8].ljust(8, b"\0"):
        table += [(b >> 5) + 1, ((b >> 2) & 7) + 1, (b & 3) + 1]
    return table


def rotl(v, n, bits):
    n %= bits
    mask = (1 << bits) - 1
    return ((v << n) | (v >> (bits - n))) & mask


def encode(data: bytes, ilink_id: bytes = b"\0" * 8) -> bytes:
    k = ilink_shifts(ilink_id)
    out = bytearray()
    i = 0
    for pos in range(0, len(data) - 1, 2):
        (w,) = struct.unpack_from("<H", data, pos)
        out += struct.pack("<H", rotl(w, k[i], 16) ^ 0xFFFF)
        i = (i + 1) % 24
    if len(data) & 1:
        out.append(rotl(data[-1], k[i], 8) ^ 0xFF)
    return bytes(out)


def decode(data: bytes, ilink_id: bytes = b"\0" * 8) -> bytes:
    k = ilink_shifts(ilink_id)
    out = bytearray()
    i = 0
    for pos in range(0, len(data) - 1, 2):
        (w,) = struct.unpack_from("<H", data, pos)
        out += struct.pack("<H", rotl(w ^ 0xFFFF, 16 - k[i] % 16, 16))
        i = (i + 1) % 24
    if len(data) & 1:
        out.append(rotl(data[-1] ^ 0xFF, 8 - k[i] % 8, 8))
    return bytes(out)


def config_files(static=None, ilink_id=b"\0" * 8, icons=None):
    """Return {name: bytes} for the BWNETCNF directory."""
    ifc = HEADER + "type eth\n"
    if static:
        cidr, gateway, dns = static
        address, prefix = cidr.split("/")
        mask = (0xFFFFFFFF << (32 - int(prefix))) & 0xFFFFFFFF
        netmask = ".".join(str((mask >> s) & 255) for s in (24, 16, 8, 0))
        ifc += ('-dhcp\naddress "%s"\nnetmask "%s"\n' % (address, netmask) +
                "route add -net 0.0.0.0 gw %s netmask 0.0.0.0\nnameserver add %s\n" % (gateway, dns))
    else:
        ifc += "dhcp\n"
    # vendor/product must match what SMAP.IRX registers with the stack.
    dev = HEADER + 'type eth\nvendor "SCE"\nproduct "Ethernet (Network Adaptor)"\nphy_config auto\n'
    net = HEADER + 'interface "Combination1" "ifc000.dat" "dev000.dat"\n'
    index = ("0,1,net000.cnf,Combination1\n"
             "1,1,ifc000.dat,REO gateway (%s)\n"
             "2,1,dev000.dat,Network Adaptor (Ethernet)\n") % ("static" if static else "DHCP")
    files = {
        "BWNETCNF": index.encode(),
        "net000.cnf": net.encode(),
        "ifc000.dat": encode(ifc.encode(), ilink_id),
        "dev000.dat": encode(dev.encode(), ilink_id),
    }
    if icons:
        for disc_name, card_name in (("ICON.SYS", "icon.sys"), ("SYS_NET.ICO", "SYS_NET.ICO")):
            path = os.path.join(icons, disc_name)
            if os.path.exists(path):
                files[card_name] = open(path, "rb").read()
            else:
                print("warning: %s not found, the card will show no icon" % path, file=sys.stderr)
    return files

# ---------------------------------------------------------------------------
# memory card file system

PAGE = 512
SPARE = 16
RAW_PAGE = PAGE + SPARE
PAGES = 16384
PAGES_PER_CLUSTER = 2
CLUSTER = PAGE * PAGES_PER_CLUSTER
PAGES_PER_BLOCK = 16
CLUSTERS = PAGES // PAGES_PER_CLUSTER  # 8192
ALLOC_OFFSET = 41
ALLOC_END = 8135
IFC_CLUSTER = 8
FAT_FREE = 0x7FFFFFFF
FAT_END = 0xFFFFFFFF
FAT_USED = 0x80000000
CARD_FLAGS = 0x2B  # what ps2-web-recomp's card reports: ECC, bad-block table

DF_READ, DF_WRITE, DF_EXECUTE, DF_PROTECTED = 0x01, 0x02, 0x04, 0x08
DF_FILE, DF_DIR, DF_0080, DF_0400, DF_HIDDEN, DF_EXISTS = 0x10, 0x20, 0x80, 0x400, 0x2000, 0x8000
DF_RWX = DF_READ | DF_WRITE | DF_EXECUTE
DIR_MODE = DF_EXISTS | DF_0400 | DF_DIR | DF_RWX                 # 0x8427
FILE_MODE = DF_EXISTS | DF_0400 | DF_0080 | DF_FILE | DF_RWX     # 0x8497
DOTDOT_ROOT_MODE = DF_EXISTS | DF_HIDDEN | DF_0400 | DF_DIR | DF_WRITE | DF_EXECUTE  # 0xA426

DIRENT = struct.Struct("<HHI8sII8sI28s32s416x")


def _parity(b):
    return bin(b).count("1") & 1


# Hamming code over 128 bytes, 3 bytes per chunk (bit 3 of the column parity is unused).
_COLUMN_MASKS = (0x55, 0x33, 0x0F, 0x00, 0xAA, 0xCC, 0xF0)
_ECC_TABLE = [sum(_parity(b & m) << i for i, m in enumerate(_COLUMN_MASKS)) | (_parity(b) << 7) for b in range(256)]


def ecc128(chunk: bytes) -> bytes:
    col = line0 = line1 = 0
    for i, b in enumerate(chunk):
        v = _ECC_TABLE[b]
        col ^= v
        if v & 0x80:
            line0 ^= ~i
            line1 ^= i
    return bytes((~col & 0x77, ~line0 & 0x7F, ~line1 & 0x7F))


def spare_for(page: bytes) -> bytes:
    return b"".join(ecc128(page[i:i + 128]) for i in range(0, PAGE, 128)) + b"\0" * 4


def stamp(t=None):
    t = time.gmtime(t)
    # The card stores Japan time; the exact value does not matter to NETCNF.
    return struct.pack("<xBBBBBH", t.tm_sec, t.tm_min, t.tm_hour, t.tm_mday, t.tm_mon, t.tm_year)


class Card:
    def __init__(self, raw: bytes = None):
        if raw is None or len(raw) < RAW_PAGE * PAGES:
            raw = b"\xFF" * (RAW_PAGE * PAGES)
        self.pages = [bytearray(raw[i * RAW_PAGE:i * RAW_PAGE + PAGE]) for i in range(PAGES)]

    # raw access ------------------------------------------------------------
    def image(self) -> bytes:
        out = bytearray()
        for p in self.pages:
            out += p
            out += spare_for(bytes(p))
        return bytes(out)

    def cluster(self, n) -> bytearray:
        return self.pages[n * 2] + self.pages[n * 2 + 1]

    def set_cluster(self, n, data: bytes):
        data = bytes(data).ljust(CLUSTER, b"\xFF")
        self.pages[n * 2][:] = data[:PAGE]
        self.pages[n * 2 + 1][:] = data[PAGE:]

    def formatted(self) -> bool:
        return bytes(self.pages[0][:28]) == b"Sony PS2 Memory Card Format "

    # format ----------------------------------------------------------------
    def format(self):
        for p in self.pages:
            p[:] = b"\xFF" * PAGE
        sb = bytearray(b"\xFF" * PAGE)
        fat_clusters = (ALLOC_END + 255) // 256  # 32
        ifc = [IFC_CLUSTER] + [0] * 31
        bad = [0xFFFFFFFF] * 32
        struct.pack_into("<28s12sHHHHIIIIII8x32I32IBB", sb, 0,
                         b"Sony PS2 Memory Card Format ", b"1.2.0.0".ljust(12, b"\0"),
                         PAGE, PAGES_PER_CLUSTER, PAGES_PER_BLOCK, 0xFF00,
                         CLUSTERS, ALLOC_OFFSET, ALLOC_END, 0, 1023, 1022, *ifc, *bad, 2, CARD_FLAGS)
        self.pages[0][:] = sb
        indirect = struct.pack("<%dI" % fat_clusters, *range(IFC_CLUSTER + 1, IFC_CLUSTER + 1 + fat_clusters))
        self.set_cluster(IFC_CLUSTER, indirect.ljust(CLUSTER, b"\0"))
        for i in range(fat_clusters):
            entries = [FAT_FREE if i * 256 + j < ALLOC_END else FAT_END for j in range(256)]
            self.set_cluster(IFC_CLUSTER + 1 + i, struct.pack("<256I", *entries))
        now = stamp()
        root = DIRENT.pack(DIR_MODE, 0, 2, now, 0, 0, now, 0, b"", b".") + \
            DIRENT.pack(DOTDOT_ROOT_MODE, 0, 0, now, 0, 0, now, 0, b"", b"..")
        self.fat_set(0, FAT_END)
        self.set_cluster(ALLOC_OFFSET, root)

    # FAT -------------------------------------------------------------------
    def fat_get(self, n):
        fc, idx = divmod(n, 256)
        (fat_cluster,) = struct.unpack_from("<I", self.cluster(IFC_CLUSTER), fc * 4)
        (v,) = struct.unpack_from("<I", self.cluster(fat_cluster), idx * 4)
        return v

    def fat_set(self, n, value):
        fc, idx = divmod(n, 256)
        (fat_cluster,) = struct.unpack_from("<I", self.cluster(IFC_CLUSTER), fc * 4)
        c = self.cluster(fat_cluster)
        struct.pack_into("<I", c, idx * 4, value)
        self.set_cluster(fat_cluster, c)

    def allocate(self, count):
        chain = []
        for n in range(ALLOC_END):
            if self.fat_get(n) == FAT_FREE:
                chain.append(n)
                if len(chain) == count:
                    break
        if len(chain) < count:
            raise SystemExit("memory card full")
        for a, b in zip(chain, chain[1:] + [None]):
            self.fat_set(a, FAT_END if b is None else (FAT_USED | b))
        return chain

    def chain(self, first):
        out = []
        n = first
        while n != FAT_END and len(out) < ALLOC_END:
            out.append(n)
            v = self.fat_get(n)
            if v == FAT_END or not (v & FAT_USED):
                break
            n = v & 0x7FFFFFFF
        return out

    def read_chain(self, first, length):
        data = b"".join(bytes(self.cluster(ALLOC_OFFSET + n)) for n in self.chain(first))
        return data[:length]

    def write_chain(self, data: bytes):
        count = max(1, (len(data) + CLUSTER - 1) // CLUSTER)
        clusters = self.allocate(count)
        for i, n in enumerate(clusters):
            self.set_cluster(ALLOC_OFFSET + n, data[i * CLUSTER:(i + 1) * CLUSTER])
        return clusters[0]

    # directories -----------------------------------------------------------
    def entries(self, first, count=None):
        if count is None:  # only the root keeps its entry count in "."
            (count,) = struct.unpack_from("<I", self.cluster(ALLOC_OFFSET + first), 4)
        raw = self.read_chain(first, count * 512)
        return [list(DIRENT.unpack_from(raw, i * 512)) for i in range(count)]

    def write_entries(self, first, ents):
        clusters = self.chain(first)
        need = (len(ents) + 1) // 2
        if need > len(clusters):
            extra = self.allocate(need - len(clusters))
            self.fat_set(clusters[-1], FAT_USED | extra[0])
            clusters += extra
        raw = b"".join(DIRENT.pack(*e) for e in ents)
        for i, n in enumerate(clusters):
            self.set_cluster(ALLOC_OFFSET + n, raw[i * CLUSTER:(i + 1) * CLUSTER])

    def lookup(self, path):
        first, count = 0, None
        ent = None
        for part in [p for p in path.split("/") if p]:
            for e in self.entries(first, count):
                if e[9].rstrip(b"\0").decode("latin1") == part and e[0] & DF_EXISTS:
                    ent = e
                    first, count = e[4], e[2]
                    break
            else:
                return None
        return ent

    def mkdir(self, name, protected=False):
        root = self.entries(0)
        for e in root:
            if e[9].rstrip(b"\0") == name.encode() and e[0] & DF_EXISTS:
                raise SystemExit("mc0:/%s already exists, remove it first" % name)
        now = stamp()
        index = len(root)
        mode = DIR_MODE | (DF_PROTECTED if protected else 0)
        sub = [[DIR_MODE, 0, 0, now, 0, index, now, 0, b"", b"."],
               [DIR_MODE, 0, 0, now, 0, 0, now, 0, b"", b".."]]
        first = self.write_chain(b"".join(DIRENT.pack(*e) for e in sub))
        root.append([mode, 0, 2, now, first, 0, now, 0, b"", name.encode()])
        root[0][2] = len(root)
        self.write_entries(0, root)
        return first, index

    def add_file(self, dir_first, dir_index, name, data: bytes):
        ents = self.entries(dir_first, self.entries(0)[dir_index][2])
        now = stamp()
        first = self.write_chain(data) if data else 0xFFFFFFFF
        ents.append([FILE_MODE, 0, len(data), now, first, 0, now, 0, b"", name.encode()])
        self.write_entries(dir_first, ents)
        # The parent's entry for this directory carries its entry count.
        root = self.entries(0)
        root[dir_index][2] = len(ents)
        self.write_entries(0, root)

    def check_ecc(self, raw: bytes):
        bad = 0
        for i in range(PAGES):
            page = raw[i * RAW_PAGE:i * RAW_PAGE + PAGE]
            if raw[i * RAW_PAGE + PAGE:i * RAW_PAGE + PAGE + 12] != spare_for(page)[:12]:
                bad += 1
        return bad


def install(card_path, files):
    raw = open(card_path, "rb").read() if os.path.exists(card_path) else None
    card = Card(raw)
    if not card.formatted():
        print("formatting %s" % card_path)
        card.format()
    first, index = card.mkdir(DIR, protected=True)
    order = ["icon.sys", "SYS_NET.ICO", "BWNETCNF", "net000.cnf", "ifc000.dat", "dev000.dat"]
    for name in sorted(files, key=lambda n: order.index(n) if n in order else 99):
        card.add_file(first, index, name, files[name])
    open(card_path, "wb").write(card.image())


def list_dir(card, path):
    ent = card.lookup(path) if path.strip("/") else None
    for e in (card.entries(ent[4], ent[2]) if ent else card.entries(0)):
        mode, _, length, _, cluster, parent, _, _, _, name = e
        print("%04x %8d  cl %-5d %s" % (mode, length, cluster if cluster != 0xFFFFFFFF else -1, name.rstrip(b"\0").decode("latin1")))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("files", "install"):
        p = sub.add_parser(name)
        p.add_argument("target")
        p.add_argument("--static", help="IP/PREFIX,GATEWAY,DNS instead of DHCP, e.g. 10.0.2.15/24,10.0.2.2,10.0.2.2")
        p.add_argument("--icons", help="folder with ICON.SYS and SYS_NET.ICO (the disc's NTGUI2/CONF/ENGLISH)")
        p.add_argument("--ilink-id", default="0000000000000000", help="console i.LINK ID in hex (runtime: zeros)")
    p = sub.add_parser("ls")
    p.add_argument("card")
    p.add_argument("path", nargs="?", default="/")
    p = sub.add_parser("cat")
    p.add_argument("card")
    p.add_argument("path")
    p.add_argument("--decode", action="store_true")
    p.add_argument("--ilink-id", default="0000000000000000")
    p = sub.add_parser("decode")
    p.add_argument("file")
    p.add_argument("--ilink-id", default="0000000000000000")
    a = ap.parse_args()

    if a.cmd in ("files", "install"):
        static = tuple(a.static.split(",")) if a.static else None
        files = config_files(static, bytes.fromhex(a.ilink_id), a.icons)
        if a.cmd == "files":
            os.makedirs(a.target, exist_ok=True)
            for name, data in files.items():
                open(os.path.join(a.target, name), "wb").write(data)
            print("wrote %s: %s" % (a.target, ", ".join(sorted(files))))
        else:
            install(a.target, files)
            print("installed mc0:/%s on %s" % (DIR, a.target))
    elif a.cmd == "ls":
        raw = open(a.card, "rb").read()
        card = Card(raw)
        if not card.formatted():
            raise SystemExit("not a formatted card")
        bad = card.check_ecc(raw)
        list_dir(card, a.path)
        print("(%d pages with a wrong ECC)" % bad)
    elif a.cmd == "cat":
        card = Card(open(a.card, "rb").read())
        e = card.lookup(a.path)
        if not e:
            raise SystemExit("no such file")
        data = card.read_chain(e[4], e[2])
        sys.stdout.buffer.write(decode(data, bytes.fromhex(a.ilink_id)) if a.decode else data)
    elif a.cmd == "decode":
        sys.stdout.buffer.write(decode(open(a.file, "rb").read(), bytes.fromhex(a.ilink_id)))


if __name__ == "__main__":
    main()
