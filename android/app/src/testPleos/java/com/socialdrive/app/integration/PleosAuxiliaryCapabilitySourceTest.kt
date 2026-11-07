package com.socialdrive.app.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PleosAuxiliaryCapabilitySourceTest {
    @Test
    fun `TTS STT 초기화 대기는 차량 UX를 무기한 차단하지 않는다`() {
        assertEquals(10_000L, AUXILIARY_INITIALIZATION_TIMEOUT_MILLIS)
        assertTrue(AUXILIARY_INITIALIZATION_TIMEOUT_MILLIS in 1_000L..15_000L)
    }

    @Test
    fun `STT 인식 세션은 자동으로 종료되는 상한을 둔다`() {
        assertEquals(30_000L, SPEECH_RECOGNITION_TIMEOUT_MILLIS)
        assertTrue(SPEECH_RECOGNITION_TIMEOUT_MILLIS in 10_000L..60_000L)
    }
}
