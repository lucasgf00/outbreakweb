# Status tracker art read from the disc at run time

Goal: draw item icons and character portraits in the page's tracker **without hosting any art**.
Everything is read from the disc the player is already streaming (`disc.iso` or `disc/NNN.bin`) and decoded in JS.
The repo and the deploy carry only our own code: format offsets, file-name tables and the decoder.

Complements [tracker.md](tracker.md), which holds the RAM map (character, health, virus and inventory with the "type" = item id).

## Summary

| What | File #1 (SLUS-20765) | File #2 (SLUS-20984) | Format |
|---|---|---|---|
| Common item icons (48x48) | `romdata_usa.afs/itemicon.img` (271616 B, 117 icons) | `romdata_usa.afs/pit_comn.dat` (MOMO), part 3 (299264 B, 129 icons) | linear PSMT8 + 2 CLUTs of 256 RGBA (CSM1) at the end |
| Item to icon + name table | `romdata_usa.afs/itmsge.dat` (20996 B) | `pit_comn.dat` part 4 (20996 B) | see below |
| Bust portraits (256x256) | `romdata_usa.afs/select.tex`, TIM2 #7 to #14 | `romdata_usa.afs/select.tex` (35-part MOMO), TIM2 #2 to #9 | OB-LZ then TIM2 PSMT8, RGBA32 CLUT CSM1 |
| Scenario item icons | `rNNN.afs/sii_NNN.itt` + `rNNN.afs/itmsgNNNe.dat` | `rNNN.afs/sii_NNN.itt` + `rNNN.afs/pit_sNNN.dat` part 0 | OB-LZ then 256x256 PSMT8 TIM2, 48-pixel cells (5 per row) |

`romdata_usa.afs` and `rNNN.afs` are AFS archives nested inside `NETBIO00.DAT` (the first file of the outer AFS is `romdata_usa.afs`).

## Containers [VERIFIED]

