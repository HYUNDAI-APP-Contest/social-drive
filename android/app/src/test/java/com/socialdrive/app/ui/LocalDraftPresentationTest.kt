package com.socialdrive.app.ui

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.NormalizedRoutePoint
import com.socialdrive.app.domain.RouteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `경로점 없는 초안은 데모 아트워크임을 숨기지 않는다`() {
        val drive = snapshot(state = LocalState.PUBLISHED).toLocalDriveSummary(fallback)

        assertEquals("기기 게시 기록", drive.date)
        assertEquals("데모 아트워크", drive.distance)
        assertEquals(RouteSource.DemoPreset, drive.routeSource)
        assertEquals(fallback.route, drive.route)
    }

    private fun snapshot(
        state: String = LocalState.DRAFT,
        routePoints: List<NormalizedRoutePoint> = emptyList(),
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
        routePoints = routePoints,
    )
}
