# RAM map for a status tracker (US)

Goal: read the EE RAM from the page (JS) and show our own tracker, with no external programs.
Machine-readable map: [tracker-map.json](tracker-map.json). EE physical addresses (0x0-0x1FFFFFF),
little endian. File #1 = SLUS-20765 v2.00, File #2 = SLUS-20984.

## Source and method
- A community member (anonymous) shared "Outbreak Web Tracker" (a LÖVE app). It ships two readers:
  `luaoutbreaktracker.dll` (the public JP reader from madgamer98/OutbreakTracker, "the public reader" below) and `luaoutbreaktracker_us.dll`
  (the same binary with the addresses patched to US). The Lua sources and the DLL were read as data; nothing was
  executed.
- Binary diff JP vs US (Python `-I`): 763 bytes in 264 groups, all immediates of
  `mov eax, imm32` (`switch` tables that return addresses) or `add rax, imm32`, plus the module name
  (`tracker` -> `trackus`). Each JP value was cross-checked against the constants in `F1Addr.h`/`F2Addr.h` and the usage in `main.c`
  of the public repository (which give the name, read size and encoding). All groups are
  explained except one 4-entry table (JP `0x3BA200`/`0x3B9B40` -> US `0x4042E0`/`0x403C20`) whose use
  is not in the public source (the DLL is newer than the GitHub version).
- The DLL is newer than the public source in two points: File #1 has 15 doors (not 10) and File #1
  `ItemRandom` uses `0x23BB28` JP (-> US `0x2B8DE8`), not `0x23BB20`.
- Not patched (identical in both DLLs; the code does not use them or they stayed JP): `F1/F2_Pointer`,
  `F1/F2_DoorHP`, `F1_DoorFlag`, `F2_DTStart`, `F2_FBTime` (`0x491254`, "flashback"/below-freezing-point time:
  **has no US address**).
- The offsets inside the player, enemy, item and enemy-list structs **do not change** between JP and US (they do not appear in
  the diff). Only the File #1 lobby room-player offsets change (+0xE4/+0xE6 -> +0xEC/+0xEE).

## Game detection
| What | Address | Encoding |
|---|---|---|
| Is File #1 | `0x250443` | u8 == `0x53` ('S' of the ELF name in RAM) |
| Is File #2 | `0x255083` | u8 == `0x53` |
If neither: no game (the original tracker returns -1; the web tracker shows it as `currentFile = 255`).

## Players (4 slots)
Slot k base = `P1 + stride*k`. File #1 `P1 = 0x4A5C30`, stride `0x10E0`; File #2 `P1 = 0x4C5E10`, stride `0x1100`.

| Field | Off F1 | Off F2 | Type | Encoding |
|---|---|---|---|---|
| enabled | 0x000 | 0x000 | u8 | nonzero = slot used |
| inGame | 0x001 | 0x001 | u8 | nonzero = in the scene |
| position X / Y | 0x038 / 0x040 | same | f32 | |
| size | 0x0B4 | 0x0B4 | f32 | |
| room (room id) | 0x1E2 | 0x1E2 | u16 | see `roomnames.lua` of the community tracker |
| health | 0x544 | 0x540 | u16 | |
| max health | 0x546 | 0x542 | u16 | |
| character | 0xB98 | 0xBB0 | u8 | 0 Kevin, 1 Mark, 2 Jim, 3 George, 4 David, 5 Alyssa, 6 Yoko, 7 Cindy |
| status | 0xBA0 | 0xBB8 | u8 | see below |
| virus | 0xBAC | 0xBC4 | u32 | % = virus / virusMax[character] x 100 |
| crit bonus | 0xBB0 | 0xBD4 | f32 | |
| nameId (NPC) | 0xBB4 | 0xBC8 | u8 | |
| antivirus | 0xBB6 | 0xBCA | u16 | 1/60 s (`Time2string5`) |
| antivirus G | 0xBB8 | — | u16 | File #1 only |
| herb (hemostatic) | 0xBBA | 0xBCE | u16 | 1/60 s |
| speed / power | 0xBC0 / 0xBC4 | 0xBD8 / 0xBDC | f32 | |
| bleeding | 0xC5A | 0xC6A | u16 | 1/30 s |
| equipped weapon | 0xC7C | 0xC8C | u8 | 1-based index into the item table (0 = none) |
| inventory | 0xC84 | 0xC94 | u8[4] | 1-based indices into the item table |
| special item (5th) | 0xC88 | 0xC98 | u8 | 1-based index |
| Yoko's rucksack | 0xC89 | 0xC99 | u8[4] | 1-based indices; Cindy: 36 bytes = 4 groups of 9 (herb bag) |

