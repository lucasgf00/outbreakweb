// WebSocket backend for the browser build, through Emscripten's websocket API
// (link with -lwebsocket.js). The socket lives on the main browser thread; its
// callbacks only touch a mutex-protected queue, so the emulation thread can poll
// it without blocking. ws:// and wss:// both work here.
//
// The socket opens on the first frame the game sends, not when the adapter is created: the network IRX
// (SMAP, AVE, DHCP) only load and transmit when the player picks NETWORK PLAY, so a tab playing offline never
// takes a gateway session (reoutbreak docs/notes/backlog.md 0, the gateway filled up with idle tabs). The
// frames sent while the handshake runs (the first DHCP DISCOVER) wait in a short queue and go out on open.
#ifdef __EMSCRIPTEN__

#include "backend.hpp"

#include <emscripten/emscripten.h>
#include <emscripten/websocket.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>

namespace ps2::net {

namespace {

constexpr std::size_t kMaxQueued = 2048;

// Link statistics for the page (desync measurements, reoutbreak docs/notes/backlog.md 23/10): frames in and out,
// the longest gap between two incoming frames and how many gaps passed 250 ms. ps2web_net_stat() reads them and
// can reset them; the page only reports gaps for minutes with game traffic (the lobby is quiet for seconds).
std::atomic<std::uint32_t> g_rx{0}, g_tx{0}, g_gaps{0};
std::atomic<double> g_last_rx{0.0}, g_max_gap{0.0};
// Frames kept while the socket connects; DHCP retries anything older.
constexpr std::size_t kMaxPending = 64;

class BrowserWebSocket final : public Backend {
public:
    explicit BrowserWebSocket(std::string url) : url_(std::move(url)) {}

    ~BrowserWebSocket() override {
        if (socket_ > 0) {
            emscripten_websocket_close(socket_, 1000, "bye");
            emscripten_websocket_delete(socket_);
        }
    }

    void send(const std::uint8_t *frame, std::size_t size) override {
        std::unique_lock lock(send_mutex_);
        if (!open_ || socket_ <= 0) {
            bool dial = false;
            if (!started_) {
                // First frame from the game: the player chose to go online.
                started_ = true;
                dial = true;
            } else if (closed_ && emscripten_get_now() - last_try_ > (refused_ ? 20000 : 3000)) {
                // The gateway was down or went away: try again now and then, driven by the
                // game sending (DHCP retries), so starting the gateway later still works.
                closed_ = false;
                if (socket_ > 0) emscripten_websocket_delete(socket_);
                socket_ = 0;
                pending_.clear();
                dial = true;
            }
            if (dial || (socket_ > 0 && !closed_)) {
                if (pending_.size() >= kMaxPending) pending_.pop_front();
                pending_.emplace_back(frame, frame + size);
            }
            // Not under the lock: the websocket calls are proxied to the main thread, and this thread may run the
            // socket's callbacks (which take the lock) while it waits.
            lock.unlock();
            if (dial) open();
            return;
        }
        lock.unlock();
        g_tx.fetch_add(1, std::memory_order_relaxed);
        emscripten_websocket_send_binary(socket_, const_cast<std::uint8_t *>(frame), static_cast<std::uint32_t>(size));
    }

    bool receive(std::vector<std::uint8_t> &frame) override {
        std::lock_guard lock(mutex_);
        if (queue_.empty()) return false;
        frame = std::move(queue_.front());
        queue_.pop_front();
        return true;
    }

