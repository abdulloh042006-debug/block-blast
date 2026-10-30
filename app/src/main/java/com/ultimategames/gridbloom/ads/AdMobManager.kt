package com.ultimategames.gridbloom.ads

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowMetrics
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-ready, policy-compliant Google Mobile Ads manager.
 *
 * Guarantees:
 * - Single SDK initialization (`MobileAds.initialize`) after consent gathering.
 * - Preloading with 1-hour cache expiration per official AdMob documentation.
 * - Strict idempotency on Rewarded Ad callbacks (`AtomicBoolean` per ad instance) so rewards
 *   can never be granted twice or granted when an ad is skipped/fails.
 * - Interstitial frequency capping via [InterstitialFrequencyCapper].
 * - Adaptive Banner loading only when screen height >= [MIN_SCREEN_HEIGHT_DP_FOR_BANNER] (640dp)
 *   so the 8x8 board and 3-piece tray are never cramped or overlapped.
 */
class AdMobManager(
    private val config: AdMobEnvironmentConfig,
    val analytics: MonetizationAnalytics = MonetizationAnalytics(),
    val frequencyCapper: InterstitialFrequencyCapper = InterstitialFrequencyCapper()
) {
    private val sdkInitialized = AtomicBoolean(false)
    private var adsDisabledDueToInvalidReleaseConfig: Boolean = false

    private var rewardedAd: RewardedAd? = null
    private var rewardedLoadedAtMillis: Long = 0L
    private var isLoadingRewarded: Boolean = false

    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoadedAtMillis: Long = 0L
    private var isLoadingInterstitial: Boolean = false
    private var isShowingFullScreenAd: Boolean = false
    private var rewardedWatchedInCurrentBreak: Boolean = false

    private var bannerAdView: AdView? = null

    init {
        if (!config.isDebugBuild) {
            val report = AdMobConfig.validateReleaseConfig(config)
            if (!report.isValidForProduction) {
                adsDisabledDueToInvalidReleaseConfig = true
                analytics.logEvent(
                    eventType = MonetizationEvent.EventType.RELEASE_CONFIG_VALIDATION_FAILED,
                    adFormat = "ALL",
                    metadata = mapOf("violations" to report.violations.joinToString("; "))
                )
            }
        }
    }

    /**
     * Initializes the Google Mobile Ads SDK once after UMP consent gathering completes.
     */
    fun initializeIfPermitted(
        context: Context,
        canRequestAds: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        frequencyCapper.onAppLaunch(nowMillis)
        if (adsDisabledDueToInvalidReleaseConfig || !canRequestAds) {
            return
        }
        if (sdkInitialized.compareAndSet(false, true)) {
            MobileAds.initialize(context.applicationContext) {
                preloadRewardedAd(context.applicationContext)
                preloadInterstitialAd(context.applicationContext)
            }
        } else {
            preloadRewardedAd(context.applicationContext)
            preloadInterstitialAd(context.applicationContext)
        }
    }

    fun isRewardedAdReady(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val ad = rewardedAd ?: return false
        if (nowMillis - rewardedLoadedAtMillis >= AD_CACHE_EXPIRATION_MILLIS) {
            rewardedAd = null
            return false
        }
        return !isShowingFullScreenAd
    }

    fun preloadRewardedAd(context: Context) {
        if (adsDisabledDueToInvalidReleaseConfig || !sdkInitialized.get()) return
        if (isLoadingRewarded || isRewardedAdReady()) return

        isLoadingRewarded = true
        analytics.logEvent(MonetizationEvent.EventType.AD_REQUEST, "REWARDED")

        RewardedAd.load(
            context.applicationContext,
            config.rewardedAdUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoadingRewarded = false
                    rewardedAd = ad
                    rewardedLoadedAtMillis = System.currentTimeMillis()
                    analytics.logEvent(MonetizationEvent.EventType.AD_LOAD_SUCCESS, "REWARDED")
                }

                override fun onAdFailedToLoad(loadError: LoadAdError) {
                    isLoadingRewarded = false
                    rewardedAd = null
                    analytics.logEvent(
                        eventType = MonetizationEvent.EventType.AD_LOAD_FAILURE,
                        adFormat = "REWARDED",
                        metadata = mapOf("code" to loadError.code.toString(), "message" to loadError.message)
                    )
                }
            }
        )
    }

    /**
     * Shows the optional Rewarded Ad when the player loses and chooses "Watch Ad to Continue".
     * Grants the continuation reward AT MOST ONCE via [OnUserEarnedRewardListener], and invokes
     * [onAdClosed] when the fullscreen content is dismissed.
     */
    fun showRewardedAd(
        activity: Activity,
        onRewardEarnedOnce: () -> Unit,
        onAdClosed: (rewardGranted: Boolean) -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null || !isRewardedAdReady() || isShowingFullScreenAd) {
            preloadRewardedAd(activity.applicationContext)
            onAdClosed(false)
            return
        }

        rewardedAd = null
        isShowingFullScreenAd = true
        val rewardGrantedGuard = AtomicBoolean(false)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                analytics.logEvent(MonetizationEvent.EventType.AD_IMPRESSION, "REWARDED")
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                isShowingFullScreenAd = false
                preloadRewardedAd(activity.applicationContext)
                onAdClosed(rewardGrantedGuard.get())
            }

            override fun onAdDismissedFullScreenContent() {
                isShowingFullScreenAd = false
                analytics.logEvent(MonetizationEvent.EventType.AD_DISMISSED, "REWARDED")
                preloadRewardedAd(activity.applicationContext)
                onAdClosed(rewardGrantedGuard.get())
            }
        }

        ad.show(
            activity,
            OnUserEarnedRewardListener { rewardItem ->
                if (rewardGrantedGuard.compareAndSet(false, true)) {
                    rewardedWatchedInCurrentBreak = true
                    analytics.logEvent(
                        eventType = MonetizationEvent.EventType.REWARD_EARNED,
                        adFormat = "REWARDED",
                        metadata = mapOf(
                            "type" to rewardItem.type,
                            "amount" to rewardItem.amount.toString()
                        )
                    )
                    onRewardEarnedOnce()
                } else {
                    analytics.logEvent(MonetizationEvent.EventType.DUPLICATE_REWARD_BLOCKED, "REWARDED")
                }
            }
        )
    }

    fun preloadInterstitialAd(context: Context) {
        if (adsDisabledDueToInvalidReleaseConfig || !sdkInitialized.get()) return
        if (isLoadingInterstitial || isInterstitialReady()) return

        isLoadingInterstitial = true
        analytics.logEvent(MonetizationEvent.EventType.AD_REQUEST, "INTERSTITIAL")

        InterstitialAd.load(
            context.applicationContext,
            config.interstitialAdUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoadingInterstitial = false
                    interstitialAd = ad
                    interstitialLoadedAtMillis = System.currentTimeMillis()
                    analytics.logEvent(MonetizationEvent.EventType.AD_LOAD_SUCCESS, "INTERSTITIAL")
                }

                override fun onAdFailedToLoad(loadError: LoadAdError) {
                    isLoadingInterstitial = false
                    interstitialAd = null
                    analytics.logEvent(
                        eventType = MonetizationEvent.EventType.AD_LOAD_FAILURE,
                        adFormat = "INTERSTITIAL",
                        metadata = mapOf("code" to loadError.code.toString(), "message" to loadError.message)
                    )
                }
            }
        )
    }

    fun isInterstitialReady(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val ad = interstitialAd ?: return false
        if (nowMillis - interstitialLoadedAtMillis >= AD_CACHE_EXPIRATION_MILLIS) {
            interstitialAd = null
            return false
        }
        return !isShowingFullScreenAd
    }

    fun onGameOverRecorded() {
        frequencyCapper.recordGameCompleted()
        rewardedWatchedInCurrentBreak = false
    }

    /**
     * Attempts to display an Interstitial Ad strictly at a natural break (after Game Over and
     * right before starting a new game session). If frequency caps block the ad or no ad is loaded,
     * invokes [onProceedToNewGame] immediately with zero delay.
     */
    fun maybeShowInterstitialBeforeNewGame(
        activity: Activity,
        isDraggingOrAnimating: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        onProceedToNewGame: () -> Unit
    ) {
        val gate = frequencyCapper.evaluateEligibility(
            nowMillis = nowMillis,
            isAtNaturalBreakBetweenGames = true,
            isDraggingOrAnimating = isDraggingOrAnimating,
            justCompletedRewardedAdInThisBreak = rewardedWatchedInCurrentBreak
        )

        if (!gate.allowed) {
            analytics.logEvent(
                eventType = MonetizationEvent.EventType.INTERSTITIAL_CAP_REJECTED,
                adFormat = "INTERSTITIAL",
                metadata = mapOf("reason" to gate.reason)
            )
            onProceedToNewGame()
            return
        }

        val ad = interstitialAd
        if (ad == null || !isInterstitialReady(nowMillis) || isShowingFullScreenAd) {
            preloadInterstitialAd(activity.applicationContext)
            onProceedToNewGame()
            return
        }

        interstitialAd = null
        isShowingFullScreenAd = true
        frequencyCapper.recordInterstitialDisplayed(nowMillis)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                analytics.logEvent(MonetizationEvent.EventType.AD_IMPRESSION, "INTERSTITIAL")
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                isShowingFullScreenAd = false
                preloadInterstitialAd(activity.applicationContext)
                onProceedToNewGame()
            }

            override fun onAdDismissedFullScreenContent() {
                isShowingFullScreenAd = false
                analytics.logEvent(MonetizationEvent.EventType.AD_DISMISSED, "INTERSTITIAL")
                preloadInterstitialAd(activity.applicationContext)
                onProceedToNewGame()
            }
        }

        ad.show(activity)
    }

    /**
     * Attaches an Anchored Adaptive Banner inside [bannerContainer] ONLY if the screen has
     * sufficient vertical height (>= [MIN_SCREEN_HEIGHT_DP_FOR_BANNER]) so the 8x8 board and
     * 3-piece tray are never compromised.
     */
    fun attachAdaptiveBannerIfSpacePermits(
        activity: Activity,
        bannerContainer: ViewGroup,
        screenHeightDp: Int
    ): Boolean {
        if (adsDisabledDueToInvalidReleaseConfig || !sdkInitialized.get()) {
            bannerContainer.removeAllViews()
            return false
        }
        if (screenHeightDp < MIN_SCREEN_HEIGHT_DP_FOR_BANNER) {
            bannerContainer.removeAllViews()
            return false
        }

        destroyBanner()
        val adView = AdView(activity)
        adView.adUnitId = config.bannerAdUnitId
        adView.setAdSize(computeAdaptiveBannerSize(activity, bannerContainer))
        bannerContainer.removeAllViews()
        bannerContainer.addView(adView)
        bannerAdView = adView

        analytics.logEvent(MonetizationEvent.EventType.AD_REQUEST, "BANNER")
        adView.loadAd(AdRequest.Builder().build())
        return true
    }

    fun pauseBanner() {
        bannerAdView?.pause()
    }

    fun resumeBanner() {
        bannerAdView?.resume()
    }

    fun destroyBanner() {
        bannerAdView?.destroy()
        bannerAdView = null
    }

    fun releaseAll() {
        destroyBanner()
        rewardedAd = null
        interstitialAd = null
        isShowingFullScreenAd = false
    }

    private fun computeAdaptiveBannerSize(activity: Activity, container: ViewGroup): AdSize {
        val density = activity.resources.displayMetrics.density
        var adWidthPixels = container.width.toFloat()
        if (adWidthPixels <= 0f) {
            adWidthPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics: WindowMetrics = activity.windowManager.currentWindowMetrics
                windowMetrics.bounds.width().toFloat()
            } else {
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.widthPixels.toFloat()
            }
        }
        val adWidthDp = (adWidthPixels / density).toInt().coerceAtLeast(320)
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp)
    }

    companion object {
        const val AD_CACHE_EXPIRATION_MILLIS = 3_600_000L // 1 hour per Google AdMob spec
        const val MIN_SCREEN_HEIGHT_DP_FOR_BANNER = 640
    }
}
