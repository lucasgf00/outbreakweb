// Plays a route (scripts/routes/*.txt) in the web build with real key events (the pre-menu default keys),
// timed by game frames (Module._ps2web_frames). Windows Chrome headless + CDP, WebGPU. Sync lines are ignored.
// usage (Windows node): URL=http://localhost:8632/file2/?nonet node web_route.mjs <outdir> <route.txt> [extraFrames] [profileDir] [shotEvery]
// ONLY_PREMENU=1 only loads the page and prints the memory card state (use the same profileDir to check persistence).
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

const proc = spawn(CHROME, ['--headless=new', '--remote-debugging-port=9341', `--user-data-dir=${PROF}`, '--enable-unsafe-webgpu',
  '--enable-features=Vulkan', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required', '--hide-scrollbars', '--window-size=1280,960', 'about:blank'], { stdio: 'ignore' });
let targets;
for (let i = 0; i < 150; i++) { try { targets = await (await fetch('http://127.0.0.1:9341/json')).json(); break; } catch { await sleep(200); } }
const page = targets.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl); await new Promise((r) => (ws.onopen = r));
let id = 0; const wait = new Map(); const consoleLines = [];
ws.onmessage = (m) => { const d = JSON.parse(m.data);
  if (d.id && wait.has(d.id)) { wait.get(d.id)(d); wait.delete(d.id); }
  if (d.method === 'Runtime.consoleAPICalled') { const t = d.params.args.map((a) => a.value ?? a.description ?? '').join(' ');
    consoleLines.push(t); if (/interpret|halt|error|abort|webgpu|adapter|\[mc\]|memcard|exception/i.test(t)) log('console:', t.slice(0, 200)); }
  if (d.method === 'Runtime.exceptionThrown') { const t = d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text; consoleLines.push('EXC ' + t); log('exception:', t.slice(0, 200)); } };
const cmd = (method, params = {}) => new Promise((r) => { const i = ++id; wait.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => (await cmd('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
const shot = async (name) => { const r = await cmd('Page.captureScreenshot', { format: 'png' }); if (r.result) writeFileSync(`${OUT}/${name}.png`, Buffer.from(r.result.data, 'base64')); };
const key = (type, b) => { const [code, k, vk] = KEYS[b]; return cmd('Input.dispatchKeyEvent', { type, code, key: k, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk }); };
await cmd('Runtime.enable'); await cmd('Page.enable');
await cmd('Page.navigate', { url: URL });
for (let i = 0; i < 600; i++) { const s = await ev(`(() => { const s = document.getElementById('start'); return s ? (s.disabled ? 'wait' : s.textContent) : 'none'; })()`);
  if (s && s !== 'wait' && s !== 'none') { log('start button:', s); break; } await sleep(500); }
log('isolated', await ev('crossOriginIsolated'), 'gpu', await ev('!!navigator.gpu'));
log('card before:', await ev(`(document.getElementById('card-sum')?.textContent + ' | ' + document.getElementById('card-info')?.textContent).replace(/\\s+/g,' ')`));
await shot('premenu');
if (process.env.ONLY_PREMENU) { proc.kill(); process.exit(0); }
const r = await ev(`(() => { const r = document.getElementById('start').getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; })()`);
for (const type of ['mousePressed', 'mouseReleased']) await cmd('Input.dispatchMouseEvent', { type, x: r[0], y: r[1], button: 'left', clickCount: 1 });
const frames = async () => (await ev(`typeof Module._ps2web_frames === 'function' ? Module._ps2web_frames() : -1`)) ?? -1;
let fr = -1; for (let i = 0; i < 600 && fr < 1; i++) { fr = await frames(); await sleep(100); }
log('running, frame', fr);
const held = []; let pi = 0, nextShot = +everyArg, lastF = fr, lastT = Date.now(), fpsLog = [];
let stuck = 0;
while (fr < end) {
  fr = await frames();
  while (pi < presses.length && presses[pi].frame <= fr) { const p = presses[pi++]; for (const b of p.buttons) await key('keyDown', b); held.push({ until: fr + p.held, buttons: p.buttons }); }
  for (let i = held.length - 1; i >= 0; i--) if (held[i].until <= fr) { for (const b of held[i].buttons) await key('keyUp', b); held.splice(i, 1); }
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

