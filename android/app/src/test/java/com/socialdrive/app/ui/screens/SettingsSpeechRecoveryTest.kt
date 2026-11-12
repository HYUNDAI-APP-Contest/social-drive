package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSpeechRecoveryTest {
    @Test
    fun `실차 음성 장애와 권한 거부에만 복구 동작을 노출한다`() {
        val checking = actual(CapabilityState.Unavailable, "실차 · 확인 중")
        val unavailable = actual(CapabilityState.Unavailable, "실차 · 초기화 실패")
        val available = actual(CapabilityState.Available, "실차 · 온디바이스")
        val denied = actual(CapabilityState.PermissionDenied, "실차 · 권한 필요")
        val demoUnavailable = CapabilityStatus(
            CapabilityState.Unavailable,
            IntegrationSource.Demo,
            "데모 · 외부 연동 없음",
        )

        assertFalse(speechRecoveryVisible(checking, checking))
        assertTrue(speechRecoveryVisible(unavailable, available))
        assertTrue(speechRecoveryVisible(available, denied))
        assertTrue(speechPermissionDenied(available, denied))
        assertFalse(speechRecoveryVisible(demoUnavailable, demoUnavailable))
    }

    private fun actual(capability: CapabilityState, label: String) =
        CapabilityStatus(capability, IntegrationSource.Actual, label)
}