- **ISO9660**: `NETBIO00.DAT` in the root. File #1 is at byte `0x1046000`, `romdata_usa.afs` at `0x1046800`. File #2 at `0xE20800` / `0xE21000`.
  `itemicon.img` (File #1) at `0xDAB0800`. `pit_comn.dat` (File #2) at `0xE58B000`.
  These offsets do not need to be stored: they are resolved at run time by reading the PVD, the root directory and the AFS tables (about 80 KB of reads).
- **AFS (CRI)**: `"AFS\0"`, `u32 n`, `n x {u32 off, u32 size}` relative to the file, then `{u32 off, u32 size}` of the name table (48 B per entry, 32 B name). Same format as the reo project's `dump_nbd_tex.py` tool.
- **MOMO (Capcom)**: `"MOMO"`, `u32 n`, `n x {u32 off, u32 size}` relative to the package.
  In File #2, `pit_comn.dat` holds: 0 = `spitem2.dat` (66560), 1 = `cp_back.img` (48128), 2 = `file.img` (121856), 3 = icons (299264), 4 = item table (20996).
  These are the same files File #1 keeps loose inside `romdata_usa.afs`.
- **OB-LZ** (`.tex`, `.itt`; the file starts with `41 04 "TIM2"`): 16-bit word LZ. Each control word is read from MSB to LSB: a 0 bit is a literal and a 1 bit is a token `count<<11 | offset`. With count 0, the count is in the next word. Offset 0 is a run of zeros and token 0 marks the end. This is the same algorithm as the reo project's `ob_decompress`.
  The output has **several TIM2s back to back**: the header `count` says 1, so they must be chained.
- **TIM2**: 16-byte header, or 0x80 if `align` is set. Per image: `total, clutSize, imgSize, u16 hdrSize, u16 colors, fmt, mip, clutType, imageType, u16 w, u16 h`, then `GsTex0/1`.
  The images here are `imageType` 5 (PSMT8) or 4 (PSMT4) with an RGBA32 CLUT (`clutType` 3). The 8-bit CLUT is in CSM1 order: swap indices 8-15 with 16-23 in every block of 32. Pixels are **not** swizzled (linear order). PS2 alpha: 0x80 = opaque (x2, capped at 255).

## Common icons (`itemicon.img`) [VERIFIED]

- `N = (size - 0x800) / 2304` icons of 48x48, 8 bpp, one after another (row by row). After them come 2 CLUTs of 0x400 bytes (RGBA, CSM1). That gives 117 icons in File #1 and 129 in File #2.
- Each icon uses one of the two CLUTs. The CLUT comes from the item table (bit 8 of the icon field), not from the position in the sheet.
  In File #1, icons 0-74 use CLUT 0 (weapons, ammo, chemicals) and from 75 on CLUT 1 (herbs, medicine, personal items).
  Icons that no item references come out with the wrong CLUT if guessed: they are unused icons.

## Item table (`itmsge.dat`) [VERIFIED]

- `+0`: `u16` and `+2`: `u16be n`. There are `n+2` entries of `{u16be icon, u16be sub}` starting at `+4`. Padding is `(0x00FF, 0)`.
- `icon`: `0xFF` = no icon. Otherwise `icon & 0xFF` is the index in `itemicon.img` and `icon >> 8` is the CLUT.
- Names: entry k at `0x204 + 64*k`, 64 B, each byte XOR 0xFF, terminated by 0xFF. Descriptions follow.
- **Item id** (the "type" in [tracker.md](tracker.md)): the entries form 8 groups and `sub` restarts in each group. `id = base[group] + sub`, with `base = [0, 100, 150, 200, 250, 300, 400, 450]` (firearms, melee, chemicals, magazines, ammo, healing, personal items, misc).
  - Evidence: the resulting set of ids matches the community tracker's icon data. In File #2 it matches exactly. In File #1 the community tracker also lists 9, 12, 30, 150, 155, 455 and 456: ids with no icon or not present in File #1's table.
  - It also matches what was measured in RAM ([tracker.md](tracker.md)): 400 = Kevin's .45 auto, 401 = Mark's handgun, 413 = Cindy's herb case, 210 = .45 auto magazine, 300 = green herb.
  - Examples (File #1 / File #2): 0 handgun -> icon 0 / 0; 300 green herb -> 75, CLUT 1 / 76, CLUT 1; 453 broken handgun -> icon 0 (shares the handgun's).
  - The full tables are generated by a local prototype and are not copied here because they are game text read from the disc.
- [HYPOTHESIS] The per-group `base` is fixed in the executable. The table was not searched for in the ELF; it was taken from the observed id ranges.

## Scenario items (id >= 10000) [VERIFIED for r001 (File #1) and r006 (File #2)]

- `id = 10000 + scenario*100 + sub`. The `itmsgNNNe.dat` table has the same format as above: entries from `+4`, `n` at `+2`, end at `(0xFF, 0)`. File #1 has it loose; in File #2 it is part 0 of `pit_sNNN.dat` (MOMO).
- `sii_NNN.itt`: OB-LZ then 1 TIM2 of 256x256 PSMT8 (CSM1). Icon k's cell is at `x = (k % 5)*48`, `y = floor(k/5)*48`.
- Resulting ids: r001 = 10100-10108 and 10150. r006 = 10600-10605, 10609-10612 and 10650-10652. They are the same ones the community tracker lists.
- The files are in `NETBIO00.DAT/rNNN.afs`: File #1 has r001, r002, r028, r035 and r041; File #2 has r006, r010, r015, r020-r023, r026, r027, r029, r030 and r040. File #2 also has `BIN/01-05.DAT`, which was not examined. [HYPOTHESIS] The remaining scenarios follow the same pattern: only r001 and r006 were tested.

## Portraits [VERIFIED]

- `select.tex` (the scenario/character select screen). File #1 is a single 1.48 MB OB-LZ stream with 27 TIM2s: the portraits are #7 to #14. File #2 is a 35-part MOMO, one part per TIM2, and the portraits are #2 to #9.
- They are 8 busts of 256x256 PSMT8 with alpha, in the order **Kevin, Mark, Jim, George, David, Alyssa, Yoko, Cindy**. This is the same order as the character id in RAM (`0 Kevin ... 7 Cindy`, [tracker.md](tracker.md)), so `portrait = ims[first + character]`.
- The same file holds small silhouettes of the 8 (TIM2 #6 in File #1, #0 in File #2), scenario backgrounds and text. There are no NPC portraits there. [HYPOTHESIS] NPC ones are probably in other `.tex` files (`demo_com.tex`, `result.tex`); not examined.

## Recommended method: (a), read the disc from JS [VERIFIED in node against the ISO]

- Do ranged `fetch` calls to the same URLs the runtime uses: `Module.discUrl`, or `disc/NNN.bin` with `Module.discPiece` = 64 MiB, cutting at piece boundaries. About 1.2 MB per File is needed: AFS tables, `itemicon.img`/`pit_comn.dat` and `select.tex`. One scenario adds about 30 KB.
  Load once per game, off the emulator thread. The decoded result can be kept in memory or in the page's Cache Storage (it is derived from the player's own disc and never leaves their browser).
- It does not depend on what is on screen or on VRAM, and it has **all** the icons and portraits from the start.
- (b), copy from RAM/VRAM: [HYPOTHESIS, not investigated] the game must load `itemicon.img` when it opens the item menu and `select.tex` only on the select screen. During play there is no guarantee they are present. Discarded in favor of (a).
- Prototype: `trackerart.mjs` (dependency-free ES module; runs the same in node and in the browser). The API is `loadTrackerArt(read, file)` -> `{items, portraits[8], icon(id), scenario(nn)}`, and each image is `{w, h, rgba}` (usable with `new ImageData`). For the web, `pieceReader(discUrl, piece)`.
  In node it takes about 60 ms per File with a local ISO.

## In the page (2026-11-02, outbreak `2dea3f8`)
- The decoder lives inside `web/shell.html` as `TrackerArt` (same code, no `export`; reads use `cache: 'no-store'`). UI and usage: [tracker.md](tracker.md) "In-page UI".
- Fixes relative to the prototype: an item name ends at a raw `0x00` byte as well as `0xFF`; in minified HTML a regex cannot use `\0` (emcc turns it into a literal NUL).

## Room names (map table) [VERIFIED, 02/11]
- The text shown by the map screen is on the disc, in English, XOR 0xFF (like items). File #1: `NETBIO00.DAT/rNNN.afs/mapNNNe.dat`. File #2: one of the parts of the MOMO `rNNN.afs/pit_sNNN.dat` (part 3 in r006, 398720 B); it is recognized by its header.
- Format: `u32` (size?), `u32 n` (entries), `u32 0x20` (offset of the first entry), rest of the header. Entries are 0x58 B: `+1 u8` room id (the same one as in RAM, player `+0x1E2`), 16-bit Japanese text, and at `+0x28` the English name XOR 0xFF terminated by a raw `0x00`. There are empty entries (r001 has no room 9; r006 has 2 blank ones at the end).
- Scenario = the `rNNN` number: the scenario id in RAM (`0x3243DA` / `0x30095A`) is 1 in J's Bar (r001) and 40 in Wild Things (`pit_s040.dat`). This matches `GetScenarioName` of the public reader (1 outbreak, 2 hellfire, 6 end of the road, 10 underbelly, 15 desperate times, 20 training ground, 26 flashback, 28 the hive, 35 below freezing point, 40 wild things, 41 decisions decisions) and the scenario items (`10000 + NN*100`).
- Validated against the community tracker's room-name list: r001 room 1 is the bar, 2 the 1F-2F stairs, 3/4 the restrooms, 10 the break room, 25 main street; r006 = End of the Road (ids 1-66 with gaps).
- Only the header + `n*0x58` bytes (about 5 KB) are read per scenario. The small File #2 `pit_sNNN.dat` files (r021-r023, r027, r029, r030: showdown/elimination) [HYPOTHESIS] have no table: the card falls back to "Room N".
