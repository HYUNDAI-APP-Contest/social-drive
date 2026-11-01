package com.socialdrive.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.socialdrive.app.data.DraftRepository
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.FakeRemoteDriveApi
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.work.PublishScheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.safety.MutableMotionStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class SocialDriveApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: SocialDriveDatabase by lazy { SocialDriveDatabase.create(this) }
    val remoteDriveApi: RemoteDriveApi by lazy { FakeRemoteDriveApi() }
    val motionStateSource by lazy { MutableMotionStateSource(MotionState.Unknown) }
    val recordingRepository: RecordingRepository by lazy { RecordingRepository(database) }
    val draftRepository: DraftRepository by lazy { DraftRepository(database) }
    val publishQueueRepository: PublishQueueRepository by lazy { PublishQueueRepository(database) }
    val publishScheduler: PublishScheduler by lazy {
        PublishScheduler(WorkManager.getInstance(this), publishQueueRepository, motionStateSource)
    }
    private val publishWorkerFactory by lazy {
        PublishWorkerFactory(PublishWorkerDependencies(database, remoteDriveApi, motionStateSource))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(publishWorkerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        recoverQueuedWork()
    }

    protected open fun recoverQueuedWork() {
        applicationScope.launch { publishScheduler.recoverAfterProcessRestart() }
    }
}
