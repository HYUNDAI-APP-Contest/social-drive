package com.socialdrive.app.data.remote

/** Actual cloud/mobile auth configuration is intentionally absent and fails closed. */
object FlavorRemoteConflictReviewApiFactory {
    fun create(): RemoteConflictReviewApi = SessionAwareConflictReviewApi
}

private object SessionAwareConflictReviewApi : RemoteConflictReviewApi {
    override suspend fun getOwnedDrive(remoteDriveId: String) =
        (PleosCloudRuntime.clientOrNull()?.let(::CloudRemoteConflictReviewApi) ?: UnavailableRemoteConflictReviewApi())
            .getOwnedDrive(remoteDriveId)
}
