#include "ps2/disc.hpp"
#include "ps2/memory.hpp"
#include "ps2/system.hpp"

#include <emscripten.h>
#include <emscripten/threading.h>
#include <pthread.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace ps2::gs::gpu {
double counter(int which);
}

void register_generated_code();
void register_generated_vu();

EM_JS_DEPS(ps2web, "$stringToNewUTF8,$UTF8ToString");

// Reads one 256 KiB chunk of the disc and settles *state to 3 (data in out) or 4 (gave up; the caller asks again
// later, it never gets zeros). Bad networks and cold CDN caches leave single range requests hanging for tens of
// seconds, so each read: looks first in the page's own Cache Storage (one entry per chunk, only complete and checked
// chunks are put there, so a second visit reads what it already downloaded without the network); on a miss goes to the
// network bypassing the HTTP cache (cache: no-store: with the HTTP cache, Chrome makes every range of the same piece
// URL wait behind a slow one, measured 10 s for all four); aborts an attempt that receives no bytes
// for 15 s, starts a second attempt in parallel when the first is not done in 6 s, retries failures with backoff
// (0.25 to 8 s, 8 attempts), checks status, Content-Range and the exact length, and counts everything in
// Module.discStats for the bug report.
EM_JS(void, ps2web_fetch, (const char *url, double offset, int size, void *out, std::atomic<std::uint32_t> *state), {
    let target = UTF8ToString(url);
    const finish = (value) => {
        Atomics.store(HEAP32, state >> 2, value);
        Atomics.notify(HEAP32, state >> 2);
    };
    const where = offset;
    let total = Module.discSize;
    if (Module.discPiece) {
        const index = Math.floor(offset / Module.discPiece);
        target = new URL('disc/' + String(index).padStart(3, '0') + '.bin', target).href;
        offset -= index * Module.discPiece;
        total = Math.min(Module.discPiece, Module.discSize - index * Module.discPiece);
    }
    const stats = Module.discStats || (Module.discStats = {requests: 0, ok: 0, failures: 0, retries: 0, hedges: 0, gaveUp: 0,
        slowestMs: 0, slowestAt: 0, truncatedBytes: 0, bytes: 0, pending: 0, lastError: '', cached: 0, stored: 0});
    const range = 'bytes=' + offset + '-' + (offset + size - 1);
    const key = target + '?r=' + offset + '-' + size;
    let store = null;
    try {
        if (self.caches && !Module.noDiscCache) {
            Module.discCache = Module.discCache || caches.open('reo-disc-v1-' + Module.discSize).catch(() => null);
            store = Module.discCache;
        }
    } catch (e) {}
    const started = performance.now();
    const controllers = new Set();
    let settled = false, live = 0, failed = 0, hedge = 0;
    ++stats.pending;
    const settle = (value) => {
        settled = true;
        --stats.pending;
        clearTimeout(hedge);
        for (const c of controllers) c.abort();
        finish(value);
    };
    const attempt = () => {
        const controller = new AbortController();
        let last = performance.now(), ended = false;
        controllers.add(controller);
        ++live;
        ++stats.requests;
        const watchdog = setInterval(() => { if (performance.now() - last > 15000) controller.abort(); }, 1000);
        const end = () => {
            if (ended) return;
            ended = true;
            clearInterval(watchdog);
            controllers.delete(controller);
            --live;
        };
        fetch(target, {headers: {Range: range}, cache: 'no-store', signal: controller.signal})
            .then(async (response) => {
                last = performance.now();
                if (response.status !== 206) throw new Error('expected 206, got ' + response.status);
                const contentRange = response.headers.get('Content-Range');
                const match = contentRange && new RegExp('^bytes ([0-9]+)-([0-9]+)/([0-9]+)$').exec(contentRange);
                if (!match || Number(match[1]) !== offset || Number(match[2]) !== offset + size - 1 ||
                    (total && Number(match[3]) !== total)) {
                    throw new Error('invalid Content-Range: ' + contentRange);
                }
                const body = new Uint8Array(size);
                const reader = response.body.getReader();
                let got = 0;
                for (;;) {
                    const part = await reader.read();
                    if (part.done) break;
                    last = performance.now();
                    if (got + part.value.length > size) throw new Error('more than ' + size + ' bytes');
                    body.set(part.value, got);
                    got += part.value.length;
                }
                if (got !== size) {
                    stats.truncatedBytes += size - got;
                    throw new Error('truncated: ' + got + ' of ' + size + ' bytes');
                }
                end();
                if (settled) return;
                HEAPU8.set(body, out);
                if (store) store.then((c) => c && c.put(key, new Response(body, {headers: {'Content-Type': 'application/octet-stream'}})))
                    .then(() => { ++stats.stored; }, () => {});
                const ms = Math.round(performance.now() - started);
                ++stats.ok;
                stats.bytes += size;
                if (ms > stats.slowestMs) {
                    stats.slowestMs = ms;
                    stats.slowestAt = where;
                }
                settle(3);
            })
            .catch((e) => {
                const aborted = controller.signal.aborted;
                end();
                if (settled) return;
                ++failed;
                ++stats.failures;
                stats.lastError = (aborted ? 'no data for 15 s' : String((e && e.message) || e)).slice(0, 100);
                console.warn('disc read failed at ' + where + ' (' + range + '), attempt ' + failed + ': ' + stats.lastError);
                if (live > 0) return;
                if (failed >= 8) {
                    ++stats.gaveUp;
                    settle(4);
                    return;
                }
                ++stats.retries;
                setTimeout(() => { if (!settled) attempt(); }, Math.min(8000, 250 << Math.min(failed - 1, 5)) * (0.75 + Math.random() * 0.5));
            });
    };
    const network = () => {
        attempt();
        hedge = setTimeout(() => {
            if (settled || live !== 1) return;
            ++stats.hedges;
            attempt();
        }, 6000);
    };
    if (!store) {
        network();
        return;
    }
    store.then((c) => c ? c.match(key) : null)
        .then((r) => r ? r.arrayBuffer() : null)
        .then((buffer) => {
            if (buffer && buffer.byteLength === size) return buffer;
            const warming = Module.discWarmWait && Module.discWarmWait(key);
            return warming ? warming.then((body) => (body && body.byteLength === size ? body : null)) : null;
        })
        .then((buffer) => {
            if (!buffer || buffer.byteLength !== size) return network();
            HEAPU8.set(buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer), out);
            ++stats.cached;
            stats.bytes += size;
            settle(3);
        }, () => network());
});

