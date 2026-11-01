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
    private val lifecycleLock = Any()
    private val actualUnavailable = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "실차 · 연결 안 됨")
    private val actualAvailable = CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "실차")
    private val mutableCapability = MutableStateFlow(actualUnavailable)
    private val mutableState = MutableStateFlow<NavigationState>(NavigationState.Idle(actualUnavailable))
    private val controller = NavigationRequestController(
        disconnectedIdleState = NavigationState.Idle(actualUnavailable),
        connectedIdleState = NavigationState.Idle(actualAvailable),
    )
    private val helperFactory: () -> NaviHelper = { NaviHelper(applicationContext) }
    private var session: NaviSession? = null

    override val state: StateFlow<NavigationState> = mutableState.asStateFlow()
    override val capability: StateFlow<CapabilityStatus> = mutableCapability.asStateFlow()

    override suspend fun initialize() {
        synchronized(lifecycleLock) {
            disposeSessionLocked()
            val lifecycleToken = controller.beginInitialization()
            val rootJob = SupervisorJob()
            val newSession = NaviSession(
                lifecycleToken = lifecycleToken,
                helper = helperFactory(),
                rootJob = rootJob,
                scope = CoroutineScope(rootJob + Dispatchers.Default),
            )
            session = newSession
            mutableCapability.value = actualUnavailable
            mutableState.value = controller.state()
            runCatching {
                newSession.helper.initialize(
                    onConnected = { handleConnected(newSession) },
                    onFailed = { handleInitializationFailure(newSession) },
                )
            }.onFailure { handleInitializationFailure(newSession) }
        }
    }

    override suspend fun requestRoute(request: NavigationRequest): NavigationRequestResult {
        val validation = NavigationRequestValidator.validate(request)
        if (validation is NavigationValidation.Invalid) {
            synchronized(lifecycleLock) { publish(controller.localRejection(validation.userMessage)) }
            return NavigationRequestResult.Rejected(validation.userMessage)
        }
        if (mutableCapability.value.capability != CapabilityState.Available) {
            return NavigationRequestResult.Rejected("차량 내비게이션 연결을 확인해 주세요.")
        }
        return synchronized(lifecycleLock) {
            val currentSession = session
                ?: return@synchronized NavigationRequestResult.Rejected("차량 내비게이션 연결을 확인해 주세요.")
            when (val start = controller.beginRequest(request.requestId, request.destination.name)) {
                RequestStart.Busy -> NavigationRequestResult.Busy
                RequestStart.Unavailable -> NavigationRequestResult.Rejected("차량 내비게이션 연결을 확인해 주세요.")
                is RequestStart.Accepted -> startRequestLocked(currentSession, start, request)
            }
        }
    }

    private fun startRequestLocked(
        currentSession: NaviSession,
        start: RequestStart.Accepted,
        request: NavigationRequest,
    ): NavigationRequestResult {
        val listener = requestListener(start.token)
        currentSession.requestToken = start.token
        currentSession.listener = listener
        mutableState.value = start.state
        return runCatching {
            currentSession.helper.addListener(listener)
            currentSession.timeoutJob = currentSession.scope.launch {
                delay(REQUEST_TIMEOUT_MILLIS)
                handleTimeout(start.token)
            }
            if (request.waypoints.isEmpty()) {
                currentSession.helper.requestRoute(request.destination.toRouteInfo())
            } else {
                currentSession.helper.requestRouteWithWaypoints(
                    RouteWithWaypointsRequest(
                        destination = request.destination.toRouteInfo(),
                        waypoints = listOf(request.waypoints.single().toWaypointInfo()),
                    ),
                )
            }
            NavigationRequestResult.Accepted
        }.getOrElse {
            val update = controller.invocationFailed(start.token, "경로 요청을 전달하지 못했어요. 잠시 후 다시 시도해 주세요.")
            cleanupRequestLocked(currentSession, start.token)
            publish(update)
            NavigationRequestResult.Rejected("경로 요청을 전달하지 못했어요.")
        }
    }

    private fun requestListener(token: RequestToken) = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            handleEvent(token, RequestEvent.RouteStarted)
        }

        override fun onRoutingCompleted() {
            handleEvent(token, RequestEvent.RoutingCompleted)
        }

        override fun onError(errorCode: NaviErrorCode) {
            handleEvent(token, RequestEvent.Error(safeErrorMessage(errorCode)))
        }
    }

    private fun handleEvent(token: RequestToken, event: RequestEvent) {
        synchronized(lifecycleLock) {
            val currentSession = session ?: return
            if (currentSession.requestToken != token) return
            val update = when (event) {
                RequestEvent.RouteStarted -> controller.routeStarted(token)
                RequestEvent.RoutingCompleted -> controller.routingCompleted(token)
                is RequestEvent.Error -> controller.requestError(token, event.userMessage)
            }
            if (update == ControllerUpdate.TerminalPending && currentSession.settleJob == null) {
                currentSession.settleJob = currentSession.scope.launch {
                    delay(MINIMUM_FEEDBACK_MILLIS)
                    handleSettle(token)
                }
            }
        }
    }

    private fun handleSettle(token: RequestToken) {
        synchronized(lifecycleLock) {
            val currentSession = session ?: return
            if (currentSession.requestToken != token) return
            currentSession.settleJob = null
            val update = controller.settle(token)
            if (update is ControllerUpdate.Applied && update.terminal) cleanupRequestLocked(currentSession, token)
            publish(update)
        }
    }

    private fun handleTimeout(token: RequestToken) {
        synchronized(lifecycleLock) {
            val currentSession = session ?: return
            if (currentSession.requestToken != token) return
            currentSession.timeoutJob = null
            val update = controller.timeout(token, "차량 내비게이션 응답이 지연되고 있어요. 다시 시도해 주세요.")
            if (update is ControllerUpdate.Applied && update.terminal) cleanupRequestLocked(currentSession, token)
            publish(update)
        }
    }

    private fun handleConnected(expected: NaviSession) {
        synchronized(lifecycleLock) {
            if (session !== expected) return
            val update = controller.connected(expected.lifecycleToken)
            if (update is ControllerUpdate.Applied) {
                mutableCapability.value = actualAvailable
                publish(update)
            }
        }
    }

    private fun handleInitializationFailure(expected: NaviSession) {
        synchronized(lifecycleLock) {
            if (session !== expected) return
            val update = controller.initializationFailed(
                expected.lifecycleToken,
                "차량 내비게이션에 연결할 수 없어요.",
            )
            mutableCapability.value = actualUnavailable
            publish(update)
            disposeSessionLocked()
        }
    }

    private fun cleanupRequestLocked(currentSession: NaviSession, token: RequestToken) {
        if (currentSession.requestToken != token) return
        currentSession.timeoutJob?.cancel()
        currentSession.settleJob?.cancel()
        currentSession.listener?.let { listener ->
            runCatching { currentSession.helper.removeListener(listener) }
        }
        currentSession.timeoutJob = null
        currentSession.settleJob = null
        currentSession.listener = null
        currentSession.requestToken = null
        currentSession.quarantineJob?.cancel()
        currentSession.quarantineJob = currentSession.scope.launch {
            delay(STALE_EVENT_QUARANTINE_MILLIS)
            synchronized(lifecycleLock) {
                if (session !== currentSession) return@synchronized
                controller.endQuarantine(token)
                currentSession.quarantineJob = null
            }
        }
    }

    private fun publish(update: ControllerUpdate) {
        if (update is ControllerUpdate.Applied) mutableState.value = update.state
    }

    override fun release() {
        synchronized(lifecycleLock) {
            publish(controller.release())
            mutableCapability.value = actualUnavailable
            disposeSessionLocked()
        }
    }

    private fun disposeSessionLocked() {
        val previous = session ?: return
        previous.timeoutJob?.cancel()
        previous.settleJob?.cancel()
        previous.quarantineJob?.cancel()
        previous.listener?.let { listener -> runCatching { previous.helper.removeListener(listener) } }
        previous.listener = null
        previous.requestToken = null
        runCatching { previous.helper.release() }
        previous.rootJob.cancel()
        session = null
    }

    private fun safeErrorMessage(errorCode: NaviErrorCode): String = when (errorCode) {
        NaviErrorCode.CAUSE_LOW_BATTERY -> "배터리 상태 때문에 경로를 시작할 수 없어요."
        NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "탐색 가능한 거리를 초과했어요."
        NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "목적지가 현재 위치와 너무 가까워요."
        NaviErrorCode.CAUSE_SEARCH_ROUTE -> "경로 탐색 서비스가 응답하지 않아요."
        else -> "차량 내비게이션에서 경로를 처리하지 못했어요."
    }

    private data class NaviSession(
        val lifecycleToken: LifecycleToken,
        val helper: NaviHelper,
        val rootJob: Job,
        val scope: CoroutineScope,
        var requestToken: RequestToken? = null,
        var listener: NaviHelperEventListener? = null,
        var timeoutJob: Job? = null,
        var settleJob: Job? = null,
        var quarantineJob: Job? = null,
    )

    private sealed interface RequestEvent {
        data object RouteStarted : RequestEvent
        data object RoutingCompleted : RequestEvent
        data class Error(val userMessage: String) : RequestEvent
    }

    private companion object {
        const val MINIMUM_FEEDBACK_MILLIS = 250L
        const val STALE_EVENT_QUARANTINE_MILLIS = 750L
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
    }
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
