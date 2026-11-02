package com.socialdrive.app.integration

import android.content.Context
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationBundle
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.NavigationGateway
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.NavigationRequestValidator
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.domain.NavigationValidation
import com.socialdrive.app.domain.VehicleMotionSource
import com.socialdrive.app.domain.VehicleMotionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

object FlavorIntegrationFactory {
    fun create(context: Context): IntegrationBundle = IntegrationBundle(
        vehicleMotionSource = PleosVehicleMotionSource(context.applicationContext),
        navigationGateway = PleosNavigationGateway(context.applicationContext),
    )
}

/**
 * Vehicle v2.3.2 lifecycle is real, but this adapter does not call a permissioned Vehicle API.
 * Initialization alone is not proof that the vehicle is parked. Until a documented parking/gear
 * signal is approved and actually read, every path remains Unknown and no Vehicle permission is requested.
 */
private class PleosVehicleMotionSource(context: Context) : VehicleMotionSource {
    private val vehicle = Vehicle(context)
    private val actualUnavailable = CapabilityStatus(
        CapabilityState.Unavailable,
        IntegrationSource.Actual,
        "실차 · 주차 신호 없음",
    )
    private val mutableStatus = MutableStateFlow(VehicleMotionStatus(MotionState.Unknown, actualUnavailable))
    override val status: StateFlow<VehicleMotionStatus> = mutableStatus.asStateFlow()

    override suspend fun initialize() {
        mutableStatus.value = VehicleMotionStatus(MotionState.Unknown, actualUnavailable)
        runCatching { vehicle.initialize() }
            .onFailure { failClosed() }
        lifecycleOnlyResult()
    }

    private fun lifecycleOnlyResult() = failClosed()

    private fun failClosed() {
        mutableStatus.value = VehicleMotionStatus(MotionState.Unknown, actualUnavailable)
    }

    override suspend fun requestSimulation(motion: MotionState): Boolean = false

    override fun release() {
        runCatching { vehicle.release() }
        failClosed()
    }
}

private class PleosNavigationGateway(context: Context) : NavigationGateway {
    private val applicationContext = context.applicationContext
    private val actualUnavailable = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "실차 · 연결 안 됨")
    private val actualAvailable = CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "실차")
    private val mutableCapability = MutableStateFlow(actualUnavailable)
    private val mutableState = MutableStateFlow<NavigationState>(NavigationState.Idle(actualUnavailable))
    private val coordinator = NavigationGatewayCoordinator(
        vendorSessionFactory = {
            NavigationVendorSession(
                port = PleosNaviPort(NaviHelper(applicationContext)),
                scheduleScope = CoroutineNavigationScheduleScope(),
            )
        },
        observer = object : NavigationCoordinatorObserver {
            override fun onCapabilityChanged(available: Boolean) {
                mutableCapability.value = if (available) actualAvailable else actualUnavailable
            }

            override fun onStateChanged(state: NavigationState) {
                mutableState.value = state
            }
        },
        disconnectedState = NavigationState.Idle(actualUnavailable),
        connectedState = NavigationState.Idle(actualAvailable),
    )

    override val state: StateFlow<NavigationState> = mutableState.asStateFlow()
    override val capability: StateFlow<CapabilityStatus> = mutableCapability.asStateFlow()

    override suspend fun initialize() = coordinator.initialize()

    override suspend fun requestRoute(request: NavigationRequest): NavigationRequestResult {
        val validation = NavigationRequestValidator.validate(request)
        if (validation is NavigationValidation.Invalid) {
            coordinator.rejectLocally(validation.userMessage)
            return NavigationRequestResult.Rejected(validation.userMessage)
        }
        return coordinator.requestRoute(request)
    }

    override fun release() = coordinator.release()
}

private class PleosNaviPort(private val helper: NaviHelper) : NavigationVendorPort {
    private val listeners = IdentityHashMap<NavigationVendorListener, NaviHelperEventListener>()

    override fun initialize(callback: NavigationInitializationCallback) {
        helper.initialize(onConnected = callback::onConnected, onFailed = { callback.onFailed() })
    }

    override fun addListener(listener: NavigationVendorListener) {
        val sdkListener = object : NaviHelperEventListener {
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = listener.onRouteStarted()
            override fun onRoutingCompleted() = listener.onRoutingCompleted()
            override fun onError(errorCode: NaviErrorCode) = listener.onError(errorCode.safeUserMessage())
        }
        listeners[listener] = sdkListener
        helper.addListener(sdkListener)
    }

    override fun requestRoute(request: NavigationRequest) {
        if (request.waypoints.isEmpty()) {
            helper.requestRoute(request.destination.toRouteInfo())
        } else {
            helper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    destination = request.destination.toRouteInfo(),
                    waypoints = listOf(request.waypoints.single().toWaypointInfo()),
                ),
            )
        }
    }

    override fun removeListener(listener: NavigationVendorListener) {
        listeners.remove(listener)?.let(helper::removeListener)
    }

    override fun release() {
        helper.release()
        listeners.clear()
    }
}

private class CoroutineNavigationScheduleScope : NavigationScheduleScope {
    private val rootJob: Job = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Default)

    override fun schedule(delayMillis: Long, task: () -> Unit): NavigationScheduledTask {
        val job = scope.launch {
            delay(delayMillis)
            task()
        }
        return NavigationScheduledTask { job.cancel() }
    }

    override fun cancelAll() = rootJob.cancel()
}

private fun NaviErrorCode.safeUserMessage(): String = when (this) {
    NaviErrorCode.CAUSE_LOW_BATTERY -> "배터리 상태 때문에 경로를 시작할 수 없어요."
    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "탐색 가능한 거리를 초과했어요."
    NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "목적지가 현재 위치와 너무 가까워요."
    NaviErrorCode.CAUSE_SEARCH_ROUTE -> "경로 탐색 서비스가 응답하지 않아요."
    else -> "차량 내비게이션에서 경로를 처리하지 못했어요."
}

private fun NavigationPlace.toRouteInfo() = RouteInfo(
    latitude = latitude,
    longitude = longitude,
    poiName = name,
    poiId = "",
    address = "",
    poiSubId = "0",
)

private fun NavigationPlace.toWaypointInfo() = RequestWaypointInfo(
    latitude = latitude,
    longitude = longitude,
    poiName = name,
    waypointIndex = WaypointIndex.FIRST,
    poiId = "",
    address = "",
    poiSubId = "0",
)
