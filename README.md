# Block Blast Ultimate — Native Android (Kotlin)

An independently engineered, quality-first Native Android 8×8 polyomino puzzle game inspired by Block Blast (`com.block.juggle`), synthesizing architectural lessons from multi-repository analysis (`ZLouisMiguel/block-blast` and `tokaa1/blockerino`, with unavailable `MrCerise/block-blast` documented) and integrating a policy-compliant Google AdMob + User Messaging Platform (UMP) monetization system.

## 1. Architecture & Module Structure

```
BlockBlastUltimate/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/ultimategames/blockblast/
        │   │   ├── domain/
        │   │   │   ├── BlockShape.kt            # 22 canonical polyomino shapes & semantic beveled colors
        │   │   │   ├── Board.kt                 # Immutable 8x8 grid & simultaneous row/col clearing
        │   │   │   ├── ScoreEngine.kt           # Triangular multi-line clear & combo streak math
        │   │   │   ├── SeededPieceGenerator.kt  # Deterministic XorShift64 PRNG & 3-piece triad generator
        │   │   │   ├── ContinuationEngine.kt    # Rewarded Ad single-use rescue continuation logic
        │   │   │   └── GameEngine.kt            # Authoritative state machine & duplicate-action guard
        │   │   ├── data/
        │   │   │   └── GameRepository.kt        # SharedPreferences + JSON GameState persistence
        │   │   ├── audio/
        │   │   │   └── SoundEffectsManager.kt   # Zero-clipping procedural PCM AudioTrack synthesizer
        │   │   ├── ads/
        │   │   │   ├── AdMobConfig.kt           # Debug/Release config & ReleaseValidationReport
        │   │   │   ├── ConsentManager.kt        # Google UMP SDK v4.0.0 consent & privacy options
        │   │   │   ├── InterstitialFrequencyCapper.kt # Natural-break, 3-game, & 180s cooldown capper
        │   │   │   ├── MonetizationAnalytics.kt # Privacy-safe ring-buffer event logger
        │   │   │   └── AdMobManager.kt          # Rewarded, Interstitial, & Adaptive Banner controller
        │   │   └── ui/
        │   │       ├── BlockBlastBoardView.kt   # Zero-alloc Canvas 8x8 board, drag-and-drop & tap-to-place
        │   │       └── MainActivity.kt          # Portrait activity, WindowInsets, HUD & modals
        │   └── res/values/
        │       ├── colors.xml
        │       ├── strings.xml
        │       └── themes.xml
        └── test/java/com/ultimategames/blockblast/
            ├── BlockBlastGameTest.kt            # 15 core gameplay unit tests
            └── AdMobMonetizationTest.kt         # AdMob config, frequency cap, & analytics tests
```

## 2. Reproducible Build & Test Instructions

### Prerequisites
- JDK 17 (`JavaVersion.VERSION_17`)
- Android SDK Platform 35 (`compileSdk = 35`, `minSdk = 23`, `targetSdk = 35`)
- Android Gradle Plugin `8.5.2` and Kotlin `1.9.24`

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Build Debug APK (Uses Official Google Test Ad Units Only)
```bash
./gradlew assembleDebug
# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

### Run Android Lint Static Analysis
```bash
./gradlew lintDebug
```

## 3. Google AdMob & UMP Privacy Configuration Guide

### A. Official Google Test IDs (Configured Automatically in `debug` Build Type)
| Format | Official Google Test ID | Location |
| :--- | :--- | :--- |
| **Sample App ID** | `ca-app-pub-3940256099942544~3347511713` | `app/build.gradle.kts` (`debug.manifestPlaceholders`) |
| **Rewarded Ad Unit** | `ca-app-pub-3940256099942544/5224354917` | `BuildConfig.ADMOB_REWARDED_UNIT_ID` |
| **Interstitial Ad Unit** | `ca-app-pub-3940256099942544/1033173712` | `BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID` |
| **Adaptive Banner Unit** | `ca-app-pub-3940256099942544/9214589741` | `BuildConfig.ADMOB_BANNER_UNIT_ID` |

### B. Configuring Production IDs for Release Builds
Never hardcode production secrets into source control. Supply your verified AdMob IDs via `~/.gradle/gradle.properties` or CI environment variables:
```properties
PROD_ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
PROD_ADMOB_REWARDED_ID=ca-app-pub-XXXXXXXXXXXXXXXX/RRRRRRRRRR
PROD_ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXXXXXXXXXX/IIIIIIIIII
PROD_ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/BBBBBBBBBB
PROD_PRIVACY_POLICY_URL=https://yourdomain.com/privacy-policy
```
`AdMobConfig.validateReleaseConfig()` automatically audits release builds at startup and disables ad requests if any placeholder (`REPLACE_WITH_*`) or Google test ID (`ca-app-pub-3940256099942544`) is detected in a release binary.
