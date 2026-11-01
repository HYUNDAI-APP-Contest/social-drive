package com.socialdrive.app.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.socialdrive.app.data.local.PublishedDeleteRequestEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.RemoteDeleteApi
import com.socialdrive.app.data.remote.RemoteDeleteException
import com.socialdrive.app.domain.DeleteFailureCode
import com.socialdrive.app.domain.DeleteFailureDecision
import com.socialdrive.app.domain.DeleteLifecyclePolicy
import com.socialdrive.app.domain.DeleteQueueSnapshot
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.safety.ParkedExecutionGate
import com.socialdrive.app.safety.ParkedExecutionResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeleteWorkerDependencies(
    val database: SocialDriveDatabase,
    val remote: RemoteDeleteApi,
    val executionGate: ParkedExecutionGate,
    val clock: () -> Long = System::currentTimeMillis,
)

class DeletePublishedDriveWorker(
    appContext: Context,
    params: WorkerParameters,
    private val dependencies: DeleteWorkerDependencies,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        val request = dependencies.database.publishedDeleteDao().find(draftId) ?: return Result.success()
        val lease = DeleteLifecyclePolicy.claim(request.snapshot()) ?: return Result.success()
        val claimed = dependencies.database.publishedDeleteDao().claim(
            draftId = draftId,
            clientRequestId = request.clientRequestId,
            remoteDriveId = request.remoteDriveId,
            idempotencyKeyHash = request.idempotencyKeyHash,
            expectedState = request.state,
            expectedAttempts = request.attempts,
            runningAttempt = lease.attempt,
            now = dependencies.clock(),
        )
        if (claimed != 1) return Result.success()

        return DeleteCancellationGuard.run(
            handoffInterruptedLease = { persistInterruptedLease(draftId, lease.attempt) },
        ) {
            runClaimedLease(draftId, request, lease.attempt)
        }
    }

    private suspend fun runClaimedLease(
        draftId: String,
        request: PublishedDeleteRequestEntity,
        attempt: Int,
    ): Result {
        return try {
            val execution = dependencies.executionGate.execute(
                onPaused = {
                    dependencies.database.publishedDeleteDao().pauseForMotionCas(
                        draftId,
                        runningAttempt = attempt,
                        restoredAttempts = attempt - 1,
                        now = dependencies.clock(),
                    )
                },
            ) {
                dependencies.remote.deletePublishedDrive(request.remoteDriveId, request.idempotencyKeyHash)
            }
            if (execution is ParkedExecutionResult.Paused) {
                Result.failure()
            } else {
                dependencies.database.publishedDeleteDao().confirmCas(draftId, attempt, dependencies.clock())
                // A 204 confirms only the remote deletion. The local original draft is deliberately retained.
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteDeleteException) {
            persistFailure(draftId, attempt, failure.failureCode)
        } catch (_: Throwable) {
            persistFailure(draftId, attempt, DeleteFailureCode.UNKNOWN)
        }
    }

    private suspend fun persistInterruptedLease(draftId: String, attempt: Int) {
        val now = dependencies.clock()
        when (val decision = DeleteLifecyclePolicy.interrupted(attempt, now)) {
            is DeleteFailureDecision.Retry -> dependencies.database.publishedDeleteDao().failCas(
                draftId, attempt, DeleteRequestState.RETRY_WAIT.name,
                decision.nextAttemptAtEpochMs, "INTERRUPTED", now,
            )
            is DeleteFailureDecision.Stop -> dependencies.database.publishedDeleteDao().failCas(
                draftId, attempt, decision.state.name, 0, "INTERRUPTED", now,
            )
        }
    }

    private suspend fun persistFailure(draftId: String, attempt: Int, code: DeleteFailureCode): Result {
        val now = dependencies.clock()
        return when (val decision = DeleteLifecyclePolicy.decideFailure(code, attempt, now)) {
            is DeleteFailureDecision.Retry -> {
                val updated = dependencies.database.publishedDeleteDao().failCas(
                    draftId, attempt, DeleteRequestState.RETRY_WAIT.name,
                    decision.nextAttemptAtEpochMs, code.name, now,
                )
                if (updated == 1) Result.retry() else Result.success()
            }
            is DeleteFailureDecision.Stop -> {
                dependencies.database.publishedDeleteDao().failCas(
                    draftId, attempt, decision.state.name, 0, code.name, now,
                )
                Result.failure()
            }
        }
    }

    companion object { const val KEY_DRAFT_ID = "deleteDraftId" }
}

class DeleteWorkScheduler(
    private val workManager: WorkManager,
    private val database: SocialDriveDatabase,
    private val executionGate: ParkedExecutionGate,
) {
    private val recoveryMutex = Mutex()
    fun enqueue(
        draftId: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        initialDelayMs: Long = 0,
    ) {
        val input = Data.Builder().putString(DeletePublishedDriveWorker.KEY_DRAFT_ID, draftId).build()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<DeletePublishedDriveWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(initialDelayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag("delete:$draftId")
            .build()
        workManager.enqueueUniqueWork(uniqueName(draftId), policy, request)
    }

    suspend fun recoverAfterProcessRestart() = recover(normalizeInterrupted = true)

    suspend fun resumeWhenParked() = recover(normalizeInterrupted = false)

    private suspend fun recover(normalizeInterrupted: Boolean) = recoveryMutex.withLock {
        val now = System.currentTimeMillis()
        if (normalizeInterrupted) {
            database.publishedDeleteDao().normalizeInterrupted(now)
            database.publishedDeleteDao().failAtLimit(now)
        }
        executionGate.coordinateRecovery(
            whenNotParked = { Unit },
            whenParked = {
                database.publishedDeleteDao().recoverable().forEach { request ->
                    enqueue(
                        request.draftId,
                        ExistingWorkPolicy.REPLACE,
                        (request.nextAttemptAtEpochMs - now).coerceAtLeast(0),
                    )
                }
            },
        )
    }

    companion object { fun uniqueName(draftId: String) = "delete-draft-$draftId" }
}

private fun PublishedDeleteRequestEntity.snapshot() = DeleteQueueSnapshot(
    state = runCatching { DeleteRequestState.valueOf(state) }.getOrDefault(DeleteRequestState.LEGACY_UNREVIEWABLE),
    attempts = attempts,
    lastErrorCode = lastErrorCode,
)
