#include "dmac.hpp"
#include "gs/gif.hpp"
#include "vif.hpp"
#include "vu_micro.hpp"
#include "gs/gs.hpp"
#include "gs/queue.hpp"
#include "hw.hpp"
#include "iop/iop.hpp"
#include "iop/sif.hpp"
#include "iop/sio2.hpp"
#include "iop/spu2.hpp"
#include "ipu/ipu.hpp"
#include "net/backend.hpp"
#include "ps2/disc.hpp"
#include "kernel.hpp"
#include "ps2/generated.hpp"
#include "ps2/memory.hpp"
#include "ps2/state.hpp"
#include "ps2/system.hpp"

#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#endif

#include <algorithm>
#include <cmath>
#include <atomic>
#include <cctype>
#include <chrono>
#include <thread>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <cstdlib>
#include <iterator>
#include <stdexcept>

namespace ps2 {

Cpu g_cpu;
Options g_options;

namespace {

constexpr std::int64_t kMaxSlice = 20000;

struct Event {
    int id;
    std::uint64_t at;
    std::function<void()> action;
};

std::uint64_t g_base = 0;
std::int64_t g_given = 0;
std::vector<Event> g_events;
bool g_halted = false;
std::string g_halt_reason;
std::uint64_t g_frames = 0;
std::uint64_t g_iop_synced = 0;
bool g_state_request = false;
bool g_resumed = false;

std::uint64_t next_event_time() {
    std::uint64_t t = UINT64_MAX;
    for (const Event &e : g_events) t = std::min(t, e.at);
    return t;
}

void run_due_events() {
    sync_iop();
    for (;;) {
        const std::uint64_t t = now();
        auto it = std::min_element(g_events.begin(), g_events.end(), [](const Event &a, const Event &b) { return a.at < b.at; });
        if (it == g_events.end() || it->at > t) return;
        auto action = std::move(it->action);
        g_events.erase(it);
        action();
    }
}

void refill(Cpu &c) {
    const std::uint64_t t = now(), next = next_event_time();
    std::int64_t slice = kMaxSlice;
    if (next != UINT64_MAX) slice = std::clamp<std::int64_t>(static_cast<std::int64_t>(next - std::min(next, t)), 1, kMaxSlice);
    set_budget(c, slice);
}

std::uint64_t frame_cycles() { return kEeClock / (g_options.pal ? 50u : 60u); }

void dump_frame() {
    std::vector<std::uint32_t> rgba;
    std::uint32_t w = 0, h = 0;
    if (!gs::display(rgba, w, h)) return;
    char name[512];
    std::snprintf(name, sizeof name, "%s/frame_%05llu.ppm", g_options.frames_dir.c_str(), static_cast<unsigned long long>(g_frames));
    std::FILE *f = std::fopen(name, "wb");
    if (!f) return;
    std::fprintf(f, "P6\n%u %u\n255\n", w, h);
    for (std::uint32_t p : rgba) {
        const std::uint8_t px[3] = {static_cast<std::uint8_t>(p), static_cast<std::uint8_t>(p >> 8), static_cast<std::uint8_t>(p >> 16)};
        std::fwrite(px, 1, 3, f);
    }
    std::fclose(f);
}

// Mean brightness (0-255) of the displayed frame, sampled; -1 when nothing is displayed.
int screen_brightness() {
    std::vector<std::uint32_t> rgba;
    std::uint32_t w = 0, h = 0;
    if (!gs::display(rgba, w, h) || rgba.empty()) return -1;
    std::uint64_t sum = 0, n = 0;
    for (std::size_t i = 0; i < rgba.size(); i += 7, ++n) {
        const std::uint32_t p = rgba[i];
        sum += (p & 0xFF) + (p >> 8 & 0xFF) + (p >> 16 & 0xFF);
    }
    return n ? static_cast<int>(sum / (3 * n)) : -1;
}

// Presses are in route frames: power-on frames shifted by the sync points met so far. A sync point
// is watched from the end of the press before it; when its screen change happens early, the route
// jumps ahead to it, and when it is late, the route waits at it (no later press starts meanwhile).
void apply_presses() {
    if (g_options.presses.empty()) return;
    static std::int64_t shift = 0;
    static std::size_t next = SIZE_MAX;
    static bool seen_black = false;
    static std::vector<const Press *> syncs;
    if (next == SIZE_MAX) {
        for (const Press &p : g_options.presses)
            if (p.sync != Press::kNone) syncs.push_back(&p);
        std::stable_sort(syncs.begin(), syncs.end(), [](const Press *a, const Press *b) { return a->frame < b->frame; });
        next = 0;
        while (next < syncs.size() && syncs[next]->frame < g_frames) ++next;  // a loaded state starts past them
    }
    std::int64_t t = static_cast<std::int64_t>(g_frames) - shift;
    if (next < syncs.size()) {
        const Press &s = *syncs[next];
        std::uint64_t watch = next ? syncs[next - 1]->frame : 0;
        for (const Press &p : g_options.presses)
            if (p.sync == Press::kNone && p.frame < s.frame) watch = std::max<std::uint64_t>(watch, p.frame + p.frames);
        if (t >= static_cast<std::int64_t>(watch)) {
            const int b = screen_brightness();
            const bool black = b >= 0 && b <= 2, lit = b >= 8;  // dark game scenes sit around 10-25
            seen_black |= black;
            if ((s.sync == Press::kBlack && black) || (s.sync == Press::kVisible && seen_black && lit)) {
                shift = static_cast<std::int64_t>(g_frames) - static_cast<std::int64_t>(s.frame);
                std::fprintf(stderr, "[route] sync %s (route frame %llu) at frame %llu, shift %+lld\n", s.sync == Press::kBlack ? "black" : "visible",
                             static_cast<unsigned long long>(s.frame), static_cast<unsigned long long>(g_frames), static_cast<long long>(shift));
                ++next;
                seen_black = false;
            } else if (t >= static_cast<std::int64_t>(s.frame)) {
                ++shift;  // late: hold the route at the sync point
            }
            t = static_cast<std::int64_t>(g_frames) - shift;
        }
    }
    const std::uint64_t limit = next < syncs.size() ? syncs[next]->frame : UINT64_MAX;
    std::uint16_t mask = 0;
    for (const Press &p : g_options.presses)
        if (p.sync == Press::kNone && p.frame < limit && t >= static_cast<std::int64_t>(p.frame) && t < static_cast<std::int64_t>(p.frame + p.frames)) mask |= p.buttons;
    iop::sio2::set_buttons(0, mask);
}

void time_frames() {
    static const char *spec = std::getenv("PS2_TIME");
    if (!spec) return;
    static const std::uint64_t from = std::strtoull(spec, nullptr, 10);
    static const std::uint64_t count = std::strchr(spec, ',') ? std::strtoull(std::strchr(spec, ',') + 1, nullptr, 10) : 500;
    static std::chrono::steady_clock::time_point start;
    if (g_frames == from) {
        gs::flush_and_wait();
        start = std::chrono::steady_clock::now();
    } else if (g_frames == from + count) {
        gs::flush_and_wait();
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        std::fprintf(stderr, "[time] frames %llu-%llu: %.2f ms per frame\n", static_cast<unsigned long long>(from), static_cast<unsigned long long>(from + count), ms / static_cast<double>(count));
    }
}

// PS2_PACE=<frame>[,<hz>][;<frame>,<hz>...]: from that frame on, never run faster than real time (59.94 Hz).
// For network runs, where the far side (gateway, servers) lives in wall-clock time. A lower <hz> from a later
// frame stands in for a machine that cannot keep up (the game steps its world per vblank pair, so it plays
// in slow motion; runtime.md "Ritmo de la lógica").
void pace_frames() {
    static const char *spec = std::getenv("PS2_PACE");
    if (!spec) return;
    struct Step { std::uint64_t from; double hz; };
    static const std::vector<Step> steps = [] {
        std::vector<Step> v;
        for (const char *s = spec; *s;) {
            Step st{std::strtoull(s, const_cast<char **>(&s), 10), 59.94};
            if (*s == ',') st.hz = std::strtod(s + 1, const_cast<char **>(&s));
            if (st.hz <= 0) st.hz = 59.94;
            v.push_back(st);
            if (*s != ';') break;
            ++s;
        }
        return v;
    }();
    if (steps.empty() || g_frames < steps[0].from) return;
    double hz = steps[0].hz;
    for (const Step &st : steps)
        if (g_frames >= st.from) hz = st.hz;
    const auto period = std::chrono::microseconds(static_cast<long long>(1e6 / hz));
    static std::chrono::steady_clock::time_point next = std::chrono::steady_clock::now();
    next += period;
    const auto now = std::chrono::steady_clock::now();
    if (next > now) std::this_thread::sleep_until(next);
    else next = now;
}

// PS2_POKE="frame:addr=hex[;frame:addr=hex...]" (debug): at that frame, write the bytes to EE RAM.
// "first-last:addr=hex" writes them on every frame of the range (state the game rewrites, e.g. after a load).
// addr may be "*ptr+off": the address is the u32 at ptr plus off. Used to fill game state that
// otherwise needs on-screen-keyboard typing (e.g. the US network account) in scripted runs.
void poke_frames() {
    static const char *spec = std::getenv("PS2_POKE");
    if (!spec) return;
    const char *s = spec;
    while (*s) {
        unsigned long long frame = std::strtoull(s, const_cast<char **>(&s), 10), last = frame;
        if (*s == '-') last = std::strtoull(s + 1, const_cast<char **>(&s), 10);
        if (*s++ != ':') return;
        bool indirect = *s == '*';
        if (indirect) ++s;
        std::uint32_t addr = static_cast<std::uint32_t>(std::strtoul(s, const_cast<char **>(&s), 16));
        std::uint32_t off = 0;
        if (*s == '+') { ++s; off = static_cast<std::uint32_t>(std::strtoul(s, const_cast<char **>(&s), 16)); }
        if (*s++ != '=') return;
        const char *hex = s;
        while (std::isxdigit(static_cast<unsigned char>(*s))) ++s;
        if (g_frames >= frame && g_frames <= last) {
            std::uint32_t a = (indirect ? (load<std::uint32_t>(g_ram + (addr & (kRamSize - 1)))) : addr) + off;
            const bool quiet = g_frames != frame;
            if (!quiet) std::fprintf(stderr, "[poke] frame %llu at %08X:", frame, a);
            for (const char *h = hex; h + 1 < s; h += 2) {
                const char b[3] = {h[0], h[1], 0};
                g_ram[(a++) & (kRamSize - 1)] = static_cast<std::uint8_t>(std::strtoul(b, nullptr, 16));
                if (!quiet) std::fprintf(stderr, " %s", b);
            }
            if (!quiet) std::fprintf(stderr, "\n");
        }
        if (*s == ';') ++s;
    }
}

// PS2_SNAP=<from>,<every>,<count>,<prefix> (debug): write EE RAM to <prefix>-<frame>.ram on <count> frames
// starting at <from>, <every> frames apart, plus the wall-clock time of each. For finding counters (diff the files).
void snap_frames() {
    static const char *spec = std::getenv("PS2_SNAP");
    if (!spec) return;
    static unsigned long long from = 0, every = 1, count = 0;
    static char prefix[256] = {};
    static bool parsed = std::sscanf(spec, "%llu,%llu,%llu,%255s", &from, &every, &count, prefix) == 4;
    if (!parsed || g_frames < from || (g_frames - from) % every || (g_frames - from) / every >= count) return;
    static const auto t0 = std::chrono::steady_clock::now();
    char path[300];
    std::snprintf(path, sizeof path, "%s-%llu.ram", prefix, static_cast<unsigned long long>(g_frames));
    if (std::FILE *f = std::fopen(path, "wb")) {
        std::fwrite(g_ram, 1, kRamSize, f);
        std::fclose(f);
    }
    std::fprintf(stderr, "[snap] frame %llu at %.1f ms\n", static_cast<unsigned long long>(g_frames),
                 std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
}

// PS2_WATCH=<every>:<addr>[,<addr>...] (debug): every <every> frames, print the u32 at each address and the
// wall-clock time. For measuring how fast a game counter advances against vblanks and real time.
void watch_frames() {
    static const char *spec = std::getenv("PS2_WATCH");
    if (!spec) return;
    static unsigned long long every = std::strtoull(spec, nullptr, 10);
    static std::vector<std::uint32_t> addrs = [] {
        std::vector<std::uint32_t> v;
        const char *s = std::strchr(spec, ':');
        while (s && *s) v.push_back(static_cast<std::uint32_t>(std::strtoul(s + 1, const_cast<char **>(&s), 16)));
        return v;
    }();
    if (!every || g_frames % every) return;
    static const auto t0 = std::chrono::steady_clock::now();
    std::fprintf(stderr, "[watch] frame %llu %.1f ms", static_cast<unsigned long long>(g_frames),
                 std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    for (std::uint32_t a : addrs) std::fprintf(stderr, " %08X=%u", a, load<std::uint32_t>(g_ram + (a & (kRamSize - 1))));
    std::fprintf(stderr, "\n");
}

// PS2_LOADLOG=1: one line per loading screen and per burst of disc reads, to compare loading times (PS2_DISC_SPEED).
// A dark spell is a run of frames with mean brightness <= 2 (NOW LOADING and fades count as dark); a burst is disc
// reads separated by less than 30 idle frames. Frames are game time (60 per second of play); wall ms is this machine.
void load_frames() {
    static const bool on = std::getenv("PS2_LOADLOG") != nullptr;
    if (!on) return;
    using clock = std::chrono::steady_clock;
    static bool dark = false;
    static std::uint64_t dark_from = 0;
    static clock::time_point dark_t;
#ifdef __EMSCRIPTEN__
    const int b = -1;  // gs::display reads the WebGPU frame back, which the emulator thread cannot do: disc bursts only
#else
    const int b = screen_brightness();
#endif
    if (!dark && b >= 0 && b <= 2) {
        dark = true;
        dark_from = g_frames;
        dark_t = clock::now();
    } else if (dark && b >= 8) {
        dark = false;
        if (g_frames - dark_from >= 10)
            std::fprintf(stderr, "[load] dark frames %llu-%llu (%llu frames, %.0f ms wall)\n", static_cast<unsigned long long>(dark_from),
                         static_cast<unsigned long long>(g_frames), static_cast<unsigned long long>(g_frames - dark_from),
                         std::chrono::duration<double, std::milli>(clock::now() - dark_t).count());
    }
    static std::uint64_t last_sectors = 0, burst_from = 0, burst_last = 0, burst_sectors = 0;
    static clock::time_point burst_t, burst_end_t;
    static bool burst = false;
    const std::uint64_t sectors = disc::sectors_read();
    if (sectors != last_sectors) {
        if (!burst) {
            burst = true;
            burst_from = g_frames;
            burst_sectors = 0;
            burst_t = clock::now();
        }
        burst_sectors += sectors - last_sectors;
        burst_last = g_frames;
        burst_end_t = clock::now();
        last_sectors = sectors;
    } else if (burst && g_frames - burst_last >= 30) {
        burst = false;
        std::fprintf(stderr, "[load] disc frames %llu-%llu (%llu frames, %.1f MB, %.0f ms wall)\n", static_cast<unsigned long long>(burst_from),
                     static_cast<unsigned long long>(burst_last), static_cast<unsigned long long>(burst_last - burst_from + 1),
                     static_cast<double>(burst_sectors) * 2048.0 / 1048576.0, std::chrono::duration<double, std::milli>(burst_end_t - burst_t).count());
    }
}

void peer_gone_frames();

void vblank() {
    ++g_frames;
    time_frames();
    pace_frames();
    poke_frames();
    snap_frames();
    watch_frames();
    peer_gone_frames();
    load_frames();
    apply_presses();
    if (g_options.on_vblank) g_options.on_vblank();
    iop::sio2::flush_cards();
    if (g_options.trace_iop_modules && g_frames % 25 == 0) std::fprintf(stderr, "[frame] %llu prims %llu\n", static_cast<unsigned long long>(g_frames), static_cast<unsigned long long>(gs::primitives_drawn()));
    if (!g_options.frames_dir.empty() && g_options.frame_every && g_frames % g_options.frame_every == 0) dump_frame();
    hw::vblank_start();
    iop::vblank(true);
    schedule(hw::kEventVblankEnd + 100, now() + hblank_cycles() * 22u, [] { iop::vblank(false); });
    kernel::on_vblank_start();
    if (g_options.max_frames && g_frames >= g_options.max_frames) halt("frame limit reached");
    if (g_options.save_state_frame && g_frames == g_options.save_state_frame) g_state_request = true;
    schedule(hw::kEventVblank, now() + frame_cycles(), vblank);
}

void idle(Cpu &c) {
    std::uint64_t next = next_event_time();
    if (next == UINT64_MAX && iop::idle()) {
        halt("deadlock: every thread waits and no event is pending");
        return;
    }
    next = std::min(next, now() + kEeClock / 2000);
    g_base = std::max(now(), next);
    g_given = 0;
    c.budget = 0;
    run_due_events();
}

}

void save_context(const Cpu &c, ThreadContext &t) {
    std::memcpy(t.r, c.r, sizeof t.r);
    t.hi = c.hi;
    t.lo = c.lo;
    t.sa = c.sa;
    t.pc = c.pc & ~kUnwind;
    std::memcpy(t.f, c.f, sizeof t.f);
    t.facc = c.facc;
    t.fcr31 = c.fcr31;
}

void load_context(Cpu &c, const ThreadContext &t) {
    std::memcpy(c.r, t.r, sizeof t.r);
    c.hi = t.hi;
    c.lo = t.lo;
    c.sa = t.sa;
    c.pc = t.pc;
    std::memcpy(c.f, t.f, sizeof t.f);
    c.facc = t.facc;
    c.fcr31 = t.fcr31;
}

std::uint64_t now() { return g_base + static_cast<std::uint64_t>(g_given - g_cpu.budget); }

std::uint64_t hblank_cycles() { return frame_cycles() * 2u / (g_options.pal ? 625u : 525u); }

void set_budget(Cpu &c, std::int64_t cycles) {
    g_base = now();
    g_given = cycles;
    c.budget = static_cast<std::int32_t>(cycles);
}

void schedule(int event, std::uint64_t at, std::function<void()> action) {
    g_events.erase(std::remove_if(g_events.begin(), g_events.end(), [&](const Event &e) { return e.id == event; }), g_events.end());
    g_events.push_back({event, at, std::move(action)});
}

std::uint64_t scheduled_at(int event) {
    for (const Event &e : g_events)
        if (e.id == event) return e.at;
    return UINT64_MAX;
}

bool preempt(Cpu &c) {
    run_due_events();
    refill(c);
    return g_halted || g_state_request || kernel::must_unwind();
}

void halt(const std::string &reason) {
    if (!g_halted) {
        std::fprintf(stderr, "[ee] halted: %s\n", reason.c_str());
        // Debugging aid: PS2_HALT_DUMP=<file> saves EE main memory as it was when the run halted.
        if (const char *dump = std::getenv("PS2_HALT_DUMP"))
            std::ofstream(dump, std::ios::binary).write(reinterpret_cast<const char *>(g_ram), kRamSize);
    }
    g_halted = true;
    g_halt_reason = reason;
}

bool halted() { return g_halted; }

namespace {

constexpr char kStateMagic[8] = {'P', 'S', '2', 'S', 'T', 'A', 'T', 'E'};
constexpr std::uint32_t kStateVersion = 2;

std::function<void()> event_action(int id, std::uint64_t at) {
    if (id == hw::kEventVblank) return vblank;
    if (id == hw::kEventVblankEnd + 100) return [] { iop::vblank(false); };
    if (id >= hw::kEventCount) return kernel::alarm_action(id - hw::kEventCount);
    return hw::event_action(id, at);
}

std::atomic<std::uint32_t> g_vu_fence{0};

void sync_vu_thread() {
    if (!gs::queue::vu_active()) return;
    const std::uint32_t seen = g_vu_fence.load();
    gs::queue::vu().post(gs::queue::kVuFence, 0, 0, nullptr, 0);
    while (g_vu_fence.load() == seen) gs::queue::wait(g_vu_fence, seen);
}

void machine_state(state::Stream &s) {
    char magic[8];
    std::memcpy(magic, kStateMagic, sizeof magic);
    s.bytes(magic, sizeof magic);
    std::uint32_t version = kStateVersion;
    s.pod(version);
    if (std::memcmp(magic, kStateMagic, sizeof magic) != 0) throw std::runtime_error("not a state file");
    if (version != kStateVersion) throw std::runtime_error("state file from another version");
    s.tag("ee");
    s.pod(g_cpu);
    s.bytes(g_ram, kRamSize);
    s.bytes(g_spr, kScratchpadSize);
    s.pod(g_base);
    s.pod(g_given);
    s.pod(g_frames);
    s.pod(g_iop_synced);
    s.pod(g_options.pal);
    s.vec(g_events, [&s](Event &e) {
        s.pod(e.id);
        s.pod(e.at);
        if (s.loading()) e.action = event_action(e.id, e.at);
    });
    kernel::state(s);
    hw::state(s);
    dmac::state(s);
    sync_vu_thread();
    vif::state(s);
    vu::state(s);
    ipu::state(s);
    gs::state(s);
    gif::state(s);
    iop::state(s);
    sif::state(s);
    s.tag("end");
}

}

bool save_state(const std::string &path) {
    try {
        state::Writer w;
        machine_state(w);
        std::ofstream out(path, std::ios::binary);
        out.write(reinterpret_cast<const char *>(w.out.data()), static_cast<std::streamsize>(w.out.size()));
        if (!out) throw std::runtime_error("cannot write " + path);
        std::fprintf(stderr, "[state] saved frame %llu to %s (%zu KB)\n", static_cast<unsigned long long>(g_frames), path.c_str(), w.out.size() >> 10);
        return true;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "[state] cannot save: %s\n", e.what());
        return false;
    }
}

bool load_state(const std::uint8_t *data, std::size_t size) {
    try {
        state::Reader r(data, size);
        machine_state(r);
        g_resumed = true;
        std::fprintf(stderr, "[state] loaded frame %llu\n", static_cast<unsigned long long>(g_frames));
        return true;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "[state] cannot load: %s\n", e.what());
        return false;
    }
}

bool load_state(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        std::fprintf(stderr, "[state] cannot open %s\n", path.c_str());
        return false;
    }
    const std::vector<std::uint8_t> data{std::istreambuf_iterator<char>(in), {}};
    return load_state(data.data(), data.size());
}

