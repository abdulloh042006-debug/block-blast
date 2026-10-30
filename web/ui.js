/* Gridbloom UI: canvas rendering, drag, effects, sound, haptics, storage. Rules live in engine.js only. */
(function () {
  'use strict';
  var GB = window.GB;
  var SIZE = GB.SIZE, SHAPES = GB.SHAPES;

  // ---------- storage (every access guarded: private mode / blocked storage must not break play) ----------
  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } },
    del: function (k) { try { localStorage.removeItem(k); } catch (e) { /* ignore */ } }
  };

  // ---------- skins (own palettes) ----------
  var SKINS = [
    { id: 'bloom', name: 'Bloom', bg1: '#222a52', bg2: '#0f1228', board: '#171b38', empty: '#222850', text: '#eef1ff', dim: '#9aa3d6', accent: '#ffd43b', panel: '#1b2042',
      blocks: ['#ff6b8b', '#ffa14a', '#ffd43b', '#5fd97a', '#44d1e6', '#6b8cff', '#b57cff'] },
    { id: 'dusk', name: 'Dusk', bg1: '#2b1b3a', bg2: '#140c1d', board: '#1d1229', empty: '#2b1c3d', text: '#fbeeff', dim: '#b9a0cc', accent: '#ff9ecb', panel: '#24162f',
      blocks: ['#ff7aa8', '#ff9a5c', '#ffd166', '#7be0a1', '#5cc8ff', '#8e8bff', '#d38cff'] },
    { id: 'mint', name: 'Mint', bg1: '#e9f8f1', bg2: '#cfeee0', board: '#ffffff', empty: '#e4f3ec', text: '#173b2f', dim: '#4f7a6a', accent: '#d4572a', panel: '#f6fffa',
      blocks: ['#e8506f', '#f0883a', '#e6b800', '#2fb86b', '#1ba7c4', '#4b6fe0', '#8d5be0'] },
    { id: 'slate', name: 'Slate', bg1: '#2a2f36', bg2: '#15181c', board: '#1c2025', empty: '#272c33', text: '#eef2f6', dim: '#98a3b0', accent: '#7be0d4', panel: '#21262c',
      blocks: ['#e07a7a', '#e0a37a', '#e0d27a', '#8fd18f', '#7ac7e0', '#8a9be0', '#b88ae0'] }
  ];

  var settings = { sound: true, haptics: true, skin: 'bloom' };
  (function loadSettings() {
    var raw = store.get('gb.settings');
    if (!raw) return;
    try {
      var s = JSON.parse(raw);
      if (typeof s.sound === 'boolean') settings.sound = s.sound;
      if (typeof s.haptics === 'boolean') settings.haptics = s.haptics;
      if (SKINS.some(function (k) { return k.id === s.skin; })) settings.skin = s.skin;
    } catch (e) { /* use defaults */ }
  })();
  function saveSettings() { store.set('gb.settings', JSON.stringify(settings)); }
  function skin() { for (var i = 0; i < SKINS.length; i++) if (SKINS[i].id === settings.skin) return SKINS[i]; return SKINS[0]; }

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---------- sound (WebAudio synth, no files) ----------
  var audio = { ctx: null };
  function ensureAudio() {
    if (audio.ctx || !settings.sound) return;
    try {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (AC) audio.ctx = new AC();
    } catch (e) { audio.ctx = null; }
  }
  function tone(freq, start, dur, type, vol) {
    var ctx = audio.ctx;
    if (!ctx || !settings.sound) return;
    try {
      var t0 = ctx.currentTime + start;
      var o = ctx.createOscillator(), g = ctx.createGain();
      o.type = type || 'sine';
      o.frequency.setValueAtTime(freq, t0);
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(vol || 0.12, t0 + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      o.connect(g); g.connect(ctx.destination);
      o.start(t0); o.stop(t0 + dur + 0.03);
    } catch (e) { /* ignore */ }
  }
  var NOTES = [523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.5];
  var sfx = {
    pick: function () { tone(440, 0, 0.06, 'triangle', 0.06); },
    place: function (size) { tone(220 + size * 18, 0, 0.09, 'triangle', 0.14); },
    deny: function () { tone(150, 0, 0.12, 'sawtooth', 0.06); },
    clear: function (lines, streak) {
      var n = Math.min(NOTES.length - 1, lines + Math.min(streak, 3));
      for (var i = 0; i <= Math.min(n, 5); i++) tone(NOTES[i], i * 0.055, 0.2, 'sine', 0.13);
    },
    allClear: function () { for (var i = 0; i < NOTES.length; i++) tone(NOTES[i], i * 0.06, 0.28, 'triangle', 0.13); },
    over: function () { [392, 330, 262, 196].forEach(function (f, i) { tone(f, i * 0.16, 0.3, 'sine', 0.13); }); },
    rescue: function () { [262, 330, 392, 523].forEach(function (f, i) { tone(f, i * 0.07, 0.18, 'triangle', 0.12); }); }
  };
  function buzz(p) { if (settings.haptics && navigator.vibrate) { try { navigator.vibrate(p); } catch (e) { /* ignore */ } } }

  // ---------- state ----------
  var best = parseInt(store.get('gb.best') || '0', 10) || 0;
  var game = GB.Game.restore(store.get('gb.save'));
  if (!game || game.over) game = new GB.Game();
  var startBest = best;

  function persist() {
    if (game.over) store.del('gb.save'); else store.set('gb.save', game.serialize());
  }

  // ---------- dom ----------
  var $ = function (id) { return document.getElementById(id); };
  var canvas = $('stage'), ctx = canvas.getContext('2d');
  var elScore = $('score'), elBest = $('best'), elLive = $('live');
  var dlgOver = $('over'), dlgSet = $('settings');

  // ---------- layout ----------
  var L = { w: 0, h: 0, dpr: 1, cell: 40, bx: 0, by: 0, bsize: 320, tcell: 20, slots: [] };
  function layout() {
    var r = canvas.getBoundingClientRect();
    L.dpr = Math.min(window.devicePixelRatio || 1, 3);
    L.w = Math.max(240, r.width); L.h = Math.max(300, r.height);
    canvas.width = Math.round(L.w * L.dpr); canvas.height = Math.round(L.h * L.dpr);
    var pad = Math.max(10, Math.min(20, L.w * 0.04)), i;
    L.slots = [];
    if (L.w > L.h * 1.25) {
      // landscape: board on the left, tray stacked on the right
      var bs2 = Math.min(L.h - pad * 1.4, L.w * 0.56);
      L.cell = Math.max(18, Math.floor(bs2 / SIZE)); L.bsize = L.cell * SIZE;
      var trayW = Math.max(L.cell * 4.2, Math.min(L.w * 0.3, L.cell * 6));
      var total = L.bsize + pad * 2 + trayW, left = Math.max(pad, (L.w - total) / 2);
      L.bx = Math.round(left); L.by = Math.round((L.h - L.bsize) / 2);
      var slotH = L.bsize / 3, tx = L.bx + L.bsize + pad * 2;
      L.tcell = Math.max(10, Math.min(L.cell * 0.55, trayW / 5.4, slotH / 5.4));
      for (i = 0; i < 3; i++) L.slots.push({ x: tx, y: L.by + slotH * i, w: trayW, h: slotH, cx: tx + trayW / 2, cy: L.by + slotH * (i + 0.5) });
    } else {
      // portrait: board on top, tray right under it, the pair centred vertically
      var gap = pad * 0.9;
      var trayMin = Math.max(88, L.w * 0.27);
      var bs = Math.min(L.w - pad * 2, L.h - trayMin - gap - pad);
      L.cell = Math.max(18, Math.floor(bs / SIZE)); L.bsize = L.cell * SIZE;
      var slotW = (L.w - pad * 2) / 3;
      L.tcell = Math.max(10, Math.min(L.cell * 0.66, slotW / 5.4));
      var trayH = Math.min(L.h - L.bsize - gap - pad, Math.max(L.tcell * 5.6, L.cell * 3.8));
      var group = L.bsize + gap + trayH;
      L.bx = Math.round((L.w - L.bsize) / 2);
      L.by = Math.round(Math.max(pad * 0.6, (L.h - group) * 0.38));
      var trayTop = L.by + L.bsize + gap;
      for (i = 0; i < 3; i++) L.slots.push({ x: pad + slotW * i, y: trayTop, w: slotW, h: trayH, cx: pad + slotW * (i + 0.5), cy: trayTop + trayH / 2 });
    }
    draw();
  }

  // ---------- colour helpers ----------
  function shade(hex, amt) {
    var n = parseInt(hex.slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
    function f(v) { return Math.max(0, Math.min(255, Math.round(amt >= 0 ? v + (255 - v) * amt : v * (1 + amt)))); }
    return 'rgb(' + f(r) + ',' + f(g) + ',' + f(b) + ')';
  }
  function rr(c, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
  }
  function drawBlock(c, x, y, s, color, alpha, scale) {
    var sc = scale === undefined ? 1 : scale;
    if (sc <= 0.01 || alpha <= 0) return;
    var m = s * (1 - sc) / 2, g = Math.max(1, s * 0.05);
    var bx = x + g + m, by = y + g + m, bw = (s - g * 2) * sc, bh = bw;
    c.globalAlpha = alpha;
    c.fillStyle = shade(color, -0.28); rr(c, bx, by + bh * 0.08, bw, bh, bw * 0.24); c.fill();
    c.fillStyle = color; rr(c, bx, by, bw, bh * 0.94, bw * 0.24); c.fill();
    var gr = c.createLinearGradient(0, by, 0, by + bh);
    gr.addColorStop(0, 'rgba(255,255,255,0.38)'); gr.addColorStop(0.5, 'rgba(255,255,255,0)');
    c.fillStyle = gr; rr(c, bx, by, bw, bh * 0.94, bw * 0.24); c.fill();
    c.globalAlpha = 1;
  }
  function blockColor(id) { var sk = skin(); return sk.blocks[(id - 1) % sk.blocks.length]; }

  // ---------- effects ----------
  var fx = { pops: [], clears: [], parts: [], floats: [], toasts: [], trayIn: [0, 0, 0], shake: 0, shakeT: 0 };
  var now = function () { return performance.now(); };
  var shownScore = game.score;
  var overTimer = 0;

  function spawnClearFx(res, cx, cy) {
    var t = now();
    res.cleared.forEach(function (e) {
      var i = e[0], r = Math.floor(i / SIZE), c = i % SIZE;
      var d = Math.abs(r - cy) + Math.abs(c - cx);
      fx.clears.push({ r: r, c: c, color: e[1], t0: t + d * 18, dur: 300 });
      var n = reduceMotion ? 0 : 3;
      for (var k = 0; k < n; k++) {
        var a = Math.random() * Math.PI * 2, sp = 40 + Math.random() * 110;
        fx.parts.push({ x: L.bx + (c + 0.5) * L.cell, y: L.by + (r + 0.5) * L.cell, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 40, t0: t + d * 18, dur: 520, color: e[1], s: L.cell * (0.1 + Math.random() * 0.1) });
      }
    });
  }
  function addFloat(text, x, y, color) { fx.floats.push({ text: text, x: x, y: y, t0: now(), dur: 900, color: color }); }
  function addToast(text, color) { fx.toasts.push({ text: text, t0: now(), dur: 1100, color: color }); }
  function shake(amount) { if (reduceMotion) return; fx.shake = amount; fx.shakeT = now(); }

  // ---------- drag ----------
  var drag = null; // {i, px, py, type, grabDX}
  function pieceBox(shape, cellSize) { return { w: shape.w * cellSize, h: shape.h * cellSize }; }
  function dragOrigin() {
    var shape = SHAPES[game.tray[drag.i]], b = pieceBox(shape, L.cell);
    var lift = drag.type === 'touch' ? L.cell * 1.7 : L.cell * 0.4;
    return { x: drag.px - b.w / 2, y: drag.py - b.h / 2 - lift, shape: shape };
  }
  // Find the legal anchor nearest to where the piece actually is; null if none within ~1 cell.
  function snapTarget() {
    var o = dragOrigin(), fc = (o.x - L.bx) / L.cell, fr = (o.y - L.by) / L.cell;
    var best = null, bd = 1e9;
    for (var dr = -1; dr <= 1; dr++) for (var dc = -1; dc <= 1; dc++) {
      var r = Math.round(fr) + dr, c = Math.round(fc) + dc;
      if (!GB.canPlace(game.board, o.shape, r, c)) continue;
      var d = Math.hypot(r - fr, c - fc);
      if (d < bd) { bd = d; best = { r: r, c: c }; }
    }
    return bd <= 0.95 ? best : null;
  }
  function pos(e) { var r = canvas.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }

  canvas.addEventListener('pointerdown', function (e) {
    if (drag || game.over || dlgOpen()) return;
    var p = pos(e);
    for (var i = 0; i < 3; i++) {
      var s = L.slots[i];
      if (game.tray[i] && p.x >= s.x && p.x <= s.x + s.w && p.y >= s.y - L.cell * 0.4 && p.y <= s.y + s.h + L.cell * 0.4) {
        ensureAudio();
        drag = { i: i, px: p.x, py: p.y, type: e.pointerType === 'mouse' ? 'mouse' : 'touch' };
        try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
        sfx.pick(); buzz(8); requestDraw(); e.preventDefault();
        return;
      }
    }
  });
  canvas.addEventListener('pointermove', function (e) { if (!drag) return; var p = pos(e); drag.px = p.x; drag.py = p.y; requestDraw(); e.preventDefault(); });
  function endDrag(e, cancelled) {
    if (!drag) return;
    var p = pos(e); drag.px = p.x; drag.py = p.y;
    var tgt = cancelled ? null : snapTarget(), i = drag.i;
    drag = null;
    if (tgt) place(i, tgt.r, tgt.c); else { sfx.deny(); }
    requestDraw();
  }
  canvas.addEventListener('pointerup', function (e) { endDrag(e, false); });
  canvas.addEventListener('pointercancel', function (e) { endDrag(e, true); });

  // ---------- one move ----------
  function place(i, r, c) {
    var res = game.submit(i, r, c);
    if (!res.ok) { sfx.deny(); return; }
    var t = now(), sh = res.shape;
    sh.cells.forEach(function (q) { fx.pops.push({ r: r + q[0], c: c + q[1], t0: t, dur: 180 }); });
    sfx.place(sh.size);
    if (res.lines > 0) {
      spawnClearFx(res, c + sh.w / 2, r + sh.h / 2);
      if (res.allClear) sfx.allClear(); else sfx.clear(res.lines, game.streak);
      buzz(res.lines > 1 ? [18, 30, 18] : 16);
      var gained = res.score.total;
      addFloat('+' + gained, L.bx + L.bsize / 2, L.by + L.bsize / 2, skin().accent);
      if (res.allClear) { addToast('ALL CLEAR +' + GB.ALL_CLEAR_BONUS, skin().accent); shake(10); }
      else if (res.score.streak >= 2) addToast('Combo x' + res.score.streak, skin().accent);
      else if (res.lines >= 3) addToast(res.lines >= 4 ? 'Incredible!' : 'Great!', skin().accent);
      if (res.lines >= 3) shake(6);
    }
    if (res.newTray) { var tt = now(); fx.trayIn = [tt, tt + 70, tt + 140]; }
    if (game.score > best) { best = game.score; store.set('gb.best', String(best)); }
    elLive.textContent = 'Score ' + game.score;
    persist();
    if (res.over) {
      clearTimeout(overTimer);
      overTimer = setTimeout(showOver, res.lines > 0 ? 650 : 350);
    }
    requestDraw();
  }

  // ---------- drawing ----------
  var needDraw = false, rafId = 0;
  function requestDraw() { if (!rafId) rafId = requestAnimationFrame(frame); }
  function easeOut(t) { return 1 - Math.pow(1 - t, 3); }

  function frame() {
    rafId = 0;
    var busy = draw();
    if (busy) requestDraw();
  }

  function draw() {
    var sk = skin(), c = ctx, t = now(), busy = false;
    c.setTransform(L.dpr, 0, 0, L.dpr, 0, 0);
    c.clearRect(0, 0, L.w, L.h);

    // screen shake
    var sx = 0, sy = 0;
    if (fx.shake > 0) {
      var k = (t - fx.shakeT) / 260;
      if (k < 1) { var a = fx.shake * (1 - k); sx = (Math.random() - 0.5) * a; sy = (Math.random() - 0.5) * a; busy = true; } else fx.shake = 0;
    }
    c.save(); c.translate(sx, sy);

    // board panel
    c.fillStyle = sk.board; rr(c, L.bx - 6, L.by - 6, L.bsize + 12, L.bsize + 12, 14); c.fill();
    var tgt = drag ? snapTarget() : null, o = drag ? dragOrigin() : null;
    var prev = null;
    if (tgt) prev = GB.previewClears(game.board, o.shape, tgt.r, tgt.c);
    var hot = {};
    if (prev) { prev.rows.forEach(function (r) { for (var cc = 0; cc < SIZE; cc++) hot[r * SIZE + cc] = 1; }); prev.cols.forEach(function (cc) { for (var r = 0; r < SIZE; r++) hot[r * SIZE + cc] = 1; }); }
    var ghost = {};
    if (tgt) o.shape.cells.forEach(function (q) { ghost[(tgt.r + q[0]) * SIZE + (tgt.c + q[1])] = 1; });

    var popMap = {};
    fx.pops = fx.pops.filter(function (p) { var k = (t - p.t0) / p.dur; if (k >= 1) return false; popMap[p.r * SIZE + p.c] = k; busy = true; return true; });

    for (var r = 0; r < SIZE; r++) for (var cc = 0; cc < SIZE; cc++) {
      var i = r * SIZE + cc, x = L.bx + cc * L.cell, y = L.by + r * L.cell;
      c.fillStyle = sk.empty; rr(c, x + 1.5, y + 1.5, L.cell - 3, L.cell - 3, L.cell * 0.2); c.fill();
      var v = game.board[i];
      if (v) {
        var sc = 1;
        if (popMap[i] !== undefined) sc = 1 + 0.18 * Math.sin(Math.PI * popMap[i]);
        drawBlock(c, x, y, L.cell, blockColor(v), 1, sc);
        if (hot[i]) { c.globalAlpha = 0.35 + 0.15 * Math.sin(t / 90); c.fillStyle = '#fff'; rr(c, x + 2, y + 2, L.cell - 4, L.cell - 4, L.cell * 0.2); c.fill(); c.globalAlpha = 1; busy = true; }
      } else if (ghost[i]) {
        drawBlock(c, x, y, L.cell, blockColor(o.shape.color), 0.45, 1);
      }
    }
    if (prev) { // lines that would clear: tint empty-but-ghost cells too
      c.globalAlpha = 0.18; c.fillStyle = sk.accent;
      Object.keys(hot).forEach(function (k2) { var q = +k2; if (!game.board[q]) { c.fillRect(L.bx + (q % SIZE) * L.cell + 2, L.by + Math.floor(q / SIZE) * L.cell + 2, L.cell - 4, L.cell - 4); } });
      c.globalAlpha = 1;
    }

    // clearing cells (already gone from the board, drawn shrinking)
    fx.clears = fx.clears.filter(function (e) {
      var k = (t - e.t0) / e.dur; if (k >= 1) return false; busy = true;
      if (k < 0) { drawBlock(c, L.bx + e.c * L.cell, L.by + e.r * L.cell, L.cell, blockColor(e.color), 1, 1); return true; }
      var f = easeOut(k);
      c.globalAlpha = 1 - f; c.fillStyle = '#fff';
      var s = L.cell * (1 - f * 0.4); rr(c, L.bx + e.c * L.cell + (L.cell - s) / 2, L.by + e.r * L.cell + (L.cell - s) / 2, s, s, s * 0.22); c.fill(); c.globalAlpha = 1;
      drawBlock(c, L.bx + e.c * L.cell, L.by + e.r * L.cell, L.cell, blockColor(e.color), 1 - f, 1 - f * 0.6);
      return true;
    });

    // particles
    fx.parts = fx.parts.filter(function (p) {
      var k = (t - p.t0) / p.dur; if (k >= 1) return false; busy = true; if (k < 0) return true;
      var tt = k * p.dur / 1000, x = p.x + p.vx * tt, y = p.y + p.vy * tt + 260 * tt * tt;
      c.globalAlpha = 1 - k; c.fillStyle = blockColor(p.color); c.fillRect(x - p.s / 2, y - p.s / 2, p.s, p.s); c.globalAlpha = 1;
      return true;
    });

    // tray
    for (var s = 0; s < 3; s++) {
      var id = game.tray[s]; if (!id) continue;
      if (drag && drag.i === s) continue;
      var sh = SHAPES[id], slot = L.slots[s];
      var inK = Math.max(0, Math.min(1, (t - fx.trayIn[s]) / 260)); if (inK < 1) busy = true;
      var playable = GB.canPlaceAnywhere(game.board, sh);
      var tc = L.tcell, bw = sh.w * tc, bh = sh.h * tc;
      var ox = slot.cx - bw / 2, oy = slot.cy - bh / 2 + (1 - easeOut(inK)) * 70;
      sh.cells.forEach(function (q) { drawBlock(c, ox + q[1] * tc, oy + q[0] * tc, tc, blockColor(sh.color), (playable ? 1 : 0.32) * inK, 1); });
    }

    // dragged piece
    if (drag) {
      var od = dragOrigin();
      od.shape.cells.forEach(function (q) { drawBlock(c, od.x + q[1] * L.cell, od.y + q[0] * L.cell, L.cell, blockColor(od.shape.color), 0.96, 1.04); });
    }

    // floating score
    fx.floats = fx.floats.filter(function (f) {
      var k = (t - f.t0) / f.dur; if (k >= 1) return false; busy = true;
      c.globalAlpha = 1 - k * k; c.fillStyle = f.color; c.font = '800 ' + Math.round(L.cell * 0.75) + 'px system-ui, sans-serif';
      c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(f.text, f.x, f.y - easeOut(k) * L.cell * 1.6); c.globalAlpha = 1; return true;
    });
    // toasts
    fx.toasts = fx.toasts.filter(function (f) {
      var k = (t - f.t0) / f.dur; if (k >= 1) return false; busy = true;
      var sc = 0.7 + 0.5 * easeOut(Math.min(1, k * 4)), a = k < 0.7 ? 1 : 1 - (k - 0.7) / 0.3;
      c.save(); c.globalAlpha = a; c.translate(L.bx + L.bsize / 2, L.by + L.bsize * 0.32 - k * 12); c.scale(sc, sc);
      c.font = '900 ' + Math.round(L.cell * 1.05) + 'px system-ui, sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
      c.lineWidth = Math.max(3, L.cell * 0.14); c.strokeStyle = 'rgba(0,0,0,0.45)'; c.lineJoin = 'round'; c.strokeText(f.text, 0, 0);
      c.fillStyle = f.color; c.fillText(f.text, 0, 0); c.restore(); return true;
    });

    // game over dim
    if (game.over) { c.fillStyle = 'rgba(0,0,0,0.35)'; rr(c, L.bx - 6, L.by - 6, L.bsize + 12, L.bsize + 12, 14); c.fill(); }
    c.restore();

    // score count-up
    if (shownScore !== game.score) {
      var diff = game.score - shownScore;
      shownScore += diff > 0 ? Math.max(1, Math.ceil(diff * 0.2)) : diff;
      if (Math.abs(game.score - shownScore) < 1) shownScore = game.score;
      busy = true;
    }
    elScore.textContent = String(shownScore);
    elBest.textContent = String(Math.max(best, shownScore));
    return busy;
  }

  // ---------- optional native host (Android WebView). Everything works without it. ----------
  var native = window.GridbloomNative || null;
  function nat(name, fallback) {
    try { if (native && typeof native[name] === 'function') return native[name](); } catch (e) { /* ignore */ }
    return fallback;
  }
  var pendingRescue = false;
  window.__gbNative = {
    rescueResult: function (granted) {
      pendingRescue = false; $('rescue').disabled = false;
      if (granted && game.rescue()) { sfx.rescue(); buzz(30); persist(); close(dlgOver); fx.trayIn = [now(), now() + 70, now() + 140]; requestDraw(); }
    },
    proceedNewGame: function () { newGame(); }
  };

  // ---------- dialogs ----------
  function dlgOpen() { return dlgOver.classList.contains('show') || dlgSet.classList.contains('show'); }
  function open(d) { d.classList.add('show'); d.removeAttribute('aria-hidden'); var b = d.querySelector('button'); if (b) b.focus(); }
  function close(d) { d.classList.remove('show'); d.setAttribute('aria-hidden', 'true'); canvas.focus(); }

  function showOver() {
    sfx.over(); buzz([40, 60, 40]);
    var isNew = game.score > startBest && game.score > 0;
    $('overScore').textContent = String(game.score);
    $('overBest').textContent = isNew ? 'New best!' : 'Best ' + best;
    $('overBest').className = isNew ? 'badge' : 'muted';
    $('overStats').textContent = game.linesTotal + ' lines · ' + game.moves + ' moves';
    var canRescue = game.canRescue() && (!native || nat('isRewardedReady', false) === true);
    $('rescue').hidden = !canRescue;
    $('rescue').textContent = native ? 'Watch ad for a second chance' : 'Second chance';
    nat('onGameOver');
    open(dlgOver);
  }
  function newGame() {
    clearTimeout(overTimer);
    game = new GB.Game();
    startBest = best; shownScore = 0;
    fx = { pops: [], clears: [], parts: [], floats: [], toasts: [], trayIn: [now(), now() + 70, now() + 140], shake: 0, shakeT: 0 };
    persist(); elLive.textContent = 'New game. Score 0';
    close(dlgOver); close(dlgSet); requestDraw();
  }
  $('again').addEventListener('click', function () {
    if (native && typeof native.beforeNewGame === 'function') { try { native.beforeNewGame(); return; } catch (e) { /* fall through */ } }
    newGame();
  });
  $('rescue').addEventListener('click', function () {
    if (native) {
      if (pendingRescue) return;
      pendingRescue = true; $('rescue').disabled = true;
      try { native.requestRescue(); } catch (e) { window.__gbNative.rescueResult(false); }
      return;
    }
    window.__gbNative.rescueResult(true);
  });
  $('menu').addEventListener('click', function () { renderSettings(); open(dlgSet); });
  $('closeSet').addEventListener('click', function () { close(dlgSet); });
  $('restart').addEventListener('click', newGame);
  $('privacy').hidden = !(native && typeof native.openPrivacy === 'function');
  $('privacy').addEventListener('click', function () { nat('openPrivacy'); });

  var resetArmed = false;
  function renderSettings() {
    $('optSound').setAttribute('aria-checked', String(settings.sound));
    $('optHaptics').setAttribute('aria-checked', String(settings.haptics));
    $('optHaptics').hidden = !navigator.vibrate;
    $('rowHaptics').hidden = !navigator.vibrate;
    var box = $('skins'); box.innerHTML = '';
    SKINS.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'skin' + (s.id === settings.skin ? ' on' : ''); b.type = 'button';
      b.setAttribute('aria-pressed', String(s.id === settings.skin));
      b.innerHTML = '<span class="sw" style="background:linear-gradient(135deg,' + s.blocks[0] + ',' + s.blocks[3] + ',' + s.blocks[5] + ')"></span>' + s.name;
      b.addEventListener('click', function () { settings.skin = s.id; saveSettings(); applySkin(); renderSettings(); draw(); });
      box.appendChild(b);
    });
    $('resetBest').textContent = resetArmed ? 'Tap again to confirm' : 'Reset best score';
  }
  $('optSound').addEventListener('click', function () { settings.sound = !settings.sound; saveSettings(); ensureAudio(); renderSettings(); if (settings.sound) sfx.pick(); });
  $('optHaptics').addEventListener('click', function () { settings.haptics = !settings.haptics; saveSettings(); renderSettings(); buzz(20); });
  $('resetBest').addEventListener('click', function () {
    if (!resetArmed) { resetArmed = true; renderSettings(); setTimeout(function () { resetArmed = false; renderSettings(); }, 3000); return; }
    best = 0; startBest = 0; store.del('gb.best'); resetArmed = false; renderSettings(); requestDraw();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { if (dlgSet.classList.contains('show')) close(dlgSet); }
    else if (e.key === 'm' || e.key === 'M') { settings.sound = !settings.sound; saveSettings(); }
    else if ((e.key === 'n' || e.key === 'N') && !dlgOpen()) { /* no accidental restarts */ }
  });

  function applySkin() {
    var sk = skin(), s = document.documentElement.style;
    s.setProperty('--bg1', sk.bg1); s.setProperty('--bg2', sk.bg2); s.setProperty('--text', sk.text);
    s.setProperty('--dim', sk.dim); s.setProperty('--accent', sk.accent); s.setProperty('--panel', sk.panel);
    var m = document.querySelector('meta[name=theme-color]'); if (m) m.setAttribute('content', sk.bg2);
  }

  document.addEventListener('visibilitychange', function () { if (document.hidden) persist(); });
  window.addEventListener('pagehide', persist);
  window.addEventListener('resize', layout);
  window.addEventListener('orientationchange', function () { setTimeout(layout, 100); });
  if (window.ResizeObserver) new ResizeObserver(layout).observe(canvas);

  // ---------- test hook (read-only helpers for automated checks) ----------
  window.__gb = { game: function () { return game; }, layout: function () { return L; }, place: place, newGame: newGame };

  applySkin(); layout();
  fx.trayIn = [now(), now() + 70, now() + 140];
  if (game.over) showOver(); else persist();
  requestDraw();
})();
