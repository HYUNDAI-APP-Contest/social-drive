package com.socialdrive.app.data.remote

import android.content.Context
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.AuthSignInResult
import com.amplifyframework.auth.result.step.AuthSignInStep
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.AmplifyConfiguration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal const val COGNITO_OPERATION_TIMEOUT_MILLIS = 20_000L

/** Cognito SRP adapter. Credentials and tokens never leave SDK/process memory through this class. */
internal class AmplifyCognitoAuth(
    private val context: Context,
) : CloudAuthBootstrap, CognitoSessionSource {
    private val mutableState = MutableStateFlow(initialState())
    private val operationGate = CognitoOperationGate()
    override val state = mutableState.asStateFlow()

    override suspend fun initialize() {
        val configuration = PleosCloudRuntime.configuration ?: return
        if (!operationGate.tryEnter()) return
        try {
            try {
                Amplify.addPlugin(AWSCognitoAuthPlugin())
                Amplify.configure(amplifyConfiguration(configuration.cognito), context)
                val session = withCognitoOperationTimeout { fetchCognitoSession() }
                val restored = session.isSignedIn && PleosCloudRuntime.installSessionSource(this)
                if (!session.isSignedIn) {
                    PleosCloudRuntime.clearSessionSource()
                }
                mutableState.value = restoredCognitoSessionState(session.isSignedIn, restored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                PleosCloudRuntime.reportAuthenticationFailure("AWS · 인증 초기화 실패")
                mutableState.value = CloudAuthState(CloudAuthPhase.ERROR, "AWS 인증을 초기화하지 못했습니다")
            }
        } finally {
            operationGate.leave()
        }
    }

    override suspend fun signIn(username: String, password: CharArray) {
        if (PleosCloudRuntime.configuration == null) {
            password.fill('\u0000')
            return
        }
        val normalizedUsername = username.trim()
        if (!CloudAuthInputPolicy.acceptsCredentials(normalizedUsername, password)) {
            password.fill('\u0000')
            mutableState.value = CloudAuthState(CloudAuthPhase.ERROR, "아이디와 비밀번호 형식을 확인해 주세요")
            return
        }
        if (!operationGate.tryEnter()) {
            password.fill('\u0000')
            return
        }
        try {
            mutableState.value = CloudAuthState(CloudAuthPhase.WORKING, "AWS 로그인 확인 중")
            val secret = password.concatToString()
            password.fill('\u0000')
            try {
                val result = withCognitoOperationTimeout { awaitSignIn(normalizedUsername, secret) }
                applySignInResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                PleosCloudRuntime.reportAuthenticationFailure("AWS · 로그인 실패")
                mutableState.value = CloudAuthState(CloudAuthPhase.ERROR, "로그인 정보를 확인해 주세요")
            }
        } finally {
            password.fill('\u0000')
            operationGate.leave()
        }
    }

    override suspend fun confirmSignIn(response: CharArray) {
        if (!CloudAuthInputPolicy.acceptsChallenge(response)) {
            response.fill('\u0000')
            val challengeLabel = mutableState.value.challengeLabel
            mutableState.value = CloudAuthState(
                CloudAuthPhase.CHALLENGE,
                "인증 응답 형식을 확인해 주세요",
                challengeLabel,
            )
            return
        }
        if (!operationGate.tryEnter()) {
            response.fill('\u0000')
            return
        }
        try {
            val challengeLabel = mutableState.value.challengeLabel
            mutableState.value = CloudAuthState(CloudAuthPhase.WORKING, "추가 인증 확인 중")
            val secret = response.concatToString()
            response.fill('\u0000')
            try {
                applySignInResult(withCognitoOperationTimeout { awaitConfirmSignIn(secret) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = CloudAuthState(CloudAuthPhase.CHALLENGE, "인증 응답을 다시 확인해 주세요", challengeLabel)
            }
        } finally {
            response.fill('\u0000')
            operationGate.leave()
        }
    }

    override suspend fun signOut() {
        if (!operationGate.tryEnter()) return
        mutableState.value = CloudAuthState(CloudAuthPhase.WORKING, "로그아웃 중")
        try {
            try {
                withCognitoOperationTimeout { clearAmplifySession() }
                PleosCloudRuntime.clearSessionSource()
                mutableState.value = signedOutState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = CloudAuthState(CloudAuthPhase.SIGNED_IN, "로그아웃을 완료하지 못했습니다. 다시 시도해 주세요")
            }
        } finally {
            operationGate.leave()
        }
    }

    private suspend fun clearAmplifySession() {
        suspendCancellableCoroutine { continuation ->
            Amplify.Auth.signOut {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override suspend fun freshAccessToken(): String {
        operationGate.enter()
        try {
            val session = withCognitoOperationTimeout { fetchCognitoSession() }
            val token = session.takeIf { it.isSignedIn }
                ?.userPoolTokensResult?.value?.accessToken
            return token?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Cognito session is signed out")
        } finally {
            operationGate.leave()
        }
    }

    private suspend fun fetchCognitoSession(): AWSCognitoAuthSession =
        suspendCancellableCoroutine { continuation ->
            Amplify.Auth.fetchAuthSession(
                { session ->
                    if (!continuation.isActive) return@fetchAuthSession
                    val cognito = session as? AWSCognitoAuthSession
                    if (cognito == null) {
                        continuation.resumeWithException(IllegalStateException("Unexpected Cognito session type"))
                    } else {
                        continuation.resume(cognito)
                    }
                },
                { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
            )
        }

    private fun amplifyConfiguration(cognito: CognitoRuntimeConfiguration): AmplifyConfiguration {
        val userPool = JSONObject()
            .put("PoolId", cognito.userPoolId)
            .put("AppClientId", cognito.clientId)
            .put("Region", cognito.region)
        val plugin = JSONObject()
            .put("CognitoUserPool", JSONObject().put("Default", userPool))
            .put(
                "Auth",
                JSONObject().put(
                    "Default",
                    JSONObject().put("authenticationFlowType", "USER_SRP_AUTH"),
                ),
            )
        val root = JSONObject().put(
            "auth",
            JSONObject().put(
                "plugins",
                JSONObject().put("awsCognitoAuthPlugin", plugin),
            ),
        )
        return AmplifyConfiguration.fromJson(root)
    }

    private suspend fun awaitSignIn(username: String, password: String): AuthSignInResult =
        suspendCancellableCoroutine { continuation ->
            Amplify.Auth.signIn(
                username,
                password,
                { result -> if (continuation.isActive) continuation.resume(result) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
            )
        }

    private suspend fun awaitConfirmSignIn(response: String): AuthSignInResult =
        suspendCancellableCoroutine { continuation ->
            Amplify.Auth.confirmSignIn(
                response,
                { result -> if (continuation.isActive) continuation.resume(result) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
            )
        }

    private suspend fun applySignInResult(result: AuthSignInResult) {
        if (result.isSignedIn) {
            val valid = PleosCloudRuntime.installSessionSource(this)
            mutableState.value = if (valid) signedInState() else CloudAuthState(CloudAuthPhase.ERROR, "AWS 세션 검증에 실패했습니다")
            return
        }
        val step = result.nextStep.signInStep
        val challenge = step.challengeLabel()
        if (challenge == null) {
            clearAmplifySession()
            PleosCloudRuntime.clearSessionSource()
            mutableState.value = CloudAuthState(CloudAuthPhase.ERROR, step.unsupportedMessage())
        } else {
            mutableState.value = CloudAuthState(CloudAuthPhase.CHALLENGE, "추가 인증이 필요합니다", challenge)
        }
    }

    private fun initialState() = if (PleosCloudRuntime.configuration == null) {
        CloudAuthState(CloudAuthPhase.UNAVAILABLE, "AWS 빌드 설정이 필요합니다")
    } else {
        CloudAuthState(CloudAuthPhase.WORKING, "AWS 인증 준비 중")
    }

    private fun signedOutState() = restoredCognitoSessionState(isSignedIn = false, sessionInstalled = false)
    private fun signedInState() = CloudAuthState(CloudAuthPhase.SIGNED_IN, "AWS 계정 연결됨")
}

internal fun restoredCognitoSessionState(isSignedIn: Boolean, sessionInstalled: Boolean): CloudAuthState = when {
    !isSignedIn -> CloudAuthState(CloudAuthPhase.SIGNED_OUT, "AWS 계정에 로그인해 주세요")
    sessionInstalled -> CloudAuthState(CloudAuthPhase.SIGNED_IN, "AWS 계정 연결됨")
    else -> CloudAuthState(CloudAuthPhase.ERROR, "저장된 AWS 세션을 다시 확인해 주세요")
}

internal suspend fun <T> withCognitoOperationTimeout(
    timeoutMillis: Long = COGNITO_OPERATION_TIMEOUT_MILLIS,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMillis) { block() }
} catch (_: TimeoutCancellationException) {
    throw CognitoOperationTimeoutException()
}

internal class CognitoOperationTimeoutException : Exception("Cognito operation timed out")

internal class CognitoOperationGate {
    private val mutex = Mutex()

    fun tryEnter(): Boolean = mutex.tryLock()

    suspend fun enter() = mutex.lock()

    fun leave() = mutex.unlock()
}

internal fun AuthSignInStep.challengeLabel(): String? = when (this) {
    AuthSignInStep.CONFIRM_SIGN_IN_WITH_SMS_MFA_CODE -> "문자 인증코드"
    AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE -> "인증 앱 코드"
    AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD -> "새 비밀번호"
    AuthSignInStep.CONFIRM_SIGN_IN_WITH_CUSTOM_CHALLENGE -> "추가 인증 응답"
    AuthSignInStep.CONFIRM_SIGN_IN_WITH_OTP -> "일회용 인증코드"
    AuthSignInStep.CONFIRM_SIGN_IN_WITH_PASSWORD -> "비밀번호"
    else -> null
}

internal fun AuthSignInStep.unsupportedMessage(): String = when (this) {
    AuthSignInStep.RESET_PASSWORD -> "비밀번호 재설정이 필요한 계정입니다. 관리자에게 문의해 주세요"
    AuthSignInStep.CONFIRM_SIGN_UP -> "가입 확인이 필요한 계정입니다. 관리자에게 문의해 주세요"
    AuthSignInStep.CONTINUE_SIGN_IN_WITH_TOTP_SETUP,
    AuthSignInStep.CONTINUE_SIGN_IN_WITH_EMAIL_MFA_SETUP -> "MFA 등록이 필요한 계정입니다. 관리자에게 문의해 주세요"
    AuthSignInStep.CONTINUE_SIGN_IN_WITH_MFA_SETUP_SELECTION,
    AuthSignInStep.CONTINUE_SIGN_IN_WITH_MFA_SELECTION,
    AuthSignInStep.CONTINUE_SIGN_IN_WITH_FIRST_FACTOR_SELECTION -> "지원되지 않는 인증 선택 단계입니다. 관리자에게 문의해 주세요"
    else -> "이 계정의 추가 인증을 앱에서 완료할 수 없습니다. 관리자에게 문의해 주세요"
}
