#include "ps2/generated.hpp"
#include "ps2/system.hpp"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <set>
#include <string>
#include <vector>

namespace ps2 {

void interpret(Cpu &c, std::uint32_t entry, std::uint32_t depth);

namespace {

std::vector<UnitInfo> g_units;
std::vector<const UnitInfo *> g_index;
std::uint32_t g_origin = 0;
std::uint32_t g_unit_size = 1;
std::set<std::uint32_t> g_misses;
std::set<std::string> g_module_misses;
std::set<std::uint32_t> g_interpreted;

// The last targets dispatch() saw, with ra at the time: printed when the EE stops on missing code or a call
// comes back to an address with no code, to tell where the bad jump came from.
struct Jump {
    std::uint32_t pc, ra;
};
constexpr std::uint32_t kJumps = 16;
Jump g_jumps[kJumps];
std::uint32_t g_jump_count = 0;

void print_jumps() {
    const std::uint32_t n = std::min(g_jump_count, kJumps);
    std::fprintf(stderr, "[ee]   last jumps (oldest first):");
    for (std::uint32_t k = g_jump_count - n; k != g_jump_count; ++k)
        std::fprintf(stderr, " %08X/ra %08X", g_jumps[k % kJumps].pc, g_jumps[k % kJumps].ra);
    std::fprintf(stderr, "\n");
}

bool plausible_code(std::uint32_t pc) {
    return (pc & 0x1FFFFFFFu) < kRamSize && ld32(pc) != 0;
}

constexpr std::uint32_t kModuleMagic = 0x32524E53u;

struct Instance {
    std::uint32_t base;
    const ModuleInfo *module;
    // Subtracted from a pc to look it up in the module's units: the load address for a relocatable
    // module, zero for an overlay translated in place.
    std::uint32_t origin() const { return module->base ? 0u : base; }
};

const ModuleInfo *g_modules = nullptr;
std::uint32_t g_module_count = 0;
std::uint32_t g_module_span = 0;
std::vector<Instance> g_instances;

// A callback table in the boot ELF filled by one overlay and still called after another overlay replaced it at
// the same address (guard_overlay_callbacks, set up per game in system.cpp).
struct CallbackGuard {
    std::uint32_t caller = 0, caller_size = 0;   // the ELF function that calls the table through jalr
    std::uint32_t base = 0, size = 0;            // where the owner overlay is loaded
    std::string owner;                           // module name of the overlay that registers the callbacks
    std::string label;
    unsigned skipped = 0;
};
CallbackGuard g_guard;

void resolve_guard() {
    g_guard.base = g_guard.size = 0;
    for (std::uint32_t k = 0; k < g_module_count; ++k)
        if (g_guard.owner == g_modules[k].name && g_modules[k].base) {
            g_guard.base = g_modules[k].base;
            g_guard.size = g_modules[k].size;
        }
}

bool loaded_at(const ModuleInfo &m, std::uint32_t base) {
    if (!m.base && ld32(base + 0x2Cu) != m.size) return false;
    for (std::uint32_t k = 0; k < m.check_count; ++k)
        if (ld32(base + m.checks[2 * k]) != m.checks[2 * k + 1]) return false;
    return true;
}

const Instance *instance_for(std::uint32_t pc) {
    for (std::size_t k = 0; k < g_instances.size(); ++k) {
        const Instance &i = g_instances[k];
        if (pc - i.base >= i.module->size) continue;
        if (loaded_at(*i.module, i.base)) return &i;
        g_instances.erase(g_instances.begin() + static_cast<std::ptrdiff_t>(k));
        break;
    }
    if (!g_module_count || pc >= kRamSize) return nullptr;
    // Overlays at a fixed address: several may share it, the checks tell which one is in memory now.
    for (std::uint32_t k = 0; k < g_module_count; ++k) {
        const ModuleInfo &m = g_modules[k];
        if (m.base && pc - m.base < m.size && loaded_at(m, m.base)) {
            g_instances.push_back({m.base, &m});
            return &g_instances.back();
        }
    }
    for (std::uint32_t at = pc & ~15u; pc - at < g_module_span; at -= 16) {
        if (ld32(at) == kModuleMagic) {
            for (std::uint32_t k = 0; k < g_module_count; ++k)
                if (!g_modules[k].base && pc - at < g_modules[k].size && loaded_at(g_modules[k], at)) {
                    g_instances.push_back({at, &g_modules[k]});
                    return &g_instances.back();
                }
            return nullptr;
        }
        if (at < 16) break;
    }
    return nullptr;
}

const UnitInfo *module_unit(const ModuleInfo &m, std::uint32_t offset) {
    for (std::uint32_t k = 0; k < m.count; ++k)
        if (offset - m.units[k].base < m.units[k].size) return &m.units[k];
    return nullptr;
}

const UnitInfo *unit_for(std::uint32_t pc) {
    const std::uint32_t slot = (pc - g_origin) / g_unit_size;
    if (pc < g_origin || slot >= g_index.size()) return nullptr;
    return g_index[slot];
}

void record_miss(std::uint32_t pc) {
    if (const Instance *m = instance_for(pc)) {
        char token[96];
        std::snprintf(token, sizeof token, "%s:%x", m->module->name, pc - m->origin());
        if (!g_module_misses.insert(token).second || g_options.misses_path.empty()) return;
        std::ofstream(g_options.misses_path, std::ios::app) << token << "\n";
        return;
    }
    if (!g_misses.insert(pc).second || g_options.misses_path.empty()) return;
    std::ofstream out(g_options.misses_path, std::ios::app);
    out << std::hex << pc << "\n";
}

}

void register_units(const UnitInfo *units, std::uint32_t count) {
    g_units.assign(units, units + count);
    if (g_units.empty()) return;
    std::sort(g_units.begin(), g_units.end(), [](const UnitInfo &a, const UnitInfo &b) { return a.base < b.base; });
    g_origin = g_units.front().base;
    g_unit_size = g_units.front().size;
    g_index.assign((g_units.back().base - g_origin) / g_unit_size + 1, nullptr);
    for (const UnitInfo &u : g_units) g_index[(u.base - g_origin) / g_unit_size] = &u;
}

void register_modules(const ModuleInfo *modules, std::uint32_t count) {
    g_modules = modules;
    g_module_count = count;
    g_instances.clear();
    for (std::uint32_t k = 0; k < count; ++k)
        if (!modules[k].base) g_module_span = std::max(g_module_span, modules[k].size);
    if (!g_guard.owner.empty()) resolve_guard();
}

void guard_overlay_callbacks(std::uint32_t caller, std::uint32_t caller_size, const char *owner, const char *label) {
    g_guard = CallbackGuard{};
    if (const char *e = std::getenv("PS2_STALE_CALLBACKS"); e && *e == '1') return;   // keep the original behaviour
    g_guard.caller = caller;
    g_guard.caller_size = caller_size;
    g_guard.owner = owner;
    g_guard.label = label;
    resolve_guard();
    if (g_guard.size)
        std::fprintf(stderr, "[ee] %s: callbacks of overlay %s called from 0x%08X are skipped when it is not resident\n", label,
                     owner, caller);
}

bool has_code(std::uint32_t pc) {
    const UnitInfo *u = unit_for(pc);
    if (u && u->entries[(pc - u->base) >> 2] != 0) return true;
    const Instance *m = instance_for(pc);
    const UnitInfo *mu = m ? module_unit(*m->module, pc - m->origin()) : nullptr;
    return mu && mu->entries[(pc - m->origin() - mu->base) >> 2] != 0;
}

void dispatch(Cpu &c, std::uint32_t, std::uint32_t depth) {
    const std::uint32_t pc = c.pc;
    if (pc & kUnwind) return;
    if (pc == kHandlerReturn || pc == kIdle) return;
    g_jumps[g_jump_count++ % kJumps] = {pc, c.r[31].w[0]};
    // PS2_INTERP_ALL=1 runs everything dispatch() sees in the interpreter: a test of the interpreter against the
    // translated code, slow but complete.
    // PS2_INTERP_ALL=<frame> starts at that frame (debugging a late scene with PS2_WATCH, interp.cpp).
    static const char *interp_env = std::getenv("PS2_INTERP_ALL");
    static const std::uint64_t interpret_from = interp_env ? std::strtoull(interp_env, nullptr, 0) : 0;
    if (interp_env && ps2::frame_count() >= interpret_from) [[clang::musttail]] return interpret(c, 0u, depth);
    const UnitInfo *u = unit_for(pc);
    const std::uint16_t id = u ? u->entries[(pc - u->base) >> 2] : 0;
    if (!id) {
        // A stale callback (docs/notes/runtime.md, "callbacks viejos de netwk"): the caller skips empty slots and
        // ignores what the callbacks return, so returning at once is the same as an empty slot.
        if (pc - g_guard.base < g_guard.size && c.r[31].w[0] - g_guard.caller - 1 < g_guard.caller_size) {
            const Instance *m = instance_for(pc);
            if (!m || g_guard.owner != m->module->name) {
                if (++g_guard.skipped <= 20)
                    std::fprintf(stderr, "[ee] %s: skipped stale %s callback 0x%08X (ra 0x%08X, overlay %s)\n", g_guard.label.c_str(),
                                 g_guard.owner.c_str(), pc, c.r[31].w[0], m ? m->module->name : "-");
                c.pc = c.r[31].w[0];
                return;
            }
        }
        if (const Instance *m = instance_for(pc)) {
            const std::uint32_t origin = m->origin();
            const UnitInfo *mu = module_unit(*m->module, pc - origin);
            const std::uint16_t entry = mu ? mu->entries[(pc - origin - mu->base) >> 2] : 0;
            if (entry) {
                c.base = origin;
                mu->function(c, entry, depth);
                return;
            }
        }
        record_miss(pc);
        // Code without a translation runs in the interpreter (runtime/src/interp.cpp) and the miss goes to
        // --misses so the next translation covers it. PS2_NO_INTERP=1 halts instead, as it used to.
        static const bool strict = std::getenv("PS2_NO_INTERP") != nullptr;
        if (!strict && plausible_code(pc)) {
            if (g_interpreted.insert(pc).second && g_interpreted.size() <= 200) {
                // Which overlay is resident at pc (several share 0x570000): a stale callback registered by another
                // overlay (docs/notes/runtime.md, "callbacks viejos de netwk") shows up as a miss in the wrong module.
                const Instance *m = instance_for(pc);
                std::fprintf(stderr, "[ee] interpreting untranslated code at 0x%08X (ra 0x%08X, overlay %s, a0 %08X a1 %08X a2 %08X)\n", pc,
                             c.r[31].w[0], m ? m->module->name : "-", c.r[4].w[0], c.r[5].w[0], c.r[6].w[0]);
            }
            [[clang::musttail]] return interpret(c, 0u, depth);
        }
        char reason[64];
        std::snprintf(reason, sizeof reason, "missing code at 0x%08X (ra 0x%08X)", pc, c.r[31].w[0]);
        std::fprintf(stderr, "[ee] no translated code at 0x%08X (ra 0x%08X)\n", pc, c.r[31].w[0]);
        for (int i = 1; i < 32; i += 4)
            std::fprintf(stderr, "[ee]   r%-2d %08X %08X %08X %08X\n", i, c.r[i].w[0], c.r[i + 1].w[0], c.r[i + 2].w[0], i + 3 < 32 ? c.r[i + 3].w[0] : 0u);
        print_jumps();
        halt(reason);
        c.pc = pc | kUnwind;
        return;
    }
    u->function(c, id, depth);
}

void call(Cpu &c, std::uint32_t target, std::uint32_t depth) {
    c.pc = target;
    if (depth >= kMaxDepth) return;
    dispatch(c, 0, depth);
}

// A call came back with c.pc != its return address. Mostly on purpose (an unwind, a thread switch, a longjmp,
// the depth limit: c.pc is then where to go on, and the caller returns so the dispatcher gets there). But when
// c.pc is an address with no code at all (a `jr ra` with ra loaded from a corrupted stack slot, e.g. File #2's
// "missing code at 0x00000100 (ra 0x00000100)"), going on would only halt the EE in dispatch(): return to the
// native caller instead, which knows the real return address. PS2_NO_INTERP=1 keeps the halt.
bool bad_return(Cpu &c, std::uint32_t ret) {
    const std::uint32_t pc = c.pc;
    if (pc & kUnwind) return false;
    if (pc == kHandlerReturn || pc == kIdle) return false;
    if (plausible_code(pc) || has_code(pc)) return false;
    static const bool strict = std::getenv("PS2_NO_INTERP") != nullptr;
    if (strict) return false;
    static unsigned count = 0;
    if (++count <= 20) {
        std::fprintf(stderr, "[ee] call returned to 0x%08X (no code there; ra 0x%08X): went on at its return address 0x%08X%s\n", pc,
                     c.r[31].w[0], ret, count == 20 ? " (not reporting any more)" : "");
        print_jumps();
    }
    c.pc = ret;
    return true;
}

void bad_entry(Cpu &c, std::uint32_t unit_base) {
    std::fprintf(stderr, "[ee] bad entry into unit 0x%08X for pc 0x%08X\n", unit_base, c.pc);
    halt("bad entry");
    c.pc |= kUnwind;
}

void stop(Cpu &c, std::uint32_t pc, const char *what) {
    std::fprintf(stderr, "[ee] stopped at 0x%08X: %s\n", pc, what);
    halt(what);
    c.pc = pc | kUnwind;
}

void probe(Cpu &c, std::uint32_t pc) {
    std::fprintf(stderr, "[probe] 0x%08X a0 %08X a1 %08X a2 %08X a3 %08X ra %08X v0 %08X\n", pc, c.r[4].w[0], c.r[5].w[0], c.r[6].w[0],
                 c.r[7].w[0], c.r[31].w[0], c.r[2].w[0]);
}

void breakpoint(Cpu &c, std::uint32_t pc) {
    std::fprintf(stderr, "[ee] break at 0x%08X (ra 0x%08X)\n", pc, c.r[31].w[0]);
}

void trap(Cpu &c, std::uint32_t pc) {
    std::fprintf(stderr, "[ee] trap at 0x%08X (ra 0x%08X)\n", pc, c.r[31].w[0]);
}

}