EM_JS(int, ps2web_save_program, (const char *name, const std::uint8_t *code, int size), {
    try {
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', 'vu1/' + UTF8ToString(name), false);
        xhr.send(HEAPU8.slice(code, code + size));
        return xhr.status;
    } catch (e) {
        return 0;
    }
});

EM_JS(double, ps2web_disc_size, (), { return Module.discSize || 0; });
EM_JS(char *, ps2web_state_url, (), { return Module.stateUrl ? stringToNewUTF8(new URL(Module.stateUrl, location.href).href) : 0; });
EM_JS(double, ps2web_url_size, (const char *url), {
    try {
        const xhr = new XMLHttpRequest();
        xhr.open('HEAD', UTF8ToString(url), false);
        xhr.send();
        return xhr.status === 200 ? Number(xhr.getResponseHeader('Content-Length') || 0) : 0;
    } catch (e) {
        return 0;
    }
});
EM_JS(double, ps2web_url_read, (const char *url, std::uint8_t *out, double size), {
    try {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', UTF8ToString(url), false);
        xhr.responseType = 'arraybuffer';
        xhr.send();
        if (xhr.status !== 200) return 0;
        const bytes = new Uint8Array(xhr.response);
        HEAPU8.set(bytes.subarray(0, size), out);
        return bytes.length;
    } catch (e) {
        return 0;
    }
});
EM_JS(char *, ps2web_disc_url, (), { return stringToNewUTF8(new URL(Module.discUrl, location.href).href); });
// The render loop waits for the browser to show each frame so it does not queue presents faster than the
// display, but requestAnimationFrame stops in hidden or occluded pages and post_present would then block the
// emulation thread. The game is online and must keep running in the background, so a hidden page never waits
// and a small worker clock (worker timers are not throttled like page timers) releases a frame rAF never shows.
EM_JS(void, ps2web_frame_done, (), {
    if (!Module.shownChannel) {
        Module.shownChannel = new MessageChannel();
        Module.shownWaiters = [];
        Module.shownRelease = () => {
            Module.shownPending = false;
            const waiters = Module.shownWaiters;
            Module.shownWaiters = [];
            for (const resolve of waiters) resolve();
        };
        Module.shownChannel.port1.onmessage = Module.shownRelease;
        document.addEventListener('visibilitychange', () => { if (document.hidden) Module.shownRelease(); });
        Module.shownSerial = 0;
        try {
            const source = 'onmessage = (e) => setTimeout(() => postMessage(e.data), 50);';
            Module.shownClock = new Worker(URL.createObjectURL(new Blob([source], { type: 'application/javascript' })));
            Module.shownClock.onmessage = (e) => { if (e.data === Module.shownSerial) Module.shownRelease(); };
        } catch (e) {}
    }
    if (!document.hidden) {
        Module.shownPending = true;
        const serial = ++Module.shownSerial;
        requestAnimationFrame(() => { if (serial === Module.shownSerial) Module.shownChannel.port2.postMessage(0); });
        if (Module.shownClock) Module.shownClock.postMessage(serial);
    }
    if (Module.onFrame) Module.onFrame();
});
EM_ASYNC_JS(void, ps2web_wait_shown, (), {
    if (Module.shownPending) await new Promise((resolve) => Module.shownWaiters.push(resolve));
});
EM_JS(void, ps2web_blit, (const std::uint32_t *rgba, int width, int height), {
    let canvas = Module.canvas;
    if (!Module.blitContext) Module.blitContext = canvas.getContext('2d');
    if (!Module.blitContext) {
        // The canvas already holds a WebGPU context (switched to copies mid-game), so draw on a
        // 2D canvas laid over it; input keeps going to the original canvas.
        if (!Module.blitOverlay) {
            Module.blitOverlay = document.createElement('canvas');
            Module.blitOverlay.style.position = 'absolute';
            Module.blitOverlay.style.pointerEvents = 'none';
            Module.blitOverlay.style.background = '#000';
            canvas.insertAdjacentElement('afterend', Module.blitOverlay);
        }
        Module.blitOverlayContext = Module.blitOverlayContext || Module.blitOverlay.getContext('2d');
    }
    if (Module.blitOverlay) {
        const overlay = Module.blitOverlay;
        if (overlay.width !== canvas.width || overlay.height !== canvas.height) {
            overlay.width = canvas.width;
            overlay.height = canvas.height;
        }
        overlay.style.left = canvas.offsetLeft + 'px';
        overlay.style.top = canvas.offsetTop + 'px';
        overlay.style.width = canvas.clientWidth + 'px';
        overlay.style.height = canvas.clientHeight + 'px';
        canvas = overlay;
    }
    if (!Module.blitImage || Module.blitImage.width !== width || Module.blitImage.height !== height) {
        Module.blitImage = new ImageData(width, height);
        Module.blitSource = new OffscreenCanvas(width, height);
    }
    Module.blitImage.data.set(HEAPU8.subarray(rgba, rgba + width * height * 4));
    Module.blitSource.getContext('2d').putImageData(Module.blitImage, 0, 0);
    const ctx = Module.blitOverlay ? Module.blitOverlayContext : Module.blitContext;
    const scale = Math.min(canvas.width / 4, canvas.height / 3);
    const w = scale * 4, h = scale * 3;
    const sx = Math.max(1, Math.floor(w / width)), sy = Math.max(1, Math.floor(h / height));
    let source = Module.blitSource;
    if (sx > 1 || sy > 1) {
        if (!Module.blitSharp || Module.blitSharp.width !== width * sx || Module.blitSharp.height !== height * sy)
            Module.blitSharp = new OffscreenCanvas(width * sx, height * sy);
        const sharp = Module.blitSharp.getContext('2d');
        sharp.imageSmoothingEnabled = false;
        sharp.drawImage(Module.blitSource, 0, 0, width * sx, height * sy);
        source = Module.blitSharp;
    }
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(source, (canvas.width - w) / 2, (canvas.height - h) / 2, w, h);
});

