// Measures a web build while it plays a route (scripts/routes/*.txt): web_route.mjs plus a per-frame profile.
// The game starts without the name dialog (no API needed: serve.py without --api). At game frame PROFILE_AT it
// samples PROFILE_SECS seconds: fps, ms per frame, idle ms per frame of the emulation (EE), VU and render (GS, page)
// threads, GPU submits/dispatches/bytes written per frame (Module._ps2web_gpu_counter) and, unless NO_CPU_PROFILE=1,
// a CPU profile of every thread (self time per function, raw .cpuprofile files in <outdir>).
// THROTTLE=n slows the page's main thread n times (CDP Emulation.setCPUThrottlingRate; Chrome does not throttle
// workers): the render thread runs there, so this models a slow machine for the GS/WebGPU side.
// usage (Windows node): URL=http://localhost:8640/file2/?nonet PROFILE_AT=9200 [THROTTLE=4] [SCALE=1] [CDP_PORT=9341]
//   node web_perf.mjs <outdir> <route.txt> [extraFrames] [profileDir] [shotEvery]
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
const [outArg, routeArg, extraArg = '600', profArg = '', everyArg = '300'] = process.argv.slice(2);
const OUT = resolve(outArg); mkdirSync(OUT, { recursive: true });
const PROF = profArg ? resolve(profArg) : OUT + '/profile';
const URL = process.env.URL || 'http://localhost:8632/file2/?nonet';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const KEYS = { start: ['Enter', 'Enter', 13], cross: ['KeyJ', 'j', 74], circle: ['KeyK', 'k', 75], up: ['KeyW', 'w', 87],
  down: ['KeyS', 's', 83], left: ['KeyA', 'a', 65], right: ['KeyD', 'd', 68], square: ['ShiftLeft', 'Shift', 16], triangle: ['KeyI', 'i', 73] };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (...a) => { const s = ((Date.now() - t0) / 1e3).toFixed(1).padStart(7) + 's ' + a.join(' '); console.log(s); appendFileSync(OUT + '/run.log', s + '\n'); };

