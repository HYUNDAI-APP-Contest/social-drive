package com.socialdrive.app.ui

import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.RoutePoint
import java.util.Locale

internal fun formatRecordingElapsed(startedAtEpochMs: Long, nowEpochMs: Long): String {
    val totalSeconds = ((nowEpochMs - startedAtEpochMs).coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

internal fun recordingRouteStatus(source: RouteSource): String =
    if (source == RouteSource.LiveAvailable) "경로 수집 중" else "데모 경로"

internal fun recordingArtworkRoute(source: RouteSource, preset: List<RoutePoint>): List<RoutePoint> =
    if (source == RouteSource.LiveAvailable) emptyList() else preset

internal fun recoverRecordingDestination(current: AppDestination, hasActiveRecording: Boolean): AppDestination =
    if (hasActiveRecording && current == AppDestination.Home) AppDestination.Recording else current
