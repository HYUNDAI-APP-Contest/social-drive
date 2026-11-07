package com.socialdrive.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechRecognitionPolicyTest {
    private val available = CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "실차")

    @Test
    fun `주차 상태이며 STT가 사용 가능할 때만 시작한다`() {
        assertTrue(SpeechRecognitionPolicy.canStart(MotionState.Parked, available))
        assertFalse(SpeechRecognitionPolicy.canStart(MotionState.Driving, available))
        assertFalse(SpeechRecognitionPolicy.canStart(MotionState.Unknown, available))
        assertFalse(
            SpeechRecognitionPolicy.canStart(
                MotionState.Parked,
                CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "연결 안 됨"),
            ),
        )
    }

    @Test
    fun `최종 음성 인식 결과만 초안에 반영한다`() {
        assertFalse(SpeechRecognitionPolicy.shouldCommit("제주 해안", isFinal = false))
        assertFalse(SpeechRecognitionPolicy.shouldCommit("   ", isFinal = true))
        assertTrue(SpeechRecognitionPolicy.shouldCommit("제주 해안 드라이브", isFinal = true))
    }

    @Test
    fun `최종 반영 시점에도 주차 상태를 다시 확인한다`() {
        assertTrue(SpeechRecognitionPolicy.canCommit(MotionState.Parked, "제주 해안", isFinal = true))
        assertFalse(SpeechRecognitionPolicy.canCommit(MotionState.Driving, "제주 해안", isFinal = true))
        assertFalse(SpeechRecognitionPolicy.canCommit(MotionState.Unknown, "제주 해안", isFinal = true))
        assertFalse(SpeechRecognitionPolicy.canCommit(MotionState.Parked, "제주 해안", isFinal = false))
    }
}
