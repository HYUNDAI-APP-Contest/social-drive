package com.socialdrive.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recording_sessions WHERE state = 'RECORDING' ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeActive(): Flow<RecordingEntity?>

    @Query("SELECT * FROM recording_sessions WHERE state = 'RECORDING' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun findActive(): RecordingEntity?

    @Query("SELECT * FROM recording_sessions WHERE id = :id")
    suspend fun find(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("UPDATE recording_sessions SET highlightCount = highlightCount + 1 WHERE id = :id AND state = 'RECORDING'")
    suspend fun incrementHighlight(id: String): Int
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drive_drafts ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drive_drafts WHERE id = :id")
    suspend fun find(id: String): DraftEntity?

    @Query("SELECT * FROM drive_drafts WHERE recordingId = :recordingId LIMIT 1")
    suspend fun findByRecording(recordingId: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: DraftEntity)

    @Query("UPDATE drive_drafts SET title = :title, description = :description, updatedAtEpochMs = :now WHERE id = :id AND state = 'DRAFT'")
    suspend fun updateEditableCopy(id: String, title: String, description: String, now: Long): Int

    @Query("UPDATE drive_drafts SET state = :state, remoteDriveId = :remoteDriveId, remoteVersion = :remoteVersion, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateRemoteState(id: String, state: String, remoteDriveId: String?, remoteVersion: Int?, now: Long): Int

    @Query("UPDATE drive_drafts SET state = 'PUBLISHED', remoteDriveId = :remoteDriveId, remoteVersion = :remoteVersion, publishedAtEpochMs = :now, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun markPublished(id: String, remoteDriveId: String, remoteVersion: Int, now: Long): Int

    @Query("""
        UPDATE drive_drafts SET state = 'QUEUED', remoteDriveId = :newRemoteDriveId,
            remoteVersion = :newRemoteVersion, updatedAtEpochMs = :now
        WHERE id = :id AND state = :expectedState
          AND ((remoteDriveId IS NULL AND :expectedRemoteDriveId IS NULL) OR remoteDriveId = :expectedRemoteDriveId)
          AND ((remoteVersion IS NULL AND :expectedRemoteVersion IS NULL) OR remoteVersion = :expectedRemoteVersion)
    """)
    suspend fun applyCreateResultCas(
        id: String,
        expectedState: String,
        expectedRemoteDriveId: String?,
        expectedRemoteVersion: Int?,
        newRemoteDriveId: String,
        newRemoteVersion: Int,
        now: Long,
    ): Int

    @Query("""
        UPDATE drive_drafts SET state = 'PUBLISHED', remoteVersion = :publishedVersion,
            publishedAtEpochMs = :now, updatedAtEpochMs = :now
        WHERE id = :id AND state = :expectedState AND remoteDriveId = :remoteDriveId
          AND remoteVersion = :expectedVersion
    """)
    suspend fun markPublishedCas(
        id: String,
        expectedState: String,
        remoteDriveId: String,
        expectedVersion: Int,
        publishedVersion: Int,
        now: Long,
    ): Int
}

@Dao
interface UploadQueueDao {
    @Query("SELECT * FROM upload_queue WHERE draftId = :draftId")
    fun observe(draftId: String): Flow<UploadQueueEntity?>

    @Query("SELECT * FROM upload_queue WHERE draftId = :draftId")
    suspend fun find(draftId: String): UploadQueueEntity?

    @Query("SELECT * FROM upload_queue WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED_MOTION') ORDER BY updatedAtEpochMs")
    suspend fun recoverable(): List<UploadQueueEntity>

    @Upsert
    suspend fun upsert(item: UploadQueueEntity)

    @Query("UPDATE upload_queue SET stage = :stage, status = :status, attempts = :attempts, nextAttemptAtEpochMs = :nextAttemptAt, lastErrorCode = :lastErrorCode, updatedAtEpochMs = :now WHERE draftId = :draftId")
    suspend fun updateStage(
        draftId: String,
        stage: String,
        status: String,
        attempts: Int,
        nextAttemptAt: Long,
        lastErrorCode: String?,
        now: Long,
    ): Int

    @Query("""
        UPDATE upload_queue SET status = 'RUNNING', attempts = :runningAttempt,
            nextAttemptAtEpochMs = :now, lastErrorCode = NULL, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = :stage AND status = :expectedStatus
          AND attempts = :expectedAttempts
          AND :expectedStatus IN ('QUEUED', 'FAILED', 'PAUSED_MOTION')
    """)
    suspend fun claimStage(
        draftId: String,
        stage: String,
        expectedStatus: String,
        expectedAttempts: Int,
        runningAttempt: Int,
        now: Long,
    ): Int

    @Query("""
        UPDATE upload_queue SET status = 'PAUSED_MOTION', nextAttemptAtEpochMs = :now,
            lastErrorCode = 'MOTION_NOT_PARKED', updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = :stage AND status = 'RUNNING' AND attempts = :attempts
    """)
    suspend fun pauseRunningStage(draftId: String, stage: String, attempts: Int, now: Long): Int

    @Query("""
        UPDATE upload_queue SET status = 'PAUSED_MOTION', nextAttemptAtEpochMs = :now,
            lastErrorCode = 'MOTION_NOT_PARKED', updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = :stage AND status = :expectedStatus
          AND attempts = :attempts AND :expectedStatus IN ('QUEUED', 'PAUSED_MOTION')
    """)
    suspend fun pauseIdleStage(
        draftId: String,
        stage: String,
        expectedStatus: String,
        attempts: Int,
        now: Long,
    ): Int

    @Query("""
        UPDATE upload_queue SET stage = 'UPLOAD', status = 'QUEUED',
            nextAttemptAtEpochMs = :now, lastErrorCode = NULL, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'CREATE' AND status = 'RUNNING' AND attempts = :attempts
    """)
    suspend fun completeCreateStage(draftId: String, attempts: Int, now: Long): Int

    @Query("""
        UPDATE upload_queue SET stage = 'PUBLISH', status = 'QUEUED',
            nextAttemptAtEpochMs = :now, lastErrorCode = NULL, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'UPLOAD' AND status = 'RUNNING' AND attempts = :attempts
    """)
    suspend fun completeUploadStage(draftId: String, attempts: Int, now: Long): Int

    @Query("""
        UPDATE upload_queue SET stage = 'COMPLETE', status = 'PUBLISHED',
            nextAttemptAtEpochMs = 0, lastErrorCode = NULL, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'PUBLISH' AND status = 'RUNNING' AND attempts = :attempts
    """)
    suspend fun completePublishStage(draftId: String, attempts: Int, now: Long): Int

    @Query("""
        UPDATE upload_queue
        SET status = 'FAILED', nextAttemptAtEpochMs = :now,
            lastErrorCode = :lastErrorCode, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = :stage AND status = 'RUNNING' AND attempts = :attempts
    """)
    suspend fun failRunningStage(draftId: String, stage: String, attempts: Int, lastErrorCode: String, now: Long): Int

    @Query("""
        UPDATE upload_queue
        SET stage = 'UPLOAD', status = 'QUEUED', attempts = :attempts,
            nextAttemptAtEpochMs = :now, lastErrorCode = 'UPLOAD_INCOMPLETE',
            uploadRewindCount = uploadRewindCount + 1, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'PUBLISH' AND status = 'RUNNING'
          AND attempts = :attempts AND uploadRewindCount < :maxRewinds
    """)
    suspend fun rewindPublishToUpload(draftId: String, attempts: Int, maxRewinds: Int, now: Long): Int

    @Query("""
        UPDATE upload_queue
        SET status = 'FAILED', lastErrorCode = 'UPLOAD_INCOMPLETE_LIMIT', updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'PUBLISH' AND status = 'RUNNING'
          AND attempts = :attempts AND uploadRewindCount >= :maxRewinds
    """)
    suspend fun failPublishAtUploadLimit(draftId: String, attempts: Int, maxRewinds: Int, now: Long): Int
}
