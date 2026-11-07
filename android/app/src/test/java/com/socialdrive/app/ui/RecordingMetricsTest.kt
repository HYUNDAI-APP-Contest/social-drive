package com.socialdrive.app.ui

import com.socialdrive.app.domain.RouteSource
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingMetricsTest {
    @Test
    fun `Room 시작 시각부터 실제 경과 시간을 표시한다`() {
        assertEquals("00:00:00", formatRecordingElapsed(10_000L, 9_000L))
        assertEquals("00:01:05", formatRecordingElapsed(10_000L, 75_999L))
        assertEquals("25:02:03", formatRecordingElapsed(0L, 90_123_000L))
    }

    @Test
    fun `실시간과 데모 경로 상태를 구분한다`() {
        assertEquals("경로 수집 중", recordingRouteStatus(RouteSource.LiveAvailable))
        assertEquals("데모 경로", recordingRouteStatus(RouteSource.DemoPreset))
    }
}
