package com.ultimategames.blockblast.ads

/**
 * Lightweight, privacy-conscious monetization analytics logger.
 * Does not collect device identifiers, IP addresses, or personal data.
 * Stores structured events in a bounded ring buffer for diagnostics & QA verification.
 */
data class MonetizationEvent(
    val timestampMillis: Long,
    val eventType: EventType,
    val adFormat: String,
    val metadata: Map<String, String> = emptyMap()
) {
    enum class EventType {
        AD_REQUEST,
        AD_LOAD_SUCCESS,
        AD_LOAD_FAILURE,
        AD_IMPRESSION,
        AD_DISMISSED,
        REWARD_EARNED,
        DUPLICATE_REWARD_BLOCKED,
        INTERSTITIAL_CAP_REJECTED,
        CONSENT_STATUS_UPDATED,
        RELEASE_CONFIG_VALIDATION_FAILED
    }
}

class MonetizationAnalytics(private val maxHistorySize: Int = 200) {

    private val events = ArrayDeque<MonetizationEvent>()

    @Synchronized
    fun logEvent(
        eventType: MonetizationEvent.EventType,
        adFormat: String,
        metadata: Map<String, String> = emptyMap(),
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        if (events.size >= maxHistorySize) {
            events.removeFirst()
        }
        events.addLast(
            MonetizationEvent(
                timestampMillis = timestampMillis,
                eventType = eventType,
                adFormat = adFormat,
                metadata = metadata
            )
        )
    }

    @Synchronized
    fun getRecordedEvents(): List<MonetizationEvent> = events.toList()

    @Synchronized
    fun countByType(type: MonetizationEvent.EventType): Int =
        events.count { it.eventType == type }

    @Synchronized
    fun clear() {
        events.clear()
    }
}