std::uint64_t frame_count() { return g_frames; }

bool load_elf(const std::string &path, std::uint32_t &entry) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    const std::vector<std::uint8_t> data{std::istreambuf_iterator<char>(file), {}};
    if (data.size() < 52 || std::memcmp(data.data(), "\x7f" "ELF", 4) != 0) return false;
    const auto u32 = [&](std::size_t o) { std::uint32_t v; std::memcpy(&v, data.data() + o, 4); return v; };
    const auto u16 = [&](std::size_t o) { std::uint16_t v; std::memcpy(&v, data.data() + o, 2); return v; };
    entry = u32(24);
    const std::uint32_t phoff = u32(28);
    for (std::uint32_t i = 0; i < u16(44); ++i) {
        const std::size_t ph = phoff + i * u16(42);
        if (u32(ph) != 1) continue;
        const std::uint32_t offset = u32(ph + 4), vaddr = physical(u32(ph + 8)), filesz = u32(ph + 16), memsz = u32(ph + 20);
        if (vaddr + memsz > kRamSize || offset + filesz > data.size()) return false;
        std::memcpy(g_ram + vaddr, data.data() + offset, filesz);
        std::memset(g_ram + vaddr + filesz, 0, memsz - filesz);
    }
    return true;
}

void sync_iop() {
    const std::uint64_t t = now();
    if (t > g_iop_synced) {
        iop::run(t - g_iop_synced);
        g_iop_synced = t;
    }
    sif::pump();
}

