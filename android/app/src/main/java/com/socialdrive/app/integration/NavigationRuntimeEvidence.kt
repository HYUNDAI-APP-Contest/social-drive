package com.socialdrive.app.integration

/** Privacy-safe, in-memory proof of the production navigation lifecycle. */
internal enum class NavigationRuntimeStage {
    INITIALIZE_STARTED,
    INITIALIZE_CONNECTED,
    REQUEST_VALIDATED,
    REQUEST_DISPATCHED,
    TERMINAL_COMPLETED,
    TERMINAL_ERROR,
    LISTENER_REMOVED,
    RELEASED,
    PERMISSION_DENIED,
    SERVICE_UNAVAILABLE,
    TIMED_OUT,
    STALE_IGNORED,
    CLEANUP_FAILED,
}

internal data class NavigationRuntimeEvidence(
    val lifecycleGeneration: Long,
    val requestGeneration: Long,
    val stage: NavigationRuntimeStage,
)

internal interface NavigationRuntimeEvidenceSource {
    fun navigationRuntimeEvidence(): List<NavigationRuntimeEvidence>
}

internal fun interface NavigationRuntimeEvidenceSink {
    fun record(evidence: NavigationRuntimeEvidence)
}

internal class BoundedNavigationRuntimeEvidence(
    private val capacity: Int = 24,
) : NavigationRuntimeEvidenceSource, NavigationRuntimeEvidenceSink {
    private val lock = Any()
    private val events = ArrayDeque<NavigationRuntimeEvidence>(capacity)

    override fun record(evidence: NavigationRuntimeEvidence) = synchronized(lock) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(evidence)
    }

    override fun navigationRuntimeEvidence(): List<NavigationRuntimeEvidence> =
        synchronized(lock) { events.toList() }
}
