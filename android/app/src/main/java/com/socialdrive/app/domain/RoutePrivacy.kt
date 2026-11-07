package com.socialdrive.app.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object RouteLocationConsentPolicy {
    const val VERSION = "2026-11-07.v1"
}

enum class LiveUnavailableReason { SDK_COMING_SOON, PERMISSION_DENIED, CAPABILITY_MISSING }

sealed interface RouteSource {
    data object DemoPreset : RouteSource
    data class LiveUnavailable(val reason: LiveUnavailableReason) : RouteSource
    data object LiveAvailable : RouteSource
}

enum class RouteConsentState { NotAsked, Granted, Withdrawn }
enum class RouteConsentAction { Grant, Withdraw }

data class RoutePrivacyStatus(
    val displayedSource: RouteSource,
    val liveSource: RouteSource,
    val consentState: RouteConsentState,
)

data class RouteConsentDecision(
    val state: RouteConsentState,
    val shouldPersist: Boolean,
    val unavailableReason: LiveUnavailableReason? = null,
)

object RouteConsentReducer {
    fun reduce(
        current: RouteConsentState,
        source: RouteSource,
        action: RouteConsentAction,
    ): RouteConsentDecision = when (action) {
        RouteConsentAction.Grant -> when (source) {
            is RouteSource.LiveUnavailable -> RouteConsentDecision(
                state = current,
                shouldPersist = false,
                unavailableReason = source.reason,
            )
            RouteSource.DemoPreset -> RouteConsentDecision(RouteConsentState.Granted, shouldPersist = true)
            RouteSource.LiveAvailable -> RouteConsentDecision(RouteConsentState.Granted, shouldPersist = true)
        }
        RouteConsentAction.Withdraw -> RouteConsentDecision(RouteConsentState.Withdrawn, shouldPersist = true)
    }
}

data class NormalizedRoutePoint(
    val sequence: Int,
    val horizontal: Float,
    val vertical: Float,
) {
    init {
        require(sequence >= 0)
        require(horizontal.isFinite() && horizontal in 0f..1f)
        require(vertical.isFinite() && vertical in 0f..1f)
    }
}

data class RawRouteCoordinate(val longitude: Double, val latitude: Double) {
    init {
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(latitude.isFinite() && latitude in -90.0..90.0)
    }

    companion object {
        fun fromOrNull(longitude: Double, latitude: Double): RawRouteCoordinate? =
            if (
                longitude.isFinite() && longitude in -180.0..180.0 &&
                latitude.isFinite() && latitude in -90.0..90.0
            ) RawRouteCoordinate(longitude, latitude) else null
    }
}

data class CollectedRoute(
    val points: List<NormalizedRoutePoint>,
    val distanceMeters: Int,
) {
    init { require(distanceMeters in 0..2_000_000) }

    companion object {
        val Empty = CollectedRoute(emptyList(), 0)
    }
}

class BoundedRouteBuffer(private val maximumSize: Int) {
    init { require(maximumSize >= 3) }

    private val points = ArrayList<RawRouteCoordinate>(maximumSize)

    fun offer(point: RawRouteCoordinate) {
        if (points.size >= maximumSize) compact()
        points += point
    }

    fun snapshotAndClear(): List<RawRouteCoordinate> = points.toList().also { points.clear() }

    fun clear() = points.clear()

    internal fun snapshot(): List<RawRouteCoordinate> = points.toList()

    private fun compact() {
        val latest = points.last()
        val compacted = ArrayList<RawRouteCoordinate>((points.size / 2) + 1)
        points.forEachIndexed { index, point -> if (index % 2 == 0) compacted += point }
        if (compacted.last() != latest) compacted += latest
        points.clear()
        points.addAll(compacted)
    }
}

object RouteDistance {
    fun plausibleTrack(points: List<RawRouteCoordinate>): List<RawRouteCoordinate> {
        if (points.size < 2) return points
        val tracks = mutableListOf<MutableList<RawRouteCoordinate>>()
        points.forEach { point ->
            val current = tracks.lastOrNull()
            if (current == null || segmentMeters(current.last(), point) > MAX_PLAUSIBLE_SEGMENT_METERS) {
                tracks += mutableListOf(point)
            } else {
                current += point
            }
        }
        return tracks.withIndex()
            .maxWithOrNull(compareBy<IndexedValue<MutableList<RawRouteCoordinate>>> { it.value.size }.thenBy { it.index })
            ?.value
            .orEmpty()
    }

    fun meters(points: List<RawRouteCoordinate>): Int {
        if (points.size < 2) return 0
        val total = points.zipWithNext().sumOf { (start, end) -> segmentMeters(start, end) }
        return total.coerceIn(0.0, 2_000_000.0).roundToInt()
    }

