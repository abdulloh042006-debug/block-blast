package com.ultimategames.blockblast

import com.ultimategames.blockblast.ads.AdMobConfig
import com.ultimategames.blockblast.ads.AdMobEnvironmentConfig
import com.ultimategames.blockblast.ads.InterstitialFrequencyCapper
import com.ultimategames.blockblast.ads.MonetizationAnalytics
import com.ultimategames.blockblast.ads.MonetizationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying Google AdMob monetization rules, debug/release safety,
 * frequency capping, and analytics logging.
 */
class AdMobMonetizationTest {

    @Test
    fun testDebugConfigUsesOfficialGoogleTestIds() {
        val debug = AdMobConfig.debugConfig()
        assertTrue(debug.isDebugBuild)
        assertTrue(debug.isTestAdMode)
        assertEquals("ca-app-pub-3940256099942544~3347511713", debug.appId)
        assertEquals("ca-app-pub-3940256099942544/5224354917", debug.rewardedAdUnitId)
        assertEquals("ca-app-pub-3940256099942544/1033173712", debug.interstitialAdUnitId)
        assertEquals("ca-app-pub-3940256099942544/9214589741", debug.bannerAdUnitId)
    }

    @Test
    fun testReleaseValidatorRejectsPlaceholdersAndTestIds() {
        val invalidReleaseWithTestIds = AdMobConfig.debugConfig().copy(
            isDebugBuild = false,
            isTestAdMode = false
        )
        val report1 = AdMobConfig.validateReleaseConfig(invalidReleaseWithTestIds)
        assertFalse(report1.isValidForProduction)
        assertTrue(report1.violations.isNotEmpty())

        val validRelease = AdMobEnvironmentConfig(
            isDebugBuild = false,
            isTestAdMode = false,
            appId = "ca-app-pub-1234567890123456~1234567890",
            rewardedAdUnitId = "ca-app-pub-1234567890123456/1111111111",
            interstitialAdUnitId = "ca-app-pub-1234567890123456/2222222222",
            bannerAdUnitId = "ca-app-pub-1234567890123456/3333333333",
            privacyPolicyUrl = "https://ultimategames.studio/blockblast/privacy"
        )
        val report2 = AdMobConfig.validateReleaseConfig(validRelease)
        assertTrue(report2.isValidForProduction)
        assertTrue(report2.violations.isEmpty())
    }

    @Test
    fun testInterstitialFrequencyCapperEnforcesAllPolicyRules() {
        val capper = InterstitialFrequencyCapper(
            minCompletedGamesBetweenAds = 3,
            minIntervalMillis = 180_000L,
            minSessionWarmupMillis = 45_000L
        )
        val launchTime = 1_000_000L
        capper.onAppLaunch(launchTime)

        // 1. Blocked on launch or before 3 games completed
        capper.recordGameCompleted()
        assertEquals(
            InterstitialFrequencyCapper.GateDecision.BLOCKED_GAME_COUNT_CAP,
            capper.evaluateEligibility(
                nowMillis = launchTime + 60_000L,
                isAtNaturalBreakBetweenGames = true,
                isDraggingOrAnimating = false,
                justCompletedRewardedAdInThisBreak = false
            )
        )

        // Complete 2nd and 3rd games
        capper.recordGameCompleted()
        capper.recordGameCompleted()

        // 2. Blocked if user is actively dragging or animating
        assertEquals(
            InterstitialFrequencyCapper.GateDecision.BLOCKED_ACTIVE_INTERACTION_OR_ANIMATION,
            capper.evaluateEligibility(
                nowMillis = launchTime + 60_000L,
                isAtNaturalBreakBetweenGames = true,
                isDraggingOrAnimating = true,
                justCompletedRewardedAdInThisBreak = false
            )
        )

        // 3. Blocked if user just watched a Rewarded Ad during this break
        assertEquals(
            InterstitialFrequencyCapper.GateDecision.BLOCKED_REWARDED_JUST_WATCHED,
            capper.evaluateEligibility(
                nowMillis = launchTime + 60_000L,
                isAtNaturalBreakBetweenGames = true,
                isDraggingOrAnimating = false,
                justCompletedRewardedAdInThisBreak = true
            )
        )

        // 4. Allowed at natural break after 3 completed games and warmup elapsed
        val adShowTime = launchTime + 60_000L
        assertEquals(
            InterstitialFrequencyCapper.GateDecision.ALLOWED,
            capper.evaluateEligibility(
                nowMillis = adShowTime,
                isAtNaturalBreakBetweenGames = true,
                isDraggingOrAnimating = false,
                justCompletedRewardedAdInThisBreak = false
            )
        )
        capper.recordInterstitialDisplayed(adShowTime)

        // 5. Blocked by cooldown even after 3 more rapid games if < 180s elapsed
        capper.recordGameCompleted()
        capper.recordGameCompleted()
        capper.recordGameCompleted()
        assertEquals(
            InterstitialFrequencyCapper.GateDecision.BLOCKED_TIME_COOLDOWN,
            capper.evaluateEligibility(
                nowMillis = adShowTime + 90_000L,
                isAtNaturalBreakBetweenGames = true,
                isDraggingOrAnimating = false,
                justCompletedRewardedAdInThisBreak = false
            )
        )

        // 6. Allowed once 180s cooldown has elapsed
        assertEquals(
            InterstitialFrequencyCapper.GateDecision.ALLOWED,
            capper.evaluateEligibility(
                nowMillis = adShowTime + 185_000L,
                isAtNaturalBreakBetweenGames = true,
                isDraggingOrAnimating = false,
                justCompletedRewardedAdInThisBreak = false
            )
        )
    }

    @Test
    fun testMonetizationAnalyticsEventLogging() {
        val analytics = MonetizationAnalytics(maxHistorySize = 10)
        analytics.logEvent(MonetizationEvent.EventType.AD_REQUEST, "REWARDED")
        analytics.logEvent(MonetizationEvent.EventType.AD_LOAD_SUCCESS, "REWARDED")
        analytics.logEvent(MonetizationEvent.EventType.AD_IMPRESSION, "REWARDED")
        analytics.logEvent(MonetizationEvent.EventType.REWARD_EARNED, "REWARDED")

        assertEquals(4, analytics.getRecordedEvents().size)
        assertEquals(1, analytics.countByType(MonetizationEvent.EventType.REWARD_EARNED))
    }
}
