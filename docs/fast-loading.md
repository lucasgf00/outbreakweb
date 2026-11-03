# Fast loading for everyone (backlog 2, 2026-11-02/03)

Runtime `outbreak` `016003c` (local, not deployed; web pre-load = WIP, unmeasured at that commit). Two changes, both on by default for everyone:

1. **Disc reads with no delay** (native and wasm): a read completes at the next IOP event dispatch, after the call that requested it has returned (never inside it), with no game time in between. Before, it imitated a DVD drive: 400 us + 200-250 us per sector (`iop/cdvd.cpp` `read_cycles`, `iop/cdvdfsv.cpp` `delay`). Both now use `kernel::disc_read_cycles()` (`iop/kernel.cpp`).
   - `PS2_DISC_SPEED=orig` restores the drive delay; `PS2_DISC_SPEED=<factor>` scales it (`0.25` = four times faster). On the web: `?PS2_DISC_SPEED=orig` (`PS2_*` URL parameters are passed to the engine).
2. **Disc pre-load on the web** (`web/shell.html` `discWarm`, `host/web.cpp`): see §4.

New tools (measurement only): `PS2_LOADLOG=1` writes one line per dark screen (mean brightness <= 2 down to >= 8, >= 10 frames; NOW LOADING and fades count) and per burst of disc reads (`[load] dark frames A-B (N frames...)`, `[load] disc frames A-B (N frames, X MB...)`); `PS2_LOADLOG=2` also logs each read (`[disc] read <sector> +<n>`). `scripts/disc_warm.py` builds the pre-load list from those logs.

## 1. Native measurement [DONE]

Native headless (WSL, llvmpipe, `PS2_GPU_LOW_POWER`), routes `scripts/routes/outbreak1-jsbar-bathroom.txt` and `outbreak2-wildthings.txt`. Each load was measured from a save state taken with the original delay right before the load, so the rest of the game is identical. **Frames = game time (60 per second at normal speed); this is what the player sees.** Wall-clock time of these runs is useless (6-9 instances in parallel with software GPU at 10-30 fps).

