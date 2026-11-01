package com.socialdrive.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.PublishedDeleteRequestEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.local.UploadQueueEntity
import com.socialdrive.app.data.remote.AssetUploadCommand
import com.socialdrive.app.data.remote.RemoteDeleteApi
import com.socialdrive.app.data.remote.RemoteDeleteConfirmed
import com.socialdrive.app.data.remote.RemoteDeleteException
import com.socialdrive.app.data.remote.RemoteDraftRef
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.RemotePublishAck
import com.socialdrive.app.data.work.DeleteWorkScheduler
import com.socialdrive.app.data.work.DeleteWorkerDependencies
import com.socialdrive.app.data.work.PublishRescheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
import com.socialdrive.app.domain.DeleteFailureCode
import com.socialdrive.app.domain.DeleteLifecyclePolicy
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.safety.ParkedExecutionGate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishedDeleteWorkTest {
    private lateinit var context: Context
    private lateinit var database: SocialDriveDatabase
    private lateinit var remote: DeleteRemote
    private lateinit var motion: ParkedExecutionGate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SocialDriveDatabase::class.java).allowMainThreadQueries().build()
        remote = DeleteRemote()
        motion = ParkedExecutionGate(MotionState.Parked)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    PublishWorkerFactory(
                        PublishWorkerDependencies(database, NoPublishRemote, motion, PublishRescheduler {}),
                        DeleteWorkerDependencies(database, remote, motion, clock = { 2_000L }),
                    ),
                )
                .build(),
        )
    }

    @After fun tearDown() = database.close()

    @Test
    fun backend204ConfirmsTombstoneAndRetainsLocalOriginal() = runTest {
        seed("draft-delete-1")
        runDelete("draft-delete-1")

        assertEquals(1, remote.calls)
        assertEquals(DeleteRequestState.CONFIRMED.name, database.publishedDeleteDao().find("draft-delete-1")?.state)
        assertEquals(LocalState.PUBLISHED, database.draftDao().find("draft-delete-1")?.state)
        assertEquals("local-recording://recording-delete-1", database.draftDao().find("draft-delete-1")?.originalContentRef)
    }

    @Test
    fun backend404IsNotTreatedAsDeletionReceipt() = runTest {
        seed("draft-delete-2")
        remote.failure = DeleteFailureCode.NOT_FOUND
        runDelete("draft-delete-2")

        assertEquals(DeleteRequestState.NOT_FOUND_UNCONFIRMED.name, database.publishedDeleteDao().find("draft-delete-2")?.state)
        assertEquals(LocalState.PUBLISHED, database.draftDao().find("draft-delete-2")?.state)
    }

    @Test
    fun motionChangeBeforeExecutionMakesZeroRemoteCallsAndRestoresAttempt() = runTest {
        seed("draft-delete-3")
        motion.transitionTo(MotionState.Unknown)
        runDelete("draft-delete-3")

        val request = requireNotNull(database.publishedDeleteDao().find("draft-delete-3"))
        assertEquals(0, remote.calls)
        assertEquals(DeleteRequestState.DELETE_PENDING.name, request.state)
        assertEquals(0, request.attempts)
    }

    @Test
    fun tombstoneBlocksPublishClaimEvenIfQueueRowExists() = runTest {
        seed("draft-delete-4")
        database.uploadQueueDao().upsert(
            UploadQueueEntity("draft-delete-4", "request-delete-4", LocalState.PUBLISH, LocalState.QUEUED, 0, 0, null, 0, 0),
        )
        assertEquals(
            0,
            database.uploadQueueDao().claimStage("draft-delete-4", LocalState.PUBLISH, LocalState.QUEUED, 0, 1, 2_000),
        )
    }

    @Test
    fun processRestartNormalizesRunningLeaseBeforeUniqueRecovery() = runTest {
        seed("draft-delete-5", state = DeleteRequestState.RUNNING, attempts = 1)
        val manager = WorkManager.getInstance(context)
        DeleteWorkScheduler(manager, database, motion).recoverAfterProcessRestart()

        val request = requireNotNull(database.publishedDeleteDao().find("draft-delete-5"))
        assertEquals(DeleteRequestState.RETRY_WAIT.name, request.state)
        assertEquals("INTERRUPTED", request.lastErrorCode)
        assertEquals(
            1,
            manager.getWorkInfosForUniqueWork(DeleteWorkScheduler.uniqueName("draft-delete-5"))
                .get(5, TimeUnit.SECONDS).size,
        )
    }

    private suspend fun seed(
        draftId: String,
        state: DeleteRequestState = DeleteRequestState.DELETE_PENDING,
        attempts: Int = 0,
    ) {
        val suffix = draftId.substringAfterLast('-')
        val requestId = "request-delete-$suffix"
        val remoteId = "remote-delete-$suffix"
        database.draftDao().insert(
            DraftEntity(
                draftId, "recording-delete-$suffix", requestId, "제목", "설명", "route-memory-default",
                "local-recording://recording-delete-$suffix", LocalState.PUBLISHED, remoteId, 2,
                1_000, 1_000, 1_000,
            ),
        )
        database.publishedDeleteDao().insertIfAbsent(
            PublishedDeleteRequestEntity(
                draftId, requestId, remoteId, DeleteLifecyclePolicy.stableKeyHash(requestId, remoteId),
                state.name, attempts, 1_000, null, 1_000, 1_000, null,
            ),
        )
    }

    private fun runDelete(draftId: String) {
        val manager = WorkManager.getInstance(context)
        DeleteWorkScheduler(manager, database, motion).enqueue(draftId)
        val work = manager.getWorkInfosForUniqueWork(DeleteWorkScheduler.uniqueName(draftId)).get(5, TimeUnit.SECONDS)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        work.forEach { driver.setAllConstraintsMet(it.id) }
    }
}

private class DeleteRemote : RemoteDeleteApi {
    var calls = 0
    var failure: DeleteFailureCode? = null
    override suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed {
        calls += 1
        failure?.let { throw RemoteDeleteException(it) }
        return RemoteDeleteConfirmed
    }
}

private object NoPublishRemote : RemoteDriveApi {
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): RemoteDraftRef = error("not expected")
    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) = error("not expected")
    override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): RemotePublishAck = error("not expected")
}