void request_iop_sync() {
    if (g_cpu.budget > 64) set_budget(g_cpu, 64);
}

// Online play: the game drops a teammate it has not heard from for 20 s (File #1: float 20.0 at 0x235F50, read by
// the peer check at 0x1C7A00; File #2: 31.0 at 0x241280, check at 0x1F32C0). A player who loads the scenario slower
// (in the browser the disc data comes over the internet) sends nothing while loading, so the fast one gave up after
// ~20 s and went back to the room alone while the slow one stayed in NOW LOADING or in the opening cutscene
// (reoutbreak docs/notes/protocol.md 9, run lat/L3). Raised to 300 s; real disconnections are noticed by the
// server and the gateway. Only when the original value is there (data constant of the boot ELF, set before main).
// When the gateway says a teammate left for good (kk-peer-gone, reoutbreak docs/notes/protocol.md 6.11: the host
// dropping out left the others waiting 300 s for it, with no item pickups and frozen enemies), only that teammate is
// made to look silent for ages, so the game's own check drops it even with 300 s. The notice has a server tag the
// table does not hold, so the slot is the active one (not me) silent the longest, once it has been silent for the
// game's own timeout; the timeout itself stays at 300 s, so a third player with a short gap (reconnecting) is not
// dropped with it (OperatorFrosty, 23/10: lowering the global timeout for 40 s did that).
// Peer table (same code in both, the check at 0x1C76E0 / 0x1F2FA0): bytes +4 me, +5 slots, +6 state, +0xF players,
// float +0x1C clock; slot i at +0x3C*i: byte +0x4C active, float +0x54 last heard (the check drops a slot when
// clock - last > timeout and the state is not 7).
// Host migration is the game's own (reoutbreak docs/notes/protocol.md 6.15): dropping a slot makes the first slot
// left in the session list the host, and the scenario's netaq overlay (File #1 BIN_8, File #2 BIN_9, at 0x810000)
// takes the authority over (latch byte) the next frame. So the host that left is marked once it has stayed silent
// for kHostGoneSilent of game clock after the notice (it sends all the time) instead of 20 s, when it is also the
// slot silent the longest. Counted from the notice and not from its last packet: after a game over the table's
// times are not those of play (run D2: a notice for another member found a live host "silent 3.4 s" and the
// leaver at 0.9 s), and a live host speaks again within those seconds. Session object = *netaq pointer: +0x68 me,
// +0x6C "I am the host" byte, +0x70 list count, +0x74 first slot of the list (= host); only while the netaq
// code that reads it is there.
struct PeerTimeout {
    const char *serial;
    std::uint32_t addr, orig;  // timeout float
    std::uint32_t table;       // peer table
    std::uint32_t code[3][2];  // the check's own loads (address, word) that pin the layout above
    std::uint32_t session;     // netaq's pointer to the session object
    std::uint32_t latch;       // netaq's "took the host authority" byte
    std::uint32_t netaq[2][2]; // netaq's own call that reads the session (address, word)
    std::uint32_t hp;          // u16 HP, u16 max HP of the character in slot 0 (0 = unknown); slot i at +stride*i
    std::uint32_t stride;      // character struct size
    std::uint32_t own;         // byte with bit 0x08 on the own character while alive (from hp; 0 = no such check)
    std::uint32_t dead;        // byte with bit 0x10 when dead or zombified (from hp)
};
const PeerTimeout *g_peer_timeout = nullptr;
constexpr std::uint32_t kPeerTimeoutRaised = 0x43960000u;   // 300.0f
constexpr std::uint64_t kPeerGoneFrames = 60 * 60;          // give up on a notice after ~60 s
constexpr float kPeerGoneSilent = 20.0f;                    // the game's own File #1 timeout
constexpr float kPeerGoneAge = 100000.0f;                   // how far back the dropped slot's last time goes
constexpr float kHostGoneSilent = 5.0f;
int g_host_watch = -1;                 // host slot watched since the notice, its last-heard bits and the clock then
std::uint32_t g_host_watch_last = 0;
float g_host_watch_clock = 0.0f;
int g_peer_host_flag = -1, g_peer_latch = -1;
std::uint32_t g_peer_gone_seen = 0;
std::uint64_t g_peer_gone_until = 0;
std::uint8_t g_peer_gone_players = 0;
std::uint8_t g_peer_players = 0;
std::uint32_t g_peer_left_seen = 0;
// Own player out of the scenario while the session goes on (dead or zombified: results screen, then watching):
// it no longer arbitrates, and as host it freezes item pickups for everybody until EXIT (owner report 02/11).
// Character of slot i (File #1): u16 HP, u16 max HP at 0x4A6174 + 0x10E0 * i (public US cheat "Infinite Health"
// 0x4A6174 = 08FC08FC; stride from the 3 characters in runs hg/Z1-Z2). HP 0 is NOT death (the character crawls,
// still playing: run Z2, a at 75 %); dead or zombified sets bit 0x10 of +0x663 (File #1 offsets) (0x12 in runs hp/K5 offline "YOU
// DIED", hg/Z2/Z4/Z9 zombified and Z9 killed by zombies; 0x00 alive, downed or not); +0x65F has bit 0x08 on the own
// character while alive (it flickers). After kLeftFrames of it in an online scenario, the gateway is told once
// ({"type":"left-scenario"}, with its slot), and the kk lobby tells the others (REOGW-LEFT, protocol.md 6.17), whose
// runtime drops the slot at once. Run Z9: host dead -> new host on the others 0.1 s of game clock after the mark.
// File #2: the same struct 0x1C bytes longer before the flags: Kevin (slot 0) at 0x4C6350, stride 0x1100 (Mark
// 0x4C7450, Jim 0x4C8550 in the offline Wild Things route), float 1.0 at +0x680 (File #1 +0x664), virus +0x684;
// zombified with the virus at 0x00200000 (run hp/K7) +0x67F = 0x12 and HP 0.
// PS2_LEFT_TRACE=1 logs the fields every 600 frames.
constexpr int kLeftFrames = 60;
int g_own_dead_frames = 0;
bool g_left_sent = false, g_own_alive = false;  // g_own_alive: own character seen alive (no false start at load)