`virusMax`: File #1 `0x71ADB0`, File #2 `0x776C00`, u32[8] indexed by character.
Dead/zombie inventory: File #1 `0x4BAC52`, File #2 `0x4DB236`, 8 bytes per slot (4 inventory + 4 special).

Status (status byte): 0 OK, 1 poison, 2 bleeding, 3 poison+bleeding. File #1: 4 escaped, 0x18-0x1C down,
>0x1C dead, >=0x80 zombie. File #2: 4 gas, 6 gas+bleeding, 0x08-0x0B down, 0x0C down+gas, >0x0C dead,
0x30-0x34 zombie. (Our runtime uses bit 0x10 of the byte at +0x663/+0x67F from the health field, i.e.
base+0xBA7 / base+0xBBF, for "dead": those are different bytes from the status byte.)

## Items (ammo)
Table of 255 entries x 60 bytes: File #1 `0x3C566C`, File #2 `0x3E1C5C`. Entry i (1-based inventory index
-> `table + 60*(i-1)`): +0x01 u8 room, +0x04 u8 number, +0x06 u16 item type (see `icondata*.lua`),
+0x1B u8 mix, +0x20 u32 present, +0x24 u16 **count / ammo**, +0x28 u16 picked up. The items of the current
room come from the same table filtered by room (the original also reads a pointer at File #1 `0x3B5160`,
File #2 `0x3D1B60`, blocks of 196/192 bytes).

## Enemies
- List: File #1 `0x74F250`, File #2 `0x7AFF80`; 80 x 0x60. +0 number, +1 flag (nonzero = used), +2 nameId, +3 type,
  +0x1C u16 HP, +0x1E u16 max HP, +0x22 u8 room, +0x45 u8 status.
- 12 scene slots: File #1 `0x44EE60` + 0xD90*k, File #2 `0x46E6B0` + 0xDB0*k (k=0..11). +0 enabled,
  +1 inGame, +3 nameId, HP +0x544/+0x540, max HP +0x546/+0x542, type +0xBBA/+0xBD2.

## Doors
u16 HP and u16 flag per door (fixed tables per scenario, see the JSON). File #1 has 15 doors (HP `0x4A1E20`...,
flags `0x4B9E2C`...), File #2 has 19 (HP `0x4C1800`...`0x4C1C06`, flags `0x4DA124`...`0x4DA3F0`).

## Game
| Field | File #1 | File #2 | Type | Encoding |
|---|---|---|---|---|
| game clock | `0x4BADE8` | `0x4DB3A8` | u32 | logic frames at 30 Hz (the tracker does `frames/0.03` ms) |
| scenario | `0x3243DA` | `0x30095A` | u16 | scenario id |
| difficulty | `0x4BAE8A` | `0x4DB44A` | u8 | 0 easy, 1 normal, 2 hard, 3 very hard |
| player count | `0x4BAEA0` | `0x4DB468` | u8 | |
| finished | `0x4BADD0` | `0x4DB390` | u8 | |
| room owner | `0x815830` | `0x815C30` | u8 every 4 bytes, 128 rooms | player index |
| NIGHTMARE | — | `0x2F51C1` | u8 | bit 3 = NIGHTMARE, bit 0 = INFINITY |

