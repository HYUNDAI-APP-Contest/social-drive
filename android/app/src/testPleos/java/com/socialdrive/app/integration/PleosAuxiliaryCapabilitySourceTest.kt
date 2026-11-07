package com.socialdrive.app.integration

import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.SpeechRecognitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(shouldRetrySpeechInitialization(true, false, false, CapabilityState.PermissionDenied))
        assertTrue(shouldRetrySpeechInitialization(true, false, false, CapabilityState.Unavailable))
    }

    @Test
    fun `초기화 중이거나 완료됐거나 권한이 없으면 음성 SDK를 중복 초기화하지 않는다`() {
        assertFalse(shouldRetrySpeechInitialization(false, false, false, CapabilityState.Unavailable))
        assertFalse(shouldRetrySpeechInitialization(true, true, false, CapabilityState.Unavailable))
        assertFalse(shouldRetrySpeechInitialization(true, false, true, CapabilityState.Unavailable))
        assertFalse(shouldRetrySpeechInitialization(true, false, false, CapabilityState.Available))
    }

    @Test
    fun `음성 서비스 호출 실패는 권한과 서비스 장애를 구분한다`() {
        val disconnected = speechCapabilityAfterOperationFailure(permissionGranted = true)
        assertEquals(CapabilityState.Unavailable, disconnected.capability)
        assertEquals(IntegrationSource.Actual, disconnected.source)
        assertEquals("실차 · 서비스 연결 끊김", disconnected.label)

        val denied = speechCapabilityAfterOperationFailure(permissionGranted = false)
        assertEquals(CapabilityState.PermissionDenied, denied.capability)
        assertEquals("실차 · 권한 필요", denied.label)
    }

    @Test
    fun `STT 시간 초과는 결과 없음이며 capability를 끊지 않는다`() {
        assertEquals(SpeechRecognitionState.NoResult, speechRecognitionStateAfterError(SttErrorType.TIMEOUT))
        assertFalse(sttErrorInvalidatesCapability(SttErrorType.TIMEOUT))
    }

    @Test
    fun `STT 엔진과 권한 오류는 capability 재초기화를 요구한다`() {
        val serviceErrors = listOf(
            SttErrorType.AUDIO_ERROR,
            SttErrorType.MIC_ERROR,
            SttErrorType.PERMISSION_DENIED,
            SttErrorType.ON_DEVICE_ENGINE_ERROR,
            SttErrorType.UNAVAILABLE_LANGUAGE,
            SttErrorType.NOT_SUPPORTED_API,
        )

        serviceErrors.forEach { error ->
            assertEquals(SpeechRecognitionState.Failed, speechRecognitionStateAfterError(error))
            assertTrue(sttErrorInvalidatesCapability(error))
        }
    }

    @Test
    fun `이전 STT 세션의 늦은 오류는 현재 capability를 끊지 않는다`() {
        assertFalse(shouldInvalidateSttCapability(false, SttErrorType.ON_DEVICE_ENGINE_ERROR))
        assertFalse(shouldInvalidateSttCapability(false, SttErrorType.PERMISSION_DENIED))
        assertTrue(shouldInvalidateSttCapability(true, SttErrorType.ON_DEVICE_ENGINE_ERROR))
    }
}
