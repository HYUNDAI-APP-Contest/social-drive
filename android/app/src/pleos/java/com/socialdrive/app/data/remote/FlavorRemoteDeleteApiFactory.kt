package com.socialdrive.app.data.remote

/** Production deletion remains unavailable until authenticated CloudDriveClient config is injected. */
object FlavorRemoteDeleteApiFactory {
    fun create(): RemoteDeleteApi = PleosCloudRuntime.clientOrNull()
        ?.let(::CloudRemoteDeleteApi)
        ?: UnavailableRemoteDeleteApi()
}
