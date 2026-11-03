#pragma once

// The PS2 network adapter: DEV9 (the expansion-bay bus bridge, CXD9611), the
// SPEED chip behind it and its SMAP Ethernet controller (EMAC3 + DP83846A PHY +
// 93C46 EEPROM holding the MAC address). Emulated at the register level so the
// game's own DEV9.IRX / SMAP.IRX / AVE-TCP modules drive it unchanged.
//
// It is only present when networking is configured (PS2_NET / --net); otherwise
// every address falls through to the generic register map exactly as before, and
// DEV9.IRX keeps reporting "unknown dev9 hardware".

#include <cstdint>

namespace ps2::iop::dev9 {

inline constexpr unsigned kIrqLine = 13;
inline constexpr unsigned kDmaChannel = 8;

void reset();
bool enabled();

// True when the physical address belongs to the adapter (and it is enabled).
bool owns(std::uint32_t a);
std::uint32_t read(std::uint32_t a, unsigned size);
void write(std::uint32_t a, std::uint32_t v, unsigned size);

// IOP DMA channel 8. Returns true when the transfer was handled.
bool dma(std::uint32_t madr, std::uint32_t words, bool to_device);

// Pull frames from the network backend; called from a recurring IOP job.
void poll();

}
