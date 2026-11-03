# GPU / presentation in the browser

## Broken video on Linux (Chrome/Brave/Firefox, Vulkan): `ImportMemory` (2026-10-22)

### Report
Ubuntu, 1366x768 screen, Chrome/Brave/Firefox. Audio and intro fine, video "glitchy". Log:
```
[gpu] error 2: Requested allocation size (1769472) is smaller than the image requires (2310144)
  at ImportMemory (dawn/native/vulkan/external_memory/MemoryServiceImplementationOpaqueFD.cpp:132)
Importing memory with an invalid handle (…:126)
… cascade of [Invalid Texture] / [Invalid TextureView] / [Invalid CommandBuffer]
```

### Facts (verified in the code)
- The runtime does **not** use `copyExternalImageToTexture`, `importExternalTexture` or video. The only texture that comes "from outside" is the canvas one: `wgpuSurfaceGetCurrentTexture` in `runtime/src/gs/gpu/gpu.cpp` (`present()`), configured in `configure_surface()` with the canvas size (`attach_surface`/`resize_surface`; the shell sets it in `fitCanvas()` → `_ps2web_resize`).
- In Chromium, the canvas `getCurrentTexture()` is a SharedImage that Dawn imports by fd (`MemoryServiceImplementationOpaqueFD`). The error comes from that import: it is the browser/driver delivering the canvas texture, not one of our textures.
- 1769472 = 768×576×4 exactly = a 768x576 canvas (4:3 window in 1366x768). 2310144 is what the driver requests for the Vulkan image; "768x752" is only the division, not a GS mode (the GS framebuffer size does not touch the canvas: `kDisplay` writes into `g_scratch`).
- Before the fix, the canvas pass went **in the same command buffer** as all the frame's compute (`submit(present_view)`). With an invalid canvas texture WebGPU rejects the whole buffer, so all GS drawing for that frame is lost, not just the presentation. That explains corrupt/flickering output (rather than plain black).
- `canPresent()` in the shell probed with a 64x64 canvas; that size does not reproduce the size-dependent failure.
- The copy path (`?copy`, `Module.presentCopy`, already used by Firefox) does not touch the WebGPU canvas: `gpu::display_async` reads the frame and `ps2web_blit` draws it into a 2D canvas.

### Hypotheses (not reproduced: no Linux/Vulkan machine available)
- H1 (main): Chromium allocates the SharedImage memory as linear w×h×4 and Dawn imports it as a VkImage whose tiling/alignment needs more (padding), so it fails depending on driver (Mesa/NVIDIA) and canvas size.
- H2: recycling of a SharedImage of the previous size after a canvas resize (fullscreen, window). Our `present()` is synchronous between `getCurrentTexture` and `submit`, so we do not use a stale texture on our side.
- That Firefox shows it too is odd (Firefox already forces copies); the reporter may have pasted the Chrome log. Unconfirmed.

### Fix (commit `7d83178` on the `outbreak` branch of `ps2-web-recomp/`)
Runtime/host (requires rebuilding the wasm):
- `gpu.cpp`: the canvas pass goes in its own command buffer (`submit_present`); compute is submitted before. If the canvas texture is invalid, only the presentation is lost.
- `gpu.cpp`: error scope (Validation) around `getCurrentTexture` + the canvas pass; counts consecutive failures (`present_failures()`, exposed via `gs`/`system`). Prints `[gpu] canvas present failed (n): …` the first 3 times.
- `host/web.cpp`: after 3 consecutive failures (or if the shell calls `_ps2web_present_copy`), the loop switches live to 2D copies (`surface=false, copy=true`), prints `[web] presenting through a 2D canvas copy (safe mode, …)` and notifies the shell (`Module.onPresentFallback`). Since the canvas already has a webgpu context, `ps2web_blit` draws into an overlaid 2D canvas (`pointer-events:none`; input stays on the original canvas).
- `CMakeLists.txt`: exports `_ps2web_present_copy`.

Shell (`web/shell.html`, compatible with the old wasm):
- Settings › Graphics › "Video presentation": Automatic (WebGPU) / Safe mode (copy frames). Stored in `localStorage['reo-present']='copy'` (in `SETTING_KEYS`). In safe mode `chooseGpu()` sets `Module.presentCopy = true`. Texts in es/en/pt/fr/de/it.
- Detector for old wasm builds: 3 `[gpu] …` lines matching `ImportMemory|invalid handle|Invalid Texture/TextureView/CommandBuffer|SharedImage` within 10 s while presenting via WebGPU → saves safe mode and, if `_ps2web_present_copy` exists, switches live; otherwise asks "Reload in safe mode" (if declined, it stays selected for next time).
- `canPresent()` probes with the real canvas size (or 640x480) instead of 64x64.
- Immediate user workaround, no deploy: open with `?copy` in the URL.

### Verification
- Done: `node --check` of the shell script OK; `-fsyntax-only` of `gpu.cpp`, `gs.cpp`, `system.cpp`, `host/web.cpp` with the web (emcc) build commands, and of `gpu.cpp`/`gs.cpp`/`system.cpp` with the native build, OK.
- **Not verified at runtime**: the wasm was not rebuilt and the failure was not reproduced. Missing: a local build, trying `Module._ps2web_present_copy()` in the console (the overlaid canvas should appear and the game continue), toggling the Settings option both ways, and confirmation from a Linux user.

### Deployment
- `shell.html` only: **no restart needed**, works with the current wasm (with an old wasm the change requires a page reload; the dialog offers it).
- `gpu.cpp`/`web.cpp`/`CMakeLists.txt`: require rebuilding and deploying the wasm (a "restart" category deploy).
