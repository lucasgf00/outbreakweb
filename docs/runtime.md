# ps2-web-recomp runtime for Outbreak: findings

Last updated: 2026-10-15. EE addresses are from **SLUS-20765** (File #1) unless stated otherwise.
Disassembled with capstone (MIPS64 LE) over the game's main ELF and over a RAM dump taken at the moment of failure (`PS2_HALT_DUMP`, see below).

## 1. `BIN/*.DAT` code overlays (MWo3 format)

### 1.1 Loader in the ELF
| Address | What it does | Evidence |
|---|---|---|
| `0x001A95A0` | `load_overlay(id)`: `sprintf(buf, "BIN\\%d.DAT", id)` (string at `0x00242638`), reads the whole file into `table[id]`, decompresses if needed and calls the copier. | `addiu a1, a1, 0x2638` at `0x001A96B0`; `jal 0x1A9500` (read), `jal 0x1AF5D0` (LZ), `jal 0x1A9480` (copy). |
| `0x002311A0` | `u32[10]` table of read buffers per id: `0x370000, 0x570000 ×3, 0x6E0000 ×3, 0x810000 ×3`. | ELF data. |
| `0x001A9500` | Reads a file from the CD (cdvd `open/read/sync/close`) into a buffer. | `jal 0x1290C8 / 0x1297A0 / 0x129D48 / 0x129350`. |
| `0x001AF5D0` | Capcom LZ decompressor (16-bit). Ids 4, 5 and 6 are skipped (those files are already flat). Destination: the pointer at `0x0032440C`. | `beq s1, 4/5/6` at `0x001A96D0..E8`. |
| `0x001A9480` | Validates the first 3 bytes `"MWo"` (`lui 0x6F; ori 0x574D` and a 24-bit mask) and does `memcpy(hdr[0x08], hdr, hdr[0x18] - hdr[0x08])` + cache flush (`0x180250`). **The header is copied too**: the overlay sits in RAM from its load address with the header at +0. | RAM dump: `0x370000` = `MWo3…submain.bin`, `0x570000` = `MWo3…demo.bin`, `0x6E0000` = `MWo3…insdnas.bin`. |
| `0x001B7E34` | Task loop: `jalr` to the `+4` pointer of each entry of the table at `0x00316350` (16 bytes per task). This is where overlay code is entered from. | Original failure with `v0 = 0x0057F1F0` (a `demo.bin` function). |

### 1.2 MWo3 header (in memory, little endian)
| Offset | Field |
|---|---|
| 0x00 | magic `"MWo3"` (0x336F574D) |
| 0x04 | overlay id (does not match the file number: 7.DAT does not exist, 5.DAT has id 7, 8.DAT has id 10) |
| 0x08 | load address |
| 0x0C | text size |
| 0x10 | data size |
| 0x14 | bss size |
| 0x18 | end of text+data = load + 0x40 + text + data (repeated at 0x1C) |
| 0x20 | file name (`submain.bin`, …), 32 bytes |
| 0x40 | text, followed by data |

There are no relocations: each file is linked for its address. The ELF reserves those areas as PT_LOAD segments with no file bytes (`0x370000`, memsz `0x1FCA80`; `0x570000`, memsz `0x64900`).

| File | Name | Load | text | data | bss | Compressed? |
|---|---|---|---|---|---|---|
| 0.DAT | submain.bin | 0x370000 | 0xFD40 | 0xD000 | 0x1DFD00 | yes |
| 1.DAT | demo.bin | 0x570000 | 0x18440 | 0xBF00 | 0x40580 | yes |
| 2.DAT | game.bin | 0x570000 | 0x198540 | 0x3AB80 | 0x17480 | yes |
| 3.DAT | netwk.bin | 0x570000 | 0x9A140 | 0x28080 | 0xAD200 | yes |
| 4.DAT | netdnas.bin | 0x6E0000 | 0x70F40 | 0x30080 | 0x81400 | no |
| 5.DAT | insdnas.bin | 0x6E0000 | 0x29CC0 | 0x7E00 | 0x6FB00 | no |
| 6.DAT | nethttp.bin | 0x6E0000 | 0x54AC0 | 0xAF80 | 0xD80 | no |
| 8.DAT | netaq.bin | 0x810000 | 0x4740 | 0xC80 | 0x94F80 | yes |
| 9.DAT | single.bin | 0x810000 | 0x189C0 | 0x3000 | 0x67E80 | yes |

### 1.3 Compression (routine `0x001AF5D0`)
LZ over halfwords (16 bits):
- One flags halfword covers the next 16 items, **from bit 15 down to 0**.
- Bit 0: copy one literal halfword.
- Bit 1: read a token `t`. `count = t >> 11`, `dist = t & 0x7FF`. If `count == 0`: `dist = t` and `count` = the next halfword.
  - `dist == 0` and `count == 0`: end.
  - `dist == 0`: write `count` zero halfwords.
  - Otherwise: copy `count` halfwords from `dist` halfwords back (may overlap).
- A compressed file is recognized because it starts with flags (bits 15 and 14 clear) followed by a literal `"MWo3"`: `02 00 'M' 'W' 'o' '3'`.
- Implementation: `capcom_lz()` / `mwo3_image()` in `scripts/disc.py` (`ps2-web-recomp/`). All 9 files decompress to the exact size `0x40 + text + data`.

## 2. How overlays are translated and dispatched (`ps2-web-recomp/`, branch `outbreak`)

Cause of the stall `no translated code at 0x00802400 (ra 0x001B7E40)`: `eerecomp` treated all memory of the executable segments as code, **including the part with no file bytes** (bss and the overlay areas). It therefore translated "nops" at `0x57C000…` and the `jalr 0x57F1F0` (a real `demo.bin` function) ran those nops until it fell into `0x802400`. There was no mechanism for code loaded at fixed addresses.

Solution (generic):
1. `recomp/elf.*`: `Segment::code_size` = `p_filesz`. Only that part is executable; the rest is read as data.
2. `scripts/disc.py overlays <iso> <dir>`: walks the disc, detects MWo3 files (flat or compressed), decompresses them and writes the image as it ends up in memory to `games/<game>/overlays/*.ovl`.
3. `recomp/overlay.*` + `eerecomp --overlays <dir>`: each overlay is analyzed as its own program at its load address (text = executable, data not), seeded with the start of text plus the `name:address` entries from `entries.txt`. It is emitted as a non-relocatable module `o_<name>_unit_XXXX` with `ModuleInfo::base` = load address.
4. Identification: the runtime (`runtime/src/dispatch.cpp`) compares *checks* (offset, value) against RAM: the header (magic, id, load, text and data sizes) and 8 sampled code words. This tells apart `demo.bin`, `game.bin` and `netwk.bin`, which share `0x570000`. While the compressed file is still in the buffer (before the copy) the checks fail, and the cached instance is invalidated when another overlay overwrites it.
5. `dispatch()`: if the address is not in the main program, it looks for a loaded overlay containing it and jumps to its translated unit. An untranslated address is recorded as `name:address` in `misses.txt`, and `port.sh` adds it to `entries.txt` for the next round.
6. Cross hints: overlays are linked against the ELF and call its functions with direct `jal` (e.g. `0x00375AD8 jal 0x191820` in submain). `eerecomp` analyzes the overlays first, passes their `jal`/`j` targets as *hints* to the ELF analysis (accepted only if they decode as code, like address constants) and then does the same the other way around. Without this, each `port.sh` round discovered a single ELF function (12 rounds → 12 functions). With hints the ELF gains ~3,900 instructions with no manual entries.
7. `eerecomp` now deletes the `*unit_*.cpp` files it no longer generates (otherwise stale units kept being compiled), and no longer emits a `goto` to a return address that is not code (this happened in overlays with encrypted areas and broke the build).

Local commit `5e6d64b` on branch `outbreak` ("Translate code overlays loaded at fixed addresses").

### 2.1 Encrypted areas in the DNAS overlays
`netdnas.bin` (4.DAT) has text stretches that do not decode as valid MIPS (e.g. at `0x6E3080`); they are the DNAS parts decrypted at run time. The analysis translates them as garbage where they decode. If decrypted code is ever executed there, the code checks may fail (the overlay would not be recognized) and one would have to exclude those words or translate the already-decrypted code (from a RAM dump). Offline mode does not need this: 4.DAT is not loaded.

## 3. File #1 boot progress (native)
Run with a fresh memory card (`--memcard`; without it the game stays on the card-check screen) and buttons: start at 300, cross every 300 up to 1800, start at 2400/2500, cross afterwards.

File sequence: `0FLIST.DIR`, `NETBIO00-02.DAT`, `BIN\0.DAT` (submain), `MCICON.BIN`, `BIN\5.DAT` (insdnas), `BIN\1.DAT` (demo: logos, title), `BIN\5.DAT`, `MCICON.BIN`, `BIN\2.DAT` (game), `BIN\9.DAT` (single). Screens: Capcom/Sofdec/ADX logos → violence warning → title "PRESS START BUTTON" (frame ~2400) → menu → **SCENARIO SELECT** (frame 3300) → intro FMV of the "Outbreak" scenario (4200+).

With start every 500 frames between 4500 and 6500 (skips the FMV) and cross afterwards, **it reaches the game**: scenario "Outbreak", J's Bar, Kevin in 3D, the inventory menu (ITEM/SPECIAL/FILE) opens and the scene renders (35 M primitives, VIF1/PATH1 active). It runs 9000 frames with no failures or misses; the log shows no unimplemented syscalls or IOP libraries (only the expected `dev9: unknown dev9 hardware`).

Entries discovered by exploration (`games/outbreak1/entries.txt`): 15 ELF functions called by pointer from overlays (`0x1D3BA0`, `0x1B82D0`, `0x15CC80`, `0x192190`, `0x1BA9F0`, `0x1D0340`, `0x1C1F20`, `0x1B0FB0`, `0x1B1080`, `0x1B2150`, `0x1B82C0`, `0x191F80`, `0x191820`…) and 8 from `submain` (`0x37B950`, `0x37DF40`, `0x3792E0`, `0x376DB0`, `0x3700C0`, `0x370100`, `0x373380`, `0x373370`).

Debug aid: `PS2_HALT_DUMP=<file>` makes the runtime dump the 32 MiB of EE RAM when the run stops (`runtime/src/system.cpp`, `halt()`).

## 4. Black screen on room change (web version bug, 2026-10-15)

### 4.1 Cause
Reproduced natively with `PS2_NO_INTERP=1` (the previous behavior): in J's Bar, when the attack scene ends (frame ~10500 of the route below), the screen fades to black and the run stops with `[ee] no translated code at 0x003719E0 (ra 0x005EB8C8)`. On the web that is a fixed black screen.

| Address | What it is | Evidence |
|---|---|---|
| `0x005EB8B0` (game.bin) | Scene/room change routine: `jal 0x1B3DC0`, `jal 0x003719E0`, then clears the table at `0x4A5C30`. | Disassembly of the `BIN_2.DAT.ovl` overlay. |
| `0x003719E0..` (submain.bin) | **Exported stub table**: 16-byte entries `j <ELF function>; nop; nop; nop` (`0x3719E0 → j 0x1CC4B0`, `0x3719F0 → j 0x1CC230`, `0x371A00 → j 0x1CC240`, …). Other overlays call them with direct `jal`. | Disassembly of `BIN_0.DAT.ovl`. |

Why it was not translated: game.bin's `jal 0x3719E0` did reach the submain analysis as a *hint*, but `tentative()` (recomp/analysis.cpp) rejected any candidate whose `j` jumped outside the image being analyzed, and a stub is precisely a `j` to another image (the ELF). Nothing inside submain calls its own stubs.

### 4.2 Fix (generic)
1. `recomp/analysis.cpp`: a `j` to an address outside the program range is taken as an exit (a tail call that `dispatch()` resolves at run time), not as proof that the candidate is not code.
2. `jump_stubs()`: all 8-byte-aligned `j <outside the program>; nop` are added as candidates, to cover stubs that nobody calls directly in the already analyzed code.
3. **Safety net: MIPS R5900 interpreter** (`runtime/src/interp.cpp`, ~520 lines). `dispatch()` no longer stops on an untranslated address: it records the miss in `--misses` (as before, for `entries.txt`), prints `[ee] interpreting untranslated code at …` (the first 200) and executes with the interpreter. The semantics replicate `recomp/codegen.cpp` using the same helpers (`mmi::`, `fp::`, `vu::`, `ld*/st*`), with delay slots, branch likely, `syscall`, `eret`, budget and `preempt()` on backward jumps. It returns to translated code at every `jal/jalr` (via `call()`), at `jr`, and at jumps whose target has a translated entry (always with `musttail`, so the stack does not grow). It uses the decoder of `recomp/r5900.cpp`, which is now also compiled into the runtime (native and web).
   - `PS2_NO_INTERP=1`: previous behavior (stops at the first miss).
   - `PS2_INTERP_ALL=1`: everything passing through `dispatch()` runs interpreted (interpreter test).
   - If the pc is not plausible (outside RAM, or a zero word) it still stops with the usual message.
4. Exploration: since a miss no longer ends the run, a single run collects all misses along the route. `port.sh --route <file>` (repeatable) replays input scripts; the format and how to record them are in `scripts/routes/README.md`.

### 4.3 Evidence
- Without the interpreter and with the old translation: `halted: missing code at 0x003719E0` at frame 10530, black screen from 10500.
- With the new translation: the same run records no miss; after the scene "NOW LOADING" appears and the reloaded room (behind the bar).
- Route `scripts/routes/outbreak1-jsbar-bathroom.txt`: J's Bar → attack → kitchen behind the bar → Kevin opens the door → "Men's bathroom" sign → bathroom (frame ~12800). Three random 2500-frame walks inside the bathroom: no misses or hangs.
- In-game controls (for building routes): tank controls; `left/right` turn (~20 frames = 90°), `up` advances, `cross` examines/opens, `start` opens ITEM/SPECIAL/FILE, `select` pauses.

## 5. File #2 (SLUS-20984), 2026-10-16

Same engine, same pipeline: `scripts/port.sh outbreak2 <file2.iso> --native-only` worked with no changes to the runtime or the recompiler. The overlay analysis, check-based identification and fallback interpreter are generic and sufficed.

### 5.1 Overlays (`games/outbreak2/overlays`, same MWo3 header as File #1)
Load addresses and file order change (5 ↔ 6, 8 ↔ 9):

| File | Name | Load | text | data | bss |
|---|---|---|---|---|---|
| 0.DAT | submain.bin | 0x38C400 | 0x10A40 | 0xC980 | 0x1E3800 |
| 1.DAT | demo.bin | 0x58D000 | 0x22540 | 0x8780 | 0x40080 |
| 2.DAT | game.bin | 0x58D000 | 0x1D67C0 | 0x41780 | 0x16580 |
| 3.DAT | netwk.bin | 0x58D000 | 0x96440 | 0x26F00 | 0x9F800 |
| 4.DAT | netdnas.bin | 0x6ED000 | 0x71040 | 0x30080 | 0x81400 |
| 5.DAT | nethttp.bin | 0x6ED000 | 0x54A40 | 0xAF80 | 0xD80 |
| 6.DAT | insdnas.bin | 0x6ED000 | 0x29CC0 | 0x7E00 | 0x6FB80 |
| 8.DAT | single.bin | 0x810000 | 0x1C2C0 | 0x4A00 | 0x67F00 |
| 9.DAT | netaq.bin | 0x810000 | 0x5140 | 0x680 | 0x95900 |

There are also `BIN\01.DAT … 05.DAT` (leading zero) that the game opens at startup; they are not MWo3.
File sequence at boot: `MODULES\DRIVE.DAT`, `BIN\01-05.DAT`, `BIN\0.DAT`, `MCICON2.BIN`, `BIN\6.DAT` (insdnas), `BIN\1.DAT` (demo), …

### 5.2 Discovered entries
Only 5, all `submain.bin` stubs called by pointer from the ELF (`jalr` with `ra 0x0013E3B8`): `submain:38FAE0, 38FAF0, 38FB10, 38FC48, 38FD74`. In the first round they ran in the interpreter; in the second round the full route recorded no miss and no missing syscall/IOP library.

### 5.3 How far it gets (native)
Route `scripts/routes/outbreak2-wildthings.txt` (9300 frames, fresh card): logos → intro → title → menu → SCENARIO SELECT (frame ~3100; the first in the list is "training grounds", the tutorial; the route picks "wild things" with `right`) → Kevin, Mark and Jim, NORMAL → NOW LOADING → FMV → Raccoon City zoo (entrance cutscene, frame ~5600) → **Kevin under 3D control**: walks to the truck and opens the inventory (ITEM/SPECIAL/FILE). "training grounds" also loads and plays (Kevin with the inventory, frame ~4650 with alternating start/cross from boot).

### 5.4 DNAS
`scripts/patches/SLUS-20984.txt`: the same bypass as File #1. The authentication steps are at `netdnas:0x6ED090` / `0x6ED0A0` (`j 0x6ED0B0` with `a3 = 0 / 1`, byte-for-byte the same pattern as `0x7507C0/D0` in SLUS-20765) and netwk.bin calls them at `0x613E24`, `0x6149FC` and `0x6148D0`. eerecomp reports "netdnas: 8 patched words". Not tested with network at the time (`--net` was not run).

### 5.5 Routes with sync points (runtime, generic)
`frame:sync:black` / `frame:sync:visible` in a route (or in `--press`): the runner measures the mean brightness of the displayed image since the end of the previous press and shifts the rest of the route according to when the change occurs (earlier: advances; later: waits). Thresholds: black ≤ 2, visible ≥ 8 after black (the game's dark scenes give 10-25; NOW LOADING counts as black). Code: `apply_presses()` and `screen_brightness()` in `runtime/src/system.cpp`; format in `scripts/routes/README.md`. Without sync points the behavior is as before.

The File #1 route to the bathroom **is not fixed by sync**: the boot and the reload after the attack (black 10450-10900, visible at ~10950) happen on time; what changed is where Kevin ends up after the reload (behind the bar; the original walk takes him to the picture wall). Also, a save state at frame 10900 and the continuous run diverge from there on (the camera differs at frame 11000), so save states are not exactly deterministic in this scene. The post-reload segment still needs re-recording.

## 6. Coverage (2026-10-20)

### 6.1 Static (eerecomp now prints one line per overlay)
`eerecomp` prints `<overlay>: text N words, code M (x%), function starts P, untranslated Q` (Q = `addiu sp,sp,-N` prologues after a `jr ra` that did not end up as code). `EERECOMP_GAPS=<overlay>` lists the untranslated text stretches that have non-zero words.

| Overlay | File #1 code / text | untranslated | File #2 code / text | untranslated |
|---|---|---|---|---|
| submain | 98.1% | 0 | 98.2% | 0 |
| demo | 98.9% | 0 | 98.5% | 0 |
| game | 98.3% | 0 | 98.3% | 0 |
| netwk | 97.9% | 0 | 97.8% | 0 |
| single | 97.9% | 0 | 98.1% | 0 |
| netaq / nethttp | 97.0 / 97.9% | 0 | 97.2 / 98.1% | 0 |
| netdnas / insdnas | 86.7 / 73.1% | 47 / 21 | 87.0 / 73.2% | 46 / 21 |

The rest of game.bin is zero padding: `EERECOMP_GAPS=game` gives **a single** stretch with non-zero words (`0x6847D8`, 14 words). Function-pointer tables already get in on their own: `analyse()` takes as a candidate every word of the segments (text and data) that points into text and decodes as code. What remains untranslated in netdnas/insdnas are the encrypted DNAS areas (§2.1). A run-time miss is therefore almost always a missing *entry* (an address in the middle of already translated code, reached through a computed pointer), not unknown code.

### 6.2 Scenarios reachable offline with a fresh card
- File #1: only "Outbreak". The mask `0x321EF4` (the online RULE one) enables nothing offline (tested with `PS2_POKE` of 0x1F and 0xFFFFFFFF every frame from 200); neither do the bitsets at `0x32007C`.
- File #2: training ground, wild things, underbelly, flashback and desperate times (end of the road is locked). The mask `0x2FF8C8` (online RULE) does not change the offline list either.
- `0x359628` (File #2) / `0x2B0A68` (File #1) is the write index of a 32-request circular queue (12-byte entries): touching it before NOW LOADING loads another scene, but it is not a scenario selector.
- Locked scenarios (File #1: 4; File #2: end of the road, elimination 1-3 and showdown 1-3) are playable online (RULE offers them all).

### 6.3 Automatic routes per scenario
`scripts/routes/scenario_route.py <game> <index> <seed> [frames]` generates a route: boot, Scenario Select (index), characters, `sync:black` at NOW LOADING, skipped intro and a random walk (tank movement, cross, aim and shoot, inventory, map). `PS2_POKE` accepts ranges (`first-last:addr=hex`) and routes accept `F:poke:addr=hex` lines (`port.sh` passes them to `PS2_POKE`). A local campaign script runs batches of these.

### 6.4 State (2026-10-20, stage 1)
- Tools committed in outbreak `6b68ffc`. Web telemetry of misses was left **out of scope for now**: there is no endpoint or hook in the runtime.
- Offline unlock: also tested on File #2 `0x2FEEC0..0x2FEF10` and `0x300220..0x300240` set to 0xFF (the bitsets demo.bin reads when building Scenario Select, helpers `0x59BB00`/`0x59BB20`): the list does not change. Without a card with won scenarios, the locked ones are not reachable offline.
- "Before" campaign (6 Outbreak seeds on File #1 and 3 seeds for each of the 5 open File #2 scenarios, 30,000 frames each): the first 15 runs, **0 misses**.

### 6.5 Result (2026-10-20)
- "Before" campaign with the current native builds (outbreak `6b68ffc`, entries.txt of 27 and 5 lines): 21 runs of 30,000 frames (~8 min of play each): File #1 Outbreak ×6 seeds; File #2 training ground, wild things, underbelly, flashback and desperate times ×3. All reached gameplay (closing captures: J's Bar with zombies, the zoo, the subway, the flashback forest, the desperate times staircase). **0 misses, 0 `interpreting untranslated code` lines** in all 21. Hence no new entries: "after" = "before" and no further translation round was needed.
- Per scenario (misses before → after): F1 Outbreak 0 → 0; F2 training 0 → 0, wild things 0 → 0, underbelly 0 → 0, flashback 0 → 0, desperate times 0 → 0. Not measured (locked offline with a fresh card, §6.2): F1 Below Freezing Point, The Hive, Hellfire, Decisions Decisions; F2 end of the road, elimination 1-3, showdown 1-3. For those, static analysis remains (§6.1: all non-zero text of game.bin translated).
- Rebuilt web builds (no entry changes): `ps2web.wasm` 26.8 MB (File #1) and 28.3 MB (File #2). Headless Chrome (WebGPU) via `run-local.sh`: File #1 up to CHARACTER SELECT and File #2 up to SCENARIO SELECT at 60 fps, 0 interpreted code. Online: native File #1 with a fresh card against `run-local.sh` → automatic login → ROOM LIST (AREA 01/02) at frame ~5870.
- Missing: a card with the scenarios won (or the offline unlock flag) to run the same campaign on the 4 + 7 locked scenarios; web telemetry of misses was postponed.

## 7. Online crash File #1 (user report, 174358 frames): stale netwk callbacks (2026-10-21)

Log: `[mem] write at unmapped 0x6339E218 (pc 0x00582FC4)`, `interpreting untranslated code at 0x005831A0 (ra 0x001E021C)`, `no translated code at 0x00000000 (ra 0)`, halt.

**FACT (static analysis, not reproduced):**
- 0x5831A0 and 0x582FB0 are **`netwk.bin` functions** (BIN_3, base 0x570000: prologue `addiu sp,-0x60` / `-0x20`). In `game.bin` (BIN_2, same base 0x570000) those addresses fall **in the middle of a function** (0x5831A0 = `lwc1 f0,0x170(s0)` after an `sb`; 0x582FB0 = mid-block).
- netwk registers those functions with `0x1DAB54(channel 1, event, fn)` (0x583D38..0x583DA4 and 0x583CD4..): event 2 → 0x582FB0, event 6 → 0x5831A0, etc. The table lives in the ELF (object `gp-0x7fe8[1]` + 0x548 + 4·event). The ELF dispatcher (0x1DFDA8; `jalr` call at 0x1E0214, `ra` = 0x1E021C) reads `+0x560` (= event 6) and jumps. `game.bin` does **not** call 0x1DAB54 (0 calls): nobody replaces or clears those callbacks on entering the match.
- So a network event (2 and then 6) arriving **inside the match** calls netwk code that is no longer there: the dispatcher sees `game.bin`, 0x582FB0 runs mid-function with garbage `a2` (0x6339E103 + 0x115 = 0x6339E218, the unmapped write) and 0x5831A0 falls into the interpreter mid-function with a foreign stack frame (`ld ra,0x20(sp)` → ra 0 → halt). This fits the reported registers.
- Not reachable offline: the zone (blizzard = apparently Below Freezing Point, locked with a fresh card, §6.2; the report says "Outbreak") requires an online match. Events 2/6 are generated by the ELF's network layer.

**HYPOTHESIS (unconfirmed):**
- On a real console the same stale callback would also break, so event 2/6 should not arrive during a match; most likely a divergence of the emulated network (timeout/disconnect/late packet from the gateway or the server after ~48 min, or a player change when leaving through the street). What events 2 and 6 mean is still unknown (session error, `kkClient` closed?).
- Alternative: the ELF table is cleared on a real console at some point the runtime does not execute.

**Change:** in `runtime/src/dispatch.cpp` the miss notice now prints the resident overlay and a0/a1/a2 (`overlay game, a0 … a1 … a2 …`). A miss in a module with `ra` 0x1E021C confirms the case.

**Next step:** repeat the session with `PS2_PCAP`/the gateway and server logs and look for what arrived right before (gateway close, 4429, teammate timeout); put a `PS2_WATCH` on `gp-0x7fe8[1]+0x550/0x560` and record the event's `a1`.

**Mitigation (done locally, 2026-10-22):** `guard_overlay_callbacks()` (`runtime/src/dispatch.cpp`, declared in `system.hpp`), enabled by `stale_netwk_callbacks()` in `system.cpp boot()` **only** if the disc is `SLUS_207.65` and the original words are in place (0x1DFDA8 = `addiu sp,-0x50`, 0x1E0214 = `jalr v1`, 0x1E1610 = `jr ra`). In `dispatch()`, when the target is not translated in the program, falls inside the `netwk` module range (fixed base 0x570000) **and** `ra` is inside the dispatcher 0x1DFDA8-0x1E1618, if the resident overlay is not `netwk` it returns to `ra` without executing anything and logs `[ee] SLUS_207.65: skipped stale netwk callback …` (max. 20 lines). No game bytes in patches.
- Why it is safe: the dispatcher has 68 `jalr` to table callbacks; before each one it skips the slot if it is 0, and **none uses `v0`** after the call. Skipping the callback is equivalent to an empty slot, which the game already handles. Only netwk calls 0x1DAB54 (game.bin 0 times), so with netwk resident the behavior does not change.
- `PS2_STALE_CALLBACKS=1` disables it. At boot it logs `callbacks of overlay netwk called from 0x001DFDA8 are skipped when it is not resident`.
- Limits: File #2 (SLUS_209.84) initially had no guard (see below). The real crash was not reproduced: verified only that it activates, that offline (native and web) it reaches gameplay and that online it reaches the room list.

## 8. Deferred network: the gateway WebSocket opens when the network is used (done locally, 2026-10-22)

Before, `dev9 reset()` created the backend and the WebSocket opened at startup even when playing offline (gateway fills up). Now:
- Trigger: the **first SMAP TX frame** (`dev9 transmit()` → backend `send()`). It is the most reliable: the dev9 driver loads at boot even offline but does not transmit; the first TX is the DHCP DISCOVER after "Select Network Configuration". The network memory card (`netcnf::ensure`, BWNETCNF/NET save, account) depends only on the configured URL, not on the socket: unchanged.
- `runtime/src/net/websocket_web.cpp`: `send()` opens the socket the first time (outside `send_mutex_`, because websocket API calls are forwarded to the main thread) and queues into `pending_` (max 64) until `onopen`, which flushes them and logs `[net] connected to URL (N queued frames sent)`. Reconnection as before (3 s; 20 s if 4429).
- `runtime/src/net/websocket_native.cpp`: the `loop()` thread starts on the first `send()`; pending frames are written after the handshake. `host/nettest.cpp` sends the DISCOVER before waiting for `connected()`.
- `web/shell.html` `probeNet()`: the pre-menu indicator now uses `fetch('/api/rooms')` (before, it opened a test WebSocket that occupied a session). It no longer detects "gateway full" before playing; `netFailed()`/`netFromLog` remain.
- `?nonet` configures no URL → no WebSocket backend.

**Tests (local, `run-local.sh` + local gateway; counter = sessions in the gateway log):**
- Native offline, `--net ws://127.0.0.1:8765/eth`, 9000 frames: File #1 (jsbar-bathroom) and File #2 (wildthings) reach gameplay; 0 new sessions, 0 TCP connections to 8765.
- Web offline (headless Chrome, CDP): File #1 8000 frames and File #2 6000 frames, 0 `webSocketCreated`, 0 sessions. `?nonet` File #1 3000 frames: 0 WebSockets, indicator "Online disabled".
- Web online File #1: WebSocket created only after "Select Network Configuration" (frame ~4900); `connected … (1 queued frames sent)`, DHCP, automatic login, SERVER SELECT and `UP TOWN: ROOM LIST` (frame 6762).
- Native nettest: connected + DHCP OFFER/ACK + DNS.
- Observed: on the web the gateway sees the first frame ~3 s after the handshake (`onopen` reaches the emulation thread late; the frame is not lost, it goes out queued). It happens once on entering Network. Untested: a drop and reconnect mid-match, and that after going from online back to offline the socket stays open (same as before).

## 9. Untranslated code, return guard and kk-peer-gone (done locally, 2026-10-22)

### 9.1 File #2: region 0x001F72F4..0x001FEAF0 interpreted (reports at 118-122 s)
- **Cause [FACT]:** SLUS-20984 has no `.text` section. `text_end` was the last instruction reached from the entry, so translation units ended at 0x001F0000 even though there is code up to ~0x001FEF3C. That area is reached only through pointers: for example 0x001F73C0 does `jal 0x001143A0` with a0 0x100 and a1 0x001F7350, and the table's functions entered the interpreter one by one.
- **Generic fix (`recomp/analysis.cpp`):**
  - `extend_text()` extends the text while there is still decodable code with no gaps larger than `kTextGap` (0x4000).
  - `frame_starts()` also accepts leaf functions (no `addiu sp`): 8-aligned and preceded by `jr ra`, delay slot and zero padding.
  - `EERECOMP_GAPS=main` prints the non-zero untranslated stretches of the ELF (`recomp/main.cpp`).
- **Effect [FACT]:**
  - File #2 main: 236,577 → 251,815 instructions and 0 gaps from 0x1E0000. The 42 addresses of the report have an entry in the generated tables.
  - File #1 main: 242,613 → 244,030 (+1417 leaves). Overlay game: +10 in each game.
- **Unexplained oddities:** netwk drops 3 words in both games (still 0 untranslated). netdnas and insdnas rise a little, in the encrypted area (§2.1). The checks of those overlays must be verified not to change.

### 9.2 File #1: 0x006847E0 (game overlay; reports "DROP FPS" and "inventory fps drops")
- **What it is [FACT, static trace]:** the handler of chunk 4 of the scene-data loader 0x001D3830 (ELF).
  - The loader calls 0x107C68 with the buffer `*(0x324420)` and then 0x1D3790, and relocates 32 {ptr, size} pointers.
  - It then walks a table at 0x236800 with records {handler, src, dst, mode} terminated by mode 999. For each chunk it does memcpy (0x18DBB0) and `jalr handler` with a0 = pointer and a1 = size. `ra 0x1D39F4` is the mode 0 case.
  - 0x6847E0 copies count×32 bytes to 0x4B8670 (max 64 records) and stores count at 0x4B8E70. The report had a1 0x404, i.e. count 32.
- **Correction [FACT]:** the entry in `games/outbreak1/entries.txt` was off by 0x10 (`game:6847d0`). It is now `game:6847e0`, and with leaf detection it is also found on its own. The generated C++ (`L_006847E0`) matches the disassembly.
- **Cost [FACT]:** it runs once per `\BIN\*.DAT` load (in the report, after loading 2.DAT), so the interpreter there costs microseconds. **It does not explain the inventory lag.**
- **Inventory measurement [FACT, native headless, `PS2_GPU_LOW_POWER`, ms/frame from capture mtimes]:**
  - File #1 (jsbar, start at 13300): ~25 → ~48.
  - File #2 (wildthings, inventory at 9040): ~45 → 100-135.
  - Same with the old and new binaries, and neither run has `interpreting` lines.
  - So opening the inventory costs 2-3 times more per frame **without the interpreter**.
  - **Hypothesis:** a GS effect when opening the menu (framebuffer copy or read, blurred background) or the renderer. Needs profiling.
- **Unverified hypothesis:** that 0x4B8670 is an item or room table and that a wrong execution in the interpreter explains "items are not picked up". Translation and interpreter should give the same result; there is no evidence the interpreter fails there.

### 9.3 Guard for returns to addresses with no code (`missing code at 0x00000100`, blizzard)
- **Fact:** when a translated or interpreted call returns to a pc different from its return address, and that pc has no code and is not plausible (outside RAM, below 0x1000, misaligned, or with no translation or possible interpretation), `bad_return()` (`runtime/src/dispatch.cpp`) does the following:
  - logs `[ee] call returned to 0x… (no code there; ra …): went on at its return address …` up to 20 times, with the last 16 jumps {pc, ra};
  - continues at the return address, as if the call had returned.
- `PS2_NO_INTERP=1` keeps the old halt.
- The `missing code` halt now prints `[ee] last jumps: …` (`g_jumps` ring), which lands in the report's log tail.
- Sites covered: `recomp/codegen.cpp` (3 call returns) and `runtime/src/interp.cpp` `invoke`.
- **Hypothesis:** the jump to 0x100 comes from a function pointer read from a freed or reused structure. The jumps in the next report can locate it.
- **Not tested in the real case:** no route reproduces it.
- Verified that normal behavior does not change: in jsbar (14,400 frames) and wildthings (9,900 frames) no `call returned` line appears.

### 9.4 kk-peer-gone: short teammate timeout after the gateway notice (protocol.md §6.11)
- **Superseded by outbreak `c8a4352` (Oct 25):** the global timeout is no longer touched; only the departed player's slot is flagged (network.md §15).
- `runtime/src/net/config.cpp`: `text_message()` recognizes `{"type":"kk-peer-gone","tag":"…"}` and counts the notices (atomic). `peer_gone_notices()` returns the count.
- The backends pass text messages to it: web in `onmessage` with `isText`, native with opcode 0x1.
- `runtime/src/system.cpp`:
  - `online_peer_timeout()` remembers which patch it applied (SLUS_207.65 0x235F50 20.0f, SLUS_209.84 0x241280 31.0f).
  - `peer_gone_frames()` runs on every vblank. On a new notice, if the float is 300.0f, it writes the native value for 2400 frames (~40 s), and a new notice extends the window. On expiry it returns to 300.0f only if the value is still the native one.
  - A false notice does no harm: a teammate who is still playing is not silent for 20 s.
- **Test [FACT, native File #1]:** a minimal WS server that sends the text 8 s after connecting. Network presses from §8 / network.md, 9000 frames. Log: `[net] gateway: teammate 0a1b2c3d left the game` → `teammate gone, timeout back to the game's own for 2400 frames` → `teammate timeout 300 s again`, and at the end `PS2_PEEK` shows 0x235F50 = 43960000.
- **Untested:** File #2 end to end (with those presses it does not get to open the network; the code is the same, only the table changes), the web in the browser (the file compiles with em++ `-fsyntax-only`) and against the real gateway.

## Stale netwk callbacks in File #2 (2026-10-23)
- [FACT] The File #2 ELF dispatcher at `0x165340` (same size and 68 `jalr` as File #1's `0x1DFDA8`; registration `0x1600EC`, 13 calls in netwk, 0 in game) calls netwk callbacks with `game.bin` resident at `0x58D000`. `0x59B250/0x59B3F0/0x59C330` are mid-code in game.bin; `0x59B248` does `obj+0xCB0 |= 0x201` (event mark) → scenario events "already happened" (Underbelly, Flashback). The guard was extended to SLUS_209.84 (verified words: `0x165340` `addiu sp,-0x50`, `0x1657AC` `jalr v1`, `0x166BA8` `jr ra`) and has been in production since `210ce23`.

## Bursts of "skipped stale netwk callback" in a match = room dissolved under players who keep playing (2026-10-24)
Two player reports (one on File #2, "gets stuck"; one on File #1, "disconnect without reason").
- [FACT, static] The guard decides correctly. It compares: target untranslated in the program, inside the `netwk` range, `ra` inside the dispatcher (`0x1DFDA8` / `0x165340`) and resident overlay ≠ `netwk`. No overlay except netwk calls the `0x1600EC` registration (BIN_3: 13 calls at `0x59C620..0x59C710`; game.bin, BIN_0/1/4/5/6/8/9: 0). The File #2 ELF registers its own (`0x1F798C..0x1F79D4`: events 0x12-0x15 → `0x1F77F0/0x1F7890/0x1F7930`, the match's `0x0F`/`0x10`), which are always resident and the guard does not touch. Skipped slots (File #2, `ra` → slot → event → netwk): `0x165514` → `0x558` → 4 → `0x5B3800` (push 4, room in the list); `0x16557C` → `0x55C` → 5 → `0x5B3930` (push 5, room deleted); `0x1657B4` → `0x560` → 6 → `0x59B3F0` (push 7, member left); `0x165CD4` → `0x574` → 0xB → `0x59C330` (`0x0F` with `0x400` without `0x1000` = room chat; NUL at the end of the text). In File #1: `0x599F20` (push 4) and `0x59A040` (push 5), `0x5831A0` (push 7). All are lobby messages; with game.bin loaded, on a console they would land mid-function in game.bin. Skipping them is correct: they are discarded (1) and (2).
- `0x5D41C0/0x5D41D0` (netwk) are return addresses after `jal` (`0x5D41B8`/`0x5D41C8`), normal resumption by the interpreter, unrelated.
- [FACT, production journal] **Cause (server, `KkService`)**: members return to the waiting room one at a time (died, escaped) and the first one to send `0x0A` puts the room in "back to waiting" with the others still in the match. From then on the server treats it as a waiting room: when the host leaves, it dissolves it and sends those still playing the class `0x10 0x0D`, the push `0x07`, and moves them to the area (`s.cur`), so they receive the list pushes 4/5 (every 5 s via `resyncLists`) and are left outside the room's relay.
  - Case A (File #2 room, 4 players): START with 4. Some minutes later the host returns ("back to waiting"). Seconds later the host leaves (push 7 → `0x59B3F0` skipped). Then the host creates another room → "one open room per creator" dissolves the old room with **3 members in the match** → pushes 4/5 and chat skipped. In the next minute the sessions of those three close: all three hung.
  - Case B (File #1 room): a member returns to "back to waiting"; shortly after, that member leaves (push 7 → `0x5831A0`). Player B keeps playing for 30 min. Then the host (in the waiting room) drops → `disband` with "1 members back to the list" = player B in the match → burst of pushes 4/5 → the session closes.
- **Fix (`server/`, local commit)**: `Session.playing` = room whose scenario is still being played (set on START and cleared by `0x0A`/`0x09`/STAT without `0x40000000` on its room, which only BIN_3 sends, or by `0x06`/`0x07`). `deferDisband()`: if the host leaves the room (drop, `0x07` or new room) with members in the match, the room becomes orphaned (`hostGone`): those already in the waiting room get the `0x0D` at that moment; those playing, when they return (`orphaned()`, already existed). The `0x07` push does not go to those playing, and `0x10`/chat are only forwarded between members in the same phase (waiting ↔ waiting, match ↔ match). No runtime change.
- Evidence: a local test tool for the server (`server/tools/kkbackgame.py`, against a local test server). With the server at HEAD: the in-match player receives repeated `0x10 ..0d`, `0x07` and `0x05` (the pattern from the logs). With the fix, nothing until they return, and then they receive the dissolution. `kkhostgone`, `kkresync`, `kkrejoin` and `kkreconn` remain OK. Server commits: `37f15c8` (fix), `b0196cb` (REOGW-GONE to those still playing when the host leaves the scenario) and `f405dc1` ("back in the waiting room" and "relay … dropped" logs).
- Native, File #2, 2 players, full match without moving (local test script, `IDLE=1`, fix jar): the `0x10` in the match are the same as with the HEAD server. The host returns first and the other later. No relay dropped, no "skipped stale" on the clients, and both end up in MEETING ROOM.
- [OPEN] On return, the host shows 1P and does not answer the other's 56-byte `0x10` (64 retries); the other shows 2P. The filter does not cause it, since there were no discards. Needs comparison with HEAD: in the HEAD run the match did not finish within 45000 frames. A host leaving with members still playing was not reproduced natively: `kkbackgame.py` covers it.

## Ad-libs (R3 "Wait", L2+R3 "No"): audio path, silence not reproduced natively (2026-10-24)
Native File #1 runs from a saved state in J's Bar (temporary traces, not committed).
- [FACT] Ad-lib path: on press, the EE reads the clip from `NETBIO01.DAT` with `sceCdRead` (0x80000595 fn 1; R3 = sector `0xB6908` x14, L2+R3 = `0xB6965` x4, to `0xF04880`). It is an **ADX** (`80 00 ... (c)CRI`, mono, 24000 Hz). The EE decodes it to 16-bit PCM and sends it with `SifSetDma` (EE `0xF007C0..0xF047C0`, ring of 0x4000) to the IOP `0xE9740..0xED740`, ~1 block per frame. The IOP's ADX driver mixes it with the music at the AutoDMA input of core 1 (`0xD3060/0xD3860`). It does **not** use SPU2 ADPCM voices, DMA/PIO to SPU RAM, or its own RPC (the subtitle "Hey, wait a second." / "No..." appears all the same).
- [FACT] Natively the ad-lib **sounds**: with only the AutoDMA input in the mix, pressing R3 raises the RMS ~2700 → ~5400 at 0.2-0.8 s after the press; zeroing what enters the `0xE9740` ring (temporary test) makes the output identical to the run without a press. With the slow disc applied only to the clip (`PS2_DISC_DELAY_MS` 1000/3000/8000 limited to that sector) the EE waits for the read and the voice plays all the same, shifted in time: slow reading is ruled out as a cause.
- [FACT] Native online (2 clients, tag AE): the host's 3 presses read their ADX (`0xB6862`, `0xB73C7`, `0xB6880`) and send PCM with audio to the IOP; the teammate also receives a clip.
- The keys `0x37858`/`0x38640` (centered voices) are periodic ambient sounds (exactly every 3 s), not the ad-lib.
- [HYPOTHESIS] The silence is on the web side (not the shared runtime): disc served in pieces that do not cover or return something else in `NETBIO01.DAT` around sector `0xB6800-0xB7400` (≈1.53 GB), or something browser-specific. Next step: in the local web build, read the audio ring or the PCM at `0xF007C0` after the press; and verify in production that the disc piece covering that range has the ADX (header `80 00`).
- [FACT, Oct 25] **Local web** (engine `ps2web.b023ad668185.wasm` = same runtime as production, `?nonet`, headless Chrome, presses via `?press=`): R3 in J's Bar shows "Wait a minut..." and the RMS of what the game writes to the page's audio ring (`ps2web_audio_ring`, the same the AudioWorklet reads) rises from ~2400-3100 (run without press) to ~4500-6300 for ~90 frames after the press. The same with "stable audio" enabled (peaks 5000-8300 after each R3). Audio queue 60-170 ms, never empties. The worklet copies the ring as is: **on the local web the ad-lib also sounds**.
- [FACT] The page's default keys have no R3 (`{ id: r3, ..., key:  }` in `shell.html`): with keyboard there is no ad-lib unless the player assigns it; with a gamepad it is button 11.
- [HYPOTHESIS] What players report is not the runtime: (a) keyboard players with no R3 key (though they say the subtitle appears), (b) the voice's relative volume (in the IOP mix it sits at music level, RMS ~3600), or (c) something on the player's machine. Next step: ask a player to report with technical data right after an R3 (browser, gamepad/keyboard, whether the subtitle shows) and, if the subtitle shows without voice, add the ring RMS of the last 2 s to the report.

## 10. Game logic rate and speed between clients (2026-11-02)
Runtime `8618fcd` (outbreak). New tools: `PS2_WATCH=<every>:<addr>,...` (prints u32 + wall clock every N vblanks), `PS2_SNAP=<from>,<every>,<n>,<prefix>` (dumps EE RAM to look for counters) and `PS2_PACE=<frame>[,<hz>][;<frame>,<hz>]` (slower pace from a frame on: simulates a machine that cannot keep up).

### 10.1 How many logic updates per second [FACT, native]
- **Logic counter** (increments 1 per world step; found by comparing dumps of 6 consecutive vblanks): File #1 `0x3244C0` (also `0x22A60C`), File #2 `0x300A50` (also `0x23ABEC`). Next to it there is one that adds 2 per step (vblanks of the step): File #1 `0x3243F4`, File #2 `0x300974`. The game's vblank counter is File #1 `0x20F3C4`, File #2 `0x21F9AC` (+1 per vblank).
- **Measurement:** J's Bar (jsbar route, frames 6000-10800) = exactly **150 steps per 300 vblanks**; Wild Things (frames 7200-9120, cutscene and control) = **120 per 240**. That is **1 step every 2 vblanks = 29.97 Hz**, the same in both titles, and steps always fall on the same even vblank (pattern 0,1,0,1,0).
- **Fixed step, no delta-time [FACT]:** with the EE 4 and 8 times "slower" (temporary test: vblank every 1/4 and 1/8 of the cycles) J's Bar dropped to ~55 and ~27 steps per 120 vblanks: the game does not compensate; when a step does not fit in 2 vblanks it takes 3 or 4 and **the world runs in slow motion**. With the normal EE there is plenty of margin (at 1/4 of the cycles it still does ~28 steps/s), so our runtime never falls to 20 Hz from emulated EE load; a real PS2 perhaps does in heavy scenes (not measured; that would be a case where our runtime is slightly faster than the original).
- **Routine (File #1 ELF) [FACT, from code]:** `0x1BAAF0(n)` calls the vsync wait `0x19F6C0` `n` times and stores the vblank counter (`0x1AC630` → `*(0x294170)`) at `0x3243F4`.
- **Original:** NTSC = 59.94 fields/s (standard); that Outbreak runs at 30 in game is stated by the community and matches what was measured, but it was not verified on a real PS2. A 60 fps emulator patch (if one exists for Outbreak) would do steps every vblank: none was found and none is used.

### 10.2 Runtime timing [FACT, from code]
- Vblanks are generated in **emulated time** (`frame_cycles()` = EE clock / 60, `system.cpp`): the logic in steps per vblank is deterministic; the only thing that changes with the machine is how many vblanks there are per real second.
- **Native:** `PS2_PACE` sleeps until the next period and, if late, `next = now` (**no catch-up**). The SDL window is governed by the audio queue (≤ 80 ms) or by a clock that also does not catch up.
- **Web:** `pace()` (`host/web.cpp`) is governed by the audio ring: it waits while more than 80 ms (or the "stable audio" value, ≤ 250) is queued. If the machine falls behind, the queue empties and the game runs at whatever it can; on recovery it only refills those 80-250 ms (**maximum burst ~5-15 vblanks**). Without audio, a clock with catch-up capped at 200 ms (12 vblanks). The disc pause (`fdf2bc1`, `disc::ready`) blocks emulation; on return, the same: ≤ 80-250 ms of burst. Hidden tab: rendering does not stop emulation (`shownRelease`) and the audio keeps setting the pace. **There are no cases of sustained "faster than real".**
- **Slower than real does happen:** a machine that cannot reach 60 vblanks/s plays in slow motion (fixed step). On the dev WSL a native run alone in J's Bar without a limiter did ~30-40 vblanks/s (6600→9600: 300 vblanks in 6-13 s).

### 10.3 Clients at different speeds, online [FACT, native; local multi-client scripts with PACE per client]
- `lr1` (A and B at real time; both capped at ~28-30 vblanks/s by CPU): 0x0F forwarded A→B 272, B→A 261 in ~8 min of play: **~1 kk packet per second per client** in J's Bar (C>S per minute ≈ 490/8.6 min). The host does not send positions per step: each client simulates the enemies and the 0x0F flow carries events/corrections.
- `lr2` (A free, B at 12 vblanks/s from frame 23000 = **6 steps/s**): B's pending flow (`0x33D9F0`) stays at 0 or a single message (64-148 bytes) in every sample: the game **consumes everything that arrives at each step**, no network backlog accumulates. The effect is only local: B sees the world (enemies, its character) in slow motion and receives the host's corrections at real pace. 0x0F forwarded: A→B 503, B→A **193**: what each client sends is tied to its steps, so the slow one also sends less (the others receive its data more spread out).

## 11. Disc reading without delay (2026-11-03), not deployed
- `kernel::disc_read_cycles()` (`iop/kernel.cpp`) replaces the DVD reader delay in `cdvd.cpp` (400 µs + 250 µs/sector) and `cdvdfsv.cpp` (400 µs + 200 µs/sector): by default **0** (the read is dispatched in the next IOP event cycle, after the call returns). `PS2_DISC_SPEED=orig` restores the original delay, `=<factor>` scales it.
- [FACT] Entering J's Bar 559 → 354 frames, Wild Things −480 frames; ×0.25 and 400 µs per request barely gain (512 / 504): [strong HYPOTHESIS] the game's loader checks each read once per frame. Movie audio, scenes and music unchanged. Table and detail: [fast-loading.md](fast-loading.md).
- Measurement: `PS2_LOADLOG=1` (dark screens and read bursts with start and end frame), `PS2_LOADLOG=2` (every read: `[disc] read <sector> +<n>`).
