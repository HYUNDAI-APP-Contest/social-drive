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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local bridge for the approved Cognito mobile login component.
 * Tokens are requested per operation and are never persisted by this runtime.
 */
object PleosCloudRuntime : CloudConnectionStatusSource {
    private val sessionSource = AtomicReference<InstalledCognitoSession?>(null)
    private val authenticationInitialized = AtomicBoolean(false)
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

    internal fun availability(): CloudRuntimeAvailability = when {
        configuration == null -> CloudRuntimeAvailability.CONFIGURATION_REQUIRED
        sessionSource.get() != null -> CloudRuntimeAvailability.READY
        !authenticationInitialized.get() -> CloudRuntimeAvailability.INITIALIZING
        else -> CloudRuntimeAvailability.AUTHENTICATION_REQUIRED
    }

    internal fun markAuthenticationInitialized() {
        authenticationInitialized.set(true)
    }

    suspend fun installSessionSource(source: CognitoSessionSource): Boolean = sessionMutex.withLock {
        val configured = configuration
        if (configured == null) {
            sessionSource.set(null)
            mutableStatus.value = configurationStatus()
            return@withLock false
        }
        val valid = validateCognitoSession(source, CognitoAccessTokenPolicy(configured.cognito))
        sessionSource.set(InstalledCognitoSession(source).takeIf { valid })
        mutableStatus.value = if (valid) {
            signedInStatus()
        } else {
            signedOutStatus("AWS · 로그인 검증 실패")
        }
        valid
    }

    suspend fun clearSessionSource() = sessionMutex.withLock {
        sessionSource.set(null)
        mutableStatus.value = configurationStatus()
    }

    internal suspend fun reportAuthenticationFailure(label: String) = sessionMutex.withLock {
        sessionSource.set(null)
        mutableStatus.value = if (configuration == null) configurationStatus() else signedOutStatus(label)
    }

    internal fun clientOrNull(): CloudDriveClient? {
        val configured = configuration ?: return null
        val installedSession = sessionSource.get() ?: return null
        val session = installedSession.source
        val policy = CognitoAccessTokenPolicy(configured.cognito)
        val validatingSession = ValidatingCognitoSessionSource(
            delegate = session,
            validator = policy,
            onValidation = { valid ->
                if (valid) {
                    if (sessionSource.get() === installedSession) mutableStatus.value = signedInStatus()
                } else if (sessionSource.compareAndSet(installedSession, null)) {
                    mutableStatus.value = signedOutStatus("AWS · 로그인 갱신 필요")
                }
            },
            isCurrent = { sessionSource.get() === installedSession },
        )
        val transports = sharedCloudTransports()
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

private data class InstalledCognitoSession(val source: CognitoSessionSource)

internal enum class CloudRuntimeAvailability {
    READY,
    INITIALIZING,
    AUTHENTICATION_REQUIRED,
    CONFIGURATION_REQUIRED,
}
