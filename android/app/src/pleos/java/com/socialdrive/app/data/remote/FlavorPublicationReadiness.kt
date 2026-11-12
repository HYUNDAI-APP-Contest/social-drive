package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.hasPublishableRecordedRoute

object FlavorPublicationReadiness {
    fun rejectionMessage(draft: DraftSnapshot): String? = if (!draft.hasPublishableRecordedRoute()) {
        "게시할 실제 경로가 충분하지 않아요. 위치 권한과 경로 동의를 확인한 뒤 새 기록을 만들어 주세요."
    } else {
        null
    }
}