// Reliable game-data flush (docs stutter.md): the game queues its own 0x0F records (state of the own character)
// and sends the queue only when an accumulator of game time reaches a float in the ELF data: File #1 1.0 s at
// 0x235F60, File #2 0.5 s at 0x2412A0 (File #1 0x1C72F0; only a game-stream tuning message, 0x1F5E30, which
// nobody sends, changes it). The others then get 1-3 records once a second and walk the remote character to
// each one and stop ("two or three steps and it stops"), even at 0 ms. 0.0 sends the queue on the step it is
// filled (3-5 messages/s per player while moving, run ST2). PS2_NET_FLUSH=<seconds> sets another value,
// PS2_NET_FLUSH=orig keeps the game's.
void online_flush_interval() {
    struct Flush { const char *serial; std::uint32_t addr, orig; };
    static constexpr Flush kFlush[] = {{"SLUS_207.65", 0x235F60, 0x3F800000u}, {"SLUS_209.84", 0x2412A0, 0x3F000000u}};
    const char *env = std::getenv("PS2_NET_FLUSH");
    if (env && std::strcmp(env, "orig") == 0) return;
    const float secs = env ? static_cast<float>(std::atof(env)) : 0.0f;
    for (const auto &f : kFlush) {
        if (!disc::find(f.serial) || load<std::uint32_t>(g_ram + f.addr) != f.orig) continue;
        std::uint32_t w;
        std::memcpy(&w, &secs, sizeof w);
        store<std::uint32_t>(g_ram + f.addr, w);
        std::fprintf(stderr, "[net] %s: game data flushed every %.3f s (was %.1f s)\n", f.serial, secs,
                     f.orig == 0x3F800000u ? 1.0 : 0.5);
    }
}

