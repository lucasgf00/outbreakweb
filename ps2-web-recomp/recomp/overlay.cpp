#include "overlay.hpp"

#include <cctype>
#include <cstring>

namespace recomp {

namespace {

std::uint32_t u32(const std::vector<std::uint8_t> &b, std::uint32_t o) {
    std::uint32_t v = 0;
    if (o <= b.size() && b.size() - o >= 4) std::memcpy(&v, b.data() + o, 4);
    return v;
}

std::string clean_name(const std::vector<std::uint8_t> &b, std::uint32_t at, std::uint32_t size) {
    std::string name;
    for (std::uint32_t o = at; o < at + size && o < b.size() && b[o]; ++o) name += static_cast<char>(b[o]);
    if (const auto dot = name.find('.'); dot != std::string::npos) name = name.substr(0, dot);
    std::string clean;
    for (char ch : name) clean += std::isalnum(static_cast<unsigned char>(ch)) ? ch : '_';
    return clean.empty() ? "overlay" : clean;
}

// Capcom's MWo3 executable, as the loader at 0x001A9480 in SLUS-20765 copies it to memory, header included:
//   0x00 "MWo3"   0x04 id         0x08 load address   0x0C text size   0x10 data size   0x14 bss size
//   0x18 end of text and data (load address + 0x40 + text + data), repeated at 0x1C    0x20 file name
//   0x40 text, then data. There are no relocations: each file is linked for its load address.
constexpr std::uint32_t kMwo3 = 0x336F574Du;
constexpr std::uint32_t kMwo3Header = 0x40;

std::optional<Overlay> read_mwo3(std::vector<std::uint8_t> bytes) {
    if (bytes.size() < kMwo3Header || u32(bytes, 0) != kMwo3) return std::nullopt;
    const std::uint32_t base = u32(bytes, 0x08), text = u32(bytes, 0x0C), data = u32(bytes, 0x10);
    const std::uint64_t image = std::uint64_t{kMwo3Header} + text + data;
    if ((base & 3u) || image > bytes.size() || u32(bytes, 0x18) != base + image) return std::nullopt;
    Overlay o;
    o.name = clean_name(bytes, 0x20, 0x20);
    o.base = base;
    o.text_begin = base + kMwo3Header;
    o.text_end = base + kMwo3Header + text;
    o.seeds.push_back(o.text_begin);
    o.identity = {0x00, 0x04, 0x08, 0x0C, 0x10};
    bytes.resize(static_cast<std::size_t>(image));
    o.bytes = std::move(bytes);
    return o;
}

}

std::optional<Overlay> read_overlay(std::vector<std::uint8_t> bytes) {
    return read_mwo3(std::move(bytes));
}

Image overlay_image(const Overlay &overlay) {
    return Image::raw(overlay.base, overlay.bytes, {overlay.text_begin, overlay.text_end}, overlay.text_end);
}

}
