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
    private val chainEnqueuer: PublishWorkChainEnqueuer = PublishWorkChainEnqueuer(workManager),
) {
    private val recoveryMutex = Mutex()
    suspend fun enqueue(draftId: String, motionState: MotionState): EnqueueResult {
        val result = queue.enqueue(draftId, motionState)
        if (result is EnqueueResult.Enqueued || result is EnqueueResult.AlreadyQueued) chainEnqueuer.enqueue(draftId, ExistingWorkPolicy.KEEP)
        return result
    }

    suspend fun recoverAfterProcessRestart() = recoveryMutex.withLock {
        executionGate.coordinateRecovery(
            whenNotParked = { queue.pauseRecoverableForMotion() },
            whenParked = {
                queue.recoverable().forEach { item ->
                    if (queue.isPublishOnlyConflictReplay(item.draftId)) {
                        chainEnqueuer.replacePublishOnly(item.draftId)
                        return@forEach
                    }
                    val policy = when (PublishRecoveryPolicy.recoveryEnqueueMode(item.status, item.stage, item.lastErrorCode)) {
                        RecoveryEnqueueMode.REPLACE -> ExistingWorkPolicy.REPLACE
                        RecoveryEnqueueMode.KEEP -> ExistingWorkPolicy.KEEP
                    }
                    chainEnqueuer.enqueue(item.draftId, policy)
                }
            },
        )
    }

    companion object {
        fun uniqueName(draftId: String) = "publish-draft-$draftId"
        const val ROUTE_UPLOAD_TAG = "publish:route"
    }
}

fun interface PublishRescheduler {
    fun replaceFromUpload(draftId: String)

    fun replacePublishOnly(draftId: String) = replaceFromUpload(draftId)
}

class PublishWorkChainEnqueuer(private val workManager: WorkManager) : PublishRescheduler {
    override fun replaceFromUpload(draftId: String) = enqueue(draftId, ExistingWorkPolicy.REPLACE)

    override fun replacePublishOnly(draftId: String) {
        val input = Data.Builder().putString(StagedPublishWorker.KEY_DRAFT_ID, draftId).build()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        workManager.enqueueUniqueWork(
            PublishScheduler.uniqueName(draftId),
            ExistingWorkPolicy.REPLACE,
            request<PublishDriveWorker>(input, constraints, draftId),
        )
    }

    fun enqueue(draftId: String, policy: ExistingWorkPolicy) {
        val input = Data.Builder().putString(StagedPublishWorker.KEY_DRAFT_ID, draftId).build()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val create = request<CreateDriveWorker>(input, constraints, draftId)
        val upload = request<UploadAssetsWorker>(input, constraints, draftId)
        val publish = request<PublishDriveWorker>(input, constraints, draftId)
        workManager.beginUniqueWork(PublishScheduler.uniqueName(draftId), policy, create)
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
        .addTag(PublishScheduler.ROUTE_UPLOAD_TAG)
        .build()

}
