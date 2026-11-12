package com.socialdrive.app.ui

import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.RoutePoint
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

    @Test
    fun `실시간 수집 중에는 데모 경로선을 실제 경로처럼 표시하지 않는다`() {
        val preset = listOf(RoutePoint(0f, 0f), RoutePoint(1f, 1f))

        assertEquals(emptyList(), recordingArtworkRoute(RouteSource.LiveAvailable, preset))
        assertEquals(preset, recordingArtworkRoute(RouteSource.DemoPreset, preset))
    }

    @Test
    fun `프로세스 재시작 뒤 활성 녹화 화면을 복원한다`() {
        assertEquals(
            AppDestination.Recording,
            recoverRecordingDestination(AppDestination.Home, hasActiveRecording = true),
        )
        assertEquals(
            AppDestination.Publish,
            recoverRecordingDestination(AppDestination.Publish, hasActiveRecording = true),
        )
        assertEquals(
            AppDestination.Home,
            recoverRecordingDestination(AppDestination.Home, hasActiveRecording = false),
        )
    }
}
