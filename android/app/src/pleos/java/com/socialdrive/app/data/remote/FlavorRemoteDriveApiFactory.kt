package com.socialdrive.app.data.remote

/**
 * The actual flavor never falls back to the demo API. A configured host app must inject
 * [CloudRemoteDriveApi] after Cognito mobile client and API host settings are approved.
 */
object FlavorRemoteDriveApiFactory {
    fun create(): RemoteDriveApi = SessionAwareRemoteDriveApi {
        val client = PleosCloudRuntime.clientOrNull() ?: return@SessionAwareRemoteDriveApi unavailableForCurrentCloudState()
        val policy = PleosCloudRuntime.configuration?.consentPolicyVersion ?: return@SessionAwareRemoteDriveApi null
        CloudRemoteDriveApi(client, policy)
    }
}

private fun unavailableForCurrentCloudState(): RemoteDriveApi = when (PleosCloudRuntime.availability()) {
    CloudRuntimeAvailability.INITIALIZING -> UnavailableRemoteDriveApi(RemoteFailureCode.SERVER_BUSY, retryable = true)
    CloudRuntimeAvailability.AUTHENTICATION_REQUIRED -> UnavailableRemoteDriveApi(RemoteFailureCode.AUTHENTICATION)
    CloudRuntimeAvailability.CONFIGURATION_REQUIRED -> UnavailableRemoteDriveApi()
    // The session may have been installed between clientOrNull() and this snapshot; retry safely.
    CloudRuntimeAvailability.READY -> UnavailableRemoteDriveApi(RemoteFailureCode.SERVER_BUSY, retryable = true)
}
