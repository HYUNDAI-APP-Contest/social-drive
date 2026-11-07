package com.socialdrive.app.data

import androidx.room.withTransaction
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.PublishedDeleteRequestEntity
import com.socialdrive.app.data.local.RoutePrivacyEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.domain.FutureRouteCollector
import com.socialdrive.app.domain.DeleteLifecyclePolicy
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.PendingRouteUploadCanceller
import com.socialdrive.app.domain.PublishedDeleteDecision
import com.socialdrive.app.domain.RouteConsentDecision
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyCoordinator
import com.socialdrive.app.domain.RouteLocationConsentPolicy
import com.socialdrive.app.domain.RoutePrivacyMutationStore
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.WithdrawalReceipt
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoutePrivacyRepository(
    private val database: SocialDriveDatabase,
    private val pendingUploads: PendingRouteUploadCanceller,
    private val currentMotion: () -> MotionState,
    private val locationCapability: StateFlow<CapabilityStatus> = MutableStateFlow(
        CapabilityStatus(CapabilityState.Unavailable, com.socialdrive.app.domain.IntegrationSource.Unavailable, "사용 불가"),
    ),
    private val collector: FutureRouteCollector = UnavailableRouteCollector,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDeletePending: (String) -> Unit = {},
) {
    private val mutationMutex = Mutex()

    val status: Flow<RoutePrivacyStatus> = combine(database.routePrivacyDao().observe(), locationCapability) { entity, capability ->
        val liveSource = capability.toRouteSource()
        val consentState = entity.effectiveConsentState()
        RoutePrivacyStatus(
            displayedSource = if (consentState == RouteConsentState.Granted && liveSource == RouteSource.LiveAvailable) {
                RouteSource.LiveAvailable
            } else RouteSource.DemoPreset,
            liveSource = liveSource,
            consentState = consentState,
        )
    }

    suspend fun requestLiveConsent(): RouteConsentDecision = mutationMutex.withLock {
        coordinator().grant(locationCapability.value.toRouteSource())
    }

    suspend fun restoreCollectionIfConsented(): Boolean = mutationMutex.withLock {
        val entity = database.routePrivacyDao().find()
        val consent = entity.effectiveConsentState()
        if (entity?.consentState == RouteConsentState.Granted.name && consent != RouteConsentState.Granted) {
            database.routePrivacyDao().upsert(RouteConsentState.NotAsked.entity(clock()))
        }
        if (consent != RouteConsentState.Granted) return@withLock false
        coordinator().grant(locationCapability.value.toRouteSource()).shouldPersist
    }

    suspend fun withdraw(): WithdrawalReceipt = mutationMutex.withLock {
        coordinator().withdraw().also { receipt ->
            // Durable delete rows are authoritative; startup recovery repairs a failed enqueue.
            receipt.remoteCleanupDraftIds.forEach { draftId -> runCatching { onDeletePending(draftId) } }
        }
    }

    suspend fun requestPublishedDelete(draftId: String, confirmed: Boolean): PublishedDeleteDecision = mutationMutex.withLock {
        val draft = database.draftDao().find(draftId)
        val decision = coordinator().requestPublishedDelete(
            draftId = draftId,
            isPublished = draft?.state == LocalState.PUBLISHED,
            confirmed = confirmed,
            currentMotion = currentMotion,
        )
        // The Room tombstone is authoritative; startup recovery repairs a failed enqueue.
        if (decision == PublishedDeleteDecision.DeletePending) runCatching { onDeletePending(draftId) }
        decision
    }

    private suspend fun coordinator(): RoutePrivacyCoordinator {
        val initial = database.routePrivacyDao().find().effectiveConsentState()
        return RoutePrivacyCoordinator(initial, collector, RoomRoutePrivacyMutationStore(database, clock), pendingUploads)
    }
}

private object UnavailableRouteCollector : FutureRouteCollector {
    override suspend fun start() = Unit
    override suspend fun beginRecording() = Unit
    override suspend fun snapshotAndClear() = com.socialdrive.app.domain.CollectedRoute.Empty
    override suspend fun stopAndAwait() = Unit
}

private fun CapabilityStatus.toRouteSource(): RouteSource = when (capability) {
    CapabilityState.Available -> RouteSource.LiveAvailable
    CapabilityState.PermissionDenied -> RouteSource.LiveUnavailable(LiveUnavailableReason.PERMISSION_DENIED)
    CapabilityState.Unavailable -> RouteSource.LiveUnavailable(LiveUnavailableReason.CAPABILITY_MISSING)
}

