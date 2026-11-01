package com.socialdrive.app.safety

import com.socialdrive.app.domain.MotionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ParkedExecutionResult<out T> {
    data class Completed<T>(val value: T) : ParkedExecutionResult<T>
    data object Paused : ParkedExecutionResult<Nothing>
}

private class MotionTransitionCancellation : CancellationException("Observed motion is not parked")
private data class ActiveExecution(
    val remoteChild: Deferred<*>,
    val settled: Deferred<Unit>,
)

private fun CancellationException.isMotionTransition(): Boolean =
    this is MotionTransitionCancellation || cause is MotionTransitionCancellation

/**
 * Serializes observed motion transitions with the start of cooperative remote work.
 * It cannot make a physical vehicle signal and network service globally atomic. Once a non-parked
 * transition is observed, however, no new block starts, registered children are cancelled, and
 * callers persist a replay-safe pause before a later Parked transition completes.
 */
class ParkedExecutionGate(
    initial: MotionState = MotionState.Unknown,
    private val afterChildRegistered: suspend () -> Unit = {},
) {
    private val transitionMutex = Mutex()
    private val registryMutex = Mutex()
    private val active = mutableSetOf<ActiveExecution>()
    private var observedState = initial

    suspend fun transitionTo(next: MotionState) = transitionMutex.withLock transition@{
        if (next == MotionState.Parked) {
            while (true) {
                val children = registryMutex.withLock {
                    active.removeAll { it.settled.isCompleted }
                    if (active.isEmpty()) {
                        observedState = MotionState.Parked
                        return@transition
                    }
                    active.toList()
                }
                children.forEach { it.settled.await() }
            }
        } else {
            val children = registryMutex.withLock {
                observedState = next
                active.toList()
            }
            children.forEach { it.remoteChild.cancel(MotionTransitionCancellation()) }
            children.forEach { it.settled.await() }
        }
    }

    suspend fun coordinateRecovery(
        whenNotParked: suspend () -> Unit,
        whenParked: suspend () -> Unit,
    ) = registryMutex.withLock {
        if (observedState == MotionState.Parked) whenParked() else whenNotParked()
    }

    suspend fun <T> execute(
        onPaused: suspend () -> Unit,
        remoteBlock: suspend CoroutineScope.() -> T,
    ): ParkedExecutionResult<T> = coroutineScope {
        val pauseRecorded = AtomicBoolean(false)
        val bodyEntered = CompletableDeferred<Unit>()
        val startGate = CompletableDeferred<Unit>()
        suspend fun recordPauseOnce() {
            if (pauseRecorded.compareAndSet(false, true)) {
                withContext(NonCancellable) { onPaused() }
            }
        }
        val settled = CompletableDeferred<Unit>()
        lateinit var child: Deferred<ParkedExecutionResult<T>>
        lateinit var registration: ActiveExecution
        registryMutex.withLock {
            val allowed = observedState == MotionState.Parked
            child = async(start = CoroutineStart.UNDISPATCHED) {
                bodyEntered.complete(Unit)
                startGate.await()
                if (!allowed) return@async ParkedExecutionResult.Paused
                currentCoroutineContext().ensureActive()
                ParkedExecutionResult.Completed(remoteBlock())
            }
            registration = ActiveExecution(child, settled)
            active += registration
            bodyEntered.await()
        }
        try {
            afterChildRegistered()
            startGate.complete(Unit)
            try {
                child.await().also { result ->
                    if (result is ParkedExecutionResult.Paused) recordPauseOnce()
                }
            } catch (error: CancellationException) {
                if (!error.isMotionTransition()) throw error
                recordPauseOnce()
                ParkedExecutionResult.Paused
            }
        } finally {
            withContext(NonCancellable) {
                startGate.complete(Unit)
                settled.complete(Unit)
                registryMutex.withLock { active.remove(registration) }
            }
        }
    }
}
