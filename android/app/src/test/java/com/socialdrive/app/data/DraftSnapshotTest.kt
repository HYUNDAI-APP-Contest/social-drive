package com.socialdrive.app.data

import com.socialdrive.app.data.local.RecordingEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftSnapshotTest {
    @Test
    fun `녹화 시작과 종료 시각으로 게시용 주행 시간을 계산한다`() {
        val recording = RecordingEntity(
            id = "recording-1",
            state = "DRAFTED",
            startedAtEpochMs = 1_000L,
            endedAtEpochMs = 3_662_999L,
            highlightCount = 3,
        )

        assertEquals(3_661, recording.recordedDurationSeconds())
    }

    @Test
    fun `종료되지 않았거나 비정상적인 시각은 안전한 주행 시간으로 제한한다`() {
        val active = RecordingEntity("active", "RECORDING", 2_000L, null, 0)
        val reversed = RecordingEntity("reversed", "DRAFTED", 2_000L, 1_000L, 0)
        val oversized = RecordingEntity("oversized", "DRAFTED", 0L, Long.MAX_VALUE, 0)

        assertEquals(0, active.recordedDurationSeconds())
        assertEquals(0, reversed.recordedDurationSeconds())
        assertEquals(259_200, oversized.recordedDurationSeconds())
    }
}
