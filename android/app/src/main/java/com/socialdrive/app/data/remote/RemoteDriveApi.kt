package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

enum class RemoteFailureCode { OFFLINE, TIMEOUT, SERVER_BUSY, REJECTED, UNKNOWN }

class RemoteDriveException(
    val code: RemoteFailureCode,
    val retryable: Boolean,
) : RuntimeException(code.name)

data class AssetUploadCommand(
    val clientRequestId: String,
    val assetId: String,
    val idempotencyKey: String,
    val checksumSha256Base64: String,
    val contentType: String,
    val content: ByteArray,
)

fun DraftSnapshot.assetUploads(): List<AssetUploadCommand> {
    val content = "route-memory:$coverPreset:$originalContentRef".toByteArray(Charsets.UTF_8)
    val checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(content))
    val assetId = "route-memory-cover-v1"
    return listOf(
        AssetUploadCommand(
            clientRequestId = clientRequestId,
            assetId = assetId,
            idempotencyKey = "$clientRequestId:$assetId:$checksum",
            checksumSha256Base64 = checksum,
            contentType = "application/vnd.socialdrive.route-memory+json",
            content = content,
        ),
    )
}

/**
 * Production adapters must request/reissue the backend upload session with [AssetUploadCommand.idempotencyKey],
 * sign both content type and SHA-256 checksum, and treat an existing object with the same checksum as success.
 * A different checksum for the same `(clientRequestId, assetId)` is a permanent conflict. Presigned URLs and
 * credentials are consumed in memory and never returned to Room or WorkManager input data.
 */
interface RemoteDriveApi {
    suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String
    suspend fun uploadAsset(remoteDriveId: String, command: AssetUploadCommand)
    suspend fun publishDrive(remoteDriveId: String, clientRequestId: String)
}

class FakeRemoteDriveApi : RemoteDriveApi {
    private val drives = ConcurrentHashMap<String, String>()
    private val uploadedChecksums = ConcurrentHashMap<String, String>()
    private val published = ConcurrentHashMap.newKeySet<String>()

    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String =
        drives.computeIfAbsent(clientRequestId) { "demo-${draft.id}" }

    override suspend fun uploadAsset(remoteDriveId: String, command: AssetUploadCommand) {
        val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(command.content))
        val expectedKey = "${command.clientRequestId}:${command.assetId}:${command.checksumSha256Base64}"
        if (actual != command.checksumSha256Base64 || command.idempotencyKey != expectedKey) {
            throw RemoteDriveException(RemoteFailureCode.REJECTED, retryable = false)
        }
        val session = "$remoteDriveId:${command.clientRequestId}:${command.assetId}"
        val existing = uploadedChecksums.putIfAbsent(session, command.checksumSha256Base64)
        if (existing != null && existing != command.checksumSha256Base64) {
            throw RemoteDriveException(RemoteFailureCode.REJECTED, retryable = false)
        }
    }

    override suspend fun publishDrive(remoteDriveId: String, clientRequestId: String) {
        published += remoteDriveId
    }
}
