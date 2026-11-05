package com.socialdrive.app.ui.components

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegrationStatusLabelTest {
    @Test
    fun `pleos vehicle state preserves missing parking signal reason`() {
        val status = CapabilityStatus(
            CapabilityState.Unavailable,
            IntegrationSource.Actual,
            "실차 · 주차 신호 없음",
        )

        assertEquals("PLEOS 연결됨 · 주차 신호 미확인", integrationStatusLabel(status, "차량 상태"))
    }

    @Test
    fun `unavailable navigation does not imply a successful connection`() {
        val status = CapabilityStatus(
            CapabilityState.Unavailable,
            IntegrationSource.Actual,
            "실차 · 연결 안 됨",
        )

        assertEquals("차량 내비게이션 연결 미확인", integrationStatusLabel(status, "차량 내비게이션"))
    }
}
