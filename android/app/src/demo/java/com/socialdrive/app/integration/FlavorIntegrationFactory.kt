package com.socialdrive.app.integration

import android.content.Context
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationBundle
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.NavigationGateway
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.NavigationRequestValidator
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.domain.NavigationValidation
import com.socialdrive.app.domain.VehicleMotionSource
import com.socialdrive.app.domain.VehicleMotionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

object FlavorIntegrationFactory {
    fun create(context: Context): IntegrationBundle = IntegrationBundle(
        vehicleMotionSource = DemoVehicleMotionSource(),
        navigationGateway = DemoNavigationGateway(),
    )
}

private class DemoVehicleMotionSource : VehicleMotionSource {
    private val mutableStatus = MutableStateFlow(
        VehicleMotionStatus(
            motion = MotionState.Parked,
            integration = CapabilityStatus(CapabilityState.Available, IntegrationSource.Demo, "데모"),
        ),
    )
    override val status: StateFlow<VehicleMotionStatus> = mutableStatus.asStateFlow()

    override suspend fun initialize() = Unit

    override suspend fun requestSimulation(motion: MotionState): Boolean {
        mutableStatus.value = mutableStatus.value.copy(motion = motion)
        return true
    }

    override fun release() = Unit
}

private class DemoNavigationGateway : NavigationGateway {
    private val requestMutex = Mutex()
    private val mutableCapability = MutableStateFlow(
        CapabilityStatus(CapabilityState.Available, IntegrationSource.Demo, "데모"),
    )
    private val mutableState = MutableStateFlow<NavigationState>(NavigationState.Idle(mutableCapability.value))
    override val state: StateFlow<NavigationState> = mutableState.asStateFlow()
    override val capability: StateFlow<CapabilityStatus> = mutableCapability.asStateFlow()

    override suspend fun initialize() = Unit

    override suspend fun requestRoute(request: NavigationRequest): NavigationRequestResult {
        val validation = NavigationRequestValidator.validate(request)
        if (validation is NavigationValidation.Invalid) {
            mutableState.value = NavigationState.Failed(validation.userMessage)
            return NavigationRequestResult.Rejected(validation.userMessage)
        }
        if (!requestMutex.tryLock()) return NavigationRequestResult.Busy
        return try {
            mutableState.value = NavigationState.Requesting(request.requestId, request.destination.name)
            delay(250)
            mutableState.value = NavigationState.Active(request.destination.name)
            NavigationRequestResult.Accepted
        } finally {
            requestMutex.unlock()
        }
    }

    override fun release() {
        mutableCapability.value = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Unavailable, "사용 불가")
        mutableState.value = NavigationState.Idle(mutableCapability.value)
    }
}