private class RoomRoutePrivacyMutationStore(
    private val database: SocialDriveDatabase,
    private val clock: () -> Long,
) : RoutePrivacyMutationStore {
    override suspend fun persistConsent(state: RouteConsentState) {
        database.routePrivacyDao().upsert(state.entity(clock()))
    }

    override suspend fun withdrawAndPurge(): WithdrawalReceipt = database.withTransaction {
        database.routePrivacyDao().upsert(RouteConsentState.Withdrawn.entity(clock()))
        val purged = database.routePrivacyDao().purgeUnpublishedPoints()
        val now = clock()
        database.uploadQueueDao().tombstonePendingForPrivacyWithdrawal(now)
        val tombstoned = database.uploadQueueDao().privacyWithdrawnDraftIds().toSet()
        val remoteCleanup = tombstoned.mapNotNullTo(mutableSetOf()) { draftId ->
            val draft = database.draftDao().find(draftId) ?: return@mapNotNullTo null
            if (draft.state == LocalState.PUBLISHED || draft.remoteDriveId == null) return@mapNotNullTo null
            val request = draft.remoteDeleteRequest(now) ?: return@mapNotNullTo null
            val inserted = database.publishedDeleteDao().insertIfAbsent(request)
            val durable = inserted != -1L || database.publishedDeleteDao().find(draftId)?.sameIdentity(request) == true
            draftId.takeIf { durable }
        }
        WithdrawalReceipt(purged, tombstoned, remoteCleanup)
    }

    override suspend fun markPublishedDeletePending(draftId: String): Boolean = database.withTransaction {
        val draft = database.draftDao().find(draftId) ?: return@withTransaction false
        if (draft.remoteDriveId == null) return@withTransaction false
        if (draft.state != LocalState.PUBLISHED) return@withTransaction false
        val now = clock()
        val request = draft.remoteDeleteRequest(now) ?: return@withTransaction false
        val inserted = database.publishedDeleteDao().insertIfAbsent(request)
        inserted != -1L || database.publishedDeleteDao().find(draftId)?.sameIdentity(request) == true
    }
}

private fun DraftEntity.remoteDeleteRequest(now: Long): PublishedDeleteRequestEntity? {
    val remoteId = remoteDriveId ?: return null
    val stableKeyHash = runCatching {
        DeleteLifecyclePolicy.stableKeyHash(clientRequestId, remoteId)
    }.getOrNull() ?: return null
    return PublishedDeleteRequestEntity(
        draftId = id,
        clientRequestId = clientRequestId,
        remoteDriveId = remoteId,
        idempotencyKeyHash = stableKeyHash,
        state = LocalState.DELETE_PENDING,
        attempts = 0,
        nextAttemptAtEpochMs = now,
        lastErrorCode = null,
        requestedAtEpochMs = now,
        updatedAtEpochMs = now,
        confirmedAtEpochMs = null,
    )
}

private fun PublishedDeleteRequestEntity.sameIdentity(other: PublishedDeleteRequestEntity): Boolean =
    clientRequestId == other.clientRequestId &&
        remoteDriveId == other.remoteDriveId &&
        idempotencyKeyHash == other.idempotencyKeyHash

private fun RouteConsentState.entity(now: Long) = RoutePrivacyEntity(
    consentState = name,
    consentPolicyVersion = if (this == RouteConsentState.Granted) RouteLocationConsentPolicy.VERSION else null,
    routeSource = if (this == RouteConsentState.Granted) "LiveAvailable" else "DemoPreset",
    unavailableReason = null,
    updatedAtEpochMs = now,
)

private fun String.toConsentState(): RouteConsentState =
    RouteConsentState.entries.firstOrNull { it.name == this } ?: RouteConsentState.NotAsked

private fun RoutePrivacyEntity?.effectiveConsentState(): RouteConsentState {
    return effectiveRouteConsentState(this?.consentState, this?.consentPolicyVersion)
}

internal fun effectiveRouteConsentState(rawState: String?, policyVersion: String?): RouteConsentState {
    val state = rawState?.toConsentState() ?: return RouteConsentState.NotAsked
    return if (state == RouteConsentState.Granted && policyVersion != RouteLocationConsentPolicy.VERSION) {
        RouteConsentState.NotAsked
    } else state
}
