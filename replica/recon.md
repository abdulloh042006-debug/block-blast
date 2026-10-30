# Recon: 8x8 block-puzzle (the "Block Blast" genre)

Working name for our version: **Gridbloom** (own name, own look; final name goes through replica-brand).

## Scope
- App: the casual 8x8 block-puzzle genre as played in Block Blast (Hungry Studio, Android/iOS).
- Platform: web first (HTML/JS), then Kotlin Android port in the user's repo.
- Slice: the core loop only. Place pieces, clear lines, chase combos, die, retry.
- Audience: the user's own game, to publish and monetise.
- Sources: genre mechanics are public and widely documented (store listing, strategy guides, the user's own prior repo notes). Search results this session were mostly low-quality, so scoring numbers that are not confirmed are marked `guess`.
- Not used: the original's code, art, sounds, copy, name, logo.

## Core loop
1. A tray shows 3 pieces (polyominoes, no rotation).
2. Drag a piece onto the 8x8 grid. It must fit entirely on empty cells.
3. Any full row or column clears at once (rows and columns together count).
4. When all 3 are placed, a new tray of 3 appears.
5. The game ends when none of the tray's remaining pieces fits anywhere.
Happy path: about 3 drags per tray, 1 drag per piece.

## Screens
| ID | screen | how you get there | purpose | key components | states |
| --- | --- | --- | --- | --- | --- |
| S01 | Game | launch | the whole loop | board, tray, score, best score, combo banner | idle, dragging, clearing, game over |
| S02 | Game over | no move left | show result, retry | final score, best, new-best flag, retry button, rescue offer | normal, new best |
| S03 | Settings / pause | menu button | sound, haptics, theme | toggles, reset best | open, closed |
| S04 | Skins | menu | cosmetic themes | skin cards with locked/unlocked | locked, selected |

## Flows
- F01 Play a game: S01 -> (repeat place/clear) -> S02 -> retry -> S01. Edge: resume after app closed mid-game.
- F02 Rescue: S02 -> optional one-time rescue (rewarded ad on mobile, free once on web) -> S01.
- F03 Change skin: S01 -> S04 -> S01.
- F04 Toggle sound/haptics: S01 -> S03 -> S01.

## Components
Board cell (empty, filled by colour, ghost preview, clearing flash), tray piece (idle, dragging, unplayable dimmed), score counter (animated count-up), combo toast, button (primary, secondary, icon), modal, toggle.

## Mechanics (what the genre does)
| rule | value | confidence |
| --- | --- | --- |
| grid | 8x8 | high |
| tray | 3 pieces, refilled only when all 3 are used | high |
| rotation | none | high |
| clear | full row and/or column, simultaneous | high |
| game over | no remaining piece fits | high |
| placement score | 1 point per cell placed | guess (ours) |
| line score | grows faster than linear with simultaneous lines | high, exact numbers guess |
| combo | consecutive clears within a few placements raise a streak | high |
| all-clear | empty board gives a big bonus | high |
| fairness | generator avoids dealing an unwinnable tray when the board is tight | high (behaviour), method ours |

Our numbers are our own design choices (see architecture.md), not copied constants.

## Inferred data model
- GameState: board(64 ints), tray(3 shape ids or null), score, best, comboStreak, movesSinceClear, rescueUsed, gameOver, seed. evidence: needed to resume and to replay; confidence: high.
- Settings: sound, haptics, skin id. confidence: high.
- Stats: best score, games played, total lines. confidence: medium.

## Hard parts
1. Fair piece generator (never an instantly dead tray, still gets harder).
2. Drag feel on touch: finger offset so the piece is visible, snap preview.
3. Juice: clear animation, combo feedback, sound, haptics without assets.

## Out of scope (skip)
Original art, music, logo, name, store listing text, daily-challenge content, leaderboards (need a backend), seasonal events, licensed assets.

## Size
Web: S. Kotlin port: S-M. Total: M.
