package com.ultimategames.gridbloom.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.ultimategames.gridbloom.BuildConfig
import com.ultimategames.gridbloom.R
import com.ultimategames.gridbloom.ads.AdMobEnvironmentConfig
import com.ultimategames.gridbloom.ads.AdMobManager
import com.ultimategames.gridbloom.ads.ConsentManager

/**
 * Gridbloom host activity.
 *
 * The game itself (rules, drawing, sound, saving) is the tested web build bundled in
 * assets/gridbloom.html. This activity only does what the web cannot:
 *  - shows the page from the app's own assets (no network, no external navigation),
 *  - runs the rewarded "second chance" ad, the interstitial between games and the banner,
 *  - handles the consent form (UMP) and privacy options.
 *
 * Bridge contract with the page (names must match web/ui.js, checked by BundledGameAssetTest):
 *   isRewardedReady(), requestRescue(), beforeNewGame(), onGameOver(), openPrivacy()
 *   and the page callbacks window.__gbNative.rescueResult(granted) / proceedNewGame().
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bannerContainer: FrameLayout
    private lateinit var adMobManager: AdMobManager
    private lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val adConfig = AdMobEnvironmentConfig(
            isDebugBuild = BuildConfig.DEBUG,
            isTestAdMode = BuildConfig.IS_TEST_AD_MODE,
            appId = BuildConfig.ADMOB_APP_ID,
            rewardedAdUnitId = BuildConfig.ADMOB_REWARDED_UNIT_ID,
            interstitialAdUnitId = BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID,
            bannerAdUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID,
            privacyPolicyUrl = BuildConfig.PRIVACY_POLICY_URL
        )
        adMobManager = AdMobManager(config = adConfig)
        consentManager = ConsentManager(context = this, analytics = adMobManager.analytics)

        val background = ContextCompat.getColor(this, R.color.bg_deep_navy)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        webView = WebView(this).apply {
            setBackgroundColor(background)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        bannerContainer = FrameLayout(this)
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            bannerContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)

        // Keep the game clear of the status bar, navigation bar and display cutout.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setUpWebView()
        initializeConsentAndAds()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // localStorage keeps the saved game, best score and settings
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            // The game never links away. Block every navigation so the JS bridge is only
            // ever reachable from our own bundled page.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = true
        }

        webView.addJavascriptInterface(NativeBridge(), "GridbloomNative")
        webView.loadUrl(GAME_URL)
    }

    private fun initializeConsentAndAds() {
        consentManager.gatherConsent(
            activity = this,
            isDebugBuild = BuildConfig.DEBUG
        ) { canRequestAds, _ ->
            adMobManager.initializeIfPermitted(this, canRequestAds = canRequestAds)
            val screenHeightDp = resources.configuration.screenHeightDp
            bannerContainer.post {
                adMobManager.attachAdaptiveBannerIfSpacePermits(
                    activity = this,
                    bannerContainer = bannerContainer,
                    screenHeightDp = screenHeightDp
                )
            }
        }
    }

    private fun callPage(script: String) {
        if (!isFinishing && !isDestroyed) {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun showRewardedRescue() {
        adMobManager.showRewardedAd(
            activity = this,
            onRewardEarnedOnce = { /* the page is told once, when the ad closes */ },
            onAdClosed = { rewardGranted ->
                callPage("window.__gbNative && window.__gbNative.rescueResult($rewardGranted)")
            }
        )
    }

    private fun openPrivacyOptions() {
        if (consentManager.isPrivacyOptionsRequired) {
            consentManager.showPrivacyOptionsForm(this) {}
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
            } catch (_: Exception) {
                // No browser installed: nothing to do.
            }
        }
    }

    /** Methods the page may call. Web calls arrive on a background thread, so UI work is posted. */
    private inner class NativeBridge {
        @JavascriptInterface
        fun isRewardedReady(): Boolean = adMobManager.isRewardedAdReady()

        @JavascriptInterface
        fun requestRescue() {
            runOnUiThread { showRewardedRescue() }
        }

        @JavascriptInterface
        fun beforeNewGame() {
            runOnUiThread {
                adMobManager.maybeShowInterstitialBeforeNewGame(
                    activity = this@MainActivity,
                    isDraggingOrAnimating = false
                ) {
                    callPage("window.__gbNative && window.__gbNative.proceedNewGame()")
                }
            }
        }

        @JavascriptInterface
        fun onGameOver() {
            runOnUiThread { adMobManager.onGameOverRecorded() }
        }

        @JavascriptInterface
        fun openPrivacy() {
            runOnUiThread { openPrivacyOptions() }
        }
    }

    override fun onPause() {
        // Ask the page to save right now (it also saves on its own when hidden).
        callPage("window.dispatchEvent(new Event('pagehide'))")
        webView.onPause()
        adMobManager.pauseBanner()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        adMobManager.resumeBanner()
    }

    override fun onDestroy() {
        adMobManager.releaseAll()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val GAME_URL = "https://appassets.androidplatform.net/assets/gridbloom.html"
    }
}
