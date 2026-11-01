package com.socialdrive.app.data.remote

import com.socialdrive.app.domain.DeleteFailureCode
import kotlinx.coroutines.CancellationException

data object RemoteDeleteConfirmed

class RemoteDeleteException(val failureCode: DeleteFailureCode) : RuntimeException(failureCode.name)

interface RemoteDeleteApi {
    /**
     * Returns only for a backend 204. A 404 must be surfaced as NOT_FOUND because the current
     * backend has no deletion receipt and intentionally hides missing/non-owner resources alike.
     */
    suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed
}

class CloudRemoteDeleteApi(private val client: CloudDriveClient) : RemoteDeleteApi {
    override suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed = try {
        client.deleteDrive(remoteDriveId, stableKeyHash)
        RemoteDeleteConfirmed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CloudDriveException) {
        throw RemoteDeleteException(
            when (failure.kind) {
                CloudFailureKind.AUTHENTICATION -> DeleteFailureCode.AUTHENTICATION
                CloudFailureKind.FORBIDDEN -> DeleteFailureCode.FORBIDDEN
                CloudFailureKind.NOT_FOUND -> DeleteFailureCode.NOT_FOUND
                CloudFailureKind.VERSION_CONFLICT, CloudFailureKind.IDEMPOTENCY_CONFLICT -> DeleteFailureCode.VERSION_CONFLICT
                CloudFailureKind.RETRYABLE, CloudFailureKind.UPLOAD_EXPIRED -> DeleteFailureCode.SERVER_BUSY
                CloudFailureKind.VALIDATION, CloudFailureKind.CONTRACT -> DeleteFailureCode.REJECTED
                else -> DeleteFailureCode.UNKNOWN
            },
        )
    }
}

class UnavailableRemoteDeleteApi : RemoteDeleteApi {
    override suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed =
        throw RemoteDeleteException(DeleteFailureCode.REJECTED)
}
