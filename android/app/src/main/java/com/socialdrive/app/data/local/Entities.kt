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
}
