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
        val drafts = com.socialdrive.app.data.DraftRepository(database)

        assertEquals(true, drafts.updateCopy(draft.id, "게시 전 제목", "게시 전 설명"))
        assertTrue(queue.enqueue(draft.id, MotionState.Driving) is EnqueueResult.Rejected)
        assertTrue(queue.enqueue(draft.id, MotionState.Unknown) is EnqueueResult.Rejected)
        assertEquals(null, database.uploadQueueDao().find(draft.id))

        val parked = queue.enqueue(draft.id, MotionState.Parked)
        assertTrue(parked is EnqueueResult.Enqueued)
        val persisted = requireNotNull(database.uploadQueueDao().find(draft.id))
        assertEquals(LocalState.CREATE, persisted.stage)
        assertEquals(draft.clientRequestId, persisted.clientRequestId)
        assertNotEquals("", persisted.clientRequestId)
        assertEquals(false, drafts.updateCopy(draft.id, "바뀐 제목", "바뀐 설명"))

        database.draftDao().markPublished(draft.id, "remote-demo", 2, 300L)
        val published = requireNotNull(database.draftDao().find(draft.id))
        assertEquals(draft.originalContentRef, published.originalContentRef)
        assertEquals(LocalState.PUBLISHED, published.state)
    }

    @Test
    fun frozenRemoteVersionAndBoundedUploadRewindSurviveRestart() = runTest {
        val values = ArrayDeque(listOf("recording-5", "draft-5", "request-5"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 500L })
        val queue = PublishQueueRepository(database, clock = { 500L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        queue.enqueue(draft.id, MotionState.Parked)
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "remote-frozen", 7, 501L)
        database.uploadQueueDao().updateStage(draft.id, LocalState.PUBLISH, LocalState.RUNNING, 3, 502L, null, 502L)

        assertEquals(0, database.uploadQueueDao().rewindPublishToUpload(draft.id, 2, 2, 503L))
        assertEquals(1, database.uploadQueueDao().rewindPublishToUpload(draft.id, 3, 2, 503L))
        database.uploadQueueDao().updateStage(draft.id, LocalState.PUBLISH, LocalState.RUNNING, 5, 504L, null, 504L)
        assertEquals(1, database.uploadQueueDao().rewindPublishToUpload(draft.id, 5, 2, 505L))
        database.uploadQueueDao().updateStage(draft.id, LocalState.PUBLISH, LocalState.RUNNING, 6, 506L, null, 506L)
        assertEquals(1, database.uploadQueueDao().failPublishAtUploadLimit(draft.id, 6, 2, 507L))
        database.uploadQueueDao().updateStage(draft.id, LocalState.COMPLETE, LocalState.PUBLISHED, 6, 0L, null, 506L)
        assertEquals(0, database.uploadQueueDao().rewindPublishToUpload(draft.id, 6, 2, 507L))
        assertEquals(0, database.uploadQueueDao().failPublishAtUploadLimit(draft.id, 6, 2, 507L))

        val reconstructedDraft = requireNotNull(database.draftDao().find(draft.id))
        val reconstructedQueue = requireNotNull(database.uploadQueueDao().find(draft.id))
        assertEquals(7, reconstructedDraft.remoteVersion)
        assertEquals(LocalState.COMPLETE, reconstructedQueue.stage)
        assertEquals(LocalState.PUBLISHED, reconstructedQueue.status)
        assertEquals(2, reconstructedQueue.uploadRewindCount)
    }

    @Test
    fun workerLeaseCasRejectsReplacedAndTerminalQueueRows() = runTest {
        val values = ArrayDeque(listOf("recording-6", "draft-6", "request-6"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 600L })
        val queueRepository = PublishQueueRepository(database, clock = { 600L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        queueRepository.enqueue(draft.id, MotionState.Parked)

        assertEquals(1, database.uploadQueueDao().claimStage(draft.id, LocalState.CREATE, LocalState.QUEUED, 0, 1, 601L))
        assertEquals(0, database.uploadQueueDao().claimStage(draft.id, LocalState.CREATE, LocalState.QUEUED, 0, 1, 601L))
        assertEquals(0, database.uploadQueueDao().pauseRunningStage(draft.id, LocalState.CREATE, 0, 602L))
        assertEquals(0, database.uploadQueueDao().failRunningStage(draft.id, LocalState.CREATE, 0, "STALE", 602L))

        assertEquals(
            1,
            database.draftDao().applyCreateResultCas(
                draft.id, LocalState.QUEUED, null, null, "remote-lease", 7, 603L,
            ),
        )
        assertEquals(1, database.uploadQueueDao().completeCreateStage(draft.id, 1, 603L))
        assertEquals(
            0,
            database.draftDao().applyCreateResultCas(
                draft.id, LocalState.QUEUED, null, null, "remote-stale", 1, 604L,
            ),
        )

        assertEquals(1, database.uploadQueueDao().claimStage(draft.id, LocalState.UPLOAD, LocalState.QUEUED, 1, 2, 605L))
        database.uploadQueueDao().updateStage(
            draft.id, LocalState.COMPLETE, LocalState.PUBLISHED, 3, 0L, null, 606L,
        )
        assertEquals(0, database.uploadQueueDao().completeUploadStage(draft.id, 2, 607L))
        assertEquals(0, database.uploadQueueDao().pauseRunningStage(draft.id, LocalState.UPLOAD, 2, 607L))
        assertEquals(0, database.uploadQueueDao().failRunningStage(draft.id, LocalState.UPLOAD, 2, "STALE", 607L))
        val terminal = requireNotNull(database.uploadQueueDao().find(draft.id))
        assertEquals(LocalState.COMPLETE, terminal.stage)
        assertEquals(LocalState.PUBLISHED, terminal.status)
    }

    @Test
    fun stalePublishAckCannotPublishAChangedFrozenVersion() = runTest {
        val values = ArrayDeque(listOf("recording-7", "draft-7", "request-7"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 700L })
        val queueRepository = PublishQueueRepository(database, clock = { 700L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        queueRepository.enqueue(draft.id, MotionState.Parked)
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "remote-publish", 7, 701L)
        database.uploadQueueDao().updateStage(draft.id, LocalState.PUBLISH, LocalState.QUEUED, 2, 702L, null, 702L)
        assertEquals(1, database.uploadQueueDao().claimStage(draft.id, LocalState.PUBLISH, LocalState.QUEUED, 2, 3, 703L))

        // A replacement/user-review path changed the frozen version while this worker was in flight.
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "remote-publish", 8, 704L)
        assertEquals(
            0,
            database.draftDao().markPublishedCas(
                draft.id, LocalState.QUEUED, "remote-publish", 7, 9, 705L,
            ),
        )
        assertEquals(LocalState.QUEUED, requireNotNull(database.draftDao().find(draft.id)).state)
        assertEquals(8, requireNotNull(database.draftDao().find(draft.id)).remoteVersion)
    }
}
