package com.socialdrive.app.domain

import java.security.MessageDigest

enum class DeleteRequestState {
    DELETE_PENDING,
    RUNNING,
    RETRY_WAIT,
    CONFIRMED,
    NOT_FOUND_UNCONFIRMED,
    AUTH_REQUIRED,
    VERSION_CONFLICT,
    FAILED_PERMANENT,
    LEGACY_UNREVIEWABLE,
}

enum class DeleteFailureCode {
    OFFLINE,
    TIMEOUT,
    SERVER_BUSY,
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    VERSION_CONFLICT,
    REJECTED,
    UNKNOWN,
}

data class DeleteQueueSnapshot(
    val state: DeleteRequestState,
    val attempts: Int,
    val lastErrorCode: String? = null,
)

data class DeleteLease(val attempt: Int)

sealed interface DeleteFailureDecision {
    data class Retry(val nextAttemptAtEpochMs: Long) : DeleteFailureDecision
    data class Stop(val state: DeleteRequestState) : DeleteFailureDecision
}

object DeleteLifecyclePolicy {
    const val MAX_ATTEMPTS = 4
    private const val BASE_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L

    fun claim(snapshot: DeleteQueueSnapshot): DeleteLease? = snapshot
        .takeIf { it.state in setOf(DeleteRequestState.DELETE_PENDING, DeleteRequestState.RETRY_WAIT) }
        ?.takeIf { it.attempts < MAX_ATTEMPTS }
        ?.let { DeleteLease(it.attempts + 1) }

    fun decideFailure(code: DeleteFailureCode, attempt: Int, nowEpochMs: Long): DeleteFailureDecision = when (code) {
        DeleteFailureCode.NOT_FOUND -> DeleteFailureDecision.Stop(DeleteRequestState.NOT_FOUND_UNCONFIRMED)
        DeleteFailureCode.AUTHENTICATION, DeleteFailureCode.FORBIDDEN ->
            DeleteFailureDecision.Stop(DeleteRequestState.AUTH_REQUIRED)
        DeleteFailureCode.VERSION_CONFLICT -> DeleteFailureDecision.Stop(DeleteRequestState.VERSION_CONFLICT)
        DeleteFailureCode.OFFLINE, DeleteFailureCode.TIMEOUT, DeleteFailureCode.SERVER_BUSY -> {
            if (attempt >= MAX_ATTEMPTS) {
                DeleteFailureDecision.Stop(DeleteRequestState.FAILED_PERMANENT)
            } else {
                val exponent = (attempt - 1).coerceIn(0, 10)
                val delay = (BASE_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
                DeleteFailureDecision.Retry(nowEpochMs + delay)
            }
        }
        DeleteFailureCode.REJECTED, DeleteFailureCode.UNKNOWN ->
            DeleteFailureDecision.Stop(DeleteRequestState.FAILED_PERMANENT)
    }

    /**
     * Stable, non-secret request identity persisted in Room and sent as Idempotency-Key.
     * It is derived only from already-durable identifiers and never from a token or response receipt.
     */
    fun stableKeyHash(clientRequestId: String, remoteDriveId: String): String {
        require(clientRequestId.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        require(remoteDriveId.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        return MessageDigest.getInstance("SHA-256")
            .digest("delete-v1:$clientRequestId:$remoteDriveId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
