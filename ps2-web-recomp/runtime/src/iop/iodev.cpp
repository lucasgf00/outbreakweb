// Guest file system drivers for the HLE ioman.
//
// IOP modules register devices with AddDrv (DEV9.IRX adds "dev9x", MCMAN adds
// "mc", ...). The HLE ioman used to refuse them, which is harmless for most games
// but makes DEV9.IRX unload itself and keeps NETCNF.IRX from reading the network
// configuration on the memory card. This file accepts the registration and routes
// ioman calls on those devices to the driver's own operation table.
//
// Driver operations can block (the memory card driver waits for SIO2), so they
// are not run as nested guest calls: the HLE function turns into a tail call into
// the driver, running on the caller's thread, and a return stub converts the
// result on the way back.
//
// Only active when networking is configured, so the offline path is unchanged.

#include "io.hpp"
#include "kernel.hpp"

#include "../net/backend.hpp"

#include <cstdio>
#include <cstdlib>
#include <deque>
#include <map>
#include <string>

namespace ps2::iop::kernel {

namespace {

constexpr int kFirstFd = 100, kLastFd = 164;
constexpr std::uint32_t kFsExt = 0x10000000u; // IOP_DT_FSEXT: iomanX operation table
constexpr int kErrNoDevice = -19, kErrBadFd = -9, kErrNoSys = -48;

enum Op : std::uint32_t {
    kInit = 0, kDeinit, kFormat, kOpen, kClose, kRead, kWrite, kLseek, kIoctl, kRemove, kMkdir, kRmdir, kDopen, kDclose, kDread,
    kGetstat, kChstat, kRename, kChdir, kSync, kMount, kUmount, kLseek64, kDevctl,
};

struct Device {
    std::uint32_t desc = 0; // iop_device_t *
    bool ext = false;
};

struct File {
    std::uint32_t file = 0; // iop_file_t * in IOP RAM
    std::string device;
};

struct Return {
    std::uint32_t ra, sp;
    std::function<std::uint32_t(std::uint32_t)> after;
};

std::map<std::string, Device> g_devices;
std::map<int, File> g_open;
std::map<int, std::deque<Return>> g_returns; // per thread
std::uint32_t g_return_stub = 0;

std::uint32_t arg(const Cpu &c, int i) { return i < 4 ? c.r[4 + i] : read32(c.r[29] + 16u + static_cast<std::uint32_t>(i - 4) * 4u); }

void def(std::uint32_t index, const char *name, std::function<void(Cpu &)> body) {
    register_hle("ioman", index, [name, body = std::move(body)](Cpu &c) {
        trace_hle("ioman", name, c);
        body(c);
    });
}

int thread_key() {
    const Thread *t = current();
    return t ? t->id : 0;
}

// Continue the current HLE call inside guest function fn. c.pc already holds the
// caller's return address (the HLE opcode sets it before dispatching).
void tail_call(Cpu &c, std::uint32_t fn, std::initializer_list<std::uint32_t> args, std::function<std::uint32_t(std::uint32_t)> after = {}) {
    g_returns[thread_key()].push_back(Return{c.pc, c.r[29], std::move(after)});
    const std::uint32_t sp = (c.r[29] - 48u) & ~7u;
    unsigned i = 0;
    for (std::uint32_t a : args) {
        if (i < 4) c.r[4 + i] = a;
        else write32(sp + 16u + (i - 4) * 4u, a);
        ++i;
    }
    c.r[29] = sp;
    c.r[31] = g_return_stub;
    c.pc = fn;
    c.npc = fn + 4;
}

void finish(Cpu &c) {
    auto &stack = g_returns[thread_key()];
    if (stack.empty()) {
        fault("ioman driver return without a pending call");
        return;
    }
    Return r = std::move(stack.back());
    stack.pop_back();
    if (r.after) c.r[2] = r.after(c.r[2]);
    c.r[29] = r.sp;
    c.pc = r.ra;
    c.npc = r.ra + 4;
}

std::uint32_t op(const Device &d, Op which) {
    const std::uint32_t ops = read32(d.desc + 16);
    return ops ? read32(ops + which * 4u) : 0u;
}

// "mc0:/BWNETCNF/BWNETCNF" -> device "mc", unit 0, name offset after the colon.
bool split(std::uint32_t path, std::string &device, int &unit, std::uint32_t &name) {
    const std::string s = ram_string(path, 1024);
    const auto colon = s.find(':');
    if (colon == std::string::npos) return false;
    std::string d = s.substr(0, colon);
    std::size_t digits = d.size();
    while (digits > 0 && std::isdigit(static_cast<unsigned char>(d[digits - 1]))) --digits;
    unit = digits < d.size() ? std::atoi(d.c_str() + digits) : 0;
    device = d.substr(0, digits);
    name = path + static_cast<std::uint32_t>(colon) + 1u;
    return g_devices.count(device) != 0;
}

std::uint32_t new_file(const Device &d, int unit, std::uint32_t mode) {
    const std::uint32_t f = alloc(32);
    if (!f) return 0;
    for (std::uint32_t i = 0; i < 32; i += 4) write32(f + i, 0);
    write32(f + 0, mode);
    write32(f + 4, static_cast<std::uint32_t>(unit));
    write32(f + 8, d.desc);
    return f;
}

int new_fd() {
    for (int fd = kFirstFd; fd < kLastFd; ++fd)
        if (!g_open.count(fd)) return fd;
    return -24;
}

const File *guest_file(int fd) {
    const auto it = g_open.find(fd);
    return it == g_open.end() ? nullptr : &it->second;
}

// open/dopen: allocate the file, call the driver, hand back our descriptor.
void open_like(Cpu &c, Op which, std::uint32_t path, std::uint32_t flags, std::uint32_t mode) {
    std::string device;
    int unit = 0;
    std::uint32_t name = 0;
    split(path, device, unit, name);
    const Device &d = g_devices[device];
    const std::uint32_t fn = op(d, which);
    const int fd = new_fd();
    const std::uint32_t file = fd < 0 ? 0 : new_file(d, unit, which == kDopen ? 8u : flags);
    if (!fn || !file) {
        if (file) free(file);
        c.r[2] = static_cast<std::uint32_t>(fn ? -24 : kErrNoSys);
        return;
    }
    g_open[fd] = File{file, device};
    if (g_trace_hle) std::fprintf(stderr, "[iodev] %s %s -> driver 0x%08X\n", which == kOpen ? "open" : "dopen", ram_string(path).c_str(), fn);
    tail_call(c, fn, {file, name, flags, mode}, [fd](std::uint32_t result) {
        if (static_cast<std::int32_t>(result) < 0) {
            free(g_open[fd].file);
            g_open.erase(fd);
            return result;
        }
        return static_cast<std::uint32_t>(fd);
    });
}

// Calls on an open descriptor: driver(file, a1, a2, ...).
void on_fd(Cpu &c, Op which, std::initializer_list<std::uint32_t> rest, bool release = false) {
    const int fd = static_cast<int>(c.r[4]);
    const File *f = guest_file(fd);
    const std::uint32_t fn = f ? op(g_devices[f->device], which) : 0u;
    if (!fn) {
        c.r[2] = static_cast<std::uint32_t>(f ? kErrNoSys : kErrBadFd);
        return;
    }
    std::uint32_t args[8] = {f->file};
    unsigned n = 1;
    for (std::uint32_t a : rest) args[n++] = a;
    std::function<std::uint32_t(std::uint32_t)> after;
    if (release)
        after = [fd](std::uint32_t result) {
            if (g_open.count(fd)) {
                free(g_open[fd].file);
                g_open.erase(fd);
            }
            return result;
        };
    switch (n) {
    case 1: tail_call(c, fn, {args[0]}, after); break;
    case 2: tail_call(c, fn, {args[0], args[1]}, after); break;
    case 3: tail_call(c, fn, {args[0], args[1], args[2]}, after); break;
    default: tail_call(c, fn, {args[0], args[1], args[2], args[3]}, after); break;
    }
}

// Calls by path: driver(temp file, name, a2, ...). Up to 7 driver arguments.
bool on_path(Cpu &c, Op which, std::uint32_t path, std::initializer_list<std::uint32_t> rest) {
    std::string device;
    int unit = 0;
    std::uint32_t name = 0;
    if (!split(path, device, unit, name)) return false;
    const Device &d = g_devices[device];
    const bool ext_only = which >= kRename;
    const std::uint32_t fn = ext_only && !d.ext ? 0u : op(d, which);
    const std::uint32_t file = fn ? new_file(d, unit, 0) : 0u;
    if (!file) {
        c.r[2] = static_cast<std::uint32_t>(kErrNoSys);
        return true;
    }
    std::uint32_t a[7] = {file, name};
    unsigned n = 2;
    for (std::uint32_t v : rest) a[n++] = v;
    auto after = [file](std::uint32_t result) {
        free(file);
        return result;
    };
    switch (n) {
    case 2: tail_call(c, fn, {a[0], a[1]}, after); break;
    case 3: tail_call(c, fn, {a[0], a[1], a[2]}, after); break;
    case 4: tail_call(c, fn, {a[0], a[1], a[2], a[3]}, after); break;
    default: tail_call(c, fn, {a[0], a[1], a[2], a[3], a[4], a[5], a[6]}, after); break;
    }
    return true;
}

}

void install_iodev() {
    g_devices.clear();
    g_open.clear();
    g_returns.clear();
    if (net::configured_url().empty() && !std::getenv("PS2_IODEV")) return;
    g_return_stub = hle_stub(finish);

    def(20, "AddDrv", [](Cpu &c) {
        const std::uint32_t desc = arg(c, 0);
        const std::string name = ram_string(read32(desc));
        const std::uint32_t type = read32(desc + 4);
        g_devices[name] = Device{desc, (type & kFsExt) != 0};
        std::fprintf(stderr, "[iodev] driver \"%s\" registered (%s, type 0x%X)\n", name.c_str(), ram_string(read32(desc + 12)).c_str(), type);
        const std::uint32_t init = op(g_devices[name], kInit);
        if (init) tail_call(c, init, {desc}, [](std::uint32_t) { return 0u; });
        else c.r[2] = 0;
    });
    def(21, "DelDrv", [](Cpu &c) {
        const std::string name = ram_string(arg(c, 0));
        c.r[2] = g_devices.erase(name) ? 0u : static_cast<std::uint32_t>(-1);
    });

    def(4, "open", [](Cpu &c) {
        std::string device;
        int unit;
        std::uint32_t name;
        if (split(arg(c, 0), device, unit, name)) {
            open_like(c, kOpen, arg(c, 0), arg(c, 1), arg(c, 2));
            return;
        }
        c.r[2] = static_cast<std::uint32_t>(io::open(ram_string(arg(c, 0)), arg(c, 1)));
    });
    def(5, "close", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) on_fd(c, kClose, {}, true);
        else c.r[2] = static_cast<std::uint32_t>(io::close(static_cast<int>(arg(c, 0))));
    });
    def(6, "read", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) {
            on_fd(c, kRead, {arg(c, 1), arg(c, 2)});
            return;
        }
        std::vector<std::uint8_t> buffer(arg(c, 2));
        const int n = io::read(static_cast<int>(arg(c, 0)), buffer.data(), arg(c, 2));
        for (int i = 0; i < n; ++i) write8(arg(c, 1) + static_cast<std::uint32_t>(i), buffer[static_cast<std::size_t>(i)]);
        c.r[2] = static_cast<std::uint32_t>(n);
    });
    def(7, "write", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) {
            on_fd(c, kWrite, {arg(c, 1), arg(c, 2)});
            return;
        }
        if (arg(c, 0) == 1 || arg(c, 0) == 2) std::fprintf(stderr, "[iop-tty] %s", ram_string(arg(c, 1), arg(c, 2)).c_str());
        c.r[2] = static_cast<std::uint32_t>(io::write(static_cast<int>(arg(c, 0)), nullptr, arg(c, 2)));
    });
    def(8, "lseek", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) {
            on_fd(c, kLseek, {arg(c, 1), arg(c, 2)});
            return;
        }
        c.r[2] = static_cast<std::uint32_t>(io::seek(static_cast<int>(arg(c, 0)), static_cast<std::int32_t>(arg(c, 1)), static_cast<int>(arg(c, 2))));
    });
    def(9, "ioctl", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) on_fd(c, kIoctl, {arg(c, 1), arg(c, 2)});
        else c.r[2] = static_cast<std::uint32_t>(-1);
    });
    def(10, "remove", [](Cpu &c) { if (!on_path(c, kRemove, arg(c, 0), {})) c.r[2] = static_cast<std::uint32_t>(kErrNoDevice); });
    def(11, "mkdir", [](Cpu &c) { if (!on_path(c, kMkdir, arg(c, 0), {arg(c, 1)})) c.r[2] = static_cast<std::uint32_t>(kErrNoDevice); });
    def(12, "rmdir", [](Cpu &c) { if (!on_path(c, kRmdir, arg(c, 0), {})) c.r[2] = static_cast<std::uint32_t>(kErrNoDevice); });
    def(13, "dopen", [](Cpu &c) {
        std::string device;
        int unit;
        std::uint32_t name;
        if (split(arg(c, 0), device, unit, name)) open_like(c, kDopen, arg(c, 0), 0, 0);
        else c.r[2] = static_cast<std::uint32_t>(kErrNoDevice);
    });
    def(14, "dclose", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) on_fd(c, kDclose, {}, true);
        else c.r[2] = static_cast<std::uint32_t>(kErrBadFd);
    });
    def(15, "dread", [](Cpu &c) {
        if (guest_file(static_cast<int>(arg(c, 0)))) on_fd(c, kDread, {arg(c, 1)});
        else c.r[2] = static_cast<std::uint32_t>(kErrBadFd);
    });
    def(16, "getstat", [](Cpu &c) {
        if (on_path(c, kGetstat, arg(c, 0), {arg(c, 1)})) return;
        std::uint32_t mode = 0, size = 0;
        if (!io::stat(ram_string(arg(c, 0)), mode, size)) {
            c.r[2] = static_cast<std::uint32_t>(-2);
            return;
        }
        const std::uint32_t p = arg(c, 1);
        for (std::uint32_t i = 0; i < 40; i += 4) write32(p + i, 0);
        write32(p, mode);
        write32(p + 8, size);
        c.r[2] = 0;
    });
    def(17, "chstat", [](Cpu &c) { if (!on_path(c, kChstat, arg(c, 0), {arg(c, 1), arg(c, 2)})) c.r[2] = static_cast<std::uint32_t>(kErrNoDevice); });
    def(25, "rename", [](Cpu &c) { if (!on_path(c, kRename, arg(c, 0), {arg(c, 1)})) c.r[2] = static_cast<std::uint32_t>(kErrNoDevice); });
    def(27, "sync", [](Cpu &c) { if (!on_path(c, kSync, arg(c, 0), {arg(c, 1)})) c.r[2] = static_cast<std::uint32_t>(kErrNoDevice); });
    def(31, "devctl", [](Cpu &c) {
        if (!on_path(c, kDevctl, arg(c, 0), {arg(c, 1), arg(c, 2), arg(c, 3), arg(c, 4), arg(c, 5)}))
            c.r[2] = static_cast<std::uint32_t>(kErrNoDevice);
    });
}

}
