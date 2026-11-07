package com.socialdrive.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.socialdrive.app.data.DraftRepository
import com.socialdrive.app.data.ConflictReviewRepository
import com.socialdrive.app.data.ConflictReplayScheduler
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.RoutePrivacyRepository
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.FlavorRemoteDriveApiFactory
import com.socialdrive.app.data.remote.FlavorRemoteDeleteApiFactory
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.FlavorRemoteConflictReviewApiFactory
import com.socialdrive.app.data.work.PublishScheduler
import com.socialdrive.app.data.work.DeleteWorkScheduler
import com.socialdrive.app.data.work.DeleteWorkerDependencies
import com.socialdrive.app.data.work.PublishRescheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
import com.socialdrive.app.data.work.PublishWorkChainEnqueuer
import com.socialdrive.app.integration.FlavorIntegrationFactory
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.PendingRouteUploadCanceller
import com.socialdrive.app.safety.ParkedExecutionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

open class SocialDriveApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: SocialDriveDatabase by lazy { SocialDriveDatabase.create(this) }
    val remoteDriveApi: RemoteDriveApi by lazy { FlavorRemoteDriveApiFactory.create() }
    val remoteDeleteApi by lazy { FlavorRemoteDeleteApiFactory.create() }
    val integrationBundle by lazy { FlavorIntegrationFactory.create(this) }
    val vehicleMotionSource get() = integrationBundle.vehicleMotionSource
    val navigationGateway get() = integrationBundle.navigationGateway
    val auxiliaryCapabilitySource get() = integrationBundle.auxiliaryCapabilitySource
    val parkedExecutionGate by lazy { ParkedExecutionGate(vehicleMotionSource.status.value.motion) }
    val recordingRepository: RecordingRepository by lazy { RecordingRepository(database) }
    val draftRepository: DraftRepository by lazy { DraftRepository(database) }
    val publishQueueRepository: PublishQueueRepository by lazy { PublishQueueRepository(database) }
    val routePrivacyRepository: RoutePrivacyRepository by lazy {
        RoutePrivacyRepository(
            database,
            PendingRouteUploadCanceller {
                WorkManager.getInstance(this).cancelAllWorkByTag(PublishScheduler.ROUTE_UPLOAD_TAG)
            },
            currentMotion = { vehicleMotionSource.status.value.motion },
            onDeletePending = { draftId ->
                WorkManager.getInstance(this).cancelUniqueWork(PublishScheduler.uniqueName(draftId))
                deleteWorkScheduler.enqueue(draftId)
            },
        )
    }
    val deleteWorkScheduler: DeleteWorkScheduler by lazy {
        DeleteWorkScheduler(WorkManager.getInstance(this), database, parkedExecutionGate)
    }
    val publishScheduler: PublishScheduler by lazy {
        PublishScheduler(WorkManager.getInstance(this), publishQueueRepository, parkedExecutionGate)
    }
    val conflictReviewRepository: ConflictReviewRepository by lazy {
        ConflictReviewRepository(
            database,
            FlavorRemoteConflictReviewApiFactory.create(),
            parkedExecutionGate,
            ConflictReplayScheduler { draftId ->
                PublishWorkChainEnqueuer(WorkManager.getInstance(this)).replacePublishOnly(draftId)
            },
        )
    }
    private val publishWorkerFactory by lazy {
        val deferredRescheduler = PublishRescheduler { draftId ->
            PublishWorkChainEnqueuer(WorkManager.getInstance(this)).replaceFromUpload(draftId)
        }
        PublishWorkerFactory(
            PublishWorkerDependencies(database, remoteDriveApi, parkedExecutionGate, deferredRescheduler),
            DeleteWorkerDependencies(database, remoteDeleteApi, parkedExecutionGate),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(publishWorkerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            vehicleMotionSource.initialize()
            navigationGateway.initialize()
            auxiliaryCapabilitySource.initialize()
        }
        applicationScope.launch {
            vehicleMotionSource.status.collect { status ->
                parkedExecutionGate.transitionTo(status.motion)
                if (status.motion == MotionState.Parked) {
                    deleteWorkScheduler.resumeWhenParked()
                    publishScheduler.recoverAfterProcessRestart()
                }
            }
        }
        recoverQueuedWork()
    }

    override fun onTerminate() {
        navigationGateway.release()
        auxiliaryCapabilitySource.release()
        vehicleMotionSource.release()
        super.onTerminate()
    }

    protected open fun recoverQueuedWork() {
        applicationScope.launch {
            conflictReviewRepository.recoverInterruptedFetches()
            deleteWorkScheduler.recoverAfterProcessRestart()
            publishScheduler.recoverAfterProcessRestart()
        }
    }
}
