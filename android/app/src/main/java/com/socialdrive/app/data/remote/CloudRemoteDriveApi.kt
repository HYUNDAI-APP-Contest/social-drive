package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import kotlinx.coroutines.CancellationException

/** Worker-facing production adapter. Every retry reuses the frozen manifest and stable request id. */
class CloudRemoteDriveApi(
    private val client: CloudDriveClient,
    private val consentPolicyVersion: String,
) : RemoteDriveApi {
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String = translate {
        require(clientRequestId == draft.clientRequestId)
        val assets = draft.assetUploads().map(AssetUploadCommand::manifest)
        client.createDrive(
            CreateDriveInput(
                clientRequestId = clientRequestId,
                title = draft.title,
                summary = draft.description,
                distanceMeters = 0,
                durationSeconds = 0,
                highlightCount = 0,
                consentPolicyVersion = consentPolicyVersion,
                assets = assets,
            ),
        ).drive.id
    }

    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) = translate {
        if (commands.size != AssetKind.entries.size || commands.map { it.kind }.toSet() != AssetKind.entries.toSet()) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "INCOMPLETE_UPLOAD_MANIFEST", false)
        }
        commands.forEach { command ->
            val expectedKey = "${command.clientRequestId}:${command.assetId}:${command.checksumSha256Hex}"
            if (command.idempotencyKey != expectedKey) {
                throw CloudDriveException(CloudFailureKind.CONTRACT, "INVALID_UPLOAD_IDEMPOTENCY", false)
            }
        }
        val manifests = commands.map(AssetUploadCommand::manifest)
        val session = client.refreshUploads(remoteDriveId, manifests)
        commands.forEach { command ->
            val instruction = session.uploads.singleOrNull { it.kind == command.kind }
                ?: throw CloudDriveException(CloudFailureKind.CONTRACT, "MISSING_UPLOAD", false)
            client.uploadAsset(instruction, command.manifest(), command.content)
        }
    }

    override suspend fun publishDrive(remoteDriveId: String, clientRequestId: String) = translate {
        // Version is intentionally refreshed after process restart instead of being persisted with a URL/token.
        val detail = client.getDrive(remoteDriveId)
        client.publishDrive(remoteDriveId, detail.drive.version)
        Unit
    }

    private suspend fun <T> translate(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CloudDriveException) {
        throw RemoteDriveException(
            code = when (failure.kind) {
                CloudFailureKind.AUTHENTICATION -> RemoteFailureCode.AUTHENTICATION
                CloudFailureKind.NOT_FOUND -> RemoteFailureCode.NOT_FOUND
                CloudFailureKind.IDEMPOTENCY_CONFLICT, CloudFailureKind.VERSION_CONFLICT -> RemoteFailureCode.CONFLICT
                CloudFailureKind.VALIDATION, CloudFailureKind.CONTRACT -> RemoteFailureCode.REJECTED
                CloudFailureKind.RETRYABLE -> RemoteFailureCode.SERVER_BUSY
                CloudFailureKind.UNKNOWN -> RemoteFailureCode.UNKNOWN
            },
            retryable = failure.retryable,
        )
    }
}

private fun AssetUploadCommand.manifest() = AssetManifest(
    kind = kind,
    contentType = contentType,
    sizeBytes = content.size.toLong(),
    sha256Hex = checksumSha256Hex,
)
