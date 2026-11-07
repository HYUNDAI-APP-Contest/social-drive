package com.socialdrive.app.data.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object FlavorCloudAuthBootstrapFactory {
    fun create(context: Context): CloudAuthBootstrap = DemoCloudAuthBootstrap
}

private object DemoCloudAuthBootstrap : CloudAuthBootstrap {
    override val state = MutableStateFlow(CloudAuthState(CloudAuthPhase.UNAVAILABLE, "데모 · 외부 로그인 없음"))
    override suspend fun initialize() = Unit
    override suspend fun signIn(username: String, password: CharArray) = password.fill('\u0000')
    override suspend fun confirmSignIn(response: CharArray) = response.fill('\u0000')
    override suspend fun signOut() = Unit
}