namespace {

constexpr std::size_t kChunk = 256u << 10;
constexpr std::uint32_t kSlots = 256;
constexpr std::uint64_t kReadAhead = 16;
constexpr std::uint32_t kAudioFrames = 1u << 14;

enum : std::uint32_t { kEmpty, kWanted, kLoading, kReady, kFailed };

struct Slot {
    std::atomic<std::uint32_t> state{kEmpty};
    std::uint64_t index = 0, used = 0;
    std::uint32_t size = 0;
    std::uint8_t *data = nullptr;
};

struct AudioRing {
    std::atomic<std::uint32_t> write{0}, read{0};
    std::uint32_t capacity = kAudioFrames;
    std::atomic<std::uint32_t> active{0};
    std::int16_t samples[kAudioFrames * 2];
};

AudioRing g_audio;
Slot g_slots[kSlots];
std::uint64_t g_disc_size = 0, g_use_clock = 0, g_last_chunk = ~0ull, g_run = 0;
char *g_disc_url = nullptr;
char *g_state_url = nullptr;

Slot *find_slot(std::uint64_t index) {
    for (Slot &s : g_slots)
        if (s.state.load(std::memory_order_acquire) != kEmpty && s.index == index) return &s;
    return nullptr;
}

Slot *want(std::uint64_t index) {
    if (index * kChunk >= g_disc_size) return nullptr;
    if (Slot *s = find_slot(index)) return s;
    Slot *victim = nullptr;
    for (Slot &s : g_slots) {
        const std::uint32_t state = s.state.load(std::memory_order_acquire);
        if (state == kEmpty) {
            victim = &s;
            break;
        }
        if ((state == kReady || state == kFailed) && (!victim || s.used < victim->used)) victim = &s;
    }
    if (!victim) return nullptr;
    if (!victim->data) victim->data = new std::uint8_t[kChunk];
    victim->index = index;
    victim->size = static_cast<std::uint32_t>(std::min<std::uint64_t>(kChunk, g_disc_size - index * kChunk));
    victim->used = ++g_use_clock;
    victim->state.store(kWanted, std::memory_order_release);
    ps2::wake_render_thread();
    return victim;
}

void advance(std::uint64_t index) {
    if (index <= g_last_chunk && g_last_chunk - index <= kReadAhead * 4) return;
    g_run = index == g_last_chunk + 1 ? g_run + 1 : 0;
    g_last_chunk = index;
}

void read_ahead(std::uint64_t index) {
    for (std::uint64_t k = 1; k <= std::min(g_run * 2, kReadAhead); ++k) want(index + k);
}

// The synchronous read path (module loads, ioman file reads) waits for the chunk however long it takes: a failed
// read is asked for again with backoff instead of handing zeros to the game (zeros used to reach it after four
// failed tries 100 ms apart, which can break an audio stream or a file for the rest of the session).
const Slot *chunk(std::uint64_t index) {
    if (index * kChunk >= g_disc_size) return nullptr;
    for (std::uint32_t attempt = 0;; ++attempt) {
        Slot *s = want(index);
        if (!attempt) {
            advance(index);
            read_ahead(std::max(index, g_last_chunk));
        }
        if (!s) {
            usleep(10000);
            continue;
        }
        if (s->state.load(std::memory_order_acquire) == kFailed) {
            s->state.store(kWanted, std::memory_order_release);
            ps2::wake_render_thread();
        }
        for (std::uint32_t state = s->state.load(); state != kReady && state != kFailed; state = s->state.load())
            emscripten_futex_wait(&s->state, state, INFINITY);
        if (s->state.load() == kReady) {
            s->used = ++g_use_clock;
            return s;
        }
        if (attempt < 3 || attempt % 20 == 0)
            std::fprintf(stderr, "[web] disc read at chunk %llu failed (round %u), asking again\n",
                         static_cast<unsigned long long>(index), attempt + 1);
        usleep(std::min(8000u, 500u << std::min(attempt, 4u)) * 1000u);
    }
}

bool disc_ready(std::uint64_t offset, std::size_t size) {
    if (!size) return true;
    const std::uint64_t last = (offset + size - 1) / kChunk;
    bool ready = true;
    for (std::uint64_t index = offset / kChunk; index <= last; ++index) {
        Slot *s = want(index);
        if (s && s->state.load(std::memory_order_acquire) == kFailed) {
            s->state.store(kWanted, std::memory_order_release);
            ps2::wake_render_thread();
        }
        ready = ready && s && s->state.load(std::memory_order_acquire) == kReady;
        advance(index);
    }
    read_ahead(std::max(last, g_last_chunk));
    return ready;
}

std::size_t read_disc(std::uint64_t offset, void *out, std::size_t size) {
    std::size_t done = 0;
    auto *to = static_cast<std::uint8_t *>(out);
    while (done < size && offset + done < g_disc_size) {
        const std::uint64_t at = offset + done;
        const Slot *c = chunk(at / kChunk);
        if (!c) break;
        const std::size_t within = static_cast<std::size_t>(at % kChunk);
        const std::size_t take = std::min<std::size_t>(size - done, c->size - within);
        std::memcpy(to + done, c->data + within, take);
        done += take;
    }
    return done;
}

void service_disc() {
    for (Slot &s : g_slots) {
        std::uint32_t expected = kWanted;
        if (s.state.compare_exchange_strong(expected, kLoading, std::memory_order_acq_rel))
            ps2web_fetch(g_disc_url, static_cast<double>(s.index * kChunk), static_cast<int>(s.size), s.data, &s.state);
    }
}

void push_audio(const std::int16_t *samples, std::size_t frames) {
    std::uint32_t write = g_audio.write.load(std::memory_order_relaxed);
    const std::uint32_t read = g_audio.read.load(std::memory_order_acquire);
    for (std::size_t i = 0; i < frames && write - read < kAudioFrames; ++i, ++write) {
        const std::uint32_t at = (write & (kAudioFrames - 1)) * 2;
        g_audio.samples[at] = samples[i * 2];
        g_audio.samples[at + 1] = samples[i * 2 + 1];
    }
    g_audio.write.store(write, std::memory_order_release);
}

double audio_queued_ms() { return static_cast<double>(g_audio.write.load() - g_audio.read.load()) / 48.0; }

double g_next_frame = 0.0;
// Audio the game may queue ahead before pace() waits (ms of the 341-ms ring). 80 by default; the page's "stable
// audio" option raises it so a slow machine's hiccups do not empty the ring (more cushion, more audio delay).
std::atomic<double> g_audio_target{80.0};
std::atomic<double> g_emulation_idle{0.0}, g_render_idle{0.0};

void pace() {
    ps2::post_present();
    if (g_audio.active.load()) {
        // The audio worklet paces the game while it drains the ring. A suspended AudioContext stops draining
        // it: after 100 ms fall back to the clock below (the worklet sets active again when it runs) instead
        // of freezing the emulation.
        const double since = emscripten_get_now();
        const double target = g_audio_target.load();
        while (audio_queued_ms() > target && emscripten_get_now() - since < 100.0) usleep(1000);
        if (audio_queued_ms() <= target) {
            g_next_frame = 0.0;
            return;
        }
        g_audio.active.store(0);
    }
    const double period = ps2::g_options.pal ? 20.0 : 1000.0 / 59.94;
    const double now = emscripten_get_now();
    if (g_next_frame == 0.0 || now - g_next_frame > 200.0) g_next_frame = now;
    g_next_frame += period;
    if (g_next_frame > now) usleep(static_cast<useconds_t>((g_next_frame - now) * 1000.0));
}

void on_vblank() {
    const double start = emscripten_get_now();
    pace();
    g_emulation_idle.store(g_emulation_idle.load() + emscripten_get_now() - start);
}

struct Launch {
    std::vector<std::string> args;
};

void *emulate(void *data) {
    const Launch &launch = *static_cast<const Launch *>(data);
    if (g_disc_size && !ps2::disc::open("disc", g_disc_size, read_disc, disc_ready)) std::fprintf(stderr, "[web] cannot read the disc image\n");
    std::uint32_t entry = 0;
    if (!ps2::load_elf("/game/core.elf", entry)) {
        std::fprintf(stderr, "cannot load the game executable\n");
        return nullptr;
    }
    ps2::boot(entry, launch.args);
    if (g_state_url) {
        const double size = ps2web_url_size(g_state_url);
        std::vector<std::uint8_t> data(static_cast<std::size_t>(size));
        if (size <= 0 || ps2web_url_read(g_state_url, data.data(), size) != size || !ps2::load_state(data.data(), data.size())) {
            std::fprintf(stderr, "[web] cannot load the state %s\n", g_state_url);
            return nullptr;
        }
    }
    ps2::run();
    return nullptr;
}

}

