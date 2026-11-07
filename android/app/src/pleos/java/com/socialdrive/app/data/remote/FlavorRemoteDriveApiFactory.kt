package com.socialdrive.app.data.remote

/**
 * The actual flavor never falls back to the demo API. A configured host app must inject
 * [CloudRemoteDriveApi] after Cognito mobile client and API host settings are approved.
 */
object FlavorRemoteDriveApiFactory {
    fun create(): RemoteDriveApi {
        val client = PleosCloudRuntime.clientOrNull() ?: return UnavailableRemoteDriveApi()
        val policyVersion = PleosCloudRuntime.configuration?.consentPolicyVersion ?: return UnavailableRemoteDriveApi()
        return CloudRemoteDriveApi(client, policyVersion)
    }
}