| Screen | Original | x0.25 | 400 us per request | **0 (new)** | Saving |
|---|---|---|---|---|---|
| F1 boot, initial black (frames 22-...) | 153 | 130 | - | **96** | -1.0 s |
| F1 menu screens (1435 / 1780) | 55 / 54 | 47 / 48 | - | **42 / 46** | -0.2 s |
| F1 NOW LOADING before the "Outbreak" movie | 551 | 543 | - | **522** | -0.5 s |
| **F1 entering J's Bar** (16 MB, 165 reads) | **559** | 512 | 504 | **354** | **-3.4 s (-37 %)** |
| F1 event fade (door with zombie, 9790) | 49 | 47 | - | **45** | -0.1 s |
| **F1 area load after the cutscene** (NOW LOADING, 12.3 MB, 10401) | **510** | 459 | - | **394** | **-1.9 s** |
| F1 bathroom room: 0.5 MB reads during the animation | 63 | - | - | 62 | 0 (set by the game's animation) |
| F2 boot, initial black | 159 | 141 | - | **106** | -0.9 s |
| F2 menu load (1515) | 223 | 209 | - | **116** | -1.8 s |
| **F2 Wild Things: from NOW LOADING (4402) to the zoo entrance visible** | **1189** (zoo at 5591) | - | - | **709** (zoo at 5111) | **-8.0 s (-40 %)** |

- Full routes from power-on with delay 0 also complete: File #1 reaches J's Bar and the next area (13000 frames); File #2 boots to the title and enters a scenario (the fixed-press route picked another scenario because the menus arrived sooner: that is the route, not the game; from the save state Wild Things loads normally).
- **Why x0.25 and 400 us barely help [strong HYPOTHESIS]:** the game's loader polls each read once per frame. With any delay, each read costs ~1 frame; with 0 it is already ready when polled. J's Bar entry: 165 reads and 205 fewer frames (559 -> 354). So a "minimum delay per request" is not worthwhile: 400 us per request gave 504.
- Small-room door loads do not change: they read ~0.5 MB while the door animation runs, which has a fixed length.

## 2. Streaming: music, voices, movies [DONE, native]

Audio dumped with `PS2_DUMP_AUDIO` and RMS per second (a local script):
- **"Outbreak" movie (Sofdec, 9.2 MB in 806 frames):** original and 0 identical second by second (3426 3811 4266 1016 ... 4999-6284), same burst frames; no `late for` and no cuts.
- **J's Bar (opening scene with voices and music):** original has 8 s of silence at the start and then continuous 2300-5700; x0.25 and 400 us 7 s; **0: 5 s** of silence (starts earlier) and then continuous, no gaps.
- **File #2, Wild Things (zoo entrance scene with dialogue):** original 8 s of silence during the load, 0: 3 s; continuous audio during the scene in both.
- No hangs and no calls arriving early: the read is still asynchronous (dispatched in the next IOP event cycle, `iop::run`), not inside `sceCdRead`. Movies and ADX run at their own pace (set by the game clock, not the disc).
- Not tested: long mid-scenario cutscenes and all scenarios. What streams was the same in everything tested.

## 3. Online [DONE by code reading and notes; no 2-player run]

- Clients already load at very different speeds today: on the web the first visit downloads the disc over the internet (tens of seconds), a second visit reads from Cache Storage, a PS2/PCSX2 (obweb-bridge) uses its own drive. The game and the server already cover that:
  - The server withholds `0x0F` for a member that has not yet sent its first `0x0F` (protocol.md §6.x `d6`/`d7`, retention of up to 4096 reliables; also unreliable ones with data, max 256, L3 run with one client at 3000 ms per read).
  - The runtime raises the game's peer timeout from 20/31 s to 300 s (`system.cpp`, "A player who loads the scenario slower..."), precisely so the fast player does not drop the slow one still in NOW LOADING.
- Loading 2-8 s faster widens the gap against a slow client by those seconds, but that is within what already happens between the first and second web visit (and far below 300 s). **Low risk.** Small-room doors do not change (set by the animation).
- [HYPOTHESIS] A PS2/PCSX2 without our runtime (without the 300 s timeout) in a room with fast web clients already had this case reversed (the slow web one); this does not change.
- Pending if something shows up: a 2-player run with one at `PS2_DISC_SPEED=orig` and the other at 0.

## 4. Web: disc pre-load

**Web bottleneck [DONE, traces]:** a loading screen is many small, scattered reads across the disc, one after another (J's Bar: 165 reads in **96 distinct** 256 KiB chunks, jumps of hundreds of MB between `NETBIO00/01/02.DAT`; only 56 sequential). The reader's read-ahead only helps in sequential runs (movies). On a first visit each chunk is a full round trip while the screen waits: 96 x (RTT + 256 KiB) ~ 10-20 s at 20-50 Mbit/s and 50-100 ms RTT [estimate].

**Implemented (`web/shell.html` `discWarm`, `host/web.cpp`):**
- `GAMES[serial].warm`: list of chunks the game reads from power-on, through title and menus, up to the first scenario (File #1: Outbreak / J's Bar; File #2: Wild Things), in order of use, as ranges. Generated with `scripts/disc_warm.py` from native runs with `PS2_LOADLOG=2`; it leaves out sequential runs of more than 8 chunks (movies: covered by read-ahead and the player can skip them). **Positions only, no game bytes.** 177 chunks = 44 MB per game (about 20 MB of boot/menus, downloaded anyway, and ~24 MB of the scenario).
- When Play is pressed, it downloads those chunks into the same Cache Storage as the reader (`reo-disc-v1-<size>`, same keys `disc/NNN.bin?r=<offset>-<size>`), **two at a time**, in order. Chunks already cached do not touch the network (second visit: nothing). If the game asks for a chunk the pre-load is downloading, it waits on that same request (`Module.discWarmWait`) instead of requesting it twice.
- It does not run with `saveData`, a 2g/3g connection, without pieces (`?disc=`), or with **`?nowarm`** (for comparison). It stops after 8 failures. The error report adds a `discwarm` line.
- Web measurement: see §5.

## 5. Web measurement [DONE 11-03, File #1; File #2 partial]
Headless Chrome 156, network throttled to 25 Mbit/s + 80 ms, fresh profile per run (first visit). Evidence: local, not published. Darkness measured with canvas captures over CDP.

| File #1 (J's Bar route) | default (pre-load) | `&nowarm` | `orig&nowarm` (= before) |
|---|---|---|---|
| NOW LOADING + movie | 813 fr | 914 | 921 |
| **Entering J's Bar** | **520 fr / 9.1 s** | 1349 / 22.7 s | 1466 / 24.5 s |
| J's Bar visible at frame | 5532 | 6361 | 6480 |

- DONE: the pre-load gains -946 frames / -15 s (-65 %) in J's Bar on a first visit; delay 0 alone on the web gains little (-1.8 s), the network dominates. `discwarm`: 177 listed, 159 downloaded, 0 failures. 0 JS exceptions, 0 failed reads; audio buffer 67-97 ms in all three variants.
- DONE: with pre-load, one large game read waited 0.4 s (it competes with the two pre-load batches). Minor cost.
- DONE: File #2 with pre-load boots and enters Wild Things (813 frames from NOW LOADING, 162 downloaded, 0 failures). PENDING: compare with `orig&nowarm` (the fixed-press route picked another scenario; sync points are missing) and a second visit.
- DONE + HYPOTHESIS: `Cache.put` fails in every run with long-path profiles on Windows (>= 7-letter folder in the test scratch dir; probably MAX_PATH; unlikely in normal profiles). Before, the reader waiting on the chunk downloaded it again; fixed in `f462d26` (the chunk is delivered anyway, `unstored` counter in the report).
- DONE: `PS2_LOADLOG=1` crashed the web build (`writeBuffer` undefined: the log read the GPU frame from the emulator thread). Fixed in `f462d26`: on the web it only logs disc bursts. Not tested on the web.
- Local tests: `serve.py` without `--api` returns 404 on `/api/kk/register` and the name is rejected; the harness simulates registration.
- Tools: a local web-load harness (`webload.mjs` with `SAMPLE=1` and `PRE_JS`, brightness/darkness scripts, a route file). Use short profile folder names.

## What deploy requires
- New wasm for both games (`kernel.cpp`, `cdvd*.cpp`, `disc.cpp`, `system.cpp`, `host/web.cpp`) and the pages (`shell.html`). No server restart or Caddy/gateway changes.
- Rolling back without a deploy: `?PS2_DISC_SPEED=orig&nowarm` in the URL.