void online_peer_timeout() {
    static constexpr PeerTimeout kPatches[] = {
        {"SLUS_207.65", 0x235F50, 0x41A00000, 0x335050, {{0x1C79E4, 0x90C4004C}, {0x1C79F4, 0xC421506C}, {0x1C7A00, 0xC4205F50}},
         0x8147B8, 0x815D25, {{0x8125E0, 0x0C071998}, {0x8125E4, 0x8C2447B8}}, 0x4A6174, 0x10E0, 0x65F, 0x663},
        {"SLUS_209.84", 0x241280, 0x41F80000, 0x367BB0, {{0x1F32A4, 0x90C4004C}, {0x1F32B4, 0xC4217BCC}, {0x1F32C0, 0xC4201280}},
         0x8151E8, 0x816125, {{0x812620, 0x0C07C7A4}, {0x812624, 0x8C2451E8}}, 0x4C6350, 0x1100, 0, 0x67F}};
    g_peer_timeout = nullptr;
    g_peer_gone_until = 0;
    g_peer_players = 0;
    g_peer_host_flag = g_peer_latch = -1;
    for (const auto &p : kPatches) {
        if (!disc::find(p.serial) || load<std::uint32_t>(g_ram + p.addr) != p.orig) continue;
        store<std::uint32_t>(g_ram + p.addr, kPeerTimeoutRaised);
        bool table = true;
        for (const auto &c : p.code) table = table && load<std::uint32_t>(g_ram + c[0]) == c[1];
        g_peer_timeout = table ? &p : nullptr;
        std::fprintf(stderr, "[net] %s: teammate timeout 300 s%s\n", p.serial, table ? "" : " (peer table not found)");
    }
    online_flush_interval();
}

