package com.socialdrive.app.data.remote

import com.socialdrive.app.BuildConfig
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local bridge for the approved Cognito mobile login component.
 * Tokens are requested per operation and are never persisted by this runtime.
 */
object PleosCloudRuntime : CloudConnectionStatusSource {
    @Volatile
    private var sessionSource: CognitoSessionSource? = null
    private val sessionMutex = Mutex()

    val configuration: CloudRuntimeConfiguration? by lazy {
        CloudRuntimeConfiguration.parse(
            BuildConfig.CLOUD_API_BASE_URL,
            BuildConfig.CLOUD_UPLOAD_HOSTS,
            BuildConfig.CLOUD_CONSENT_POLICY_VERSION,
            BuildConfig.CLOUD_COGNITO_REGION,
            BuildConfig.CLOUD_COGNITO_USER_POOL_ID,
            BuildConfig.CLOUD_COGNITO_CLIENT_ID,
        )
    }
    private val mutableStatus = MutableStateFlow(configurationStatus())
    override val status: StateFlow<CapabilityStatus> = mutableStatus.asStateFlow()

    suspend fun installSessionSource(source: CognitoSessionSource): Boolean = sessionMutex.withLock {
        val configured = configuration
        if (configured == null) {
            sessionSource = null
            mutableStatus.value = configurationStatus()
            return@withLock false
        }
        val valid = runCatching {
            CognitoAccessTokenPolicy(configured.cognito).isValid(source.freshAccessToken())
        }.getOrDefault(false)
        sessionSource = source.takeIf { valid }
        mutableStatus.value = if (valid) {
            signedInStatus()
        } else {
            signedOutStatus("AWS · 로그인 검증 실패")
        }
        valid
    }

    suspend fun clearSessionSource() = sessionMutex.withLock {
        sessionSource = null
        mutableStatus.value = configurationStatus()
    }

    internal fun clientOrNull(): CloudDriveClient? {
        val configured = configuration ?: return null
        val session = sessionSource ?: return null
        val policy = CognitoAccessTokenPolicy(configured.cognito)
        val validatingSession = ValidatingCognitoSessionSource(session, policy) { valid ->
            if (sessionSource === session) {
                mutableStatus.value = if (valid) signedInStatus() else signedOutStatus("AWS · 로그인 갱신 필요")
            }
        }
        val transports = OkHttpCloudTransports.create()
        return CloudDriveClient(
            config = configured.client,
            auth = InMemoryCognitoAuthSessionProvider(validatingSession),
            apiTransport = transports,
            objectTransport = transports,
        )
    }

    private fun configurationStatus(): CapabilityStatus = if (configuration == null) {
        CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "AWS · 빌드 설정 필요")
    } else {
        signedOutStatus("AWS · 로그인 필요")
    }

    private fun signedInStatus() =
        CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "AWS · 로그인 검증됨")

    private fun signedOutStatus(label: String) =
        CapabilityStatus(CapabilityState.PermissionDenied, IntegrationSource.Actual, label)
}
