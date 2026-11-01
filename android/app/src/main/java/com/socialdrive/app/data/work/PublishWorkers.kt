package com.socialdrive.app.data.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.RemoteDriveException
import com.socialdrive.app.data.remote.assetUploads
import com.socialdrive.app.safety.ParkedExecutionGate
import com.socialdrive.app.safety.ParkedExecutionResult
import kotlinx.coroutines.CancellationException

data class PublishWorkerDependencies(
    val database: SocialDriveDatabase,
    val remote: RemoteDriveApi,
    val executionGate: ParkedExecutionGate,
    val clock: () -> Long = System::currentTimeMillis,
)

class PublishWorkerFactory(private val dependencies: PublishWorkerDependencies) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        CreateDriveWorker::class.java.name -> CreateDriveWorker(appContext, workerParameters, dependencies)
        UploadAssetsWorker::class.java.name -> UploadAssetsWorker(appContext, workerParameters, dependencies)
        PublishDriveWorker::class.java.name -> PublishDriveWorker(appContext, workerParameters, dependencies)
        else -> null
    }
}

abstract class StagedPublishWorker(
    appContext: Context,
    params: WorkerParameters,
    protected val dependencies: PublishWorkerDependencies,
) : CoroutineWorker(appContext, params) {
    protected val draftId: String? get() = inputData.getString(KEY_DRAFT_ID)

    protected suspend fun pauseForMotion(
        draftId: String,
        stage: String,
        attempts: Int,
    ) {
        val now = dependencies.clock()
        dependencies.database.uploadQueueDao().updateStage(
            draftId = draftId,
            stage = stage,
            status = LocalState.PAUSED_MOTION,
            attempts = attempts,
            nextAttemptAt = now,
            lastErrorCode = "MOTION_NOT_PARKED",
            now = now,
        )
    }

    protected suspend fun fail(draftId: String, error: Throwable): Result {
        val queue = dependencies.database.uploadQueueDao().find(draftId) ?: return Result.failure()
        val remote = error as? RemoteDriveException
        val now = dependencies.clock()
        dependencies.database.uploadQueueDao().updateStage(
            draftId = draftId,
            stage = queue.stage,
            status = LocalState.FAILED,
            attempts = queue.attempts,
            nextAttemptAt = now,
            lastErrorCode = remote?.code?.name ?: "UNKNOWN",
            now = now,
        )
        return if (remote?.retryable == true && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
    }

    protected fun DraftEntity.snapshot() = DraftSnapshot(
        id = id,
        recordingId = recordingId,
        clientRequestId = clientRequestId,
        title = title,
        description = description,
        coverPreset = coverPreset,
        originalContentRef = originalContentRef,
        state = state,
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
        val startedAt = dependencies.clock()
        dependencies.database.uploadQueueDao().updateStage(id, queue.stage, LocalState.RUNNING, queue.attempts + 1, startedAt, null, startedAt)
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = { pauseForMotion(id, queue.stage, queue.attempts + 1) },
            ) {
                draft.remoteDriveId ?: dependencies.remote.createDrive(draft.clientRequestId, draft.snapshot())
            }
            val remoteId = when (execution) {
                ParkedExecutionResult.Paused -> return Result.failure()
                is ParkedExecutionResult.Completed -> execution.value
            }
            val now = dependencies.clock()
            dependencies.database.withTransaction {
                dependencies.database.draftDao().updateRemoteState(id, LocalState.QUEUED, remoteId, now)
                dependencies.database.uploadQueueDao().updateStage(id, LocalState.UPLOAD, LocalState.QUEUED, queue.attempts + 1, now, null, now)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, error)
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
        val startedAt = dependencies.clock()
        dependencies.database.uploadQueueDao().updateStage(id, queue.stage, LocalState.RUNNING, queue.attempts + 1, startedAt, null, startedAt)
        val remoteId = draft.remoteDriveId ?: return fail(id, IllegalStateException("missing remote id"))
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = { pauseForMotion(id, queue.stage, queue.attempts + 1) },
            ) {
                // Each retry uses the same server session key and checksum; URLs remain in memory.
                draft.snapshot().assetUploads().forEach { asset ->
                    dependencies.remote.uploadAsset(remoteId, asset)
                }
            }
            if (execution is ParkedExecutionResult.Paused) return Result.failure()
            val now = dependencies.clock()
            dependencies.database.uploadQueueDao().updateStage(id, LocalState.PUBLISH, LocalState.QUEUED, queue.attempts + 1, now, null, now)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, error)
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
        if (queue.stage != LocalState.PUBLISH) return Result.failure()
        val startedAt = dependencies.clock()
        dependencies.database.uploadQueueDao().updateStage(id, queue.stage, LocalState.RUNNING, queue.attempts + 1, startedAt, null, startedAt)
        val remoteId = draft.remoteDriveId ?: return fail(id, IllegalStateException("missing remote id"))
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = { pauseForMotion(id, queue.stage, queue.attempts + 1) },
            ) {
                dependencies.remote.publishDrive(remoteId, draft.clientRequestId)
            }
            if (execution is ParkedExecutionResult.Paused) return Result.failure()
            val now = dependencies.clock()
            dependencies.database.withTransaction {
                // The original draft is retained after acknowledgement for recovery and audit.
                dependencies.database.draftDao().markPublished(id, remoteId, now)
                dependencies.database.uploadQueueDao().updateStage(id, LocalState.COMPLETE, LocalState.PUBLISHED, queue.attempts + 1, 0, null, now)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, error)
        }
    }
}
