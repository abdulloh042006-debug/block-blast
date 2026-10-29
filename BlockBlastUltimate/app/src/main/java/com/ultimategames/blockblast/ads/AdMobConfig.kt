package com.ultimategames.blockblast.ads

/**
 * Centralized Google AdMob configuration and release-readiness validator.
 *
 * Official Google Test IDs for Android:
 * - App ID:          ca-app-pub-3940256099942544~3347511713
 * - Rewarded Unit:   ca-app-pub-3940256099942544/5224354917
 * - Interstitial:    ca-app-pub-3940256099942544/1033173712
 * - Adaptive Banner: ca-app-pub-3940256099942544/9214589741
 *
 * Ref: https://developers.google.com/admob/android/test-ads
 */
data class AdMobEnvironmentConfig(
    val isDebugBuild: Boolean,
    val isTestAdMode: Boolean,
    val appId: String,
    val rewardedAdUnitId: String,
    val interstitialAdUnitId: String,
    val bannerAdUnitId: String,
    val privacyPolicyUrl: String
)

data class ReleaseValidationReport(
    val isValidForProduction: Boolean,
    val violations: List<String>
)

object AdMobConfig {

    const val GOOGLE_TEST_PUBLISHER_PREFIX = "ca-app-pub-3940256099942544"
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"

    private val APP_ID_REGEX = Regex("^ca-app-pub-\\d{16}~\\d{10}$")
    private val AD_UNIT_ID_REGEX = Regex("^ca-app-pub-\\d{16}/\\d{10}$")

    /**
     * Returns the safe debug configuration guaranteed to use only Google's demo test ad units.
     * Debug builds NEVER fall back to live production ad units.
     */
    fun debugConfig(privacyPolicyUrl: String = "https://example.com/privacy-policy"): AdMobEnvironmentConfig {
        return AdMobEnvironmentConfig(
            isDebugBuild = true,
            isTestAdMode = true,
            appId = TEST_APP_ID,
            rewardedAdUnitId = TEST_REWARDED_UNIT_ID,
            interstitialAdUnitId = TEST_INTERSTITIAL_UNIT_ID,
            bannerAdUnitId = TEST_BANNER_UNIT_ID,
            privacyPolicyUrl = privacyPolicyUrl
        )
    }

    /**
     * Validates that a Release configuration does not contain Google test IDs, `REPLACE_WITH_*` placeholders,
     * malformed AdMob IDs, or an unconfigured privacy policy URL.
     * If validation fails in a release build, ad requests are safely disabled rather than serving
     * misconfigured or policy-violating traffic.
     */
    fun validateReleaseConfig(config: AdMobEnvironmentConfig): ReleaseValidationReport {
        val violations = mutableListOf<String>()

        if (config.isDebugBuild || config.isTestAdMode) {
            violations.add("Release configuration must have isDebugBuild=false and isTestAdMode=false.")
        }

        if (config.appId.startsWith("REPLACE_WITH") || !APP_ID_REGEX.matches(config.appId)) {
            violations.add("Invalid or placeholder AdMob App ID: '${config.appId}'. Expected ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY.")
        } else if (config.appId.startsWith(GOOGLE_TEST_PUBLISHER_PREFIX)) {
            violations.add("Release build contains Google Test App ID (${config.appId}).")
        }

        fun checkAdUnit(label: String, unitId: String) {
            if (unitId.startsWith("REPLACE_WITH") || !AD_UNIT_ID_REGEX.matches(unitId)) {
                violations.add("Invalid or placeholder $label Ad Unit ID: '$unitId'.")
            } else if (unitId.startsWith(GOOGLE_TEST_PUBLISHER_PREFIX)) {
                violations.add("Release build contains Google Test $label Ad Unit ID ($unitId).")
            }
        }

        checkAdUnit("Rewarded", config.rewardedAdUnitId)
        checkAdUnit("Interstitial", config.interstitialAdUnitId)
        checkAdUnit("Banner", config.bannerAdUnitId)

        if (config.privacyPolicyUrl.isBlank() ||
            config.privacyPolicyUrl.contains("example.com") ||
            config.privacyPolicyUrl.contains("yourdomain.com") ||
            !config.privacyPolicyUrl.startsWith("https://")
        ) {
            violations.add("Release build requires a valid HTTPS production Privacy Policy URL, got '${config.privacyPolicyUrl}'.")
        }

        return ReleaseValidationReport(
            isValidForProduction = violations.isEmpty(),
            violations = violations
        )
    }
}
