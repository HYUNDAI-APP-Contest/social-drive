package com.socialdrive.app.data.remote

import com.socialdrive.app.domain.ConflictReviewPolicy
import com.socialdrive.app.domain.OwnedDriveConflictSnapshot

/**
 * Deliberately separate from the publishing worker API. Implementations return only a bounded,
 * non-secret owner snapshot; short-lived links, response headers and request IDs never cross it.
 */
fun interface RemoteConflictReviewApi {
    suspend fun getOwnedDrive(remoteDriveId: String): OwnedDriveConflictSnapshot
}

class CloudRemoteConflictReviewApi(private val client: CloudDriveClient) : RemoteConflictReviewApi {
    override suspend fun getOwnedDrive(remoteDriveId: String): OwnedDriveConflictSnapshot {
        val drive = try {
            client.getDrive(remoteDriveId).drive
        } catch (error: CloudDriveException) {
            val code = when (error.kind) {
                CloudFailureKind.NOT_FOUND -> RemoteFailureCode.NOT_FOUND
                CloudFailureKind.AUTHENTICATION -> RemoteFailureCode.AUTHENTICATION
                CloudFailureKind.FORBIDDEN -> RemoteFailureCode.REJECTED
                CloudFailureKind.RETRYABLE -> RemoteFailureCode.SERVER_BUSY
                else -> RemoteFailureCode.REJECTED
            }
            throw RemoteDriveException(code, error.retryable)
        }
        val snapshot = OwnedDriveConflictSnapshot(
            remoteDriveId = drive.id,
            version = drive.version,
            status = drive.status.name,
            title = drive.title,
            summary = drive.summary,
        )
        return ConflictReviewPolicy.bounded(snapshot)
            ?: throw RemoteDriveException(RemoteFailureCode.REJECTED, retryable = false)
    }
}

class UnavailableRemoteConflictReviewApi(
    private val code: RemoteFailureCode = RemoteFailureCode.CONFIGURATION,
    private val retryable: Boolean = false,
) : RemoteConflictReviewApi {
    override suspend fun getOwnedDrive(remoteDriveId: String): OwnedDriveConflictSnapshot =
        throw RemoteDriveException(code, retryable)
}
