// Puts a ready-made PS2 network configuration ("Your Network Configuration",
// mc0:/BWNETCNF) on the memory card image when networking is on, so a game's
// network menu goes straight to "REO gateway (DHCP)" instead of Sony's setup
// wizard. C++ port of net/gateway/tools/ps2netcnf.py (reoutbreak project).
//
// NETCNF.IRX reads (see docs/notes/network.md in reoutbreak):
//   BWNETCNF     index, "<type>,<flag>,<file>,<name>" per line
//   net000.cnf   combination (plain text)
//   ifc000.dat   interface: type eth + dhcp      (obfuscated, see encode())
//   dev000.dat   device: vendor/product of SMAP  (obfuscated)
//   icon.sys, SYS_NET.ICO from the disc (NTGUI2 or NTGUIDVD /CONF/ENGLISH) when present.
// The card keeps its saves: files only go to clusters the FAT marks free, and
// a blank (never formatted) card is formatted first.

#include "netcnf_card.hpp"

#include "ps2/disc.hpp"
#include "ps2/memory.hpp"
#include "ps2/system.hpp"

#include <array>
#include <cctype>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>
#include <utility>
#include <vector>

namespace ps2::iop::netcnf {

namespace {

constexpr std::size_t kPage = 512, kRawPage = 528, kPages = 0x4000, kCluster = 1024;
constexpr std::uint32_t kFree = 0x7FFFFFFFu, kEnd = 0xFFFFFFFFu, kUsed = 0x80000000u;
constexpr std::uint16_t kDirMode = 0x8427, kFileMode = 0x8497, kDotDotRoot = 0xA426, kProtected = 0x0008, kExists = 0x8000;
constexpr const char kMagic[] = "Sony PS2 Memory Card Format ";
constexpr const char kHeader[] = "# <Sony Computer Entertainment Inc.>\n\n";

std::uint32_t rd32(const std::uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | static_cast<std::uint32_t>(p[3]) << 24; }
void wr32(std::uint8_t *p, std::uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = static_cast<std::uint8_t>(v >> (8 * i)); }
void wr16(std::uint8_t *p, std::uint16_t v) { p[0] = static_cast<std::uint8_t>(v); p[1] = static_cast<std::uint8_t>(v >> 8); }

// .dat obfuscation with the runtime's all-zero i.LINK ID: every shift is 1.
std::string encode(const std::string &plain) {
    std::string out = plain;
    for (std::size_t i = 0; i + 1 < out.size(); i += 2) {
        std::uint16_t w = static_cast<std::uint8_t>(out[i]) | static_cast<std::uint8_t>(out[i + 1]) << 8;
        w = static_cast<std::uint16_t>(((w << 1) | (w >> 15)) ^ 0xFFFF);
        out[i] = static_cast<char>(w);
        out[i + 1] = static_cast<char>(w >> 8);
    }
    if (out.size() & 1) {
        auto b = static_cast<std::uint8_t>(out.back());
        out.back() = static_cast<char>(static_cast<std::uint8_t>((b << 1) | (b >> 7)) ^ 0xFF);
    }
    return out;
}

// Hamming ECC over 128 bytes, the layout MCMAN checks (3 bytes per chunk).
std::array<std::uint8_t, 3> ecc128(const std::uint8_t *chunk) {
    static const std::array<std::uint8_t, 256> table = [] {
        std::array<std::uint8_t, 256> t{};
        const std::uint8_t masks[7] = {0x55, 0x33, 0x0F, 0x00, 0xAA, 0xCC, 0xF0};
        for (int b = 0; b < 256; ++b) {
            int v = 0;
            for (int i = 0; i < 7; ++i) v |= (__builtin_popcount(b & masks[i]) & 1) << i;
            t[b] = static_cast<std::uint8_t>(v | (__builtin_popcount(b) & 1) << 7);
        }
        return t;
    }();
    std::uint8_t col = 0, line0 = 0, line1 = 0;
    for (int i = 0; i < 128; ++i) {
        const std::uint8_t v = table[chunk[i]];
        col ^= v;
        if (v & 0x80) {
            line0 ^= static_cast<std::uint8_t>(~i);
            line1 ^= static_cast<std::uint8_t>(i);
        }
    }
    return {static_cast<std::uint8_t>(~col & 0x77), static_cast<std::uint8_t>(~line0 & 0x7F), static_cast<std::uint8_t>(~line1 & 0x7F)};
}

struct Card {
    std::vector<std::uint8_t> &raw;
    std::vector<bool> touched = std::vector<bool>(kPages, false);
    std::uint32_t alloc_offset = 41, alloc_end = 8135, ifc = 8;
    std::array<std::uint8_t, 8> now{};

