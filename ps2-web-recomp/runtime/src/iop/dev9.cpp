// DEV9 / SPEED / SMAP emulation. Register map and behaviour follow the ps2sdk
// headers (speedregs.h, smapregs.h) and what Sony's drivers expect; written for
// this runtime from those descriptions (PCSX2's DEV9 plugin was read as a
// behavioural reference only).
//
// Physical address map (IOP side):
//   0x1F801460-0x1F80147F  DEV9 bridge registers (16-bit), 0x146E = revision
//   0x10000000-0x100000FF  SPEED: revision/capabilities, interrupt status/mask,
//                          DMA control, PIO port wired to the 93C46 EEPROM
//   0x10000100-0x10004FFF  SMAP: FIFOs (0x1000..0x1308), EMAC3 (0x2000..0x206F),
//                          TX/RX buffer descriptors (0x3000..0x33FF)
// Interrupts go out on IOP line 13, data moves through PIO or IOP DMA channel 8.

#include "dev9.hpp"

#include "hw.hpp"
#include "iop.hpp"
#include "kernel.hpp"

#include "../net/backend.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <deque>
#include <memory>
#include <string>
#include <vector>

namespace ps2::iop::dev9 {

namespace {

// DEV9 bridge
constexpr std::uint32_t kDev9Base = 0x1F801460u, kDev9End = 0x1F801480u;
constexpr std::uint32_t kDev9Rev = 0x0E; // offset of 0x1F80146E
constexpr std::uint16_t kExpansionBay = 0x32;

// SPEED
constexpr std::uint32_t kSpeedBase = 0x10000000u, kSpeedEnd = 0x10010000u;
constexpr std::uint32_t kSpdRev = 0x00, kSpdRev1 = 0x02, kSpdCaps = 0x04, kSpd0e = 0x0E, kSpdId = 0x20;
constexpr std::uint32_t kSpdDmaCtrl = 0x24, kSpdIntrStat = 0x28, kSpdIntrMask = 0x2A;
constexpr std::uint32_t kSpdPioData = 0x2E;
constexpr std::uint16_t kSpeedRevision = 0x11; // ES2, newer than 0x11 changes the SMAP init path
constexpr std::uint16_t kCapsSmap = 1u << 0;

// SPEED interrupt causes raised by SMAP
constexpr std::uint16_t kIntrTxDnv = 1u << 2, kIntrRxDnv = 1u << 3, kIntrTxEnd = 1u << 4, kIntrRxEnd = 1u << 5;

// SMAP
constexpr std::uint32_t kBdMode = 0x102, kIntrClr = 0x128;
constexpr std::uint32_t kTxCtrl = 0x1000, kTxWrPtr = 0x1004, kTxSize = 0x1008, kTxFrameCnt = 0x100C, kTxFrameInc = 0x1010;
constexpr std::uint32_t kRxCtrl = 0x1030, kRxRdPtr = 0x1034, kRxSize = 0x1038, kRxFrameCnt = 0x103C, kRxFrameDec = 0x1040;
constexpr std::uint32_t kTxData = 0x1100, kRxData = 0x1200;
constexpr std::uint32_t kEmacBase = 0x2000, kEmacEnd = 0x2070;
constexpr std::uint32_t kBdTx = 0x3000, kBdRx = 0x3200, kBdEnd = 0x3400;
constexpr std::uint8_t kFifoReset = 1u << 0, kFifoDmaEn = 1u << 1;
constexpr std::uint32_t kFifoSize = 16384, kFifoMask = kFifoSize - 1;
constexpr unsigned kBdCount = 64;
constexpr std::uint16_t kBdTxReady = 0x8000, kBdRxEmpty = 0x8000;

// EMAC3 registers (offsets from kEmacBase), 32-bit, high half at the lower address
constexpr std::uint32_t kE3Mode0 = 0x00, kE3Mode1 = 0x04, kE3TxMode0 = 0x08, kE3IntrStat = 0x14,
                        kE3AddrHi = 0x1C, kE3AddrLo = 0x20, kE3StaCtrl = 0x5C;
constexpr std::uint32_t kE3RxIdle = 1u << 31, kE3TxIdle = 1u << 30, kE3SoftReset = 1u << 29, kE3RxEnable = 1u << 27;
constexpr std::uint32_t kE3InternalLoopback = 1u << 30; // MODE1
constexpr std::uint32_t kE3TxGnp0 = 1u << 31;
constexpr std::uint32_t kStaOpDone = 1u << 15, kStaRead = 1u << 12, kStaWrite = 2u << 12;

// PHY (National Semiconductor DP83846A)
constexpr unsigned kBmcr = 0x00, kBmsr = 0x01, kIdr1 = 0x02, kIdr2 = 0x03, kAnar = 0x04, kAnlpar = 0x05, kAner = 0x06,
                   kPhySts = 0x10;
constexpr std::uint16_t kBmcrReset = 1u << 15, kBmcrLoopback = 1u << 14, kBmcrRestartAn = 1u << 9;
constexpr std::uint16_t kBmsrLink = 1u << 2, kBmsrAnDone = 1u << 5;

struct Eeprom {
    enum Phase { kIdle, kOpcode, kAddress, kRead, kIgnore } phase = kIdle;
    bool cs = false, clock = false, out = false;
    unsigned bits = 0, opcode = 0, address = 0, bit = 0;
};

struct State {
    bool enabled = false;
    bool polling = false;
    net::Backend *backend = nullptr;
    std::uint8_t mac[6]{};
    std::uint16_t eeprom[64]{};
    Eeprom rom;

