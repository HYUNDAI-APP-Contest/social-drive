package com.socialdrive.app.data

import androidx.room.withTransaction
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
import com.socialdrive.app.domain.RoutePrivacyMutationStore
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.WithdrawalReceipt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoutePrivacyRepository(
    private val database: SocialDriveDatabase,
    private val pendingUploads: PendingRouteUploadCanceller,
    private val currentMotion: () -> MotionState,
    private val collector: FutureRouteCollector = UnavailableFutureRouteCollector,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDeletePending: (String) -> Unit = {},
) {
    private val liveUnavailable = RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON)
    private val mutationMutex = Mutex()

    val status: Flow<RoutePrivacyStatus> = database.routePrivacyDao().observe().map { entity ->
        RoutePrivacyStatus(
            displayedSource = RouteSource.DemoPreset,
            liveSource = liveUnavailable,
            consentState = entity?.consentState?.toConsentState() ?: RouteConsentState.NotAsked,
        )
    }

    suspend fun requestLiveConsent(): RouteConsentDecision = mutationMutex.withLock {
        coordinator().grant(liveUnavailable)
    }

    suspend fun withdraw(): WithdrawalReceipt = mutationMutex.withLock { coordinator().withdraw() }

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
        val initial = database.routePrivacyDao().find()?.consentState?.toConsentState() ?: RouteConsentState.NotAsked
        return RoutePrivacyCoordinator(initial, collector, RoomRoutePrivacyMutationStore(database, clock), pendingUploads)
    }
}

private object UnavailableFutureRouteCollector : FutureRouteCollector {
    override suspend fun start(): Nothing = error("Live route collection is unavailable")
    override suspend fun stopAndAwait() = Unit
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
        database.uploadQueueDao().tombstonePendingForPrivacyWithdrawal(clock())
        WithdrawalReceipt(purged, database.uploadQueueDao().privacyWithdrawnDraftIds().toSet())
    }

    override suspend fun markPublishedDeletePending(draftId: String): Boolean = database.withTransaction {
        val draft = database.draftDao().find(draftId) ?: return@withTransaction false
        val remoteId = draft.remoteDriveId ?: return@withTransaction false
        if (draft.state != LocalState.PUBLISHED) return@withTransaction false
        val stableKeyHash = runCatching {
            DeleteLifecyclePolicy.stableKeyHash(draft.clientRequestId, remoteId)
        }.getOrNull() ?: return@withTransaction false
        val now = clock()
        val request = PublishedDeleteRequestEntity(
            draftId = draftId,
            clientRequestId = draft.clientRequestId,
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
        val inserted = database.publishedDeleteDao().insertIfAbsent(request)
        inserted != -1L || database.publishedDeleteDao().find(draftId)?.let {
            it.clientRequestId == request.clientRequestId &&
                it.remoteDriveId == request.remoteDriveId &&
                it.idempotencyKeyHash == request.idempotencyKeyHash
        } == true
    }
}

private fun RouteConsentState.entity(now: Long) = RoutePrivacyEntity(
    consentState = name,
    routeSource = "DemoPreset",
    unavailableReason = LiveUnavailableReason.SDK_COMING_SOON.name,
    updatedAtEpochMs = now,
)

private fun String.toConsentState(): RouteConsentState =
    RouteConsentState.entries.firstOrNull { it.name == this } ?: RouteConsentState.NotAsked
