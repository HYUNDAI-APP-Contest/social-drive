package com.socialdrive.app.data

import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.UploadState
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishQueuePresentationTest {
    @Test
    fun `동의 갱신 오류는 앱 재시작 뒤에도 전용 상태로 복원한다`() {
        assertEquals(
            UploadState.NeedsConsentRenewal,
            queueUploadState(LocalState.FAILED, LocalState.CONSENT_RENEWAL_REQUIRED),
        )
        assertEquals(UploadState.NeedsConflictReview, queueUploadState(LocalState.FAILED, LocalState.VERSION_CONFLICT))
        assertEquals(UploadState.Rejected, queueUploadState(LocalState.FAILED, "REJECTED"))
        assertEquals(UploadState.Rejected, queueUploadState(LocalState.FAILED, "NOT_FOUND"))
        assertEquals(UploadState.Rejected, queueUploadState(LocalState.FAILED, "CONFIGURATION"))
        assertEquals(UploadState.Failed, queueUploadState(LocalState.FAILED, "SERVER_BUSY"))
    }
}
