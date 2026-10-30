# Gridbloom

An 8×8 block-puzzle game. Drag three pieces onto the grid, clear full rows and columns, chain combos, and keep going until nothing fits.

This is an original game with its own name, look, sounds and scoring. It rebuilds the *genre's rules* (which belong to nobody), not any other game's art, music, code or brand.

Two targets, one game:

| target | what it is | how it is checked |
| --- | --- | --- |
| **Web** (`web/`) | the whole game: rules, drawing, sound, saving | 34 Node rule tests + 30 headless-browser checks |
| **Android** (`app/`) | a thin Kotlin host that shows the web game and runs the ads | host/page contract test; **not compiled in the environment that wrote it** |

## Play in a browser

Open `dist/gridbloom.html` (one self-contained file, no network). Rebuild it after editing `web/`:

```bash
node build-web.js
```

## Tests

```bash
node web-tests/engine.test.js            # rules, scoring, generator, rescue, save/restore, 300 random games
python3 web-tests/ui_smoke.py dist/gridbloom.html   # drag, touch, clear, game over, settings, layouts (needs: pip install playwright)
python3 web-tests/ui_native.py dist/gridbloom.html  # the Android bridge, against a mock host
./gradlew testDebugUnitTest              # Android: ads tests + BundledGameAssetTest
```

## How the game works

- 8×8 grid, a tray of 3 pieces, no rotation, 37 piece shapes.
- Full rows and columns clear together. Score: 1 point per cell, `10·n·(n+1)/2` for `n` lines at once, combo bonus while you keep clearing (the streak ends after 3 placements without a clear), +300 for an empty board.
- The piece generator never deals a tray that is dead on arrival: it checks that some order of the three can all be placed. It gets harder as your score rises.
- One free "second chance" per game (on Android it is gated by a rewarded ad that must finish first).
- Four themes, sound (synthesised, no audio files), vibration, resume after closing.

## Layout

```
web/engine.js     rules only, no DOM, seeded RNG (shared by tests and the browser)
web/ui.js         canvas, drag, effects, sound, settings, storage, optional Android bridge
web/index.html    the page
web-tests/        engine tests, browser tests, bridge tests
replica/          recon map, feature matrix, architecture, parity report
app/              Android host (WebView) + AdMob/UMP modules (ads/)
build-web.js      inlines web/ into one HTML file and copies it into app assets
```

## Android notes

`MainActivity` loads the bundled page through `WebViewAssetLoader` (no network, all other navigation blocked) and exposes five methods to it: `isRewardedReady`, `requestRescue`, `beforeNewGame`, `onGameOver`, `openPrivacy`. R8 keep rules for them are in `proguard-rules.pro`. `BundledGameAssetTest` fails if the page and the activity ever disagree about these names.

Requirements: JDK 17, Android SDK 35, AGP 8.5.2, Kotlin 1.9.24.

```bash
./gradlew assembleDebug     # debug build uses Google's official test ad IDs only
```

Open it in Android Studio and run it on a device before trusting it: the ad flow (rewarded rescue, interstitial between games, banner, consent form) has only been tested through a mock.

### AdMob production IDs (release builds)

Debug builds use Google's test IDs. For release, provide your own through `~/.gradle/gradle.properties` or environment variables, never in source control:

```properties
PROD_ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
PROD_ADMOB_REWARDED_ID=ca-app-pub-XXXXXXXXXXXXXXXX/RRRRRRRRRR
PROD_ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXXXXXXXXXX/IIIIIIIIII
PROD_ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/BBBBBBBBBB
PROD_PRIVACY_POLICY_URL=https://yourdomain.com/privacy-policy
```

`AdMobConfig.validateReleaseConfig()` turns ads off in a release build that still has placeholder or test IDs.

## Before you publish

- Pick a final name and check it (store search, trademark, domain). "Gridbloom" is a working name.
- Make an icon of your own (the manifest still points at the system default).
- Host a privacy policy and set `PROD_PRIVACY_POLICY_URL`.
- Run `python3 ~/.claude/skills/replica-brand/sweep.py . --avoid "<original's name>"` until it is clean.
