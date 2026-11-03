# Web engine performance (fps on modest machines)

Production context: [measurements.md](measurements.md) (17% of players with median < 55 fps; Samsung browser 30.9% and Safari 13.6% of slow minutes).

## How it is measured
- `scripts/web_perf.mjs` (`ps2-web-recomp/`, since `641dfc1`): plays a route from `scripts/routes/` in headless Chrome (WebGPU) and, at frame `PROFILE_AT`, measures for `PROFILE_SECS` s: fps, ms per frame, idle ms per frame of each thread (EE = emulation, VU, GS = page), GPU counters per frame (`Module._ps2web_gpu_counter`: submits, dispatches per pipeline, bytes written) and a CPU profile of all threads (self time per function; the wasm has names via `--profiling-funcs`).
- It starts the game without the name dialog (no API needed): `serve.py file1=<dir>,<iso> file2=<dir>,<iso> <port>` and `URL=http://localhost:<port>/file2/?nonet`.
- `THROTTLE=4` uses `Emulation.setCPUThrottlingRate`. **Chrome only throttles the page's main thread, not workers** ("Operation is only supported for pages, not workers"). The main thread is the GS/WebGPU one, so this models the render side of a slow machine; it does not slow EE or VU.
- Scenes: File #1 J's Bar (`outbreak1-jsbar-bathroom.txt`, frame 13100) and File #2 zoo entrance of "wild things" (`outbreak2-wildthings.txt` without the last start, frame 9200).
- Noise: other jobs run natively and builds on the same PC. The same base build gave 39.0 and 45.9 fps on File #2 at 4x, and 29.6 and 42.1 fps on File #1 at 4x. Always compare back-to-back runs and repeat them.

## Where the time goes [FACT, Oct 24-25]
Unthrottled (fast PC, 60 fps), busy ms per 16.7 ms frame:
| thread | File #1 bar | File #2 zoo | most expensive |
|---|---|---|---|
| VU (worker) | ~10.2 | ~8.4 | VU1 microprograms `vu1_825986071014DF57` 4.1-4.6 ms and `vu1_0D50AFC88C628060` 2.3 ms; VIF unpack 0.4 |
| EE (worker) | ~7.4 | ~7.1 | `iop::execute` (IOP interpreter) 1.6-1.8 ms; VU0 in macro mode ~1 ms; VIF |
| GS (page) | ~6.6 | ~6.0 | `gs::vertex` (includes `gpu::draw`) 1.0-1.3; `gif::render` 0.9; `writeBuffer` 0.45-0.75; `_emscripten_get_now` 0.6-0.8 |

With the page at 4x (File #2 zoo, base): 23.3 ms per frame on the page, GS idle 0.2 ms: **the page is the bottleneck**. Breakdown: browser `(program)` 4.9 ms, `gs::vertex` 3.9, `gif::render` 3.3, `writeBuffer` 3.2, `emit_all` 1.4, `gs::write` 1.3, `_emscripten_get_now` 0.56.

GPU per frame (File #2 zoo): 1 submit, ~75 dispatches (upload 11, load_clut 12, raster 6.5, decode 43, display 1), **967 KB written to buffers**: 430 KB of texture uploads that missed the cache, 432 KB of primitives (~3900 primitives × 112 B), 45 KB of tile lists.

## The 3 biggest wins
1. **Broken upload cache [DONE, fixed in `641dfc1`].** `cache_insert` evicted the oldest entry even if the ring had not wrapped (the cache held ~1 entry) and failed if that entry had been used in the same submit. Outbreak re-uploads 256×256 PSMT8 textures (64 KB) to `bp 0x3120/0x3220` every frame: with the diagnostic log, all came out `insertfail`. Now the head only overwrites entries in `[head, head+words)` and skips those used by the current submit (they become the newest). Result: KB written per frame 967 → 510 (File #2), uploads from the arena 430 → 0 KB.
2. **GIF/GS on the page (`gs::vertex` + `gif::render` + `gs::write` ≈ 8.5 ms at 4x, 39%)** [HYPOTHESIS for improvement, not done]: packet parsing and building 28-word primitive records per primitive. Candidates: avoid the `memcmp` of the 100 B setup per primitive, `vector::insert` per primitive, a smaller record (also lowers `writeBuffer`, 432 KB/frame of primitives).
3. **Decode dispatches (43 per frame vs 6.5 raster)** [HYPOTHESIS]: each batch of >64 primitives decodes all its textures again and the decoded buffer is discarded at every submit. Caching decodes across batches/frames (invalidating by written pages and CLUT) would save GPU and dispatch overhead; the GPU cost is not measurable here (desktop GPU, ~0.2-0.6 ms/frame with timestamps).

Also done in `641dfc1`: the `render_pending` clock is read every 64 packets (was 8; `_emscripten_get_now` 0.56 → 0.24 ms/frame at 4x) and the VU0 macro lambdas are inlined (they vanish from the EE profile).

The VU thread is the busiest when unthrottled (8-10 ms): the recompiled VU1 code stores every result in `r.vf[]` in addition to the register value (`vstore` + shadow `nX`), and `mem` (`uint8_t*`) may point to `r`, so those writes cannot be eliminated. A register-liveness pass in `recomp/vurecomp.cpp` could remove them; medium risk, not done.

## Before / after (page at 4x, back-to-back runs, same PC)
| scene | base (wasm `b023ad668185` / `e51dd393440c` + fixed html) | new |
|---|---|---|
| File #2 zoo | 39.0 fps, 25.7 ms/frame (another run: 45.9 fps) | **54.4 fps, 18.4 ms/frame** |
| File #1 bar | 29.6 fps (another run: 42.1) | 40.2 fps, 24.9 ms/frame |
| File #1 bar (repeat, 30 s) | 30.0 fps, 33.3 ms/frame | **46.6 fps, 21.5 ms/frame** |
- The base includes uncommitted changes from other work at the time (system.cpp); the EE is not the bottleneck with the page throttled.
- Image: screenshots every 1500-2000 frames, base vs new (local evidence, not published). Static screens were pixel-identical (frame 4500 on File #2, frame 4000 on File #1: 0 differing pixels); in animated scenes the differences are capture-timing (camera/fog), with no wrong textures visible.

## Performance mode (proposal, not implemented)
- The default internal resolution is already 640×512 (scale 1): lowering it does not help; the cost is CPU.
- **Skip rendering 1 of every 2 frames on the page** when the GS thread has no idle time: GIF packets are still consumed to keep GS state (registers, uploads, CLUT), but `draw` and that frame's submit are skipped. Saves about half of `gs::vertex`/`writeBuffer`/dispatches (≈ 6-8 ms per frame at 4x). Visual cost: 30 fps on screen with the game at 60 (no slow motion or online desync). Risk: effects that read the previous frame's framebuffer.
- Enable it only by measurement (GS idle < 5% for several seconds) or from Settings → Performance.

## Next step
Repeat base/new several times (high noise) and attack item 2 (primitive record / `gs::vertex`).
