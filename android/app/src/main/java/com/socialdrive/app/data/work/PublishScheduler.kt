package com.socialdrive.app.data.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.safety.ParkedExecutionGate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PublishScheduler(
    private val workManager: WorkManager,
    private val queue: PublishQueueRepository,
    private val executionGate: ParkedExecutionGate,
) {
    private val recoveryMutex = Mutex()
    suspend fun enqueue(draftId: String, motionState: MotionState): EnqueueResult {
        val result = queue.enqueue(draftId, motionState)
        if (result is EnqueueResult.Enqueued || result is EnqueueResult.AlreadyQueued) schedule(draftId, ExistingWorkPolicy.KEEP)
        return result
    }

    suspend fun recoverAfterProcessRestart() = recoveryMutex.withLock {
        executionGate.coordinateRecovery(
            whenNotParked = { queue.pauseRecoverableForMotion() },
            whenParked = {
                queue.recoverable().forEach { item ->
                    val policy = if (item.status == LocalState.PAUSED_MOTION) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                    schedule(item.draftId, policy)
                }
            },
        )
    }

    private fun schedule(draftId: String, policy: ExistingWorkPolicy) {
        val input = Data.Builder().putString(StagedPublishWorker.KEY_DRAFT_ID, draftId).build()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val create = request<CreateDriveWorker>(input, constraints, draftId)
        val upload = request<UploadAssetsWorker>(input, constraints, draftId)
        val publish = request<PublishDriveWorker>(input, constraints, draftId)
        workManager.beginUniqueWork(uniqueName(draftId), policy, create)
            .then(upload)
            .then(publish)
            .enqueue()
    }

    private inline fun <reified T : ListenableWorker> request(
        input: Data,
        constraints: Constraints,
        draftId: String,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<T>()
        .setInputData(input)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag("publish:$draftId")
        .build()

    companion object {
        fun uniqueName(draftId: String) = "publish-draft-$draftId"
    }
}
