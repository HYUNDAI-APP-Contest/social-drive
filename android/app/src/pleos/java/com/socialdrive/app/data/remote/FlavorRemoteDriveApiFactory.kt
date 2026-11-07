package com.socialdrive.app.data.remote

/**
 * The actual flavor never falls back to the demo API. A configured host app must inject
 * [CloudRemoteDriveApi] after Cognito mobile client and API host settings are approved.
 */
object FlavorRemoteDriveApiFactory {
    fun create(): RemoteDriveApi = SessionAwareRemoteDriveApi {
        val client = PleosCloudRuntime.clientOrNull() ?: return@SessionAwareRemoteDriveApi null
        val policy = PleosCloudRuntime.configuration?.consentPolicyVersion ?: return@SessionAwareRemoteDriveApi null
        CloudRemoteDriveApi(client, policy)
    }
}
