package com.socialdrive.app.domain

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DeleteLifecycleTest {
    @Test
    fun `claim is bounded to pending and retry wait`() {
        assertEquals(1, DeleteLifecyclePolicy.claim(DeleteQueueSnapshot(DeleteRequestState.DELETE_PENDING, 0))?.attempt)
        assertEquals(4, DeleteLifecyclePolicy.claim(DeleteQueueSnapshot(DeleteRequestState.RETRY_WAIT, 3))?.attempt)
        assertNull(DeleteLifecyclePolicy.claim(DeleteQueueSnapshot(DeleteRequestState.RETRY_WAIT, 4)))
        assertNull(DeleteLifecyclePolicy.claim(DeleteQueueSnapshot(DeleteRequestState.RUNNING, 1)))
        assertNull(DeleteLifecyclePolicy.claim(DeleteQueueSnapshot(DeleteRequestState.CONFIRMED, 1)))
    }

    @Test
    fun `transient failures retry at most four attempts`() {
        val first = DeleteLifecyclePolicy.decideFailure(DeleteFailureCode.OFFLINE, 1, 1_000)
        assertEquals(DeleteFailureDecision.Retry(31_000), first)
        assertTrue(DeleteLifecyclePolicy.decideFailure(DeleteFailureCode.SERVER_BUSY, 3, 1_000) is DeleteFailureDecision.Retry)
        assertEquals(
            DeleteFailureDecision.Stop(DeleteRequestState.FAILED_PERMANENT),
            DeleteLifecyclePolicy.decideFailure(DeleteFailureCode.TIMEOUT, 4, 1_000),
        )
    }

    @Test
    fun `not found is unconfirmed and authentication and conflict never auto retry`() {
        assertEquals(
            DeleteFailureDecision.Stop(DeleteRequestState.NOT_FOUND_UNCONFIRMED),
            DeleteLifecyclePolicy.decideFailure(DeleteFailureCode.NOT_FOUND, 1, 0),
        )
        for (code in listOf(DeleteFailureCode.AUTHENTICATION, DeleteFailureCode.FORBIDDEN)) {
            assertEquals(
                DeleteFailureDecision.Stop(DeleteRequestState.AUTH_REQUIRED),
                DeleteLifecyclePolicy.decideFailure(code, 1, 0),
            )
        }
        for (code in listOf(DeleteFailureCode.VERSION_CONFLICT, DeleteFailureCode.IDEMPOTENCY_CONFLICT)) {
            assertEquals(
                DeleteFailureDecision.Stop(DeleteRequestState.VERSION_CONFLICT),
                DeleteLifecyclePolicy.decideFailure(code, 1, 0),
            )
        }
    }

    @Test
    fun `external cancellation leaves a durable replay handoff unless attempts are exhausted`() {
        assertEquals(DeleteFailureDecision.Retry(5_000), DeleteLifecyclePolicy.interrupted(1, 5_000))
        assertEquals(
            DeleteFailureDecision.Stop(DeleteRequestState.FAILED_PERMANENT),
            DeleteLifecyclePolicy.interrupted(DeleteLifecyclePolicy.MAX_ATTEMPTS, 5_000),
        )
    }

    @Test
    fun `stable key hash is deterministic scoped and contains no input`() {
        val first = DeleteLifecyclePolicy.stableKeyHash("request-delete-0001", "drive-delete-0001")
        assertEquals(first, DeleteLifecyclePolicy.stableKeyHash("request-delete-0001", "drive-delete-0001"))
        assertNotEquals(first, DeleteLifecyclePolicy.stableKeyHash("request-delete-0002", "drive-delete-0001"))
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertTrue("request-delete-0001" !in first)
        assertTrue("drive-delete-0001" !in first)
    }
}