extern "C" EMSCRIPTEN_KEEPALIVE void ps2web_pad(int pad, int buttons, int lx, int ly, int rx, int ry) {
    ps2::set_pad_buttons(static_cast<std::uint16_t>(buttons), pad);
    ps2::set_pad_axis(0, static_cast<std::uint8_t>(rx), pad);
    ps2::set_pad_axis(1, static_cast<std::uint8_t>(ry), pad);
    ps2::set_pad_axis(2, static_cast<std::uint8_t>(lx), pad);
    ps2::set_pad_axis(3, static_cast<std::uint8_t>(ly), pad);
}

extern "C" EMSCRIPTEN_KEEPALIVE void ps2web_pad_connected(int pad, int connected) { ps2::set_pad_connected(pad, connected != 0); }

extern "C" EMSCRIPTEN_KEEPALIVE void ps2web_pressure(int pad, int a, int b, int c, int d) {
    const int packed[4] = {a, b, c, d};
    for (int i = 0; i < 16; ++i) ps2::set_pad_pressure(i, static_cast<std::uint8_t>(static_cast<unsigned>(packed[i / 4]) >> (i % 4 * 8)), pad);
}

extern "C" EMSCRIPTEN_KEEPALIVE int ps2web_rumble(int pad) { return ps2::pad_rumble(pad); }