File #2 extras: coins (Wild Things) `0x4DB278` (4 x u8 at +0,+2,+4,+6, summed), zombies killed `0x4DB358`,
scenario times `0x4DB338` (WT), `0x4DB346` (escape), `0x4DB35C`/`0x4DB33E` (DT fight), `0x4DB360`
(garage), `0x4DB368`/`0x4DA0EC` (gas), item/puzzle randomness `0x297948`, `0x300A49`, `0x300A48`.
File #1 randomness: `0x2B8DE8`, `0x3244B9`, `0x3244B8`. The passwords/puzzles (`Pass*`) are in the original's
JSON but are **not used**: the web tracker does not show answers (a decision of the original).

## Lobby
- 20 rooms every 0x15C: File #1 `0x6CD4A0`, File #2 `0x6DEAA0`. +0 u16 number, +2 u16 players, +4 u16 max,
  +0xE u8 status, +0xF u8 password, +0x14 u16 scenario, +0x16 u16 version (0x11 DVD, 0x12 HDD), +0x18 title.
- Room players: File #1 `0x6DBBE4/0x6DBF94/0x6DC344/0x6DC6F4` (+0 enabled, +0xEC nameId, +0xEE NPC);
  File #2 `0x6E6058/0x6E6408/0x6E67B8/0x6E6B68` (+0 nameId, +2 NPC, +6 enabled).
- Own room (host): File #1 status `0x6D8DB0`, scenario `0x6D8DB6`, time `0x6D9708`, players
  `0x6494FE`, max `0x6A3E7A`, difficulty `0x6A67CA`; File #2 `0x6E2ED0`, `0x6E2ED6`, `0x6E3850`, `0x6540BA`,
  `0x6B547A`, `0x6B7DCA`.

