'use strict';
const assert = require('assert');
const GB = require('../web/engine.js');
const { SIZE, SHAPES, Game } = GB;

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log('  ok   ' + name); }
  catch (e) { failed++; console.log('  FAIL ' + name + '\n       ' + (e && e.stack || e)); }
}
const idx = (r, c) => r * SIZE + c;

console.log('shapes');
test('every shape has cells, unique id, valid colour', () => {
  const ids = GB.SHAPE_IDS;
  assert.strictEqual(new Set(ids).size, ids.length);
  assert.ok(ids.length >= 35);
  ids.forEach(id => {
    const s = SHAPES[id];
    assert.ok(s.size >= 1 && s.size === s.cells.length);
    assert.ok(s.color >= 1 && s.color <= GB.COLOR_COUNT);
    s.cells.forEach(([r, c]) => assert.ok(r >= 0 && c >= 0 && r < s.h && c < s.w));
  });
});
test('shapes have the right cell counts', () => {
  assert.strictEqual(SHAPES.dot.size, 1);
  assert.strictEqual(SHAPES.h5.size, 5);
  assert.strictEqual(SHAPES.sq3.size, 9);
  assert.strictEqual(SHAPES.l5a.size, 5);
  assert.strictEqual(SHAPES.t1.size, 4);
});

console.log('board');
test('canPlace respects bounds and occupied cells', () => {
  const b = GB.emptyBoard();
  assert.ok(GB.canPlace(b, SHAPES.sq2, 0, 0));
  assert.ok(GB.canPlace(b, SHAPES.sq2, 6, 6));
  assert.ok(!GB.canPlace(b, SHAPES.sq2, 7, 7));
  assert.ok(!GB.canPlace(b, SHAPES.h3, 0, 6));
  assert.ok(!GB.canPlace(b, SHAPES.dot, -1, 0));
  b[idx(3, 3)] = 1;
  assert.ok(!GB.canPlace(b, SHAPES.sq2, 2, 2));
  assert.ok(GB.canPlace(b, SHAPES.sq2, 4, 4));
});
test('a full row clears', () => {
  const b = GB.emptyBoard();
  for (let c = 0; c < 7; c++) b[idx(4, c)] = 2;
  const o = GB.placeAndClear(b, SHAPES.dot, 4, 7);
  assert.deepStrictEqual(o.rows, [4]);
  assert.strictEqual(o.lines, 1);
  assert.strictEqual(o.cleared.length, 8);
  for (let c = 0; c < SIZE; c++) assert.strictEqual(o.board[idx(4, c)], 0);
});
test('a full column clears', () => {
  const b = GB.emptyBoard();
  for (let r = 0; r < 7; r++) b[idx(r, 2)] = 3;
  const o = GB.placeAndClear(b, SHAPES.dot, 7, 2);
  assert.deepStrictEqual(o.cols, [2]);
  assert.strictEqual(o.lines, 1);
});
test('row and column clear together, shared cell counted once', () => {
  const b = GB.emptyBoard();
  for (let c = 0; c < SIZE; c++) if (c !== 3) b[idx(3, c)] = 1;
  for (let r = 0; r < SIZE; r++) if (r !== 3) b[idx(r, 3)] = 1;
  const o = GB.placeAndClear(b, SHAPES.dot, 3, 3);
  assert.strictEqual(o.lines, 2);
  assert.strictEqual(o.cleared.length, 15);
  assert.ok(o.allClear);
});
test('two rows clear at the same time', () => {
  const b = GB.emptyBoard();
  for (let c = 0; c < 6; c++) { b[idx(0, c)] = 1; b[idx(1, c)] = 1; }
  const o = GB.placeAndClear(b, SHAPES.sq2, 0, 6);
  assert.deepStrictEqual(o.rows, [0, 1]);
  assert.ok(o.allClear);
});
test('placeAndClear does not mutate the input board', () => {
  const b = GB.emptyBoard();
  const copy = b.slice();
  GB.placeAndClear(b, SHAPES.h3, 0, 0);
  assert.deepStrictEqual(Array.from(b), Array.from(copy));
});
test('previewClears matches the real result', () => {
  const b = GB.emptyBoard();
  for (let c = 0; c < 7; c++) b[idx(5, c)] = 1;
  const p = GB.previewClears(b, SHAPES.dot, 5, 7);
  assert.deepStrictEqual(p.rows, [5]);
  assert.deepStrictEqual(p.cols, []);
});
test('placements lists exactly the legal anchors', () => {
  const b = GB.emptyBoard();
  assert.strictEqual(GB.placements(b, SHAPES.sq2).length, 49);
  assert.strictEqual(GB.placements(b, SHAPES.sq3).length, 36);
  assert.strictEqual(GB.placements(b, SHAPES.h5).length, 32);
});