// route: frame:button[:held]; sync lines ignored (logged), 'frames N' sets the end
let end = 0; const presses = [];
for (const line of readFileSync(routeArg, 'utf8').split('\n')) {
  const l = line.replace(/#.*/, '').trim(); if (!l) continue;
  if (l.startsWith('frames')) { end = +l.split(/\s+/)[1]; continue; }
  const [f, b, h] = l.split(':'); if (b === 'sync') continue;
  presses.push({ frame: +f, buttons: b.split('+'), held: h ? +h : 6 });
}
end += +extraArg;

const proc = spawn(CHROME, ['--headless=new', '--remote-debugging-port=' + (process.env.CDP_PORT || 9341), `--user-data-dir=${PROF}`, '--enable-unsafe-webgpu', '--enable-dawn-features=allow_unsafe_apis',
  '--enable-features=Vulkan', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required', '--hide-scrollbars', '--window-size=1280,960', 'about:blank'], { stdio: 'ignore' });
let targets;
for (let i = 0; i < 150; i++) { try { targets = await (await fetch('http://127.0.0.1:' + (process.env.CDP_PORT || 9341) + '/json')).json(); break; } catch { await sleep(200); } }
const page = targets.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl); await new Promise((r) => (ws.onopen = r));
let id = 0; const wait = new Map(); const consoleLines = [];
const THROTTLE = +(process.env.THROTTLE || 1), PROFILE_AT = +(process.env.PROFILE_AT || 0), PROFILE_SECS = +(process.env.PROFILE_SECS || 15), PROFILE_CPU = !process.env.NO_CPU_PROFILE;
const sessions = [];
ws.onmessage = (m) => { const d = JSON.parse(m.data);
  if (d.id && wait.has(d.id)) { wait.get(d.id)(d); wait.delete(d.id); }
  if (d.method === 'Runtime.consoleAPICalled') { const t = d.params.args.map((a) => a.value ?? a.description ?? '').join(' ');
    consoleLines.push(t); if (/interpret|halt|error|abort|webgpu|adapter|\[mc\]|memcard|exception/i.test(t)) log('console:', t.slice(0, 200)); }
  if (d.method === 'Runtime.exceptionThrown') { const t = d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text; consoleLines.push('EXC ' + t); log('exception:', t.slice(0, 200)); } };
const cmd = (method, params = {}, sessionId) => new Promise((r) => { const i = ++id; wait.set(i, r); ws.send(JSON.stringify(sessionId ? { id: i, method, params, sessionId } : { id: i, method, params })); });
ws.addEventListener('message', (m) => { const d = JSON.parse(m.data);
  if (d.method === 'Target.attachedToTarget') { const sid = d.params.sessionId; sessions.push({ sid, type: d.params.targetInfo.type });
    cmd('Runtime.runIfWaitingForDebugger', {}, sid);
    if (THROTTLE > 1) cmd('Emulation.setCPUThrottlingRate', { rate: THROTTLE }, sid).then((r) => log('throttle', d.params.targetInfo.type, JSON.stringify(r.error || 'ok'))); } });
let profiled = false;
const snap = () => ev(`[Module._ps2web_frames(), Module._ps2web_idle_ms(0), Module._ps2web_idle_ms(1), Module._ps2web_idle_ms(2), Module._ps2web_gpu_ms(), performance.now(), ...(Module._ps2web_gpu_counter ? [0,1,2,3,4,5,6,7,8,9,10].map((i) => Module._ps2web_gpu_counter(i)) : [])]`);
const profileAll = async () => {
  const targets = [{ sid: undefined, type: 'page' }, ...sessions];
  for (const t of targets) { await cmd('Profiler.enable', {}, t.sid); await cmd('Profiler.setSamplingInterval', { interval: 500 }, t.sid); }
  const s0 = await snap();
  if (PROFILE_CPU) for (const t of targets) await cmd('Profiler.start', {}, t.sid);
  await sleep(PROFILE_SECS * 1000);
  const s1 = await snap();
  const span = s1[5] - s0[5], fr = s1[0] - s0[0];
  log(`PROFILE ${fr} frames in ${span.toFixed(0)} ms: ${(fr * 1000 / span).toFixed(1)} fps, ${(span / fr).toFixed(2)} ms/frame; idle per frame emu ${((s1[1] - s0[1]) / fr).toFixed(2)} gs ${((s1[2] - s0[2]) / fr).toFixed(2)} vu ${((s1[3] - s0[3]) / fr).toFixed(2)} ms; gpu ${((s1[4] - s0[4]) / fr).toFixed(2)} ms/frame`);
  if (s1.length > 6) { const names = ["submits", "upload", "copy_read", "copy_write", "load_clut", "display", "raster", "resolve", "snapshot", "decode", "KB written"]; log("GPU per frame: " + names.map((n, i) => n + " " + ((s1[6 + i] - s0[6 + i]) / fr / (i >= 10 ? 1024 : 1)).toFixed(1)).join(", ")); }
  if (!PROFILE_CPU) return;
  for (const t of targets) {
    const r = await cmd('Profiler.stop', {}, t.sid); const p = r.result && r.result.profile; if (!p) { log('no profile', t.type, JSON.stringify(r.error)); continue; }
    const byId = new Map(p.nodes.map((n) => [n.id, n])); const self = new Map(); let total = 0;
    for (let i = 0; i < p.samples.length; ++i) { const n = byId.get(p.samples[i]); const dt = p.timeDeltas[i] || 0; total += dt; const k = n.callFrame.functionName || '(anon ' + n.callFrame.url.split('/').pop() + ')'; self.set(k, (self.get(k) || 0) + dt); }
    const top = [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 40);
    log(`TARGET ${t.type} ${t.sid || ''} ${(total / 1000 / fr).toFixed(2)} ms/frame sampled\n` + top.map(([k, v]) => `   ${(v / 1000 / fr).toFixed(3).padStart(7)} ms/f ${(v * 100 / total).toFixed(1).padStart(5)}%  ${k.slice(0, 100)}`).join('\n'));
    writeFileSync(`${OUT}/profile-${t.type}-${(t.sid || "main").slice(0, 8)}.json`, JSON.stringify(Object.fromEntries(self))); writeFileSync(`${OUT}/raw-${t.type}-${(t.sid || "main").slice(0, 8)}.cpuprofile`, JSON.stringify(p));
  }
};
const ev = async (expr) => (await cmd('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
const shot = async (name) => { const r = await cmd('Page.captureScreenshot', { format: 'png' }); if (r.result) writeFileSync(`${OUT}/${name}.png`, Buffer.from(r.result.data, 'base64')); };
const key = (type, b) => { const [code, k, vk] = KEYS[b]; return cmd('Input.dispatchKeyEvent', { type, code, key: k, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk }); };
await cmd('Runtime.enable'); await cmd('Page.enable');
await cmd('Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: false, flatten: true });
if (THROTTLE > 1) log('throttle page', JSON.stringify((await cmd('Emulation.setCPUThrottlingRate', { rate: THROTTLE })).error || 'ok'));
await cmd('Page.navigate', { url: URL });
for (let i = 0; i < 240; i++) { const s = await ev('!!(window.Module && Module.initialized && Module.discSize)');
  if (s) { log('ready'); break; } await sleep(500); }
log('isolated', await ev('crossOriginIsolated'), 'gpu', await ev('!!navigator.gpu'));
log('card before:', await ev(`(document.getElementById('card-sum')?.textContent + ' | ' + document.getElementById('card-info')?.textContent).replace(/\\s+/g,' ')`));
await shot('premenu'); if (process.env.SCALE) await ev('window.SCALE_OVERRIDE = ' + JSON.stringify(process.env.SCALE));
if (process.env.ONLY_PREMENU) { proc.kill(); process.exit(0); }
await ev(`(async () => { document.body.classList.add('running'); document.getElementById('cover').hidden = true; Module.running = true; fitCanvas(); await startAudio(); Module.callMain(Module.arguments.concat(['--env', 'PS2_SCALE=' + (window.SCALE_OVERRIDE || scaleSelect.value)])); return scaleSelect.value; })()`).then((v) => log('scale', v));
const frames = async () => (await ev(`typeof Module._ps2web_frames === 'function' ? Module._ps2web_frames() : -1`)) ?? -1;
let fr = -1; for (let i = 0; i < 600 && fr < 1; i++) { fr = await frames(); await sleep(100); }
log('running, frame', fr);
const held = []; let pi = 0, nextShot = +everyArg, lastF = fr, lastT = Date.now(), fpsLog = [];
let stuck = 0;
while (fr < end) {
  fr = await frames();
  while (pi < presses.length && presses[pi].frame <= fr) { const p = presses[pi++]; for (const b of p.buttons) await key('keyDown', b); held.push({ until: fr + p.held, buttons: p.buttons }); }
  for (let i = held.length - 1; i >= 0; i--) if (held[i].until <= fr) { for (const b of held[i].buttons) await key('keyUp', b); held.splice(i, 1); }
  if (PROFILE_AT && !profiled && fr >= PROFILE_AT) { profiled = true; await profileAll(); }
  if (fr >= nextShot) { await shot('f' + String(nextShot).padStart(5, '0')); nextShot += +everyArg; }
  if (Date.now() - lastT >= 5000) { const fps = ((fr - lastF) * 1000 / (Date.now() - lastT)).toFixed(1); fpsLog.push(+fps);
    log('frame', fr, 'fps', fps, 'stats:', await ev(`document.getElementById('stats')?.textContent`), 'status:', await ev(`document.getElementById('status')?.textContent`));
    stuck = fr === lastF ? stuck + 1 : 0; if (stuck >= 6) { log('STUCK'); break; } lastF = fr; lastT = Date.now(); }
  await sleep(10);
}
await shot('end');
log('fps samples', fpsLog.join(' '));
if (process.env.EXTRA_JS) log('extra:', JSON.stringify(await ev(process.env.EXTRA_JS)));
writeFileSync(OUT + '/console.txt', consoleLines.join('\n'));
proc.kill(); process.exit(0);

