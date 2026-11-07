package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionAwareRemoteDriveApiTest {
    @Test
    fun `로그인 이후에는 시작 시 생성된 어댑터도 현재 세션을 사용한다`() = runTest {
        var current: RemoteDriveApi? = null
        val adapter = SessionAwareRemoteDriveApi { current }
        val unavailable = assertFailsWith<RemoteDriveException> { adapter.uploadAssets("remote-1", emptyList()) }
        assertEquals(RemoteFailureCode.CONFIGURATION, unavailable.code)

        val calls = mutableListOf<String>()
        current = object : RemoteDriveApi {
            override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot) = RemoteDraftRef("unused", 1)
            override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) { calls += remoteDriveId }
            override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String) = RemotePublishAck(1)
        }
        adapter.uploadAssets("remote-2", emptyList())
        assertEquals(listOf("remote-2"), calls)

        current = null
        assertFailsWith<RemoteDriveException> { adapter.uploadAssets("remote-3", emptyList()) }
    }
}