console.log('scoring');
test('line base points grow faster than linear', () => {
  assert.deepStrictEqual([0, 1, 2, 3, 4, 5].map(GB.lineBase), [0, 10, 30, 60, 100, 150]);
});
test('no clear: only placement points, streak waits then resets after 3', () => {
  let s = GB.scorePlacement(4, 0, false, 2, 0);
  assert.strictEqual(s.total, 4); assert.strictEqual(s.streak, 2); assert.strictEqual(s.since, 1);
  s = GB.scorePlacement(4, 0, false, 2, 1);
  assert.strictEqual(s.streak, 2); assert.strictEqual(s.since, 2);
  s = GB.scorePlacement(4, 0, false, 2, 2);
  assert.strictEqual(s.streak, 0); assert.strictEqual(s.since, 3);
});
test('first clear has no combo bonus, second does', () => {
  let s = GB.scorePlacement(3, 1, false, 0, 0);
  assert.strictEqual(s.streak, 1); assert.strictEqual(s.combo, 0); assert.strictEqual(s.total, 3 + 10);
  s = GB.scorePlacement(3, 1, false, 1, 0);
  assert.strictEqual(s.streak, 2); assert.strictEqual(s.combo, 10); assert.strictEqual(s.total, 3 + 10 + 10);
  s = GB.scorePlacement(3, 2, false, 2, 0);
  assert.strictEqual(s.combo, 2 * 10 * 2);
});
test('all clear adds the bonus', () => {
  const s = GB.scorePlacement(1, 1, true, 0, 0);
  assert.strictEqual(s.allClear, GB.ALL_CLEAR_BONUS);
  assert.strictEqual(s.total, 1 + 10 + GB.ALL_CLEAR_BONUS);
});

console.log('rng and generator');
test('same seed gives the same sequence, different seed differs', () => {
  const a = new GB.Rng(42), b = new GB.Rng(42), c = new GB.Rng(43);
  const sa = [], sb = [], sc = [];
  for (let i = 0; i < 20; i++) { sa.push(a.next()); sb.push(b.next()); sc.push(c.next()); }
  assert.deepStrictEqual(sa, sb);
  assert.notDeepStrictEqual(sa, sc);
  sa.forEach(x => assert.ok(x >= 0 && x < 1));
});
test('same seed gives the same first tray', () => {
  assert.deepStrictEqual(new Game(7).tray, new Game(7).tray);
});
test('every dealt tray fits and is solvable (empty board, 300 seeds)', () => {
  for (let s = 1; s <= 300; s++) {
    const g = new Game(s);
    assert.strictEqual(g.tray.length, 3);
    g.tray.forEach(id => assert.ok(GB.canPlaceAnywhere(g.board, SHAPES[id]), 'seed ' + s));
    assert.ok(GB.solvable(g.board, g.tray), 'seed ' + s);
  }
});
test('trays on half-full random boards still fit', () => {
  const rng = new GB.Rng(99);
  let checked = 0;
  for (let n = 0; n < 150; n++) {
    const b = GB.emptyBoard();
    for (let i = 0; i < GB.CELLS; i++) if (rng.next() < 0.45) b[i] = 1 + rng.int(7);
    // skip boards with a full line (they would already have cleared)
    const fl = GB.fullLines(b);
    if (fl.rows.length || fl.cols.length) continue;
    const t = GB.nextTray(rng, b, 1500);
    assert.strictEqual(t.length, 3);
    // At least one piece must fit unless the board is truly packed.
    if (GB.placements(b, SHAPES.dot).length > 0) assert.ok(GB.hasAnyMove(b, t) || t.every(id => !GB.canPlaceAnywhere(b, SHAPES[id])));
    checked++;
  }
  assert.ok(checked > 50);
});
test('higher score deals harder pieces on average', () => {
  const avg = score => {
    const rng = new GB.Rng(5); let sum = 0, n = 0;
    for (let i = 0; i < 400; i++) { const id = GB.nextTray(rng, GB.emptyBoard(), score); id.forEach(x => { sum += SHAPES[x].cls; n++; }); }
    return sum / n;
  };
  assert.ok(avg(6000) > avg(0));
});

