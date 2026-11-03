#include "analysis.hpp"

#include <algorithm>
#include <deque>

namespace recomp {

namespace {

constexpr std::size_t kTentativeLimit = 16384;

class Explorer {
public:
    Explorer(const Image &image, Program &program) : image_(image), p_(program) {}

    bool valid_target(std::uint32_t address) const {
        return (address & 3u) == 0 && address >= p_.base && address < p_.end && image_.executable(address);
    }

    void strong(std::uint32_t address, bool function) {
        if (!valid_target(address)) return;
        if (function) p_.functions.insert(address);
        p_.entries.insert(address);
        if (!p_.is_code(address)) queue_.push_back(address);
    }

    void drain() {
        while (!queue_.empty()) {
            const std::uint32_t start = queue_.front();
            queue_.pop_front();
            walk(start);
        }
    }

    bool tentative(std::uint32_t start) {
        std::deque<std::uint32_t> pending{start};
        std::set<std::uint32_t> seen;
        while (!pending.empty()) {
            std::uint32_t pc = pending.front();
            pending.pop_front();
            while (true) {
                if (!valid_target(pc)) return false;
                if (p_.is_code(pc) || seen.count(pc)) break;
                if (seen.size() > kTentativeLimit) return false;
                seen.insert(pc);
                const Insn insn = decode(*image_.word(pc), pc);
                if (insn.op == Op::Invalid) return false;
                const Flow f = flow(insn.op);
                if (f == Flow::Stop) break;
                if (f == Flow::None) { pc += 4; continue; }
                if (!valid_target(pc + 4) || decode(*image_.word(pc + 4), pc + 4).op == Op::Invalid) return false;
                if (has_delay_slot(decode(*image_.word(pc + 4), pc + 4).op)) return false;
                seen.insert(pc + 4);
                // A `j` out of this program's address range is a tail call into other code (the stub table
                // of Outbreak's submain.bin overlay, `j <ELF function>` from 0x003719E0 on, is only that);
                // dispatch() finds the target at run time.
                const std::uint32_t target = insn.branch_target_of();
                if (f == Flow::Jump && (target < p_.base || target >= p_.end)) break;
                if (f == Flow::Branch || f == Flow::BranchLikely || (f == Flow::Jump && !p_.dynamic.count(pc))) {
                    if (!valid_target(target)) return false;
                    pending.push_back(insn.branch_target_of());
                }
                if (f == Flow::Jump || f == Flow::JumpRegister) break;
                pc += 8;
            }
        }
        return true;
    }

private:
    void walk(std::uint32_t pc) {
        while (valid_target(pc) && !p_.is_code(pc)) {
            const Insn insn = decode(*image_.word(pc), pc);
            if (insn.op == Op::Invalid) return;
            mark(pc);
            const Flow f = flow(insn.op);
            if (f == Flow::Stop) return;
            if (insn.op == Op::Syscall) p_.entries.insert(pc + 4);
            if (f == Flow::None) { pc += 4; continue; }
            if (valid_target(pc + 4) && decode(*image_.word(pc + 4), pc + 4).op != Op::Invalid) mark(pc + 4);
            switch (f) {
            case Flow::Branch:
            case Flow::BranchLikely:
                local(insn.branch_target_of(), pc);
                break;
            case Flow::Jump:
                if (!p_.dynamic.count(pc)) local(insn.branch_target_of(), pc);
                return;
            case Flow::Call:
                if (!p_.dynamic.count(pc)) strong(insn.branch_target_of(), true);
                p_.entries.insert(pc + 8);
                break;
            case Flow::CallRegister:
                p_.entries.insert(pc + 8);
                break;
            case Flow::JumpRegister:
                return;
            default:
                break;
            }
            pc += 8;
        }
    }

    void local(std::uint32_t target, std::uint32_t from) {
        if (!valid_target(target)) return;
        if (target <= from) p_.entries.insert(target);
        if (!p_.is_code(target)) queue_.push_back(target);
    }

    void mark(std::uint32_t pc) { p_.code[(pc - p_.base) >> 2] = 1; }