namespace {
float load_float(std::uint32_t addr) {
    const std::uint32_t w = load<std::uint32_t>(g_ram + addr);
    float f;
    std::memcpy(&f, &w, sizeof f);
    return f;
}

void store_float(std::uint32_t addr, float f) {
    std::uint32_t w;
    std::memcpy(&w, &f, sizeof w);
    store<std::uint32_t>(g_ram + addr, w);
}

// The netaq session object (0 = netaq not loaded or no session).
std::uint32_t peer_session(const PeerTimeout &p) {
    for (const auto &c : p.netaq)
        if (load<std::uint32_t>(g_ram + c[0]) != c[1]) return 0;
    const std::uint32_t s = load<std::uint32_t>(g_ram + p.session);
    if (s < 0x100000 || s > 0x1FFFF00 || (s & 3)) return 0;
    const std::uint32_t n = load<std::uint32_t>(g_ram + s + 0x70);
    return n >= 1 && n <= 4 ? s : 0;
}

#ifndef __EMSCRIPTEN__
// Native test stand-in for the web page's tracker channel (web/shell.html trkNetTick, protocol.md 6.18), so two
// native clients can check the gateway + kk lobby path: PS2_NET_TRK=p1:stride:ch:st:vir:av:herb:bleed:vmax (hex,
// the page's TRK_GAMES offsets) sends the same {"type":"trk",...} once a second while in an online game. The
// teammates' kk-peer-trk messages are logged by net::text_message. Not in the browser build (the page does it).
void trk_test_frames(std::uint32_t session, unsigned me, unsigned players) {
    static std::uint32_t o[9];
    static const bool on = [] {
        const char *v = std::getenv("PS2_NET_TRK");
        return v && std::sscanf(v, "%x:%x:%x:%x:%x:%x:%x:%x:%x", &o[0], &o[1], &o[2], &o[3], &o[4], &o[5], &o[6], &o[7], &o[8]) == 9;
    }();
    if (!on || !session || players < 2 || me > 3 || g_frames % 60) return;
    const std::uint32_t b = o[0] + o[1] * me;
    const unsigned ch = g_ram[b + o[2]];
    if (!g_ram[b] || !g_ram[b + 1] || ch > 7) return;
    const std::uint32_t vmax = load<std::uint32_t>(g_ram + o[8] + 4 * ch), vir = load<std::uint32_t>(g_ram + b + o[4]);
    const unsigned virus = vmax ? static_cast<unsigned>(std::min(1000.0, std::round(vir * 1000.0 / vmax))) : 0;
    char text[160];
    std::snprintf(text, sizeof text, "{\"type\":\"trk\",\"slot\":%u,\"ch\":%u,\"st\":%u,\"virus\":%u,\"av\":%u,\"herb\":%u,\"bleed\":%u}", me, ch,
                  g_ram[b + o[3]], virus, load<std::uint16_t>(g_ram + b + o[5]), load<std::uint16_t>(g_ram + b + o[6]), load<std::uint16_t>(g_ram + b + o[7]));
    net::send_control(text);
    if (g_frames % 600 == 0) std::fprintf(stderr, "[net] trk test: sent %s\n", text);
}
#endif

void peer_gone_frames() {
    if (!g_peer_timeout) return;
    const PeerTimeout &p = *g_peer_timeout;
    const std::uint32_t t = p.table;
    const std::uint32_t session = peer_session(p);
#ifndef __EMSCRIPTEN__
    trk_test_frames(session, g_ram[t + 4], g_ram[t + 0xF]);
#endif
    if (session) {  // for the report log: when this game becomes the host
        const int flag = g_ram[session + 0x6C], latch = g_ram[p.latch];
        if (flag != g_peer_host_flag || latch != g_peer_latch) {
            if (g_peer_host_flag >= 0 && (flag != g_peer_host_flag || latch != g_peer_latch))
                std::fprintf(stderr, "[net] %s: host flag %d, netaq latch %d (slot %u host, clock %.1f s)\n", p.serial, flag, latch,
                             load<std::uint32_t>(g_ram + session + 0x74), static_cast<double>(load_float(t + 0x1C)));
            g_peer_host_flag = flag;
            g_peer_latch = latch;
        }
    } else {
        g_peer_host_flag = g_peer_latch = -1;
    }
    if (static const bool trace = std::getenv("PS2_LEFT_TRACE"); trace && g_frames % 600 == 0) {
        const std::uint32_t a = p.hp + p.stride * (g_ram[t + 4] & 3);
        std::fprintf(stderr, "[net] left trace: frame %llu session %x players %u me %u max %u own %02x dead %02x alive %d sent %d\n",
                     static_cast<unsigned long long>(g_frames), session, g_ram[t + 0xF], g_ram[t + 4], load<std::uint16_t>(g_ram + a + 2),
                     g_ram[a + p.own], g_ram[a + p.dead], g_own_alive, g_left_sent);
    }
    if (!session || !p.hp || g_ram[t + 0xF] < 2 || g_ram[t + 4] >= 4) {
        g_own_dead_frames = 0;  // (netaq is not resident for a moment between the death and the results screen: run Z6)
    } else {
        const unsigned me = g_ram[t + 4];
        const std::uint32_t a = p.hp + p.stride * me;
        const unsigned max = load<std::uint16_t>(g_ram + a + 2);
        // the own-character bit (+0x65F & 0x08) only while alive: on death that byte changes (0x08 -> 0x40, run Z4)
        const bool valid = max >= 500 && max <= 5000, dead = (g_ram[a + p.dead] & 0x10) != 0;
        if (valid && !dead && (!p.own || (g_ram[a + p.own] & 0x08))) g_own_alive = true, g_left_sent = false;  // alive: a new notice may follow
        g_own_dead_frames = g_own_alive && !g_left_sent && valid && dead ? g_own_dead_frames + 1 : 0;
        if (g_own_dead_frames >= kLeftFrames) {
            g_left_sent = true;
            char text[64];
            std::snprintf(text, sizeof text, "{\"type\":\"left-scenario\",\"slot\":%u}", me);
            net::send_control(text);
            std::fprintf(stderr, "[net] %s: own player out of the scenario (dead, slot %u%s, clock %.1f s): told the gateway\n", p.serial, me,
                         g_ram[session + 0x6C] ? ", host" : "", static_cast<double>(load_float(t + 0x1C)));
        }
    }
    if (const std::uint8_t players = g_ram[t + 0xF]; players != g_peer_players) {  // for the report log
        if (players < g_peer_players && players)
            std::fprintf(stderr, "[net] %s: the game dropped a teammate (%u -> %u players, clock %.1f s)\n", p.serial,
                         g_peer_players, players, static_cast<double>(load_float(t + 0x1C)));
        g_peer_players = players;
    }
    if (const std::uint32_t n = net::peer_left_notices(); n != g_peer_left_seen) {
        // A teammate left the scenario while its session goes on (dead or zombified, on the results screen) and said
        // which slot it had (protocol.md 6.17): it keeps sending until it presses EXIT, so silence cannot find it.
        // Its slot is marked at once; if it was the host the game elects a new one the same frame.
        g_peer_left_seen = n;
        const unsigned slot = static_cast<unsigned>(net::peer_left_slot()), me = g_ram[t + 4];
        const std::uint32_t s = t + 0x3C * slot;
        if (slot < std::min<unsigned>(g_ram[t + 5], 4) && slot != me && g_ram[s + 0x4C] == 1) {
            const float clock = load_float(t + 0x1C);
            store_float(s + 0x54, clock - kPeerGoneAge);
            std::fprintf(stderr, "[net] %s: teammate in slot %u left the scenario: marked for the game's own drop (clock %.1f s)\n",
                         p.serial, slot, static_cast<double>(clock));
        } else {
            std::fprintf(stderr, "[net] %s: teammate in slot %u left the scenario: not an active teammate here (me %u)\n", p.serial,
                         slot, me);
        }
    }
    if (const std::uint32_t n = net::peer_gone_notices(); n != g_peer_gone_seen) {
        g_peer_gone_seen = n;
        if (!g_peer_gone_until) g_peer_gone_players = g_ram[t + 0xF];
        g_peer_gone_until = g_frames + kPeerGoneFrames;
        g_host_watch = -1;
        std::fprintf(stderr, "[net] %s: teammate gone, looking for its slot (%u players)\n", p.serial, g_ram[t + 0xF]);
    }
    if (!g_peer_gone_until) return;
    if (g_ram[t + 0xF] < g_peer_gone_players) {  // the game dropped someone by itself
        g_peer_gone_until = 0;
        std::fprintf(stderr, "[net] %s: teammate gone, already dropped by the game (%u players)\n", p.serial, g_ram[t + 0xF]);
        return;
    }
    const unsigned me = g_ram[t + 4], slots = std::min<unsigned>(g_ram[t + 5], 4);
    const float clock = load_float(t + 0x1C);
    int pick = -1;
    float silent = 0.0f, quiet[4] = {};
    for (unsigned i = 0; i < slots; ++i) {
        const std::uint32_t s = t + 0x3C * i;
        if (i == me || g_ram[s + 0x4C] != 1) continue;
        quiet[i] = clock - load_float(s + 0x54);
        if (quiet[i] > silent) { silent = quiet[i]; pick = static_cast<int>(i); }
    }
    bool host = false;
    if (session && pick >= 0) {
        const std::uint32_t h = load<std::uint32_t>(g_ram + session + 0x74), mine = load<std::uint32_t>(g_ram + session + 0x68);
        const std::uint32_t last = load<std::uint32_t>(g_ram + t + 0x3C * pick + 0x54);
        if (mine == me && h == static_cast<std::uint32_t>(pick)) {
            if (g_host_watch != pick || g_host_watch_last != last || clock < g_host_watch_clock) {
                g_host_watch = pick;  // (re)start: the host spoke, or a new candidate
                g_host_watch_last = last;
                g_host_watch_clock = clock;
            }
            host = clock - g_host_watch_clock >= kHostGoneSilent;
        } else {
            g_host_watch = -1;
        }
    }
    if (pick >= 0 && (host || silent >= kPeerGoneSilent) && silent < kPeerGoneAge) {
        store_float(t + 0x3C * pick + 0x54, clock - kPeerGoneAge);
        g_peer_gone_until = 0;
        char others[64] = "";
        for (unsigned i = 0, n = 0; i < slots && n < sizeof others; ++i)
            if (i != me && static_cast<int>(i) != pick && g_ram[t + 0x3C * i + 0x4C] == 1)
                n += std::snprintf(others + n, sizeof others - n, " slot %u %.1f s", i, static_cast<double>(quiet[i]));
        std::fprintf(stderr, "[net] %s: teammate gone: slot %d%s (silent %.1f s) marked for the game's own drop; others:%s\n",
                     p.serial, pick, host ? " (host)" : "", static_cast<double>(silent), others[0] ? others : " none");
    } else if (g_frames >= g_peer_gone_until) {
        g_peer_gone_until = 0;
        std::fprintf(stderr, "[net] %s: teammate gone, no slot silent for %.0f s, left to the 300 s timeout\n", p.serial,
                     static_cast<double>(kPeerGoneSilent));
    }
}
}