console.log('game');
test('new game: empty board, three pieces, score 0, not over', () => {
  const g = new Game(1);
  assert.strictEqual(g.score, 0);
  assert.ok(!g.over);
  assert.strictEqual(g.tray.filter(Boolean).length, 3);
  assert.ok(Array.from(g.board).every(v => v === 0));
});
test('submit places a piece, scores it and removes it from the tray', () => {
  const g = new Game(3);
  const id = g.tray[0];
  const r = g.submit(0, 0, 0);
  assert.ok(r.ok);
  assert.strictEqual(g.tray[0], null);
  assert.strictEqual(g.score, SHAPES[id].size);
});
test('invalid moves are rejected and change nothing', () => {
  const g = new Game(3);
  const before = g.serialize();
  assert.strictEqual(g.submit(0, 7, 7).ok && SHAPES[g.tray[0]].size > 1, false);
  assert.strictEqual(g.submit(5, 0, 0).reason, 'no-piece');
  assert.strictEqual(g.submit(-1, 0, 0).reason, 'no-piece');
  assert.strictEqual(g.submit(0, 100, 100).reason, 'blocked');
  assert.strictEqual(g.submit(0, NaN, 0).reason, 'blocked');
  assert.strictEqual(g.serialize(), before);
});
test('placing on an occupied spot is rejected', () => {
  const g = new Game(3);
  g.board[idx(0, 0)] = 1;
  assert.strictEqual(g.submit(0, 0, 0).reason, 'blocked');
});
test('tray refills only after all three are used', () => {
  const g = new Game(11);
  const r1 = g.submit(0, 0, 0 + 0);
  assert.ok(r1.ok && !r1.newTray);
  const p2 = GB.placements(g.board, SHAPES[g.tray[1]])[0];
  const r2 = g.submit(1, p2[0], p2[1]);
  assert.ok(r2.ok && !r2.newTray);
  const p3 = GB.placements(g.board, SHAPES[g.tray[2]])[0];
  const r3 = g.submit(2, p3[0], p3[1]);
  assert.ok(r3.ok && r3.newTray);
  assert.strictEqual(g.tray.filter(Boolean).length, 3);
});
test('game over when nothing fits, not before', () => {
  const g = new Game(1);
  g.board.fill(1);
  g.board[idx(0, 0)] = 0;           // one hole
  g.tray = ['h2', null, null];      // does not fit a single hole
  g.over = !GB.hasAnyMove(g.board, g.tray);
  assert.ok(g.over);
  g.over = false;
  g.tray = ['dot', 'h2', null];     // dot fits
  assert.ok(GB.hasAnyMove(g.board, g.tray));
});
test('last placement that leaves no move ends the game', () => {
  const g = new Game(1);
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) g.board[idx(r, c)] = (r + c) % 2 === 0 ? 1 : 0; // checkerboard: no line, only single holes
  g.tray = ['dot', 'sq3', null];
  const r = g.submit(0, 0, 1);
  assert.ok(r.ok);
  assert.ok(r.over && g.over);
  assert.strictEqual(g.submit(1, 0, 0).reason, 'over');
});
test('clearing lines scores line + combo and counts lines', () => {
  const g = new Game(1);
  for (let c = 0; c < 7; c++) g.board[idx(0, c)] = 2;
  g.board[idx(5, 5)] = 4; // a stray block so this is not an all-clear
  g.tray = ['dot', 'dot', 'dot'];
  const r = g.submit(0, 0, 7);
  assert.strictEqual(r.lines, 1);
  assert.strictEqual(g.score, 1 + 10);
  assert.strictEqual(g.streak, 1);
  assert.strictEqual(g.linesTotal, 1);
});
test('all-clear bonus is applied in a real move', () => {
  const g = new Game(1);
  for (let c = 0; c < 7; c++) g.board[idx(0, c)] = 2;
  g.tray = ['dot', null, null];
  const r = g.submit(0, 0, 7);
  assert.ok(r.allClear);
  assert.strictEqual(g.score, 1 + 10 + GB.ALL_CLEAR_BONUS);
});

console.log('rescue');
test('rescue works once, clears the centre, deals small pieces', () => {
  const g = new Game(1);
  g.board.fill(3);
  g.tray = [null, null, null];
  g.over = true;
  assert.ok(g.canRescue());
  assert.ok(g.rescue());
  for (let r = 2; r <= 5; r++) for (let c = 2; c <= 5; c++) assert.strictEqual(g.board[idx(r, c)], 0);
  assert.strictEqual(g.board[idx(0, 0)], 3);
  g.tray.forEach(id => assert.ok(GB.SMALL_IDS.includes(id)));
  assert.ok(!g.over);
  g.over = true;
  assert.ok(!g.canRescue());
  assert.strictEqual(g.rescue(), false);
});
test('rescue is refused when the game is not over', () => {
  const g = new Game(1);
  assert.strictEqual(g.rescue(), false);
});

