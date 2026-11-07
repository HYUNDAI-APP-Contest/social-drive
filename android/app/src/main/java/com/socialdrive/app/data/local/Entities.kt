package com.socialdrive.app.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recording_sessions")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val state: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val highlightCount: Int,
    @ColumnInfo(defaultValue = "0") val distanceMeters: Int = 0,
)

@Entity(
    tableName = "drive_drafts",
    indices = [
        Index(value = ["recordingId"], unique = true),
        Index(value = ["clientRequestId"], unique = true),
    ],
)
data class DraftEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val clientRequestId: String,
    val title: String,
    val description: String,
    val coverPreset: String,
    val originalContentRef: String,
    val state: String,
    val remoteDriveId: String?,
    val remoteVersion: Int?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val publishedAtEpochMs: Long?,
)

@Entity(
    tableName = "upload_queue",
    indices = [Index(value = ["clientRequestId"], unique = true), Index(value = ["status", "nextAttemptAtEpochMs"])],
)
data class UploadQueueEntity(
    @PrimaryKey val draftId: String,
    val clientRequestId: String,
    val stage: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAtEpochMs: Long,
    val lastErrorCode: String?,
    @ColumnInfo(defaultValue = "0") val uploadRewindCount: Int,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "route_privacy_state")
data class RoutePrivacyEntity(
    @PrimaryKey val id: Int = 1,
    val consentState: String,
    val consentPolicyVersion: String?,
    val routeSource: String,
    val unavailableReason: String?,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "normalized_route_points", primaryKeys = ["recordingId", "sequence"])
data class NormalizedRoutePointEntity(
    val recordingId: String,
    val sequence: Int,
    val horizontal: Float,
    val vertical: Float,
    val publicationState: String,
)

@Entity(tableName = "published_delete_requests")
data class PublishedDeleteRequestEntity(
    @PrimaryKey val draftId: String,
    val clientRequestId: String,
    val remoteDriveId: String,
    val idempotencyKeyHash: String,
    val state: String,
    val attempts: Int,
    val nextAttemptAtEpochMs: Long,
    val lastErrorCode: String?,
    val requestedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val confirmedAtEpochMs: Long?,
)

@Entity(tableName = "publish_conflicts")
data class PublishConflictEntity(
    @PrimaryKey val draftId: String,
    val clientRequestId: String,
    val remoteDriveId: String,
    val attemptedVersion: Int,
    val latestVersion: Int?,
    val latestStatus: String?,
    val latestTitle: String?,
    val latestSummary: String?,
    val state: String,
    val replayCount: Int,
    val fetchGeneration: Int,
    val detectedAtEpochMs: Long,
    val fetchedAtEpochMs: Long?,
    val resolvedAtEpochMs: Long?,
    val lastFetchErrorCode: String?,
)

object LocalState {
    const val RECORDING = "RECORDING"
    const val DRAFTED = "DRAFTED"
    const val DRAFT = "DRAFT"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val FAILED = "FAILED"
    const val PAUSED_MOTION = "PAUSED_MOTION"
    const val PUBLISHED = "PUBLISHED"
    const val CREATE = "CREATE"
    const val UPLOAD = "UPLOAD"
    const val PUBLISH = "PUBLISH"
    const val COMPLETE = "COMPLETE"
    const val CANCELLED = "CANCELLED"
    const val PRIVACY_WITHDRAWN = "PRIVACY_WITHDRAWN"
    const val DELETE_PENDING = "DELETE_PENDING"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
}

object ConflictState {
    const val DETECTED = "DETECTED"
    const val FETCHING = "FETCHING"
    const val FETCH_FAILED = "FETCH_FAILED"
    const val SNAPSHOT_READY = "SNAPSHOT_READY"
    const val KEPT_LOCAL = "KEPT_LOCAL"
    const val REPLAY_QUEUED = "REPLAY_QUEUED"
    const val RESOLVED = "RESOLVED"
    const val UNREVIEWABLE = "UNREVIEWABLE"
}
