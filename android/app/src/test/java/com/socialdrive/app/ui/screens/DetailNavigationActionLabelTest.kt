package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationState
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailNavigationActionLabelTest {
    private val request = NavigationRequest(
        requestId = "privacy-reviewed-route",
        destination = NavigationPlace("테스트 목적지", 37.0, 127.0),
    )

    @Test
    fun `unavailable navigation with a request remains connection guidance`() {
        val unavailable = CapabilityStatus(
            capability = CapabilityState.Unavailable,
            source = IntegrationSource.Unavailable,
            label = "사용 불가",
        )

        assertEquals(
            " 차량 내비게이션 연결 안내",
            navigationActionLabel(
                capability = unavailable.capability,
                request = request,
                state = NavigationState.Idle(unavailable),
            ),
        )
    }

    @Test
    fun `available navigation with a request offers route handoff`() {
        val available = CapabilityStatus(
            capability = CapabilityState.Available,
            source = IntegrationSource.Actual,
            label = "사용 가능",
        )

        assertEquals(
            " 차량 내비게이션으로 보내기",
            navigationActionLabel(
                capability = available.capability,
                request = request,
                state = NavigationState.Idle(available),
            ),
        )
    }
}
