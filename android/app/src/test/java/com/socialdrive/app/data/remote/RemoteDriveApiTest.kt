package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteDriveApiTest {
    private val draft = DraftSnapshot(
        id = "draft-1",
        recordingId = "recording-1",
        clientRequestId = "drive-request-1",
        title = "데모",
        description = "데모",
        coverPreset = "route-memory-default",
        originalContentRef = "local-recording://recording-1",
        state = "QUEUED",
    )

    @Test
    fun `same asset session and checksum is replay safe`() = runTest {
        val api = FakeRemoteDriveApi()
        val remoteId = api.createDrive(draft.clientRequestId, draft)
        val command = draft.assetUploads().single()

        api.uploadAsset(remoteId, command)
        api.uploadAsset(remoteId, command)

        assertEquals(command.idempotencyKey, draft.assetUploads().single().idempotencyKey)
    }

    @Test
    fun `same session with a different checksum fails closed`() = runTest {
        val api = FakeRemoteDriveApi()
        val remoteId = api.createDrive(draft.clientRequestId, draft)
        val command = draft.assetUploads().single()
        api.uploadAsset(remoteId, command)

        val differentChecksum = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("different".toByteArray()),
        )
        val conflict = command.copy(
            idempotencyKey = "${command.clientRequestId}:${command.assetId}:$differentChecksum",
            checksumSha256Base64 = differentChecksum,
            content = "different".toByteArray(),
        )
        val error = assertFailsWith<RemoteDriveException> { api.uploadAsset(remoteId, conflict) }
        assertEquals(RemoteFailureCode.REJECTED, error.code)
        assertEquals(false, error.retryable)
    }
}
