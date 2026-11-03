// Fallback interpreter for EE code the static translation never reached (an entry point no exploring run
// found, code loaded from the disc at run time, a jump table nobody followed). dispatch() hands it any pc
// without translated code; it runs instruction by instruction and goes back to translated code at the
// first call, register jump or branch that lands on a translated entry. The semantics mirror the code
// recomp/codegen.cpp emits, using the same helpers, so a function behaves the same either way.
#include <vector>
#include <cstdlib>
#include "ps2/generated.hpp"
#include "ps2/system.hpp"

#include "r5900.hpp"

#include <bit>
#include <cstdio>

namespace ps2 {

bool has_code(std::uint32_t pc);

namespace {
// PS2_WATCH=<addr>,<len> (hex): logs the pc of every store the interpreter makes into that range (debugging;
// run the scene interpreted with PS2_INTERP_ALL=<frame>).
inline void watch(std::uint32_t pc, std::uint32_t a, std::uint32_t n, std::uint32_t value) {
    static std::uint32_t lo = 0, hi = 0;
    static const bool on = [] {
        const char *e = std::getenv("PS2_WATCH");
        unsigned x = 0, len = 0;
        if (!e || std::sscanf(e, "%x,%x", &x, &len) != 2) return false;
        lo = x; hi = x + len;
        return true;
    }();
    if (!on) return;
    a &= 0x1FFFFFFFu;
    if (a + n <= lo || a >= hi) return;
    static unsigned budget = 4000;
    if (budget && budget--) std::fprintf(stderr, "[watch] pc %08X store%u %08X = %08X (frame %llu)\n", pc, n * 8, a, value,
                                         static_cast<unsigned long long>(ps2::frame_count()));
}

// PS2_WATCH_LOADS=1: the same for loads (each pc once). PS2_WATCH_LOADS=<lo>,<hi> (hex) only logs loads made by
// code in [lo, hi) (one overlay, e.g. a menu, while the whole RAM is watched).
inline void watch_load(std::uint32_t pc, std::uint32_t a, std::uint32_t n) {
    static std::uint32_t lo = 0, hi = 0, pc_lo = 0, pc_hi = 0xFFFFFFFFu;
    static const bool on = [] {
        const char *e = std::getenv("PS2_WATCH"), *l = std::getenv("PS2_WATCH_LOADS");
        unsigned x = 0, len = 0, p0 = 0, p1 = 0;
        if (!e || !l || std::sscanf(e, "%x,%x", &x, &len) != 2) return false;
        lo = x; hi = x + len;
        if (std::sscanf(l, "%x,%x", &p0, &p1) == 2) pc_lo = p0, pc_hi = p1;
        return true;
    }();
    if (!on) return;
    a &= 0x1FFFFFFFu;
    if (a + n <= lo || a >= hi || pc < pc_lo || pc >= pc_hi) return;
    static std::vector<std::uint32_t> seen;
    for (std::uint32_t p : seen)
        if (p == pc) return;
    if (seen.size() > 2000) return;
    seen.push_back(pc);
    std::fprintf(stderr, "[watch] pc %08X load%u %08X (+%X) (frame %llu)\n", pc, n * 8, a, a - lo,
                 static_cast<unsigned long long>(ps2::frame_count()));
}


using recomp::Flow;
using recomp::Insn;
using enum recomp::Op;

inline std::uint32_t W(const Cpu &c, std::uint32_t r) { return r ? c.r[r].w[0] : 0u; }
inline std::uint64_t D(const Cpu &c, std::uint32_t r) { return r ? c.r[r].d[0] : 0ull; }
inline std::int64_t SD(const Cpu &c, std::uint32_t r) { return r ? c.r[r].sd[0] : 0ll; }
inline void setw(Cpu &c, std::uint32_t r, std::uint32_t v) {
    if (r) set32(c.r[r], v);
}
inline void setd(Cpu &c, std::uint32_t r, std::uint64_t v) {
    if (r) c.r[r].d[0] = v;
}
inline std::uint32_t addr(const Cpu &c, const Insn &i) { return W(c, i.rs()) + static_cast<std::uint32_t>(i.simm()); }
inline std::uint32_t bcv(const Cpu &c, const Insn &i) { return c.vu0.vf[i.ft()].w[i.bc()]; }

bool condition(Cpu &c, const Insn &i) {
    switch (i.op) {
    case Beq: case Beql: return D(c, i.rs()) == D(c, i.rt());
    case Bne: case Bnel: return D(c, i.rs()) != D(c, i.rt());
    case Blez: case Blezl: return SD(c, i.rs()) <= 0;
    case Bgtz: case Bgtzl: return SD(c, i.rs()) > 0;
    case Bltz: case Bltzl: case Bltzal: case Bltzall: return SD(c, i.rs()) < 0;
    case Bgez: case Bgezl: case Bgezal: case Bgezall: return SD(c, i.rs()) >= 0;
    case Bc0f: case Bc0fl: return !cpcond0(c);
    case Bc0t: case Bc0tl: return cpcond0(c);
    case Bc1f: case Bc1fl: return !(c.fcr31 & kFpuCondition);
    case Bc1t: case Bc1tl: return (c.fcr31 & kFpuCondition) != 0;
    case Bc2f: case Bc2fl: return !vu0_busy(c);
    case Bc2t: case Bc2tl: return vu0_busy(c);
    default: return false;
    }
}

#define MMI3(fn) \
    if (rd) mmi::fn(c.r[rd], c.r[rs], c.r[rt]); \
    break
#define MMI2(fn) \
    if (rd) mmi::fn(c.r[rd], c.r[rt]); \
    break

// Executes one instruction without control flow. Returns false when the run must stop (stop() was called).
bool simple(Cpu &c, const Insn &i) {
    const std::uint32_t rs = i.rs(), rt = i.rt(), rd = i.rd(), sa = i.sa();
    const std::uint32_t fsf = (i.word >> 21) & 3u, ftf = (i.word >> 23) & 3u;
    auto &v = c.vu0;
    Reg128 scratch;
    Reg128 &dst = rd ? c.r[rd] : scratch;
    switch (i.op) {
    case Sll: setw(c, rd, W(c, rt) << sa); break;
    case Srl: setw(c, rd, W(c, rt) >> sa); break;
    case Sra: setw(c, rd, static_cast<std::uint32_t>(static_cast<std::int32_t>(W(c, rt)) >> sa)); break;
    case Sllv: setw(c, rd, W(c, rt) << (W(c, rs) & 31u)); break;
    case Srlv: setw(c, rd, W(c, rt) >> (W(c, rs) & 31u)); break;
    case Srav: setw(c, rd, static_cast<std::uint32_t>(static_cast<std::int32_t>(W(c, rt)) >> (W(c, rs) & 31u))); break;
    case Movz: if (rd && D(c, rt) == 0) c.r[rd].d[0] = D(c, rs); break;
    case Movn: if (rd && D(c, rt) != 0) c.r[rd].d[0] = D(c, rs); break;
    case Break: breakpoint(c, i.pc); break;
    case Sync: case Cache: case Pref: break;
    case Mfhi: setd(c, rd, c.hi.d[0]); break;
    case Mflo: setd(c, rd, c.lo.d[0]); break;
    case Mfhi1: setd(c, rd, c.hi.d[1]); break;
    case Mflo1: setd(c, rd, c.lo.d[1]); break;
    case Mthi: c.hi.d[0] = D(c, rs); break;
    case Mtlo: c.lo.d[0] = D(c, rs); break;
    case Mthi1: c.hi.d[1] = D(c, rs); break;
    case Mtlo1: c.lo.d[1] = D(c, rs); break;
    case Dsllv: setd(c, rd, D(c, rt) << (W(c, rs) & 63u)); break;
    case Dsrlv: setd(c, rd, D(c, rt) >> (W(c, rs) & 63u)); break;
    case Dsrav: setd(c, rd, static_cast<std::uint64_t>(SD(c, rt) >> (W(c, rs) & 63u))); break;
    case Mult: mmi::mult(c, 0, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[0]); break;
    case Multu: mmi::multu(c, 0, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[0]); break;
    case Madd: mmi::madd(c, 0, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[0]); break;
    case Maddu: mmi::maddu(c, 0, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[0]); break;
    case Mult1: mmi::mult(c, 1, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[1]); break;
    case Multu1: mmi::multu(c, 1, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[1]); break;
    case Madd1: mmi::madd(c, 1, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[1]); break;
    case Maddu1: mmi::maddu(c, 1, W(c, rs), W(c, rt)); setd(c, rd, c.lo.d[1]); break;
    case Div: mmi::div(c, 0, W(c, rs), W(c, rt)); break;
    case Divu: mmi::divu(c, 0, W(c, rs), W(c, rt)); break;
    case Div1: mmi::div(c, 1, W(c, rs), W(c, rt)); break;
    case Divu1: mmi::divu(c, 1, W(c, rs), W(c, rt)); break;
    case Add: case Addu: setw(c, rd, W(c, rs) + W(c, rt)); break;
    case Sub: case Subu: setw(c, rd, W(c, rs) - W(c, rt)); break;
    case And: setd(c, rd, D(c, rs) & D(c, rt)); break;
    case Or: setd(c, rd, D(c, rs) | D(c, rt)); break;
    case Xor: setd(c, rd, D(c, rs) ^ D(c, rt)); break;
    case Nor: setd(c, rd, ~(D(c, rs) | D(c, rt))); break;
    case Mfsa: setd(c, rd, c.sa); break;
    case Mtsa: c.sa = W(c, rs) & 15u; break;
    case Mtsab: c.sa = (W(c, rs) ^ i.uimm()) & 15u; break;
    case Mtsah: c.sa = ((W(c, rs) ^ i.uimm()) & 7u) * 2u; break;
    case Slt: setd(c, rd, SD(c, rs) < SD(c, rt) ? 1ull : 0ull); break;
    case Sltu: setd(c, rd, D(c, rs) < D(c, rt) ? 1ull : 0ull); break;
    case Dadd: case Daddu: setd(c, rd, D(c, rs) + D(c, rt)); break;
    case Dsub: case Dsubu: setd(c, rd, D(c, rs) - D(c, rt)); break;
    case Tge: if (SD(c, rs) >= SD(c, rt)) trap(c, i.pc); break;
    case Tgeu: if (D(c, rs) >= D(c, rt)) trap(c, i.pc); break;
    case Tlt: if (SD(c, rs) < SD(c, rt)) trap(c, i.pc); break;
    case Tltu: if (D(c, rs) < D(c, rt)) trap(c, i.pc); break;
    case Teq: if (D(c, rs) == D(c, rt)) trap(c, i.pc); break;
    case Tne: if (D(c, rs) != D(c, rt)) trap(c, i.pc); break;
    case Tgei: if (SD(c, rs) >= i.simm()) trap(c, i.pc); break;
    case Tgeiu: if (D(c, rs) >= static_cast<std::uint64_t>(static_cast<std::int64_t>(i.simm()))) trap(c, i.pc); break;
    case Tlti: if (SD(c, rs) < i.simm()) trap(c, i.pc); break;
    case Tltiu: if (D(c, rs) < static_cast<std::uint64_t>(static_cast<std::int64_t>(i.simm()))) trap(c, i.pc); break;
    case Teqi: if (SD(c, rs) == i.simm()) trap(c, i.pc); break;
    case Tnei: if (SD(c, rs) != i.simm()) trap(c, i.pc); break;
    case Dsll: setd(c, rd, D(c, rt) << sa); break;
    case Dsrl: setd(c, rd, D(c, rt) >> sa); break;
    case Dsra: setd(c, rd, static_cast<std::uint64_t>(SD(c, rt) >> sa)); break;
    case Dsll32: setd(c, rd, D(c, rt) << (sa + 32)); break;
    case Dsrl32: setd(c, rd, D(c, rt) >> (sa + 32)); break;
    case Dsra32: setd(c, rd, static_cast<std::uint64_t>(SD(c, rt) >> (sa + 32))); break;
    case Addi: case Addiu: setw(c, rt, W(c, rs) + static_cast<std::uint32_t>(i.simm())); break;
    case Slti: setd(c, rt, SD(c, rs) < i.simm() ? 1ull : 0ull); break;
    case Sltiu: setd(c, rt, D(c, rs) < static_cast<std::uint64_t>(static_cast<std::int64_t>(i.simm())) ? 1ull : 0ull); break;
    case Andi: setd(c, rt, D(c, rs) & i.uimm()); break;
    case Ori: setd(c, rt, D(c, rs) | i.uimm()); break;
    case Xori: setd(c, rt, D(c, rs) ^ i.uimm()); break;
    case Lui: if (rt) c.r[rt].sd[0] = static_cast<std::int32_t>(i.uimm() << 16); break;
    case Daddi: case Daddiu: setd(c, rt, D(c, rs) + static_cast<std::uint64_t>(static_cast<std::int64_t>(i.simm()))); break;
    case Ldl: if (rt) ldl(c.r[rt], addr(c, i)); break;
    case Ldr: if (rt) ldr(c.r[rt], addr(c, i)); break;
    case Lwl: if (rt) lwl(c.r[rt], addr(c, i)); break;
    case Lwr: if (rt) lwr(c.r[rt], addr(c, i)); break;
    case Sdl: sdl(c.r[rt], addr(c, i)); break;
    case Sdr: sdr(c.r[rt], addr(c, i)); break;
    case Swl: swl(c.r[rt], addr(c, i)); break;
    case Swr: swr(c.r[rt], addr(c, i)); break;
    case Lq: watch_load(i.pc, addr(c, i) & ~15u, 16); ld128(addr(c, i) & ~15u, rt ? c.r[rt] : scratch); break;
    case Sq: watch(i.pc, addr(c, i) & ~15u, 16, c.r[rt].w[0]); st128(addr(c, i) & ~15u, c.r[rt]); break;
    case Lb: { watch_load(i.pc, addr(c, i), 1); const auto x = ld8(addr(c, i)); if (rt) c.r[rt].sd[0] = static_cast<std::int8_t>(x); break; }
    case Lbu: { watch_load(i.pc, addr(c, i), 1); const auto x = ld8(addr(c, i)); if (rt) c.r[rt].d[0] = x; break; }
    case Lh: { watch_load(i.pc, addr(c, i), 2); const auto x = ld16(addr(c, i)); if (rt) c.r[rt].sd[0] = static_cast<std::int16_t>(x); break; }
    case Lhu: { watch_load(i.pc, addr(c, i), 2); const auto x = ld16(addr(c, i)); if (rt) c.r[rt].d[0] = x; break; }
    case Lw: { watch_load(i.pc, addr(c, i), 4); const auto x = ld32(addr(c, i)); if (rt) set32(c.r[rt], x); break; }
    case Lwu: { const auto x = ld32(addr(c, i)); if (rt) c.r[rt].d[0] = x; break; }
    case Ld: { watch_load(i.pc, addr(c, i), 8); const auto x = ld64(addr(c, i)); if (rt) c.r[rt].d[0] = x; break; }
    case Sb: watch(i.pc, addr(c, i), 1, W(c, rt)); st8(addr(c, i), static_cast<std::uint8_t>(W(c, rt))); break;
    case Sh: watch(i.pc, addr(c, i), 2, W(c, rt)); st16(addr(c, i), static_cast<std::uint16_t>(W(c, rt))); break;
    case Sw: watch(i.pc, addr(c, i), 4, W(c, rt)); st32(addr(c, i), W(c, rt)); break;
    case Sd: watch(i.pc, addr(c, i), 8, W(c, rt)); st64(addr(c, i), D(c, rt)); break;
    case Lwc1: c.f[i.ft()] = ld32(addr(c, i)); break;
    case Swc1: st32(addr(c, i), c.f[i.ft()]); break;
    case Lqc2: ld128(addr(c, i) & ~15u, i.ft() ? v.vf[i.ft()] : scratch); break;
    case Sqc2: st128(addr(c, i) & ~15u, v.vf[i.ft()]); break;

    case Plzcw: if (rd) mmi::plzcw(c.r[rd], c.r[rs]); break;
    case Pmfhl: if (rd) mmi::pmfhl(c, c.r[rd], sa); break;
    case Pmthl: mmi::pmthl(c, c.r[rs]); break;
    case Psllh: if (rd) mmi::psllh(c.r[rd], c.r[rt], sa); break;
    case Psrlh: if (rd) mmi::psrlh(c.r[rd], c.r[rt], sa); break;
    case Psrah: if (rd) mmi::psrah(c.r[rd], c.r[rt], sa); break;
    case Psllw: if (rd) mmi::psllw(c.r[rd], c.r[rt], sa); break;
    case Psrlw: if (rd) mmi::psrlw(c.r[rd], c.r[rt], sa); break;
    case Psraw: if (rd) mmi::psraw(c.r[rd], c.r[rt], sa); break;
    case Paddw: MMI3(paddw);
    case Psubw: MMI3(psubw);
    case Pcgtw: MMI3(pcgtw);
    case Pmaxw: MMI3(pmaxw);
    case Paddh: MMI3(paddh);
    case Psubh: MMI3(psubh);
    case Pcgth: MMI3(pcgth);
    case Pmaxh: MMI3(pmaxh);
    case Paddb: MMI3(paddb);
    case Psubb: MMI3(psubb);
    case Pcgtb: MMI3(pcgtb);
    case Paddsw: MMI3(paddsw);
    case Psubsw: MMI3(psubsw);
    case Pextlw: MMI3(pextlw);
    case Ppacw: MMI3(ppacw);
    case Paddsh: MMI3(paddsh);
    case Psubsh: MMI3(psubsh);
    case Pextlh: MMI3(pextlh);
    case Ppach: MMI3(ppach);
    case Paddsb: MMI3(paddsb);
    case Psubsb: MMI3(psubsb);
    case Pextlb: MMI3(pextlb);
    case Ppacb: MMI3(ppacb);
    case Pext5: MMI2(pext5);
    case Ppac5: MMI2(ppac5);
    case Pabsw: MMI2(pabsw);
    case Pceqw: MMI3(pceqw);
    case Pminw: MMI3(pminw);
    case Padsbh: MMI3(padsbh);
    case Pabsh: MMI2(pabsh);
    case Pceqh: MMI3(pceqh);
    case Pminh: MMI3(pminh);
    case Pceqb: MMI3(pceqb);
    case Padduw: MMI3(padduw);
    case Psubuw: MMI3(psubuw);
    case Pextuw: MMI3(pextuw);
    case Padduh: MMI3(padduh);
    case Psubuh: MMI3(psubuh);
    case Pextuh: MMI3(pextuh);
    case Paddub: MMI3(paddub);
    case Psubub: MMI3(psubub);
    case Pextub: MMI3(pextub);
    case Qfsrv: if (rd) mmi::qfsrv(c.r[rd], c.r[rs], c.r[rt], c.sa); break;
    case Psllvw: MMI3(psllvw);
    case Psrlvw: MMI3(psrlvw);
    case Psravw: MMI3(psravw);
    case Pmaddw: case Pmsubw: mmi::pmaddw(c, dst, c.r[rs], c.r[rt], i.op == Pmsubw); break;
    case Pmadduw: mmi::pmadduw(c, dst, c.r[rs], c.r[rt]); break;
    case Pmultw: mmi::pmultw(c, dst, c.r[rs], c.r[rt]); break;
    case Pmultuw: mmi::pmultuw(c, dst, c.r[rs], c.r[rt]); break;
    case Pmulth: case Pmaddh: case Pmsubh: mmi::pmulth(c, dst, c.r[rs], c.r[rt], i.op == Pmulth ? 0 : i.op == Pmaddh ? 1 : 2); break;
    case Phmadh: case Phmsbh: mmi::phmadh(c, dst, c.r[rs], c.r[rt], i.op == Phmsbh); break;
    case Pdivw: mmi::pdivw(c, c.r[rs], c.r[rt]); break;
    case Pdivuw: mmi::pdivuw(c, c.r[rs], c.r[rt]); break;
    case Pdivbw: mmi::pdivbw(c, c.r[rs], c.r[rt]); break;
    case Pmfhi: if (rd) c.r[rd] = c.hi; break;
    case Pmflo: if (rd) c.r[rd] = c.lo; break;
    case Pmthi: c.hi = c.r[rs]; break;
    case Pmtlo: c.lo = c.r[rs]; break;
    case Pinth: MMI3(pinth);
    case Pinteh: MMI3(pinteh);
    case Pcpyld: MMI3(pcpyld);
    case Pcpyud: MMI3(pcpyud);
    case Pand: MMI3(pand);
    case Pxor: MMI3(pxor);
    case Por: MMI3(por);
    case Pnor: MMI3(pnor);
    case Pexeh: MMI2(pexeh);
    case Prevh: MMI2(prevh);
    case Pexew: MMI2(pexew);
    case Prot3w: MMI2(prot3w);
    case Pexch: MMI2(pexch);
    case Pcpyh: MMI2(pcpyh);
    case Pexcw: MMI2(pexcw);

    case Mfc0: setw(c, rt, mfc0(c, rd, i.word & 7u)); break;
    case Mtc0: mtc0(c, rd, W(c, rt)); break;
    case Ei: set_interrupts(c, true); break;
    case Di: set_interrupts(c, false); break;

    case Mfc1: setw(c, rt, c.f[i.fs()]); break;
    case Mtc1: c.f[i.fs()] = W(c, rt); break;
    case Cfc1: setw(c, rt, cfc1(c, i.fs())); break;
    case Ctc1: ctc1(c, i.fs(), W(c, rt)); break;
    case AddS: c.f[i.fd()] = fp::add(c.f[i.fs()], c.f[i.ft()]); break;
    case SubS: c.f[i.fd()] = fp::sub(c.f[i.fs()], c.f[i.ft()]); break;
    case MulS: c.f[i.fd()] = fp::mul(c.f[i.fs()], c.f[i.ft()]); break;
    case DivS: fpu_div(c, i.fd(), c.f[i.fs()], c.f[i.ft()]); break;
    case SqrtS: c.f[i.fd()] = fp::sqrt(c.f[i.ft()]); break;
    case AbsS: c.f[i.fd()] = c.f[i.fs()] & fp::kAbs; break;
    case MovS: c.f[i.fd()] = c.f[i.fs()]; break;
    case NegS: c.f[i.fd()] = c.f[i.fs()] ^ fp::kSign; break;
    case RsqrtS: fpu_rsqrt(c, i.fd(), c.f[i.fs()], c.f[i.ft()]); break;
    case AddaS: c.facc = fp::add(c.f[i.fs()], c.f[i.ft()]); break;
    case SubaS: c.facc = fp::sub(c.f[i.fs()], c.f[i.ft()]); break;
    case MulaS: c.facc = fp::mul(c.f[i.fs()], c.f[i.ft()]); break;
    case MaddS: c.f[i.fd()] = fp::add(c.facc, fp::mul(c.f[i.fs()], c.f[i.ft()])); break;
    case MsubS: c.f[i.fd()] = fp::sub(c.facc, fp::mul(c.f[i.fs()], c.f[i.ft()])); break;
    case MaddaS: c.facc = fp::add(c.facc, fp::mul(c.f[i.fs()], c.f[i.ft()])); break;
    case MsubaS: c.facc = fp::sub(c.facc, fp::mul(c.f[i.fs()], c.f[i.ft()])); break;
    case CvtWS: c.f[i.fd()] = fp::cvt_w_s(c.f[i.fs()]); break;
    case CvtSW: c.f[i.fd()] = fp::cvt_s_w(c.f[i.fs()]); break;
    case MaxS: c.f[i.fd()] = fp::max(c.f[i.fs()], c.f[i.ft()]); break;
    case MinS: c.f[i.fd()] = fp::min(c.f[i.fs()], c.f[i.ft()]); break;
    case CF: fpu_compare(c, false); break;
    case CEq: fpu_compare(c, fp::key(c.f[i.fs()]) == fp::key(c.f[i.ft()])); break;
    case CLt: fpu_compare(c, fp::key(c.f[i.fs()]) < fp::key(c.f[i.ft()])); break;
    case CLe: fpu_compare(c, fp::key(c.f[i.fs()]) <= fp::key(c.f[i.ft()])); break;

    case Qmfc2:
        if (i.word & 1u) vu0_wait(c);
        if (rt) c.r[rt] = v.vf[i.fs()];
        break;
    case Qmtc2:
        if (i.word & 1u) vu0_wait(c);
        if (i.fs()) v.vf[i.fs()] = c.r[rt];
        break;
    case Cfc2: setw(c, rt, i.fs() < 16 ? v.vi[i.fs()] : cfc2(c, i.fs())); break;
    case Ctc2:
        if (i.fs() < 16) vu::set_vi(v, i.fs(), W(c, rt));
        else ctc2(c, i.fs(), W(c, rt));
        break;
    case Vaddbc: vu::addbc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vsubbc: vu::subbc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vmaddbc: vu::maddbc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vmsubbc: vu::msubbc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vmaxbc: vu::maxbc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vminibc: vu::minibc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vmulbc: vu::mulbc(v, i.dest(), i.fd(), i.fs(), bcv(c, i)); break;
    case Vmulq: vu::mulbc(v, i.dest(), i.fd(), i.fs(), v.q); break;
    case Vmaxi: vu::maxbc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vmuli: vu::mulbc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vminii: vu::minibc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vaddq: vu::addbc(v, i.dest(), i.fd(), i.fs(), v.q); break;
    case Vmaddq: vu::maddbc(v, i.dest(), i.fd(), i.fs(), v.q); break;
    case Vaddi: vu::addbc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vmaddi: vu::maddbc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vsubq: vu::subbc(v, i.dest(), i.fd(), i.fs(), v.q); break;
    case Vmsubq: vu::msubbc(v, i.dest(), i.fd(), i.fs(), v.q); break;
    case Vsubi: vu::subbc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vmsubi: vu::msubbc(v, i.dest(), i.fd(), i.fs(), v.i); break;
    case Vadd: vu::add(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vmadd: vu::madd(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vmul: vu::mul(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vmax: vu::max(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vsub: vu::sub(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vmsub: vu::msub(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vmini: vu::mini(v, i.dest(), i.fd(), i.fs(), i.ft()); break;
    case Vopmsub: vu::opmsub(v, i.fd(), i.fs(), i.ft()); break;
    case Viadd: vu::set_vi(v, i.fd(), v.vi[i.fs()] + v.vi[i.ft()]); break;
    case Visub: vu::set_vi(v, i.fd(), v.vi[i.fs()] - v.vi[i.ft()]); break;
    case Viaddi: vu::set_vi(v, i.ft(), v.vi[i.fs()] + i.imm5()); break;
    case Viand: vu::set_vi(v, i.fd(), v.vi[i.fs()] & v.vi[i.ft()]); break;
    case Vior: vu::set_vi(v, i.fd(), v.vi[i.fs()] | v.vi[i.ft()]); break;
    case Vcallms: vcallms(c, i.imm15() * 8u); break;
    case Vcallmsr: vcallms(c, v.cmsar0 * 8u); break;
    case Vaddabc: vu::addabc(v, i.dest(), i.fs(), bcv(c, i)); break;
    case Vsubabc: vu::subabc(v, i.dest(), i.fs(), bcv(c, i)); break;
    case Vmaddabc: vu::maddabc(v, i.dest(), i.fs(), bcv(c, i)); break;
    case Vmsubabc: vu::msubabc(v, i.dest(), i.fs(), bcv(c, i)); break;
    case Vmulabc: vu::mulabc(v, i.dest(), i.fs(), bcv(c, i)); break;
    case Vmulaq: vu::mulabc(v, i.dest(), i.fs(), v.q); break;
    case Vmulai: vu::mulabc(v, i.dest(), i.fs(), v.i); break;
    case Vaddaq: vu::addabc(v, i.dest(), i.fs(), v.q); break;
    case Vmaddaq: vu::maddabc(v, i.dest(), i.fs(), v.q); break;
    case Vaddai: vu::addabc(v, i.dest(), i.fs(), v.i); break;
    case Vmaddai: vu::maddabc(v, i.dest(), i.fs(), v.i); break;
    case Vsubaq: vu::subabc(v, i.dest(), i.fs(), v.q); break;
    case Vmsubaq: vu::msubabc(v, i.dest(), i.fs(), v.q); break;
    case Vsubai: vu::subabc(v, i.dest(), i.fs(), v.i); break;
    case Vmsubai: vu::msubabc(v, i.dest(), i.fs(), v.i); break;
    case Vadda: vu::adda(v, i.dest(), i.fs(), i.ft()); break;
    case Vmadda: vu::madda(v, i.dest(), i.fs(), i.ft()); break;
    case Vmula: vu::mula(v, i.dest(), i.fs(), i.ft()); break;
    case Vsuba: vu::suba(v, i.dest(), i.fs(), i.ft()); break;
    case Vmsuba: vu::msuba(v, i.dest(), i.fs(), i.ft()); break;
    case Vopmula: vu::opmula(v, i.fs(), i.ft()); break;
    case Vitof0: vu::itof(v, i.dest(), i.ft(), i.fs(), 0); break;
    case Vitof4: vu::itof(v, i.dest(), i.ft(), i.fs(), 4); break;
    case Vitof12: vu::itof(v, i.dest(), i.ft(), i.fs(), 12); break;
    case Vitof15: vu::itof(v, i.dest(), i.ft(), i.fs(), 15); break;
    case Vftoi0: vu::ftoi(v, i.dest(), i.ft(), i.fs(), 0); break;
    case Vftoi4: vu::ftoi(v, i.dest(), i.ft(), i.fs(), 4); break;
    case Vftoi12: vu::ftoi(v, i.dest(), i.ft(), i.fs(), 12); break;
    case Vftoi15: vu::ftoi(v, i.dest(), i.ft(), i.fs(), 15); break;
    case Vabs: vu::abs(v, i.dest(), i.ft(), i.fs()); break;
    case Vmove: vu::move(v, i.dest(), i.ft(), i.fs()); break;
    case Vmr32: vu::mr32(v, i.dest(), i.ft(), i.fs()); break;
    case Vclip: vu::clip(v, i.fs(), i.ft()); break;
    case Vnop: case Vwaitq: break;
    case Vlqi: lqc2_vu(c, i.dest(), i.ft(), v.vi[i.fs()]); vu::set_vi(v, i.fs(), v.vi[i.fs()] + 1u); break;
    case Vsqi: sqc2_vu(c, i.dest(), i.fs(), v.vi[i.ft()]); vu::set_vi(v, i.ft(), v.vi[i.ft()] + 1u); break;
    case Vlqd: vu::set_vi(v, i.fs(), v.vi[i.fs()] - 1u); lqc2_vu(c, i.dest(), i.ft(), v.vi[i.fs()]); break;
    case Vsqd: vu::set_vi(v, i.ft(), v.vi[i.ft()] - 1u); sqc2_vu(c, i.dest(), i.fs(), v.vi[i.ft()]); break;
    case Vdiv: vu::div(v, v.vf[i.fs()].w[fsf], v.vf[i.ft()].w[ftf]); break;
    case Vsqrt: vu::sqrt(v, v.vf[i.ft()].w[ftf]); break;
    case Vrsqrt: vu::rsqrt(v, v.vf[i.fs()].w[fsf], v.vf[i.ft()].w[ftf]); break;
    case Vmtir: vu::set_vi(v, i.ft(), v.vf[i.fs()].w[fsf]); break;
    case Vmfir: vu::mfir(v, i.dest(), i.ft(), i.fs()); break;
    case Vilwr: {
        std::uint16_t x;
        __builtin_memcpy(&x, vu0_data() + vu0_addr(v.vi[i.fs()]) + 4u * static_cast<unsigned>(std::countl_zero(i.dest() << 28)), 2);
        vu::set_vi(v, i.ft(), x);
        break;
    }
    case Viswr:
        for (int k = 0; k < 4; ++k)
            if (vu::lane(i.dest(), k)) {
                const std::uint32_t x = v.vi[i.ft()];
                __builtin_memcpy(vu0_data() + vu0_addr(v.vi[i.fs()]) + 4u * k, &x, 4);
            }
        break;
    case Vrnext:
        v.r = vu::rand_next(v.r);
        vu::apply(v, i.dest(), i.ft(), [&](int) { return v.r | fp::kOne; });
        break;
    case Vrget: vu::apply(v, i.dest(), i.ft(), [&](int) { return v.r | fp::kOne; }); break;
    case Vrinit: v.r = v.vf[i.fs()].w[fsf] & 0x7FFFFFu; break;
    case Vrxor: v.r = (v.r ^ v.vf[i.fs()].w[fsf]) & 0x7FFFFFu; break;

    default:
        stop(c, i.pc, recomp::name(i.op));
        return false;
    }
    return true;
}

#undef MMI3
#undef MMI2

}

// Entered from dispatch() with c.pc at code that has no translation. Returns the way translated code does:
// with c.pc at the return address after a `jr ra`, or with kUnwind set to stop.
void interpret(Cpu &c, std::uint32_t, std::uint32_t depth) {
    for (;;) {
        const std::uint32_t pc = c.pc;
        if (pc & 3u) {
            stop(c, pc & ~3u, "misaligned pc");
            return;
        }
        const Insn i = recomp::decode(ld32(pc), pc);
        const Flow fl = recomp::flow(i.op);
        c.budget -= 1;
        if (fl == Flow::None) {
            if (i.op == Syscall) {
                c.pc = pc + 4u;
                syscall(c, pc);
                if (c.pc != pc + 4u) return;
                continue;
            }
            if (!simple(c, i)) return;
            c.pc = pc + 4u;
            continue;
        }
        if (i.op == Eret) {
            c.pc = eret(c);
            [[clang::musttail]] return dispatch(c, 0u, depth);
        }
        if (fl == Flow::Stop) {
            stop(c, pc, recomp::name(i.op));
            return;
        }
        const Insn ds = recomp::decode(ld32(pc + 4u), pc + 4u);
        if (ds.op == Invalid || recomp::has_delay_slot(ds.op) || ds.op == Syscall) {
            stop(c, pc + 4u, "unsupported delay slot");
            return;
        }
        c.budget -= 1;
        const std::uint32_t ret = pc + 8u;
        const auto link = [&](std::uint32_t r) {
            if (r) c.r[r].sd[0] = static_cast<std::int32_t>(ret);
        };
        // A call returns here with c.pc at the return address unless something unwinds (or it came back to an
        // address with no code: bad_return, dispatch.cpp).
        const auto invoke = [&](std::uint32_t t) {
            call(c, t, depth + 1u);
            return c.pc == ret || bad_return(c, ret);
        };
        bool taken = false;
        std::uint32_t t = i.branch_target_of();
        switch (i.op) {
        case Jal:
            link(31);
            if (!simple(c, ds)) return;
            if (!invoke(t)) return;
            c.pc = ret;
            continue;
        case Bltzal: case Bgezal:
            taken = condition(c, i);
            link(31);
            if (!simple(c, ds)) return;
            if (taken && !invoke(t)) return;
            c.pc = ret;
            continue;
        case Bltzall: case Bgezall:
            if (condition(c, i)) {
                link(31);
                if (!simple(c, ds)) return;
                if (!invoke(t)) return;
            }
            c.pc = ret;
            continue;
        default:
            break;
        }
        switch (fl) {
        case Flow::Branch:
            taken = condition(c, i);
            if (!simple(c, ds)) return;
            break;
        case Flow::BranchLikely:
            taken = condition(c, i);
            if (taken && !simple(c, ds)) return;
            break;
        case Flow::Jump:
            taken = true;
            if (!simple(c, ds)) return;
            break;
        case Flow::JumpRegister:
            t = W(c, i.rs());
            if (!simple(c, ds)) return;
            c.pc = t;
            if (i.rs() == 31) return;
            // As translated code does: dispatch() knows the special addresses (kIdle...) and comes back here
            // if the target has no translation either.
            [[clang::musttail]] return dispatch(c, 0u, depth);
        case Flow::CallRegister:
            t = W(c, i.rs());
            link(i.rd());
            if (!simple(c, ds)) return;
            c.pc = t;
            if (!invoke(t)) return;
            c.pc = ret;
            continue;
        default:
            stop(c, pc, "unexpected control flow");
            return;
        }
        if (!taken) {
            c.pc = ret;
            continue;
        }
        if (t <= pc && c.budget < 0 && preempt(c)) {
            c.pc = t | kUnwind;
            return;
        }
        c.pc = t;
        if (has_code(t)) [[clang::musttail]] return dispatch(c, 0u, depth);
    }
}

}
