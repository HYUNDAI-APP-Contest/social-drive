package com.socialdrive.app.data

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
    val storyId: String = "demo-coast",
    val routePoints: List<NormalizedRoutePoint> = emptyList(),
    val createdAtEpochMs: Long = 0L,
)
