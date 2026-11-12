package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudPublicationGateTest {
    @Test
    fun `실제 로컬 초안은 AWS 로그인 검증 전 게시를 잠근다`() {
        val signedOut = CapabilityStatus(
            CapabilityState.PermissionDenied,
            IntegrationSource.Actual,
            "AWS · 로그인 필요",
        )

        assertEquals(
            "AWS · 로그인 필요. 설정에서 AWS 연결 상태를 확인해 주세요.",
            cloudPublicationBlockReason(isLocalDraft = true, signedOut),
        )
    }

    @Test
    fun `데모 게시와 검증된 AWS 세션은 게시 게이트를 열어 둔다`() {
        val signedOut = CapabilityStatus(CapabilityState.PermissionDenied, IntegrationSource.Actual, "AWS · 로그인 필요")
        val signedIn = CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "AWS · 로그인 검증됨")

        assertNull(cloudPublicationBlockReason(isLocalDraft = false, signedOut))
        assertNull(cloudPublicationBlockReason(isLocalDraft = true, signedIn))
    }
}
