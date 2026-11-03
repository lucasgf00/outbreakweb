#!/usr/bin/env python3
"""Build a tiny test IRX that reads files through ioman, to check guest file
system drivers (MCMAN's "mc" device) under build/*/iopnet.

  python3 scripts/iop_mctest.py out.irx [--netcnf] mc0:/BWNETCNF/BWNETCNF [more paths]...

For each path the module opens it, reads up to 1 KiB, prints the byte count and
the text, and closes it; then it lists mc0:/BWNETCNF with dopen/dread. With
--netcnf it also asks NETCNF.IRX (load it first) for the entry "Combination1"
and prints what it parsed. The code
is position independent (it finds its own address with bal), so the IRX needs
no relocations, only import stubs that the loader links.
"""
import struct
import sys

ZERO, V0, A0, A1, A2, A3, S0, S1, S2, SP, RA = 0, 2, 4, 5, 6, 7, 16, 17, 18, 29, 31


def i_type(op, rs, rt, imm):
    return (op << 26) | (rs << 21) | (rt << 16) | (imm & 0xFFFF)


class Asm:
    def __init__(self):
        self.words = []
        self.fixups = []  # (index, label) for branches
        self.labels = {}

    def here(self):
        return len(self.words) * 4

    def label(self, name):
        self.labels[name] = self.here()

    def emit(self, w):
        self.words.append(w & 0xFFFFFFFF)

    def addiu(self, rt, rs, imm): self.emit(i_type(0x09, rs, rt, imm))
    def lw(self, rt, off, base): self.emit(i_type(0x23, base, rt, off))
    def sw(self, rt, off, base): self.emit(i_type(0x2B, base, rt, off))
    def move(self, rd, rs): self.emit((rs << 21) | (rd << 11) | 0x21)  # addu rd, rs, zero
    def nop(self): self.emit(0)
    def jr(self, rs): self.emit((rs << 21) | 0x08)

    def bal(self, target):  # bgezal $zero, target
        self.fixups.append((len(self.words), target, "bal"))
        self.emit(i_type(0x01, 0, 0x11, 0))

    def bltz(self, rs, target):
        self.fixups.append((len(self.words), target, "bltz"))
        self.emit(i_type(0x01, rs, 0x00, 0))

    def b(self, target):  # beq zero, zero
        self.fixups.append((len(self.words), target, "b"))
        self.emit(i_type(0x04, 0, 0, 0))

    def addr(self, rt, label):  # rt = s0 + (label - base)
        self.fixups.append((len(self.words), label, "addr"))
        self.emit(i_type(0x09, S0, rt, 0))

    def finish(self):
        for idx, label, kind in self.fixups:
            target = self.labels[label]
            if kind == "addr":
                self.words[idx] |= (target - self.labels["base"]) & 0xFFFF
            else:
                self.words[idx] |= ((target - (idx * 4 + 4)) >> 2) & 0xFFFF
        return struct.pack("<%dI" % len(self.words), *self.words)