## What is shown per difficulty (rule of the original web tracker)
- Difficulty is read from the game byte (`0x4BAE8A` / `0x4DB44A`), only when there is a scenario (non-empty name).
- EASY and NORMAL: everything.
- HARD: player cards (status and inventory, no room), clock, and enemy HP bars + names.
- VERY HARD: player cards and clock.
- No key shows them again on HARD/VERY HARD.
- NIGHTMARE (File #2 only): if `0x2F51C1 & 0x08`, nothing else is read and "Tracker is disabled with
  NIGHTMARE mode on." is shown. The original also treats a File #2 whose byte cannot be read as "on".
  File #1 has no NIGHTMARE.

## EE RAM from JS (web runtime)
- RAM is the global array `g_ram` (`runtime/src/memory.cpp`, 32 MiB + 64, 64-aligned) inside the wasm linear
  memory: `Module.HEAPU8[ eeBase + addr ]`. `HEAPU8` is already exported (`EXPORTED_RUNTIME_METHODS`).
- **Exported (outbreak `2dea3f8`)**, see "In-page UI" below. Before this there was no function returning `g_ram`. In `host/web.cpp`:
  `extern "C" EMSCRIPTEN_KEEPALIVE std::uint8_t *ps2web_ee_ram() { return g_ram; }`, and `_ps2web_ee_ram` added to
  `EXPORTED_FUNCTIONS` (`CMakeLists.txt`). Requires a new wasm.
- With `ALLOW_MEMORY_GROWTH` and pthreads the buffer can change: take `Module.HEAPU8` again on every read
  (or a new `DataView` if `buffer` changed). Use `getUint16(addr, true)` etc.; `g_ram` itself is fixed for the session.
- Reading every 2 vblanks (30 Hz) is enough; the game updates at 29.97 Hz (runtime.md §10.1).

## Verification [VERIFIED, native, 02/11]
Offline runs of the native client (`ps2-web-recomp` outbreak, already-built binaries) with the routes
`scripts/routes/outbreak1-jsbar-bathroom.txt` and `outbreak2-wildthings.txt`, `PS2_SNAP` dumps (F1 frames
9000-13000 every 1000, F2 8000-9200 every 400). A local script read the dumps (the `.ram` files and screenshots are not
published). Values read with this map's US addresses:

| | File #1 (J's Bar, 3 players, NORMAL) | File #2 (Wild Things, 3 players, NORMAL) |
|---|---|---|
| detection | `0x250443`=0x53, `0x255083`=0x41 | `0x255083`=0x53, `0x250443`=0xA0 |
| characters | slot0 0 Kevin, slot1 1 Mark, slot2 7 Cindy | 0 Kevin, 1 Mark, 2 Jim |
| health / max | 2300/2300, 3000/3000 -> **2820/3000** after the attack, 1500/1500 | 2300/2300, 2840/3000, 1800 -> **1580/1800** |
| virus | rises on every dump (Kevin 1770->3240 of 151200 = 1.17%->2.14%) | 1260->1860 of 151200 (0.83%->1.23%) |
| inventory | Kevin: special #81 type 400 x7 (.45 auto, 7 rounds); Mark: #1 type 300, picks up #67 type 28 x9; special #82 type 401 **15->12** rounds; Cindy: special type 413 (bag), bag[0]=#85 | Kevin #119 type 210 x7, special 400 x7; Jim #5 type 103 x40 |
| equipped weapon | Mark = 82 (his pistol, the one spending rounds) | Jim = 5, Mark 0 -> 123 |
| enemies (list) | 10 in room 1; one drops **800->410** HP while Mark spends 3 rounds | 4 (one of type 0, 9000 HP, room 4) |
| clock | 1715->2150->2650->3150 (500 per 1000 vblanks = 30 Hz) | 1208->1608->1808 (400 per 800 vblanks) |
| difficulty / players | 1 (normal) / 3 | 1 / 3 |
| NIGHTMARE `0x2F51C1` | 00 | 00 (offline game with no rule) |

Matches what the runtime already used: `0x4A6174` = P1 health File #1 (0x4A5C30+0x544), virus +0x668 = base+0xBAC;
File #2 `0x4C6350` = 0x4C5E10+0x540, virus +0x684 = base+0xBC4; strides 0x10E0 / 0x1100 the same.

**VERIFIED:** detection, enabled/inGame, health, max health, character, virus % (with virusMax), inventory + special +
Cindy's bag (indices into the item table, count = ammo), equipped weapon, enemy list (HP), clock,
player count, difficulty = 1 on NORMAL. File #2 NIGHTMARE was verified earlier online (protocol.md).

**HYPOTHESIS (diff + public source only):** difficulty values 0/2/3, status (codes), player room
(1/3 was read, plausible), scenario (F1 = 1, F2 = 40: US name table needed), antivirus/herb/bleeding timers,
Yoko's rucksack, dead inventory, scene enemy slots, doors, room items, room owner, lobby and
own room (need an online run), File #2 special times/counters and randomness values.

## In-page UI (2026-11-02, outbreak `2dea3f8`)
- **Export:** `ps2web_ee_ram()` in `host/web.cpp` (returns `ps2::g_ram`) + `_ps2web_ee_ram` in `EXPORTED_FUNCTIONS`. Requires a new wasm. With an old wasm the tab says "This engine cannot read the game".
- **Where:** everything in `web/shell.html` (no new files to version): CSS `.trk*`/`#trk-ov`, a **Tracker** tab in the side panel (between Rooms and Settings), the `#trk-ov` strip inside `#stage`, a **Tracker (F2)** button in the bar, and at the end of the script `TrackerArt` (the decoder from [tracker-art.md](tracker-art.md), without `export`) and the `trk*` block.
- **Card:** portrait (bust cropped from the TIM2 in `select.tex`), name, status dot and text, health bar (green >=66%, amber >=33%, red), health/max, virus %, room (EASY/NORMAL only), antivirus/herb/bleeding timers if running, 4 items + the character's item with icon, name in the tooltip and count (firearms, magazines/ammo, or if >1); the equipped weapon (index equal to `eq`) gets a gold border; Yoko's rucksack on a second row. Footer: clock (`frames/30`) and difficulty. Below, in the panel: enemies in P1's room with a health bar and items in the room (item-table entries with room = P1's room, present nonzero, not picked up, not held by anyone).
- **Overlay:** the same compact card in a row, semi-transparent, top or bottom, opacity 30-100% and size 70-150% (in `em`). F2 toggles it while the game runs (before playing it opens the tab); F2 was added to the reserved keys (`k.reserved` in the 7 languages). Below 560 px the portrait is hidden.
- **Settings** (`reo-trk-v1` in localStorage, try/catch via `store()`): over the game, in the panel, vertical/horizontal panel cards, room items, enemy health, timers, position, opacity, size. A "Credits" link to the existing dialog.
- **Difficulty rules** (same as the original): 0/1 everything; 2 cards without room + clock + enemies; 3 cards + clock; NIGHTMARE (File #2, `0x2F51C1 & 8`): stops before reading players and shows "Tracker disabled in NIGHTMARE mode". No F2/F4 pages of the original.
- **Empty states:** not playing ("The tracker activates..."); game loaded but no player with `inGame` (menu, lobby, **cutscenes** and results: "No game in progress, or in a scene..."). The strip shows nothing in those cases.
- **Performance:** a 100 ms `setInterval` only if the strip is on with the game running, or the Tracker tab is visible with the game running and the page visible; otherwise there is no timer (verified: `trkTimer` = 0 when hidden, comes back on show). The HTML is built as text and only written if it changed. Measured: steady 60 fps with the strip and the panel active (File #1 and #2).
- **Art:** loaded once per page when the first game is seen (~1.2 MB of range reads with `cache: 'no-store'` to `disc/NNN.bin`); icons and portraits are converted to blob URLs on demand. Scenario icons: atlas `rNNN.afs` loaded by scenario number (`(id-10000)/100`), so a new scenario loads its own.
- **Continuity between games:** nothing tied to the game is cached: each tick re-requests `ps2web_ee_ram()`, `Module.HEAPU8`, file detection, NIGHTMARE, players, difficulty, room, enemies and items. Only the art (from the disc, not from the game) and the per-scenario atlases are kept.
- **Two build traps:** emcc minifies the shell and turned `/\0.*$/` into a literal NUL (the regex stopped working and the AFS table could not find `romdata_usa.afs`); `split(String.fromCharCode(0))` is used instead. The names in `itmsge.dat` end in a raw `0x00` byte (not only `0xFF`): without cutting there they came out padded with `ÿ`.

### Verification [VERIFIED, local, headless Chrome + WebGPU, `run-local.sh`]
A route script (variant of `scripts/web_route.mjs`); screenshots stay out of the repo.
- **File #1** (`?nonet&lang=es`, route `outbreak1-jsbar-bathroom.txt`): title/menus = empty; from frame ~6000 = game, NORMAL, Kevin 2300/2300, Mark 3000/3000, Cindy 1500/1500; virus rises (Kevin 0.18% -> 2.24%); items with name and icon: Kevin's .45 auto x7, Mark's handgun x15 (in the previous run it dropped 15->12->10 while an enemy went 800->449), herb case, green herb, room 1/3; at frame 10000 (cutscene) empty and at 11000 the game comes back by itself.
- **File #2** (`?nonet&lang=en`, route `outbreak2-wildthings.txt`, strip at the bottom): Kevin/Mark/Jim; Mark 3000->2500, Jim 1800->1560; inventory equal to the game's ITEM menu in the same capture (Jim: coin, handgun x15 equipped, green herb, iron pipe x40; Kevin: .45 auto magazine x7, .45 auto x7); enemy 700->200.
- Rules against real state with difficulty forced only in the render: 0/1 everything; 2 no room or room items, with enemies and clock; 3 only cards and clock; NIGHTMARE only the message (ES and EN).
- 390 px: `scrollWidth` 390 with the strip on. Scenario icons: r001, r028, r035, r041 decode; r002 loads (its first id has no icon).
- **Not tested:** real HARD/VERY HARD/NIGHTMARE (the value was only read on NORMAL), an online game (which slot is "me": slot 0's room is used), enemy and room names.
- **Verified by a tester (02/11, run-local):** finishing a game (results) and starting another on the same page: the tracker keeps updating. Reported issues: each player's room was missing from the card, a dead enemy reappeared with full health, and enemies and room items were requested in the overlay (all addressed below).

## Room and enemy names, Cindy's bag, dead enemies, extended overlay (2026-11-02, local, not deployed)
- **Rooms:** the name comes from the player's own disc (map table, see [tracker-art.md](tracker-art.md) "Room names"), per scenario = `u16` at `0x3243DA` / `0x30095A` (1 = J's Bar, 40 = Wild Things). Each card (panel and strip) shows **that** player's room at the top right (tooltip "name · Room N"); without a table it falls back to "Room N". EASY/NORMAL only, as before. Text in English (the US disc's) in all languages.
- **Enemies:** own table `TRK_ENEMY` (nameId -> [en, es]) + `TRK_ENEMY_SUB` (nameId+type: Cerberus, Stalker/Feral, Tyrant R/C, Thanatos R, green zombie, character zombies `type 70-77`, Bob, Will). Ids taken from the public reader (`structs.h` GetEnemyName/GetZombieName...); names written by us. The game does not show them and they are not on the disc. Unknown ids: "Enemy (type nameId/type)". Other languages use English.
- **Dead enemy bug (user report):** the list of 80 (`0x74F250`/`0x7AFF80`) was read with `Math.min(hp, max)`; on death HP goes negative (65522, 65527, 65535 measured) and it was drawn as a full bar. [VERIFIED] Now the **12 scene slots** are read like the original (`0x44EE60`+0xD90*k / `0x46E6B0`+0xDB0*k; +1 inGame, +3 nameId, HP/max at +0x544/+0x540, type +0xBBA/+0xBD2), HP 0 or >= 0x8000 is discarded, and the last slot with inGame 0 counts only if it is a plant (13/35/46). nameId 11/12/14/26/34/56 (invincible) go without a bar (∞).
  - Evidence (J's Bar offline, 22000 frames, raw dump of slots and list every 300 frames): slot 10 750->350->220->**65522** (hidden) and ~300 frames later inGame 0; slot 9 180->65527; slot 8 31->9->65535. In J's Bar new zombies keep entering: the same slot comes back ~600 frames later with full health (750/750, inGame 1, status 0) and is shown as a new enemy; this is game behavior, not the tracker's. [HYPOTHESIS] slot reuse by a new zombie (which one enters was not seen on screen).
  - Wild Things: slot 11 stays enabled with inGame 0 and a zombie from another room (the original's "plant boss" case showed it): now filtered out.
- **Cindy's herb bag** (herb case): the same bytes as Yoko's rucksack (+0xC89/+0xC99), 4 groups of 9 indices: groups 0-2 = stack (icon of the first, count = sum), group 3 = a single item (public reader, GetCindyBag). Measured: GREEN x2, BLUE x2, RED x2 -> later GREEN, BLUE x2, RED, MIXED HERB. Tooltip "Herb case".
- **Strip (overlay):** a thin row next to the clock with the room's enemies (name + short bar, no numbers) and the room's items (icons 2.4 em; 1.7 em below 560 px). Respects "Enemy health" / "Room items" and the difficulty rules (enemies up to HARD, items up to NORMAL).
- **Verification [VERIFIED, headless Chrome + WebGPU, separate instance]:** page assembled from `web/shell.html` with the already-built engines (via symlinks, without touching the build directories). File #1 `outbreak1-jsbar-bathroom` (ES): room names per player, enemies "Zombie", "Will (zombie)", Cindy's bag, dead ones hidden. File #2 `outbreak2-wildthings` (EN): "In front of Elephant Restaurant", "Elephant Restaurant", Zombie 780->280, inventory equal to the ITEM menu. 390 px with no horizontal scroll.
- **Not tested:** other scenarios (the tables for r002/r028/r035/r041 and r006/r010/r015/r026 exist on the disc; not played), boss names on a real screen, the last-slot plant.
- **Status dot (02/11, `125adff`):** each player's dot and health bar follow the game's per-health condition (>=75% FINE green, >=50% CAUTION yellow, >=25% CAUTION orange, >0 DANGER red; same cutoffs as the original's `GetConditionName`). Down/dead/zombie/escaped keep their color; poison, bleeding and gas stay as text next to the name. Not verified in a game (syntax check only).

## Online: what is synchronized of the remote player (2026-11-03, outbreak `e97e0d6`, page only)
Report from production (File #1 NORMAL, 4 players): Cindy sees her own virus correctly (34.3%) and the other three at
"Virus 0.0%"; remote Jim and Alyssa with max health 1404 (504/1404, 1204/1404); remote Kevin 2025/2300 correct.

**Test bench:** a local script, 2 native File #1 clients at 0 ms (A = Kevin slot 0 host, B = Mark slot 1).
TV1: `PS2_SNAP` dumps 21500-27500; TV2 (`IDLE=1`, zombies kill them): `PS2_WATCH=300:4A6174,4A7254,4A67DC,4A78BC`
(u16 health + u16 max and virus of slots 0/1) and dumps at 33000/37000. Local scripts compared fields per slot in
both clients (plus differing words of the struct) and ran the page's `trkRead` over a dump.

### VERIFIED
- **Session:** `*(0x8147B8)` = `0x8A49F0` in both; `+0x68` = 0 on A and 1 on B, `+0x70` = 2, `+0x74` = [0, 1]. Offline
  that address holds overlay code (`0x0C2069B8` File #1; File #2 `*(0x8151E8)` = `0x282D`), so it is validated
  (aligned, in RAM, my slot in the list) before use.
- **Remote virus: not synchronized.** The other player's copy stays at **5** the whole time (A sees Mark = 5
  while B has 360 -> 3420 -> 18570; B sees Kevin = 5 while A has 420 -> 2340 -> 28860). 5/virusMax = 0.0% (what
  the player saw). One exception seen once: with Kevin down (status 0x18), B read 75605 for Kevin (A's own
  58860 a few frames earlier): with the player down the value can arrive, but it is not reliable.
- **Health and max health:** synchronized in this run. B sees Kevin 2300 -> 2120 -> 970 -> 1 -> 0 out of 2300 and A sees Mark
  the same (3000 -> ... -> 0 out of 3000), with network delay. The max health field never changed.
- **Character, special item, inventory, room, inGame:** the same in both clients. Status: both see 0x18
  (down) for both players at the end. About 90 of 1080 words of the struct differ (positions/matrices,
  pointers, `+0xBAC` virus, `+0xBD8`, `+0xC34-+0xC64`).
- **Max health table per character** (ELF data, in RAM): File #1 `0x71B020`, File #2 `0x776E70`, u16[8]:
  Kevin 2300, Mark 3000, Jim 1800, George 2100, David 2200, Alyssa 2000, Yoko 1300, Cindy 1500 (matches all
  measured maxima: Kevin, Mark, Jim, Cindy).

### HYPOTHESIS / not reproduced
- **1404 was not reproduced** (only 2 players, Kevin/Mark, no items). 1404 is not Jim's max (1800) nor
  Alyssa's (2000) and it is the same number for both: a wrong value in the remote copy in some case (4 players?
  chosen characters? late join?). No evidence of which.
- The original game does not show teammates' virus (its own UI shows only your own): it was not looked at
  on screen in this run; the RAM data is enough to decide not to show it.
- File #2: the same fix with its session (`*(0x8151E8)`, same offsets, network.md) and its table `0x776E70`; **no
  online File #2 game was run** (the table was read in the offline Wild Things dump).
- Remote timers (antivirus, herb, bleeding): not verified.

### Fix in `web/shell.html` (outbreak `e97e0d6`, page only, no wasm)
- `me` = the session's `+0x68` if `netState === 'on'` and the session is valid; otherwise `me = -1` and **everything is shown as
  before** (offline the teammates are this game's AI and their data is local).
- Remotes: virus shown as "Virus —" with tooltip `trk.virusOwn` (EN "Only visible to each player: the game does not send the virus of the others"; other languages fall back to English like the rest of the tracker). Max health = per-character table (or current health if larger);
  current health = the (synchronized) field.
- Enemies and room items: **my** slot's room is used (before, slot 0's).
- Verified with the `trkRead` runner over the dumps: A `s0 2300/2300 v=1.75 | s1R 3000/3000 v=—`; B `s0R 970/2300 v=— | s1
  1460/3000 v=4.34`; downed `s0 0/2300 v=38.93 | s1R 0/3000 v=—` and the reverse on B; offline File #1/#2 dumps (with
  `netState` 'on') = all complete. `node --check` of the page script OK. Not tested in the browser.

## Teammates' virus: a dedicated channel between pages (2026-11-03; outbreak, gateway, server; not deployed)
Format and rules in [protocol.md §6.18](protocol.md). Summary: each page in an online game sends once per
second (only if changed, or every 3 s) its slot, character, status byte, virus in tenths of % and the
antivirus/herb/bleeding timers over the runtime's WebSocket (the page wraps the `WebSocket` constructor; no
new wasm); the gateway passes it to the server as `REOGW-TRK` and the server forwards it only to those in the same game.
It is sent even if the tracker is closed (teammates may have it open; <= 1 msg/s).

### What else is not synchronized (besides virus)
- **Bleeding [VERIFIED, one sample]:** TV2 dumps at frame 33000: B sees its Mark with status 2 (bleeding) and
  timer 1115; A sees Mark with status 0 and timer 0. So neither the bleeding status nor its timer
  reached the other side (could be delay; a single sample). Down (0x18) is seen on both.
- **Antivirus and herb [UNVERIFIED]:** in the dumps nobody used items (all 0). They are sent anyway: the
  owner's value is correct even if the game also synchronized them (at most 1 s delay).
- So the receiving page uses, if the data is <= 5 s old and the character matches: virus, antivirus, herb,
  bleeding and status except down/dead/zombie (those are synchronized by the game and its copy is newer).

### Tests
- **Page over dumps [VERIFIED]** (a local script runs the page's code with a fake WebSocket): B (`d-33000`) sends `{"type":"trk","slot":1,"ch":1,"st":2,"virus":43,"av":0,
  "herb":0,"bleed":1115}` once over two ticks; A (`c-33000`) before `s1R 3000/3000 ok v=—`, after
  receiving the `kk-peer-trk` `s1R 3000/3000 bleed v=4.3 bleed 1115`; with the data at 6 s, or with another character,
  it goes back to `v=—`; when the socket closes it is cleared; a WebSocket to another URL is not picked up.
- **Two native clients (gateway channel + real server):** the native runtime has a test stand-in for the page
  (`PS2_NET_TRK=p1:stride:ch:st:vir:av:herb:bleed:vmax`, only outside the browser: `#ifndef __EMSCRIPTEN__`, the
  wasm does not change) that sends the same JSON and logs the `kk-peer-trk` received.
- **Run TK1 [VERIFIED]** (File #1, 0 ms, `IDLE=1`, with `PS2_NET_TRK=4A5C30:10E0:B98:BA0:BAC:BB6:BBA:C5A:71ADB0`): A (Kevin, slot 0) received 70
  `kk-peer-trk` from B (Mark, slot 1) with virus in tenths of %: 0 1 2 ... 12 15 29 33 38 39 40;
  B received 104 from A: 0 1 ... 19 39 48 56 64 70 71. B's last `trk test: sent` (every 600 frames) is 40 = the
  last one A received. No exceptions in the server. The remote's in-game copy
  stays at 5 (run TV2), so without this channel both would see "—".
- **Not tested:** two real browser tabs (the `WebSocket` wrapper and send/receive were only run in node with a fake
  socket); online File #2; antivirus/herb in use.
