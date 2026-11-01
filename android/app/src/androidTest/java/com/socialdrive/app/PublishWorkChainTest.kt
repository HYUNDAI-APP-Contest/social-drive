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
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.IdFactory
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.ConflictState
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.PublishConflictEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.local.UploadQueueEntity
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.AssetUploadCommand
import com.socialdrive.app.data.remote.RemoteDraftRef
import com.socialdrive.app.data.remote.RemoteDriveException
import com.socialdrive.app.data.remote.RemoteFailureCode
import com.socialdrive.app.data.work.PublishScheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
import com.socialdrive.app.data.work.PublishRescheduler
import com.socialdrive.app.data.work.PublishWorkChainEnqueuer
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.safety.ParkedExecutionGate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishWorkChainTest {
    private lateinit var context: Context
    private lateinit var database: SocialDriveDatabase
    private val motion = ParkedExecutionGate(MotionState.Parked)
    private lateinit var remote: CountingRemoteDriveApi

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SocialDriveDatabase::class.java).allowMainThreadQueries().build()
        remote = CountingRemoteDriveApi()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(PublishWorkerFactory(PublishWorkerDependencies(database, remote, motion, PublishRescheduler {})))
                .build(),
        )
    }

    @After
    fun tearDown() {
        TestWorkManagerLifecycle.close(WorkManager.getInstance(context))
        database.close()
    }

    @Test
    fun theSameDraftCreatesOnePersistentThreeStageChain() = runTest {
        val values = ArrayDeque(listOf("recording-3", "draft-3", "request-3"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 300L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        val scheduler = PublishScheduler(WorkManager.getInstance(context), PublishQueueRepository(database, clock = { 300L }), motion)

        scheduler.enqueue(draft.id, MotionState.Parked)
        scheduler.enqueue(draft.id, MotionState.Parked)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id))
            .get(5, TimeUnit.SECONDS)
        assertEquals(3, work.size)
    }

    @Test
    fun motionChangeBeforeWorkerRunPausesWithoutRemoteCalls() = runTest {
        val values = ArrayDeque(listOf("recording-4", "draft-4", "request-4"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 400L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        val scheduler = PublishScheduler(WorkManager.getInstance(context), PublishQueueRepository(database, clock = { 400L }), motion)
        scheduler.enqueue(draft.id, MotionState.Parked)
        motion.transitionTo(MotionState.Driving)

        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id)).get(5, TimeUnit.SECONDS)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        work.forEach { driver.setAllConstraintsMet(it.id) }
        awaitUniqueWorkFinished(draft.id)

        assertEquals(0, remote.calls)
        assertEquals(LocalState.PAUSED_MOTION, database.uploadQueueDao().find(draft.id)?.status)

        motion.transitionTo(MotionState.Parked)
        scheduler.recoverAfterProcessRestart()
        val resumed = manager.getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id)).get(5, TimeUnit.SECONDS)
        assertEquals(3, resumed.count { !it.state.isFinished })
    }

    @Test
    fun legacyRemoteIdWithoutVersionReplaysCreateWithStableIdentity() = runTest {
        val values = ArrayDeque(listOf("recording-8", "draft-8", "request-8"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 800L })
        val queueRepository = PublishQueueRepository(database, clock = { 800L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        queueRepository.enqueue(draft.id, MotionState.Parked)
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "remote-legacy", null, 801L)
        database.uploadQueueDao().updateStage(
            draft.id, LocalState.CREATE, LocalState.QUEUED, 0, 801L, "REMOTE_VERSION_REQUIRED", 801L,
        )
        remote.createResult = RemoteDraftRef("remote-legacy", 4)

        val scheduler = PublishScheduler(WorkManager.getInstance(context), queueRepository, motion)
        scheduler.recoverAfterProcessRestart()
        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id)).get(5, TimeUnit.SECONDS)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        work.forEach { driver.setAllConstraintsMet(it.id) }
        awaitUniqueWorkFinished(draft.id)

        assertEquals(1, remote.createCalls)
        assertEquals("remote-legacy", database.draftDao().find(draft.id)?.remoteDriveId)
        assertEquals(true, requireNotNull(database.draftDao().find(draft.id)?.remoteVersion) >= 4)
    }

    @Test
    fun legacyCreateReplayWithDifferentRemoteIdFailsClosed() = runTest {
        val values = ArrayDeque(listOf("recording-9", "draft-9", "request-9"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 900L })
        val queueRepository = PublishQueueRepository(database, clock = { 900L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        queueRepository.enqueue(draft.id, MotionState.Parked)
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "remote-legacy", null, 901L)
        database.uploadQueueDao().updateStage(
            draft.id, LocalState.CREATE, LocalState.QUEUED, 0, 901L, "REMOTE_VERSION_REQUIRED", 901L,
        )
        remote.createResult = RemoteDraftRef("remote-other", 4)

        val scheduler = PublishScheduler(WorkManager.getInstance(context), queueRepository, motion)
        scheduler.recoverAfterProcessRestart()
        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id)).get(5, TimeUnit.SECONDS)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        work.forEach { driver.setAllConstraintsMet(it.id) }
        awaitUniqueWorkFinished(draft.id)

        assertEquals(1, remote.createCalls)
        assertEquals("remote-legacy", database.draftDao().find(draft.id)?.remoteDriveId)
        assertEquals(null, database.draftDao().find(draft.id)?.remoteVersion)
        assertEquals("REJECTED", database.uploadQueueDao().find(draft.id)?.lastErrorCode)
    }

    @Test
    fun versionConflictPersistsTerminalReviewBeforeWorkerReturns() = runTest {
        val values = ArrayDeque(listOf("recording-c", "draft-c", "request-c"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 1_000L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        val queueRepository = PublishQueueRepository(database, clock = { 1_000L })
        val scheduler = PublishScheduler(WorkManager.getInstance(context), queueRepository, motion)
        remote.publishError = RemoteDriveException(RemoteFailureCode.VERSION_CONFLICT, false)
        scheduler.enqueue(draft.id, MotionState.Parked)
        val manager = WorkManager.getInstance(context)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        manager.getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id)).get(5, TimeUnit.SECONDS)
            .forEach { driver.setAllConstraintsMet(it.id) }
        awaitUniqueWorkFinished(draft.id)

        val queue = requireNotNull(database.uploadQueueDao().find(draft.id))
        assertEquals(LocalState.PUBLISH, queue.stage)
        assertEquals(LocalState.FAILED, queue.status)
        assertEquals(LocalState.VERSION_CONFLICT, queue.lastErrorCode)
        val conflict = requireNotNull(database.publishConflictDao().find(draft.id))
        assertEquals(ConflictState.DETECTED, conflict.state)
        assertEquals(draft.clientRequestId, conflict.clientRequestId)
        assertEquals(EnqueueResult.NeedsConflictReview, queueRepository.enqueue(draft.id, MotionState.Parked))
        assertEquals("local-recording://${draft.recordingId}", database.draftDao().find(draft.id)?.originalContentRef)
    }

    @Test
    fun restartedPublishWorkerCannotClaimCommittedConflictFailure() = runTest {
        database.draftDao().insert(
            DraftEntity(
                "draft-crash", "recording-crash", "request-crash", "local title", "local body",
                "route-memory-default", "local-recording://recording-crash", LocalState.QUEUED,
                "drive-remote-crash", 4, 1_200L, 1_201L, null,
            ),
        )
        database.uploadQueueDao().upsert(
            UploadQueueEntity(
                "draft-crash", "request-crash", LocalState.PUBLISH, LocalState.FAILED,
                3, 1_201L, LocalState.VERSION_CONFLICT, 0, 1_201L,
            ),
        )
        database.publishConflictDao().insertIfAbsent(
            PublishConflictEntity(
                "draft-crash", "request-crash", "drive-remote-crash", 4,
                null, null, null, null, ConflictState.DETECTED, 0, 1,
                1_201L, null, null, null,
            ),
        )

        val manager = WorkManager.getInstance(context)
        PublishWorkChainEnqueuer(manager).replacePublishOnly("draft-crash")
        val work = manager.getWorkInfosForUniqueWork(PublishScheduler.uniqueName("draft-crash"))
            .get(5, TimeUnit.SECONDS)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        work.forEach { driver.setAllConstraintsMet(it.id) }
        awaitUniqueWorkFinished("draft-crash")

        assertEquals(0, remote.publishCalls)
        val queue = requireNotNull(database.uploadQueueDao().find("draft-crash"))
        assertEquals(LocalState.FAILED, queue.status)
        assertEquals(LocalState.VERSION_CONFLICT, queue.lastErrorCode)
        assertEquals(3, queue.attempts)
        assertEquals(ConflictState.DETECTED, database.publishConflictDao().find("draft-crash")?.state)
    }

    @Test
    fun transientFailedRowRemainsClaimableWithinWorkerRetryPolicy() = runTest {
        database.uploadQueueDao().upsert(
            UploadQueueEntity(
                "draft-conflict-direct", "request-conflict-direct", LocalState.PUBLISH, LocalState.FAILED,
                2, 1_300L, LocalState.VERSION_CONFLICT, 0, 1_300L,
            ),
        )
        assertEquals(
            0,
            database.uploadQueueDao().claimStage(
                "draft-conflict-direct", LocalState.PUBLISH, LocalState.FAILED, 2, 3, 1_301L,
            ),
        )
        assertEquals(
            LocalState.VERSION_CONFLICT,
            database.uploadQueueDao().find("draft-conflict-direct")?.lastErrorCode,
        )
        database.uploadQueueDao().upsert(
            UploadQueueEntity(
                "draft-transient", "request-transient", LocalState.PUBLISH, LocalState.FAILED,
                2, 1_300L, "SERVER_BUSY", 0, 1_300L,
            ),
        )
        assertEquals(
            1,
            database.uploadQueueDao().claimStage(
                "draft-transient", LocalState.PUBLISH, LocalState.FAILED, 2, 3, 1_301L,
            ),
        )
        val queue = requireNotNull(database.uploadQueueDao().find("draft-transient"))
        assertEquals(LocalState.RUNNING, queue.status)
        assertEquals(null, queue.lastErrorCode)
    }

    @Test
    fun replayQueuedRestartCreatesPublishOnlyChain() = runTest {
        val values = ArrayDeque(listOf("recording-r", "draft-r", "request-r"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 1_100L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        val queueRepository = PublishQueueRepository(database, clock = { 1_100L })
        queueRepository.enqueue(draft.id, MotionState.Parked)
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "drive-remote-replay", 5, 1_101L)
        database.uploadQueueDao().updateStage(draft.id, LocalState.PUBLISH, LocalState.QUEUED, 3, 1_101L, null, 1_101L)
        database.publishConflictDao().insertIfAbsent(
            PublishConflictEntity(draft.id, draft.clientRequestId, "drive-remote-replay", 4, 5, "DRAFT",
                "server", "summary", ConflictState.REPLAY_QUEUED, 1, 1, 1_100L, 1_101L, null, null),
        )
        val scheduler = PublishScheduler(WorkManager.getInstance(context), queueRepository, motion)
        scheduler.recoverAfterProcessRestart()
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id)).get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertEquals(0, remote.createCalls)
        assertEquals(0, remote.uploadCalls)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        work.forEach { driver.setAllConstraintsMet(it.id) }
        awaitUniqueWorkFinished(draft.id)
        assertEquals(1, remote.publishCalls)
        assertEquals(LocalState.COMPLETE, database.uploadQueueDao().find(draft.id)?.stage)
        assertEquals(ConflictState.RESOLVED, database.publishConflictDao().find(draft.id)?.state)
    }

    private suspend fun awaitUniqueWorkFinished(draftId: String) = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            while (true) {
                val work = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draftId))
                    .get(5, TimeUnit.SECONDS)
                if (work.isNotEmpty() && work.all { it.state.isFinished }) return@withTimeout
                delay(10)
            }
        }
    }
}

private class CountingRemoteDriveApi : RemoteDriveApi {
    var calls = 0
    var createCalls = 0
    var uploadCalls = 0
    var publishCalls = 0
    var createResult: RemoteDraftRef? = null
    var publishError: RemoteDriveException? = null
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): com.socialdrive.app.data.remote.RemoteDraftRef {
        calls += 1
        createCalls += 1
        return createResult ?: com.socialdrive.app.data.remote.RemoteDraftRef("remote-${draft.id}", 1)
    }
    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) { calls += 1; uploadCalls += 1 }
    override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): com.socialdrive.app.data.remote.RemotePublishAck {
        calls += 1
        publishCalls += 1
        publishError?.let { throw it }
        return com.socialdrive.app.data.remote.RemotePublishAck(expectedVersion + 1)
    }
}
