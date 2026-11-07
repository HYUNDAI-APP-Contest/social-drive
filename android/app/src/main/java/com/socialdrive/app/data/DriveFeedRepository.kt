package com.socialdrive.app.data

import androidx.room.withTransaction
import com.socialdrive.app.data.local.CachedFeedDriveEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.CloudDriveClient
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.EphemeralCoverImage
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DriveFeedRepository {
    val state: StateFlow<ContentState<List<DriveSummary>>>
    suspend fun refresh()
    suspend fun detail(id: String): DriveSummary?
}

class StaticDriveFeedRepository(items: List<DriveSummary>) : DriveFeedRepository {
    private val items = items.toList()
    override val state = MutableStateFlow<ContentState<List<DriveSummary>>>(ContentState.Content(items))
    override suspend fun refresh() = Unit
    override suspend fun detail(id: String): DriveSummary? = items.firstOrNull { it.id == id }
}

/**
 * Keeps only public, non-sensitive feed metadata. Presigned cover URLs remain inside the network
 * response and are deliberately discarded before the Room transaction.
 */
class PersistentCloudDriveFeedRepository(
    private val database: SocialDriveDatabase,
    private val currentClient: () -> CloudDriveClient?,
    private val now: () -> Long = System::currentTimeMillis,
) : DriveFeedRepository {
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow<ContentState<List<DriveSummary>>>(ContentState.Loading)
    override val state: StateFlow<ContentState<List<DriveSummary>>> = mutableState

    override suspend fun refresh() = refreshMutex.withLock {
        val cached = database.cachedFeedDao().findAll().map(CachedFeedDriveEntity::toSummary)
        val client = currentClient()
        if (client == null) {
            mutableState.value = if (cached.isEmpty()) {
                ContentState.PermissionDenied("AWS 로그인")
            } else {
                ContentState.Offline(cached)
            }
            return@withLock
        }
        try {
            val page = client.feed(limit = 50)
            val timestamp = now()
            val entities = page.items.map { item ->
                val drive = item.drive
                CachedFeedDriveEntity(
                    id = drive.id,
                    version = drive.version,
                    title = drive.title,
                    summary = drive.summary,
                    distanceMeters = drive.distanceMeters,
                    durationSeconds = drive.durationSeconds,
                    highlightCount = drive.highlightCount,
                    publishedAt = requireNotNull(drive.publishedAt),
                    cachedAtEpochMs = timestamp,
                )
            }
            database.withTransaction {
                database.cachedFeedDao().deleteAll()
                if (entities.isNotEmpty()) database.cachedFeedDao().insertAll(entities)
            }
            mutableState.value = if (entities.isEmpty()) ContentState.Empty
            else ContentState.Content(entities.map(CachedFeedDriveEntity::toSummary))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = if (cached.isEmpty()) ContentState.Retry("피드를 불러오지 못했습니다.")
            else ContentState.Offline(cached)
        }
    }

    override suspend fun detail(id: String): DriveSummary? {
        if (!id.startsWith(CLOUD_ID_PREFIX)) return null
        val remoteId = id.removePrefix(CLOUD_ID_PREFIX)
        if (remoteId.isBlank()) return null
        val client = currentClient() ?: return null
        return try {
            var detail = client.getDrive(remoteId)
            val assets = try {
                client.downloadRoute(detail) to client.downloadCover(detail)
            } catch (failure: com.socialdrive.app.data.remote.CloudDriveException) {
                if (failure.code != "READ_LINK_EXPIRED") throw failure
                detail = client.getDrive(remoteId)
                client.downloadRoute(detail) to client.downloadCover(detail)
            }
            detail.drive.toSummary(
                route = assets.first.points.map { RoutePoint(it.horizontal, it.vertical) },
                coverImage = EphemeralCoverImage.inMemory(assets.second),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

private fun CachedFeedDriveEntity.toSummary(): DriveSummary = DriveSummary(
    id = CLOUD_ID_PREFIX + id,
    date = publishedAt.toDisplayDate(),
    title = title,
    summary = summary,
    distance = distanceMeters.toDistanceLabel(),
    duration = durationSeconds.toDurationLabel(),
    highlights = highlightCount,
    route = stableArtworkRoute(id),
    moments = emptyList(),
    routeSource = RouteSource.LiveAvailable,
)

private fun com.socialdrive.app.data.remote.DriveResource.toSummary(
    route: List<RoutePoint>,
    coverImage: EphemeralCoverImage,
): DriveSummary = DriveSummary(
    id = CLOUD_ID_PREFIX + id,
    date = requireNotNull(publishedAt).toDisplayDate(),
    title = title,
    summary = summary,
    distance = distanceMeters.toDistanceLabel(),
    duration = durationSeconds.toDurationLabel(),
    highlights = highlightCount,
    route = route,
    moments = emptyList(),
    routeSource = RouteSource.LiveAvailable,
    coverImage = coverImage,
)

private fun String.toDisplayDate(): String = runCatching {
    DATE_FORMATTER.format(Instant.parse(this).atZone(ZoneId.systemDefault()))
}.getOrDefault("게시된 드라이브")

private fun Int.toDistanceLabel(): String = if (this < 1_000) "$this m" else "%.1f km".format(this / 1_000.0)

private fun Int.toDurationLabel(): String {
    val hours = this / 3_600
    val minutes = (this % 3_600) / 60
    return if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes.coerceAtLeast(1)}분"
}

private fun stableArtworkRoute(id: String): List<RoutePoint> {
    val seed = id.fold(0x45d9f3b) { value, character -> value * 31 + character.code }
    return List(7) { index ->
        val mixed = seed xor (index * 0x9e3779b9.toInt())
        RoutePoint(.06f + index * .145f, .2f + ((mixed ushr 8) and 0xff) / 510f)
    }
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("M월 d일 · 공개 드라이브")
private const val CLOUD_ID_PREFIX = "cloud-"
