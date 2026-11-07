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

    @Test
    fun `양수 타임스탬프는 이전 위치보다 최신일 때만 받는다`() {
        assertTrue(locationTimestampAccepted(previousTimestampMillis = null, timestampMillis = 1_000L))
        assertTrue(locationTimestampAccepted(previousTimestampMillis = 1_000L, timestampMillis = 1_001L))

        assertFalse(locationTimestampAccepted(previousTimestampMillis = 1_000L, timestampMillis = 1_000L))
        assertFalse(locationTimestampAccepted(previousTimestampMillis = 1_000L, timestampMillis = 999L))
    }

    @Test
    fun `타임스탬프를 제공하지 않는 SDK 콜백은 호환성을 위해 받는다`() {
        assertTrue(locationTimestampAccepted(previousTimestampMillis = null, timestampMillis = 0L))
        assertTrue(locationTimestampAccepted(previousTimestampMillis = 1_000L, timestampMillis = 0L))
        assertTrue(locationTimestampAccepted(previousTimestampMillis = 1_000L, timestampMillis = -1L))
    }
}
