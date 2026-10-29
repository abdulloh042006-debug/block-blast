package com.ultimategames.blockblast.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Manages regional privacy and user consent using Google's User Messaging Platform (UMP) SDK v4.0.0.
 *
 * Ref: https://developers.google.com/admob/android/privacy
 */
class ConsentManager(
    context: Context,
    private val analytics: MonetizationAnalytics
) {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)

    val canRequestAds: Boolean
        get() = consentInformation.canRequestAds()

    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Requests updated consent information on app launch and displays the UMP consent form if required
     * (e.g., for users in the EEA, UK, or Switzerland).
     * Never blocks core offline gameplay if the network is unavailable or consent gathering fails.
     */
    fun gatherConsent(
        activity: Activity,
        isDebugBuild: Boolean,
        testDeviceHashedId: String? = null,
        onConsentGatheringComplete: (canRequestAds: Boolean, errorDescription: String?) -> Unit
    ) {
        val paramsBuilder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)

        if (isDebugBuild && !testDeviceHashedId.isNullOrBlank()) {
            val debugSettings = ConsentDebugSettings.Builder(activity)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .addTestDeviceHashedId(testDeviceHashedId)
                .build()
            paramsBuilder.setConsentDebugSettings(debugSettings)
        }

        val params = paramsBuilder.build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    val statusLabel = if (formError != null) {
                        "FORM_ERROR_${formError.errorCode}"
                    } else {
                        "CONSENT_OBTAINED_OR_NOT_REQUIRED"
                    }
                    analytics.logEvent(
                        eventType = MonetizationEvent.EventType.CONSENT_STATUS_UPDATED,
                        adFormat = "UMP",
                        metadata = mapOf(
                            "canRequestAds" to canRequestAds.toString(),
                            "status" to statusLabel
                        )
                    )
                    onConsentGatheringComplete(canRequestAds, formError?.message)
                }
            },
            { requestError ->
                analytics.logEvent(
                    eventType = MonetizationEvent.EventType.CONSENT_STATUS_UPDATED,
                    adFormat = "UMP",
                    metadata = mapOf(
                        "canRequestAds" to canRequestAds.toString(),
                        "status" to "REQUEST_ERROR_${requestError.errorCode}"
                    )
                )
                onConsentGatheringComplete(canRequestAds, requestError.message)
            }
        )
    }

    /**
     * Opens the UMP privacy options form when the user taps the "Privacy" button in settings/HUD.
     */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (errorDescription: String?) -> Unit
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(
            activity,
            ConsentForm.OnConsentFormDismissedListener { formError ->
                analytics.logEvent(
                    eventType = MonetizationEvent.EventType.CONSENT_STATUS_UPDATED,
                    adFormat = "UMP_PRIVACY_OPTIONS",
                    metadata = mapOf(
                        "canRequestAds" to canRequestAds.toString(),
                        "error" to (formError?.message ?: "none")
                    )
                )
                onDismissed(formError?.message)
            }
        )
    }
}
