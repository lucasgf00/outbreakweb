#pragma once

#include "elf.hpp"
#include "r5900.hpp"

#include <cstdint>
#include <set>
#include <vector>

namespace recomp {

struct Program {
    std::uint32_t base{};
    std::uint32_t end{};
    std::vector<std::uint8_t> code;
    std::set<std::uint32_t> functions;
    std::set<std::uint32_t> entries;
    std::set<std::uint32_t> rejected;
    std::set<std::uint32_t> dynamic;
    // The text the candidate rounds searched (EERECOMP_GAPS=main lists what is left out in it).
    std::uint32_t text_begin{};
    std::uint32_t text_end{};

    bool is_code(std::uint32_t address) const {
        return address >= base && address < end && (address & 3u) == 0 && code[(address - base) >> 2];
    }
};

// hints are possible entry points (for example calls into this code from overlays linked against it); they are
// translated only if they decode as code, like the address constants the analysis finds by itself.
Program analyse(const Image &image, const std::vector<std::uint32_t> &extra_entries, const std::set<std::uint32_t> &dynamic = {},
                const std::vector<std::uint32_t> &hints = {});

// Targets of the direct jumps and calls in the code found.
std::vector<std::uint32_t> call_targets(const Image &image, const Program &p);

}
