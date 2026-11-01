package com.socialdrive.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.data.ConflictReplayScheduler
import com.socialdrive.app.data.ConflictReviewRepository
import com.socialdrive.app.data.local.ConflictState
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.PublishConflictEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.local.UploadQueueEntity
import com.socialdrive.app.data.remote.RemoteConflictReviewApi
import com.socialdrive.app.domain.ConflictReviewDecision
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.OwnedDriveConflictSnapshot
import com.socialdrive.app.safety.ParkedExecutionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConflictReviewPersistenceTest {
    private lateinit var database: SocialDriveDatabase
    private lateinit var remote: FakeReviewApi
    private lateinit var gate: ParkedExecutionGate
    private var scheduled = 0

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SocialDriveDatabase::class.java).allowMainThreadQueries().build()
        remote = FakeReviewApi()
        gate = ParkedExecutionGate(MotionState.Parked)
    }

    @After fun close() = database.close()

    @Test fun explicitFetchIsOneRequestPerTapAndGenerationRejectsOldResponse() = runTest {
        seed()
        val repository = repository()
        assertEquals(ConflictReviewDecision.Applied, repository.fetchLatest(DRAFT_ID))
        assertEquals(1, remote.calls)
        assertEquals(ConflictReviewDecision.Coalesced, repository.fetchLatest(DRAFT_ID))
        assertEquals(1, remote.calls)
        val conflict = requireNotNull(database.publishConflictDao().find(DRAFT_ID))
        assertEquals(0, database.publishConflictDao().storeSnapshotCas(
            DRAFT_ID, REQUEST_ID, REMOTE_ID, 4, conflict.fetchGeneration - 1,
            99, "DRAFT", "stale", "stale", 3,
        ))
        assertEquals(5, database.publishConflictDao().find(DRAFT_ID)?.latestVersion)
    }

    @Test fun parkedAdoptPreservesLocalContentAndSchedulesPublishOnlyOnce() = runTest {
        seed()
        val repository = repository()
        repository.fetchLatest(DRAFT_ID)
        assertEquals(ConflictReviewDecision.Applied, repository.adoptLatestAndReplay(DRAFT_ID))
        val draft = requireNotNull(database.draftDao().find(DRAFT_ID))
        assertEquals("로컬 제목", draft.title)
        assertEquals("로컬 본문", draft.description)
        assertEquals("local-recording://recording-1", draft.originalContentRef)
        assertEquals(REQUEST_ID, draft.clientRequestId)
        assertEquals(5, draft.remoteVersion)
        val queue = requireNotNull(database.uploadQueueDao().find(DRAFT_ID))
        assertEquals(LocalState.PUBLISH, queue.stage)
        assertEquals(LocalState.QUEUED, queue.status)
        val conflict = requireNotNull(database.publishConflictDao().find(DRAFT_ID))
        assertEquals(ConflictState.REPLAY_QUEUED, conflict.state)
        assertEquals(1, conflict.replayCount)
        assertEquals(1, scheduled)
        assertEquals(1, database.publishConflictDao().redetectPreservingReplayCount(
            DRAFT_ID, REQUEST_ID, REMOTE_ID, 5, 4,
        ))
        assertEquals(1, database.publishConflictDao().find(DRAFT_ID)?.replayCount)
        assertEquals(1, database.publishConflictDao().keepLocalCas(DRAFT_ID, REQUEST_ID, REMOTE_ID, 5, 5))
        assertEquals(1, database.publishConflictDao().find(DRAFT_ID)?.replayCount)
    }

    @Test fun drivingAndUnknownDoNotFetchOrMutate() = runTest {
        for (motion in listOf(MotionState.Driving, MotionState.Unknown)) {
            seed(suffix = motion.name)
            gate.transitionTo(motion)
            val id = "$DRAFT_ID-${motion.name}"
            assertEquals(ConflictReviewDecision.NotParked, repository().fetchLatest(id))
            assertEquals(ConflictState.DETECTED, database.publishConflictDao().find(id)?.state)
        }
        assertEquals(0, remote.calls)
    }

    @Test fun motionTransitionDuringFetchCancelsAndCannotPersistSnapshot() = runTest {
        seed()
        remote.block = true
        val request = async { repository().fetchLatest(DRAFT_ID) }
        remote.entered.await()
        gate.transitionTo(MotionState.Driving)
        assertEquals(ConflictReviewDecision.NotParked, request.await())
        val conflict = requireNotNull(database.publishConflictDao().find(DRAFT_ID))
        assertEquals(ConflictState.FETCH_FAILED, conflict.state)
        assertNull(conflict.latestVersion)
    }

    @Test fun callerCancellationNormalizesFetchingGenerationBeforeRethrow() = runTest {
        seed()
        remote.block = true
        val request = async { repository().fetchLatest(DRAFT_ID) }
        remote.entered.await()
        request.cancelAndJoin()
        val conflict = requireNotNull(database.publishConflictDao().find(DRAFT_ID))
        assertEquals(ConflictState.FETCH_FAILED, conflict.state)
        assertEquals("INTERRUPTED", conflict.lastFetchErrorCode)
        assertNull(conflict.latestVersion)
    }

    @Test fun restartNormalizesFetchingWithoutNetworkOrReplay() = runTest {
        seed()
        assertEquals(1, database.publishConflictDao().claimFetch(DRAFT_ID, REQUEST_ID, REMOTE_ID, 4))
        assertEquals(1, repository().recoverInterruptedFetches())
        val conflict = requireNotNull(database.publishConflictDao().find(DRAFT_ID))
        assertEquals(ConflictState.FETCH_FAILED, conflict.state)
        assertEquals("INTERRUPTED", conflict.lastFetchErrorCode)
        assertEquals(0, remote.calls)
        assertEquals(0, scheduled)
    }

    @Test fun transitionBetweenDialogAndConfirmPreventsAdoptMutation() = runTest {
        seed()
        val repository = repository()
        repository.fetchLatest(DRAFT_ID)
        gate.transitionTo(MotionState.Unknown)
        assertEquals(ConflictReviewDecision.NotParked, repository.adoptLatestAndReplay(DRAFT_ID))
        assertEquals(4, database.draftDao().find(DRAFT_ID)?.remoteVersion)
        assertEquals(LocalState.FAILED, database.uploadQueueDao().find(DRAFT_ID)?.status)
        assertEquals(0, scheduled)
    }

    private fun repository() = ConflictReviewRepository(
        database, remote, gate, ConflictReplayScheduler { scheduled += 1 }, clock = { 3L },
    )

    private suspend fun seed(suffix: String = "") {
        val postfix = suffix.takeIf(String::isNotEmpty)?.let { "-$it" }.orEmpty()
        val draftId = "$DRAFT_ID$postfix"
        val requestId = "$REQUEST_ID$postfix"
        val remoteId = "$REMOTE_ID$postfix"
        database.draftDao().insert(
            DraftEntity(draftId, "recording-1$postfix", requestId, "로컬 제목", "로컬 본문", "route-memory-default",
                "local-recording://recording-1", LocalState.QUEUED, remoteId, 4, 1, 2, null),
        )
        database.uploadQueueDao().upsert(
            UploadQueueEntity(draftId, requestId, LocalState.PUBLISH, LocalState.FAILED, 2, 2, LocalState.VERSION_CONFLICT, 0, 2),
        )
        database.publishConflictDao().insertIfAbsent(
            PublishConflictEntity(draftId, requestId, remoteId, 4, null, null, null, null,
                ConflictState.DETECTED, 0, 0, 2, null, null, null),
        )
    }

    private class FakeReviewApi : RemoteConflictReviewApi {
        var calls = 0
        var block = false
        val entered = CompletableDeferred<Unit>()
        override suspend fun getOwnedDrive(remoteDriveId: String): OwnedDriveConflictSnapshot {
            calls += 1
            if (block) {
                entered.complete(Unit)
                awaitCancellation()
            }
            return OwnedDriveConflictSnapshot(remoteDriveId, 5, "DRAFT", "서버 제목", "서버 요약")
        }
    }

    companion object {
        private const val DRAFT_ID = "draft-conflict"
        private const val REQUEST_ID = "request-conflict"
        private const val REMOTE_ID = "drive-remote-conflict"
    }
}