    bool connected() const override { return open_; }
    std::string describe() const override { return "browser websocket " + url_; }
    void send_text(const std::string &text) override {
        if (open_ && socket_ > 0) emscripten_websocket_send_utf8_text(socket_, text.c_str());
    }

private:
    void open() {
        if (!emscripten_websocket_is_supported()) {
            std::fprintf(stderr, "[net] this browser has no WebSocket support\n");
            return;
        }
        EmscriptenWebSocketCreateAttributes attrs;
        emscripten_websocket_init_create_attributes(&attrs);
        last_try_ = emscripten_get_now();
        attrs.url = url_.c_str();
        attrs.createOnMainThread = EM_TRUE;
        socket_ = emscripten_websocket_new(&attrs);
        if (socket_ <= 0) {
            std::fprintf(stderr, "[net] cannot open %s (%d)\n", url_.c_str(), socket_);
            socket_ = 0;
            closed_ = true;   // retried by the next frames, like a refused connection
            return;
        }
        emscripten_websocket_set_onopen_callback(socket_, this, [](int socket, const EmscriptenWebSocketOpenEvent *, void *self) -> EM_BOOL {
            auto *ws = static_cast<BrowserWebSocket *>(self);
            std::size_t flushed = 0;
            {
                // Under the lock, so a frame sent meanwhile is either queued here first or sent after these.
                std::lock_guard lock(ws->send_mutex_);
                for (auto &f : ws->pending_) emscripten_websocket_send_binary(socket, f.data(), static_cast<std::uint32_t>(f.size()));
                flushed = ws->pending_.size();
                ws->pending_.clear();
                ws->open_ = true;
            }
            ws->reported_ = false;
            std::fprintf(stderr, "[net] connected to %s (%zu queued frames sent)\n", ws->url_.c_str(), flushed);
            return EM_TRUE;
        });
        emscripten_websocket_set_onclose_callback(socket_, this, [](int, const EmscriptenWebSocketCloseEvent *e, void *self) -> EM_BOOL {
            auto *ws = static_cast<BrowserWebSocket *>(self);
            // 4429 = the gateway refused the session (too many sessions from this address, or full); the reason
            // says which and the page shows it. Retries slow down so they do not hit the handshake rate limit.
            ws->refused_ = e->code == 4429;
            if (ws->refused_) {
                if (!ws->reported_) std::fprintf(stderr, "[net] gateway refused the connection: %s\n", e->reason);
            } else if (ws->open_) std::fprintf(stderr, "[net] gateway closed the connection (%d)\n", e->code);
            else if (!ws->reported_) std::fprintf(stderr, "[net] cannot reach %s, the game stays offline (retrying)\n", ws->url_.c_str());
            ws->reported_ = true;
            ws->open_ = false;
            ws->closed_ = true;
            return EM_TRUE;
        });
        emscripten_websocket_set_onerror_callback(socket_, this, [](int, const EmscriptenWebSocketErrorEvent *, void *self) -> EM_BOOL {
            static_cast<BrowserWebSocket *>(self)->closed_ = true;
            return EM_TRUE;
        });
        emscripten_websocket_set_onmessage_callback(socket_, this, [](int, const EmscriptenWebSocketMessageEvent *e, void *self) -> EM_BOOL {
            if (e->isText) {
                // Gateway control (kk-peer-gone); the text arrives NUL-terminated.
                const char *text = reinterpret_cast<const char *>(e->data);
                text_message(text, strnlen(text, e->numBytes));
                return EM_TRUE;
            }
            {
                const double now = emscripten_get_now();
                const double last = g_last_rx.exchange(now);
                if (last > 0.0) {
                    const double gap = now - last;
                    if (gap > g_max_gap.load()) g_max_gap.store(gap);
                    if (gap > 250.0) g_gaps.fetch_add(1, std::memory_order_relaxed);
                }
                g_rx.fetch_add(1, std::memory_order_relaxed);
            }
            auto *ws = static_cast<BrowserWebSocket *>(self);
            std::lock_guard lock(ws->mutex_);
            if (ws->queue_.size() >= kMaxQueued) ws->queue_.pop_front();
            ws->queue_.emplace_back(e->data, e->data + e->numBytes);
            return EM_TRUE;
        });
    }

    std::string url_;
    EMSCRIPTEN_WEBSOCKET_T socket_ = 0;
    std::atomic<bool> open_{false}, closed_{false};
    std::atomic<bool> reported_{false}, refused_{false};
    bool started_ = false;
    double last_try_ = 0;
    std::mutex mutex_, send_mutex_;
    std::deque<std::vector<std::uint8_t>> queue_, pending_;
};

}

// which: 0 frames in, 1 frames out, 2 longest gap between incoming frames (ms), 3 gaps over 250 ms; reset clears it.
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_net_stat(int which, int reset) {
    switch (which) {
    case 0: return reset ? g_rx.exchange(0) : g_rx.load();
    case 1: return reset ? g_tx.exchange(0) : g_tx.load();
    case 2: return reset ? g_max_gap.exchange(0.0) : g_max_gap.load();
    case 3: return reset ? g_gaps.exchange(0) : g_gaps.load();
    default: return 0.0;
    }
}

std::unique_ptr<Backend> make_websocket(const std::string &url) { return std::make_unique<BrowserWebSocket>(url); }

}

#endif
