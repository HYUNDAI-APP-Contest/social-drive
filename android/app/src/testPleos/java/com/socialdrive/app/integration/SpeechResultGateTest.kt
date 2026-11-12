package com.socialdrive.app.integration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechResultGateTest {
    @Test
    fun `현재 세션 콜백과 STT 권한이 모두 유효할 때만 음성 결과를 받는다`() {
        assertTrue(speechResultAccepted(true, true, true))
        assertFalse(speechResultAccepted(false, true, true))
        assertFalse(speechResultAccepted(true, false, true))
        assertFalse(speechResultAccepted(true, true, false))
    }
}