// File #1 (SLUS-20765): netwk.bin registers callbacks (0x1DAB54) in a table that the ELF function 0x1DFDA8 keeps
// calling through jalr after game.bin has replaced netwk.bin at 0x570000; the stale pointer lands in the middle of
// game code and crashes the scenario (reoutbreak docs/notes/runtime.md 7). Every one of its 68 jalr skips empty
// slots and ignores the return value, so dispatch() treats a callback of a non-resident netwk as an empty slot.
// Only when the original caller is there (prologue and the jalr at 0x1E0214).
// File #2 (SLUS-20984) has the same dispatcher at 0x165340 (same size, 68 jalr, each after a null-slot test and
// followed by a branch to the epilogue) and register function 0x1600EC (13 calls in netwk.bin, none in game.bin,
// both at 0x58D000). There a stale callback ran game.bin mid-function (0x59B250 sets bits 0x201 of a scenario
// object): scenario events fired by themselves, and the bad returns went to 0x100 (reports 22-23/10).
void stale_netwk_callbacks() {
    if (disc::find("SLUS_207.65") && load<std::uint32_t>(g_ram + 0x1DFDA8) == 0x27BDFFB0u &&
        load<std::uint32_t>(g_ram + 0x1E0214) == 0x0060F809u && load<std::uint32_t>(g_ram + 0x1E1610) == 0x03E00008u)
        guard_overlay_callbacks(0x1DFDA8, 0x1E1618 - 0x1DFDA8, "netwk", "SLUS_207.65");
    else if (disc::find("SLUS_209.84") && load<std::uint32_t>(g_ram + 0x165340) == 0x27BDFFB0u &&
             load<std::uint32_t>(g_ram + 0x1657AC) == 0x0060F809u && load<std::uint32_t>(g_ram + 0x166BA8) == 0x03E00008u)
        guard_overlay_callbacks(0x165340, 0x166BB0 - 0x165340, "netwk", "SLUS_209.84");
}

void boot(std::uint32_t entry, const std::vector<std::string> &args) {
    hw::reset();
    kernel::reset();
    if (!g_options.disc_path.empty() && !disc::open(g_options.disc_path))
        std::fprintf(stderr, "[ee] cannot open disc image %s\n", g_options.disc_path.c_str());
    online_peer_timeout();
    stale_netwk_callbacks();
    iop::set_trace(g_options.trace_iop_modules, g_options.trace_iop_hle);
    iop::reset();
    std::vector<std::string> guest_args = args;
    if (!guest_args.empty() && guest_args[0].find(':') == std::string::npos) {
        std::string name = guest_args[0].substr(guest_args[0].find_last_of("/\\") + 1);
        for (char &ch : name) ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
        guest_args[0] = "cdrom0:\\" + name + ";1";
    }
    std::uint32_t argv = 0x00000800u, payload = argv + 4u * static_cast<std::uint32_t>(guest_args.size() + 1);
    for (std::size_t i = 0; i < guest_args.size(); ++i) {
        store<std::uint32_t>(g_ram + argv + 4 * i, payload);
        std::memcpy(g_ram + payload, guest_args[i].c_str(), guest_args[i].size() + 1);
        payload += static_cast<std::uint32_t>(guest_args[i].size() + 1);
    }
    store<std::uint32_t>(g_ram + argv + 4 * guest_args.size(), 0);
    kernel::start_main_thread(entry, 0, static_cast<std::uint32_t>(guest_args.size()), argv);
    schedule(hw::kEventVblank, frame_cycles(), vblank);
}

