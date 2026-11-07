package com.socialdrive.app.integration

import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.PleosPermission
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.socialdrive.app.domain.AuxiliaryCapabilitySource
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet

object FlavorIntegrationFactory {
    fun create(context: Context): IntegrationBundle = IntegrationBundle(
        vehicleMotionSource = PleosVehicleMotionSource(context.applicationContext),
        navigationGateway = PleosNavigationGateway(context.applicationContext),
        auxiliaryCapabilitySource = PleosAuxiliaryCapabilitySource(context.applicationContext),
    )
}

private class PleosAuxiliaryCapabilitySource(context: Context) : AuxiliaryCapabilitySource {
    private val fusedLocation = FusedLocation(context)
    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private val checking = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "실차 · 확인 중")
    private fun available(label: String) = CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, label)
    private fun unavailable(label: String) = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, label)
    private val mutableLocation = MutableStateFlow(checking)
    private val mutableTts = MutableStateFlow(checking)
    private val mutableStt = MutableStateFlow(checking)
    @Volatile private var recognitionCallback: ((String, Boolean) -> Unit)? = null
    private val recognitionListener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            recognitionCallback?.invoke(text, isFinal)
            if (isFinal) recognitionCallback = null
        }
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() { recognitionCallback = null }
        override fun onError(error: ai.pleos.playground.stt.constant.ErrorType) { recognitionCallback = null }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }
    override val location: StateFlow<CapabilityStatus> = mutableLocation.asStateFlow()
    override val textToSpeech: StateFlow<CapabilityStatus> = mutableTts.asStateFlow()
    override val speechToText: StateFlow<CapabilityStatus> = mutableStt.asStateFlow()

    override suspend fun initialize() {
        runCatching {
            fusedLocation.initialize()
            fusedLocation.isSupported()
        }.onSuccess { supported ->
            mutableLocation.value = if (supported) available("실차 · SDK 지원") else unavailable("실차 · 위치 미지원")
        }.onFailure { mutableLocation.value = unavailable("실차 · 초기화 실패") }
        runCatching {
            tts.initialize(object : TtsInitializeListener {
                override fun onSuccess() { mutableTts.value = available("실차 · 온디바이스") }
                override fun onFailure(error: ai.pleos.playground.tts.constant.ErrorType) {
                    mutableTts.value = unavailable("실차 · 초기화 실패")
                }
            })
        }.onFailure { mutableTts.value = unavailable("실차 · 초기화 실패") }
        runCatching {
            stt.initialize(object : SttInitializeListener {
                override fun onSuccess() {
                    stt.addListener(recognitionListener)
                    mutableStt.value = available("실차 · 온디바이스")
                }
                override fun onFailure(error: ai.pleos.playground.stt.constant.ErrorType) {
                    mutableStt.value = unavailable("실차 · 초기화 실패")
                }
            })
        }.onFailure { mutableStt.value = unavailable("실차 · 초기화 실패") }
    }

    override fun speakFeedback(message: String): Boolean {
        if (mutableTts.value.capability != CapabilityState.Available || message.isBlank()) return false
        return runCatching { tts.speak(message, false) }.isSuccess
    }

    override fun startSpeechRecognition(onText: (String, Boolean) -> Unit): Boolean {
        if (mutableStt.value.capability != CapabilityState.Available || recognitionCallback != null) return false
        recognitionCallback = onText
        return runCatching { stt.request() }
            .onFailure { recognitionCallback = null }
            .isSuccess
    }

    override fun stopSpeechRecognition() {
        recognitionCallback = null
        runCatching { stt.stop() }
    }

    override fun release() {
        stopSpeechRecognition()
        runCatching { stt.removeListener(recognitionListener) }
        runCatching { fusedLocation.release() }
        runCatching { tts.release() }
        runCatching { stt.release() }
    }
}