def build(paths, listing="mc0:/BWNETCNF", netcnf=False):
    a = Asm()
    a.addiu(SP, SP, -64)
    a.sw(RA, 60, SP)
    a.sw(S0, 56, SP)
    a.sw(S1, 52, SP)
    a.sw(S2, 48, SP)
    a.bal("base")
    a.nop()
    a.label("base")
    a.move(S0, RA)
    for i, _ in enumerate(paths):
        a.addr(A0, "path%d" % i)
        a.addiu(A1, ZERO, 1)            # O_RDONLY
        a.bal("open"); a.nop()
        a.move(S1, V0)
        a.addr(A0, "fmt_open"); a.addr(A1, "path%d" % i); a.move(A2, S1)
        a.bal("printf"); a.nop()
        a.bltz(S1, "next%d" % i); a.nop()
        a.move(A0, S1); a.addr(A1, "buffer"); a.addiu(A2, ZERO, 1024)
        a.bal("read"); a.nop()
        a.move(S2, V0)
        a.bltz(S2, "print%d" % i); a.nop()
        a.addr(8, "buffer")
        a.emit((8 << 21) | (S2 << 16) | (8 << 11) | 0x21)  # addu t0, t0, s2
        a.emit(i_type(0x28, 8, ZERO, 0))                   # sb zero, 0(t0)
        a.label("print%d" % i)
        a.addr(A0, "fmt_read"); a.move(A1, S2); a.addr(A2, "buffer")
        a.bal("printf"); a.nop()
        a.move(A0, S1)
        a.bal("close"); a.nop()
        a.label("next%d" % i)
    # directory listing
    a.addr(A0, "dir")
    a.bal("dopen"); a.nop()
    a.move(S1, V0)
    a.addr(A0, "fmt_dopen"); a.move(A1, S1)
    a.bal("printf"); a.nop()
    a.bltz(S1, "done"); a.nop()
    a.label("loop")
    a.move(A0, S1); a.addr(A1, "dirent")
    a.bal("dread"); a.nop()
    a.move(S2, V0)
    a.bltz(S2, "closedir"); a.nop()
    a.emit(i_type(0x04, S2, ZERO, 0)); a.fixups.append((len(a.words) - 1, "closedir", "b"))  # beqz s2
    a.nop()
    # iox_dirent_t: iox_stat_t (mode, attr, size, ctime[8], atime[8], mtime[8], hisize, private[6]) = 64 bytes, then name
    a.addr(A0, "fmt_dirent"); a.addr(A1, "dirent_name"); a.addr(A3, "dirent"); a.lw(A2, 8, A3); a.lw(A3, 0, A3)
    a.bal("printf"); a.nop()
    a.b("loop"); a.nop()
    a.label("closedir")
    a.move(A0, S1)
    a.bal("dclose"); a.nop()
    a.label("done")
    if netcnf:
        # env = sceNetCnfEnv_t (2160 bytes): mem_base/mem_ptr at +8/+12, mem_last at +16, f_verbose at +40
        a.addr(A3, "env"); a.addr(A2, "heap")
        a.sw(A2, 8, A3); a.sw(A2, 12, A3)
        a.addiu(A2, A2, 0x3FFC); a.addiu(A2, A2, 4); a.sw(A2, 16, A3)
        a.addiu(A2, ZERO, 1); a.sw(A2, 40, A3)
        a.addr(A0, "mc"); a.move(A1, ZERO); a.addr(A2, "combination")
        a.bal("LoadEntry"); a.nop()
        a.move(S1, V0)
        a.addr(A3, "env")
        a.addr(A0, "fmt_load"); a.move(A1, S1); a.lw(A2, 44, A3); a.lw(A3, 52, A3)
        a.bal("printf"); a.nop()
        a.bltz(S1, "end"); a.nop()
        a.addr(A3, "env"); a.lw(S1, 24, A3)      # root
        a.lw(S1, 0, S1)                           # root->pair_head
        a.addr(A0, "fmt_pair"); a.lw(A1, 8, S1); a.lw(A2, 12, S1); a.lw(A3, 16, S1)
        a.bal("printf"); a.nop()
        a.lw(S2, 20, S1)                          # pair->ifc
        a.addr(A0, "fmt_ifc"); a.lw(A1, 0, S2); a.emit(i_type(0x24, S2, A2, 16))  # lbu a2, 16(s2)
        a.bal("printf"); a.nop()
        a.lw(S2, 24, S1)                          # pair->dev
        a.addr(A0, "fmt_dev"); a.lw(A1, 0, S2); a.lw(A2, 4, S2); a.lw(A3, 8, S2)
        a.bal("printf"); a.nop()
        a.label("end")
    a.lw(RA, 60, SP)
    a.lw(S0, 56, SP)
    a.lw(S1, 52, SP)
    a.lw(S2, 48, SP)
    a.addiu(V0, ZERO, 1)  # MODULE_NO_RESIDENT_END
    a.jr(RA)
    a.addiu(SP, SP, 64)

    def imports(lib, version, funcs):
        a.emit(0x41E00000); a.emit(0); a.emit(version)
        name = lib.encode().ljust(8, b"\0")
        a.emit(struct.unpack_from("<I", name, 0)[0]); a.emit(struct.unpack_from("<I", name, 4)[0])
        for label, index in funcs:
            a.label(label)
            a.jr(RA)
            a.addiu(ZERO, ZERO, index)
        a.emit(0); a.emit(0)

    imports("ioman", 0x0102, [("open", 4), ("close", 5), ("read", 6), ("dopen", 13), ("dclose", 14), ("dread", 15)])
    imports("stdio", 0x0102, [("printf", 4)])
    if netcnf:
        imports("netcnf", 0x0101, [("LoadEntry", 6)])

    def data(label, raw):
        a.label(label)
        raw = raw + b"\0" * (-len(raw) % 4)
        for k in range(0, len(raw), 4):
            a.emit(struct.unpack_from("<I", raw, k)[0])

    for i, p in enumerate(paths):
        data("path%d" % i, p.encode() + b"\0")
    data("dir", listing.encode() + b"\0")
    data("fmt_open", b"mctest: open %s -> %d\n\0")
    data("fmt_read", b"mctest: read %d bytes:\n%s\n\0")
    data("fmt_dopen", b"mctest: dopen -> %d\n\0")
    data("fmt_dirent", b"mctest:   %-16s %6d bytes mode 0x%04x\n\0")
    data("dirent", b"\0" * 64)
    data("dirent_name", b"\0" * 256)
    data("buffer", b"\0" * 1028)
    if netcnf:
        data("mc", b"mc0:\0")
        data("combination", b"Combination1\0")
        data("fmt_load", b"mctest: sceNetCnfLoadEntry -> %d (file_err %d, syntax_err %d)\n\0")
        data("fmt_pair", b"mctest:   interface \"%s\" ifc \"%s\" dev \"%s\"\n\0")
        data("fmt_ifc", b"mctest:   ifc type %d dhcp %d\n\0")
        data("fmt_dev", b"mctest:   dev type %d vendor \"%s\" product \"%s\"\n\0")
        data("env", b"\0" * 2160)
        data("heap", b"\0" * 0x4000)
    return a.finish()


