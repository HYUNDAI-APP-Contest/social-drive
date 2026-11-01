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
import com.socialdrive.app.data.work.PublishScheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
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
                .setWorkerFactory(PublishWorkerFactory(PublishWorkerDependencies(database, remote, motion)))
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
}

private class CountingRemoteDriveApi : RemoteDriveApi {
    var calls = 0
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String {
        calls += 1
        return "remote-${draft.id}"
    }
    override suspend fun uploadAsset(remoteDriveId: String, command: AssetUploadCommand) { calls += 1 }
    override suspend fun publishDrive(remoteDriveId: String, clientRequestId: String) { calls += 1 }
}
