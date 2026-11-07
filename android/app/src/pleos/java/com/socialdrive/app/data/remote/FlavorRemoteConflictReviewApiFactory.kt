package com.socialdrive.app.data.remote

/** Actual cloud/mobile auth configuration is intentionally absent and fails closed. */
object FlavorRemoteConflictReviewApiFactory {
    fun create(): RemoteConflictReviewApi = SessionAwareConflictReviewApi
}

private object SessionAwareConflictReviewApi : RemoteConflictReviewApi {
    override suspend fun getOwnedDrive(remoteDriveId: String) =
        (PleosCloudRuntime.clientOrNull()?.let(::CloudRemoteConflictReviewApi) ?: unavailableConflictForCurrentCloudState())
            .getOwnedDrive(remoteDriveId)
}

private fun unavailableConflictForCurrentCloudState(): RemoteConflictReviewApi = when (PleosCloudRuntime.availability()) {
    CloudRuntimeAvailability.INITIALIZING -> UnavailableRemoteConflictReviewApi(RemoteFailureCode.SERVER_BUSY, retryable = true)
    CloudRuntimeAvailability.AUTHENTICATION_REQUIRED -> UnavailableRemoteConflictReviewApi(RemoteFailureCode.AUTHENTICATION)
    CloudRuntimeAvailability.CONFIGURATION_REQUIRED -> UnavailableRemoteConflictReviewApi()
    CloudRuntimeAvailability.READY -> UnavailableRemoteConflictReviewApi(RemoteFailureCode.SERVER_BUSY, retryable = true)
}