    private fun segmentMeters(start: RawRouteCoordinate, end: RawRouteCoordinate): Double {
        val latitudeDelta = Math.toRadians(end.latitude - start.latitude)
        val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
        val startLatitude = Math.toRadians(start.latitude)
        val endLatitude = Math.toRadians(end.latitude)
        val haversine = sin(latitudeDelta / 2).let { it * it } +
            cos(startLatitude) * cos(endLatitude) * sin(longitudeDelta / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(haversine), sqrt((1 - haversine).coerceAtLeast(0.0)))
    }

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MAX_PLAUSIBLE_SEGMENT_METERS = 5_000.0
}

object RouteNormalizer {
    fun normalize(points: List<RawRouteCoordinate>): List<NormalizedRoutePoint> {
        if (points.isEmpty()) return emptyList()
        val distinctPoints = points.filterIndexed { index, point -> index == 0 || point != points[index - 1] }
        val minX = distinctPoints.minOf(RawRouteCoordinate::longitude)
        val maxX = distinctPoints.maxOf(RawRouteCoordinate::longitude)
        val minY = distinctPoints.minOf(RawRouteCoordinate::latitude)
        val maxY = distinctPoints.maxOf(RawRouteCoordinate::latitude)
        return distinctPoints.mapIndexed { index, point ->
            NormalizedRoutePoint(
                sequence = index,
                horizontal = scale(point.longitude, minX, maxX),
                vertical = 1f - scale(point.latitude, minY, maxY),
            )
        }
    }

    private fun scale(value: Double, minimum: Double, maximum: Double): Float =
        if (maximum == minimum) .5f else ((value - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f)
}

interface FutureRouteCollector {
    suspend fun start()
    suspend fun beginRecording()
    suspend fun snapshotAndClear(): CollectedRoute
    suspend fun stopAndAwait()
}

data class WithdrawalReceipt(
    val purgedUnpublishedPointCount: Int,
    val tombstonedDraftIds: Set<String>,
    val remoteCleanupDraftIds: Set<String> = emptySet(),
)

interface RoutePrivacyMutationStore {
    suspend fun persistConsent(state: RouteConsentState)

    /** Must atomically persist Withdrawn, purge unpublished normalized points, and tombstone pending queues. */
    suspend fun withdrawAndPurge(): WithdrawalReceipt

    /** Persists only a local DELETE_PENDING marker. This contract performs no network call. */
    suspend fun markPublishedDeletePending(draftId: String): Boolean
}

fun interface PendingRouteUploadCanceller {
    suspend fun cancel(draftIds: Set<String>)
}

sealed interface PublishedDeleteDecision {
    data object ConfirmationRequired : PublishedDeleteDecision
    data object NotPublished : PublishedDeleteDecision
    data class MotionRejected(val motion: MotionState) : PublishedDeleteDecision
    data object DeletePending : PublishedDeleteDecision
    data object IdentityUnavailable : PublishedDeleteDecision
}

class RoutePrivacyCoordinator(
    initialConsent: RouteConsentState,
    private val collector: FutureRouteCollector,
    private val store: RoutePrivacyMutationStore,
    private val pendingUploads: PendingRouteUploadCanceller,
) {
    private val mutationMutex = Mutex()
    var consentState: RouteConsentState = initialConsent
        private set

    suspend fun grant(source: RouteSource): RouteConsentDecision = mutationMutex.withLock {
        val decision = RouteConsentReducer.reduce(consentState, source, RouteConsentAction.Grant)
        if (!decision.shouldPersist) return@withLock decision
        store.persistConsent(decision.state)
        consentState = decision.state
        if (source == RouteSource.LiveAvailable) collector.start()
        decision
    }

    suspend fun withdraw(): WithdrawalReceipt = mutationMutex.withLock {
        // Awaiting stop first ensures no point can race into the purge transaction.
        collector.stopAndAwait()
        val receipt = store.withdrawAndPurge()
        consentState = RouteConsentState.Withdrawn
        // The durable tombstone is authoritative if process death happens before cancellation completes.
        pendingUploads.cancel(receipt.tombstonedDraftIds)
        receipt
    }

    suspend fun requestPublishedDelete(
        draftId: String,
        isPublished: Boolean,
        confirmed: Boolean,
        currentMotion: () -> MotionState,
    ): PublishedDeleteDecision = mutationMutex.withLock {
        if (!isPublished) return@withLock PublishedDeleteDecision.NotPublished
        if (!confirmed) return@withLock PublishedDeleteDecision.ConfirmationRequired
        // Read the authoritative source at the mutation boundary, never the dialog's captured UI state.
        val motion = currentMotion()
        if (motion != MotionState.Parked) return@withLock PublishedDeleteDecision.MotionRejected(motion)
        if (store.markPublishedDeletePending(draftId)) PublishedDeleteDecision.DeletePending
        else PublishedDeleteDecision.IdentityUnavailable
    }
}
