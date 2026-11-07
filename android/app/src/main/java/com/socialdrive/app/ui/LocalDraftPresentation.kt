package com.socialdrive.app.ui

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource

internal fun DraftSnapshot.toLocalDriveSummary(fallback: DriveSummary): DriveSummary {
    val liveRoute = routePoints.takeIf { it.size >= 2 }
        ?.sortedBy { it.sequence }
        ?.map { RoutePoint(it.horizontal, it.vertical) }
    val published = state == LocalState.PUBLISHED
    return DriveSummary(
        id = "local-$id",
        date = if (published) "기기 게시 기록" else "기기 초안 · 게시 전",
        title = title,
        summary = description,
        distance = liveRoute?.let { "${it.size}개 경로점" } ?: "데모 아트워크",
        duration = "기기에 보존",
        highlights = highlightCount,
        route = liveRoute ?: fallback.route,
        moments = emptyList(),
        routeSource = if (liveRoute == null) RouteSource.DemoPreset else RouteSource.LiveAvailable,
        navigationRequest = null,
    )
}