    std::uint8_t *page(std::size_t n) { return raw.data() + n * kRawPage; }
    void read_cluster(std::uint32_t n, std::uint8_t *out) {
        std::memcpy(out, page(n * 2), kPage);
        std::memcpy(out + kPage, page(n * 2 + 1), kPage);
    }
    void write_cluster(std::uint32_t n, const std::uint8_t *in, std::size_t size = kCluster) {
        std::uint8_t buf[kCluster];
        std::memset(buf, 0xFF, sizeof buf);
        std::memcpy(buf, in, std::min(size, kCluster));
        std::memcpy(page(n * 2), buf, kPage);
        std::memcpy(page(n * 2 + 1), buf + kPage, kPage);
        touched[n * 2] = touched[n * 2 + 1] = true;
    }
    bool formatted() { return std::memcmp(page(0), kMagic, 28) == 0; }
    bool blank() {
        for (std::size_t i = 0; i < kPage; ++i) if (page(0)[i] != 0xFF) return false;
        return true;
    }

    std::uint32_t fat_get(std::uint32_t n) {
        std::uint8_t c[kCluster];
        read_cluster(ifc, c);
        const std::uint32_t fat_cluster = rd32(c + (n / 256) * 4);
        read_cluster(fat_cluster, c);
        return rd32(c + (n % 256) * 4);
    }
    void fat_set(std::uint32_t n, std::uint32_t v) {
        std::uint8_t c[kCluster];
        read_cluster(ifc, c);
        const std::uint32_t fat_cluster = rd32(c + (n / 256) * 4);
        read_cluster(fat_cluster, c);
        wr32(c + (n % 256) * 4, v);
        write_cluster(fat_cluster, c);
    }
    bool allocate(std::size_t count, std::vector<std::uint32_t> &chain) {
        chain.clear();
        for (std::uint32_t n = 0; n < alloc_end && chain.size() < count; ++n)
            if (fat_get(n) == kFree) chain.push_back(n);
        if (chain.size() < count) return false;
        for (std::size_t i = 0; i < chain.size(); ++i) fat_set(chain[i], i + 1 < chain.size() ? (kUsed | chain[i + 1]) : kEnd);
        return true;
    }
    std::vector<std::uint32_t> chain(std::uint32_t first) {
        std::vector<std::uint32_t> out;
        for (std::uint32_t n = first; out.size() < alloc_end;) {
            out.push_back(n);
            const std::uint32_t v = fat_get(n);
            if (v == kEnd || !(v & kUsed)) break;
            n = v & 0x7FFFFFFF;
        }
        return out;
    }
    std::vector<std::uint8_t> read_chain(std::uint32_t first, std::size_t length) {
        std::vector<std::uint8_t> out;
        for (std::uint32_t n : chain(first)) {
            const std::size_t at = out.size();
            out.resize(at + kCluster);
            read_cluster(alloc_offset + n, out.data() + at);
            if (out.size() >= length) break;
        }
        out.resize(length, 0xFF);
        return out;
    }
    bool write_new_chain(const std::string &data, std::uint32_t &first) {
        std::vector<std::uint32_t> clusters;
        if (!allocate(std::max<std::size_t>(1, (data.size() + kCluster - 1) / kCluster), clusters)) return false;
        for (std::size_t i = 0; i < clusters.size(); ++i) {
            const std::size_t at = i * kCluster;
            write_cluster(alloc_offset + clusters[i], reinterpret_cast<const std::uint8_t *>(data.data()) + std::min(at, data.size()),
                          at < data.size() ? data.size() - at : 0);
        }
        first = clusters[0];
        return true;
    }
    // A directory as a list of 512-byte entries.
    using Entry = std::array<std::uint8_t, 512>;
    std::vector<Entry> entries(std::uint32_t first, std::size_t count) {
        const auto data = read_chain(first, count * 512);
        std::vector<Entry> out(count);
        for (std::size_t i = 0; i < count; ++i) std::memcpy(out[i].data(), data.data() + i * 512, 512);
        return out;
    }
    std::vector<Entry> root() {
        std::uint8_t c[kCluster];
        read_cluster(alloc_offset, c);
        return entries(0, rd32(c + 4));
    }
    bool write_entries(std::uint32_t first, const std::vector<Entry> &ents) {
        auto clusters = chain(first);
        const std::size_t need = (ents.size() + 1) / 2;
        if (need > clusters.size()) {
            std::vector<std::uint32_t> extra;
            if (!allocate(need - clusters.size(), extra)) return false;
            fat_set(clusters.back(), kUsed | extra[0]);
            clusters.insert(clusters.end(), extra.begin(), extra.end());
        }
        for (std::size_t i = 0; i < clusters.size(); ++i) {
            std::uint8_t buf[kCluster];
            std::memset(buf, 0xFF, sizeof buf);
            for (std::size_t j = 0; j < 2 && i * 2 + j < ents.size(); ++j) std::memcpy(buf + j * 512, ents[i * 2 + j].data(), 512);
            write_cluster(alloc_offset + clusters[i], buf);
        }
        return true;
    }
    Entry entry(std::uint16_t mode, std::uint32_t length, std::uint32_t cluster, std::uint32_t dir_entry, const char *name) {
        Entry e{};
        wr16(e.data(), mode);
        wr32(e.data() + 4, length);
        std::memcpy(e.data() + 8, now.data(), 8);
        wr32(e.data() + 16, cluster);
        wr32(e.data() + 20, dir_entry);
        std::memcpy(e.data() + 24, now.data(), 8);
        std::strncpy(reinterpret_cast<char *>(e.data() + 64), name, 31);
        return e;
    }
    static std::string name_of(const Entry &e) { return std::string(reinterpret_cast<const char *>(e.data() + 64), strnlen(reinterpret_cast<const char *>(e.data() + 64), 32)); }

