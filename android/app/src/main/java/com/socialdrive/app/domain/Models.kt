package com.socialdrive.app.domain

enum class AppDestination { Home, Detail, Recording, Publish, ConflictReview, Settings }

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

enum class UploadState { Draft, Queued, Uploading, Failed, NeedsConflictReview, NeedsConsentRenewal, Published }

data class RoutePoint(val horizontal: Float, val vertical: Float)

data class DriveMoment(
    val id: String,
    val time: String,
    val title: String,
    val description: String,
)

/** In-memory only public cover bytes. Diagnostics never expose content and callers receive copies. */
class EphemeralCoverImage private constructor(private val content: ByteArray) {
    internal fun copyBytes(): ByteArray = content.copyOf()
    override fun toString(): String = "EphemeralCoverImage([REDACTED])"

    companion object {
        fun inMemory(content: ByteArray): EphemeralCoverImage {
            require(content.isNotEmpty() && content.size <= 2 * 1024 * 1024)
            return EphemeralCoverImage(content.copyOf())
        }
    }
}

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
    val routeSource: RouteSource,
    /** Null unless the source supplied a privacy-reviewed destination. Demo fixtures keep this null. */
    val navigationRequest: NavigationRequest? = null,
    /** Short-lived public artwork bytes; never persisted or included in saved presentation state. */
    val coverImage: EphemeralCoverImage? = null,
)

data class RecordingSnapshot(
    val storyId: String,
    val route: List<RoutePoint>,
    val elapsed: String,
    val distance: String,
    val highlights: Int,
    val gps: CapabilityState,
    val vehicle: CapabilityState,
    val routeSource: RouteSource = RouteSource.DemoPreset,
)
