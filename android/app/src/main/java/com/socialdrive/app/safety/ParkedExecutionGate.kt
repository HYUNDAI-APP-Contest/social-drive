package com.socialdrive.app.safety

import com.socialdrive.app.domain.MotionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ParkedExecutionResult<out T> {
    data class Completed<T>(val value: T) : ParkedExecutionResult<T>
    data object Paused : ParkedExecutionResult<Nothing>
}

private class MotionTransitionCancellation : CancellationException("Observed motion is not parked")

/**
 * Serializes observed motion transitions with the start of cooperative remote work.
 * It cannot make a physical vehicle signal and network service globally atomic. Once a non-parked
 * transition is observed, however, no new block starts, registered children are cancelled, and
 * callers persist a replay-safe pause before a later Parked transition completes.
 */
class ParkedExecutionGate(
    initial: MotionState = MotionState.Unknown,
    private val afterRegistration: suspend () -> Unit = {},
) {
    private val transitionMutex = Mutex()
    private val registryMutex = Mutex()
    private val active = mutableSetOf<Deferred<ParkedExecutionResult<*>>>()
    private var observedState = initial

    suspend fun transitionTo(next: MotionState) = transitionMutex.withLock transition@{
        if (next == MotionState.Parked) {
            while (true) {
                val children = registryMutex.withLock {
                    active.removeAll { it.isCompleted }
                    if (active.isEmpty()) {
                        observedState = MotionState.Parked
                        return@transition
                    }
                    active.toList()
                }
                children.joinAll()
            }
        } else {
            val children = registryMutex.withLock {
                observedState = next
                active.toList()
            }
            children.forEach { it.cancel(MotionTransitionCancellation()) }
            children.joinAll()
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
        val allowed = AtomicBoolean(false)
        val registered = CompletableDeferred<Unit>()
        lateinit var child: Deferred<ParkedExecutionResult<T>>
        child = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                registered.await()
                afterRegistration()
                if (!allowed.get()) {
                    withContext(NonCancellable) { onPaused() }
                    return@async ParkedExecutionResult.Paused
                }
                currentCoroutineContext().ensureActive()
                ParkedExecutionResult.Completed(remoteBlock())
            } catch (error: MotionTransitionCancellation) {
                withContext(NonCancellable) { onPaused() }
                ParkedExecutionResult.Paused
            }
        }
        registryMutex.withLock {
            allowed.set(observedState == MotionState.Parked)
            active += child
            child.start()
            registered.complete(Unit)
        }
        try {
            child.await()
        } finally {
            registryMutex.withLock { active.remove(child) }
        }
    }
}
