# Architecture: Gridbloom

## Stack (boring on purpose)
| layer | choice | why |
| --- | --- | --- |
| core rules | one pure module, no DOM (`web/engine.js`, UMD) | testable in Node, mirrored 1:1 in Kotlin |
| web UI | Canvas 2D + pointer events, one HTML file | no build step, runs anywhere, easy to wrap in Android later |
| persistence | localStorage (web), SharedPreferences (Android) | no backend needed |
| audio | WebAudio synth (web), AudioTrack synth (Android) | no audio files, nothing to license |
| tests | Node `assert` (web), JUnit (Android) | same cases on both sides |
| Android | Kotlin, custom View, existing repo, new branch | the user's repo, kept intact on `main` |

No server, no database, no accounts. Nothing to hack or pay for at zero users.

## Layers
```
engine  (rules, scoring, generator, rescue, serialise)   <- no UI, no clock, seeded RNG
state   (settings, best score, saved game)                 <- storage adapter
ui      (render, drag, animation, sound, haptics)          <- reads engine, never changes rules
```
Rule: the UI never decides anything about the rules. It asks the engine and draws the answer.

## Data model
GameState = { board[64], tray[3], score, streak, since, rescueUsed, over, rng }.
Board cell: 0 empty, 1..N colour id. Piece: id, cells, colour.
Saved game = GameState as JSON (version field, so old saves can be ignored safely).

## Scoring (our numbers)
- Placement: 1 point per cell.
- Lines: 10 * n * (n + 1) / 2 for n lines cleared at once.
- Combo: each clear raises the streak; the streak resets after 3 placements without a clear. Bonus = (streak - 1) * 10 * lines.
- All clear: +300.

## Fair generator
1. Draw 3 shapes by weight (weights shift toward big or awkward shapes as score rises).
2. Check the tray is solvable: some order of the 3 pieces can all be placed in turn (clears simulated). Search is budgeted.
3. Retry up to 40 times, then fall back to small shapes. A tray is never dealt that is dead on arrival.

## Rescue
Once per game: clear the central 4x4 block and deal 3 small pieces. On Android this is gated by a rewarded ad that must report "earned" first; on web it is free once.

## Build order (vertical slice first)
1. engine + tests (done before any UI)
2. canvas board, drag, ghost preview, clear
3. score, combo, game over, retry, save
4. juice (animation, sound, haptics), skins, settings
5. Kotlin port of engine, then UI, then ads
