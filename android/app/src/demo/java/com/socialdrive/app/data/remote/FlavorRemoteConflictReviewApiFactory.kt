package com.socialdrive.app.data.remote

import com.socialdrive.app.domain.OwnedDriveConflictSnapshot

object FlavorRemoteConflictReviewApiFactory {
    fun create(): RemoteConflictReviewApi = DemoRemoteConflictReviewApi()
}

/** Demo-only deterministic owner snapshot. It contains no URL, token, coordinate, or vehicle data. */
private class DemoRemoteConflictReviewApi : RemoteConflictReviewApi {
    override suspend fun getOwnedDrive(remoteDriveId: String): OwnedDriveConflictSnapshot =
        OwnedDriveConflictSnapshot(
            remoteDriveId = remoteDriveId,
            version = 2,
            status = "DRAFT",
            title = "서버에 보관된 데모 초안",
            summary = "데모 데이터 · 로컬 초안은 자동으로 덮어쓰지 않습니다.",
        )
}
