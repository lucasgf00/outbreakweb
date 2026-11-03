// loader.js: engine download with progress, the loading overlay over the canvas, Play while loading,
// GPU adapter retries and cleanup on reload. Loaded in <head> of shell.html, before anything else runs, so the
// engine (ps2web.wasm) starts downloading while the player is still in the pre-menu. shell.html only has three
// hooks: REOLoader.attach(Module), REOLoader.canQueue() (Play stays enabled while loading) and
// REOLoader.queue(start) (Play pressed before the engine is ready).
// File names: scripts/version_web.py gives ps2web.js/.wasm, loader.js/.css a content hash and writes the wasm name
// and size into <meta name=reo-engine> ("ps2web.<hash>.wasm <bytes>"); the hashed files are cached as immutable.
// Nothing here touches the disc image: it is read by the runtime as before.
(() => {
  'use strict';
  const T = {
    es: { dl: 'Descargando motor ({0}/{1} MB, {2} %)', dlN: 'Descargando motor ({0} MB)', compile: 'Compilando', gpu: 'Iniciando gráficos (WebGPU)',
          boot: 'Iniciando juego', wait: 'Cargando…', slow: 'Puede tardar la primera vez; no recargues.', err: 'No se pudo cargar el juego.',
          errNet: 'Se cortó la descarga del motor. Revisá la conexión.', errGpu: 'No se pudo iniciar la GPU (WebGPU). Cerrá otras pestañas con el juego y reintentá.',
          gpuLost: 'Se perdió la GPU (WebGPU). Reintentá para seguir jugando.', retry: 'Reintentar', save: 'Ahorro de datos activo: el motor ({0} MB) se descarga al apretar Jugar.' },
    en: { dl: 'Downloading engine ({0}/{1} MB, {2} %)', dlN: 'Downloading engine ({0} MB)', compile: 'Compiling', gpu: 'Starting graphics (WebGPU)',
          boot: 'Starting game', wait: 'Loading…', slow: 'The first time can take a while; please do not reload.', err: 'The game could not be loaded.',
          errNet: 'The engine download was interrupted. Check your connection.', errGpu: 'The GPU (WebGPU) could not be started. Close other tabs with the game and try again.',
          gpuLost: 'The GPU (WebGPU) was lost. Try again to keep playing.', retry: 'Try again', save: 'Data saver is on: the engine ({0} MB) downloads when you press Play.' },
    pt: { dl: 'Baixando o motor ({0}/{1} MB, {2} %)', dlN: 'Baixando o motor ({0} MB)', compile: 'Compilando', gpu: 'Iniciando gráficos (WebGPU)',
          boot: 'Iniciando o jogo', wait: 'Carregando…', slow: 'Na primeira vez pode demorar; não recarregue.', err: 'Não foi possível carregar o jogo.',
          errNet: 'O download do motor foi interrompido. Verifique a conexão.', errGpu: 'Não foi possível iniciar a GPU (WebGPU). Feche outras abas com o jogo e tente de novo.',
          gpuLost: 'A GPU (WebGPU) foi perdida. Tente de novo para continuar jogando.', retry: 'Tentar de novo', save: 'Economia de dados ativa: o motor ({0} MB) é baixado ao apertar Jogar.' },
    fr: { dl: 'Téléchargement du moteur ({0}/{1} Mo, {2} %)', dlN: 'Téléchargement du moteur ({0} Mo)', compile: 'Compilation', gpu: 'Démarrage des graphismes (WebGPU)',
          boot: 'Démarrage du jeu', wait: 'Chargement…', slow: 'La première fois peut être longue ; ne rechargez pas.', err: 'Le jeu n\'a pas pu être chargé.',
          errNet: 'Le téléchargement du moteur a été interrompu. Vérifiez la connexion.', errGpu: 'Le GPU (WebGPU) n\'a pas pu démarrer. Fermez les autres onglets du jeu et réessayez.',
          gpuLost: 'Le GPU (WebGPU) a été perdu. Réessayez pour continuer à jouer.', retry: 'Réessayer', save: 'Économiseur de données actif : le moteur ({0} Mo) se télécharge quand vous appuyez sur Jouer.' },
    de: { dl: 'Engine wird geladen ({0}/{1} MB, {2} %)', dlN: 'Engine wird geladen ({0} MB)', compile: 'Wird kompiliert', gpu: 'Grafik wird gestartet (WebGPU)',
          boot: 'Spiel wird gestartet', wait: 'Lädt…', slow: 'Beim ersten Mal kann es dauern; bitte nicht neu laden.', err: 'Das Spiel konnte nicht geladen werden.',
          errNet: 'Der Download der Engine wurde unterbrochen. Prüfe deine Verbindung.', errGpu: 'Die GPU (WebGPU) konnte nicht gestartet werden. Schließe andere Tabs mit dem Spiel und versuche es erneut.',
          gpuLost: 'Die GPU (WebGPU) ging verloren. Versuche es erneut, um weiterzuspielen.', retry: 'Erneut versuchen', save: 'Datensparmodus aktiv: Die Engine ({0} MB) wird beim Drücken von Spielen geladen.' },
    it: { dl: 'Download del motore ({0}/{1} MB, {2} %)', dlN: 'Download del motore ({0} MB)', compile: 'Compilazione', gpu: 'Avvio della grafica (WebGPU)',
          boot: 'Avvio del gioco', wait: 'Caricamento…', slow: 'La prima volta può volerci un po\'; non ricaricare.', err: 'Impossibile caricare il gioco.',
          errNet: 'Il download del motore si è interrotto. Controlla la connessione.', errGpu: 'Impossibile avviare la GPU (WebGPU). Chiudi le altre schede con il gioco e riprova.',
          gpuLost: 'La GPU (WebGPU) è andata persa. Riprova per continuare a giocare.', retry: 'Riprova', save: 'Risparmio dati attivo: il motore ({0} MB) si scarica quando premi Gioca.' },
  };
  const lang = () => { const l = (document.documentElement.lang || 'en').slice(0, 2); return l in T ? l : 'en'; };
  const t = (k, ...a) => (T[lang()][k] || T.en[k]).replace(/\{(\d)\}/g, (_, i) => a[i]);
  const mb = (n) => (n / 1048576).toLocaleString(lang(), { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const params = new URLSearchParams(location.search);
  const remember = (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} };
  const recall = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };

  const meta = document.querySelector('meta[name=reo-engine]');
  const [WASM, BYTES] = ((meta && meta.content) || 'ps2web.wasm 0').split(' ');
  const conn = navigator.connection || {};
  // Data saver: wait for Play before downloading ~25 MB (?preload forces the download).
  const deferred = !!conn.saveData && !params.has('preload');
  const slowNet = !!conn.saveData || /(^|-)2g|3g/.test(conn.effectiveType || '');
  // Seconds after which a stage shows the "first time can take a while" hint.
  const SLOW = { download: 20, compile: 10, gpu: 10, boot: 12, wait: 15 };

  const S = { stage: 'idle', since: performance.now(), got: 0, total: Number(BYTES) || 0, err: null, play: false, queued: null,
              runDevice: false, prims: 0, frames0: 0, leaving: false, gpuWarn: false, timings: {} };
  window.REOLoader = { state: () => Object.assign({ wasm: WASM }, S, { queued: !!S.queued }) };
  let Module = null;

  // ---- overlay (after Play) and the progress line on the pre-menu cover ----
  let ui = null;
  function build() {
    const stage = document.getElementById('stage'), cover = document.getElementById('pm-state');
    if (!stage || !cover) return null;
    const el = (tag, cls, parent) => { const e = document.createElement(tag); if (cls) e.className = cls; if (parent) parent.appendChild(e); return e; };
    const over = el('div', 'ldr', stage);
    over.id = 'ldr';
    over.setAttribute('role', 'status');
    over.setAttribute('aria-live', 'polite');
    const box = el('div', 'ldr-box', over);
    const text = el('p', 'ldr-stage', box), bar = el('div', 'ldr-bar', box), hint = el('p', 'ldr-hint', box);
    el('i', '', bar);
    const retry = el('button', 'primary ldr-retry', box);
    retry.type = 'button';
    retry.addEventListener('click', () => location.reload());
    const mini = el('div', 'ldr-mini');
    mini.id = 'ldr-mini';
    cover.after(mini);
    const mbar = el('div', 'ldr-bar', mini), mtext = el('span', 'ldr-mtext', mini), mretry = el('button', 'link ldr-retry', mini);
    el('i', '', mbar);
    mretry.type = 'button';
    mretry.addEventListener('click', () => location.reload());
    new MutationObserver(render).observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] });
    return { over, text, bar, hint, retry, mini, mbar, mtext, mretry };
  }
  function stageText() {
    if (S.err) return t(S.err);
    switch (S.stage) {
      case 'idle': return deferred ? t('save', mb(S.total)) : t('wait');
      case 'download': return S.total ? t('dl', mb(Math.min(S.got, S.total)), mb(S.total), Math.floor(Math.min(S.got, S.total) * 100 / S.total)) : t('dlN', mb(S.got));
      case 'compile': return t('compile');
      case 'gpu': case 'gpu2': return t('gpu');
      case 'boot': return t('boot');
      default: return t('wait');
    }
  }
  function render() {
    if (!ui) ui = build();
    if (!ui) return;
    const text = stageText(), pct = S.stage === 'download' && S.total ? Math.min(100, S.got * 100 / S.total) : -1;
    const long = (performance.now() - S.since) / 1000 > (SLOW[S.stage === 'gpu2' ? 'gpu' : S.stage] || 1e9);
    for (const [bar, show] of [[ui.bar, true], [ui.mbar, true]]) {
      bar.hidden = !show || !!S.err;
      bar.classList.toggle('busy', pct < 0);
      bar.firstChild.style.width = pct < 0 ? '' : pct.toFixed(1) + '%';
    }
    // overlay: from Play until the game has drawn something, or an error after Play
    const overlay = S.play && S.stage !== 'done' || (S.err && (S.play || (Module && Module.running)));
    ui.over.hidden = !overlay;
    ui.text.textContent = text;
    ui.hint.textContent = S.err ? '' : t('slow');
    ui.hint.hidden = !!S.err || !long;
    ui.retry.textContent = t('retry');
    ui.retry.hidden = !S.err;
    // pre-menu line: while the engine loads (or failed) and the player has not pressed Play
    const loading = ['idle', 'download', 'compile', 'gpu'].includes(S.stage) && !(S.stage === 'idle' && !deferred);
    ui.mini.hidden = overlay || !(loading || S.err || S.gpuWarn);
    ui.mini.classList.toggle("err", !!(S.err || S.gpuWarn));
    ui.mtext.textContent = S.err ? t(S.err) : S.gpuWarn ? t('errGpu') : text + (long ? ' · ' + t('slow') : '');
    ui.mbar.hidden = !!(S.err || S.gpuWarn) || (S.stage === 'idle' && deferred);
    ui.mretry.textContent = t('retry');
    ui.mretry.hidden = !(S.err || S.gpuWarn);
  }
  function setStage(s) {
    if (S.stage === s || S.err) return;
    S.timings[s] = Math.round(performance.now());
    S.stage = s;
    S.since = performance.now();
    render();
  }
  function fail(key, why) {
    if (S.leaving) return new Error(key);
    if (!S.err) {
      S.err = key;
      console.warn('[loader] ' + key + (why ? ': ' + why : ''));
      render();
    }
    return why instanceof Error ? why : new Error(key + (why ? ': ' + why : ''));
  }

  // ---- engine download: fetch + streaming compile, progress from the decompressed byte count ----
  let compiled = null, go = null;
  const goSignal = new Promise((r) => { go = r; });
  if (!deferred) go();
  function download() {
    if (compiled) return compiled;
    compiled = goSignal.then(async () => {
      setStage('download');
      let r;
      try { r = await fetch(WASM, { credentials: 'same-origin' }); } catch (e) { throw fail('errNet', e); }
      if (!r.ok || !r.body) throw fail(r.status === 404 ? 'err' : 'errNet', 'HTTP ' + r.status);
      if (!S.total && !r.headers.get('content-encoding')) S.total = Number(r.headers.get('content-length')) || 0;
      const [a, b] = r.body.tee();
      const module = (WebAssembly.compileStreaming
        ? WebAssembly.compileStreaming(new Response(a, { headers: { 'Content-Type': 'application/wasm' } }))
        : new Response(a).arrayBuffer().then((buf) => WebAssembly.compile(buf)));
      module.catch(() => {});
      const reader = b.getReader();
      let shown = 0;
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          S.got += value.length;
          if (S.got - shown > 262144) { shown = S.got; render(); }
        }
      } catch (e) { throw fail('errNet', e); }
      setStage('compile');
      try { return await module; } catch (e) { throw fail('err', e); }
    });
    compiled.catch(() => {});
    return compiled;
  }
  download();
  // The engine of the other File (hover or focus on its tab), only once this one is downloaded and not on slow links.
  const prefetched = new Set();
  function prefetchOther(href) {
    if (slowNet || prefetched.has(href) || !['gpu', 'ready', 'gpu2', 'boot', 'wait', 'done'].includes(S.stage)) return;
    prefetched.add(href);
    fetch(new URL('engine.json', href), { cache: 'no-cache' }).then((r) => r.json()).then((m) => {
      for (const f of [m.js, m.wasm]) fetch(new URL(f, href), { credentials: 'same-origin', priority: 'low' })
        .then((r) => (r.ok && r.body ? r.body.pipeTo(new WritableStream()) : null)).catch(() => {});
    }).catch(() => {});
  }
  for (const type of ['pointerover', 'focusin', 'touchstart']) document.addEventListener(type, (e) => {
    const a = e.target && e.target.closest && e.target.closest('#games a[href]');
    if (a && !a.hasAttribute('aria-current')) prefetchOther(a.href);
  }, { passive: true });

  // ---- GPU: retry a missing adapter (it happens right after a reload), watch for a lost device ----
  const devices = new Set(), audios = new Set();
  if (window.GPU && GPU.prototype.requestAdapter) {
    const requestAdapter = GPU.prototype.requestAdapter;
    GPU.prototype.requestAdapter = async function (options) {
      let adapter = await requestAdapter.call(this, options);
      for (let i = 1; !adapter && i <= 3 && !S.leaving; ++i) {
        await sleep(400 * i);
        adapter = await requestAdapter.call(this, options);
      }
      if (adapter) remember('reo-gpu-ok', '1');
      else if (recall('reo-gpu-ok') === '1') {   // this browser had WebGPU before: most likely a stuck GPU process
        S.gpuWarn = true;
        if (Module && Module.running) fail('errGpu', 'no adapter');
        render();
      }
      return adapter;
    };
  }
  if (window.GPUAdapter && GPUAdapter.prototype.requestDevice) {
    const requestDevice = GPUAdapter.prototype.requestDevice;
    GPUAdapter.prototype.requestDevice = async function (descriptor) {
      let device;
      try { device = await requestDevice.call(this, descriptor); } catch (e) {
        if (Module && Module.running) fail('errGpu', e);
        throw e;
      }
      devices.add(device);
      const forGame = !!(Module && Module.running);
      if (forGame) { S.runDevice = true; setStage('boot'); }
      device.lost.then((info) => {
        devices.delete(device);
        if (forGame && info.reason !== 'destroyed') fail('gpuLost', info.message);
      });
      return device;
    };
  }
  if (window.AudioContext) {
    const Base = window.AudioContext;
    window.AudioContext = class extends Base {
      constructor(...args) {
        super(...args);
        audios.add(this);
      }
    };
  }
  // Release the GPU device and the audio output when the page goes away, so a reload starts clean.
  addEventListener('pagehide', () => {
    S.leaving = true;
    for (const d of devices) try { d.destroy(); } catch (e) {}
    for (const a of audios) try { a.close(); } catch (e) {}
  });
  addEventListener('pageshow', (e) => { if (e.persisted && S.leaving) location.reload(); });

  // ---- shell.html hooks ----
  REOLoader.attach = (M) => {
    Module = M;
    M.instantiateWasm = (imports, done) => {
      download().then((module) => WebAssembly.instantiate(module, imports).then((instance) => {
        setStage('gpu');
        done(instance, module);
      })).catch((e) => fail(S.err || 'err', e));
      return {};
    };
    const locate = M.locateFile;
    M.locateFile = (path, dir) => (path === 'ps2web.wasm' ? (dir || '') + WASM : locate ? locate(path, dir) : (dir || '') + path);
    const printErr = M.printErr;
    M.printErr = (text) => {
      if (printErr) printErr(text);
      if (M.running && /^\[gpu\] (no adapter|device:)/.test(text)) fail('errGpu', text);
    };
    const onAbort = M.onAbort;
    M.onAbort = (what) => {
      fail('err', what);
      if (onAbort) onAbort(what);
    };
    render();
  };
  REOLoader.canQueue = () => !S.err && !(document.getElementById('pm-state') || {}).dataset?.state?.startsWith('err');
  // Play before the engine is ready: show the overlay, start the download if it waited (data saver) and
  // press Play again by itself when the page is ready.
  REOLoader.queue = (start) => {
    S.queued = start;
    S.play = true;
    go();
    render();
  };
  // Has the game drawn something? The frame counter alone is not enough (the first frames are black while the
  // game loads its files), so wait for primitives drawn by the GS after the first frames.
  const CONTENT_PRIMS = 200;
  setInterval(() => {
    if (!Module) return;
    const shellError = (document.getElementById('pm-state') || {}).dataset?.state === 'error';
    if (S.queued && Module.initialized && Module.discSize) {
      const start = S.queued;
      S.queued = null;
      Promise.resolve(start()).finally(() => { if (!Module.running) { S.play = false; render(); } });
    } else if (S.queued && shellError) {
      S.queued = null;
      fail('err', 'page error');
    }
    if (Module.initialized && S.stage === 'gpu') setStage('ready');
    if (Module.running) {
      S.play = true;
      if (S.stage === 'ready' || S.stage === 'gpu') setStage(S.runDevice ? 'boot' : 'gpu2');
      const frames = Module._ps2web_frames ? Module._ps2web_frames() : 0;
      if (frames > 0 && (S.stage === 'gpu2' || S.stage === 'boot')) {
        S.prims = Module._ps2web_primitives ? Module._ps2web_primitives() : 0;
        setStage('wait');
      }
      if (S.stage === 'wait' && Module._ps2web_primitives && Module._ps2web_primitives() - S.prims > CONTENT_PRIMS) setStage('done');
    }
    if (S.stage !== 'done' || S.err) render();
  }, 250);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', render);
  else render();
})();
