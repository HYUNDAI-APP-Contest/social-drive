package com.socialdrive.app.integration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationSampleGateTest {
    @Test
    fun `권한 동의 기록과 현재 리스너가 모두 유효할 때만 위치를 받는다`() {
        assertTrue(locationSampleAccepted(true, true, true, true, true))

        assertFalse(locationSampleAccepted(false, true, true, true, true))
        assertFalse(locationSampleAccepted(true, false, true, true, true))
        assertFalse(locationSampleAccepted(true, true, false, true, true))
        assertFalse(locationSampleAccepted(true, true, true, false, true))
        assertFalse(locationSampleAccepted(true, true, true, true, false))
    }
}
