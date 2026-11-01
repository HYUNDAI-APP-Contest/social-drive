package com.socialdrive.app.data

import androidx.room.withTransaction
import com.socialdrive.app.data.local.DraftEntity
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.data.local.RecordingEntity
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.local.UploadQueueEntity
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.UploadState
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun interface IdFactory {
    fun create(): String
}

object RandomIdFactory : IdFactory {
    override fun create(): String = UUID.randomUUID().toString()
}

data class DraftSnapshot(
    val id: String,
    val recordingId: String,
    val clientRequestId: String,
    val title: String,
    val description: String,
    val coverPreset: String,
    val originalContentRef: String,
    val state: String,
)

sealed interface EnqueueResult {
    data class Enqueued(val draftId: String, val clientRequestId: String) : EnqueueResult
    data object AlreadyQueued : EnqueueResult
    data object AlreadyPublished : EnqueueResult
    data class Rejected(val message: String) : EnqueueResult
    data object MissingDraft : EnqueueResult
}

class RecordingRepository(
    private val database: SocialDriveDatabase,
    private val ids: IdFactory = RandomIdFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val active: Flow<RecordingEntity?> = database.recordingDao().observeActive()

    suspend fun start(): RecordingEntity = database.withTransaction {
        database.recordingDao().findActive()?.let { return@withTransaction it }
        RecordingEntity(
            id = ids.create(),
            state = LocalState.RECORDING,
            startedAtEpochMs = clock(),
            endedAtEpochMs = null,
            highlightCount = 0,
        ).also { database.recordingDao().insert(it) }
    }

    suspend fun addHighlight(id: String) {
        database.recordingDao().incrementHighlight(id)
    }

    suspend fun finishAsDraft(id: String): DraftSnapshot? = database.withTransaction {
        database.draftDao().findByRecording(id)?.toSnapshot()?.let { return@withTransaction it }
        val recording = database.recordingDao().find(id) ?: return@withTransaction null
        val now = clock()
        database.recordingDao().update(recording.copy(state = LocalState.DRAFTED, endedAtEpochMs = now))
        val draft = DraftEntity(
            id = ids.create(),
            recordingId = recording.id,
            clientRequestId = "drive-${ids.create()}",
            title = "새로운 드라이브 기록",
            description = "좋았던 순간 ${recording.highlightCount}개를 안전하게 보관했어요.",
            coverPreset = "route-memory-default",
            originalContentRef = "local-recording://${recording.id}",
            state = LocalState.DRAFT,
            remoteDriveId = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            publishedAtEpochMs = null,
        )
        database.draftDao().insert(draft)
        draft.toSnapshot()
    }

    suspend fun finishActiveAsDraft(): DraftSnapshot? =
        database.recordingDao().findActive()?.let { finishAsDraft(it.id) }
}

class DraftRepository(
    private val database: SocialDriveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val drafts: Flow<List<DraftSnapshot>> = database.draftDao().observeAll().map { items -> items.map(DraftEntity::toSnapshot) }

    suspend fun find(id: String): DraftSnapshot? = database.draftDao().find(id)?.toSnapshot()

    suspend fun updateCopy(id: String, title: String, description: String): Boolean =
        database.draftDao().updateEditableCopy(id, title.take(40), description.take(120), clock()) == 1
}

class PublishQueueRepository(
    private val database: SocialDriveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeState(draftId: String): Flow<UploadState> = database.uploadQueueDao().observe(draftId).map { item ->
        when (item?.status) {
            null -> UploadState.Draft
            LocalState.QUEUED -> UploadState.Queued
            LocalState.RUNNING -> UploadState.Uploading
            LocalState.FAILED -> UploadState.Failed
            LocalState.PAUSED_MOTION -> UploadState.Queued
            LocalState.PUBLISHED -> UploadState.Published
            else -> UploadState.Draft
        }
    }

    suspend fun enqueue(draftId: String, motionState: MotionState): EnqueueResult {
        if (motionState != MotionState.Parked) {
            return EnqueueResult.Rejected("주차 상태가 확인되지 않아 게시 대기열에 추가하지 않았어요. 초안은 이 기기에 보존됩니다.")
        }
        return database.withTransaction {
            val draft = database.draftDao().find(draftId) ?: return@withTransaction EnqueueResult.MissingDraft
            if (draft.state == LocalState.PUBLISHED) return@withTransaction EnqueueResult.AlreadyPublished
            val existing = database.uploadQueueDao().find(draftId)
            if (existing != null && existing.status != LocalState.FAILED) return@withTransaction EnqueueResult.AlreadyQueued
            val now = clock()
            database.draftDao().updateRemoteState(draft.id, LocalState.QUEUED, draft.remoteDriveId, now)
            database.uploadQueueDao().upsert(
                UploadQueueEntity(
                    draftId = draft.id,
                    clientRequestId = draft.clientRequestId,
                    stage = existing?.stage ?: LocalState.CREATE,
                    status = LocalState.QUEUED,
                    attempts = existing?.attempts ?: 0,
                    nextAttemptAtEpochMs = now,
                    lastErrorCode = null,
                    updatedAtEpochMs = now,
                ),
            )
            EnqueueResult.Enqueued(draft.id, draft.clientRequestId)
        }
    }

    suspend fun recoverable(): List<UploadQueueEntity> = database.uploadQueueDao().recoverable()

    suspend fun pauseRecoverableForMotion() {
        val now = clock()
        database.withTransaction {
            database.uploadQueueDao().recoverable().forEach { item ->
                database.uploadQueueDao().updateStage(
                    draftId = item.draftId,
                    stage = item.stage,
                    status = LocalState.PAUSED_MOTION,
                    attempts = item.attempts,
                    nextAttemptAt = now,
                    lastErrorCode = "MOTION_NOT_PARKED",
                    now = now,
                )
            }
        }
    }
}

private fun DraftEntity.toSnapshot() = DraftSnapshot(
    id = id,
    recordingId = recordingId,
    clientRequestId = clientRequestId,
    title = title,
    description = description,
    coverPreset = coverPreset,
    originalContentRef = originalContentRef,
    state = state,
)
