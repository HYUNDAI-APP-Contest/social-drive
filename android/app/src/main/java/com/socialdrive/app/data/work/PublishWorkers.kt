package com.socialdrive.app.data.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.local.ConflictState
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.PublishConflictEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.local.UploadQueueEntity
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.RemoteDriveException
import com.socialdrive.app.data.remote.assetUploads
import com.socialdrive.app.domain.NormalizedRoutePoint
import com.socialdrive.app.safety.ParkedExecutionGate
import com.socialdrive.app.safety.ParkedExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class PublishWorkerDependencies(
    val database: SocialDriveDatabase,
    val remote: RemoteDriveApi,
    val executionGate: ParkedExecutionGate,
    val rescheduler: PublishRescheduler,
    val clock: () -> Long = System::currentTimeMillis,
)

private class StaleWorkerTransition : Exception()

class PublishWorkerFactory(
    private val dependencies: PublishWorkerDependencies,
    private val deleteDependencies: DeleteWorkerDependencies? = null,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        CreateDriveWorker::class.java.name -> CreateDriveWorker(appContext, workerParameters, dependencies)
        UploadAssetsWorker::class.java.name -> UploadAssetsWorker(appContext, workerParameters, dependencies)
        PublishDriveWorker::class.java.name -> PublishDriveWorker(appContext, workerParameters, dependencies)
        DeletePublishedDriveWorker::class.java.name -> deleteDependencies?.let {
            DeletePublishedDriveWorker(appContext, workerParameters, it)
        }
        else -> null
    }
}

abstract class StagedPublishWorker(
    appContext: Context,
    params: WorkerParameters,
    protected val dependencies: PublishWorkerDependencies,
) : CoroutineWorker(appContext, params) {
    protected val draftId: String? get() = inputData.getString(KEY_DRAFT_ID)

    protected suspend fun claim(draftId: String, queue: UploadQueueEntity): WorkerStageLease? {
        val now = dependencies.clock()
        val lease = WorkerTransitionContract.claim(
            WorkerQueueSnapshot(queue.stage, queue.status, queue.attempts, queue.lastErrorCode),
        ) ?: return null
        val claimed = dependencies.database.uploadQueueDao().claimStage(
            draftId = draftId,
            stage = queue.stage,
            expectedStatus = queue.status,
            expectedAttempts = queue.attempts,
            runningAttempt = lease.attempt,
            now = now,
        )
        return lease.takeIf { claimed == 1 }
    }

    protected suspend fun pauseForMotion(draftId: String, lease: WorkerStageLease) {
        dependencies.database.uploadQueueDao().pauseRunningStage(draftId, lease.stage, lease.attempt, dependencies.clock())
    }

    protected suspend fun fail(draftId: String, lease: WorkerStageLease, error: Throwable): Result {
        val remote = error as? RemoteDriveException
        val now = dependencies.clock()
        val updated = dependencies.database.uploadQueueDao().failRunningStage(
            draftId = draftId,
            stage = lease.stage,
            attempts = lease.attempt,
            lastErrorCode = remote?.code?.name ?: "UNKNOWN",
            now = now,
        )
        if (updated == 0) return Result.success()
        return if (remote?.retryable == true && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
    }

    protected suspend fun DraftEntity.snapshot() = DraftSnapshot(
        id = id,
        recordingId = recordingId,
        clientRequestId = clientRequestId,
        title = title,
        description = description,
        coverPreset = coverPreset,
        originalContentRef = originalContentRef,
        state = state,
        routePoints = dependencies.database.routePrivacyDao().pointsForRecording(recordingId).map { point ->
            NormalizedRoutePoint(point.sequence, point.horizontal, point.vertical)
        },
    )

    companion object {
        const val KEY_DRAFT_ID = "draftId"
        private const val MAX_RETRY_ATTEMPTS = 4
    }
}

class CreateDriveWorker(
    appContext: Context,
    params: WorkerParameters,
    dependencies: PublishWorkerDependencies,
) : StagedPublishWorker(appContext, params, dependencies) {
    override suspend fun doWork(): Result {
        val id = draftId ?: return Result.failure()
        val draft = dependencies.database.draftDao().find(id) ?: return Result.failure()
        val queue = dependencies.database.uploadQueueDao().find(id) ?: return Result.failure()
        if (queue.stage != LocalState.CREATE) return Result.success()
        val lease = claim(id, queue) ?: return Result.success()
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = { pauseForMotion(id, lease) },
            ) {
                if (draft.remoteDriveId != null && draft.remoteVersion != null) {
                    com.socialdrive.app.data.remote.RemoteDraftRef(draft.remoteDriveId, draft.remoteVersion)
                } else {
                    dependencies.remote.createDrive(draft.clientRequestId, draft.snapshot()).also { replay ->
                        if (!WorkerTransitionContract.acceptsCreateReplayIdentity(draft.remoteDriveId, replay.id)) {
                            throw RemoteDriveException(com.socialdrive.app.data.remote.RemoteFailureCode.REJECTED, retryable = false)
                        }
                    }
                }
            }
            val remote = when (execution) {
                ParkedExecutionResult.Paused -> return Result.failure()
                is ParkedExecutionResult.Completed -> execution.value
            }
            val now = dependencies.clock()
            try {
                dependencies.database.withTransaction {
                    if (dependencies.database.draftDao().applyCreateResultCas(
                        id, draft.state, draft.remoteDriveId, draft.remoteVersion, remote.id, remote.version, now,
                    ) != 1) throw StaleWorkerTransition()
                    if (dependencies.database.uploadQueueDao().completeCreateStage(id, lease.attempt, now) != 1) {
                        throw StaleWorkerTransition()
                    }
                }
            } catch (_: StaleWorkerTransition) { return Result.success() }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, lease, error)
        }
    }
}

