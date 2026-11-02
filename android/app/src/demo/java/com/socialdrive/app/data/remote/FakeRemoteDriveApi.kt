package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Demo-only cloud simulator. It is not present in the actual flavor's compile classpath. */
class FakeRemoteDriveApi : RemoteDriveApi {
    private val drives = ConcurrentHashMap<String, RemoteDraftRef>()
    private val uploadedChecksums = ConcurrentHashMap<String, String>()
    private val published = ConcurrentHashMap.newKeySet<String>()

    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): RemoteDraftRef {
        currentCoroutineContext().ensureActive()
        yield()
        return drives.computeIfAbsent(clientRequestId) { RemoteDraftRef("demo-${draft.id}", 1) }
    }

    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) {
        currentCoroutineContext().ensureActive()
        yield()
        if (commands.map { it.kind }.toSet() != AssetKind.entries.toSet() || commands.size != AssetKind.entries.size) reject()
        commands.forEach { command ->
            val digest = MessageDigest.getInstance("SHA-256").digest(command.content)
            val actual = Base64.getEncoder().encodeToString(digest)
            val actualHex = digest.joinToString("") { "%02x".format(it) }
            val expectedKey = "${command.clientRequestId}:${command.assetId}:${command.checksumSha256Hex}"
            if (actual != command.checksumSha256Base64 || actualHex != command.checksumSha256Hex || command.idempotencyKey != expectedKey) reject()
            val session = "$remoteDriveId:${command.clientRequestId}:${command.assetId}"
            val existing = uploadedChecksums.putIfAbsent(session, command.checksumSha256Base64)
            if (existing != null && existing != command.checksumSha256Base64) reject()
        }
    }

    private fun reject(): Nothing = throw RemoteDriveException(RemoteFailureCode.REJECTED, retryable = false)

    override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): RemotePublishAck {
        currentCoroutineContext().ensureActive()
        yield()
        published += remoteDriveId
        return RemotePublishAck(expectedVersion + 1)
    }
}
