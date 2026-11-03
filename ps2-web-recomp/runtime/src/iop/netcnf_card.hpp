#pragma once

#include <cstdint>
#include <vector>

namespace ps2::iop::netcnf {

// Adds mc0:/BWNETCNF with the "REO gateway (DHCP)" network configuration to a
// raw 8 MiB card image (528-byte pages with ECC) unless the card already has
// one; formats a blank card first. Existing saves are kept. Returns whether the
// image changed.
bool ensure(std::vector<std::uint8_t> &raw);

// Runtime service for the patched network menu of RE Outbreak (scripts/patches/SLUS-20765.txt),
// reached through the private syscalls 0x7F00 (full) and 0x7F01 (password only):
//   password_buf  16 bytes: zeroed, then PS2_NET_PASSWORD (letters/digits, <= 15).
//   account_field 20 bytes of the NET save in RAM: PS2_NET_ACCOUNT is written there if empty.
//   name_buf      16 bytes: zeroed, then the account ID (the login name).
//   allow_auto    nonzero: the first call of the run reports "choose GO ONLINE" unless PS2_NET_AUTO=0.
// Returns 0 = no account, 1 = account set, 2 = account set and GO ONLINE automatic
// (password only: 1 if a password was preloaded).
std::uint32_t login_hook(bool full, std::uint32_t password_buf, std::uint32_t account_field, std::uint32_t allow_auto, std::uint32_t name_buf);

// Private syscall 0x7F02, LOGIN NAME / PASSWORD screen: returns 2 (choose ENTRY at once) the
// first time in the run when the password buffer is not empty and PS2_NET_AUTO is not 0, else 1.
std::uint32_t login_auto_entry(std::uint32_t password_buf);

// Private syscalls 0x7F03-0x7F06, generic hooks of RE Outbreak's network menus (scripts/patches/SLUS-20765.txt):
//   0x7F03 menu_confirm(ra)         netwk X check 0x5F6AF0: X pressed (1/0)
//   0x7F04 menu_directions(ra)      netwk d-pad read 0x5F6AA0: pad bits (1 up, 2 down, 0x10 X, ...)
//   0x7F05 lobby_button(mask, ra)   trampoline in front of the lobby button check 0x570AD0: 1 = pressed
//                                   now (the trampoline calls the original when 0)
//   0x7F06 netcnf_list_pad()        netcnf GUI "Select Network Configuration" list: pad word 0x324210
// ra = return address of the check (which screen asks). Besides what the game reads, they make the
// automatic presses that go from NETWORK PLAY to the area list: once per run each, PS2_NET_AUTO=0 turns
// them off, PS2_NET_AREA=1 also enters AREA 01. PS2_NET_MENU_TRACE=1 logs the sites and presses.
std::uint32_t menu_confirm(std::uint32_t ra);
std::uint32_t menu_directions(std::uint32_t ra);
std::uint32_t lobby_button(std::uint32_t mask, std::uint32_t ra);
std::uint32_t netcnf_list_pad();
// Private syscall 0x7F07: pad word for the netcnf GUI message states, plus one X on message 0x34 (once per run).
std::uint32_t netcnf_message_pad();

}
