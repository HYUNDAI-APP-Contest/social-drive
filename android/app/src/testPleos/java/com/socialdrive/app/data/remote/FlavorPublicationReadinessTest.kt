package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.domain.NormalizedRoutePoint
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlavorPublicationReadinessTest {
    @Test
    fun `PLEOS는 실제 경로가 두 점 미만인 게시를 즉시 차단한다`() {
        val message = assertNotNull(FlavorPublicationReadiness.rejectionMessage(draft(emptyList())))
        assertTrue(message.contains("위치 권한"))
        assertTrue(message.contains("경로 동의"))
        assertNotNull(FlavorPublicationReadiness.rejectionMessage(draft(listOf(NormalizedRoutePoint(0, .5f, .5f)))))
    }

    @Test
    fun `PLEOS는 실제 경로가 두 점 이상이면 게시 준비를 통과한다`() {
        assertNull(
            FlavorPublicationReadiness.rejectionMessage(
                draft(listOf(NormalizedRoutePoint(0, .2f, .7f), NormalizedRoutePoint(1, .8f, .3f))),
            ),
        )
    }

    @Test
    fun `PLEOS는 중복 좌표와 역순 경로를 게시하지 않는다`() {
        assertNotNull(
            FlavorPublicationReadiness.rejectionMessage(
                draft(listOf(NormalizedRoutePoint(0, .5f, .5f), NormalizedRoutePoint(1, .5f, .5f))),
            ),
        )
        assertNotNull(
            FlavorPublicationReadiness.rejectionMessage(
                draft(listOf(NormalizedRoutePoint(1, .2f, .7f), NormalizedRoutePoint(0, .8f, .3f))),
            ),
        )
    }

    private fun draft(points: List<NormalizedRoutePoint>) = DraftSnapshot(
        id = "draft",
        recordingId = "recording",
        clientRequestId = "request",
        title = "제목",
        description = "설명",
        coverPreset = "cover",
        originalContentRef = "local-recording://recording",
        state = "DRAFT",
        routePoints = points,
    )
}
