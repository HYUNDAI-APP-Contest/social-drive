package com.socialdrive.app.data.remote

import com.socialdrive.app.BuildConfig

/**
 * Process-local bridge for the approved Cognito mobile login component.
 * Tokens are requested per operation and are never persisted by this runtime.
 */
object PleosCloudRuntime {
    @Volatile
    private var sessionSource: CognitoSessionSource? = null

    val configuration: CloudRuntimeConfiguration? by lazy {
        CloudRuntimeConfiguration.parse(
            BuildConfig.CLOUD_API_BASE_URL,
            BuildConfig.CLOUD_UPLOAD_HOSTS,
            BuildConfig.CLOUD_CONSENT_POLICY_VERSION,
        )
    }

    fun installSessionSource(source: CognitoSessionSource) {
        sessionSource = source
    }

    fun clearSessionSource() {
        sessionSource = null
    }

    internal fun clientOrNull(): CloudDriveClient? {
        val configured = configuration ?: return null
        val session = sessionSource ?: return null
        val transports = OkHttpCloudTransports.create()
        return CloudDriveClient(
            config = configured.client,
            auth = InMemoryCognitoAuthSessionProvider(session),
            apiTransport = transports,
            objectTransport = transports,
        )
    }
}