extern "C" EMSCRIPTEN_KEEPALIVE void ps2web_resize(int width, int height) {
    ps2::resize_window(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height));
}

// Asks the render loop to stop presenting on the WebGPU canvas and draw frame copies instead.
std::atomic<bool> g_force_copy{false};
extern "C" EMSCRIPTEN_KEEPALIVE void ps2web_present_copy() { g_force_copy.store(true); }

extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_gpu_ms() { return ps2::gpu_busy_ms(); }
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_gpu_counter(int which) { return ps2::gs::gpu::counter(which); }
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_frames() { return static_cast<double>(ps2::frames_run()); }
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_primitives() { return static_cast<double>(ps2::primitives_drawn()); }
extern "C" EMSCRIPTEN_KEEPALIVE void *ps2web_audio_ring() { return &g_audio; }
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_audio_queued_ms() { return audio_queued_ms(); }
extern "C" EMSCRIPTEN_KEEPALIVE void ps2web_set_audio_target(double ms) { g_audio_target.store(ms < 40.0 ? 40.0 : ms > 250.0 ? 250.0 : ms); }
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_vu_interpreted() { return static_cast<double>(ps2::vu1_interpreted_calls()); }
// EE RAM base inside the wasm memory, for the page's status tracker (read only): Module.HEAPU8[ps2web_ee_ram() + address].
extern "C" EMSCRIPTEN_KEEPALIVE std::uint8_t *ps2web_ee_ram() { return ps2::g_ram; }
extern "C" EMSCRIPTEN_KEEPALIVE double ps2web_idle_ms(int which) {
    if (which == 2) return ps2::vu_thread_idle_ms();
    return which ? g_render_idle.load() : g_emulation_idle.load();
}

