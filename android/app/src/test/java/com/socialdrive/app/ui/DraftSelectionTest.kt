package com.socialdrive.app.ui

import com.socialdrive.app.data.DraftSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DraftSelectionTest {
    @Test
    fun `사용자가 고른 이전 초안을 유지한다`() {
        val latest = draft("latest")
        val previous = draft("previous")

        assertEquals(previous, selectVisibleDraft(listOf(latest, previous), previous.id))
    }

    @Test
    fun `선택 기록이 사라지면 최신 초안으로 안전하게 복구한다`() {
        val latest = draft("latest")

        assertEquals(latest, selectVisibleDraft(listOf(latest), "missing"))
        assertNull(selectVisibleDraft(emptyList(), "missing"))
    }

    private fun draft(id: String) = DraftSnapshot(
        id = id,
        recordingId = "recording-$id",
        clientRequestId = "request-$id",
        title = id,
        description = "기기 초안",
        coverPreset = "route-memory-demo-coast",
        originalContentRef = "local-recording://$id",
        state = "DRAFT",
    )
}
