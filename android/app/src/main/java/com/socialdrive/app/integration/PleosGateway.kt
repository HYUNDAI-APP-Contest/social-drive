package com.socialdrive.app.integration

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.MotionState

interface PleosGateway {
    fun motionState(): MotionState
    fun vehicleStateCapability(): CapabilityState
    fun navigationCapability(): CapabilityState
}

/** Demo-only boundary. Replace this implementation after Pleos capability approval. */
class FakePleosGateway : PleosGateway {
    override fun motionState(): MotionState = MotionState.Parked
    override fun vehicleStateCapability(): CapabilityState = CapabilityState.Unavailable
    override fun navigationCapability(): CapabilityState = CapabilityState.Unavailable
}
