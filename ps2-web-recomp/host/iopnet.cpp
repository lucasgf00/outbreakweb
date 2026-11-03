// iopnet: runs IOP modules (IRX) on the emulated IOP without any EE program,
// to bring up the network adapter and the game's network stack in isolation.
//
//   iopnet [--net ws://127.0.0.1:8765/eth] [--ms 8000] [--trace]
//          --module DEV9.IRX --module SNAVETCP.IRX ... [--module "SMAP.IRX:arg1 arg2"]
//          [--call LIB:INDEX[:a0,a1,a2,a3]@MS]... (LIB "addr": INDEX is a function address)
//
// Each module is loaded and started in order (like LoadModule from the EE), then
// the IOP keeps running for --ms milliseconds of emulated time with vblank ticks.
// --call runs an exported function of a loaded library from a fresh IOP thread
// at the given time, which is how the EE-side calls of the network library are
// stood in for here.

#include "iop/iop.hpp"
#include "iop/kernel.hpp"

#include "ps2/system.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

struct Module {
    std::string path;
    std::string args; // NUL-separated
};

struct Call {
    std::string library;
    std::uint32_t index = 0;
    std::uint32_t a[4] = {};
    std::uint64_t at_ms = 0;
    bool done = false;
};

int usage() {
    std::fprintf(stderr, "usage: iopnet [--net url] [--ms n] [--trace] --module file[:args]... [--call lib:index[:a0,a1,a2,a3]@ms]...\n");
    return 2;
}

std::uint64_t g_ms = 0;

void step_ms() {
    static std::uint64_t vblank_due = 0;
    ps2::iop::run(ps2::kEeClock / 1000);
    ++g_ms;
    if (g_ms * 1000 >= vblank_due) {
        ps2::iop::vblank(true);
        ps2::iop::vblank(false);
        vblank_due += 16683;
    }
}

// Starts an IOP thread that calls an exported library function and exits.
void call_export(const Call &c) {
    using namespace ps2::iop;
    const std::uint32_t target = c.library == "addr" ? c.index : kernel::library_export(c.library, c.index);
    if (!target) {
        std::fprintf(stderr, "[iopnet] %s #%u is not exported by a loaded module\n", c.library.c_str(), c.index);
        return;
    }
    const std::uint32_t entry = kernel::alloc(32);
    write32(entry + 0, 0x03E08021u);                                  // move s0, ra (the thread exit stub)
    write32(entry + 4, 0x0C000000u | ((target >> 2) & 0x03FFFFFFu)); // jal target
    write32(entry + 8, 0);
    write32(entry + 12, 0x02000008u);                                 // jr s0
    write32(entry + 16, 0);
    const int tid = kernel::create_thread(entry, 0x2000, 32, 0, 0x02000000u, 0);
    const std::string library = c.library;
    const std::uint32_t index = c.index;
    kernel::thread(tid)->on_exit = [tid, library, index]() {
        std::fprintf(stderr, "[iopnet] %s #%u returned %d\n", library.c_str(), index, static_cast<int>(kernel::thread(tid)->context.r[2]));
    };
    kernel::start_thread(tid, c.a[0], c.a[1]);
    kernel::thread(tid)->context.r[6] = c.a[2];
    kernel::thread(tid)->context.r[7] = c.a[3];
}

}

int main(int argc, char **argv) {
    std::vector<Module> modules;
    std::vector<Call> calls;
    std::uint64_t total_ms = 8000;
    bool trace = false;
    std::string dump_path;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--module" && i + 1 < argc) {
            std::string spec = argv[++i];
            Module m;
            const auto colon = spec.find(':');
            m.path = spec.substr(0, colon);
            if (colon != std::string::npos) {
                m.args = spec.substr(colon + 1);
                for (char &ch : m.args)
                    if (ch == ' ') ch = '\0';
            }
            modules.push_back(m);
        } else if (a == "--call" && i + 1 < argc) {
            Call c;
            std::string spec = argv[++i];
            const auto at = spec.find('@');
            if (at != std::string::npos) {
                c.at_ms = std::strtoull(spec.c_str() + at + 1, nullptr, 10);
                spec = spec.substr(0, at);
            }
            const auto c1 = spec.find(':');
            if (c1 == std::string::npos) return usage();
            c.library = spec.substr(0, c1);
            const auto c2 = spec.find(':', c1 + 1);
            c.index = static_cast<std::uint32_t>(std::strtoul(spec.c_str() + c1 + 1, nullptr, 0));
            if (c2 != std::string::npos) {
                std::sscanf(spec.c_str() + c2 + 1, "%i,%i,%i,%i", reinterpret_cast<int *>(&c.a[0]), reinterpret_cast<int *>(&c.a[1]),
                            reinterpret_cast<int *>(&c.a[2]), reinterpret_cast<int *>(&c.a[3]));
            }
            calls.push_back(c);
        } else if (a == "--ms" && i + 1 < argc) {
            total_ms = std::strtoull(argv[++i], nullptr, 10);
        } else if (a == "--net" && i + 1 < argc) {
            setenv("PS2_NET", argv[++i], 1);
        } else if (a == "--dump" && i + 1 < argc) {
            dump_path = argv[++i];
        } else if (a == "--memcard" && i + 1 < argc) {
            ps2::g_options.memcard_path = argv[++i];
        } else if (a == "--trace") {
            trace = true;
        } else {
            return usage();
        }
    }
    if (modules.empty()) return usage();

    ps2::iop::set_trace(trace, false);
    ps2::iop::reset();
    for (const Module &m : modules) {
        std::ifstream in(m.path, std::ios::binary);
        if (!in) {
            std::fprintf(stderr, "[iopnet] cannot read %s\n", m.path.c_str());
            return 1;
        }
        std::vector<std::uint8_t> data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        bool done = false;
        int result = 0;
        const std::string name = m.path.substr(m.path.find_last_of('/') + 1);
        const int id = ps2::iop::load_module_buffer(data, "host:" + name, m.args, [&](int, int r) {
            done = true;
            result = r;
        });
        if (id < 0) {
            std::fprintf(stderr, "[iopnet] %s did not load\n", name.c_str());
            return 1;
        }
        const std::uint64_t until = g_ms + 5000;
        while (!done && g_ms < until) step_ms();
        std::fprintf(stderr, "[iopnet] %s started at %llu ms: result %d (%s)\n", name.c_str(), static_cast<unsigned long long>(g_ms), result,
                     !done ? "still running" : (result & 3) == 0 ? "resident" : (result & 3) == 1 ? "no resident end" : "removable");
    }
    while (g_ms < total_ms) {
        for (Call &c : calls)
            if (!c.done && g_ms >= c.at_ms) {
                c.done = true;
                call_export(c);
            }
        step_ms();
    }
    ps2::iop::dump();
    if (!dump_path.empty()) {
        std::FILE *f = std::fopen(dump_path.c_str(), "wb");
        if (f) {
            std::fwrite(ps2::iop::ram_ptr(0), 1, ps2::iop::kRamSize, f);
            std::fclose(f);
        }
    }
    return 0;
}
