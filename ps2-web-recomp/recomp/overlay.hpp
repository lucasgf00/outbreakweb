#pragma once

#include "elf.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace recomp {

// Code a game copies from the disc to a fixed address at run time, outside its ELF. Several overlays can
// share an address, so the runtime tells them apart by their words (see Translation::checks).
struct Overlay {
    std::string name;
    std::uint32_t base{};
    std::vector<std::uint8_t> bytes;
    std::uint32_t text_begin{};
    std::uint32_t text_end{};
    std::vector<std::uint32_t> seeds;
    // Header words that identify this overlay among others loaded at the same address, as offsets.
    std::vector<std::uint32_t> identity;
};

// Reads an overlay image as it sits in memory once loaded (scripts/disc.py overlays decompresses them).
// Knows Capcom's "MWo3" executables (Resident Evil Outbreak, SLUS-20765/SLUS-20984).
std::optional<Overlay> read_overlay(std::vector<std::uint8_t> bytes);
Image overlay_image(const Overlay &overlay);

}
