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

    @Test
    fun `실제 주행 가능한 거리 집계는 유지한다`() {
        assertEquals(88, plausibleDistanceMeters(distanceMeters = 88, durationSeconds = 6))
        assertEquals(12_345, plausibleDistanceMeters(distanceMeters = 12_345, durationSeconds = 3_661))
    }

    @Test
    fun `오래된 GPS 점프로 불가능한 거리는 게시 입력에서 격리한다`() {
        assertEquals(0, plausibleDistanceMeters(distanceMeters = 21_400, durationSeconds = 23))
        assertEquals(0, plausibleDistanceMeters(distanceMeters = 500, durationSeconds = 0))
    }

    @Test
    fun `초기 위치 오차 허용 범위 경계는 결정적이다`() {
        assertEquals(1_600, plausibleDistanceMeters(distanceMeters = 1_600, durationSeconds = 6))
        assertEquals(0, plausibleDistanceMeters(distanceMeters = 1_601, durationSeconds = 6))
    }
}
