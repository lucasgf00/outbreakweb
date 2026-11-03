#include "backend.hpp"

#include <atomic>
#include <cstdio>
#include <cstdlib>

namespace ps2::net {

namespace {

const char *env(const char *name) {
    const char *v = std::getenv(name);
    return v && *v ? v : nullptr;
}

}

std::string configured_url() {
    const char *v = env("PS2_NET");
    return v ? v : "";
}

void configured_mac(std::uint8_t mac[6]) {
    // 00:04:1F is one of Sony Computer Entertainment's OUIs.
    static const std::uint8_t fallback[6] = {0x00, 0x04, 0x1F, 0x52, 0x45, 0x4F};
    for (int i = 0; i < 6; ++i) mac[i] = fallback[i];
    const char *v = env("PS2_NET_MAC");
    if (!v) return;
    unsigned b[6];
    if (std::sscanf(v, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) == 6) {
        for (int i = 0; i < 6; ++i) mac[i] = static_cast<std::uint8_t>(b[i]);
        mac[0] &= 0xFE; // never a multicast source
    } else {
        std::fprintf(stderr, "[net] PS2_NET_MAC=%s is not a MAC address, using the default\n", v);
    }
}

std::string configured_pcap() {
    const char *v = env("PS2_NET_PCAP");
    return v ? v : "";
}

bool trace() {
    static const bool on = env("PS2_NET_TRACE") != nullptr;
    return on;
}

namespace {
std::atomic<std::uint32_t> g_peer_gone{0}, g_peer_left{0};
std::atomic<int> g_peer_left_slot{-1};
}

void text_message(const char *data, std::size_t size) {
    const std::string text(data, size);
    if (text.find("\"kk-peer-left\"") != std::string::npos) {
        const auto at = text.find("\"slot\"");
        const int slot = at == std::string::npos ? -1 : std::atoi(text.c_str() + text.find(':', at) + 1);
        if (slot < 0 || slot > 7) return;
        std::fprintf(stderr, "[net] gateway: teammate in slot %d left the scenario (%.60s)\n", slot, text.c_str());
        g_peer_left_slot.store(slot, std::memory_order_relaxed);
        g_peer_left.fetch_add(1, std::memory_order_release);
        return;
    }
#ifndef __EMSCRIPTEN__
    if (text.find("\"kk-peer-trk\"") != std::string::npos) {  // for the page (web/shell.html); logged for PS2_NET_TRK runs
        if (std::getenv("PS2_NET_TRK")) std::fprintf(stderr, "[net] gateway: teammate tracker %.200s\n", text.c_str());
        return;
    }
#endif
    if (text.find("\"kk-peer-gone\"") == std::string::npos) {
        if (trace()) std::fprintf(stderr, "[net] gateway text message ignored: %.80s\n", text.c_str());
        return;
    }
    std::string tag = "?";
    if (const auto at = text.find("\"tag\""); at != std::string::npos) {
        const auto open = text.find('"', text.find(':', at) + 1), close = open == std::string::npos ? open : text.find('"', open + 1);
        if (close != std::string::npos && close - open <= 17) tag = text.substr(open + 1, close - open - 1);
    }
    std::fprintf(stderr, "[net] gateway: teammate %s left the game\n", tag.c_str());
    g_peer_gone.fetch_add(1, std::memory_order_release);
}

std::uint32_t peer_gone_notices() { return g_peer_gone.load(std::memory_order_acquire); }
std::uint32_t peer_left_notices() { return g_peer_left.load(std::memory_order_acquire); }
int peer_left_slot() { return g_peer_left_slot.load(std::memory_order_relaxed); }

}
