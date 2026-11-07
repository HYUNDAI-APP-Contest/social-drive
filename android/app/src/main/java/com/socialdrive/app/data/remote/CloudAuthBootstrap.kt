package com.socialdrive.app.data.remote

import kotlinx.coroutines.flow.StateFlow

enum class CloudAuthPhase { UNAVAILABLE, SIGNED_OUT, WORKING, CHALLENGE, SIGNED_IN, ERROR }

data class CloudAuthState(
    val phase: CloudAuthPhase,
    val message: String,
    val challengeLabel: String? = null,
)

/** Initializes the flavor-specific authentication SDK and restores a secure SDK session, if any. */
interface CloudAuthBootstrap {
    val state: StateFlow<CloudAuthState>
    suspend fun initialize()
    suspend fun signIn(username: String, password: CharArray)
    suspend fun confirmSignIn(response: CharArray)
    suspend fun signOut()
}