    void format() {
        std::fill(raw.begin(), raw.end(), 0xFF);
        std::uint8_t *sb = page(0);
        std::memcpy(sb, kMagic, 28);
        std::memset(sb + 28, 0, 12);
        std::memcpy(sb + 28, "1.2.0.0", 7);
        wr16(sb + 40, 512); wr16(sb + 42, 2); wr16(sb + 44, 16); wr16(sb + 46, 0xFF00);
        wr32(sb + 48, 8192); wr32(sb + 52, 41); wr32(sb + 56, 8135); wr32(sb + 60, 0);
        wr32(sb + 64, 1023); wr32(sb + 68, 1022);
        std::memset(sb + 72, 0, 8);
        for (int i = 0; i < 32; ++i) wr32(sb + 80 + 4 * i, i == 0 ? 8 : 0);
        for (int i = 0; i < 32; ++i) wr32(sb + 208 + 4 * i, 0xFFFFFFFF);
        sb[336] = 2;
        sb[337] = 0x2B;
        std::uint8_t c[kCluster];
        std::memset(c, 0, sizeof c);
        const std::uint32_t fat_clusters = (8135 + 255) / 256;
        for (std::uint32_t i = 0; i < fat_clusters; ++i) wr32(c + 4 * i, 9 + i);
        write_cluster(8, c);
        for (std::uint32_t i = 0; i < fat_clusters; ++i) {
            for (std::uint32_t j = 0; j < 256; ++j) wr32(c + 4 * j, i * 256 + j < 8135 ? kFree : kEnd);
            write_cluster(9 + i, c);
        }
        fat_set(0, kEnd);
        std::uint8_t r[kCluster];
        const Entry dot = entry(kDirMode, 2, 0, 0, "."), dotdot = entry(kDotDotRoot, 0, 0, 0, "..");
        std::memcpy(r, dot.data(), 512);
        std::memcpy(r + 512, dotdot.data(), 512);
        write_cluster(41, r);
        std::fill(touched.begin(), touched.end(), true);
    }

