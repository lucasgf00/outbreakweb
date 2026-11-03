# Plan: widescreen and extra characters, offline and online

Context and sources: [mods-us.md](mods-us.md) §3 and §6. We stay on the US version (no migration to the JP one).

## Scope
1. **16:9 widescreen** in File #1 and File #2, as a pre-menu option. It is client-only, so it works online without touching the server.
2. **"File 3" characters** (HUNK, Bone, Clint, etc.) playable offline and online.

Out of scope for now: unlock-everything, health/ammo cheats, Spanish, ad-libs, migrating to the JP version or to a native build.

## 1. Widescreen
- **Reference, not copy:** the pnach files of the PCSX2 patches repository `SLUS-20765_0245EF6D` (23 lines) and `SLUS-20984_1E65A50E` (11 lines) say *that it can be done* and for which CRC. That repository has no license: we write our own patches in `scripts/patches/SLUS-20765.txt` and `SLUS-20984.txt`, only our own addresses and instructions.
- **Steps:**
  1. Read what each pnach line touches (which functions, which values) and locate them in our translated ELF.
  2. In-game projection/aspect patch (gameplay).
  3. Cropping fixes: inventory items, videos (FMV), 2D screens that end up stretched.
  4. 16:9 output in the WebGPU renderer and in `web/shell.html` (Settings option, saved per player; 4:3 remains the default).
- **Verification:** captures at 4:3 and 16:9 of one scenario of each File, a video and the inventory. Check that 60 fps does not drop (more pixels) and that the fullscreen chat panel still looks right.
- **Risk:** low. Make sure the option changes nothing on the network side (projection only).

## 2. Extra characters
- **Mechanism [FACT, from community wikis and cheat lists]:** each character is a hex slot ("1F" is `0x1F`); it is forced with player 1's character modifier plus a "type" (Kevin, David, etc.). Some have no stats and they must be injected.
- **Steps:**
  1. Find in the US ELF VER 2.00 (File #1) and VER 1.05 (File #2) the addresses of each player's character ID and type. Community cheat addresses serve as a guide to what to look for; verify them with our runtime.
  2. Poke via `PS2_POKE` in an **offline** run: try each slot and note which load, which hang and which have no stats. Output: a table in this note.
  3. Selection mechanism on the web (Settings or pre-menu) with the list of those that work.
  4. **Online:** see how the character and model are reported to the other clients (room/slot in `KkService`, game packets) and whether the extra model looks the same for everyone. Test with 2 native clients before offering it on the web.
- **Risks:** incomplete models or hangs; online, another client without the same ID may break the game or desync. Characters that fail stay off the list.
- **Open decision:** are the online extra characters enabled by a room option (off by default) or always available? Proposal: a room option, so the game informs everyone.

## Order and definition of done
1. Widescreen File #1 → File #2 → pre-menu → browser test.
2. Extra characters offline (slot table) → web → online with 2 native clients → online in the browser.
- "Done" = run and backed by evidence (captures or logs, local and not published), not just compiled. A new wasm needs the owner's approval before any deploy.
