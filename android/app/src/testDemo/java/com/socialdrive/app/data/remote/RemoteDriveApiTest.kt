package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
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
    fun `same asset session and checksum is replay safe`() = runBlocking {
        val api = FakeRemoteDriveApi()
        val remoteId = api.createDrive(draft.clientRequestId, draft)
        val commands = draft.assetUploads()
        api.uploadAssets(remoteId, commands)
        api.uploadAssets(remoteId, commands)
        assertEquals(commands.first().idempotencyKey, draft.assetUploads().first().idempotencyKey)
    }

    @Test
    fun `same session with a different checksum fails closed`() = runBlocking {
        val api = FakeRemoteDriveApi()
        val remoteId = api.createDrive(draft.clientRequestId, draft)
        val commands = draft.assetUploads()
        val command = commands.first()
        api.uploadAssets(remoteId, commands)
        val differentChecksum = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("different".toByteArray()),
        )
        val conflict = command.copy(
            idempotencyKey = "${command.clientRequestId}:${command.assetId}:${"0".repeat(64)}",
            checksumSha256Hex = "0".repeat(64),
            checksumSha256Base64 = differentChecksum,
            content = "different".toByteArray(),
        )
        val error = assertFailsWith<RemoteDriveException> { api.uploadAssets(remoteId, listOf(conflict, commands.last())) }
        assertEquals(RemoteFailureCode.REJECTED, error.code)
        assertEquals(false, error.retryable)
    }
}
