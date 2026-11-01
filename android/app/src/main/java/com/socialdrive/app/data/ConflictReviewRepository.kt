package com.socialdrive.app.data

import androidx.room.withTransaction
import com.socialdrive.app.data.local.ConflictState
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.PublishConflictEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.RemoteConflictReviewApi
import com.socialdrive.app.data.remote.RemoteDriveException
import com.socialdrive.app.domain.ConflictReviewDecision
import com.socialdrive.app.domain.ConflictReviewPolicy
import com.socialdrive.app.domain.ConflictReviewUiState
import com.socialdrive.app.domain.ConflictUiPhase
import com.socialdrive.app.domain.MAX_CONFLICT_REPLAYS
import com.socialdrive.app.safety.ParkedExecutionGate
import com.socialdrive.app.safety.ParkedExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface ConflictReplayScheduler {
    fun replacePublishOnly(draftId: String)
}

/**
 * User-driven conflict review. The gate serializes the observed motion transition with claim,
 * response persistence and local adoption. It does not claim physical signal/DB global atomicity.
 */
class ConflictReviewRepository(
    private val database: SocialDriveDatabase,
    private val remote: RemoteConflictReviewApi,
    private val executionGate: ParkedExecutionGate,
    private val replayScheduler: ConflictReplayScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val actionMutex = Mutex()

    fun observe(draftId: String): Flow<ConflictReviewUiState> =
        database.publishConflictDao().observe(draftId).map(::toUiState)

    /** Process death may interrupt the coroutine before its NonCancellable cleanup can run. */
    suspend fun recoverInterruptedFetches(): Int = actionMutex.withLock {
        database.publishConflictDao().normalizeInterruptedFetches()
    }

    suspend fun fetchLatest(draftId: String): ConflictReviewDecision = actionMutex.withLock {
        val identity = validatedIdentity(draftId) ?: return@withLock ConflictReviewDecision.IdentityMismatch
        var claimed = false
        var claimedGeneration = identity.fetchGeneration
        var claimedIdentity = identity
        try {
            when (val result = executionGate.execute(
                onPaused = {
                    if (claimed) database.publishConflictDao().failFetchCas(
                        draftId,
                        identity.clientRequestId,
                        identity.remoteDriveId,
                        identity.attemptedVersion,
                        claimedGeneration,
                        "MOTION_NOT_PARKED",
                    )
                },
            ) {
                claimed = database.withTransaction {
                    validatedIdentity(draftId, identity) == identity &&
                        database.publishConflictDao().claimFetch(
                            draftId,
                            identity.clientRequestId,
                            identity.remoteDriveId,
                            identity.attemptedVersion,
                        ) == 1
                }
                if (!claimed) return@execute ConflictReviewDecision.Coalesced
                claimedGeneration = database.publishConflictDao().find(draftId)?.fetchGeneration
                    ?: return@execute ConflictReviewDecision.IdentityMismatch
                claimedIdentity = identity.copy(fetchGeneration = claimedGeneration)
                val snapshot = remote.getOwnedDrive(identity.remoteDriveId)
                val bounded = ConflictReviewPolicy.bounded(snapshot)
                    ?: throw RemoteDriveException(com.socialdrive.app.data.remote.RemoteFailureCode.REJECTED, false)
                if (bounded.remoteDriveId != identity.remoteDriveId) {
                    throw RemoteDriveException(com.socialdrive.app.data.remote.RemoteFailureCode.REJECTED, false)
                }
                val stored = database.withTransaction {
                    if (validatedIdentity(draftId, claimedIdentity) != claimedIdentity) return@withTransaction 0
                    database.publishConflictDao().storeSnapshotCas(
                        draftId = draftId,
                        clientRequestId = identity.clientRequestId,
                        remoteDriveId = identity.remoteDriveId,
                        attemptedVersion = identity.attemptedVersion,
                        fetchGeneration = claimedGeneration,
                        latestVersion = bounded.version,
                        latestStatus = bounded.status,
                        latestTitle = bounded.title,
                        latestSummary = bounded.summary,
                        now = clock(),
                    )
                }
                if (stored == 1) ConflictReviewDecision.Applied else ConflictReviewDecision.IdentityMismatch
            }) {
                ParkedExecutionResult.Paused -> ConflictReviewDecision.NotParked
                is ParkedExecutionResult.Completed -> result.value
            }
        } catch (cancelled: CancellationException) {
            if (claimed) withContext(NonCancellable) {
                database.publishConflictDao().failFetchCas(
                    draftId,
                    identity.clientRequestId,
                    identity.remoteDriveId,
                    identity.attemptedVersion,
                    claimedGeneration,
                    "INTERRUPTED",
                )
            }
            throw cancelled
        } catch (error: RemoteDriveException) {
            if (claimed) database.publishConflictDao().failFetchCas(
                draftId,
                identity.clientRequestId,
                identity.remoteDriveId,
                identity.attemptedVersion,
                claimedGeneration,
                safeFetchCode(error),
            )
            ConflictReviewDecision.NeedsReview
        } catch (_: Throwable) {
            if (claimed) database.publishConflictDao().failFetchCas(
                draftId,
                identity.clientRequestId,
                identity.remoteDriveId,
                identity.attemptedVersion,
                claimedGeneration,
                "FETCH_UNAVAILABLE",
            )
            ConflictReviewDecision.NeedsReview
        }
    }

    suspend fun keepLocal(draftId: String): ConflictReviewDecision = actionMutex.withLock {
        val identity = validatedIdentity(draftId) ?: return@withLock ConflictReviewDecision.IdentityMismatch
        when (val result = executionGate.execute(onPaused = {}) {
            val changed = database.withTransaction {
                if (validatedIdentity(draftId, identity) != identity) 0 else database.publishConflictDao().keepLocalCas(
                    draftId,
                    identity.clientRequestId,
                    identity.remoteDriveId,
                    identity.attemptedVersion,
                    clock(),
                )
            }
            if (changed == 1) ConflictReviewDecision.Applied else ConflictReviewDecision.Coalesced
        }) {
            ParkedExecutionResult.Paused -> ConflictReviewDecision.NotParked
            is ParkedExecutionResult.Completed -> result.value
        }
    }

    suspend fun adoptLatestAndReplay(draftId: String): ConflictReviewDecision = actionMutex.withLock {
        val identity = validatedIdentity(draftId) ?: return@withLock ConflictReviewDecision.IdentityMismatch
        val decision = when (val result = executionGate.execute(onPaused = {}) {
            database.withTransaction {
                val conflict = database.publishConflictDao().find(draftId)
                    ?: return@withTransaction ConflictReviewDecision.Missing
                val draft = database.draftDao().find(draftId)
                    ?: return@withTransaction ConflictReviewDecision.Missing
                val queue = database.uploadQueueDao().find(draftId)
                    ?: return@withTransaction ConflictReviewDecision.Missing
                if (!identity.matches(conflict, draft.clientRequestId, queue.clientRequestId) ||
                    draft.remoteDriveId != identity.remoteDriveId || draft.remoteVersion != identity.attemptedVersion ||
                    queue.stage != LocalState.PUBLISH || queue.status != LocalState.FAILED ||
                    queue.lastErrorCode != LocalState.VERSION_CONFLICT
                ) return@withTransaction ConflictReviewDecision.IdentityMismatch
                val latestVersion = conflict.latestVersion ?: return@withTransaction ConflictReviewDecision.SnapshotNotReplayable
                val snapshot = com.socialdrive.app.domain.OwnedDriveConflictSnapshot(
                    identity.remoteDriveId,
                    latestVersion,
                    conflict.latestStatus.orEmpty(),
                    conflict.latestTitle.orEmpty(),
                    conflict.latestSummary.orEmpty(),
                )
                if (conflict.replayCount >= MAX_CONFLICT_REPLAYS) return@withTransaction ConflictReviewDecision.ReplayLimitReached
                if (!ConflictReviewPolicy.mayReplay(identity.attemptedVersion, snapshot, conflict.replayCount)) {
                    return@withTransaction ConflictReviewDecision.SnapshotNotReplayable
                }
                if (database.publishConflictDao().queueReplayCas(
                    draftId,
                    identity.clientRequestId,
                    identity.remoteDriveId,
                    identity.attemptedVersion,
                    latestVersion,
                    MAX_CONFLICT_REPLAYS,
                ) != 1) return@withTransaction ConflictReviewDecision.Coalesced
                if (database.draftDao().adoptConflictVersionCas(
                    draftId,
                    identity.remoteDriveId,
                    identity.attemptedVersion,
                    latestVersion,
                    identity.clientRequestId,
                    clock(),
                ) != 1) error("stale draft conflict transition")
                if (database.uploadQueueDao().queueConflictReplayCas(draftId, identity.clientRequestId, clock()) != 1) {
                    error("stale queue conflict transition")
                }
                ConflictReviewDecision.Applied
            }
        }) {
            ParkedExecutionResult.Paused -> ConflictReviewDecision.NotParked
            is ParkedExecutionResult.Completed -> result.value
        }
        if (decision == ConflictReviewDecision.Applied) {
            // Durable REPLAY_QUEUED is committed first; restart recovery repairs enqueue failure.
            runCatching { replayScheduler.replacePublishOnly(draftId) }
        }
        decision
    }

    private suspend fun validatedIdentity(draftId: String, expected: ConflictIdentity? = null): ConflictIdentity? {
        val conflict = database.publishConflictDao().find(draftId) ?: return null
        val draft = database.draftDao().find(draftId) ?: return null
        val queue = database.uploadQueueDao().find(draftId) ?: return null
        val identity = ConflictIdentity(
            draftId,
            conflict.remoteDriveId,
            conflict.attemptedVersion,
            draft.clientRequestId,
            conflict.fetchGeneration,
        )
        if (expected != null && expected != identity) return null
        return identity.takeIf {
            it.remoteDriveId.isNotBlank() && it.attemptedVersion > 0 &&
                draft.remoteDriveId == it.remoteDriveId && draft.remoteVersion == it.attemptedVersion &&
                draft.clientRequestId == queue.clientRequestId &&
                queue.stage == LocalState.PUBLISH && queue.status == LocalState.FAILED &&
                queue.lastErrorCode == LocalState.VERSION_CONFLICT &&
                conflict.state in setOf(
                    ConflictState.DETECTED,
                    ConflictState.FETCHING,
                    ConflictState.FETCH_FAILED,
                    ConflictState.SNAPSHOT_READY,
                )
        }
    }

    private fun safeFetchCode(error: RemoteDriveException): String = when (error.code) {
        com.socialdrive.app.data.remote.RemoteFailureCode.NOT_FOUND -> "FETCH_UNAVAILABLE"
        com.socialdrive.app.data.remote.RemoteFailureCode.OFFLINE,
        com.socialdrive.app.data.remote.RemoteFailureCode.TIMEOUT,
        com.socialdrive.app.data.remote.RemoteFailureCode.SERVER_BUSY -> "FETCH_UNAVAILABLE"
        else -> "FETCH_UNAVAILABLE"
    }

    private fun toUiState(entity: PublishConflictEntity?): ConflictReviewUiState {
        if (entity == null) return ConflictReviewUiState(ConflictUiPhase.Hidden)
        val phase = when (entity.state) {
            ConflictState.DETECTED -> ConflictUiPhase.Detected
            ConflictState.FETCHING -> ConflictUiPhase.Fetching
            ConflictState.SNAPSHOT_READY -> ConflictUiPhase.SnapshotReady
            ConflictState.FETCH_FAILED -> ConflictUiPhase.FetchFailed
            ConflictState.KEPT_LOCAL -> ConflictUiPhase.KeptLocal
            ConflictState.REPLAY_QUEUED -> ConflictUiPhase.ReplayQueued
            ConflictState.RESOLVED -> ConflictUiPhase.Resolved
            ConflictState.UNREVIEWABLE -> ConflictUiPhase.Unreviewable
            else -> ConflictUiPhase.Hidden
        }
        return ConflictReviewUiState(phase, entity.latestVersion, entity.latestTitle, entity.latestSummary, entity.replayCount)
    }
}

private data class ConflictIdentity(
    val draftId: String,
    val remoteDriveId: String,
    val attemptedVersion: Int,
    val clientRequestId: String,
    val fetchGeneration: Int,
) {
    fun matches(conflict: PublishConflictEntity, draftClientRequestId: String, queueClientRequestId: String): Boolean =
        draftId == conflict.draftId && remoteDriveId == conflict.remoteDriveId &&
            attemptedVersion == conflict.attemptedVersion &&
            clientRequestId == conflict.clientRequestId && clientRequestId == draftClientRequestId &&
            clientRequestId == queueClientRequestId
}
