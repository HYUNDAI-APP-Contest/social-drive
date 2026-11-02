package com.socialdrive.app.data.work

import com.socialdrive.app.data.remote.RemoteFailureCode

enum class PublishFailureAction { REWIND_UPLOAD, STOP_PERMANENT, STANDARD_FAILURE }
enum class RecoveryEnqueueMode { KEEP, REPLACE }
data class RewindHandoffResult(val committed: Boolean, val scheduled: Boolean)
data class WorkerStageLease(val stage: String, val attempt: Int)
data class WorkerQueueSnapshot(
    val stage: String,
    val status: String,
    val attempts: Int,
    val lastErrorCode: String? = null,
)
data class DraftRemoteSnapshot(val state: String, val remoteDriveId: String?, val remoteVersion: Int?)

/**
 * SDK-independent mirror of the Room compare-and-set predicates used by publish workers.
 * Room remains authoritative; this contract makes replacement overlap behavior deterministic in JVM tests.
 */
object WorkerTransitionContract {
    private val claimableStatuses = setOf("QUEUED", "FAILED", "PAUSED_MOTION")

    fun claim(snapshot: WorkerQueueSnapshot): WorkerStageLease? =
        snapshot.takeIf {
            it.status in claimableStatuses &&
                !(it.status == "FAILED" && it.lastErrorCode == "VERSION_CONFLICT")
        }
            ?.let { WorkerStageLease(stage = it.stage, attempt = it.attempts + 1) }

    fun owns(snapshot: WorkerQueueSnapshot, lease: WorkerStageLease): Boolean =
        snapshot.stage == lease.stage && snapshot.status == "RUNNING" && snapshot.attempts == lease.attempt

    fun transition(
        snapshot: WorkerQueueSnapshot,
        lease: WorkerStageLease,
        nextStage: String,
        nextStatus: String,
    ): WorkerQueueSnapshot? = snapshot.takeIf { owns(it, lease) }
        ?.copy(stage = nextStage, status = nextStatus)

    fun acceptsCreateResult(current: DraftRemoteSnapshot, expected: DraftRemoteSnapshot): Boolean = current == expected

    fun acceptsCreateReplayIdentity(existingRemoteId: String?, replayRemoteId: String): Boolean =
        existingRemoteId == null || existingRemoteId == replayRemoteId

    fun acceptsPublishResult(
        current: DraftRemoteSnapshot,
        expected: DraftRemoteSnapshot,
        expectedRemoteId: String,
        expectedVersion: Int,
    ): Boolean = current == expected && current.remoteDriveId == expectedRemoteId && current.remoteVersion == expectedVersion
}

object PublishRecoveryPolicy {
    const val MAX_UPLOAD_REWINDS = 2

    fun decide(code: RemoteFailureCode, completedRewinds: Int): PublishFailureAction = when {
        code == RemoteFailureCode.VERSION_CONFLICT -> PublishFailureAction.STOP_PERMANENT
        code == RemoteFailureCode.UPLOAD_INCOMPLETE && completedRewinds < MAX_UPLOAD_REWINDS -> PublishFailureAction.REWIND_UPLOAD
        code == RemoteFailureCode.UPLOAD_INCOMPLETE -> PublishFailureAction.STOP_PERMANENT
        else -> PublishFailureAction.STANDARD_FAILURE
    }

    fun recoveryEnqueueMode(status: String, stage: String, lastErrorCode: String?): RecoveryEnqueueMode =
        if (status == "PAUSED_MOTION" || lastErrorCode == "REMOTE_VERSION_REQUIRED" ||
            (stage == "UPLOAD" && lastErrorCode == "UPLOAD_INCOMPLETE")
        ) {
            RecoveryEnqueueMode.REPLACE
        } else {
            RecoveryEnqueueMode.KEEP
        }
}

object DurableRewindHandoff {
    /** Persists the rewind before enqueue. A failed enqueue is recoverable from the committed row on restart. */
    suspend fun commitThenSchedule(
        persist: suspend () -> Boolean,
        schedule: () -> Unit,
    ): RewindHandoffResult {
        if (!persist()) return RewindHandoffResult(committed = false, scheduled = false)
        val scheduled = try {
            schedule()
            true
        } catch (_: Exception) {
            false
        }
        return RewindHandoffResult(committed = true, scheduled = scheduled)
    }
}
