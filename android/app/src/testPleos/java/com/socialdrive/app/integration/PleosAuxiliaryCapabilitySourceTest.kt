package com.socialdrive.app.integration

import com.socialdrive.app.domain.CapabilityState
import org.junit.Assert.assertFalse
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

    @Test
    fun `권한이 복구되면 실패하거나 거부됐던 음성 SDK 초기화를 재시도한다`() {
        assertTrue(shouldRetrySpeechInitialization(true, false, CapabilityState.PermissionDenied))
        assertTrue(shouldRetrySpeechInitialization(true, false, CapabilityState.Unavailable))
    }

    @Test
    fun `초기화 중이거나 완료됐거나 권한이 없으면 음성 SDK를 중복 초기화하지 않는다`() {
        assertFalse(shouldRetrySpeechInitialization(false, false, CapabilityState.Unavailable))
        assertFalse(shouldRetrySpeechInitialization(true, true, CapabilityState.Unavailable))
        assertFalse(shouldRetrySpeechInitialization(true, false, CapabilityState.Available))
    }
}
