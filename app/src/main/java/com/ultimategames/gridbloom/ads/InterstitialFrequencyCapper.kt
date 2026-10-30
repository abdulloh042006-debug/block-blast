package com.ultimategames.gridbloom.ads

/**
 * Policy-compliant frequency capper for Interstitial Ads.
 *
 * Enforces strict placement and timing rules:
 * 1. Shown ONLY at natural session breaks (after a completed game and before starting the next session).
 * 2. Never shown on app launch or app exit.
 * 3. Never shown during active gameplay, drag-and-drop gestures, or line-clear animations.
 * 4. Requires at least [minCompletedGamesBetweenAds] (default: 3) completed games since the last interstitial.
 * 5. Requires at least [minIntervalMillis] (default: 180,000 ms = 3 minutes) elapsed since the last interstitial
 *    and at least [minSessionWarmupMillis] (default: 45,000 ms) since app launch.
 * 6. Suppresses an interstitial on the exact transition where the user just watched a Rewarded Ad,
 *    preventing back-to-back ad fatigue.
 */
class InterstitialFrequencyCapper(
    val minCompletedGamesBetweenAds: Int = 3,
    val minIntervalMillis: Long = 180_000L,
    val minSessionWarmupMillis: Long = 45_000L
) {
    var completedGamesSinceLastAd: Int = 0
        private set

    var lastInterstitialShownAtMillis: Long = 0L
        private set

    private var appLaunchTimeMillis: Long = 0L

    fun onAppLaunch(nowMillis: Long) {
        appLaunchTimeMillis = nowMillis
    }

    fun recordGameCompleted() {
        completedGamesSinceLastAd += 1
    }

    enum class GateDecision(val allowed: Boolean, val reason: String) {
        ALLOWED(true, "ELIGIBLE_NATURAL_BREAK"),
        BLOCKED_NOT_NATURAL_BREAK(false, "NOT_AT_NATURAL_GAME_BREAK"),
        BLOCKED_ACTIVE_INTERACTION_OR_ANIMATION(false, "ACTIVE_DRAG_OR_ANIMATION"),
        BLOCKED_LAUNCH_WARMUP(false, "LAUNCH_WARMUP_NOT_ELAPSED"),
        BLOCKED_REWARDED_JUST_WATCHED(false, "REWARDED_AD_RECENTLY_WATCHED"),
        BLOCKED_GAME_COUNT_CAP(false, "COMPLETED_GAMES_BELOW_CAP"),
        BLOCKED_TIME_COOLDOWN(false, "MINIMUM_TIME_INTERVAL_NOT_ELAPSED")
    }

    fun evaluateEligibility(
        nowMillis: Long,
        isAtNaturalBreakBetweenGames: Boolean,
        isDraggingOrAnimating: Boolean,
        justCompletedRewardedAdInThisBreak: Boolean
    ): GateDecision {
        if (isDraggingOrAnimating) {
            return GateDecision.BLOCKED_ACTIVE_INTERACTION_OR_ANIMATION
        }
        if (!isAtNaturalBreakBetweenGames) {
            return GateDecision.BLOCKED_NOT_NATURAL_BREAK
        }
        if (justCompletedRewardedAdInThisBreak) {
            return GateDecision.BLOCKED_REWARDED_JUST_WATCHED
        }
        if (nowMillis - appLaunchTimeMillis < minSessionWarmupMillis) {
            return GateDecision.BLOCKED_LAUNCH_WARMUP
        }
        if (completedGamesSinceLastAd < minCompletedGamesBetweenAds) {
            return GateDecision.BLOCKED_GAME_COUNT_CAP
        }
        if (lastInterstitialShownAtMillis > 0L &&
            (nowMillis - lastInterstitialShownAtMillis) < minIntervalMillis
        ) {
            return GateDecision.BLOCKED_TIME_COOLDOWN
        }
        return GateDecision.ALLOWED
    }

    fun recordInterstitialDisplayed(nowMillis: Long) {
        completedGamesSinceLastAd = 0
        lastInterstitialShownAtMillis = nowMillis
    }
}