int main(int argc, char **argv) {
    static Launch launch;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--arg" && i + 1 < argc) launch.args.push_back(argv[++i]);
        else if (a == "--frames" && i + 1 < argc) ps2::g_options.max_frames = std::strtoull(argv[++i], nullptr, 10);
        else if (a == "--env" && i + 1 < argc) {
            // KEY=VALUE set at start time (the page's Module.ENV is fixed before the player
            // fills the pre-game menu, e.g. PS2_NET_ACCOUNT / PS2_NET_PASSWORD).
            const std::string kv = argv[++i];
            const auto eq = kv.find('=');
            if (eq != std::string::npos && eq > 0) setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
        }
        else if (a == "--press" && i + 1 < argc) {
            ps2::Press press;
            if (ps2::parse_press(argv[++i], press)) ps2::g_options.presses.push_back(press);
        }
    }
    if (launch.args.empty()) {
        std::string boot = "/game/core.elf";
        if (std::FILE *f = std::fopen("/game/boot.txt", "rb")) {
            char text[256] = {};
            const std::size_t n = std::fread(text, 1, sizeof text - 1, f);
            std::fclose(f);
            std::string line(text, n);
            line.erase(line.find_last_not_of(" \r\n\t") + 1);
            if (!line.empty()) boot = line;
        }
        launch.args.push_back(boot);
    }
    g_disc_size = static_cast<std::uint64_t>(ps2web_disc_size());
    g_disc_url = ps2web_disc_url();
    g_state_url = ps2web_state_url();
    ps2::g_options.on_vblank = on_vblank;
    ps2::g_options.on_vu_program = [](const std::string &name, const std::uint8_t *code, std::size_t size) {
        static bool unsupported = false;
        if (unsupported) return;
        const int status = ps2web_save_program(name.c_str(), code, static_cast<int>(size));
        if (status == 200 || status == 201) std::fprintf(stderr, "[web] saved VU1 program %s\n", name.c_str());
        else unsupported = true;
    };
    if (!EM_ASM_INT({ return Module.noCard ? 1 : 0; })) {
        ps2::g_options.memcard_path = "/memcard/Mcd001.ps2";
        ps2::g_options.on_memcard_saved = [] { MAIN_THREAD_ASYNC_EM_ASM({ if (Module.ps2CardSaved) Module.ps2CardSaved(); }); };
    }
    ps2::set_audio_sink(push_audio);
    register_generated_code();
    register_generated_vu();

    ps2::init_graphics();
    const bool no_surface = EM_ASM_INT({ return Module.noSurface ? 1 : 0; });
    bool copy = EM_ASM_INT({ return Module.presentCopy ? 1 : 0; });
    bool surface = false;
    if (!copy && !no_surface) {
        ps2::WindowTarget target;
        target.kind = ps2::WindowTarget::Canvas;
        target.selector = "#canvas";
        target.width = static_cast<std::uint32_t>(EM_ASM_INT({ return Module.canvas.width; }));
        target.height = static_cast<std::uint32_t>(EM_ASM_INT({ return Module.canvas.height; }));
        surface = ps2::attach_window(target);
    }
    copy = copy || (!surface && !no_surface);
    std::fprintf(stderr, "[web] %s\n", surface ? "presenting through WebGPU" : copy ? "presenting through a 2D canvas copy" : "not presenting");

    ps2::start_render_thread();
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16u << 20);
    if (!std::getenv("PS2_NO_VU_THREAD")) {
        ps2::start_vu_thread();
        pthread_t vu_thread;
        if (pthread_create(&vu_thread, &attr, [](void *) -> void * {
                ps2::run_vu_thread();
                return nullptr;
            }, nullptr) != 0) {
            std::fprintf(stderr, "[web] cannot start the VU thread\n");
            return 1;
        }
    }
    pthread_t thread;
    if (pthread_create(&thread, &attr, emulate, &launch) != 0) {
        std::fprintf(stderr, "[web] cannot start the emulation thread\n");
        return 1;
    }
    const std::function<void(const std::uint32_t *, std::uint32_t, std::uint32_t)> blit = [](const std::uint32_t *rgba, std::uint32_t width, std::uint32_t height) {
        ps2web_blit(rgba, static_cast<int>(width), static_cast<int>(height));
    };
    const std::function<void(const std::uint32_t *, std::uint32_t, std::uint32_t)> none;
    const std::function<void()> wait_shown = [] {
        static int since_skip = 0;
        static double window_start = 0.0, window_gpu = 0.0, gpu_load = 1.0;
        const double start = emscripten_get_now();
        if (start - window_start > 500.0) {
            const double gpu = ps2::gpu_busy_ms();
            gpu_load = window_start > 0.0 ? (gpu - window_gpu) / (start - window_start) : 1.0;
            window_start = start;
            window_gpu = gpu;
        }
        if (++since_skip > 8 && gpu_load < 0.8 && g_audio.active.load() && audio_queued_ms() < g_audio_target.load() * 0.5) {
            since_skip = 0;
            return;
        }
        ps2web_wait_shown();
        g_render_idle.store(g_render_idle.load() + emscripten_get_now() - start);
    };
    for (;;) {
        service_disc();
        if (surface && (g_force_copy.load() || ps2::present_failures() >= 3)) {
            const bool automatic = !g_force_copy.load();
            surface = false;
            copy = true;
            std::fprintf(stderr, "[web] presenting through a 2D canvas copy (safe mode, %s)\n", automatic ? "canvas presents kept failing" : "requested");
            EM_ASM({ if (Module.onPresentFallback) Module.onPresentFallback($0); }, automatic ? 1 : 0);
        }
        const ps2::Rendered rendered = ps2::render_pending(surface, copy ? blit : none, wait_shown, 6.0);
        if (rendered == ps2::Rendered::presented) ps2web_frame_done();
        if (rendered != ps2::Rendered::idle) {
            ps2::browser_yield();
        } else {
            const double start = emscripten_get_now();
            ps2::wait_render_work(50);
            g_render_idle.store(g_render_idle.load() + emscripten_get_now() - start);
        }
    }
}
