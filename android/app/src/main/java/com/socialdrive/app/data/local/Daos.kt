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

    @Query("UPDATE drive_drafts SET state = :state, remoteDriveId = :remoteDriveId, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun updateRemoteState(id: String, state: String, remoteDriveId: String?, now: Long): Int

    @Query("UPDATE drive_drafts SET state = 'PUBLISHED', remoteDriveId = :remoteDriveId, publishedAtEpochMs = :now, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun markPublished(id: String, remoteDriveId: String, now: Long): Int
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
}
