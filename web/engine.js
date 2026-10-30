/* Gridbloom engine: pure rules, no DOM, no clock. UMD so Node tests and the browser share it. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.GB = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var SIZE = 8;
  var CELLS = SIZE * SIZE;
  var TRAY = 3;
  var COMBO_GRACE = 3;
  var ALL_CLEAR_BONUS = 300;
  var SAVE_VERSION = 1;
  var MAX_RESCUES = 1;

  // ---------- shapes (ASCII, '#' = filled) ----------
  var RAW = {
    dot: ['#'],
    h2: ['##'], v2: ['#', '#'],
    h3: ['###'], v3: ['#', '#', '#'],
    h4: ['####'], v4: ['#', '#', '#', '#'],
    h5: ['#####'], v5: ['#', '#', '#', '#', '#'],
    sq2: ['##', '##'],
    sq3: ['###', '###', '###'],
    r23: ['###', '###'], r32: ['##', '##', '##'],
    // small L (3 cells), 4 rotations
    l3a: ['#.', '##'], l3b: ['.#', '##'], l3c: ['##', '#.'], l3d: ['##', '.#'],
    // big L (5 cells), 4 rotations
    l5a: ['#..', '#..', '###'], l5b: ['..#', '..#', '###'],
    l5c: ['###', '#..', '#..'], l5d: ['###', '..#', '..#'],
    // L tetromino, both hands, 4 rotations each
    lt1: ['#.', '#.', '##'], lt2: ['.#', '.#', '##'],
    lt3: ['##', '#.', '#.'], lt4: ['##', '.#', '.#'],
    lt5: ['###', '#..'], lt6: ['###', '..#'],
    lt7: ['#..', '###'], lt8: ['..#', '###'],
    // T tetromino, 4 rotations
    t1: ['###', '.#.'], t2: ['.#.', '###'], t3: ['#.', '##', '#.'], t4: ['.#', '##', '.#'],
    // S / Z
    s1: ['.##', '##.'], z1: ['##.', '.##'], s2: ['#.', '##', '.#'], z2: ['.#', '##', '#.']
  };

  // weight class: 0 easy, 1 medium, 2 hard (hard pieces grow more likely as score rises)
  var CLASS = {
    dot: 0, h2: 0, v2: 0, l3a: 0, l3b: 0, l3c: 0, l3d: 0, sq2: 0, h3: 0, v3: 0,
    h4: 1, v4: 1, r23: 1, r32: 1, lt1: 1, lt2: 1, lt3: 1, lt4: 1, lt5: 1, lt6: 1, lt7: 1, lt8: 1,
    t1: 1, t2: 1, t3: 1, t4: 1, s1: 1, z1: 1, s2: 1, z2: 1,
    h5: 2, v5: 2, sq3: 2, l5a: 2, l5b: 2, l5c: 2, l5d: 2
  };
  var SMALL_IDS = ['dot', 'h2', 'v2', 'sq2', 'l3a', 'l3b', 'l3c', 'l3d'];

  var SHAPES = {};
  var SHAPE_IDS = [];
  Object.keys(RAW).forEach(function (id, i) {
    var rows = RAW[id], cells = [];
    for (var r = 0; r < rows.length; r++)
      for (var c = 0; c < rows[r].length; c++)
        if (rows[r][c] === '#') cells.push([r, c]);
    SHAPES[id] = {
      id: id, cells: cells, h: rows.length, w: rows[0].length,
      size: cells.length, cls: CLASS[id], color: (i % 7) + 1
    };
    SHAPE_IDS.push(id);
  });
  var COLOR_COUNT = 7;

  // ---------- seeded rng (mulberry32, state is one integer so it can be saved) ----------
  function Rng(seed) { this.s = (seed >>> 0) || 1; }
  Rng.prototype.next = function () {
    this.s = (this.s + 0x6D2B79F5) >>> 0;
    var t = this.s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  Rng.prototype.int = function (n) { return Math.floor(this.next() * n); };

  // ---------- board helpers (board = Uint8Array(64), 0 empty, 1..7 colour) ----------
  function emptyBoard() { return new Uint8Array(CELLS); }

  function canPlace(board, shape, r0, c0) {
    for (var i = 0; i < shape.cells.length; i++) {
      var r = r0 + shape.cells[i][0], c = c0 + shape.cells[i][1];
      if (r < 0 || c < 0 || r >= SIZE || c >= SIZE) return false;
      if (board[r * SIZE + c] !== 0) return false;
    }
    return true;
  }

  function placements(board, shape) {
    var out = [];
    for (var r = 0; r <= SIZE - shape.h; r++)
      for (var c = 0; c <= SIZE - shape.w; c++)
        if (canPlace(board, shape, r, c)) out.push([r, c]);
    return out;
  }

  function canPlaceAnywhere(board, shape) {
    for (var r = 0; r <= SIZE - shape.h; r++)
      for (var c = 0; c <= SIZE - shape.w; c++)
        if (canPlace(board, shape, r, c)) return true;
    return false;
  }

  // Which rows/cols would be full after placing (no mutation). Used for the preview.
  function previewClears(board, shape, r0, c0) {
    var tmp = board.slice();
    for (var i = 0; i < shape.cells.length; i++)
      tmp[(r0 + shape.cells[i][0]) * SIZE + (c0 + shape.cells[i][1])] = shape.color;
    return fullLines(tmp);
  }

  function fullLines(b) {
    var rows = [], cols = [], r, c, ok;
    for (r = 0; r < SIZE; r++) {
      ok = true;
      for (c = 0; c < SIZE; c++) if (b[r * SIZE + c] === 0) { ok = false; break; }
      if (ok) rows.push(r);
    }
    for (c = 0; c < SIZE; c++) {
      ok = true;
      for (r = 0; r < SIZE; r++) if (b[r * SIZE + c] === 0) { ok = false; break; }
      if (ok) cols.push(c);
    }
    return { rows: rows, cols: cols };
  }

  // Place a shape and clear every full row and column at once.
  function placeAndClear(board, shape, r0, c0) {
    var b = board.slice(), i;
    for (i = 0; i < shape.cells.length; i++)
      b[(r0 + shape.cells[i][0]) * SIZE + (c0 + shape.cells[i][1])] = shape.color;
    var lines = fullLines(b);
    var cleared = []; // [index, colour] so the UI can animate what vanished
    var seen = {};
    function wipe(idx) {
      if (seen[idx]) return;
      seen[idx] = true;
      cleared.push([idx, b[idx]]);
    }
    var r, c;
    lines.rows.forEach(function (row) { for (c = 0; c < SIZE; c++) wipe(row * SIZE + c); });
    lines.cols.forEach(function (col) { for (r = 0; r < SIZE; r++) wipe(r * SIZE + col); });
    cleared.forEach(function (e) { b[e[0]] = 0; });
    var empty = true;
    for (i = 0; i < CELLS; i++) if (b[i] !== 0) { empty = false; break; }
    return {
      board: b, rows: lines.rows, cols: lines.cols,
      lines: lines.rows.length + lines.cols.length,
      cleared: cleared, allClear: empty
    };
  }

  // ---------- scoring ----------
  function lineBase(n) { return n <= 0 ? 0 : (10 * n * (n + 1)) / 2; }

  function scorePlacement(cells, lines, allClear, streak, since) {
    if (lines === 0) {
      var s = since + 1;
      return {
        placement: cells, line: 0, combo: 0, allClear: 0,
        streak: s >= COMBO_GRACE ? 0 : streak, since: s,
        total: cells
      };
    }
    var ns = streak + 1;
    var line = lineBase(lines);
    var combo = ns >= 2 ? (ns - 1) * 10 * lines : 0;
    var ac = allClear ? ALL_CLEAR_BONUS : 0;
    return {
      placement: cells, line: line, combo: combo, allClear: ac,
      streak: ns, since: 0, total: cells + line + combo + ac
    };
  }

  // ---------- generator ----------
  function weightFor(shape, score) {
    var t = Math.min(1, score / 4000); // difficulty ramp 0..1
    var base = shape.cls === 0 ? 1.0 - 0.4 * t : shape.cls === 1 ? 1.0 : 0.35 + 0.9 * t;
    return base;
  }

  function pickShape(rng, score, pool) {
    var ids = pool || SHAPE_IDS, total = 0, ws = [], i;
    for (i = 0; i < ids.length; i++) { var w = weightFor(SHAPES[ids[i]], score); ws.push(w); total += w; }
    var x = rng.next() * total;
    for (i = 0; i < ids.length; i++) { x -= ws[i]; if (x <= 0) return ids[i]; }
    return ids[ids.length - 1];
  }

  // Is there some order of the tray where every piece can be placed in turn? (clears simulated)
  // The search has a node budget; if it runs out we give the tray the benefit of the doubt.
  function solvable(board, trayIds, budget) {
    var nodes = { n: budget || 12000 };
    var pieces = trayIds.filter(Boolean).map(function (id) { return SHAPES[id]; });
    if (pieces.length === 0) return true;

    function dfs(b, remaining) {
      if (remaining.length === 0) return true;
      if (nodes.n <= 0) return true;
      for (var i = 0; i < remaining.length; i++) {
        var sh = remaining[i];
        var rest = remaining.slice(0, i).concat(remaining.slice(i + 1));
        for (var r = 0; r <= SIZE - sh.h; r++) {
          for (var c = 0; c <= SIZE - sh.w; c++) {
            if (!canPlace(b, sh, r, c)) continue;
            nodes.n--;
            if (nodes.n <= 0) return true;
            var out = placeAndClear(b, sh, r, c);
            if (dfs(out.board, rest)) return true;
          }
        }
      }
      return false;
    }
    return dfs(board, pieces);
  }

  function nextTray(rng, board, score) {
    var tries = 40, best = null, i, j;
    for (i = 0; i < tries; i++) {
      var tray = [];
      for (j = 0; j < TRAY; j++) tray.push(pickShape(rng, score));
      var allFit = tray.every(function (id) { return canPlaceAnywhere(board, SHAPES[id]); });
      if (!allFit) continue;
      if (solvable(board, tray)) return tray;
      if (!best) best = tray;
    }
    if (best) return best;
    // Fallback: small pieces only, and prefer ones that fit.
    var small = [];
    for (j = 0; j < TRAY; j++) small.push(pickShape(rng, 0, SMALL_IDS));
    return small;
  }

  function hasAnyMove(board, tray) {
    for (var i = 0; i < tray.length; i++)
      if (tray[i] && canPlaceAnywhere(board, SHAPES[tray[i]])) return true;
    return false;
  }

  // ---------- game ----------
  function Game(seed) { this.newGame(seed); }

  Game.prototype.newGame = function (seed) {
    if (seed === undefined) seed = (Date.now() ^ Math.floor(Math.random() * 0xffffffff)) >>> 0;
    this.seed = seed >>> 0;
    this.rng = new Rng(this.seed);
    this.board = emptyBoard();
    this.score = 0;
    this.streak = 0;
    this.since = 0;
    this.rescues = 0;
    this.over = false;
    this.moves = 0;
    this.linesTotal = 0;
    this.tray = nextTray(this.rng, this.board, 0);
  };

  // submit(trayIndex, row, col) -> result. Never throws on bad input; returns ok:false with a reason.
  Game.prototype.submit = function (index, r, c) {
    if (this.over) return { ok: false, reason: 'over' };
    if (!(index >= 0 && index < TRAY) || !this.tray[index]) return { ok: false, reason: 'no-piece' };
    var shape = SHAPES[this.tray[index]];
    if (!canPlace(this.board, shape, r, c)) return { ok: false, reason: 'blocked' };

    var out = placeAndClear(this.board, shape, r, c);
    var sc = scorePlacement(shape.size, out.lines, out.allClear, this.streak, this.since);
    this.board = out.board;
    this.score += sc.total;
    this.streak = sc.streak;
    this.since = sc.since;
    this.moves++;
    this.linesTotal += out.lines;
    this.tray[index] = null;

    var newTray = false;
    if (this.tray.every(function (t) { return t === null; })) {
      this.tray = nextTray(this.rng, this.board, this.score);
      newTray = true;
    }
    this.over = !hasAnyMove(this.board, this.tray);
    return {
      ok: true, shape: shape, row: r, col: c, rows: out.rows, cols: out.cols,
      lines: out.lines, cleared: out.cleared, allClear: out.allClear,
      score: sc, newTray: newTray, over: this.over
    };
  };

  Game.prototype.canRescue = function () { return this.over && this.rescues < MAX_RESCUES; };

  // Clear the central 4x4 block and deal three small pieces. Returns false if not allowed.
  Game.prototype.rescue = function () {
    if (!this.canRescue()) return false;
    for (var r = 2; r <= 5; r++) for (var c = 2; c <= 5; c++) this.board[r * SIZE + c] = 0;
    var t = [];
    for (var i = 0; i < TRAY; i++) t.push(pickShape(this.rng, 0, SMALL_IDS));
    this.tray = t;
    this.rescues++;
    this.over = !hasAnyMove(this.board, this.tray);
    return true;
  };

  Game.prototype.serialize = function () {
    return JSON.stringify({
      v: SAVE_VERSION, seed: this.seed, rng: this.rng.s, board: Array.prototype.slice.call(this.board),
      tray: this.tray, score: this.score, streak: this.streak, since: this.since,
      rescues: this.rescues, over: this.over, moves: this.moves, lines: this.linesTotal
    });
  };

  // Returns a Game, or null if the data is missing, old or damaged. Never throws.
  Game.restore = function (json) {
    try {
      var d = typeof json === 'string' ? JSON.parse(json) : json;
      if (!d || d.v !== SAVE_VERSION) return null;
      if (!Array.isArray(d.board) || d.board.length !== CELLS) return null;
      if (!Array.isArray(d.tray) || d.tray.length !== TRAY) return null;
      for (var i = 0; i < CELLS; i++) {
        var v = d.board[i];
        if (!(v === 0 || (v >= 1 && v <= COLOR_COUNT && v === (v | 0)))) return null;
      }
      for (var j = 0; j < TRAY; j++) if (d.tray[j] !== null && !SHAPES[d.tray[j]]) return null;
      var g = new Game(d.seed);
      g.rng = new Rng(1); g.rng.s = d.rng >>> 0;
      g.board = Uint8Array.from(d.board);
      g.tray = d.tray.slice();
      g.score = Math.max(0, d.score | 0);
      g.streak = Math.max(0, d.streak | 0);
      g.since = Math.max(0, d.since | 0);
      g.rescues = Math.max(0, d.rescues | 0);
      g.moves = Math.max(0, d.moves | 0);
      g.linesTotal = Math.max(0, d.lines | 0);
      g.over = !!d.over;
      return g;
    } catch (e) { return null; }
  };

  return {
    SIZE: SIZE, CELLS: CELLS, TRAY: TRAY, COMBO_GRACE: COMBO_GRACE, ALL_CLEAR_BONUS: ALL_CLEAR_BONUS,
    COLOR_COUNT: COLOR_COUNT, SHAPES: SHAPES, SHAPE_IDS: SHAPE_IDS, SMALL_IDS: SMALL_IDS,
    Rng: Rng, Game: Game, emptyBoard: emptyBoard, canPlace: canPlace, canPlaceAnywhere: canPlaceAnywhere,
    placements: placements, previewClears: previewClears, placeAndClear: placeAndClear, fullLines: fullLines,
    lineBase: lineBase, scorePlacement: scorePlacement, nextTray: nextTray, solvable: solvable,
    hasAnyMove: hasAnyMove
  };
});
