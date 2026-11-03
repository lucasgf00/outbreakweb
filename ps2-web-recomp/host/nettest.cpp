// nettest: checks the runtime's frame backend against a gateway without any PS2
// code. It plays a minimal client: DHCP DISCOVER/REQUEST, a DNS lookup of the
// Capcom lobby host, then prints what came back.
//
//   nettest ws://127.0.0.1:8765/eth [hostname]

#include "net/backend.hpp"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

using Frame = std::vector<std::uint8_t>;

std::uint16_t checksum(const std::uint8_t *p, std::size_t n, std::uint32_t sum = 0) {
    for (std::size_t i = 0; i + 1 < n; i += 2) sum += (p[i] << 8) | p[i + 1];
    if (n & 1) sum += p[n - 1] << 8;
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
    return static_cast<std::uint16_t>(~sum);
}

void put16(std::uint8_t *p, unsigned v) { p[0] = static_cast<std::uint8_t>(v >> 8); p[1] = static_cast<std::uint8_t>(v); }

// Ethernet + IPv4 + UDP around a payload.
Frame udp(const std::uint8_t mac[6], const std::uint8_t dst_mac[6], const std::uint8_t src[4], const std::uint8_t dst[4], unsigned sport,
          unsigned dport, const Frame &payload) {
    Frame f(14 + 20 + 8 + payload.size());
    std::memcpy(&f[0], dst_mac, 6);
    std::memcpy(&f[6], mac, 6);
    put16(&f[12], 0x0800);
    std::uint8_t *ip = &f[14];
    ip[0] = 0x45;
    put16(ip + 2, static_cast<unsigned>(20 + 8 + payload.size()));
    ip[8] = 64;
    ip[9] = 17;
    std::memcpy(ip + 12, src, 4);
    std::memcpy(ip + 16, dst, 4);
    put16(ip + 10, checksum(ip, 20));
    std::uint8_t *u = ip + 20;
    put16(u, sport);
    put16(u + 2, dport);
    put16(u + 4, static_cast<unsigned>(8 + payload.size()));
    std::memcpy(u + 8, payload.data(), payload.size());
    std::uint32_t pseudo = 0;
    for (int i = 0; i < 4; i += 2) pseudo += ((src[i] << 8) | src[i + 1]) + ((dst[i] << 8) | dst[i + 1]);
    pseudo += 17 + 8 + static_cast<std::uint32_t>(payload.size());
    put16(u + 6, checksum(u, 8 + payload.size(), pseudo));
    return f;
}

Frame dhcp(const std::uint8_t mac[6], std::uint8_t type, std::uint32_t xid, const std::uint8_t *requested) {
    Frame m(240);
    m[0] = 1;
    m[1] = 1;
    m[2] = 6;
    m[4] = static_cast<std::uint8_t>(xid >> 24);
    m[5] = static_cast<std::uint8_t>(xid >> 16);
    m[6] = static_cast<std::uint8_t>(xid >> 8);
    m[7] = static_cast<std::uint8_t>(xid);
    m[10] = 0x80;
    std::memcpy(&m[28], mac, 6);
    const std::uint8_t cookie[4] = {0x63, 0x82, 0x53, 0x63};
    std::memcpy(&m[236], cookie, 4);
    m.insert(m.end(), {53, 1, type});
    if (requested) {
        m.insert(m.end(), {50, 4});
        m.insert(m.end(), requested, requested + 4);
    }
    m.insert(m.end(), {55, 3, 1, 3, 6, 255});
    m.resize(300);
    return m;
}

