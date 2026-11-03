// WebSocket client for the native runner: plain ws:// over POSIX sockets, with
// a reader thread and automatic reconnection. One Ethernet frame per binary
// message. TLS (wss://) is left to the browser build or a local gateway.
#ifndef __EMSCRIPTEN__

#include "backend.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <random>
#include <thread>

#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

namespace ps2::net {

namespace {

constexpr std::size_t kMaxQueued = 2048;
// Frames kept while (re)connecting; DHCP and TCP retry anything older.
constexpr std::size_t kMaxPending = 64;

struct Url {
    std::string host, port, path;
};

bool parse(const std::string &url, Url &out) {
    const std::string scheme = "ws://";
    if (url.rfind(scheme, 0) != 0) return false;
    std::string rest = url.substr(scheme.size());
    const auto slash = rest.find('/');
    std::string authority = rest.substr(0, slash);
    out.path = slash == std::string::npos ? "/" : rest.substr(slash);
    const auto colon = authority.rfind(':');
    if (colon != std::string::npos && authority.find(']') == std::string::npos) {
        out.host = authority.substr(0, colon);
        out.port = authority.substr(colon + 1);
    } else {
        out.host = authority;
        out.port = "80";
    }
    return !out.host.empty();
}

std::string base64(const std::uint8_t *data, std::size_t n) {
    static const char *t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string s;
    for (std::size_t i = 0; i < n; i += 3) {
        const std::uint32_t v = (data[i] << 16) | ((i + 1 < n ? data[i + 1] : 0) << 8) | (i + 2 < n ? data[i + 2] : 0);
        s += t[(v >> 18) & 63];
        s += t[(v >> 12) & 63];
        s += i + 1 < n ? t[(v >> 6) & 63] : '=';
        s += i + 2 < n ? t[v & 63] : '=';
    }
    return s;
}

bool write_all(int fd, const std::uint8_t *p, std::size_t n) {
    while (n) {
        const ssize_t w = ::send(fd, p, n, MSG_NOSIGNAL);
        if (w <= 0) return false;
        p += w;
        n -= static_cast<std::size_t>(w);
    }
    return true;
}

bool read_all(int fd, std::uint8_t *p, std::size_t n) {
    while (n) {
        const ssize_t r = ::recv(fd, p, n, 0);
        if (r <= 0) return false;
        p += r;
        n -= static_cast<std::size_t>(r);
    }
    return true;
}

class NativeWebSocket final : public Backend {
public:
    // Like the browser backend, the connection starts with the first frame the game sends (NETWORK PLAY), so a
    // run that stays offline never opens a gateway session; frames sent during the handshake wait in pending_.
    explicit NativeWebSocket(std::string url) : url_(std::move(url)), rng_(std::random_device{}()) {
        if (!parse(url_, parts_)) {
            std::fprintf(stderr, "[net] %s: only ws://host:port/path is supported natively\n", url_.c_str());
            return;
        }
        valid_ = true;
    }

    ~NativeWebSocket() override {
        stop_ = true;
        {
            std::lock_guard lock(write_mutex_);
            if (fd_ >= 0) ::shutdown(fd_, SHUT_RDWR);
        }
        if (thread_.joinable()) thread_.join();
    }

    void send(const std::uint8_t *frame, std::size_t size) override {
        std::lock_guard lock(write_mutex_);
        if (fd_ < 0 || !open_) {
            if (valid_ && !thread_.joinable()) thread_ = std::thread([this] { loop(); });
            if (pending_.size() >= kMaxPending) {
                pending_.pop_front();
                ++dropped_;
            }
            pending_.emplace_back(frame, frame + size);
            return;
        }
        write_frame(frame, size);
    }

