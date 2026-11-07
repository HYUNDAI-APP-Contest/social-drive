package com.socialdrive.app.data

import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RouteLocationConsentPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutePrivacyConsentPolicyTest {
    @Test
    fun `현재 정책 버전에 동의한 경우만 실시간 위치 동의로 인정한다`() {
        assertEquals(
            RouteConsentState.Granted,
            effectiveRouteConsentState(RouteConsentState.Granted.name, RouteLocationConsentPolicy.VERSION),
        )
    }

    @Test
    fun `이전 정책과 버전 없는 동의는 재동의 대기로 처리한다`() {
        assertEquals(RouteConsentState.NotAsked, effectiveRouteConsentState(RouteConsentState.Granted.name, "old-policy"))
        assertEquals(RouteConsentState.NotAsked, effectiveRouteConsentState(RouteConsentState.Granted.name, null))
        assertEquals(RouteConsentState.Withdrawn, effectiveRouteConsentState(RouteConsentState.Withdrawn.name, null))
    }
}
