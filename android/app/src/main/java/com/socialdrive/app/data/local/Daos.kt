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

    @Query("""
        UPDATE drive_drafts SET remoteVersion = :latestVersion, updatedAtEpochMs = :now
        WHERE id = :draftId AND state = 'QUEUED' AND remoteDriveId = :remoteDriveId
          AND remoteVersion = :attemptedVersion AND clientRequestId = :clientRequestId
    """)
    suspend fun adoptConflictVersionCas(
        draftId: String,
        remoteDriveId: String,
        attemptedVersion: Int,
        latestVersion: Int,
        clientRequestId: String,
        now: Long,
    ): Int
}

@Dao
interface UploadQueueDao {
    @Query("SELECT * FROM upload_queue WHERE draftId = :draftId")
    fun observe(draftId: String): Flow<UploadQueueEntity?>

    @Query("SELECT * FROM upload_queue WHERE draftId = :draftId")
    suspend fun find(draftId: String): UploadQueueEntity?

    @Query("""
        SELECT * FROM upload_queue q
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED_MOTION')
          AND NOT EXISTS (SELECT 1 FROM published_delete_requests d WHERE d.draftId = q.draftId)
        ORDER BY updatedAtEpochMs
    """)
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
          AND NOT (:expectedStatus = 'FAILED' AND COALESCE(lastErrorCode, '') = 'VERSION_CONFLICT')
          AND NOT EXISTS (
              SELECT 1 FROM published_delete_requests d
              WHERE d.draftId = upload_queue.draftId
          )
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
        UPDATE upload_queue SET status = 'FAILED', lastErrorCode = 'VERSION_CONFLICT',
            nextAttemptAtEpochMs = :now, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'PUBLISH' AND status = 'RUNNING' AND attempts = :attempts
    """)
    suspend fun failVersionConflictCas(draftId: String, attempts: Int, now: Long): Int

    @Query("""
        UPDATE upload_queue SET status = 'QUEUED', lastErrorCode = NULL,
            nextAttemptAtEpochMs = :now, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND stage = 'PUBLISH' AND status = 'FAILED'
          AND lastErrorCode = 'VERSION_CONFLICT' AND clientRequestId = :clientRequestId
    """)
    suspend fun queueConflictReplayCas(draftId: String, clientRequestId: String, now: Long): Int

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

    @Query("""
        UPDATE upload_queue SET stage = 'CANCELLED', status = 'PRIVACY_WITHDRAWN',
            nextAttemptAtEpochMs = 0, lastErrorCode = 'PRIVACY_WITHDRAWN', updatedAtEpochMs = :now
        WHERE status IN ('QUEUED', 'RUNNING', 'FAILED', 'PAUSED_MOTION')
          AND stage NOT IN ('COMPLETE', 'CANCELLED')
    """)
    suspend fun tombstonePendingForPrivacyWithdrawal(now: Long): Int

    @Query("SELECT draftId FROM upload_queue WHERE status = 'PRIVACY_WITHDRAWN'")
    suspend fun privacyWithdrawnDraftIds(): List<String>
}

@Dao
interface RoutePrivacyDao {
    @Query("SELECT * FROM route_privacy_state WHERE id = 1")
    fun observe(): Flow<RoutePrivacyEntity?>

    @Query("SELECT * FROM route_privacy_state WHERE id = 1")
    suspend fun find(): RoutePrivacyEntity?

    @Upsert
    suspend fun upsert(state: RoutePrivacyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoint(point: NormalizedRoutePointEntity)

    @Query("SELECT COUNT(*) FROM normalized_route_points WHERE publicationState != 'PUBLISHED'")
    suspend fun unpublishedPointCount(): Int

    @Query("SELECT COUNT(*) FROM normalized_route_points")
    suspend fun pointCount(): Int

    @Query("SELECT * FROM normalized_route_points WHERE recordingId = :recordingId ORDER BY sequence")
    suspend fun pointsForRecording(recordingId: String): List<NormalizedRoutePointEntity>

    @Query("UPDATE normalized_route_points SET publicationState = 'PUBLISHED' WHERE recordingId = :recordingId AND publicationState != 'PUBLISHED'")
    suspend fun markRecordingPublished(recordingId: String): Int

    @Query("DELETE FROM normalized_route_points WHERE publicationState != 'PUBLISHED'")
    suspend fun purgeUnpublishedPoints(): Int

}

@Dao
interface PublishedDeleteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(request: PublishedDeleteRequestEntity): Long

    @Query("SELECT * FROM published_delete_requests WHERE draftId = :draftId")
    suspend fun find(draftId: String): PublishedDeleteRequestEntity?

    @Query("SELECT * FROM published_delete_requests WHERE draftId = :draftId")
    fun observe(draftId: String): Flow<PublishedDeleteRequestEntity?>

    @Query("""
        SELECT * FROM published_delete_requests
        WHERE state IN ('DELETE_PENDING', 'RETRY_WAIT', 'RUNNING')
        ORDER BY requestedAtEpochMs
    """)
    suspend fun recoverable(): List<PublishedDeleteRequestEntity>

    @Query("""
        UPDATE published_delete_requests SET state = 'DELETE_PENDING', attempts = 0,
            nextAttemptAtEpochMs = :now, lastErrorCode = NULL, updatedAtEpochMs = :now
        WHERE state = 'AUTH_REQUIRED'
    """)
    suspend fun resumeAuthenticationRequired(now: Long): Int

    @Query("""
        UPDATE published_delete_requests SET state = 'RUNNING', attempts = :runningAttempt,
            lastErrorCode = NULL, updatedAtEpochMs = :now
        WHERE draftId = :draftId AND clientRequestId = :clientRequestId
          AND remoteDriveId = :remoteDriveId AND idempotencyKeyHash = :idempotencyKeyHash
          AND state = :expectedState AND attempts = :expectedAttempts
          AND :expectedState IN ('DELETE_PENDING', 'RETRY_WAIT')
          AND :runningAttempt <= 4
          AND nextAttemptAtEpochMs <= :now
    """)
    suspend fun claim(
        draftId: String,
        clientRequestId: String,
        remoteDriveId: String,
        idempotencyKeyHash: String,
        expectedState: String,
        expectedAttempts: Int,
        runningAttempt: Int,
        now: Long,
    ): Int

    @Query("""
        UPDATE published_delete_requests SET state = 'DELETE_PENDING', attempts = :restoredAttempts,
            nextAttemptAtEpochMs = :now, lastErrorCode = 'MOTION_NOT_PARKED', updatedAtEpochMs = :now
        WHERE draftId = :draftId AND state = 'RUNNING' AND attempts = :runningAttempt
    """)
    suspend fun pauseForMotionCas(draftId: String, runningAttempt: Int, restoredAttempts: Int, now: Long): Int

    @Query("""
        UPDATE published_delete_requests SET state = :nextState,
            nextAttemptAtEpochMs = :nextAttemptAtEpochMs, lastErrorCode = :errorCode,
            updatedAtEpochMs = :now
        WHERE draftId = :draftId AND state = 'RUNNING' AND attempts = :runningAttempt
    """)
    suspend fun failCas(
        draftId: String,
        runningAttempt: Int,
        nextState: String,
        nextAttemptAtEpochMs: Long,
        errorCode: String,
        now: Long,
    ): Int

    @Query("""
        UPDATE published_delete_requests SET state = 'CONFIRMED', nextAttemptAtEpochMs = 0,
            lastErrorCode = NULL, updatedAtEpochMs = :now, confirmedAtEpochMs = :now
        WHERE draftId = :draftId AND state = 'RUNNING' AND attempts = :runningAttempt
    """)
    suspend fun confirmCas(draftId: String, runningAttempt: Int, now: Long): Int

    @Query("""
        UPDATE published_delete_requests SET state = 'RETRY_WAIT',
            nextAttemptAtEpochMs = :now, lastErrorCode = 'INTERRUPTED', updatedAtEpochMs = :now
        WHERE state = 'RUNNING' AND attempts < 4
    """)
    suspend fun normalizeInterrupted(now: Long): Int

    @Query("""
        UPDATE published_delete_requests SET state = 'FAILED_PERMANENT',
            nextAttemptAtEpochMs = 0, lastErrorCode = 'RETRY_EXHAUSTED', updatedAtEpochMs = :now
        WHERE state IN ('RUNNING', 'RETRY_WAIT', 'DELETE_PENDING') AND attempts >= 4
    """)
    suspend fun failAtLimit(now: Long): Int
}

@Dao
interface PublishConflictDao {
    @Query("SELECT * FROM publish_conflicts WHERE draftId = :draftId")
    suspend fun find(draftId: String): PublishConflictEntity?

    @Query("SELECT * FROM publish_conflicts WHERE draftId = :draftId")
    fun observe(draftId: String): Flow<PublishConflictEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(conflict: PublishConflictEntity): Long

    @Query("""
        UPDATE publish_conflicts SET remoteDriveId = :remoteDriveId,
            attemptedVersion = :attemptedVersion, latestVersion = NULL, latestStatus = NULL,
            latestTitle = NULL, latestSummary = NULL, state = 'DETECTED',
            fetchGeneration = fetchGeneration + 1,
            detectedAtEpochMs = :now, fetchedAtEpochMs = NULL, resolvedAtEpochMs = NULL,
            lastFetchErrorCode = NULL
        WHERE draftId = :draftId AND clientRequestId = :clientRequestId
    """)
    suspend fun redetectPreservingReplayCount(
        draftId: String,
        clientRequestId: String,
        remoteDriveId: String,
        attemptedVersion: Int,
        now: Long,
    ): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'UNREVIEWABLE', latestVersion = NULL,
            latestStatus = NULL, latestTitle = NULL, latestSummary = NULL,
            resolvedAtEpochMs = NULL, lastFetchErrorCode = 'IDENTITY_UNAVAILABLE',
            detectedAtEpochMs = :now
        WHERE draftId = :draftId
    """)
    suspend fun markUnreviewablePreservingReplayCount(draftId: String, now: Long): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'FETCHING', fetchGeneration = fetchGeneration + 1,
            lastFetchErrorCode = NULL
        WHERE draftId = :draftId AND remoteDriveId = :remoteDriveId
          AND attemptedVersion = :attemptedVersion AND clientRequestId = :clientRequestId
          AND state IN ('DETECTED', 'FETCH_FAILED')
          AND NOT EXISTS (
              SELECT 1 FROM published_delete_requests d
              WHERE d.draftId = publish_conflicts.draftId
          )
    """)
    suspend fun claimFetch(draftId: String, clientRequestId: String, remoteDriveId: String, attemptedVersion: Int): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'SNAPSHOT_READY', latestVersion = :latestVersion,
            latestStatus = :latestStatus, latestTitle = :latestTitle, latestSummary = :latestSummary,
            fetchedAtEpochMs = :now, lastFetchErrorCode = NULL
        WHERE draftId = :draftId AND remoteDriveId = :remoteDriveId
          AND attemptedVersion = :attemptedVersion AND clientRequestId = :clientRequestId
          AND fetchGeneration = :fetchGeneration AND state = 'FETCHING'
          AND NOT EXISTS (
              SELECT 1 FROM published_delete_requests d
              WHERE d.draftId = publish_conflicts.draftId
          )
    """)
    suspend fun storeSnapshotCas(
        draftId: String,
        clientRequestId: String,
        remoteDriveId: String,
        attemptedVersion: Int,
        fetchGeneration: Int,
        latestVersion: Int,
        latestStatus: String,
        latestTitle: String,
        latestSummary: String,
        now: Long,
    ): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'FETCH_FAILED', lastFetchErrorCode = :errorCode
        WHERE draftId = :draftId AND remoteDriveId = :remoteDriveId
          AND attemptedVersion = :attemptedVersion AND clientRequestId = :clientRequestId
          AND fetchGeneration = :fetchGeneration AND state = 'FETCHING'
    """)
    suspend fun failFetchCas(
        draftId: String,
        clientRequestId: String,
        remoteDriveId: String,
        attemptedVersion: Int,
        fetchGeneration: Int,
        errorCode: String,
    ): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'FETCH_FAILED', lastFetchErrorCode = 'INTERRUPTED'
        WHERE state = 'FETCHING'
    """)
    suspend fun normalizeInterruptedFetches(): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'KEPT_LOCAL', resolvedAtEpochMs = :now
        WHERE draftId = :draftId AND remoteDriveId = :remoteDriveId
          AND attemptedVersion = :attemptedVersion AND clientRequestId = :clientRequestId
          AND state IN ('DETECTED', 'SNAPSHOT_READY', 'FETCH_FAILED')
          AND NOT EXISTS (
              SELECT 1 FROM published_delete_requests d
              WHERE d.draftId = publish_conflicts.draftId
          )
    """)
    suspend fun keepLocalCas(draftId: String, clientRequestId: String, remoteDriveId: String, attemptedVersion: Int, now: Long): Int

    @Query("""
        UPDATE publish_conflicts SET state = 'REPLAY_QUEUED', replayCount = replayCount + 1,
            resolvedAtEpochMs = NULL
        WHERE draftId = :draftId AND remoteDriveId = :remoteDriveId
          AND attemptedVersion = :attemptedVersion AND clientRequestId = :clientRequestId
          AND state = 'SNAPSHOT_READY'
          AND latestVersion = :latestVersion AND latestStatus = 'DRAFT'
          AND latestVersion > attemptedVersion AND replayCount < :maxReplays
          AND NOT EXISTS (
              SELECT 1 FROM published_delete_requests d
              WHERE d.draftId = publish_conflicts.draftId
          )
    """)
    suspend fun queueReplayCas(
        draftId: String,
        clientRequestId: String,
        remoteDriveId: String,
        attemptedVersion: Int,
        latestVersion: Int,
        maxReplays: Int,
    ): Int

    @Query("SELECT * FROM publish_conflicts WHERE state = 'REPLAY_QUEUED'")
    suspend fun replayQueued(): List<PublishConflictEntity>

    @Query("""
        UPDATE publish_conflicts SET state = 'RESOLVED', resolvedAtEpochMs = :now
        WHERE draftId = :draftId AND state = 'REPLAY_QUEUED'
    """)
    suspend fun resolveReplay(draftId: String, now: Long): Int
}