    bool receive(std::vector<std::uint8_t> &frame) override {
        std::lock_guard lock(queue_mutex_);
        if (queue_.empty()) return false;
        frame = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    bool connected() const override { return open_; }

    std::string describe() const override { return "websocket " + url_; }

    void send_text(const std::string &text) override {
        std::lock_guard lock(write_mutex_);
        if (fd_ >= 0 && open_) write_frame(reinterpret_cast<const std::uint8_t *>(text.data()), text.size(), 0x81);
    }

private:
    // One masked binary message; write_mutex_ held.
    void write_frame(const std::uint8_t *frame, std::size_t size, std::uint8_t first = 0x82 /* FIN + binary */) {
        std::uint8_t header[14];
        std::size_t h = 0;
        header[h++] = first;
        if (size < 126) {
            header[h++] = static_cast<std::uint8_t>(0x80 | size);
        } else {
            header[h++] = 0x80 | 126;
            header[h++] = static_cast<std::uint8_t>(size >> 8);
            header[h++] = static_cast<std::uint8_t>(size);
        }
        std::uint8_t mask[4];
        const std::uint32_t m = rng_();
        std::memcpy(mask, &m, 4);
        std::memcpy(header + h, mask, 4);
        h += 4;
        scratch_.assign(header, header + h);
        scratch_.resize(h + size);
        for (std::size_t i = 0; i < size; ++i) scratch_[h + i] = frame[i] ^ mask[i & 3];
        if (!write_all(fd_, scratch_.data(), scratch_.size())) ::shutdown(fd_, SHUT_RDWR);
    }

    int dial() {
        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo *res = nullptr;
        if (getaddrinfo(parts_.host.c_str(), parts_.port.c_str(), &hints, &res) != 0) return -1;
        int fd = -1;
        for (addrinfo *ai = res; ai; ai = ai->ai_next) {
            fd = ::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
            if (fd < 0) continue;
            if (::connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
            ::close(fd);
            fd = -1;
        }
        freeaddrinfo(res);
        if (fd < 0) return -1;
        const int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
        return fd;
    }

    bool handshake(int fd) {
        std::uint8_t key[16];
        for (auto &b : key) b = static_cast<std::uint8_t>(rng_());
        const std::string request = "GET " + parts_.path + " HTTP/1.1\r\nHost: " + parts_.host + ":" + parts_.port +
                                    "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + base64(key, 16) +
                                    "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: ps2-web-recomp\r\n\r\n";
        if (!write_all(fd, reinterpret_cast<const std::uint8_t *>(request.data()), request.size())) return false;
        std::string response;
        char ch;
        while (response.size() < 8192) {
            if (::recv(fd, &ch, 1, 0) != 1) return false;
            response += ch;
            if (response.size() >= 4 && response.compare(response.size() - 4, 4, "\r\n\r\n") == 0) break;
        }
        if (response.rfind("HTTP/1.1 101", 0) != 0) {
            std::fprintf(stderr, "[net] gateway refused the connection: %s\n", response.substr(0, response.find('\r')).c_str());
            return false;
        }
        return true;
    }

    void control(int fd, std::uint8_t opcode, const std::vector<std::uint8_t> &payload) {
        std::lock_guard lock(write_mutex_);
        std::vector<std::uint8_t> f{static_cast<std::uint8_t>(0x80 | opcode), static_cast<std::uint8_t>(0x80 | payload.size()), 0, 0, 0, 0};
        f.insert(f.end(), payload.begin(), payload.end());
        write_all(fd, f.data(), f.size());
    }

    void session(int fd) {
        std::vector<std::uint8_t> message;
        for (;;) {
            std::uint8_t h[2];
            if (!read_all(fd, h, 2)) return;
            const std::uint8_t opcode = h[0] & 0x0F;
            const bool fin = h[0] & 0x80, masked = h[1] & 0x80;
            std::uint64_t len = h[1] & 0x7F;
            if (len == 126) {
                std::uint8_t e[2];
                if (!read_all(fd, e, 2)) return;
                len = (e[0] << 8) | e[1];
            } else if (len == 127) {
                std::uint8_t e[8];
                if (!read_all(fd, e, 8)) return;
                len = 0;
                for (std::uint8_t b : e) len = (len << 8) | b;
            }
            if (len > (1u << 20)) return;
            std::uint8_t mask[4] = {};
            if (masked && !read_all(fd, mask, 4)) return;
            std::vector<std::uint8_t> payload(static_cast<std::size_t>(len));
            if (len && !read_all(fd, payload.data(), payload.size())) return;
            if (masked)
                for (std::size_t i = 0; i < payload.size(); ++i) payload[i] ^= mask[i & 3];
            switch (opcode) {
            case 0x1: // text: gateway control (kk-peer-gone)
                if (fin) text_message(reinterpret_cast<const char *>(payload.data()), payload.size());
                break;
            case 0x0: // continuation
            case 0x2: // binary
                message.insert(message.end(), payload.begin(), payload.end());
                if (fin) {
                    std::lock_guard lock(queue_mutex_);
                    if (queue_.size() >= kMaxQueued) queue_.pop_front();
                    queue_.push_back(std::move(message));
                    message.clear();
                }
                break;
            case 0x8:
                control(fd, 0x8, {});
                return;
            case 0x9:
                control(fd, 0xA, payload);
                break;
            default:
                break;
            }
        }
    }

    void loop() {
        int delay_ms = 500;
        bool reported = false;
        while (!stop_) {
            const int fd = dial();
            if (fd >= 0 && handshake(fd)) {
                {
                    std::lock_guard lock(write_mutex_);
                    fd_ = fd;
                    open_ = true;
                    for (auto &f : pending_) write_frame(f.data(), f.size());
                    pending_.clear();
                }
                std::fprintf(stderr, "[net] connected to %s\n", url_.c_str());
                delay_ms = 500;
                reported = false;
                session(fd);
                {
                    std::lock_guard lock(write_mutex_);
                    open_ = false;
                    fd_ = -1;
                }
                ::close(fd);
                if (!stop_) std::fprintf(stderr, "[net] disconnected from %s\n", url_.c_str());
            } else {
                if (fd >= 0) ::close(fd);
                if (!reported) std::fprintf(stderr, "[net] cannot reach %s, retrying\n", url_.c_str());
                reported = true;
            }
            for (int waited = 0; waited < delay_ms && !stop_; waited += 50) std::this_thread::sleep_for(std::chrono::milliseconds(50));
            delay_ms = std::min(delay_ms * 2, 5000);
        }
    }

    std::string url_;
    Url parts_;
    std::thread thread_;
    std::atomic<bool> stop_{false}, open_{false};
    bool valid_ = false;
    std::mutex write_mutex_, queue_mutex_;
    int fd_ = -1;
    std::deque<std::vector<std::uint8_t>> queue_, pending_;
    std::vector<std::uint8_t> scratch_;
    std::mt19937 rng_;
    std::uint64_t dropped_ = 0;
};

}

std::unique_ptr<Backend> make_websocket(const std::string &url) { return std::make_unique<NativeWebSocket>(url); }

}

#endif
