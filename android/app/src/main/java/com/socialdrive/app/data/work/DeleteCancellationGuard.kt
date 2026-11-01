package com.socialdrive.app.data.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Owns the cancellation boundary for the complete leased delete flow, including Room failure CAS.
 * If cancellation interrupts any nested transition, the caller gets one non-cancellable chance to
 * move the same RUNNING lease to a replay-safe state before cancellation is propagated.
 */
object DeleteCancellationGuard {
    suspend fun <T> run(
        handoffInterruptedLease: suspend () -> Unit,
        leasedFlow: suspend () -> T,
    ): T = try {
        leasedFlow()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { handoffInterruptedLease() }
        throw cancelled
    }
}
