package com.socialdrive.app.data.remote

/** Production deletion remains unavailable until authenticated CloudDriveClient config is injected. */
object FlavorRemoteDeleteApiFactory {
    fun create(): RemoteDeleteApi = SessionAwareRemoteDeleteApi
}

private object SessionAwareRemoteDeleteApi : RemoteDeleteApi {
    override suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed =
        (PleosCloudRuntime.clientOrNull()?.let(::CloudRemoteDeleteApi) ?: UnavailableRemoteDeleteApi())
            .deletePublishedDrive(remoteDriveId, stableKeyHash)
}
