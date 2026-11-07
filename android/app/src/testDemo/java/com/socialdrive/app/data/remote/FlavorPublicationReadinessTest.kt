package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import kotlin.test.Test
import kotlin.test.assertNull

class FlavorPublicationReadinessTest {
    @Test
    fun `데모는 합성 경로 게시 시연을 유지한다`() {
        val draft = DraftSnapshot(
            id = "draft",
            recordingId = "recording",
            clientRequestId = "request",
            title = "제목",
            description = "설명",
            coverPreset = "cover",
            originalContentRef = "local-recording://recording",
            state = "DRAFT",
        )

        assertNull(FlavorPublicationReadiness.rejectionMessage(draft))
    }
}
