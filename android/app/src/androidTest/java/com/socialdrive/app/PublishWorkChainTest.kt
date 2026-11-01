package com.socialdrive.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.socialdrive.app.data.IdFactory
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.FakeRemoteDriveApi
import com.socialdrive.app.data.work.PublishScheduler
import com.socialdrive.app.data.work.PublishWorkerDependencies
import com.socialdrive.app.data.work.PublishWorkerFactory
import com.socialdrive.app.domain.MotionState
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SocialDriveDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(PublishWorkerFactory(PublishWorkerDependencies(database, FakeRemoteDriveApi())))
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
        val scheduler = PublishScheduler(WorkManager.getInstance(context), PublishQueueRepository(database, clock = { 300L }))

        scheduler.enqueue(draft.id, MotionState.Parked)
        scheduler.enqueue(draft.id, MotionState.Parked)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PublishScheduler.uniqueName(draft.id))
            .get(5, TimeUnit.SECONDS)
        assertEquals(3, work.size)
    }
}