bool wait(ps2::net::Backend &b, Frame &out, unsigned port, int ms = 3000) {
    for (int waited = 0; waited < ms; waited += 5) {
        while (b.receive(out))
            if (out.size() > 42 && out[12] == 8 && out[13] == 0 && out[23] == 17 && ((out[36] << 8) | out[37]) == port) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return false;
}

std::string ip(const std::uint8_t *p) { return std::to_string(p[0]) + "." + std::to_string(p[1]) + "." + std::to_string(p[2]) + "." + std::to_string(p[3]); }

}

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: nettest ws://host:port/eth [hostname]\n");
        return 2;
    }
    const std::string host = argc > 2 ? argv[2] : "app01.reo.capcom.sf.yav4.com";
    auto backend = ps2::net::make_websocket(argv[1]);
    std::uint8_t mac[6];
    ps2::net::configured_mac(mac);
    const std::uint8_t bcast_mac[6] = {255, 255, 255, 255, 255, 255}, zero[4] = {}, bcast[4] = {255, 255, 255, 255};

    // The backend connects on the first frame (like the game choosing NETWORK PLAY) and sends it once open.
    Frame f = udp(mac, bcast_mac, zero, bcast, 68, 67, dhcp(mac, 1, 0x50533200, nullptr));
    backend->send(f.data(), f.size());
    for (int i = 0; i < 400 && !backend->connected(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    if (!backend->connected()) {
        std::fprintf(stderr, "FAIL: no connection to %s\n", argv[1]);
        return 1;
    }
    Frame r;
    if (!wait(*backend, r, 68)) {
        std::fprintf(stderr, "FAIL: no DHCP OFFER\n");
        return 1;
    }
    const std::uint8_t *bootp = &r[42];
    std::uint8_t offered[4];
    std::memcpy(offered, bootp + 16, 4);
    std::printf("DHCP OFFER %s\n", ip(offered).c_str());
    f = udp(mac, bcast_mac, zero, bcast, 68, 67, dhcp(mac, 3, 0x50533201, offered));
    backend->send(f.data(), f.size());
    if (!wait(*backend, r, 68)) {
        std::fprintf(stderr, "FAIL: no DHCP ACK\n");
        return 1;
    }
    std::uint8_t router[4] = {}, dns[4] = {}, gw_mac[6];
    std::memcpy(gw_mac, &r[6], 6);
    for (std::size_t i = 42 + 240; i + 2 <= r.size() && r[i] != 255;) {
        const std::uint8_t code = r[i], len = r[i + 1];
        if (code == 3 && len >= 4) std::memcpy(router, &r[i + 2], 4);
        if (code == 6 && len >= 4) std::memcpy(dns, &r[i + 2], 4);
        if (code == 53) std::printf("DHCP message type %u\n", r[i + 2]);
        i += 2u + len;
        if (code == 0) i -= 1;
    }
    std::printf("DHCP ACK %s router %s dns %s\n", ip(&r[42 + 16]).c_str(), ip(router).c_str(), ip(dns).c_str());

    Frame q = {0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0};
    std::size_t start = 0;
    while (start <= host.size()) {
        const auto dot = host.find('.', start);
        const std::string label = host.substr(start, dot == std::string::npos ? std::string::npos : dot - start);
        q.push_back(static_cast<std::uint8_t>(label.size()));
        q.insert(q.end(), label.begin(), label.end());
        if (dot == std::string::npos) break;
        start = dot + 1;
    }
    q.insert(q.end(), {0, 0, 1, 0, 1});
    f = udp(mac, gw_mac, offered, dns, 1053, 53, q);
    backend->send(f.data(), f.size());
    if (!wait(*backend, r, 1053)) {
        std::fprintf(stderr, "FAIL: no DNS answer\n");
        return 1;
    }
    const std::uint8_t *d = &r[42];
    const unsigned rcode = d[3] & 15, answers = (d[6] << 8) | d[7];
    if (rcode == 0 && answers > 0 && r.size() >= 4) std::printf("DNS %s -> %s\n", host.c_str(), ip(&r[r.size() - 4]).c_str());
    else std::printf("DNS %s -> rcode %u\n", host.c_str(), rcode);
    std::printf("OK\n");
    return 0;
}
