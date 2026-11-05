package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.RouteConsentState
import org.junit.Assert.assertEquals
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
}
