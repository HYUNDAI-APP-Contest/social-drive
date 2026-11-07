package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.RouteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConsentLabelTest {
    @Test
    fun `granted consent is disclosed while live collection is unavailable`() {
        assertEquals(
            "현재 수집하지 않음 · 기존 동의 저장됨",
            routeConsentStorageLabel(RouteConsentState.Granted),
        )
    }

    @Test
    fun `not asked and withdrawn states do not claim stored consent`() {
        val expected = "현재 수집하지 않음 · 동의도 저장하지 않음"

        assertEquals(expected, routeConsentStorageLabel(RouteConsentState.NotAsked))
        assertEquals(expected, routeConsentStorageLabel(RouteConsentState.Withdrawn))
    }

    @Test
    fun `저장된 동의가 있고 권한만 철회되면 재허용 동선을 보여준다`() {
        val denied = RouteSource.LiveUnavailable(LiveUnavailableReason.PERMISSION_DENIED)

        assertTrue(routePermissionRecoveryVisible(RouteConsentState.Granted, denied))
        assertFalse(routePermissionRecoveryVisible(RouteConsentState.NotAsked, denied))
        assertFalse(routePermissionRecoveryVisible(RouteConsentState.Granted, RouteSource.LiveAvailable))
    }

    @Test
    fun `위치 권한 거부와 기능 미지원을 구분해서 안내한다`() {
        assertEquals(
            "PLEOS 위치 권한 필요",
            routeCapabilityTitle(RouteSource.LiveUnavailable(LiveUnavailableReason.PERMISSION_DENIED)),
        )
        assertEquals(
            "PLEOS 위치 기능 사용 불가",
            routeCapabilityTitle(RouteSource.LiveUnavailable(LiveUnavailableReason.CAPABILITY_MISSING)),
        )
        assertEquals("PLEOS 위치 SDK 사용 가능", routeCapabilityTitle(RouteSource.LiveAvailable))
    }
}
