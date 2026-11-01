package com.socialdrive.app.data.work

import com.socialdrive.app.data.remote.RemoteFailureCode

enum class PublishFailureAction { REWIND_UPLOAD, STOP_PERMANENT, STANDARD_FAILURE }
enum class RecoveryEnqueueMode { KEEP, REPLACE }
data class RewindHandoffResult(val committed: Boolean, val scheduled: Boolean)

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
