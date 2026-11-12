package com.socialdrive.app.ui

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.NormalizedRoutePoint
import com.socialdrive.app.domain.RouteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.ZoneId

class LocalDraftPresentationTest {
    private val fallback = requireNotNull(FakeDriveRepository().drive("demo-coast"))

    @Test
    fun `정규화 경로 초안을 홈용 실제 기록으로 변환한다`() {
        val draft = snapshot(
            routePoints = listOf(
                NormalizedRoutePoint(1, 1f, 0f),
                NormalizedRoutePoint(0, 0f, 1f),
            ),
        )

        val drive = draft.toLocalDriveSummary(fallback)

        assertEquals("local-draft-1", drive.id)
        assertEquals("기기 초안 · 게시 전", drive.date)
        assertEquals("2개 경로점", drive.distance)
        assertEquals(RouteSource.LiveAvailable, drive.routeSource)
        assertEquals(listOf(0f, 1f), drive.route.map { it.horizontal })
        assertNull(drive.navigationRequest)
    }

    @Test
    fun `기기 기록에 개인정보 안전 집계 거리와 실제 시간을 표시한다`() {
        val drive = snapshot(
            routePoints = listOf(
                NormalizedRoutePoint(0, 0f, 1f),
                NormalizedRoutePoint(1, 1f, 0f),
            ),
            distanceMeters = 12_345,
            durationSeconds = 3_661,
        ).toLocalDriveSummary(fallback)

        assertEquals("12.3 km", drive.distance)
        assertEquals("1시간 1분", drive.duration)
        assertEquals("999 m", localDistanceLabel(999))
        assertEquals("59초", localDurationLabel(59))
    }

    @Test
    fun `경로점 없는 초안은 데모 아트워크임을 숨기지 않는다`() {
        val drive = snapshot(state = LocalState.PUBLISHED).toLocalDriveSummary(fallback)

        assertEquals("기기 게시 기록", drive.date)
        assertEquals("데모 아트워크", drive.distance)
        assertEquals(RouteSource.DemoPreset, drive.routeSource)
        assertEquals(fallback.route, drive.route)
    }

    @Test
    fun `기기 기록의 실제 생성 시각을 한국어로 표시한다`() {
        val label = localDraftDateLabel(
            createdAtEpochMs = 1_799_290_800_000L,
            published = false,
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        assertEquals("1월 7일 12:00 · 기기 초안 · 게시 전", label)
    }

    private fun snapshot(
        state: String = LocalState.DRAFT,
        routePoints: List<NormalizedRoutePoint> = emptyList(),
        distanceMeters: Int = 0,
        durationSeconds: Int = 0,
    ) = DraftSnapshot(
        id = "draft-1",
        recordingId = "recording-1",
        clientRequestId = "request-1",
        title = "나의 실제 드라이브",
        description = "기기에 안전하게 보존한 기록",
        coverPreset = "route-memory-demo-coast",
        originalContentRef = "local-recording://recording-1",
        state = state,
        highlightCount = 2,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        routePoints = routePoints,
    )
}
