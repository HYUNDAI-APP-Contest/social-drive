package com.socialdrive.app.ui.screens

import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.ui.components.routeArtworkDescription
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingRouteLabelTest {
    @Test
    fun `실시간 경로는 데모나 정밀 위치 미수집으로 표시하지 않는다`() {
        assertEquals("실시간 경로를 안전하게 기록하고 있어요", recordingRouteTitle(RouteSource.LiveAvailable))
        assertEquals(
            "원본 위치는 저장하지 않고 경로 형태만 보존합니다.",
            recordingRoutePrivacyLabel(RouteSource.LiveAvailable),
        )
        assertEquals(
            "원본 위치가 제거된 실시간 경로와 하이라이트 2개",
            routeArtworkDescription(RouteSource.LiveAvailable, 2),
        )
        assertEquals("원본 위치와 사진은 포함되지 않습니다", publishCoverPrivacyLabel(RouteSource.LiveAvailable))
        assertEquals(
            "정규화 경로 아트워크 · 제목과 설명 · 하이라이트 개수",
            publishSharedItemsLabel(RouteSource.LiveAvailable),
        )
    }

    @Test
    fun `데모 경로 표시는 기존 개인정보 경계를 유지한다`() {
        assertEquals("데모 경로를 기록하고 있어요", recordingRouteTitle(RouteSource.DemoPreset))
        assertEquals("정밀 위치는 수집하지 않습니다.", recordingRoutePrivacyLabel(RouteSource.DemoPreset))
    }
}
