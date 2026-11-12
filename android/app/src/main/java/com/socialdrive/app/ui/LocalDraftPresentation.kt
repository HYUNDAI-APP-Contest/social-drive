package com.socialdrive.app.ui

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val localDraftDateFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

internal fun localDraftDateLabel(
    createdAtEpochMs: Long,
    published: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val stateLabel = if (published) "게시 기록" else "초안 · 게시 전"
    if (createdAtEpochMs <= 0L) return "기기 $stateLabel"
    val date = Instant.ofEpochMilli(createdAtEpochMs).atZone(zoneId).format(localDraftDateFormatter)
    return "$date · 기기 $stateLabel"
}

internal fun DraftSnapshot.toLocalDriveSummary(fallback: DriveSummary): DriveSummary {
    val liveRoute = routePoints.takeIf { it.size >= 2 }
        ?.sortedBy { it.sequence }
        ?.map { RoutePoint(it.horizontal, it.vertical) }
    val published = state == LocalState.PUBLISHED
    return DriveSummary(
        id = "local-$id",
        date = localDraftDateLabel(createdAtEpochMs, published),
        title = title,
        summary = description,
        distance = when {
            distanceMeters > 0 -> localDistanceLabel(distanceMeters)
            liveRoute != null -> "${liveRoute.size}개 경로점"
            else -> "데모 아트워크"
        },
        duration = if (durationSeconds > 0) localDurationLabel(durationSeconds) else "기기에 보존",
        highlights = highlightCount,
        route = liveRoute ?: fallback.route,
        moments = emptyList(),
        routeSource = if (liveRoute == null) RouteSource.DemoPreset else RouteSource.LiveAvailable,
        navigationRequest = null,
    )
}

internal fun localDistanceLabel(distanceMeters: Int): String = when {
    distanceMeters < 1_000 -> "$distanceMeters m"
    else -> String.format(Locale.KOREA, "%.1f km", distanceMeters / 1_000.0)
}

internal fun localDurationLabel(durationSeconds: Int): String = when {
    durationSeconds < 60 -> "${durationSeconds}초"
    durationSeconds < 3_600 -> "${durationSeconds / 60}분"
    else -> "${durationSeconds / 3_600}시간 ${(durationSeconds % 3_600) / 60}분"
}
