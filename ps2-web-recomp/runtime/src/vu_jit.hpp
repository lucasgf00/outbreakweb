#pragma once

#include <cstdint>
#include <vector>

namespace ps2::vu::jit {

struct Layout {
    std::uint32_t flags = 0;
    std::uint32_t scratch = 0;
};

enum Import : std::uint32_t {
    kClipValue, kClipPost, kSetClip, kStart, kMac, kStatus, kClip, kForceMac, kMul, kMsub, kToFixed, kFromFixed,
    kDiv, kSqrt, kRsqrt, kEfu, kKick, kTop, kItop, kRandNext, kInterpret, kSyncQ, kFlushQ, kImports
};

extern const char *const kImportNames[kImports];

bool translate(const std::uint8_t *code, std::uint32_t entry, const Layout &layout, std::vector<std::uint8_t> &out);

}
