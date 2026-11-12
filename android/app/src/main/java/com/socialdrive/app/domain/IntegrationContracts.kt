package com.socialdrive.app.domain

import kotlinx.coroutines.flow.StateFlow

enum class IntegrationSource { Actual, Demo, Unavailable }

data class CapabilityStatus(
    val capability: CapabilityState,
    val source: IntegrationSource,
    val label: String,
)

data class VehicleMotionStatus(
    val motion: MotionState,
    val integration: CapabilityStatus,
)

interface VehicleMotionSource {
    val status: StateFlow<VehicleMotionStatus>

    suspend fun initialize()

    /** Returns false in actual builds. Simulation is a demo-only operation. */
    suspend fun requestSimulation(motion: MotionState): Boolean

    fun release()
}

data class NavigationPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

data class NavigationRequest(
    val requestId: String,
    val destination: NavigationPlace,
    val waypoints: List<NavigationPlace> = emptyList(),
)

sealed interface NavigationValidation {
    data object Valid : NavigationValidation
    data class Invalid(val userMessage: String) : NavigationValidation
}

object NavigationRequestValidator {
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_WAYPOINTS = 1

    fun validate(request: NavigationRequest): NavigationValidation {
        if (request.requestId.isBlank() || request.requestId.length > 80) {
            return NavigationValidation.Invalid("경로 요청을 식별할 수 없어요.")
        }
        if (request.waypoints.size > MAX_WAYPOINTS) {
            return NavigationValidation.Invalid("현재는 경유지를 1곳까지 추가할 수 있어요.")
        }
        val places = listOf(request.destination) + request.waypoints
        places.forEach { place ->
            if (place.name.isBlank() || place.name.length > MAX_NAME_LENGTH) {
                return NavigationValidation.Invalid("목적지 이름을 다시 확인해 주세요.")
            }
            if (!place.latitude.isFinite() || place.latitude !in -90.0..90.0) {
                return NavigationValidation.Invalid("목적지 위치를 다시 확인해 주세요.")
            }
            if (!place.longitude.isFinite() || place.longitude !in -180.0..180.0) {
                return NavigationValidation.Invalid("목적지 위치를 다시 확인해 주세요.")
            }
        }
        return NavigationValidation.Valid
    }
}

sealed interface NavigationState {
    data class Idle(val capability: CapabilityStatus) : NavigationState
    data class Requesting(val requestId: String, val label: String) : NavigationState
    data class Active(val label: String) : NavigationState
    data class Failed(val userMessage: String) : NavigationState
}

sealed interface NavigationRequestResult {
    data object Accepted : NavigationRequestResult
    data object Busy : NavigationRequestResult
    data class Rejected(val userMessage: String) : NavigationRequestResult
}

interface NavigationGateway {
    val state: StateFlow<NavigationState>
    val capability: StateFlow<CapabilityStatus>

    suspend fun initialize()
    suspend fun requestRoute(request: NavigationRequest): NavigationRequestResult
    fun release()
}

interface AuxiliaryCapabilitySource {
    val location: StateFlow<CapabilityStatus>
    val textToSpeech: StateFlow<CapabilityStatus>
    val speechToText: StateFlow<CapabilityStatus>
    val speechRecognitionActive: StateFlow<Boolean>
    val speechRecognitionState: StateFlow<SpeechRecognitionState>
    val locationPermission: String?
    val speechPermissions: List<String>

    suspend fun initialize()
    fun speakFeedback(message: String): Boolean
    fun startSpeechRecognition(onText: (text: String, isFinal: Boolean) -> Unit): Boolean
    fun stopSpeechRecognition()
    fun resetSpeechRecognitionState()
    fun refreshLocationPermission()
    fun refreshSpeechPermissions()
    fun release()
}

enum class SpeechRecognitionState { Idle, Listening, Applied, NoResult, Cancelled, Failed }

data class IntegrationBundle(
    val vehicleMotionSource: VehicleMotionSource,
    val navigationGateway: NavigationGateway,
    val auxiliaryCapabilitySource: AuxiliaryCapabilitySource,
    val routeCollector: FutureRouteCollector,
)

object SpeechRecognitionPolicy {
    fun canStart(motion: MotionState, capability: CapabilityStatus): Boolean =
        motion == MotionState.Parked && capability.capability == CapabilityState.Available

    fun shouldCommit(text: String, isFinal: Boolean): Boolean = isFinal && text.isNotBlank()

    fun canCommit(motion: MotionState, text: String, isFinal: Boolean): Boolean =
        motion == MotionState.Parked && shouldCommit(text, isFinal)
}

const val DRAFT_TITLE_MAX_CODE_POINTS = 40
const val DRAFT_DESCRIPTION_MAX_CODE_POINTS = 120

fun String.takeCodePoints(maxCodePoints: Int): String {
    require(maxCodePoints >= 0)
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}
