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
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.socialdrive.app.domain.AuxiliaryCapabilitySource
import com.socialdrive.app.domain.CollectedRoute
import com.socialdrive.app.domain.SpeechRecognitionState
import com.socialdrive.app.domain.FutureRouteCollector
import com.socialdrive.app.domain.NormalizedRoutePoint
import com.socialdrive.app.domain.RawRouteCoordinate
import com.socialdrive.app.domain.RouteNormalizer
import com.socialdrive.app.domain.RouteDistance
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.BoundedRouteBuffer
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
import java.util.concurrent.CopyOnWriteArraySet

object FlavorIntegrationFactory {
    fun create(context: Context): IntegrationBundle {
        val auxiliary = PleosAuxiliaryCapabilitySource(context.applicationContext)
        return IntegrationBundle(
            vehicleMotionSource = PleosVehicleMotionSource(context.applicationContext),
            navigationGateway = PleosNavigationGateway(context.applicationContext),
            auxiliaryCapabilitySource = auxiliary,
            routeCollector = auxiliary,
        )
    }
}

private class PleosAuxiliaryCapabilitySource(private val context: Context) : AuxiliaryCapabilitySource, FutureRouteCollector {
    private val lifecycleJob = SupervisorJob()
    private val lifecycleScope = CoroutineScope(lifecycleJob + Dispatchers.Default)
    private val fusedLocation = FusedLocation(context)
    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private val checking = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "실차 · 확인 중")
    private fun available(label: String) = CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, label)
    private fun unavailable(label: String) = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, label)
    private val mutableLocation = MutableStateFlow(checking)
    private val mutableTts = MutableStateFlow(checking)
    private val mutableStt = MutableStateFlow(checking)
    private val mutableRecognitionActive = MutableStateFlow(false)
    private val mutableRecognitionState = MutableStateFlow(SpeechRecognitionState.Idle)
    private val locationLock = Any()
    private val locationOperationLock = Any()
    private val recognitionLock = Any()
    private val speechOperationLock = Any()
    private val rawLocations = BoundedRouteBuffer(MAX_ROUTE_POINTS)
    private var collectionAuthorized = false
    private var recordingRequested = false
    private var locationCollecting = false
    private var registeredLocationListener: FusedLocationListener? = null
    @Volatile private var ttsInitialized = false
    @Volatile private var sttInitialized = false
    @Volatile private var ttsInitializationGeneration = 0L
    @Volatile private var sttInitializationGeneration = 0L
    @Volatile private var released = false
    private var recognitionCallback: ((String, Boolean) -> Unit)? = null
    private var recognitionTimeoutJob: Job? = null
    private var recognitionSession = 0L
    private var recognitionListener: ResultListener? = null
    private fun createRecognitionListener(session: Long): ResultListener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            val callback = synchronized(recognitionLock) {
                recognitionCallback.takeIf { recognitionSession == session }
            } ?: return
            if (isFinal) {
                val completed = finishRecognition(
                    if (text.isBlank()) SpeechRecognitionState.NoResult else SpeechRecognitionState.Applied,
                    session,
                )
                if (completed && runCatching { callback.invoke(text, true) }.isFailure) {
                    val stillFinished = synchronized(recognitionLock) {
                        recognitionSession == session + 1 && recognitionCallback == null
                    }
                    if (stillFinished) mutableRecognitionState.value = SpeechRecognitionState.Failed
                }
            } else if (runCatching { callback.invoke(text, false) }.isFailure) {
                finishRecognitionAndStop(SpeechRecognitionState.Failed, session)
            }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() {
            finishRecognition(SpeechRecognitionState.NoResult, session)
        }
        override fun onError(error: ai.pleos.playground.stt.constant.ErrorType) {
            finishRecognition(SpeechRecognitionState.Failed, session)
        }
        override fun onReady() {
            if (synchronized(recognitionLock) { recognitionSession == session && recognitionCallback != null }) {
                mutableRecognitionState.value = SpeechRecognitionState.Listening
            }
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }
    override val location: StateFlow<CapabilityStatus> = mutableLocation.asStateFlow()
    override val textToSpeech: StateFlow<CapabilityStatus> = mutableTts.asStateFlow()
    override val speechToText: StateFlow<CapabilityStatus> = mutableStt.asStateFlow()
    override val speechRecognitionActive: StateFlow<Boolean> = mutableRecognitionActive.asStateFlow()
    override val speechRecognitionState: StateFlow<SpeechRecognitionState> = mutableRecognitionState.asStateFlow()
    override val locationPermission: String = FUSED_LOCATION_PERMISSION
    override val speechPermissions: List<String> = listOf(TTS_PERMISSION, STT_PERMISSION)

    override suspend fun initialize() {
        if (released) return
        runCatching {
            fusedLocation.initialize()
            fusedLocation.isSupported()
        }.onSuccess { supported ->
            mutableLocation.value = when {
                !supported -> unavailable("실차 · 위치 미지원")
                !hasLocationPermission() -> CapabilityStatus(CapabilityState.PermissionDenied, IntegrationSource.Actual, "실차 · 권한 필요")
                else -> available("실차 · 위치 사용 가능")
            }
        }.onFailure { mutableLocation.value = unavailable("실차 · 초기화 실패") }
        initializeSpeechServices()
    }

    private fun initializeSpeechServices() {
        if (!hasPermission(TTS_PERMISSION)) {
            mutableTts.value = permissionDenied("실차 · TTS 권한 필요")
        } else initializeTts()
        if (!hasPermission(STT_PERMISSION)) {
            mutableStt.value = permissionDenied("실차 · STT 권한 필요")
        } else initializeStt()
    }

    private fun scheduleInitializationTimeout(
        timeoutIfCurrent: () -> Unit,
    ): Job = lifecycleScope.launch {
        delay(AUXILIARY_INITIALIZATION_TIMEOUT_MILLIS)
        if (!released) timeoutIfCurrent()
    }

    override fun speakFeedback(message: String): Boolean {
        synchronized(speechOperationLock) {
            if (released) return false
            if (!hasPermission(TTS_PERMISSION)) {
                mutableTts.value = permissionDenied("실차 · TTS 권한 필요")
                return false
            }
            if (mutableTts.value.capability != CapabilityState.Available || message.isBlank()) return false
            return runCatching { tts.speak(message, false) }.isSuccess
        }
    }

    override fun startSpeechRecognition(onText: (String, Boolean) -> Unit): Boolean {
        synchronized(speechOperationLock) {
            if (released) return false
            if (!hasPermission(STT_PERMISSION)) {
                mutableStt.value = permissionDenied("실차 · STT 권한 필요")
                return false
            }
            if (mutableStt.value.capability != CapabilityState.Available) return false
            val session = synchronized(recognitionLock) {
                if (recognitionCallback != null) return false
                recognitionSession += 1
                recognitionCallback = onText
                recognitionListener = createRecognitionListener(recognitionSession)
                recognitionSession
            }
            mutableRecognitionActive.value = true
            mutableRecognitionState.value = SpeechRecognitionState.Listening
            val listener = synchronized(recognitionLock) { recognitionListener }
                ?: return false
            if (runCatching {
                    stt.addListener(listener)
                    stt.request()
                }.isFailure
            ) {
                finishRecognition(SpeechRecognitionState.Failed, session)
                return false
            }
            val timeout = lifecycleScope.launch {
                delay(SPEECH_RECOGNITION_TIMEOUT_MILLIS)
                finishRecognitionAndStop(SpeechRecognitionState.NoResult, session)
            }
            synchronized(recognitionLock) {
                if (recognitionSession == session && recognitionCallback != null) {
                    recognitionTimeoutJob = timeout
                } else {
                    timeout.cancel()
                }
            }
            return true
        }
    }

    override fun stopSpeechRecognition() {
        synchronized(speechOperationLock) {
            val wasActive = synchronized(recognitionLock) { recognitionCallback != null } || mutableRecognitionActive.value
            if (wasActive) finishRecognition(SpeechRecognitionState.Cancelled)
            else mutableRecognitionState.value = SpeechRecognitionState.Idle
            runCatching { stt.stop() }
        }
    }

    override fun resetSpeechRecognitionState() {
        if (!mutableRecognitionActive.value) mutableRecognitionState.value = SpeechRecognitionState.Idle
    }

    private fun finishRecognition(state: SpeechRecognitionState, expectedSession: Long? = null): Boolean {
        val (timeout, listener) = synchronized(recognitionLock) {
            if (expectedSession != null && recognitionSession != expectedSession) return false
            if (recognitionCallback == null) return false
            recognitionCallback = null
            recognitionSession += 1
            val timeout = recognitionTimeoutJob.also { recognitionTimeoutJob = null }
            val listener = recognitionListener.also { recognitionListener = null }
            timeout to listener
        }
        timeout?.cancel()
        listener?.let { runCatching { stt.removeListener(it) } }
        mutableRecognitionActive.value = false
        mutableRecognitionState.value = state
        return true
    }

    private fun finishRecognitionAndStop(state: SpeechRecognitionState, expectedSession: Long) {
        synchronized(speechOperationLock) {
            if (finishRecognition(state, expectedSession)) runCatching { stt.stop() }
        }
    }

    override fun refreshLocationPermission() {
        if (released) return
        val permitted = hasLocationPermission()
        val supported = runCatching { fusedLocation.isSupported() }.getOrDefault(false)
        if (permitted && supported) {
            mutableLocation.value = available("실차 · 위치 사용 가능")
            val shouldRegister = synchronized(locationLock) {
                val start = collectionAuthorized && recordingRequested && !locationCollecting
                if (start) locationCollecting = true
                start
            }
            if (shouldRegister) registerLocationCallback()
        } else {
            val listener = synchronized(locationLock) {
                locationCollecting = false
                rawLocations.clear()
                detachLocationListenerLocked()
            }
            unregisterLocationCallback(listener)
            mutableLocation.value = if (!permitted) {
                CapabilityStatus(CapabilityState.PermissionDenied, IntegrationSource.Actual, "실차 · 권한 필요")
            } else {
                unavailable("실차 · 위치 미지원")
            }
        }
    }

    override fun refreshSpeechPermissions() {
        if (released) return
        val ttsGranted = hasPermission(TTS_PERMISSION)
        val sttGranted = hasPermission(STT_PERMISSION)
        if (!ttsGranted) {
            synchronized(speechOperationLock) {
                ttsInitializationGeneration += 1
                mutableTts.value = permissionDenied("실차 · TTS 권한 필요")
            }
        }
        if (!sttGranted) {
            synchronized(speechOperationLock) {
                sttInitializationGeneration += 1
                mutableStt.value = permissionDenied("실차 · STT 권한 필요")
            }
            stopSpeechRecognition()
        }
        if (ttsGranted && mutableTts.value.capability == CapabilityState.PermissionDenied) {
            if (ttsInitialized) mutableTts.value = available("실차 · 온디바이스")
            else {
                mutableTts.value = checking
                initializeTts()
            }
        }
        if (sttGranted && mutableStt.value.capability == CapabilityState.PermissionDenied) {
            if (sttInitialized) mutableStt.value = available("실차 · 온디바이스")
            else {
                mutableStt.value = checking
                initializeStt()
            }
        }
    }

    private fun initializeTts() {
        val generation = synchronized(speechOperationLock) { ++ttsInitializationGeneration }
        runCatching {
            tts.initialize(object : TtsInitializeListener {
                override fun onSuccess() {
                    synchronized(speechOperationLock) {
                        if (released || generation != ttsInitializationGeneration || !hasPermission(TTS_PERMISSION)) return
                        ttsInitialized = true
                        mutableTts.value = available("실차 · 온디바이스")
                    }
                }
                override fun onFailure(error: ai.pleos.playground.tts.constant.ErrorType) {
                    synchronized(speechOperationLock) {
                        if (!released && generation == ttsInitializationGeneration && hasPermission(TTS_PERMISSION)) {
                            mutableTts.value = unavailable("실차 · 초기화 실패")
                        }
                    }
                }
            })
        }.onFailure {
            synchronized(speechOperationLock) {
                if (!released && generation == ttsInitializationGeneration && hasPermission(TTS_PERMISSION)) {
                    mutableTts.value = unavailable("실차 · 초기화 실패")
                }
            }
        }
        scheduleInitializationTimeout {
            synchronized(speechOperationLock) {
                if (generation == ttsInitializationGeneration &&
                    hasPermission(TTS_PERMISSION) && mutableTts.value == checking
                ) mutableTts.value = unavailable("실차 · 초기화 시간 초과")
            }
        }
    }

    private fun initializeStt() {
        val generation = synchronized(speechOperationLock) { ++sttInitializationGeneration }
        runCatching {
            stt.initialize(object : SttInitializeListener {
                override fun onSuccess() {
                    synchronized(speechOperationLock) {
                        if (released || generation != sttInitializationGeneration || !hasPermission(STT_PERMISSION)) return
                        sttInitialized = true
                        mutableStt.value = available("실차 · 온디바이스")
                    }
                }
                override fun onFailure(error: ai.pleos.playground.stt.constant.ErrorType) {
                    synchronized(speechOperationLock) {
                        if (!released && generation == sttInitializationGeneration && hasPermission(STT_PERMISSION)) {
                            mutableStt.value = unavailable("실차 · 초기화 실패")
                        }
                    }
                }
            })
        }.onFailure {
            synchronized(speechOperationLock) {
                if (!released && generation == sttInitializationGeneration && hasPermission(STT_PERMISSION)) {
                    mutableStt.value = unavailable("실차 · 초기화 실패")
                }
            }
        }
        scheduleInitializationTimeout {
            synchronized(speechOperationLock) {
                if (generation == sttInitializationGeneration &&
                    hasPermission(STT_PERMISSION) && mutableStt.value == checking
                ) mutableStt.value = unavailable("실차 · 초기화 시간 초과")
            }
        }
    }

    override suspend fun start() {
        if (released) return
        check(mutableLocation.value.capability == CapabilityState.Available)
        val shouldRegister = synchronized(locationLock) {
            collectionAuthorized = true
            val start = recordingRequested && !locationCollecting
            if (start) locationCollecting = true
            start
        }
        if (shouldRegister) registerLocationCallback()
    }

    override suspend fun beginRecording() {
        if (released) return
        val shouldRegister = synchronized(locationLock) {
            rawLocations.clear()
            recordingRequested = true
            val start = collectionAuthorized &&
                mutableLocation.value.capability == CapabilityState.Available &&
                !locationCollecting
            if (start) locationCollecting = true
            start
        }
        if (shouldRegister) registerLocationCallback()
    }

    override suspend fun snapshotAndClear(): CollectedRoute {
        val (snapshot, listener) = synchronized(locationLock) {
            val points = rawLocations.snapshotAndClear()
            recordingRequested = false
            locationCollecting = false
            points to detachLocationListenerLocked()
        }
        unregisterLocationCallback(listener)
        val plausibleTrack = RouteDistance.plausibleTrack(snapshot)
        return CollectedRoute(
            points = RouteNormalizer.normalize(plausibleTrack),
            distanceMeters = RouteDistance.meters(plausibleTrack),
        )
    }

    override suspend fun stopAndAwait() {
        if (released) return
        val listener = synchronized(locationLock) {
            collectionAuthorized = false
            recordingRequested = false
            locationCollecting = false
            rawLocations.clear()
            detachLocationListenerLocked()
        }
        unregisterLocationCallback(listener)
    }

    private fun registerLocationCallback() {
        var attemptedListener: FusedLocationListener? = null
        val result = synchronized(locationOperationLock) {
            if (released) return
            val listener = synchronized(locationLock) {
                if (!locationCollecting || released) return@synchronized null
                createLocationListener().also { registeredLocationListener = it }
            } ?: return
            attemptedListener = listener
            runCatching {
                fusedLocation.registerFusedLocationCallback(listener)
                val stillOwned = synchronized(locationLock) {
                    registeredLocationListener === listener && locationCollecting && !released
                }
                if (!stillOwned) {
                    synchronized(locationLock) {
                        if (registeredLocationListener === listener) registeredLocationListener = null
                    }
                    runCatching { fusedLocation.unregisterFusedLocationCallback(listener) }
                }
            }
        }
        result
            .onFailure {
                attemptedListener?.let { listener ->
                    deactivateLocationCollection("실차 · 위치 연결 실패", expectedListener = listener)
                }
            }
    }

    private fun unregisterLocationCallback(listener: FusedLocationListener?) {
        if (listener == null) return
        synchronized(locationOperationLock) {
            runCatching { fusedLocation.unregisterFusedLocationCallback(listener) }
        }
    }

    private fun detachLocationListenerLocked(): FusedLocationListener? =
        registeredLocationListener.also { registeredLocationListener = null }

    private fun createLocationListener(): FusedLocationListener {
        lateinit var listener: FusedLocationListener
        listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                val coordinate = RawRouteCoordinate.fromOrNull(location.longitude, location.latitude) ?: return
                synchronized(locationLock) {
                    if (registeredLocationListener === listener && locationCollecting) rawLocations.offer(coordinate)
                }
            }

            override fun onError(error: Exception) {
                deactivateLocationCollection("실차 · 위치 수신 오류", expectedListener = listener)
            }
        }
        return listener
    }

    private fun deactivateLocationCollection(
        label: String,
        unregister: Boolean = true,
        expectedListener: FusedLocationListener? = null,
    ) {
        val listener = synchronized(locationLock) {
            if (expectedListener != null && registeredLocationListener !== expectedListener) return
            locationCollecting = false
            rawLocations.clear()
            detachLocationListenerLocked()
        }
        mutableLocation.value = unavailable(label)
        if (unregister && listener != null) {
            lifecycleScope.launch { unregisterLocationCallback(listener) }
        }
    }

    private fun hasLocationPermission(): Boolean =
        hasPermission(FUSED_LOCATION_PERMISSION)

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun permissionDenied(label: String) =
        CapabilityStatus(CapabilityState.PermissionDenied, IntegrationSource.Actual, label)

    override fun release() {
        val shouldRelease = synchronized(speechOperationLock) {
            if (released) false else {
                released = true
                ttsInitializationGeneration += 1
                sttInitializationGeneration += 1
                true
            }
        }
        if (!shouldRelease) return
        lifecycleJob.cancel()
        val listener = synchronized(locationLock) {
            collectionAuthorized = false
            recordingRequested = false
            locationCollecting = false
            rawLocations.clear()
            detachLocationListenerLocked()
        }
        unregisterLocationCallback(listener)
        synchronized(speechOperationLock) {
            stopSpeechRecognition()
            runCatching { tts.release() }
            runCatching { stt.release() }
        }
        synchronized(locationOperationLock) {
            runCatching { fusedLocation.release() }
        }
    }
}

private const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
private const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"
private const val STT_PERMISSION = "pleos.car.permission.STT_SERVICE"
private const val MAX_ROUTE_POINTS = 5_000
internal const val AUXILIARY_INITIALIZATION_TIMEOUT_MILLIS = 10_000L
internal const val SPEECH_RECOGNITION_TIMEOUT_MILLIS = 30_000L

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
