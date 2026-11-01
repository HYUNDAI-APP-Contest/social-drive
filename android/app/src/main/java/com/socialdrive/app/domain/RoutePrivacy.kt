package com.socialdrive.app.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LiveUnavailableReason { SDK_COMING_SOON, PERMISSION_DENIED, CAPABILITY_MISSING }

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Live route capability is test-only until an approved location SDK and permission flow exist.",
)
annotation class TestOnlyLiveRouteCapability

@TestOnlyLiveRouteCapability
class LiveRouteCapabilityToken private constructor() {
    companion object {
        fun issueForTest(): LiveRouteCapabilityToken = LiveRouteCapabilityToken()
    }
}

sealed interface RouteSource {
    data object DemoPreset : RouteSource
    data class LiveUnavailable(val reason: LiveUnavailableReason) : RouteSource

    @TestOnlyLiveRouteCapability
    class LiveAvailable internal constructor(val capability: LiveRouteCapabilityToken) : RouteSource
}

enum class RouteConsentState { NotAsked, Granted, Withdrawn }
enum class RouteConsentAction { Grant, Withdraw }

data class RoutePrivacyStatus(
    val displayedSource: RouteSource,
    val liveSource: RouteSource.LiveUnavailable,
    val consentState: RouteConsentState,
)

data class RouteConsentDecision(
    val state: RouteConsentState,
    val shouldPersist: Boolean,
    val unavailableReason: LiveUnavailableReason? = null,
)

@OptIn(TestOnlyLiveRouteCapability::class)
object RouteConsentReducer {
    fun reduce(
        current: RouteConsentState,
        source: RouteSource,
        action: RouteConsentAction,
    ): RouteConsentDecision = when (action) {
        RouteConsentAction.Grant -> when (source) {
            is RouteSource.LiveUnavailable -> RouteConsentDecision(
                state = current,
                shouldPersist = false,
                unavailableReason = source.reason,
            )
            RouteSource.DemoPreset -> RouteConsentDecision(RouteConsentState.Granted, shouldPersist = true)
            is RouteSource.LiveAvailable -> RouteConsentDecision(RouteConsentState.Granted, shouldPersist = true)
        }
        RouteConsentAction.Withdraw -> RouteConsentDecision(RouteConsentState.Withdrawn, shouldPersist = true)
    }
}

data class NormalizedRoutePoint(
    val sequence: Int,
    val horizontal: Float,
    val vertical: Float,
) {
    init {
        require(sequence >= 0)
        require(horizontal.isFinite() && horizontal in 0f..1f)
        require(vertical.isFinite() && vertical in 0f..1f)
    }
}

interface FutureRouteCollector {
    suspend fun start()
    suspend fun stopAndAwait()
}

data class WithdrawalReceipt(
    val purgedUnpublishedPointCount: Int,
    val tombstonedDraftIds: Set<String>,
)

interface RoutePrivacyMutationStore {
    suspend fun persistConsent(state: RouteConsentState)

    /** Must atomically persist Withdrawn, purge unpublished normalized points, and tombstone pending queues. */
    suspend fun withdrawAndPurge(): WithdrawalReceipt

    /** Persists only a local DELETE_PENDING marker. This contract performs no network call. */
    suspend fun markPublishedDeletePending(draftId: String): Boolean
}

fun interface PendingRouteUploadCanceller {
    suspend fun cancel(draftIds: Set<String>)
}

sealed interface PublishedDeleteDecision {
    data object ConfirmationRequired : PublishedDeleteDecision
    data object NotPublished : PublishedDeleteDecision
    data class MotionRejected(val motion: MotionState) : PublishedDeleteDecision
    data object DeletePending : PublishedDeleteDecision
    data object IdentityUnavailable : PublishedDeleteDecision
}

@OptIn(TestOnlyLiveRouteCapability::class)
class RoutePrivacyCoordinator(
    initialConsent: RouteConsentState,
    private val collector: FutureRouteCollector,
    private val store: RoutePrivacyMutationStore,
    private val pendingUploads: PendingRouteUploadCanceller,
) {
    private val mutationMutex = Mutex()
    var consentState: RouteConsentState = initialConsent
        private set

    suspend fun grant(source: RouteSource): RouteConsentDecision = mutationMutex.withLock {
        val decision = RouteConsentReducer.reduce(consentState, source, RouteConsentAction.Grant)
        if (!decision.shouldPersist) return@withLock decision
        store.persistConsent(decision.state)
        consentState = decision.state
        if (source is RouteSource.LiveAvailable) collector.start()
        decision
    }

    suspend fun withdraw(): WithdrawalReceipt = mutationMutex.withLock {
        // Awaiting stop first ensures no point can race into the purge transaction.
        collector.stopAndAwait()
        val receipt = store.withdrawAndPurge()
        consentState = RouteConsentState.Withdrawn
        // The durable tombstone is authoritative if process death happens before cancellation completes.
        pendingUploads.cancel(receipt.tombstonedDraftIds)
        receipt
    }

    suspend fun requestPublishedDelete(
        draftId: String,
        isPublished: Boolean,
        confirmed: Boolean,
        currentMotion: () -> MotionState,
    ): PublishedDeleteDecision = mutationMutex.withLock {
        if (!isPublished) return@withLock PublishedDeleteDecision.NotPublished
        if (!confirmed) return@withLock PublishedDeleteDecision.ConfirmationRequired
        // Read the authoritative source at the mutation boundary, never the dialog's captured UI state.
        val motion = currentMotion()
        if (motion != MotionState.Parked) return@withLock PublishedDeleteDecision.MotionRejected(motion)
        if (store.markPublishedDeletePending(draftId)) PublishedDeleteDecision.DeletePending
        else PublishedDeleteDecision.IdentityUnavailable
    }
}
