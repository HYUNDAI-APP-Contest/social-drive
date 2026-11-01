package com.socialdrive.app.safety

import com.socialdrive.app.domain.MotionState
import java.util.concurrent.atomic.AtomicReference

fun interface MotionStateSource {
    suspend fun current(): MotionState
}

class MutableMotionStateSource(initial: MotionState = MotionState.Unknown) : MotionStateSource {
    private val value = AtomicReference(initial)

    override suspend fun current(): MotionState = value.get()

    fun update(state: MotionState) {
        value.set(state)
    }
}
