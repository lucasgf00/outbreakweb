#include "ps2/disc.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <cstdlib>
#include <map>
#include <memory>
#include <thread>

namespace ps2::disc {

namespace {

std::FILE *g_file = nullptr;
Reader g_reader;
Probe g_probe;
// When each read that is not ready yet was first asked for (wall clock), see ready().
std::map<std::uint64_t, std::chrono::steady_clock::time_point> g_waiting;
std::uint64_t g_size = 0;
std::uint32_t g_layer1 = 0;
std::map<std::string, Entry> g_index;

std::uint32_t u32(const std::uint8_t *p) {
    std::uint32_t v;
    std::memcpy(&v, p, 4);
    return v;
}

std::string upper(std::string s) {
    for (char &c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return s;
}

void walk(std::uint32_t base, std::uint32_t lba, std::uint32_t size, const std::string &prefix, int depth) {
    if (depth > 16) return;
    std::vector<std::uint8_t> data((size + kSector - 1) / kSector * kSector);
    if (!read(base + lba, static_cast<std::uint32_t>(data.size() / kSector), data.data())) return;
    std::size_t off = 0;
    while (off < size) {
        const std::uint8_t length = data[off];
        if (!length) {
            off = (off / kSector + 1) * kSector;
            continue;
        }
        const std::uint8_t *rec = &data[off];
        off += length;
        const std::uint8_t name_len = rec[32];
        std::string name(reinterpret_cast<const char *>(rec + 33), name_len);
        if (name_len == 1 && (name[0] == 0 || name[0] == 1)) continue;
        const auto semi = name.find(';');
        if (semi != std::string::npos) name = name.substr(0, semi);
        const std::string path = prefix + upper(name);
        const bool dir = rec[25] & 2;
        const Entry entry{base + u32(rec + 2), u32(rec + 10), dir};
        g_index[path] = entry;
        if (dir) walk(base, u32(rec + 2), u32(rec + 10), path + "/", depth + 1);
    }
}

void index_layer(std::uint32_t pvd_sector, std::uint32_t base) {
    std::uint8_t pvd[kSector];
    if (!read(pvd_sector, 1, pvd) || std::memcmp(pvd, "\x01" "CD001", 6) != 0) return;
    walk(base, u32(pvd + 158), u32(pvd + 166), "", 0);
}

}

namespace {
// PS2_DISC_DELAY_MS=<ms> (test only, native): a disc read becomes ready that many ms after it is first asked
// for (the web build waits for its HTTP ranges the same way), to reproduce slow scenario loads.
Probe slow_probe() {
    const char *e = std::getenv("PS2_DISC_DELAY_MS");
    if (!e || std::atoi(e) <= 0) return {};
    const auto delay = std::chrono::milliseconds(std::atoi(e));
    auto first = std::make_shared<std::map<std::uint64_t, std::chrono::steady_clock::time_point>>();
    // PS2_DISC_DELAY_FILE=<path>: slow only while that file exists (a test script creates it at START)
    const char *gate = std::getenv("PS2_DISC_DELAY_FILE");
    std::string g = gate ? gate : "";
    // PS2_DISC_DELAY_AT=<byte offset>: slow only the reads that touch that 256 KiB chunk (one late web range)
    const char *at_env = std::getenv("PS2_DISC_DELAY_AT");
    const std::uint64_t at = at_env ? std::strtoull(at_env, nullptr, 0) / (256u << 10) * (256u << 10) : ~0ull;
    return [first, delay, g, at](std::uint64_t offset, std::size_t size) {
        if (at != ~0ull && (offset + size <= at || offset >= at + (256u << 10))) return true;
        if (!g.empty()) {
            std::FILE *f = std::fopen(g.c_str(), "rb");
            if (!f) return true;
            std::fclose(f);
        }
        const auto now = std::chrono::steady_clock::now();
        auto it = first->find(offset);
        if (it == first->end()) {
            if (first->size() > 100000) first->clear();
            first->emplace(offset, now);
            return false;
        }
        return now - it->second >= delay;
    };
}
}

bool open(const std::string &path) {
    g_file = std::fopen(path.c_str(), "rb");
    if (!g_file) return false;
#if defined(_WIN32)
    _fseeki64(g_file, 0, SEEK_END);
    const std::uint64_t size = static_cast<std::uint64_t>(_ftelli64(g_file));
#else
    fseeko(g_file, 0, SEEK_END);
    const std::uint64_t size = static_cast<std::uint64_t>(ftello(g_file));
#endif
    std::FILE *file = g_file;
    return open(path, size, [file](std::uint64_t offset, void *out, std::size_t bytes) {
#if defined(_WIN32)
        _fseeki64(file, static_cast<long long>(offset), SEEK_SET);
#else
        fseeko(file, static_cast<off_t>(offset), SEEK_SET);
#endif
        return std::fread(out, 1, bytes, file);
    }, slow_probe());
}

bool open(const std::string &name, std::uint64_t size, Reader reader, Probe probe) {
    g_reader = std::move(reader);
    g_probe = std::move(probe);
    g_size = size;
    g_index.clear();
    index_layer(16, 0);
    std::uint8_t pvd[kSector];
    read(16, 1, pvd);
    const std::uint32_t layer0 = u32(pvd + 80);
    for (std::uint32_t s = layer0; s < layer0 + 64 && static_cast<std::uint64_t>(s) * kSector < g_size; ++s) {
        std::uint8_t probe[kSector];
        if (read(s, 1, probe) && std::memcmp(probe, "\x01" "CD001", 6) == 0) {
            g_layer1 = s - 16;
            index_layer(s, g_layer1);
            break;
        }
    }
    std::fprintf(stderr, "[disc] %s: %zu files, %llu sectors, layer 1 at %u\n", name.c_str(), g_index.size(),
                 static_cast<unsigned long long>(g_size / kSector), g_layer1);
    return true;
}

bool is_open() { return static_cast<bool>(g_reader); }

namespace {
std::uint64_t g_sectors_read = 0;
}

std::uint64_t sectors_read() { return g_sectors_read; }

bool read(std::uint32_t sector, std::uint32_t count, void *out) {
    if (!g_reader) return false;
    g_sectors_read += count;
    static const bool trace = [] { const char *e = std::getenv("PS2_LOADLOG"); return e && e[0] == '2'; }();
    if (trace) std::fprintf(stderr, "[disc] read %u +%u\n", sector, count);
    const std::size_t want = static_cast<std::size_t>(count) * kSector;
    const std::size_t got = g_reader(static_cast<std::uint64_t>(sector) * kSector, out, want);
    if (got < want) std::memset(static_cast<std::uint8_t *>(out) + got, 0, want - got);
    return got > 0 || !count;
}

// A read that is not ready lets the game keep running (its jobs poll this), like a drive that seeks. But the web
// build can wait much longer than a drive ever does (a late HTTP range), and the game's CRI stream code gives up
// on a read that takes more than about 10 s of game time: the movie is dropped and the cutscene and voice audio
// stay silent for the rest of the session (reproduced natively with PS2_DISC_DELAY_AT, 15 s: silent; 6 s: fine).
// So after kGrace of waiting for the same read, stop the emulation (block here) until the data is there: the game
// sees at most kGrace of delay, the player sees a pause instead of losing the sound.
bool ready(std::uint32_t sector, std::uint32_t count) {
    if (!g_probe) return true;
    const std::uint64_t offset = static_cast<std::uint64_t>(sector) * kSector;
    const std::size_t size = static_cast<std::size_t>(count) * kSector;
    const std::uint64_t key = (static_cast<std::uint64_t>(sector) << 24) ^ count;
    if (g_probe(offset, size)) {
        g_waiting.erase(key);
        return true;
    }
    constexpr auto kGrace = std::chrono::milliseconds(2000);
    const auto now = std::chrono::steady_clock::now();
    if (g_waiting.size() > 256) g_waiting.clear();
    const auto since = g_waiting.emplace(key, now).first->second;
    if (now - since < kGrace) return false;
    std::fprintf(stderr, "[disc] read of %u sectors at %u late for 2 s: pausing the game until it arrives\n", count, sector);
    const auto paused = std::chrono::steady_clock::now();
    while (!g_probe(offset, size)) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    std::fprintf(stderr, "[disc] read at %u arrived after a %.1f s pause\n", sector,
                 std::chrono::duration<double>(std::chrono::steady_clock::now() - paused).count());
    g_waiting.erase(key);
    return true;
}

std::optional<Entry> find(const std::string &raw) {
    std::string path = raw;
    const auto colon = path.find(':');
    if (colon != std::string::npos) path = path.substr(colon + 1);
    std::replace(path.begin(), path.end(), '\\', '/');
    const auto semi = path.find(';');
    if (semi != std::string::npos) path = path.substr(0, semi);
    while (!path.empty() && path.front() == '/') path.erase(path.begin());
    auto it = g_index.find(upper(path));
    if (it == g_index.end()) return std::nullopt;
    return it->second;
}

std::uint32_t layer1_start() { return g_layer1; }
std::uint64_t sector_count() { return g_size / kSector; }

}
