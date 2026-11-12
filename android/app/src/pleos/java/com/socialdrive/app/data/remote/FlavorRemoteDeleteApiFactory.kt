package com.socialdrive.app.data.remote

import com.socialdrive.app.domain.DeleteFailureCode

/** Production deletion remains unavailable until authenticated CloudDriveClient config is injected. */
object FlavorRemoteDeleteApiFactory {
    fun create(): RemoteDeleteApi = SessionAwareRemoteDeleteApi
}

private object SessionAwareRemoteDeleteApi : RemoteDeleteApi {
    override suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed =
        (PleosCloudRuntime.clientOrNull()?.let(::CloudRemoteDeleteApi) ?: unavailableDeleteForCurrentCloudState())
            .deletePublishedDrive(remoteDriveId, stableKeyHash)
}

private fun unavailableDeleteForCurrentCloudState(): RemoteDeleteApi = when (PleosCloudRuntime.availability()) {
    CloudRuntimeAvailability.INITIALIZING -> UnavailableRemoteDeleteApi(DeleteFailureCode.SERVER_BUSY)
    CloudRuntimeAvailability.AUTHENTICATION_REQUIRED -> UnavailableRemoteDeleteApi(DeleteFailureCode.AUTHENTICATION)
    CloudRuntimeAvailability.CONFIGURATION_REQUIRED -> UnavailableRemoteDeleteApi()
    CloudRuntimeAvailability.READY -> UnavailableRemoteDeleteApi(DeleteFailureCode.SERVER_BUSY)
}
