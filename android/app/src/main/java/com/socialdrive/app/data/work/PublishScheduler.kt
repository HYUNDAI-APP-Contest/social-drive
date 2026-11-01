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
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.safety.MotionStateSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PublishScheduler(
    private val workManager: WorkManager,
    private val queue: PublishQueueRepository,
    private val motionStateSource: MotionStateSource,
) {
    private val recoveryMutex = Mutex()
    suspend fun enqueue(draftId: String, motionState: MotionState): EnqueueResult {
        val result = queue.enqueue(draftId, motionState)
        if (result is EnqueueResult.Enqueued || result is EnqueueResult.AlreadyQueued) schedule(draftId)
        return result
    }

    suspend fun recoverAfterProcessRestart() = recoveryMutex.withLock {
        if (motionStateSource.current() != MotionState.Parked) {
            queue.pauseRecoverableForMotion()
        } else {
            queue.recoverable().forEach(::schedule)
        }
    }

    private fun schedule(draftId: String) {
        val input = Data.Builder().putString(StagedPublishWorker.KEY_DRAFT_ID, draftId).build()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val create = request<CreateDriveWorker>(input, constraints, draftId)
        val upload = request<UploadAssetsWorker>(input, constraints, draftId)
        val publish = request<PublishDriveWorker>(input, constraints, draftId)
        workManager.beginUniqueWork(uniqueName(draftId), ExistingWorkPolicy.KEEP, create)
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
