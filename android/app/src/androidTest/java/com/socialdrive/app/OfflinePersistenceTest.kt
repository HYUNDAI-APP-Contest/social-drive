package com.socialdrive.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.IdFactory
import com.socialdrive.app.data.PublishQueueRepository
import com.socialdrive.app.data.RecordingRepository
import com.socialdrive.app.data.RoutePrivacyRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.NormalizedRoutePointEntity
import com.socialdrive.app.data.local.RoutePrivacyEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.PendingRouteUploadCanceller
import com.socialdrive.app.domain.PublishedDeleteDecision
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RouteLocationConsentPolicy
import com.socialdrive.app.domain.FutureRouteCollector
import com.socialdrive.app.domain.CollectedRoute
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.domain.NormalizedRoutePoint
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
    fun processRestartReattachesCollectorToActiveRecording() = runTest {
        var beginCount = 0
        val collector = object : FutureRouteCollector {
            override suspend fun start() = Unit
            override suspend fun beginRecording() { beginCount++ }
            override suspend fun snapshotAndClear() = CollectedRoute.Empty
            override suspend fun stopAndAwait() = Unit
        }
        val first = RecordingRepository(database, IdFactory { "recording-restart" }, routeCollector = collector)
        first.start()

        val reconstructed = RecordingRepository(database, routeCollector = collector)
        assertTrue(reconstructed.restoreActiveCollection())
        assertEquals(2, beginCount)
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
    fun recordingPersistsOnlyNormalizedCollectorOutput() = runTest {
        database.routePrivacyDao().upsert(grantedRoutePrivacy())
        val values = ArrayDeque(listOf("recording-route", "draft-route", "request-route"))
        val collector = object : FutureRouteCollector {
            var began = false
            override suspend fun start() = Unit
            override suspend fun beginRecording() { began = true }
            override suspend fun snapshotAndClear() = CollectedRoute(
                points = listOf(
                    NormalizedRoutePoint(0, 0f, 1f),
                    NormalizedRoutePoint(1, .5f, .4f),
                    NormalizedRoutePoint(2, 1f, 0f),
                ),
                distanceMeters = 12_345,
            )
            override suspend fun stopAndAwait() = Unit
        }
        val clockValues = ArrayDeque(listOf(250L, 3_661_250L))
        val recordings = RecordingRepository(
            database,
            IdFactory { values.removeFirst() },
            clock = { clockValues.removeFirst() },
            routeCollector = collector,
        )

        val recording = recordings.start()
        val draft = requireNotNull(recordings.finishAsDraft(recording.id))
        val stored = database.routePrivacyDao().pointsForRecording(draft.recordingId)
        assertEquals(12_345, draft.distanceMeters)
        assertEquals(12_345, database.recordingDao().find(recording.id)?.distanceMeters)

        assertTrue(collector.began)
        assertEquals(listOf(0f, .5f, 1f), draft.routePoints.map { it.horizontal })
        assertEquals(listOf(0f, .5f, 1f), stored.map { it.horizontal })
        assertEquals(listOf(1f, .4f, 0f), stored.map { it.vertical })
        assertTrue(stored.all { it.publicationState == "UNPUBLISHED" })
    }

    @Test
    fun withdrawnConsentDiscardsSnapshotTakenBeforeDraftTransaction() = runTest {
        database.routePrivacyDao().upsert(
            grantedRoutePrivacy().copy(consentState = RouteConsentState.Withdrawn.name, consentPolicyVersion = null),
        )
        val values = ArrayDeque(listOf("recording-withdrawn", "draft-withdrawn", "request-withdrawn"))
        val collector = object : FutureRouteCollector {
            override suspend fun start() = Unit
            override suspend fun beginRecording() = Unit
            override suspend fun snapshotAndClear() = CollectedRoute(
                points = listOf(NormalizedRoutePoint(0, .2f, .4f)),
                distanceMeters = 999,
            )
            override suspend fun stopAndAwait() = Unit
        }
        val recordings = RecordingRepository(
            database,
            IdFactory { values.removeFirst() },
            clock = { 300L },
            routeCollector = collector,
        )

        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))

        assertTrue(draft.routePoints.isEmpty())
        assertEquals(0, draft.distanceMeters)
        assertEquals(0, database.routePrivacyDao().pointCount())
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

    @Test
    fun withdrawalPurgesOnlyUnpublishedPointsAndTombstonesPendingUpload() = runTest {
        val values = ArrayDeque(listOf("recording-10", "draft-10", "request-10"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 1_000L })
        val queue = PublishQueueRepository(database, clock = { 1_000L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        queue.enqueue(draft.id, MotionState.Parked)
        database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, "remote-withdraw-10", 1, 1_000L)
        database.routePrivacyDao().insertPoint(NormalizedRoutePointEntity(draft.recordingId, 0, .2f, .3f, "UNPUBLISHED"))
        database.routePrivacyDao().insertPoint(NormalizedRoutePointEntity(draft.recordingId, 1, .4f, .5f, "PUBLISHED"))
        var cancelled = emptySet<String>()
        val scheduledRemoteCleanup = mutableSetOf<String>()
        val privacy = RoutePrivacyRepository(
            database,
            PendingRouteUploadCanceller { cancelled = it },
            currentMotion = { MotionState.Parked },
            clock = { 1_001L },
            onDeletePending = scheduledRemoteCleanup::add,
        )

        val receipt = privacy.withdraw()

        assertEquals(1, receipt.purgedUnpublishedPointCount)
        assertEquals(1, database.routePrivacyDao().pointCount())
        assertEquals(RouteConsentState.Withdrawn.name, database.routePrivacyDao().find()?.consentState)
        assertEquals(LocalState.CANCELLED, database.uploadQueueDao().find(draft.id)?.stage)
        assertEquals(LocalState.PRIVACY_WITHDRAWN, database.uploadQueueDao().find(draft.id)?.status)
        assertEquals(0, database.uploadQueueDao().claimStage(draft.id, LocalState.CANCELLED, LocalState.PRIVACY_WITHDRAWN, 0, 1, 1_002L))
        assertEquals(0, database.uploadQueueDao().completeCreateStage(draft.id, 1, 1_002L))
        assertEquals(setOf(draft.id), cancelled)
        assertEquals(setOf(draft.id), receipt.remoteCleanupDraftIds)
        assertEquals(setOf(draft.id), scheduledRemoteCleanup)
        assertEquals("remote-withdraw-10", database.publishedDeleteDao().find(draft.id)?.remoteDriveId)
        assertEquals(DeleteRequestState.DELETE_PENDING.name, database.publishedDeleteDao().find(draft.id)?.state)
    }

    @Test
    fun publishedDeleteRequiresConfirmationAndPersistsOnlyDeletePending() = runTest {
        val values = ArrayDeque(listOf("recording-11", "draft-11", "request-11"))
        val recordings = RecordingRepository(database, IdFactory { values.removeFirst() }, clock = { 1_100L })
        val draft = requireNotNull(recordings.finishAsDraft(recordings.start().id))
        database.draftDao().markPublished(draft.id, "remote-published", 2, 1_101L)
        var authoritativeMotion = MotionState.Parked
        val privacy = RoutePrivacyRepository(
            database,
            PendingRouteUploadCanceller {},
            currentMotion = { authoritativeMotion },
            clock = { 1_102L },
        )

        assertEquals(PublishedDeleteDecision.ConfirmationRequired, privacy.requestPublishedDelete(draft.id, false))
        assertEquals(null, database.publishedDeleteDao().find(draft.id))
        authoritativeMotion = MotionState.Driving
        assertEquals(PublishedDeleteDecision.MotionRejected(MotionState.Driving), privacy.requestPublishedDelete(draft.id, true))
        assertEquals(null, database.publishedDeleteDao().find(draft.id))
        authoritativeMotion = MotionState.Unknown
        assertEquals(PublishedDeleteDecision.MotionRejected(MotionState.Unknown), privacy.requestPublishedDelete(draft.id, true))
        assertEquals(null, database.publishedDeleteDao().find(draft.id))
        authoritativeMotion = MotionState.Parked
        assertEquals(PublishedDeleteDecision.DeletePending, privacy.requestPublishedDelete(draft.id, true))
        val request = requireNotNull(database.publishedDeleteDao().find(draft.id))
        assertEquals(LocalState.DELETE_PENDING, request.state)
        assertEquals("drive-request-11", request.clientRequestId)
        assertEquals("remote-published", request.remoteDriveId)
        assertEquals(64, request.idempotencyKeyHash.length)
        assertEquals(0, request.attempts)
        assertEquals(LocalState.PUBLISHED, database.draftDao().find(draft.id)?.state)
    }

    private fun grantedRoutePrivacy() = RoutePrivacyEntity(
        consentState = RouteConsentState.Granted.name,
        consentPolicyVersion = RouteLocationConsentPolicy.VERSION,
        routeSource = "LiveAvailable",
        unavailableReason = null,
        updatedAtEpochMs = 1L,
    )
}
