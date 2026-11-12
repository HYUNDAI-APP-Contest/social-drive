package com.socialdrive.app.data.remote

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import kotlinx.coroutines.flow.MutableStateFlow

object FlavorCloudConnectionStatusFactory {
    fun create(): CloudConnectionStatusSource = object : CloudConnectionStatusSource {
        override val status = MutableStateFlow(
            CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Demo, "데모 · 외부 통신 없음"),
        )
    }
}
