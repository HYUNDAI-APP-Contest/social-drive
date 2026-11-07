package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException

enum class RemoteFailureCode {
    OFFLINE,
    TIMEOUT,
    SERVER_BUSY,
    AUTHENTICATION,
    NOT_FOUND,
    VERSION_CONFLICT,
    UPLOAD_INCOMPLETE,
    UPLOAD_EXPIRED,
    REJECTED,
    CONFIGURATION,
    UNKNOWN,
}

class RemoteDriveException(
    val code: RemoteFailureCode,
    val retryable: Boolean,
) : RuntimeException(code.name)

data class AssetUploadCommand(
    val clientRequestId: String,
    val assetId: String,
    val kind: AssetKind,
    val idempotencyKey: String,
    val checksumSha256Hex: String,
    val checksumSha256Base64: String,
    val contentType: String,
    val content: ByteArray,
)

data class RemoteDraftRef(val id: String, val version: Int)
data class RemotePublishAck(val version: Int)

fun DraftSnapshot.assetUploads(): List<AssetUploadCommand> {
    // Contest-safe placeholders contain no original local reference, coordinate, or raw vehicle field.
    val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
    val route = """{"schemaVersion":1,"path":[{"horizontal":0.2,"vertical":0.7,"progress":0.0},{"horizontal":0.8,"vertical":0.3,"progress":1.0}],"highlights":[]}"""
        .toByteArray(Charsets.UTF_8)
    return listOf(
        uploadCommand(AssetKind.COVER, "route-memory-cover-v1", "image/jpeg", cover),
        uploadCommand(AssetKind.ROUTE, "route-memory-route-v1", "application/json", route),
    )
}

private fun DraftSnapshot.uploadCommand(
    kind: AssetKind,
    assetId: String,
    contentType: String,
    content: ByteArray,
): AssetUploadCommand {
    val digest = MessageDigest.getInstance("SHA-256").digest(content)
    val hex = digest.joinToString("") { "%02x".format(it) }
    val base64 = Base64.getEncoder().encodeToString(digest)
    return AssetUploadCommand(
        clientRequestId = clientRequestId,
        assetId = assetId,
        kind = kind,
        idempotencyKey = "$clientRequestId:$assetId:$hex",
        checksumSha256Hex = hex,
        checksumSha256Base64 = base64,
        contentType = contentType,
        content = content,
    )
}

/**
 * Production adapters must request/reissue the backend upload session with [AssetUploadCommand.idempotencyKey],
 * sign both content type and SHA-256 checksum, and treat an existing object with the same checksum as success.
 * A different checksum for the same `(clientRequestId, assetId)` is a permanent conflict. Presigned URLs and
 * credentials are consumed in memory and never returned to Room or WorkManager input data. Implementations must
 * use a cancellation-cooperative HTTP client and propagate [CancellationException] rather than swallowing it.
 */
interface RemoteDriveApi {
    suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): RemoteDraftRef
    suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>)
    suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): RemotePublishAck
}

/** Explicit fail-closed production boundary used when authenticated mobile configuration is absent. */
class UnavailableRemoteDriveApi : RemoteDriveApi {
    private fun unavailable(): Nothing = throw RemoteDriveException(RemoteFailureCode.CONFIGURATION, retryable = false)
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): RemoteDraftRef = unavailable()
    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) = unavailable()
    override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): RemotePublishAck = unavailable()
}

/** Resolves the authenticated adapter for every stage so login/logout does not stale WorkManager dependencies. */
class SessionAwareRemoteDriveApi(
    private val current: () -> RemoteDriveApi?,
) : RemoteDriveApi {
    private fun delegate(): RemoteDriveApi = current() ?: UnavailableRemoteDriveApi()

    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): RemoteDraftRef =
        delegate().createDrive(clientRequestId, draft)

    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) =
        delegate().uploadAssets(remoteDriveId, commands)

    override suspend fun publishDrive(remoteDriveId: String, expectedVersion: Int, clientRequestId: String): RemotePublishAck =
        delegate().publishDrive(remoteDriveId, expectedVersion, clientRequestId)
}
