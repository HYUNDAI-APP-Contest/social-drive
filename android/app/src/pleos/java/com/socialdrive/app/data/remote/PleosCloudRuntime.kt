package com.socialdrive.app.data.remote

import com.socialdrive.app.BuildConfig
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local bridge for the approved Cognito mobile login component.
 * Tokens are requested per operation and are never persisted by this runtime.
 */
object PleosCloudRuntime : CloudConnectionStatusSource {
    @Volatile
    private var sessionSource: CognitoSessionSource? = null

    val configuration: CloudRuntimeConfiguration? by lazy {
        CloudRuntimeConfiguration.parse(
            BuildConfig.CLOUD_API_BASE_URL,
            BuildConfig.CLOUD_UPLOAD_HOSTS,
            BuildConfig.CLOUD_CONSENT_POLICY_VERSION,
        )
    }
    private val mutableStatus = MutableStateFlow(configurationStatus())
    override val status: StateFlow<CapabilityStatus> = mutableStatus.asStateFlow()

    fun installSessionSource(source: CognitoSessionSource) {
        sessionSource = source
        mutableStatus.value = if (configuration != null) {
            CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "AWS · 로그인 연결됨")
        } else configurationStatus()
    }

    fun clearSessionSource() {
        sessionSource = null
        mutableStatus.value = configurationStatus()
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

    private fun configurationStatus(): CapabilityStatus = if (configuration == null) {
        CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "AWS · 빌드 설정 필요")
    } else {
        CapabilityStatus(CapabilityState.PermissionDenied, IntegrationSource.Actual, "AWS · 로그인 필요")
    }
}
