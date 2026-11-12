package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.SpeechRecognitionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechRecognitionMessageTest {
    @Test
    fun `음성 인식 종료 이유를 사용자에게 구분해 안내한다`() {
        assertNull(speechRecognitionMessage(SpeechRecognitionState.Idle))
        assertNull(speechRecognitionMessage(SpeechRecognitionState.Listening))
        assertEquals("음성 인식 결과를 반영했어요.", speechRecognitionMessage(SpeechRecognitionState.Applied))
        assertEquals(
            "인식된 음성이 없어요. 다시 시도해 주세요.",
            speechRecognitionMessage(SpeechRecognitionState.NoResult),
        )
        assertEquals("음성 입력을 취소했어요.", speechRecognitionMessage(SpeechRecognitionState.Cancelled))
        assertEquals(
            "음성 인식을 시작하지 못했어요. 잠시 후 다시 시도해 주세요.",
            speechRecognitionMessage(SpeechRecognitionState.Failed),
        )
    }
}