int run() {
    Cpu &c = g_cpu;
    if (!g_resumed) set_budget(c, kMaxSlice);
    while (!g_halted) {
        if (g_state_request) {
            g_state_request = false;
            save_state(g_options.save_state_path);
        }
        c.pc &= ~kUnwind;
        run_due_events();
        kernel::service(c);
        if (g_halted) break;
        if (c.pc == kIdle) {
            if (kernel::thread_returned(c) && (c.pc & ~kUnwind) != kIdle) continue;
            c.pc &= ~kUnwind;
            idle(c);
            continue;
        }
        if (c.budget <= 0) refill(c);
        dispatch(c, 0, 0);
    }
    kernel::dump();
    iop::dump();
    sif::dump();
    if (const char *peek = std::getenv("PS2_PEEK")) {
        std::uint32_t at = 0, len = 0;
        if (std::sscanf(peek, "%x,%x", &at, &len) == 2)
            for (std::uint32_t i = 0; i < len; i += 16) {
                std::fprintf(stderr, "[peek] %08X:", at + i);
                for (std::uint32_t j = 0; j < 16 && i + j < len; j += 4) std::fprintf(stderr, " %08X", ld32(at + i + j));
                std::fprintf(stderr, "\n");
            }
    }
    if (const char *path = std::getenv("PS2_DUMP")) {
        if (std::FILE *f = std::fopen(path, "wb")) {
            std::fwrite(g_ram, 1, kRamSize, f);
            std::fwrite(iop::ram_ptr(0), 1, iop::kRamSize, f);
            std::fwrite(iop::spu2::ram(), 2, 1u << 20, f);
            std::fclose(f);
        }
    }
    gs::stats();
    ipu::stats();
    if (const char *path = std::getenv("PS2_DUMP_AUDIO")) iop::spu2::dump_audio(path);
    if (std::getenv("PS2_TRACE_SPU2")) iop::spu2::dump_voices();
    dmac::stats();
    gif::stats();
    vif::stats();
    std::fprintf(stderr, "[ee] stopped after %llu frames, %llu cycles: %s\n",
                 static_cast<unsigned long long>(g_frames), static_cast<unsigned long long>(now()), g_halt_reason.c_str());
    return 0;
}

bool display_frame(std::vector<std::uint32_t> &rgba, std::uint32_t &width, std::uint32_t &height) { return gs::display(rgba, width, height); }
bool attach_window(const WindowTarget &t) { return gs::attach_window(static_cast<int>(t.kind), t.display, t.window, t.surface, t.selector, t.width, t.height); }
void resize_window(std::uint32_t width, std::uint32_t height) { gs::resize_window(width, height); }
bool present_frame() { return gs::present(); }
void init_graphics() { gs::init(); }
void start_render_thread() { gs::start_thread(); }
void start_vu_thread() { gs::queue::vu().start(32u << 20); }

std::atomic<double> g_vu_idle_ms{0.0};

double vu_thread_idle_ms() { return g_vu_idle_ms.load(); }
std::uint64_t vu1_interpreted_calls() { return vu::interpreted_calls(); }

void run_vu_thread() {
    gs::queue::role() = gs::queue::Role::Vu;
    gs::queue::Ring &in = gs::queue::vu();
    for (;;) {
        gs::queue::Header h;
        const std::uint8_t *payload = nullptr;
        while (in.peek(h, payload)) {
            switch (h.type) {
            case gs::queue::kVif1: vif::transfer(1, payload, h.arg0); break;
            case gs::queue::kVif1Write: vif::write(1, h.arg0, h.arg1); break;
            case gs::queue::kVuReset:
                vif::reset_vu_side();
                vu::reset();
                break;
            case gs::queue::kVuFence:
                g_vu_fence.fetch_add(1);
                gs::queue::notify(g_vu_fence);
                break;
            default: gs::queue::render().post(static_cast<gs::queue::Type>(h.type), h.arg0, h.arg1, payload, h.bytes); break;
            }
            in.pop(h);
        }
        const auto start = std::chrono::steady_clock::now();
        in.wait_for_work(50);
        g_vu_idle_ms.store(g_vu_idle_ms.load() + std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    }
}
void post_present() { gs::post_present(); }

Rendered render_pending(bool surface, const std::function<void(const std::uint32_t *, std::uint32_t, std::uint32_t)> &copy,
                        const std::function<void()> &before_present, double budget_ms) {
    static std::vector<std::uint32_t> frame;
    return gs::render_pending([&](const gs::DisplayRegs &regs) {
        if (before_present) before_present();
        if (surface) {
            gs::present(regs);
            return;
        }
        std::uint32_t width = 0, height = 0;
        if (copy && gs::copy_frame(regs, frame, width, height)) copy(frame.data(), width, height);
    }, budget_ms);
}

void wait_render_work(int timeout_ms) { gs::queue::render().wait_for_work(timeout_ms); }
void wake_render_thread() { gs::queue::render().wake(); }
bool copy_frame(std::vector<std::uint32_t> &rgba, std::uint32_t &width, std::uint32_t &height) { return gs::copy_frame(rgba, width, height); }

bool parse_press(const std::string &spec, Press &press) {
    static const char *const kNames[16] = {"select", "l3", "r3", "start", "up", "right", "down", "left",
                                           "l2", "r2", "l1", "r1", "triangle", "circle", "cross", "square"};
    const auto colon = spec.find(':');
    if (colon == std::string::npos) return false;
    press.frame = std::strtoull(spec.c_str(), nullptr, 10);
    std::string rest = spec.substr(colon + 1);
    press.frames = 6;
    press.sync = Press::kNone;
    if (rest == "sync:black" || rest == "sync:visible") {
        press.sync = rest == "sync:black" ? Press::kBlack : Press::kVisible;
        press.frames = 0;
        press.buttons = 0;
        return true;
    }
    const auto second = rest.find(':');
    if (second != std::string::npos) {
        press.frames = static_cast<std::uint32_t>(std::strtoul(rest.c_str() + second + 1, nullptr, 10));
        rest = rest.substr(0, second);
    }
    press.buttons = 0;
    std::size_t start = 0;
    while (start <= rest.size()) {
        const auto plus = rest.find('+', start);
        const std::string name = rest.substr(start, plus == std::string::npos ? std::string::npos : plus - start);
        int bit = -1;
        for (int i = 0; i < 16; ++i)
            if (name == kNames[i]) bit = i;
        if (bit < 0) return false;
        press.buttons |= static_cast<std::uint16_t>(1u << bit);
        if (plus == std::string::npos) break;
        start = plus + 1;
    }
    return true;
}

double gpu_busy_ms() { return gs::gpu_busy_ms(); }
std::uint32_t present_failures() { return gs::present_failures(); }

#ifdef __EMSCRIPTEN__
EM_ASYNC_JS(void, ps2_browser_yield, (), {
    if (!Module.yieldPort) {
        const channel = new MessageChannel();
        Module.yieldWaiters = [];
        channel.port1.onmessage = () => Module.yieldWaiters.shift()();
        Module.yieldPort = channel.port2;
    }
    await new Promise((resolve) => { Module.yieldWaiters.push(resolve); Module.yieldPort.postMessage(0); });
});
void browser_yield() { ps2_browser_yield(); }
#else
void browser_yield() {}
#endif

std::uint64_t frames_run() { return g_frames; }
std::uint64_t primitives_drawn() { return gs::primitives_drawn(); }
void set_pad_buttons(std::uint16_t pressed, int pad) { iop::sio2::set_buttons(pad, pressed); }
void set_pad_axis(int axis, std::uint8_t value, int pad) { iop::sio2::set_axis(pad, axis, value); }
void set_pad_pressure(int button, std::uint8_t value, int pad) { iop::sio2::set_pressure(pad, button, value); }
void set_pad_connected(int pad, bool connected) { iop::sio2::set_connected(pad, connected); }
std::uint16_t pad_rumble(int pad) { return iop::sio2::rumble(pad); }
void set_audio_sink(std::function<void(const std::int16_t *, std::size_t)> sink) { iop::spu2::set_sink(std::move(sink)); }

}