class UploadAssetsWorker(
    appContext: Context,
    params: WorkerParameters,
    dependencies: PublishWorkerDependencies,
) : StagedPublishWorker(appContext, params, dependencies) {
    override suspend fun doWork(): Result {
        val id = draftId ?: return Result.failure()
        val draft = dependencies.database.draftDao().find(id) ?: return Result.failure()
        val queue = dependencies.database.uploadQueueDao().find(id) ?: return Result.failure()
        if (queue.stage != LocalState.UPLOAD) return Result.success()
        val lease = claim(id, queue) ?: return Result.success()
        val remoteId = draft.remoteDriveId ?: return fail(id, lease, IllegalStateException("missing remote id"))
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = { pauseForMotion(id, lease) },
            ) {
                // Each retry refreshes the complete frozen manifest; returned URLs remain in memory.
                dependencies.remote.uploadAssets(remoteId, draft.snapshot().assetUploads())
            }
            if (execution is ParkedExecutionResult.Paused) return Result.failure()
            val now = dependencies.clock()
            // A zero-row result means a replacement worker already owns or completed this row.
            dependencies.database.uploadQueueDao().completeUploadStage(id, lease.attempt, now)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, lease, error)
        }
    }
}

class PublishDriveWorker(
    appContext: Context,
    params: WorkerParameters,
    dependencies: PublishWorkerDependencies,
) : StagedPublishWorker(appContext, params, dependencies) {
    override suspend fun doWork(): Result {
        val id = draftId ?: return Result.failure()
        val draft = dependencies.database.draftDao().find(id) ?: return Result.failure()
        val queue = dependencies.database.uploadQueueDao().find(id) ?: return Result.failure()
        if (queue.stage == LocalState.COMPLETE || draft.state == LocalState.PUBLISHED) return Result.success()
        if (queue.stage != LocalState.PUBLISH) return Result.success()
        val lease = claim(id, queue) ?: return Result.success()
        val remoteId = draft.remoteDriveId ?: return fail(id, lease, IllegalStateException("missing remote id"))
        val expectedVersion = draft.remoteVersion ?: return fail(id, lease, IllegalStateException("missing remote version"))
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = { pauseForMotion(id, lease) },
            ) {
                dependencies.remote.publishDrive(remoteId, expectedVersion, draft.clientRequestId)
            }
            val acknowledgement = when (execution) {
                ParkedExecutionResult.Paused -> return Result.failure()
                is ParkedExecutionResult.Completed -> execution.value
            }
            val now = dependencies.clock()
            try {
                dependencies.database.withTransaction {
                    // The original draft is retained after acknowledgement; only publication metadata changes.
                    if (dependencies.database.draftDao().markPublishedCas(
                        id, draft.state, remoteId, expectedVersion, acknowledgement.version, now,
                    ) != 1) throw StaleWorkerTransition()
                    if (dependencies.database.uploadQueueDao().completePublishStage(id, lease.attempt, now) != 1) {
                        throw StaleWorkerTransition()
                    }
                    dependencies.database.publishConflictDao().resolveReplay(id, now)
                }
            } catch (_: StaleWorkerTransition) { return Result.success() }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteDriveException) {
            if (error.code == com.socialdrive.app.data.remote.RemoteFailureCode.VERSION_CONFLICT) {
                return persistVersionConflict(id, draft, remoteId, expectedVersion, lease)
            }
            val action = PublishRecoveryPolicy.decide(error.code, queue.uploadRewindCount)
            if (action == PublishFailureAction.STOP_PERMANENT) {
                if (error.code == com.socialdrive.app.data.remote.RemoteFailureCode.UPLOAD_INCOMPLETE) {
                    val now = dependencies.clock()
                    dependencies.database.uploadQueueDao().failPublishAtUploadLimit(
                        id,
                        lease.attempt,
                        PublishRecoveryPolicy.MAX_UPLOAD_REWINDS,
                        now,
                    )
                    return stalePublishResult(id)
                }
                return fail(id, lease, RemoteDriveException(error.code, retryable = false))
            }
            if (action != PublishFailureAction.REWIND_UPLOAD) return fail(id, lease, error)
            val now = dependencies.clock()
            val handoff = DurableRewindHandoff.commitThenSchedule(
                persist = {
                    dependencies.database.withTransaction {
                        dependencies.database.uploadQueueDao().rewindPublishToUpload(
                            draftId = id,
                            attempts = lease.attempt,
                            maxRewinds = PublishRecoveryPolicy.MAX_UPLOAD_REWINDS,
                            now = now,
                        ) == 1
                    }
                },
                schedule = { dependencies.rescheduler.replaceFromUpload(id) },
            )
            if (handoff.committed) {
                // If enqueue failed, restart recovery sees UPLOAD_INCOMPLETE and uses REPLACE.
                return Result.failure()
            }
            stalePublishResult(id)
        } catch (error: Throwable) {
            fail(id, lease, error)
        }
    }

    private suspend fun stalePublishResult(id: String): Result {
        val latestQueue = dependencies.database.uploadQueueDao().find(id)
        val latestDraft = dependencies.database.draftDao().find(id)
        return if (latestQueue?.stage == LocalState.COMPLETE || latestQueue?.status == LocalState.PUBLISHED || latestDraft?.state == LocalState.PUBLISHED) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private suspend fun persistVersionConflict(
        id: String,
        draft: DraftEntity,
        remoteId: String,
        expectedVersion: Int,
        lease: WorkerStageLease,
    ): Result = withContext(NonCancellable) {
        val now = dependencies.clock()
        val committed = try {
            dependencies.database.withTransaction {
                if (dependencies.database.uploadQueueDao().failVersionConflictCas(id, lease.attempt, now) != 1) {
                    throw StaleWorkerTransition()
                }
                dependencies.database.publishConflictDao().insertIfAbsent(
                    PublishConflictEntity(
                        draftId = id,
                        clientRequestId = draft.clientRequestId,
                        remoteDriveId = remoteId,
                        attemptedVersion = expectedVersion,
                        latestVersion = null,
                        latestStatus = null,
                        latestTitle = null,
                        latestSummary = null,
                        state = ConflictState.DETECTED,
                        replayCount = 0,
                        fetchGeneration = 0,
                        detectedAtEpochMs = now,
                        fetchedAtEpochMs = null,
                        resolvedAtEpochMs = null,
                        lastFetchErrorCode = null,
                    ),
                )
                val reviewable = dependencies.database.publishConflictDao().redetectPreservingReplayCount(
                    id,
                    draft.clientRequestId,
                    remoteId,
                    expectedVersion,
                    now,
                ) == 1
                if (!reviewable) dependencies.database.publishConflictDao().markUnreviewablePreservingReplayCount(id, now)
                val currentQueue = dependencies.database.uploadQueueDao().find(id) ?: throw StaleWorkerTransition()
                if (currentQueue.clientRequestId != draft.clientRequestId) throw StaleWorkerTransition()
            }
            true
        } catch (_: StaleWorkerTransition) {
            false
        }
        if (committed) Result.failure() else Result.success()
    }

}
