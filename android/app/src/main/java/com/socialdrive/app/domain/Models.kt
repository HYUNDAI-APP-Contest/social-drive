package com.socialdrive.app.domain

enum class AppDestination { Home, Detail, Recording, Publish, Settings }

enum class MotionState { Parked, Driving, Unknown }

enum class ThemeMode { System, Day, Night }

enum class CapabilityState { Available, Unavailable, PermissionDenied }

enum class DriveSessionState { Idle, Recording, Finishing, DraftReady }

enum class DemoContentMode { Loading, Content, Empty, Offline, Error, PermissionDenied, Retry }

sealed interface ContentState<out T> {
    data object Loading : ContentState<Nothing>
    data class Content<T>(val value: T) : ContentState<T>
    data object Empty : ContentState<Nothing>
    data class Offline<T>(val cached: T) : ContentState<T>
    data class Error(val message: String) : ContentState<Nothing>
    data class PermissionDenied(val capability: String) : ContentState<Nothing>
    data class Retry(val message: String) : ContentState<Nothing>
}

enum class UploadState { Draft, Queued, Uploading, Failed, Published }

data class RoutePoint(val horizontal: Float, val vertical: Float)

data class DriveMoment(
    val id: String,
    val time: String,
    val title: String,
    val description: String,
)

data class DriveSummary(
    val id: String,
    val date: String,
    val title: String,
    val summary: String,
    val distance: String,
    val duration: String,
    val highlights: Int,
    val route: List<RoutePoint>,
    val moments: List<DriveMoment>,
    /** Null unless the source supplied a privacy-reviewed destination. Demo fixtures keep this null. */
    val navigationRequest: NavigationRequest? = null,
)

data class RecordingSnapshot(
    val elapsed: String,
    val distance: String,
    val highlights: Int,
    val gps: CapabilityState,
    val vehicle: CapabilityState,
)
