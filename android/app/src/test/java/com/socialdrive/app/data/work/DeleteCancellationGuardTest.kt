package com.socialdrive.app.data.work

import com.socialdrive.app.domain.DeleteRequestState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import org.junit.Test

class DeleteCancellationGuardTest {
    @Test
    fun `cancellation while persisting retry failure hands off running lease`() = runBlocking {
        val persistenceEntered = CompletableDeferred<Unit>()
        var handoffs = 0
        var leaseState = DeleteRequestState.RUNNING
        val worker = launch {
            DeleteCancellationGuard.run(
                handoffInterruptedLease = {
                    handoffs += 1
                    if (leaseState == DeleteRequestState.RUNNING) leaseState = DeleteRequestState.RETRY_WAIT
                },
                leasedFlow = {
                    try {
                        throw SimulatedRemoteFailure()
                    } catch (_: SimulatedRemoteFailure) {
                        // Mirrors RemoteDeleteException -> persistFailure(RETRY_WAIT) suspension.
                        persistenceEntered.complete(Unit)
                        awaitCancellation()
                    }
                },
            )
        }

        persistenceEntered.await()
        worker.cancelAndJoin()
        assertEquals(1, handoffs)
        assertEquals(DeleteRequestState.RETRY_WAIT, leaseState)
    }

    @Test
    fun `cancellation while persisting terminal 409 hands off running lease`() = runBlocking {
        val terminalCasEntered = CompletableDeferred<Unit>()
        var handoffs = 0
        var leaseState = DeleteRequestState.RUNNING
        val worker = launch {
            DeleteCancellationGuard.run(
                handoffInterruptedLease = {
                    handoffs += 1
                    if (leaseState == DeleteRequestState.RUNNING) leaseState = DeleteRequestState.RETRY_WAIT
                },
                leasedFlow = {
                    try {
                        throw SimulatedDelete409()
                    } catch (_: SimulatedDelete409) {
                        // Mirrors DELETE 409 -> persistFailure(VERSION_CONFLICT) suspension.
                        terminalCasEntered.complete(Unit)
                        awaitCancellation()
                    }
                },
            )
        }

        terminalCasEntered.await()
        worker.cancelAndJoin()
        assertEquals(1, handoffs)
        assertEquals(DeleteRequestState.RETRY_WAIT, leaseState)
    }
}

private class SimulatedRemoteFailure : RuntimeException()
private class SimulatedDelete409 : RuntimeException()