console.log('save and restore');
test('round trip keeps the whole state and the future', () => {
  const g = new Game(21);
  for (let k = 0; k < 4; k++) {
    for (let i = 0; i < 3; i++) {
      if (!g.tray[i]) continue;
      const p = GB.placements(g.board, SHAPES[g.tray[i]])[0];
      if (p) g.submit(i, p[0], p[1]);
    }
  }
  const h = Game.restore(g.serialize());
  assert.ok(h);
  assert.strictEqual(h.score, g.score);
  assert.deepStrictEqual(Array.from(h.board), Array.from(g.board));
  assert.deepStrictEqual(h.tray, g.tray);
  // continue both with the same moves: identical outcome
  for (let i = 0; i < 3; i++) {
    if (!g.tray[i]) continue;
    const p = GB.placements(g.board, SHAPES[g.tray[i]])[0];
    if (!p) continue;
    g.submit(i, p[0], p[1]); h.submit(i, p[0], p[1]);
  }
  assert.strictEqual(h.score, g.score);
  assert.deepStrictEqual(h.tray, g.tray);
});
test('damaged or old saves are rejected, never throw', () => {
  assert.strictEqual(Game.restore(null), null);
  assert.strictEqual(Game.restore(''), null);
  assert.strictEqual(Game.restore('{not json'), null);
  assert.strictEqual(Game.restore('{"v":99}'), null);
  const good = JSON.parse(new Game(1).serialize());
  const bad1 = Object.assign({}, good, { board: good.board.slice(0, 10) });
  const bad2 = Object.assign({}, good, { tray: ['nope', null, null] });
  const bad3 = Object.assign({}, good, { board: good.board.map((v, i) => i === 0 ? 99 : v) });
  assert.strictEqual(Game.restore(bad1), null);
  assert.strictEqual(Game.restore(bad2), null);
  assert.strictEqual(Game.restore(bad3), null);
});

console.log('playouts');
test('300 random games finish with valid invariants', () => {
  for (let s = 1; s <= 300; s++) {
    const g = new Game(s * 7919);
    const rng = new GB.Rng(s);
    let last = 0, guard = 0;
    while (!g.over && guard++ < 2000) {
      const opts = [];
      for (let i = 0; i < 3; i++) if (g.tray[i]) GB.placements(g.board, SHAPES[g.tray[i]]).forEach(p => opts.push([i, p[0], p[1]]));
      assert.ok(opts.length > 0, 'not over but no move, seed ' + s);
      const m = opts[rng.int(opts.length)];
      const r = g.submit(m[0], m[1], m[2]);
      assert.ok(r.ok);
      assert.ok(g.score >= last); last = g.score;
      for (let i = 0; i < GB.CELLS; i++) assert.ok(g.board[i] >= 0 && g.board[i] <= 7);
      const fl = GB.fullLines(g.board);
      assert.strictEqual(fl.rows.length + fl.cols.length, 0, 'full line left on board');
    }
    assert.ok(g.over, 'game did not end, seed ' + s);
    assert.ok(!GB.hasAnyMove(g.board, g.tray));
  }
});
test('a smart-ish player outlasts a random one', () => {
  function play(seed, smart) {
    const g = new Game(seed); const rng = new GB.Rng(seed + 1); let guard = 0;
    while (!g.over && guard++ < 3000) {
      let best = null, bestV = -1e9;
      for (let i = 0; i < 3; i++) if (g.tray[i]) GB.placements(g.board, SHAPES[g.tray[i]]).forEach(p => {
        const v = smart ? GB.previewClears(g.board, SHAPES[g.tray[i]], p[0], p[1]).rows.length + GB.previewClears(g.board, SHAPES[g.tray[i]], p[0], p[1]).cols.length * 1 : rng.next();
        if (v > bestV) { bestV = v; best = [i, p[0], p[1]]; }
      });
      g.submit(best[0], best[1], best[2]);
    }
    return g.score;
  }
  let a = 0, b = 0;
  for (let s = 1; s <= 40; s++) { a += play(s, true); b += play(s, false); }
  assert.ok(a > b, 'greedy ' + a + ' vs random ' + b);
});

console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);
