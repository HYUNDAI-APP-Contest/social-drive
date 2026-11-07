package com.socialdrive.app.data.remote

import android.content.Context
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.AmplifyConfiguration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/** Cognito SRP adapter. Credentials and tokens never leave SDK/process memory through this class. */
internal class AmplifyCognitoAuth(
    private val context: Context,
) : CloudAuthBootstrap, CognitoSessionSource {
    override suspend fun initialize() {
        val configuration = PleosCloudRuntime.configuration ?: return
        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(amplifyConfiguration(configuration.cognito), context)
            PleosCloudRuntime.installSessionSource(this)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            PleosCloudRuntime.reportAuthenticationFailure("AWS · 인증 초기화 실패")
        }
    }

    override suspend fun freshAccessToken(): String = suspendCancellableCoroutine { continuation ->
        Amplify.Auth.fetchAuthSession(
            { session ->
                if (!continuation.isActive) return@fetchAuthSession
                val cognito = session as? AWSCognitoAuthSession
                val token = cognito?.takeIf { it.isSignedIn }
                    ?.userPoolTokensResult?.value?.accessToken
                if (token.isNullOrBlank()) {
                    continuation.resumeWithException(IllegalStateException("Cognito session is signed out"))
                } else {
                    continuation.resume(token)
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
}