    std::uint16_t bridge[16]{};
    std::uint16_t speed[0x80]{};
    std::uint16_t cause = 0, mask = 0;

    bool bd_swap = false;
    std::uint16_t bd[(kBdEnd - kBdTx) / 2]{}; // driver's view, swap undone
    std::uint8_t tx_fifo[kFifoSize]{}, rx_fifo[kFifoSize]{};
    std::uint8_t tx_ctrl = 0, rx_ctrl = 0;
    std::uint32_t tx_wr = 0, rx_wr = 0, rx_rd = 0;
    std::uint8_t tx_frames = 0, rx_frames = 0;
    unsigned tx_bd = 0, rx_bd = 0;
    std::uint16_t emac[(kEmacEnd - kEmacBase) / 2]{};
    std::uint16_t phy[32]{};
    std::deque<std::vector<std::uint8_t>> pending; // received, waiting for a free RX descriptor

    std::FILE *pcap = nullptr;
    std::uint64_t sent = 0, received = 0, dropped = 0;
    bool link_reported = false;
};

State g;
// The connection and the capture outlive IOP reboots (the game reboots the IOP
// with its own module set during start-up), like a cable stays plugged in.
std::unique_ptr<net::Backend> g_backend;
std::FILE *g_pcap = nullptr;

void log_line(const char *fmt, auto... args) {
    std::fprintf(stderr, "[dev9] ");
    std::fprintf(stderr, fmt, args...);
    std::fputc('\n', stderr);
}

// ---- pcap ----------------------------------------------------------------

void pcap_open(const std::string &path) {
    g_pcap = std::fopen(path.c_str(), "wb");
    if (!g_pcap) {
        log_line("cannot write %s", path.c_str());
        return;
    }
    const std::uint32_t header[6] = {0xA1B2C3D4u, 0x00040002u, 0, 0, 65535, 1};
    std::fwrite(header, sizeof header, 1, g_pcap);
}

void pcap_write(const std::uint8_t *frame, std::size_t size) {
    if (!g.pcap) return;
    const std::uint64_t t = kernel::now();
    const std::uint64_t usec = t * 1000000ull / 36864000ull; // IOP clock, not wall time
    const std::uint32_t record[4] = {static_cast<std::uint32_t>(usec / 1000000u), static_cast<std::uint32_t>(usec % 1000000u),
                                     static_cast<std::uint32_t>(size), static_cast<std::uint32_t>(size)};
    std::fwrite(record, sizeof record, 1, g.pcap);
    std::fwrite(frame, size, 1, g.pcap);
    std::fflush(g.pcap);
}

std::string summary(const std::uint8_t *f, std::size_t n) {
    char buf[160];
    if (n < 14) return "runt";
    const unsigned type = (f[12] << 8) | f[13];
    if (type == 0x0806 && n >= 42) {
        std::snprintf(buf, sizeof buf, "ARP %s %u.%u.%u.%u -> %u.%u.%u.%u", f[21] == 1 ? "who-has" : "reply", f[28], f[29], f[30], f[31],
                      f[38], f[39], f[40], f[41]);
    } else if (type == 0x0800 && n >= 34) {
        const std::uint8_t *ip = f + 14;
        const unsigned ihl = (ip[0] & 15u) * 4u, proto = ip[9];
        const char *name = proto == 6 ? "TCP" : proto == 17 ? "UDP" : proto == 1 ? "ICMP" : "IP";
        unsigned sport = 0, dport = 0;
        if ((proto == 6 || proto == 17) && n >= 14 + ihl + 4) {
            sport = (ip[ihl] << 8) | ip[ihl + 1];
            dport = (ip[ihl + 2] << 8) | ip[ihl + 3];
        }
        std::snprintf(buf, sizeof buf, "%s %u.%u.%u.%u:%u -> %u.%u.%u.%u:%u", name, ip[12], ip[13], ip[14], ip[15], sport, ip[16], ip[17],
                      ip[18], ip[19], dport);
    } else {
        std::snprintf(buf, sizeof buf, "type 0x%04X", type);
    }
    return std::string(buf) + " (" + std::to_string(n) + " bytes)";
}

// ---- interrupts ----------------------------------------------------------

void update_irq() {
    if (g.cause & g.mask) hw::raise_interrupt(kIrqLine);
}

void raise(std::uint16_t bits) {
    g.cause |= bits;
    update_irq();
}

// ---- EEPROM (93C46, 64 x 16 bits, Microwire) -------------------------------

void build_eeprom() {
    std::memset(g.eeprom, 0, sizeof g.eeprom);
    std::uint16_t sum = 0;
    for (int i = 0; i < 3; ++i) {
        g.eeprom[i] = static_cast<std::uint16_t>(g.mac[i * 2] | (g.mac[i * 2 + 1] << 8));
        sum = static_cast<std::uint16_t>(sum + g.eeprom[i]);
    }
    g.eeprom[3] = sum; // SMAP.IRX refuses the address unless this matches
}

// PIO bits: 7 chip select, 6 clock, 5 data to the EEPROM, 4 data from it.
void eeprom_pins(std::uint8_t v) {
    Eeprom &e = g.rom;
    const bool cs = v & 0x80, clock = v & 0x40, din = v & 0x20;
    if (!cs) {
        e = Eeprom{};
        return;
    }
    const bool rising = clock && !e.clock;
    e.cs = true;
    e.clock = clock;
    if (!rising) return;
    switch (e.phase) {
    case Eeprom::kIdle:
        if (din) { // start bit
            e.phase = Eeprom::kOpcode;
            e.bits = e.opcode = 0;
        }
        break;
    case Eeprom::kOpcode:
        e.opcode = (e.opcode << 1) | din;
        if (++e.bits == 2) {
            e.phase = Eeprom::kAddress;
            e.bits = e.address = 0;
        }
        break;
    case Eeprom::kAddress:
        e.address = (e.address << 1) | din;
        if (++e.bits == 6) {
            if (e.opcode == 2) { // READ: a dummy 0, then the words MSB first
                e.phase = Eeprom::kRead;
                e.out = false;
                e.bit = 16;
            } else {
                e.phase = Eeprom::kIgnore; // erase/write/enable: the contents are fixed
            }
        }
        break;
    case Eeprom::kRead:
        if (e.bit == 0) {
            e.address = (e.address + 1) & 63u;
            e.bit = 16;
        }
        --e.bit;
        e.out = (g.eeprom[e.address] >> e.bit) & 1u;
        break;
    case Eeprom::kIgnore:
        break;
    }
}

// ---- PHY -----------------------------------------------------------------

void phy_reset() {
    std::memset(g.phy, 0, sizeof g.phy);
    g.phy[kBmcr] = 0x3100;  // 100 Mb/s, autonegotiation on, full duplex
    g.phy[kBmsr] = 0x7809;  // 100TX FD/HD, 10T FD/HD, can autonegotiate, extended registers
    g.phy[kIdr1] = 0x2000;  // National Semiconductor OUI
    g.phy[kIdr2] = 0x5C21;  // DP83846A, revision 1
    g.phy[kAnar] = 0x05E1;
    g.phy[kAnlpar] = 0x45E1; // the switch on the other end offers everything, acknowledged
    g.phy[kAner] = 0x0001;
}

std::uint16_t phy_read(unsigned reg) {
    std::uint16_t v = g.phy[reg & 31];
    switch (reg) {
    case kBmsr: v |= kBmsrLink | kBmsrAnDone; break;
    case kPhySts: v = 0x0015; break; // link, full duplex, 100 Mb/s, autonegotiation complete
    default: break;
    }
    return v;
}

void phy_write(unsigned reg, std::uint16_t v) {
    if (reg == kBmcr) {
        if (v & kBmcrReset) {
            phy_reset();
            return;
        }
        v &= static_cast<std::uint16_t>(~kBmcrRestartAn);
    }
    g.phy[reg & 31] = v;
}

// ---- EMAC3 ---------------------------------------------------------------

std::uint32_t emac_get(std::uint32_t reg) { return (static_cast<std::uint32_t>(g.emac[reg / 2]) << 16) | g.emac[reg / 2 + 1]; }

void emac_set(std::uint32_t reg, std::uint32_t v) {
    g.emac[reg / 2] = static_cast<std::uint16_t>(v >> 16);
    g.emac[reg / 2 + 1] = static_cast<std::uint16_t>(v);
}

bool loopback() { return (g.phy[kBmcr] & kBmcrLoopback) || (emac_get(kE3Mode1) & kE3InternalLoopback); }

void deliver_rx();
void transmit();

// Called once both halves of an EMAC3 register have been written.
void emac_commit(std::uint32_t reg) {
    std::uint32_t v = emac_get(reg);
    switch (reg) {
    case kE3Mode0:
        if (v & kE3SoftReset) {
            std::memset(g.emac, 0, sizeof g.emac);
            v = 0;
        }
        v = (v & ~kE3SoftReset) | kE3TxIdle | kE3RxIdle;
        emac_set(reg, v);
        if (v & kE3RxEnable) deliver_rx();
        return;
    case kE3TxMode0:
        emac_set(reg, v & ~kE3TxGnp0);
        if (v & kE3TxGnp0) transmit();
        return;
    case kE3IntrStat:
        emac_set(reg, 0); // write one to clear; no EMAC3 error is ever raised
        return;
    case kE3AddrLo: {
        const std::uint32_t hi = emac_get(kE3AddrHi);
        log_line("SMAP station address %02x:%02x:%02x:%02x:%02x:%02x", (hi >> 8) & 0xFF, hi & 0xFF, v >> 24, (v >> 16) & 0xFF, (v >> 8) & 0xFF,
                 v & 0xFF);
        return;
    }
    case kE3StaCtrl: {
        const unsigned phy_reg = v & 31u;
        if (v & kStaRead) {
            const std::uint16_t data = phy_read(phy_reg);
            v = (v & 0xFFFFu) | kStaOpDone | (static_cast<std::uint32_t>(data) << 16);
            if (net::trace()) log_line("PHY read reg %u = 0x%04X", phy_reg, data);
            if (phy_reg == kBmsr && !g.link_reported) {
                g.link_reported = true;
                log_line("PHY link up, 100 Mb/s full duplex (BMSR 0x%04X)", data);
            }
        } else if (v & kStaWrite) {
            phy_write(phy_reg, static_cast<std::uint16_t>(v >> 16));
            if (net::trace()) log_line("PHY write reg %u = 0x%04X", phy_reg, v >> 16);
            v |= kStaOpDone;
        }
        emac_set(reg, v);
        return;
    }
    default:
        return;
    }
}

// ---- buffer descriptors --------------------------------------------------

std::uint16_t *tx_desc(unsigned i) { return &g.bd[(i & 63u) * 4u]; }
std::uint16_t *rx_desc(unsigned i) { return &g.bd[(kBdRx - kBdTx) / 2 + (i & 63u) * 4u]; }

std::uint16_t swap16(std::uint16_t v) { return static_cast<std::uint16_t>((v >> 8) | (v << 8)); }

// ---- frames --------------------------------------------------------------

bool rx_accept(const std::vector<std::uint8_t> &frame) {
    if (!(emac_get(kE3Mode0) & kE3RxEnable)) return false;
    std::uint16_t *d = rx_desc(g.rx_bd);
    if (!(d[0] & kBdRxEmpty) || g.rx_frames >= kBdCount) {
        raise(kIntrRxDnv);
        return false;
    }
    const std::uint32_t size = static_cast<std::uint32_t>(std::max<std::size_t>(frame.size(), 60));
    const std::uint32_t used = (g.rx_wr - g.rx_rd) & kFifoMask;
    if (g.rx_frames && used + ((size + 3) & ~3u) >= kFifoSize) return false;
    const std::uint32_t start = g.rx_wr;
    for (std::uint32_t i = 0; i < size; ++i) g.rx_fifo[(start + i) & kFifoMask] = i < frame.size() ? frame[i] : 0;
    g.rx_wr = (start + ((size + 3) & ~3u)) & kFifoMask;
    d[2] = static_cast<std::uint16_t>(size);
    d[3] = static_cast<std::uint16_t>(0x4000u + start);
    d[0] = static_cast<std::uint16_t>(d[0] & ~kBdRxEmpty & ~0x03FFu);
    g.rx_bd = (g.rx_bd + 1) & 63u;
    ++g.rx_frames;
    raise(kIntrRxEnd);
    return true;
}

void deliver_rx() {
    while (!g.pending.empty() && rx_accept(g.pending.front())) {
        g.pending.pop_front();
        ++g.received;
    }
}

void transmit() {
    unsigned count = 0;
    for (;;) {
        std::uint16_t *d = tx_desc(g.tx_bd);
        if (!(d[0] & kBdTxReady)) break;
        const std::uint32_t length = d[2], pointer = d[3];
        if (length >= 14 && length <= 1518) {
            std::vector<std::uint8_t> frame(length);
            const std::uint32_t base = (pointer - 0x1000u) & kFifoMask;
            for (std::uint32_t i = 0; i < length; ++i) frame[i] = g.tx_fifo[(base + i) & kFifoMask];
            pcap_write(frame.data(), frame.size());
            if (net::trace()) log_line("TX %s", summary(frame.data(), frame.size()).c_str());
            if (loopback()) {
                g.pending.push_back(std::move(frame));
            } else if (g.backend) {
                g.backend->send(frame.data(), frame.size());
                ++g.sent;
            }
        } else {
            log_line("TX descriptor %u has a bad length %u", g.tx_bd, length);
        }
        d[0] = static_cast<std::uint16_t>(d[0] & ~kBdTxReady & ~0x03FFu);
        g.tx_bd = (g.tx_bd + 1) & 63u;
        if (g.tx_frames) --g.tx_frames;
        ++count;
    }
    // The controller flags TXDNV whenever it runs into a descriptor that is not
    // ready, which after a burst is always the case.
    raise(static_cast<std::uint16_t>((count ? kIntrTxEnd : 0) | kIntrTxDnv));
    deliver_rx();
}

void schedule_poll() {
    if (!g.enabled || g.polling) return;
    g.polling = true;
    kernel::schedule(kernel::now() + kernel::usec_to_cycles(250), kernel::Job{kernel::kJobDev9Poll});
}

// ---- register files ------------------------------------------------------

std::uint16_t speed_read(std::uint32_t o) {
    switch (o) {
    case kSpdRev: return 0;
    case kSpdRev1: return kSpeedRevision;
    case kSpdCaps: return kCapsSmap; // network only, no ATA (no hard disk attached)
    case kSpd0e: return 0;
    case kSpdId: return 1;           // official adapters answer 1 here
    case kSpdIntrStat: return g.cause;
    case kSpdIntrMask: return g.mask;
    case kSpdPioData: return g.rom.out ? 0x10 : 0;
    default: return g.speed[(o >> 1) & 0x7F];
    }
}

void speed_write(std::uint32_t o, std::uint16_t v) {
    switch (o) {
    case kSpdIntrStat: return; // read-only, causes are cleared through SMAP_R_INTR_CLR
    case kSpdIntrMask:
        g.mask = v;
        update_irq();
        return;
    case kSpdPioData:
        eeprom_pins(static_cast<std::uint8_t>(v));
        break;
    default: break;
    }
    g.speed[(o >> 1) & 0x7F] = v;
}

std::uint32_t smap_read(std::uint32_t o, unsigned size) {
    if (o >= kEmacBase && o < kEmacEnd) {
        const std::uint32_t lo = g.emac[(o - kEmacBase) / 2];
        if (size == 4) return lo | (static_cast<std::uint32_t>(g.emac[(o - kEmacBase) / 2 + 1]) << 16);
        return lo;
    }
    if (o >= kBdTx && o < kBdEnd) {
        auto get = [](std::uint32_t at) {
            const std::uint16_t v = g.bd[(at - kBdTx) / 2];
            return g.bd_swap ? swap16(v) : v;
        };
        if (size == 4) return get(o) | (static_cast<std::uint32_t>(get(o + 2)) << 16);
        if (size == 1) return (get(o & ~1u) >> ((o & 1u) * 8)) & 0xFF;
        return get(o);
    }
    switch (o) {
    case kBdMode: return g.bd_swap ? 1 : 0;
    case kTxCtrl: return g.tx_ctrl;
    case kTxWrPtr: return g.tx_wr;
    case kTxSize: return kFifoSize;
    case kTxFrameCnt: return g.tx_frames;
    case kRxCtrl: return g.rx_ctrl;
    case kRxRdPtr: return g.rx_rd;
    case kRxSize: return kFifoSize;
    case kRxFrameCnt: return g.rx_frames;
    case kRxData: {
        std::uint32_t v = 0;
        for (unsigned i = 0; i < 4; ++i) v |= static_cast<std::uint32_t>(g.rx_fifo[(g.rx_rd + i) & kFifoMask]) << (i * 8);
        g.rx_rd = (g.rx_rd + 4) & kFifoMask;
        return v;
    }
    default:
        return 0;
    }
}

void smap_write(std::uint32_t o, std::uint32_t v, unsigned size) {
    if (o >= kEmacBase && o < kEmacEnd) {
        const std::uint32_t idx = (o - kEmacBase) / 2;
        if (size == 4) {
            g.emac[idx] = static_cast<std::uint16_t>(v);
            g.emac[idx + 1] = static_cast<std::uint16_t>(v >> 16);
            emac_commit((o - kEmacBase) & ~3u);
            return;
        }
        g.emac[idx] = static_cast<std::uint16_t>(v);
        if (o & 2u) emac_commit((o - kEmacBase) & ~3u);
        return;
    }
    if (o >= kBdTx && o < kBdEnd) {
        auto put = [](std::uint32_t at, std::uint16_t value) { g.bd[(at - kBdTx) / 2] = g.bd_swap ? swap16(value) : value; };
        if (size == 4) {
            put(o, static_cast<std::uint16_t>(v));
            put(o + 2, static_cast<std::uint16_t>(v >> 16));
        } else if (size == 1) {
            const std::uint32_t at = o & ~1u;
            std::uint16_t cur = g.bd[(at - kBdTx) / 2];
            if (g.bd_swap) cur = swap16(cur);
            const unsigned shift = (o & 1u) * 8;
            cur = static_cast<std::uint16_t>((cur & ~(0xFFu << shift)) | ((v & 0xFFu) << shift));
            put(at, cur);
        } else {
            put(o, static_cast<std::uint16_t>(v));
        }
        return;
    }
    switch (o) {
    case kBdMode:
        g.bd_swap = v & 1u;
        return;
    case kIntrClr:
        g.cause = static_cast<std::uint16_t>(g.cause & ~v);
        return;
    case kTxCtrl:
        if (v & kFifoReset) {
            g.tx_bd = 0;
            g.tx_wr = 0;
            g.tx_frames = 0;
        }
        g.tx_ctrl = static_cast<std::uint8_t>(v & ~kFifoReset);
        return;
    case kTxWrPtr:
        g.tx_wr = v & kFifoMask;
        return;
    case kTxFrameInc:
        ++g.tx_frames;
        return;
    case kRxCtrl:
        if (v & kFifoReset) {
            g.rx_bd = 0;
            g.rx_wr = g.rx_rd = 0;
            g.rx_frames = 0;
        }
        g.rx_ctrl = static_cast<std::uint8_t>(v & ~kFifoReset);
        return;
    case kRxRdPtr:
        g.rx_rd = v & kFifoMask;
        return;
    case kRxFrameDec:
        if (g.rx_frames) --g.rx_frames;
        deliver_rx();
        return;
    case kTxData:
        for (unsigned i = 0; i < size; ++i) g.tx_fifo[(g.tx_wr + i) & kFifoMask] = static_cast<std::uint8_t>(v >> (i * 8));
        g.tx_wr = (g.tx_wr + size) & kFifoMask;
        return;
    default:
        return;
    }
}

}

void reset() {
    g = State{};
    kernel::register_job(kernel::kJobDev9Poll, [](const kernel::Job &) {
        g.polling = false;
        poll();
        schedule_poll();
    });
    const std::string url = net::configured_url();
    if (url.empty()) return;
    g.enabled = true;
    net::configured_mac(g.mac);
    build_eeprom();
    phy_reset();
    g.bridge[kDev9Rev / 2] = kExpansionBay;
    for (unsigned i = 0; i < kBdCount; ++i) rx_desc(i)[0] = kBdRxEmpty;
    if (!g_backend) {
        g_backend = net::make_websocket(url);
        const std::string pcap = net::configured_pcap();
        if (!pcap.empty()) pcap_open(pcap);
    }
    g.backend = g_backend.get();
    g.pcap = g_pcap;
    log_line("network adapter present (expansion bay, SPEED rev 0x%02X), MAC %02x:%02x:%02x:%02x:%02x:%02x, frames via %s", kSpeedRevision,
             g.mac[0], g.mac[1], g.mac[2], g.mac[3], g.mac[4], g.mac[5], g.backend->describe().c_str());
}

bool enabled() { return g.enabled; }

bool owns(std::uint32_t a) {
    return g.enabled && ((a >= kDev9Base && a < kDev9End) || (a >= kSpeedBase && a < kSpeedEnd));
}

std::uint32_t read(std::uint32_t a, unsigned size) {
    schedule_poll();
    if (a >= kDev9Base && a < kDev9End) {
        const std::uint32_t o = a - kDev9Base;
        std::uint32_t v = g.bridge[(o >> 1) & 15u];
        if (o == kDev9Rev) log_line("DEV9 revision read: 0x%02X", v);
        if (size == 1) v = (v >> ((o & 1u) * 8)) & 0xFF;
        return v;
    }
    const std::uint32_t o = a - kSpeedBase;
    if (o < 0x100) {
        if (size == 4) return speed_read(o) | (static_cast<std::uint32_t>(speed_read(o + 2)) << 16);
        const std::uint16_t v = speed_read(o & ~1u);
        return size == 1 ? (v >> ((o & 1u) * 8)) & 0xFF : v;
    }
    if (o < 0x4800) return smap_read(o, size);
    return 0;
}

void write(std::uint32_t a, std::uint32_t v, unsigned size) {
    if (a >= kDev9Base && a < kDev9End) {
        const std::uint32_t o = a - kDev9Base;
        if (o != kDev9Rev) g.bridge[(o >> 1) & 15u] = static_cast<std::uint16_t>(v);
        return;
    }
    const std::uint32_t o = a - kSpeedBase;
    if (o < 0x100) {
        if (size == 4) {
            speed_write(o, static_cast<std::uint16_t>(v));
            speed_write(o + 2, static_cast<std::uint16_t>(v >> 16));
        } else if (!(size == 1 && (o & 1u))) {
            speed_write(o & ~1u, static_cast<std::uint16_t>(v)); // byte writes reach the whole register
        }
        return;
    }
    if (o < 0x4800) smap_write(o, v, size);
}

bool dma(std::uint32_t madr, std::uint32_t words, bool to_device) {
    if (!g.enabled) return false;
    const std::uint32_t bytes = words * 4u;
    if (!(g.speed[kSpdDmaCtrl / 2] & 1u)) {
        log_line("ATA DMA of %u bytes ignored (no hard disk)", bytes);
        return true;
    }
    if (to_device) {
        if (g.tx_ctrl & kFifoDmaEn) {
            for (std::uint32_t i = 0; i < bytes; ++i) g.tx_fifo[(g.tx_wr + i) & kFifoMask] = *ram_ptr(madr + i);
            g.tx_wr = (g.tx_wr + bytes) & kFifoMask;
            g.tx_ctrl &= static_cast<std::uint8_t>(~kFifoDmaEn);
        }
    } else if (g.rx_ctrl & kFifoDmaEn) {
        for (std::uint32_t i = 0; i < bytes; ++i) *ram_ptr(madr + i) = g.rx_fifo[(g.rx_rd + i) & kFifoMask];
        g.rx_rd = (g.rx_rd + bytes) & kFifoMask;
        g.rx_ctrl &= static_cast<std::uint8_t>(~kFifoDmaEn);
    }
    return true;
}

void poll() {
    if (!g.enabled || !g.backend) return;
    std::vector<std::uint8_t> frame;
    while (g.pending.size() < 256 && g.backend->receive(frame)) {
        if (frame.size() < 14 || frame.size() > 1518) continue;
        pcap_write(frame.data(), frame.size());
        if (net::trace()) log_line("RX %s", summary(frame.data(), frame.size()).c_str());
        g.pending.push_back(std::move(frame));
    }
    deliver_rx();
}

}

namespace ps2::net {
// The adapter owns the WebSocket; control texts (left-scenario) go out on it.
void send_control(const std::string &text) {
    if (iop::dev9::g_backend) iop::dev9::g_backend->send_text(text);
}
}