    const Image &image_;
    Program &p_;
    std::deque<std::uint32_t> queue_;
};

std::vector<std::uint32_t> address_constants(const Image &image, const Program &p) {
    std::vector<std::uint32_t> found;
    for (std::uint32_t pc = p.base; pc < p.end; pc += 4) {
        if (!p.is_code(pc)) continue;
        const Insn lui = decode(*image.word(pc), pc);
        if (lui.op != Op::Lui) continue;
        const bool saved = (lui.rt() >= 16 && lui.rt() <= 23) || lui.rt() == 30;
        std::uint32_t stop = pc + (saved ? 1024 : 160);
        for (std::uint32_t next = pc + 4; next < stop && p.is_code(next); next += 4) {
            const Insn insn = decode(*image.word(next), next);
            if ((insn.op == Op::Addiu || insn.op == Op::Ori) && insn.rs() == lui.rt()) {
                const std::uint32_t high = lui.uimm() << 16;
                found.push_back(insn.op == Op::Addiu ? high + static_cast<std::uint32_t>(insn.simm()) : high | insn.uimm());
            }
            if (insn.rt() == lui.rt() && insn.op == Op::Lui) break;
            const Flow f = flow(insn.op);
            if (f == Flow::Jump || f == Flow::JumpRegister || f == Flow::Stop) stop = std::min(stop, std::max(pc + 40, next + 8));
        }
    }
    return found;
}

std::uint32_t constant_in(const Image &image, const Program &p, std::uint32_t from, std::uint32_t reg) {
    for (std::uint32_t pc = from - 4; from - pc <= 64 && p.is_code(pc); pc -= 4) {
        const Insn low = decode(*image.word(pc), pc);
        if ((low.op != Op::Addiu && low.op != Op::Ori) || low.rt() != reg || low.rs() != reg) continue;
        for (std::uint32_t at = pc - 4; pc - at <= 64 && p.is_code(at); at -= 4) {
            const Insn lui = decode(*image.word(at), at);
            if (lui.op != Op::Lui || lui.rt() != reg) continue;
            const std::uint32_t high = lui.uimm() << 16;
            return low.op == Op::Addiu ? high + static_cast<std::uint32_t>(low.simm()) : high | low.uimm();
        }
        return 0;
    }
    return 0;
}

std::uint32_t scale_of(const Image &image, const Program &p, std::uint32_t from, std::uint32_t reg) {
    for (std::uint32_t pc = from - 4; from - pc <= 64 && p.is_code(pc); pc -= 4) {
        const Insn insn = decode(*image.word(pc), pc);
        if (insn.op == Op::Sll && insn.rd() == reg) return std::clamp<std::uint32_t>(insn.sa(), 2, 6);
    }
    return 2;
}

std::vector<std::uint32_t> computed_branches(const Image &image, const Program &p) {
    std::vector<std::uint32_t> found;
    for (std::uint32_t pc = p.base; pc < p.end; pc += 4) {
        if (!p.is_code(pc)) continue;
        const Insn jr = decode(*image.word(pc), pc);
        if (jr.op != Op::Jr || jr.rs() == 31) continue;
        for (std::uint32_t at = pc - 4; pc - at <= 64 && p.is_code(at); at -= 4) {
            const Insn add = decode(*image.word(at), at);
            if ((add.op != Op::Addu && add.op != Op::Daddu) || add.rd() != jr.rs()) continue;
            const std::uint32_t regs[2] = {add.rs(), add.rt()};
            for (int i = 0; i < 2; ++i) {
                const std::uint32_t table = constant_in(image, p, at, regs[i]);
                if (!table || (table & 3u) || !image.word(table)) continue;
                const std::uint32_t stride = 1u << scale_of(image, p, at, regs[i ^ 1]);
                std::uint32_t last = table;
                while (last < table + 64 * stride && image.word(last)) {
                    const Flow f = flow(decode(*image.word(last), last).op);
                    if (f == Flow::JumpRegister || f == Flow::Stop) break;
                    last += 4;
                }
                for (std::uint32_t slot = table; slot <= last; slot += stride) found.push_back(slot);
            }
            break;
        }
    }
    return found;
}

// Jump stubs: an aligned `j <address outside this program>; nop`. MWo3 overlays export functions this way
// (submain.bin, 0x003719E0 on in SLUS-20765, is a table of `j <ELF function>` padded to 16 bytes) and other
// overlays call the stubs directly, so nothing inside the overlay itself reaches them.
std::vector<std::uint32_t> jump_stubs(const Image &image, const Program &p, std::uint32_t begin, std::uint32_t end) {
    std::vector<std::uint32_t> found;
    for (std::uint32_t pc = (begin + 7) & ~7u; pc + 4 < end; pc += 8) {
        const auto w = image.word(pc);
        if (!w || image.word(pc + 4).value_or(1) != 0) continue;
        const Insn insn = decode(*w, pc);
        if (insn.op != Op::J) continue;
        const std::uint32_t t = insn.branch_target_of();
        if (t < p.base || t >= p.end) found.push_back(pc);
    }
    return found;
}

// Function starts: an `addiu sp, sp, -N` (or daddiu) right after a `jr ra` and its delay slot, padding allowed.
// Also a leaf function (no frame): the first nonzero word, 8-byte aligned, after a `jr ra`, its delay slot and
// at least one word of zero padding. Only the leaf form with padding counts, so the code that follows an early
// `jr ra` inside a function is not taken for a new one. SLUS-20765 game.bin 0x006847E0 (called by pointer from
// 0x001D39F4) is such a leaf; tentative() still has to accept every candidate.
std::vector<std::uint32_t> frame_starts(const Image &image, std::uint32_t begin, std::uint32_t end) {
    std::vector<std::uint32_t> found;
    for (std::uint32_t pc = begin + 8; pc < end; pc += 4) {
        const std::uint32_t w = image.word(pc).value_or(0);
        if (!w) continue;
        const bool frame = (w & 0xFFFF8000u) == 0x27BD8000u || (w & 0xFFFF8000u) == 0x67BD8000u;
        if (!frame && ((pc & 7u) || image.word(pc - 4).value_or(1) != 0)) continue;
        std::uint32_t at = pc - 4;
        while (at > begin + 4 && pc - at < 20 && image.word(at).value_or(1) == 0) at -= 4;
        const std::uint32_t jr = image.word(at).value_or(0) == 0x03E00008u ? at : image.word(at - 4).value_or(0) == 0x03E00008u ? at - 4 : 0;
        if (jr && (frame || pc - jr >= 12)) found.push_back(pc);
    }
    return found;
}

// End of the text the candidate rounds search when the ELF has no section headers to say where .text ends: the
// code reached from the entry point, extended through any following function starts that are no further than
// kTextGap apart. SLUS-20984's main ELF has code up to ~0x001FEF00 that only interrupt handlers and pointers
// reach (0x001F73C0 does `jal 0x001143A0` with a0 0x100, a1 0x001F7350); without this the rounds stopped at
// the last code the entry point reached (translated units ended at 0x001F0000) and 0x001F72F4..0x001FEAF0 ran
// in the interpreter.
constexpr std::uint32_t kTextGap = 0x4000;

std::uint32_t extend_text(const Image &image, std::uint32_t begin, std::uint32_t end, std::uint32_t limit) {
    for (std::uint32_t start : frame_starts(image, end > begin + 8 ? end - 8 : begin, limit)) {
        if (start >= end + kTextGap) break;
        end = std::max(end, start + 4);
    }
    return end;
}

}

std::vector<std::uint32_t> call_targets(const Image &image, const Program &p) {
    std::vector<std::uint32_t> found;
    for (std::uint32_t pc = p.base; pc < p.end; pc += 4) {
        if (!p.is_code(pc) || p.dynamic.count(pc)) continue;
        const Insn insn = decode(*image.word(pc), pc);
        if (insn.op == Op::Jal || insn.op == Op::J) found.push_back(insn.branch_target_of());
    }
    return found;
}

Program analyse(const Image &image, const std::vector<std::uint32_t> &extra_entries, const std::set<std::uint32_t> &dynamic,
                const std::vector<std::uint32_t> &hints) {
    Program p;
    p.dynamic = dynamic;
    p.base = UINT32_MAX;
    for (const auto &segment : image.segments()) {
        if (!segment.executable || !segment.code_size) continue;
        p.base = std::min(p.base, segment.address & ~3u);
        p.end = std::max<std::uint32_t>(p.end, segment.address + segment.code_size);
    }
    p.end &= ~3u;
    p.code.assign((p.end - p.base) >> 2, 0);

    Explorer explorer(image, p);
    explorer.strong(image.entry(), true);
    for (const auto &[address, name] : image.symbols()) explorer.strong(address, true);
    for (std::uint32_t address : extra_entries) explorer.strong(address, true);
    explorer.drain();

    std::uint32_t text_begin = p.end, text_end = p.base;
    for (std::uint32_t pc = p.base; pc < p.end; pc += 4) {
        if (p.is_code(pc)) {
            text_begin = std::min(text_begin, pc);
            text_end = pc + 4;
        }
    }
    if (image.text().second > image.text().first) {
        text_begin = std::min(text_begin, image.text().first);
        text_end = std::max(text_end, image.text().second);
    } else if (text_end > text_begin) {
        text_end = extend_text(image, text_begin, text_end, p.end);
    }
    p.text_begin = text_begin;
    p.text_end = text_end;

    for (int round = 0; round < 8; ++round) {
        std::vector<std::uint32_t> candidates = address_constants(image, p);
        const std::vector<std::uint32_t> branches = computed_branches(image, p);
        candidates.insert(candidates.end(), branches.begin(), branches.end());
        const std::vector<std::uint32_t> frames = frame_starts(image, text_begin, text_end);
        candidates.insert(candidates.end(), frames.begin(), frames.end());
        const std::vector<std::uint32_t> stubs = jump_stubs(image, p, text_begin, text_end);
        candidates.insert(candidates.end(), stubs.begin(), stubs.end());
        candidates.insert(candidates.end(), hints.begin(), hints.end());
        for (const auto &segment : image.segments())
            for (std::size_t offset = 0; offset + 4 <= segment.bytes.size(); offset += 4)
                candidates.push_back(*image.word(segment.address + static_cast<std::uint32_t>(offset)));
        std::sort(candidates.begin(), candidates.end());
        candidates.erase(std::unique(candidates.begin(), candidates.end()), candidates.end());

        std::size_t added = 0;
        for (std::uint32_t target : candidates) {
            if (target < text_begin || target >= text_end || !explorer.valid_target(target)) continue;
            if (p.entries.count(target) || p.rejected.count(target)) continue;
            if (p.is_code(target) || explorer.tentative(target)) {
                p.entries.insert(target);
                if (!p.is_code(target)) explorer.strong(target, false);
                ++added;
            } else {
                p.rejected.insert(target);
            }
        }
        explorer.drain();
        if (!added) break;
    }
    return p;
}

}
