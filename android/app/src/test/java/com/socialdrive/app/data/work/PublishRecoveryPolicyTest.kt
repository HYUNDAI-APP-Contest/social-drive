package com.socialdrive.app.data.work

import com.socialdrive.app.data.remote.RemoteFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class PublishRecoveryPolicyTest {
    @Test
    fun `version conflict always stops for explicit user review`() {
        assertEquals(
            PublishFailureAction.STOP_PERMANENT,
            PublishRecoveryPolicy.decide(RemoteFailureCode.VERSION_CONFLICT, completedRewinds = 0),
        )
    }

    @Test
    fun `normal worker claim rejects conflict failure but accepts transient failure`() {
        assertNull(
            WorkerTransitionContract.claim(
                WorkerQueueSnapshot("PUBLISH", "FAILED", 3, "VERSION_CONFLICT"),
            ),
        )
        assertEquals(
            WorkerStageLease("PUBLISH", 4),
            WorkerTransitionContract.claim(
                WorkerQueueSnapshot("PUBLISH", "FAILED", 3, "SERVER_BUSY"),
            ),
        )
    }

    @Test
    fun `upload incomplete rewinds only within the durable bound`() {
        assertEquals(PublishFailureAction.REWIND_UPLOAD, PublishRecoveryPolicy.decide(RemoteFailureCode.UPLOAD_INCOMPLETE, 0))
        assertEquals(PublishFailureAction.REWIND_UPLOAD, PublishRecoveryPolicy.decide(RemoteFailureCode.UPLOAD_INCOMPLETE, 1))
        assertEquals(PublishFailureAction.STOP_PERMANENT, PublishRecoveryPolicy.decide(RemoteFailureCode.UPLOAD_INCOMPLETE, 2))
    }

    @Test
    fun `expired upload remains a standard bounded worker retry after adapter refresh bound`() {
        assertEquals(
            PublishFailureAction.STANDARD_FAILURE,
            PublishRecoveryPolicy.decide(RemoteFailureCode.UPLOAD_EXPIRED, completedRewinds = 0),
        )
    }

    @Test
    fun `durable rewind commits before replacement enqueue`() = runBlocking {
        val events = mutableListOf<String>()
        val result = DurableRewindHandoff.commitThenSchedule(
            persist = { events += "commit"; true },
            schedule = { events += "enqueue" },
        )
        assertEquals(listOf("commit", "enqueue"), events)
        assertEquals(RewindHandoffResult(committed = true, scheduled = true), result)
    }

    @Test
    fun `enqueue crash leaves a committed row for restart recovery replacement`() = runBlocking {
        val result = DurableRewindHandoff.commitThenSchedule(
            persist = { true },
            schedule = { error("simulated process gap") },
        )
        assertEquals(RewindHandoffResult(committed = true, scheduled = false), result)
        assertEquals(
            RecoveryEnqueueMode.REPLACE,
            PublishRecoveryPolicy.recoveryEnqueueMode("QUEUED", "UPLOAD", "UPLOAD_INCOMPLETE"),
        )
        assertEquals(
            RecoveryEnqueueMode.REPLACE,
            PublishRecoveryPolicy.recoveryEnqueueMode("QUEUED", "CREATE", "REMOTE_VERSION_REQUIRED"),
        )
    }

    @Test
    fun `failed legacy row repaired to create is replaced immediately for version replay`() {
        assertEquals(
            RecoveryEnqueueMode.REPLACE,
            PublishRecoveryPolicy.recoveryEnqueueMode(
                status = "QUEUED",
                stage = "CREATE",
                lastErrorCode = "REMOTE_VERSION_REQUIRED",
            ),
        )
        assertEquals(WorkerStageLease("CREATE", 5), WorkerTransitionContract.claim(WorkerQueueSnapshot("CREATE", "QUEUED", 4)))
    }

    @Test
    fun `stale rewind does not enqueue replacement`() = runBlocking {
        var scheduled = false
        val result = DurableRewindHandoff.commitThenSchedule(
            persist = { false },
            schedule = { scheduled = true },
        )
        assertEquals(RewindHandoffResult(committed = false, scheduled = false), result)
        assertEquals(false, scheduled)
    }

    @Test
    fun `admission issues one exact lease and rejects running overlap`() {
        val queued = WorkerQueueSnapshot(stage = "CREATE", status = "QUEUED", attempts = 3)
        val lease = requireNotNull(WorkerTransitionContract.claim(queued))
        assertEquals(WorkerStageLease("CREATE", 4), lease)
        assertNull(WorkerTransitionContract.claim(queued.copy(status = "RUNNING")))
        assertNull(WorkerTransitionContract.claim(queued.copy(status = "PUBLISHED")))
    }

    @Test
    fun `stale create cannot overwrite a newer remote version or published draft`() {
        val expected = DraftRemoteSnapshot("DRAFT", remoteDriveId = null, remoteVersion = null)
        assertEquals(true, WorkerTransitionContract.acceptsCreateResult(expected, expected))
        assertEquals(
            false,
            WorkerTransitionContract.acceptsCreateResult(
                DraftRemoteSnapshot("QUEUED", remoteDriveId = "remote-new", remoteVersion = 9),
                expected,
            ),
        )
        assertEquals(
            false,
            WorkerTransitionContract.acceptsCreateResult(
                DraftRemoteSnapshot("PUBLISHED", remoteDriveId = "remote-new", remoteVersion = 10),
                expected,
            ),
        )
    }

    @Test
    fun `legacy create replay fails closed when the returned remote id differs`() {
        assertEquals(true, WorkerTransitionContract.acceptsCreateReplayIdentity(null, "remote-created"))
        assertEquals(true, WorkerTransitionContract.acceptsCreateReplayIdentity("remote-existing", "remote-existing"))
        assertEquals(false, WorkerTransitionContract.acceptsCreateReplayIdentity("remote-existing", "remote-other"))
    }

    @Test
    fun `stale upload pause and failure cannot move a completed row backward`() {
        val lease = WorkerStageLease("UPLOAD", 2)
        val completed = WorkerQueueSnapshot("COMPLETE", "PUBLISHED", attempts = 3)
        assertEquals(false, WorkerTransitionContract.owns(completed, lease))
        assertNull(WorkerTransitionContract.transition(completed, lease, "UPLOAD", "PAUSED_MOTION"))
        assertNull(WorkerTransitionContract.transition(completed, lease, "UPLOAD", "FAILED"))
        assertNull(WorkerTransitionContract.transition(completed, lease, "PUBLISH", "QUEUED"))
    }

    @Test
    fun `stale publish acknowledgement cannot mark a changed frozen version`() {
        val expected = DraftRemoteSnapshot("QUEUED", remoteDriveId = "remote-1", remoteVersion = 7)
        assertEquals(
            false,
            WorkerTransitionContract.acceptsPublishResult(
                current = expected.copy(remoteVersion = 8),
                expected = expected,
                expectedRemoteId = "remote-1",
                expectedVersion = 7,
            ),
        )
        assertEquals(
            true,
            WorkerTransitionContract.acceptsPublishResult(expected, expected, "remote-1", 7),
        )
    }
}