private class PleosVehicleMotionSource(context: Context) : VehicleMotionSource {
    private val actualUnavailable = CapabilityStatus(
        CapabilityState.Unavailable,
        IntegrationSource.Actual,
        "실차 · 주차 신호 미확인",
    )
    private val actualAvailable = CapabilityStatus(
        CapabilityState.Available,
        IntegrationSource.Actual,
        "실차 · 변속 상태 확인",
    )
    private val mutableStatus = MutableStateFlow(VehicleMotionStatus(MotionState.Unknown, actualUnavailable))
    private val controller = GearMotionController(
        port = PleosGearSignalPort(context),
        scheduleScope = CoroutineGearScheduleScope(),
        onMotionChanged = { motion ->
            mutableStatus.value = VehicleMotionStatus(
                motion = motion,
                integration = if (motion == MotionState.Unknown) actualUnavailable else actualAvailable,
            )
        },
    )
    override val status: StateFlow<VehicleMotionStatus> = mutableStatus.asStateFlow()

    override suspend fun initialize() = controller.initialize()

    override suspend fun requestSimulation(motion: MotionState): Boolean = false

    override fun release() = controller.release()
}

@Suppress("DEPRECATION")
private class PleosGearSignalPort(context: Context) : GearSignalPort {
    private val applicationContext = context.applicationContext
    private val vehicle = Vehicle(applicationContext)
    private val gearApi = vehicle.getGear()
    private val lock = Any()
    private val listeners = IdentityHashMap<GearSignalCallback, GearSelectionListener>()

    override fun hasReadPermission(): Boolean =
        applicationContext.checkSelfPermission(PleosPermission.CAR_DRIVE) == PackageManager.PERMISSION_GRANTED

    override fun initialize() = synchronized(lock) { vehicle.initialize() }

    override fun readCurrent(callback: GearSignalCallback) = synchronized(lock) {
        gearApi.getCurrentGearSelectionState(
            onComplete = { gear -> callback.onSignal(gear.toGearSignal()) },
            onFailed = { callback.onFailure() },
        )
    }

    override fun register(callback: GearSignalCallback) = synchronized(lock) {
        check(!listeners.containsKey(callback))
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = callback.onSignal(gear.toGearSignal())
            override fun onFailed(exception: Exception) = callback.onFailure()
        }
        listeners[callback] = listener
        runCatching { gearApi.registerGearSelection(listener) }
            .onFailure { listeners.remove(callback) }
            .getOrThrow()
    }

    override fun unregister(callback: GearSignalCallback) {
        synchronized(lock) {
            listeners.remove(callback)?.let(gearApi::unregisterGearSelection)
        }
    }

    override fun release() = synchronized(lock) {
        listeners.values.toList().forEach { listener ->
            runCatching { gearApi.unregisterGearSelection(listener) }
        }
        listeners.clear()
        vehicle.release()
    }
}

private fun VehicleGear?.toGearSignal(): GearSignal = when (this) {
    null,
    VehicleGear.GEAR_UNKNOWN,
    -> GearSignal.Unknown
    VehicleGear.GEAR_NEUTRAL -> GearSignal.Neutral
    VehicleGear.GEAR_REVERSE -> GearSignal.Reverse
    VehicleGear.GEAR_PARK -> GearSignal.Park
    VehicleGear.GEAR_DRIVE -> GearSignal.Drive
    VehicleGear.GEAR_1 -> GearSignal.Gear1
    VehicleGear.GEAR_2 -> GearSignal.Gear2
    VehicleGear.GEAR_3 -> GearSignal.Gear3
    VehicleGear.GEAR_4 -> GearSignal.Gear4
    VehicleGear.GEAR_5 -> GearSignal.Gear5
    VehicleGear.GEAR_6 -> GearSignal.Gear6
    VehicleGear.GEAR_7 -> GearSignal.Gear7
    VehicleGear.GEAR_8 -> GearSignal.Gear8
    VehicleGear.GEAR_9 -> GearSignal.Gear9
}

private class CoroutineGearScheduleScope : GearScheduleScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = CopyOnWriteArraySet<kotlinx.coroutines.Job>()

    override fun schedule(delayMillis: Long, task: () -> Unit): GearScheduledTask {
        val job = scope.launch {
            delay(delayMillis)
            task()
        }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
        return GearScheduledTask { job.cancel() }
    }

    override fun cancelAll() {
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
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
    private val rootJob: kotlinx.coroutines.Job = SupervisorJob()
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
