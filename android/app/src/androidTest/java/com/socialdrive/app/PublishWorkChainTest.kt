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
import com.socialdrive.app.data.IdFactory
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.AssetUploadCommand
import com.socialdrive.app.data.remote.RemoteDraftRef
import com.socialdrive.app.data.work.PublishScheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
import com.socialdrive.app.data.work.PublishRescheduler
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
    fun tearDown() = database.close()

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

        assertEquals(1, remote.createCalls)
        assertEquals("remote-legacy", database.draftDao().find(draft.id)?.remoteDriveId)
        assertEquals(null, database.draftDao().find(draft.id)?.remoteVersion)
        assertEquals("REJECTED", database.uploadQueueDao().find(draft.id)?.lastErrorCode)
    }
}

private class CountingRemoteDriveApi : RemoteDriveApi {
    var calls = 0
    var createCalls = 0
    var createResult: RemoteDraftRef? = null
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): com.socialdrive.app.data.remote.RemoteDraftRef {
        calls += 1
        createCalls += 1
        return createResult ?: com.socialdrive.app.data.remote.RemoteDraftRef("remote-${draft.id}", 1)
    }
    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) { calls += 1 }
    override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): com.socialdrive.app.data.remote.RemotePublishAck {
        calls += 1
        return com.socialdrive.app.data.remote.RemotePublishAck(expectedVersion + 1)
    }
}
