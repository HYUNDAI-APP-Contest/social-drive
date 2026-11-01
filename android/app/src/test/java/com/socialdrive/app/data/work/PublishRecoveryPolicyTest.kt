package com.socialdrive.app.data.work

import com.socialdrive.app.data.remote.RemoteFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `stale rewind does not enqueue replacement`() = runBlocking {
        var scheduled = false
        val result = DurableRewindHandoff.commitThenSchedule(
            persist = { false },
            schedule = { scheduled = true },
        )
        assertEquals(RewindHandoffResult(committed = false, scheduled = false), result)
        assertEquals(false, scheduled)
    }
}
