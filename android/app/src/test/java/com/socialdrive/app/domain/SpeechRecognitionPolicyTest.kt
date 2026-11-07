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
}