def irx(code, name=b"mctest"):
    iopmod = struct.pack("<IIIIIIH", 0xFFFFFFFF, 0, 0, len(code), 0, 0, 0x0101) + name + b"\0"
    iopmod += b"\0" * (-len(iopmod) % 4)
    ehdr_size, phdr_size = 52, 32
    iopmod_off = ehdr_size + 2 * phdr_size
    code_off = (iopmod_off + len(iopmod) + 15) & ~15
    ehdr = struct.pack("<16sHHIIIIIHHHHHH", b"\x7fELF\x01\x01\x01" + b"\0" * 9, 0xFF80, 8, 1, 0, ehdr_size, 0, 0,
                       ehdr_size, phdr_size, 2, 40, 0, 0)
    ph_iopmod = struct.pack("<8I", 0x70000080, iopmod_off, 0, 0, len(iopmod), 0, 4, 4)
    ph_load = struct.pack("<8I", 1, code_off, 0, 0, len(code), len(code), 7, 16)
    out = bytearray(ehdr + ph_iopmod + ph_load + iopmod)
    out += b"\0" * (code_off - len(out))
    out += code
    return bytes(out)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    args = sys.argv[2:]
    netcnf = "--netcnf" in args
    args = [x for x in args if x != "--netcnf"]
    open(sys.argv[1], "wb").write(irx(build(args, netcnf=netcnf)))