    void fix_ecc() {
        for (std::size_t i = 0; i < kPages; ++i) {
            if (!touched[i]) continue;
            std::uint8_t *p = page(i);
            for (int k = 0; k < 4; ++k) {
                const auto e = ecc128(p + 128 * k);
                std::memcpy(p + kPage + 3 * k, e.data(), 3);
            }
            std::memset(p + kPage + 12, 0, 4);
        }
    }
};

std::string disc_file(const char *path) {
    const auto e = disc::find(path);
    if (!e || e->directory || e->size > (1u << 20)) return {};
    const std::uint32_t sectors = (e->size + 2047) / 2048;
    std::string data(static_cast<std::size_t>(sectors) * 2048, '\0');
    if (!disc::read(e->sector, sectors, data.data())) return {};
    data.resize(e->size);
    return data;
}


// Online account of RE Outbreak File #1 (SLUS-20765). The game's network system data
// mc0:/BASLUS-20765NET/BASLUS-20765NET (0x1D7C bytes, no checksum) keeps the account ID as a
// NUL-terminated string at 0xAF0 (20 bytes). With it empty the NETWORK menu keeps GO ONLINE
// disabled; any ID enables it (see docs/notes/protocol.md 6.4 in reoutbreak). The password is
// typed in the ENTRY/PASSWORD dialog each time, so only the ID lives on the card.
struct AccountSlot { const char *dir, *file; std::size_t size, offset, length; };
constexpr AccountSlot kAccountSlots[] = {
    {"BASLUS-20765NET", "BASLUS-20765NET", 0x1D7C, 0xAF0, 20},
};

// PS2_NET_ACCOUNT=<id> (default "player"): letters and digits, at most 15 characters (the
// game copies the ID into a 16-byte login-name buffer at 0x8A64F0 without a bound; see
// docs/notes/protocol.md 6.4 in reoutbreak).
std::string account_id() {
    const char *env = std::getenv("PS2_NET_ACCOUNT");
    std::string id = env && *env ? env : "player";
    std::string out;
    for (char c : id)
        if (std::isalnum(static_cast<unsigned char>(c))) out += c;
    if (out.size() > 15) out.resize(15);
    return out.empty() ? "player" : out;
}

// An explicit PS2_NET_ACCOUNT replaces the ID already on the card or in memory (the web page
// derives the password from the name, so both must match); without it only an empty ID is filled.
bool account_forced() {
    const char *env = std::getenv("PS2_NET_ACCOUNT");
    return env && *env;
}

// PS2_NET_PASSWORD=<credential>: letters and digits, at most 15 characters (16-byte buffer).
std::string password() {
    const char *env = std::getenv("PS2_NET_PASSWORD");
    std::string out;
    for (const char *c = env ? env : ""; *c && out.size() < 15; ++c)
        if (std::isalnum(static_cast<unsigned char>(*c))) out += *c;
    return out;
}

using Files = std::vector<std::pair<std::string, std::string>>;

// Adds a directory with these files to the root. Checks for room first, so a full card is
// left untouched (returns false).
bool add_dir(Card &card, const char *dirname, const Files &files, bool protect) {
    std::size_t need = 2;
    for (const auto &f : files) need += (f.second.size() + kCluster - 1) / kCluster;
    std::size_t free = 0;
    for (std::uint32_t n = 0; n < card.alloc_end && free < need; ++n) free += card.fat_get(n) == kFree;
    if (free < need) {
        std::fprintf(stderr, "[mc] card is full, not adding mc0:/%s\n", dirname);
        return false;
    }
    auto root = card.root();
    const std::uint32_t index = static_cast<std::uint32_t>(root.size());
    std::uint32_t dir = 0;
    std::vector<Card::Entry> sub = {card.entry(kDirMode, 0, 0, index, "."), card.entry(kDirMode, 0, 0, 0, "..")};
    std::string sub_raw(1024, '\xFF');
    std::memcpy(sub_raw.data(), sub[0].data(), 512);
    std::memcpy(sub_raw.data() + 512, sub[1].data(), 512);
    if (!card.write_new_chain(sub_raw, dir)) return false;
    root.push_back(card.entry(protect ? (kDirMode | kProtected) : kDirMode, 2, dir, 0, dirname));
    for (const auto &[name, data] : files) {
        std::uint32_t first = 0xFFFFFFFF;
        if (!data.empty() && !card.write_new_chain(data, first)) return false;
        sub.push_back(card.entry(kFileMode, static_cast<std::uint32_t>(data.size()), first, 0, name.c_str()));
    }
    wr32(root.back().data() + 4, static_cast<std::uint32_t>(sub.size()));
    wr32(root[0].data() + 4, static_cast<std::uint32_t>(root.size()));
    return card.write_entries(dir, sub) && card.write_entries(0, root);
}

// Per-game addresses of the network hooks (File #1 SLUS-20765, File #2 SLUS-20984; docs/notes/network.md 12
// and protocol.md 7 in reoutbreak). netwk.bin of File #2 is File #1's relocated: same code, other addresses.
struct GameNet {
    const char *serial;      // boot ELF on the disc
    int id;                  // 1 = File #1, 2 = File #2
    std::uint32_t pad_ptr;   // pointer to the pad state (+0x10 = newly pressed buttons)
    std::uint32_t pad_bits;  // pad words the netwk d-pad read ORs (held | repeat)
    std::uint32_t pad_bits2;
    std::uint32_t gui_ptr;   // netcnf GUI state pointer (+0x10 cursor, +0x24 last index - 1)
    std::uint32_t cancel;    // cancel button bit of the netcnf GUI (circle 0x20 / triangle 0x200)
    std::uint32_t menu_open, state, substate;  // netwk screen variables (trace only)
};
constexpr GameNet kGames[] = {
    {"SLUS_207.65", 1, 0x2AFFA8, 0x324210, 0x324228, 0x69BCC0, 0x20, 0x6DF185, 0x6DF151, 0x6DF152},
    {"SLUS_209.84", 2, 0x33C5F8, 0x300790, 0x3007A8, 0x6ABF70, 0x200, 0x6E94C5, 0x6E9491, 0x6E9492},
};

const GameNet *game() {
    static const GameNet *g = []() -> const GameNet * {
        for (const auto &k : kGames)
            if (disc::find(k.serial)) return &k;
        return nullptr;
    }();
    return g;
}

// The network system data shared by File #1 and File #2 (both use mc0:/BASLUS-20765NET, same size and layout).
// On a card without it the game asks twice ("No NET file has
// been found... create?" and "OK to return to the main menu?") and only writes the account on
// a later boot; with it, the (patched) game loads it at once. The save is 0x1D7C zero bytes
// (what the game itself writes, no checksum) plus the account ID; the icons and icon.sys
// template come from the disc's CNICON.BIN ("MOMO", count, then offset/size pairs: icon_20,
// icon_21, icon_22, icon.sys). The game's own icon.sys only differs in the title, set here.
void ensure_net_save(Card &card) {
    for (const auto &e : card.root())
        if ((e[0] | e[1] << 8) & kExists && Card::name_of(e) == "BASLUS-20765NET") return;
    if (!game()) return;
    const std::string pack = disc_file("CNICON.BIN");
    if (pack.size() < 40 || pack.compare(0, 4, "MOMO") != 0 || rd32(reinterpret_cast<const std::uint8_t *>(pack.data()) + 4) != 4) return;
    // File #1 packs icon_20, icon_21, icon_22, icon.sys; File #2 icon.sys first: icon.sys is the 964-byte "PS2D" part.
    std::string part[4];
    int icons = 0;
    for (int i = 0; i < 4; ++i) {
        const auto *h = reinterpret_cast<const std::uint8_t *>(pack.data()) + 8 + 8 * i;
        const std::uint32_t at = rd32(h), size = rd32(h + 4);
        if (at > pack.size() || size > pack.size() - at) return;
        std::string data = pack.substr(at, size);
        if (data.size() == 964 && data.compare(0, 4, "PS2D") == 0 && part[3].empty()) part[3] = std::move(data);
        else if (icons < 3) part[icons++] = std::move(data);
    }
    if (icons != 3 || part[3].empty()) return;
    // Title "CAPCOM" / "NET FILE" in full-width Shift-JIS, line break after 12 bytes.
    static const unsigned char title[] = {0x82, 0x62, 0x82, 0x60, 0x82, 0x6F, 0x82, 0x62, 0x82, 0x6E, 0x82, 0x6C, 0x82, 0x6D, 0x82, 0x64,
                                          0x82, 0x73, 0x81, 0x40, 0x82, 0x65, 0x82, 0x68, 0x82, 0x6B, 0x82, 0x64};
    std::memset(part[3].data() + 0xC0, 0, 68);
    std::memcpy(part[3].data() + 0xC0, title, sizeof title);
    part[3][6] = 12;
    part[3][7] = 0;
    std::string save(0x1D7C, '\0');
    const std::string id = account_id();
    std::memcpy(save.data() + 0xAF0, id.data(), std::min<std::size_t>(id.size(), 19));
    const Files files = {{"BASLUS-20765NET", save}, {"icon_20.ico", part[0]}, {"icon_21.ico", part[1]}, {"icon_22.ico", part[2]}, {"icon.sys", part[3]}};
    if (add_dir(card, "BASLUS-20765NET", files, false))
        std::fprintf(stderr, "[mc] added the NET save (File #1/#2) with account \"%s\" (mc0:/BASLUS-20765NET)\n", id.c_str());
}

// Writes the account ID into an existing save whose slot is empty; a set ID is left alone.
bool ensure_account(Card &card) {
    bool changed = false;
    const auto root = card.root();
    for (const auto &slot : kAccountSlots) {
        for (const auto &d : root) {
            if (!((d[0] | d[1] << 8) & kExists) || Card::name_of(d) != slot.dir) continue;
            for (const auto &f : card.entries(rd32(d.data() + 16), rd32(d.data() + 4))) {
                if (!((f[0] | f[1] << 8) & kExists) || Card::name_of(f) != slot.file || rd32(f.data() + 4) != slot.size) continue;
                const auto clusters = card.chain(rd32(f.data() + 16));
                const std::size_t ci = slot.offset / kCluster, at = slot.offset % kCluster;
                if (ci >= clusters.size() || at + slot.length > kCluster) continue;
                std::uint8_t buf[kCluster];
                card.read_cluster(card.alloc_offset + clusters[ci], buf);
                const std::string id = account_id();
                const std::string current(reinterpret_cast<char *>(buf + at), strnlen(reinterpret_cast<char *>(buf + at), slot.length));
                if (!current.empty() && (!account_forced() || current == id)) {
                    std::fprintf(stderr, "[mc] network account already set (%s, \"%s\")\n", slot.dir, current.c_str());
                    continue;
                }
                std::memset(buf + at, 0, slot.length);
                std::memcpy(buf + at, id.data(), std::min(id.size(), slot.length - 1));
                card.write_cluster(card.alloc_offset + clusters[ci], buf);
                std::fprintf(stderr, "[mc] network account \"%s\" written to %s/%s\n", id.c_str(), slot.dir, slot.file);
                changed = true;
            }
        }
    }
    return changed;
}
}

bool ensure(std::vector<std::uint8_t> &raw) {
    if (raw.size() < kRawPage * kPages) return false;
    Card card{raw};
    const std::time_t t = std::time(nullptr);
    if (const std::tm *g = std::gmtime(&t)) {
        card.now = {0, static_cast<std::uint8_t>(g->tm_sec), static_cast<std::uint8_t>(g->tm_min), static_cast<std::uint8_t>(g->tm_hour),
                    static_cast<std::uint8_t>(g->tm_mday), static_cast<std::uint8_t>(g->tm_mon + 1),
                    static_cast<std::uint8_t>((g->tm_year + 1900) & 0xFF), static_cast<std::uint8_t>((g->tm_year + 1900) >> 8)};
    }
    if (!card.formatted()) {
        if (!card.blank()) {
            std::fprintf(stderr, "[mc] card is neither formatted nor blank, not adding the network configuration\n");
            return false;
        }
        card.format();
        std::fprintf(stderr, "[mc] formatted the new card\n");
    } else {
        const std::uint8_t *sb = card.page(0);
        if ((sb[40] | sb[41] << 8) != 512 || (sb[42] | sb[43] << 8) != 2) {
            std::fprintf(stderr, "[mc] unusual card geometry, not adding the network configuration\n");
            return false;
        }
        card.alloc_offset = rd32(sb + 52);
        card.alloc_end = rd32(sb + 56);
        card.ifc = rd32(sb + 80);
    }
    bool has_cnf = false;
    for (const auto &e : card.root())
        if ((e[0] | e[1] << 8) & kExists && Card::name_of(e) == "BWNETCNF") has_cnf = true;
    if (has_cnf) {
        std::fprintf(stderr, "[mc] card already has a network configuration (mc0:/BWNETCNF)\n");
    } else {
        const std::string ifc = std::string(kHeader) + "type eth\ndhcp\n";
        const std::string dev = std::string(kHeader) + "type eth\nvendor \"SCE\"\nproduct \"Ethernet (Network Adaptor)\"\nphy_config auto\n";
        Files files;
        std::string icon = disc_file("NTGUI2/CONF/ENGLISH/ICON.SYS"), ico = disc_file("NTGUI2/CONF/ENGLISH/SYS_NET.ICO");
        if (icon.empty() || ico.empty()) {
            icon = disc_file("NTGUIDVD/CONF/ENGLISH/ICON.SYS");
            ico = disc_file("NTGUIDVD/CONF/ENGLISH/SYS_NET.ICO");
        }
        if (!icon.empty() && !ico.empty()) {
            files.emplace_back("icon.sys", icon);
            files.emplace_back("SYS_NET.ICO", ico);
        }
        files.emplace_back("BWNETCNF", "0,1,net000.cnf,Combination1\n1,1,ifc000.dat,REO gateway (DHCP)\n2,1,dev000.dat,Network Adaptor (Ethernet)\n");
        files.emplace_back("net000.cnf", std::string(kHeader) + "interface \"Combination1\" \"ifc000.dat\" \"dev000.dat\"\n");
        files.emplace_back("ifc000.dat", encode(ifc));
        files.emplace_back("dev000.dat", encode(dev));
        if (add_dir(card, "BWNETCNF", files, true))
            std::fprintf(stderr, "[mc] added the network configuration \"REO gateway (DHCP)\" (mc0:/BWNETCNF, %zu files%s)\n", files.size(),
                         icon.empty() ? ", no icon" : "");
    }
    ensure_net_save(card);
    ensure_account(card);
    card.fix_ecc();
    for (bool t : card.touched)
        if (t) return true;
    return false;
}

std::uint32_t login_hook(bool full, std::uint32_t password_buf, std::uint32_t account_field, std::uint32_t allow_auto, std::uint32_t name_buf) {
    const std::string pw = password();
    for (std::uint32_t i = 0; i < 16; ++i) st8(password_buf + i, i < pw.size() ? static_cast<std::uint8_t>(pw[i]) : 0);
    if (!full) {
        std::fprintf(stderr, "[net] login password %s\n", pw.empty() ? "not set (PS2_NET_PASSWORD)" : "preloaded");
        return pw.empty() ? 0 : 1;
    }
    std::string current;
    for (std::uint32_t i = 0; account_field && i < 20; ++i) {
        const std::uint8_t ch = ld8(account_field + i);
        if (!ch) break;
        current += static_cast<char>(ch);
    }
    if (account_field && (current.empty() || (account_forced() && current != account_id()))) {
        const std::string id = account_id();
        for (std::uint32_t i = 0; i < 20; ++i) st8(account_field + i, i < id.size() ? static_cast<std::uint8_t>(id[i]) : 0);
        std::fprintf(stderr, "[net] network account \"%s\" set in memory\n", id.c_str());
    }
    std::string name;
    for (std::uint32_t i = 0; account_field && i < 15; ++i) {
        const std::uint8_t ch = ld8(account_field + i);
        if (!ch) break;
        name += static_cast<char>(ch);
    }
    if (name_buf)
        for (std::uint32_t i = 0; i < 16; ++i) st8(name_buf + i, i < name.size() ? static_cast<std::uint8_t>(name[i]) : 0);
    static bool auto_done = false;
    const char *env = std::getenv("PS2_NET_AUTO");
    const bool want_auto = allow_auto && !name.empty() && !auto_done && !(env && *env == '0');
    if (want_auto) auto_done = true;
    std::fprintf(stderr, "[net] login name \"%s\", password %s%s\n", name.c_str(), pw.empty() ? "not set" : "preloaded",
                 want_auto ? ", GO ONLINE chosen automatically" : "");
    return name.empty() ? 0 : want_auto ? 2 : 1;
}

std::uint32_t login_auto_entry(std::uint32_t password_buf) {
    static bool done = false;
    const char *env = std::getenv("PS2_NET_AUTO");
    const bool go = !done && ld8(password_buf) != 0 && !(env && *env == '0');
    if (go) {
        done = true;
        std::fprintf(stderr, "[net] login screen: ENTRY chosen automatically (password preloaded)\n");
    }
    return go ? 2 : 1;
}

// Automatic menu presses (docs/notes/network.md 12 in reoutbreak). Each rule fires once per run, a few
// polls after its screen first asks, so a screen that comes back (error, disconnect, Back) is manual.
namespace {

enum class Hook { Confirm, Lobby, NetcnfList, NetcnfMessage };

struct AutoRule {
    int game;             // 1 File #1, 2 File #2, 0 both
    Hook hook;
    std::uint32_t ra;     // return address of the check (0 = any)
    std::uint32_t mask;   // lobby: button mask asked for
    const char *what;
    bool optional;        // only with PS2_NET_AREA
    std::uint32_t polls = 0;
    bool done = false;
};

constexpr std::uint32_t kAutoDelay = 8;  // polls (about frames) before the automatic press

AutoRule g_rules[] = {
    {1, Hook::Confirm, 0x00602A04u, 0, "NETWORK CONNECTION", false},
    {0, Hook::NetcnfList, 0, 0, "Select Network Configuration: first configuration", false},
    {1, Hook::NetcnfMessage, 0, 0, "Your Network Configuration file has been loaded: X", false},
    {1, Hook::Confirm, 0x0060969Cu, 0, "news", false},
    {1, Hook::Lobby, 0x005DD808u, 0x10, "SERVER SELECT (FREE MODE)", false},
    {1, Hook::Lobby, 0x005CBAA0u, 0x10, "UP TOWN: ROOM LIST", false},
    {1, Hook::Lobby, 0x005CBB84u, 0x10, "area list: AREA 01", true},
    {2, Hook::Confirm, 0x006131D4u, 0, "NETWORK CONNECTION", false},
    {2, Hook::Confirm, 0x006194D4u, 0, "CAPCOM Online User Agreement: I agree", false},
    {2, Hook::Confirm, 0x00619B5Cu, 0, "news (page)", false},
    {2, Hook::Confirm, 0x0061A80Cu, 0, "news (end)", false},
    {2, Hook::Lobby, 0x00608F84u, 0x10, "AREA SELECT: first area", true},
};

bool auto_on() {
    const char *env = std::getenv("PS2_NET_AUTO");
    return !(env && *env == '0');
}

bool area_on() {
    const char *env = std::getenv("PS2_NET_AREA");
    return env && *env && *env != '0';
}

bool menu_trace() {
    static const bool on = [] { const char *e = std::getenv("PS2_NET_MENU_TRACE"); return e && *e && *e != '0'; }();
    return on;
}

void trace_site(const char *what, std::uint32_t ra, std::uint32_t value) {
    std::fprintf(stderr, "[net] menu %s ra=%08X -> %X (frame %llu, menu %02X state %02X/%02X)\n", what, ra, value,
                 static_cast<unsigned long long>(ps2::frame_count()), game() ? ld8(game()->menu_open) : 0, game() ? ld8(game()->state) : 0, game() ? ld8(game()->substate) : 0);
}

void trace_first(const char *what, std::uint32_t ra, std::uint32_t mask) {
    static std::vector<std::uint64_t> seen;
    const std::uint64_t key = static_cast<std::uint64_t>(ra) << 32 | mask;
    for (std::uint64_t k : seen)
        if (k == key) return;
    seen.push_back(key);
    trace_site(what, ra, mask);
}

// True when the rule for this check is due now (once per run).
bool auto_press(Hook hook, std::uint32_t ra, std::uint32_t mask) {
    if (!auto_on()) return false;
    for (AutoRule &r : g_rules) {
        if (r.done || r.hook != hook || (r.game && (!game() || r.game != game()->id)) || (r.ra && r.ra != ra) || (r.mask && !(r.mask & mask))) continue;
        if (r.optional && !area_on()) continue;
        if (++r.polls < kAutoDelay) return false;
        r.done = true;
        std::fprintf(stderr, "[net] auto: %s (frame %llu)\n", r.what, static_cast<unsigned long long>(ps2::frame_count()));
        return true;
    }
    return false;
}

}

std::uint32_t menu_confirm(std::uint32_t ra) {
    if (menu_trace()) trace_first("confirm site", ra, 0x10);
    if (!game()) return 0;
    const std::uint32_t pad = ld32(game()->pad_ptr);
    const bool pressed = pad && (ld32(pad + 0x10) & 0x10) != 0;
    if (menu_trace() && pressed) trace_site("cross", ra, 1);
    return pressed || auto_press(Hook::Confirm, ra, 0x10) ? 1 : 0;
}

std::uint32_t menu_directions(std::uint32_t ra) {
    if (!game()) return 0;
    const std::uint32_t bits = ld32(game()->pad_bits) | ld32(game()->pad_bits2);
    if (menu_trace() && bits) trace_site("dir", ra, bits);
    return bits;
}

std::uint32_t lobby_button(std::uint32_t mask, std::uint32_t ra) {
    if (menu_trace()) trace_first("lobby site", ra, mask);
    return auto_press(Hook::Lobby, ra, mask) ? 1 : 0;
}

std::uint32_t netcnf_list_pad() {
    if (!game()) return 0;
    std::uint32_t pad = ld32(game()->pad_bits);
    const std::uint32_t gui = ld32(game()->gui_ptr);  // netcnf GUI state: +0x10 cursor (0 Edit, 1 Proxy, 2.. configurations), +0x24 last index - 1
    if (gui && !(pad & (0x10 | game()->cancel)) && static_cast<std::int8_t>(ld8(gui + 0x24)) >= 1 && auto_press(Hook::NetcnfList, 0, 0)) {
        st8(gui + 0x10, 2);
        pad |= 0x10;
    }
    return pad;
}

// Private syscall 0x7F07: pad word for the message states of the netcnf GUI (0xD/0xE/0xF), which read it at
// 0x5E7AD0 / 0x5E7B0C / 0x5E7C0C in File #1. While message 0x34 ("Your Network Configuration file has been
// loaded... Press the X button", docs/notes/network.md 13 in reoutbreak) is up it adds X once per run.
std::uint32_t netcnf_message_pad() {
    if (!game()) return 0;
    std::uint32_t pad = ld32(game()->pad_bits);
    const std::uint32_t gui = ld32(game()->gui_ptr);
    if (gui && ld8(gui + 0x4E) == 0x34 && !(pad & (0x10 | game()->cancel)) && auto_press(Hook::NetcnfMessage, 0, 0)) pad |= 0x10;
    return pad;
}

}
