package com.socialdrive.app.data

import com.socialdrive.app.data.local.RecordingEntity
import com.socialdrive.app.domain.NormalizedRoutePoint

/**
 * Immutable, privacy-minimized local publication input.
 *
 * Raw route samples, coordinates, credentials, and remote upload instructions are deliberately
 * absent. [originalContentRef] is an app-private local reference and must never be sent to the API.
 */
data class DraftSnapshot(
    val id: String,
    val recordingId: String,
    val clientRequestId: String,
    val title: String,
    val description: String,
    val coverPreset: String,
    val originalContentRef: String,
    val state: String,
    val highlightCount: Int = 0,
    val durationSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val storyId: String = "demo-coast",
    val routePoints: List<NormalizedRoutePoint> = emptyList(),
    val createdAtEpochMs: Long = 0L,
)

internal fun RecordingEntity?.recordedDurationSeconds(): Int {
    val recording = this ?: return 0
    val endedAt = recording.endedAtEpochMs ?: return 0
    return ((endedAt - recording.startedAtEpochMs).coerceAtLeast(0L) / 1_000L)
        .coerceAtMost(259_200L)
        .toInt()
}

/**
 * Rejects legacy/stale-fix distance aggregates that imply physically impossible road travel.
 * A generous 1 km acquisition allowance plus 100 m/s keeps this a corruption guard, not a
 * driving-style judgement. The persisted source remains untouched for audit/recovery.
 */
internal fun plausibleDistanceMeters(distanceMeters: Int, durationSeconds: Int): Int {
    if (distanceMeters <= 0 || durationSeconds <= 0) return 0
    val boundedDistance = distanceMeters.coerceAtMost(2_000_000)
    val boundedDuration = durationSeconds.coerceAtMost(259_200)
    val maximumPlausible = 1_000L + boundedDuration.toLong() * 100L
    return boundedDistance.takeIf { it.toLong() <= maximumPlausible } ?: 0
}
