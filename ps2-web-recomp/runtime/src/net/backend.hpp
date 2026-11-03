#pragma once

// Where the emulated network adapter's Ethernet frames go. The adapter (DEV9 +
// SMAP, runtime/src/iop/dev9.cpp) only sees this interface; the native runner
// and the browser build each provide a WebSocket implementation that talks to the
// same gateway (net/gateway in the reoutbreak project), one frame per binary
// message, the same framing v86 uses for its network relay.

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace ps2::net {

class Backend {
public:
    virtual ~Backend() = default;
    // Queue a frame for the network. Called from the emulation thread; must not block.
    virtual void send(const std::uint8_t *frame, std::size_t size) = 0;
    // Take the next frame that arrived from the network, if any. Emulation thread, non-blocking.
    virtual bool receive(std::vector<std::uint8_t> &frame) = 0;
    virtual bool connected() const = 0;
    virtual std::string describe() const = 0;
    // A control message for the gateway (WebSocket text), dropped when not connected. Emulation thread.
    virtual void send_text(const std::string &) {}
};

// PS2_NET (or --net) holds the gateway URL, e.g. ws://127.0.0.1:8765/eth.
// Returns an empty string when networking is off.
std::string configured_url();
// The adapter's MAC address: PS2_NET_MAC=aa:bb:cc:dd:ee:ff, or a fixed address
// in Sony's range when unset.
void configured_mac(std::uint8_t mac[6]);
// Optional pcap of every frame the adapter sends or receives (PS2_NET_PCAP=path).
std::string configured_pcap();

std::unique_ptr<Backend> make_websocket(const std::string &url);

// Text messages from the gateway (control, never game traffic): {"type":"kk-peer-gone","tag":"xxxxxxxx"} says a
// teammate left a game for good (reoutbreak docs/notes/protocol.md 6.11). The backends call this from their reader;
// the emulation thread polls peer_gone_notices(), which counts the notices seen so far.
void text_message(const char *data, std::size_t size);
std::uint32_t peer_gone_notices();
// {"type":"kk-peer-left","tag":"xxxxxxxx","slot":n}: a teammate left the scenario while its session goes on (dead
// or zombified, on the results screen; protocol.md 6.17); its slot in the game's peer table comes with it.
// peer_left_notices() counts them, peer_left_slot() is the slot of the last one.
std::uint32_t peer_left_notices();
int peer_left_slot();
// Sends a control text to the gateway on the adapter's WebSocket (no-op when offline), e.g. left-scenario.
void send_control(const std::string &text);

// Logging helper shared by the adapter and the backends.
bool trace();

}
