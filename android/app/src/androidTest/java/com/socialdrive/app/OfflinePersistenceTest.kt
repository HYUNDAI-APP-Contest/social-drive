package com.socialdrive.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.IdFactory
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.domain.MotionState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePersistenceTest {
    private lateinit var database: SocialDriveDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SocialDriveDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun processRestartAndFinishKeepOneSessionAndStableClientRequestId() = runTest {
        val values = ArrayDeque(listOf("recording-1", "draft-1", "request-1"))
        val repository = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 100L })
        val recording = repository.start()
        val reconstructedRepository = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 101L })
        val resumed = reconstructedRepository.start()

        val first = requireNotNull(reconstructedRepository.finishAsDraft(recording.id))
        val replay = requireNotNull(reconstructedRepository.finishAsDraft(recording.id))

        assertEquals(recording.id, resumed.id)
        assertEquals(first.id, replay.id)
        assertEquals("drive-request-1", first.clientRequestId)
        assertEquals(first.clientRequestId, replay.clientRequestId)
        assertTrue(first.originalContentRef.startsWith("local-recording://"))
    }

    @Test
    fun drivingAndUnknownNeverEnterThePublishQueue() = runTest {
        val values = ArrayDeque(listOf("recording-2", "draft-2", "request-2"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 200L })
        val queue = PublishQueueRepository(database, clock = { 200L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))

        assertTrue(queue.enqueue(draft.id, MotionState.Driving) is EnqueueResult.Rejected)
        assertTrue(queue.enqueue(draft.id, MotionState.Unknown) is EnqueueResult.Rejected)
        assertEquals(null, database.uploadQueueDao().find(draft.id))

        val parked = queue.enqueue(draft.id, MotionState.Parked)
        assertTrue(parked is EnqueueResult.Enqueued)
        val persisted = requireNotNull(database.uploadQueueDao().find(draft.id))
        assertEquals(LocalState.CREATE, persisted.stage)
        assertEquals(draft.clientRequestId, persisted.clientRequestId)
        assertNotEquals("", persisted.clientRequestId)
        assertEquals(false, com.socialdrive.app.data.DraftRepository(database).updateCopy(draft.id, "바뀐 제목", "바뀐 설명"))

        database.draftDao().markPublished(draft.id, "remote-demo", 300L)
        val published = requireNotNull(database.draftDao().find(draft.id))
        assertEquals(draft.originalContentRef, published.originalContentRef)
        assertEquals(LocalState.PUBLISHED, published.state)
    }
}
