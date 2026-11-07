package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.hasPublishableRecordedRoute
import kotlinx.coroutines.CancellationException

/** Worker-facing production adapter. Every retry reuses the frozen manifest and stable request id. */
class CloudRemoteDriveApi(
    private val client: CloudDriveClient,
    private val consentPolicyVersion: String,
) : RemoteDriveApi {
    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): RemoteDraftRef = translate {
        require(clientRequestId == draft.clientRequestId)
        if (!draft.hasPublishableRecordedRoute()) {
            throw CloudDriveException(CloudFailureKind.VALIDATION, "INSUFFICIENT_RECORDED_ROUTE", false)
        }
        val assets = draft.assetUploads().map(AssetUploadCommand::manifest)
        val drive = client.createDrive(
            CreateDriveInput(
                clientRequestId = clientRequestId,
                title = draft.title,
                summary = draft.description,
                distanceMeters = draft.distanceMeters.coerceIn(0, 2_000_000),
                durationSeconds = draft.durationSeconds.coerceIn(0, 259_200),
                highlightCount = draft.highlightCount.coerceIn(0, 100),
                consentPolicyVersion = consentPolicyVersion,
                assets = assets,
            ),
        ).drive
        RemoteDraftRef(drive.id, drive.version)
    }

    override suspend fun uploadAssets(remoteDriveId: String, commands: List<AssetUploadCommand>) = translate {
        if (commands.size != AssetKind.entries.size || commands.map { it.kind }.toSet() != AssetKind.entries.toSet()) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "INCOMPLETE_UPLOAD_MANIFEST", false)
        }
        if (commands.any { it.kind == AssetKind.ROUTE && it.syntheticDemoContent }) {
            throw CloudDriveException(CloudFailureKind.VALIDATION, "SYNTHETIC_ROUTE_NOT_PUBLISHABLE", false)
        }
        commands.forEach { command ->
            val expectedKey = "${command.clientRequestId}:${command.assetId}:${command.checksumSha256Hex}"
            if (command.idempotencyKey != expectedKey) {
                throw CloudDriveException(CloudFailureKind.CONTRACT, "INVALID_UPLOAD_IDEMPOTENCY", false)
            }
        }
        val manifests = commands.map(AssetUploadCommand::manifest)
        var lastExpiry: CloudDriveException? = null
        repeat(MAX_UPLOAD_SESSION_ATTEMPTS) { attempt ->
            try {
                val session = client.refreshUploads(remoteDriveId, manifests)
                commands.forEach { command ->
                    val instruction = session.uploads.singleOrNull { it.kind == command.kind }
                        ?: throw CloudDriveException(CloudFailureKind.CONTRACT, "MISSING_UPLOAD", false)
                    client.uploadAsset(instruction, command.manifest(), command.content)
                }
                return@translate
            } catch (failure: CloudDriveException) {
                if (failure.kind != CloudFailureKind.UPLOAD_EXPIRED) throw failure
                lastExpiry = failure
                if (attempt + 1 == MAX_UPLOAD_SESSION_ATTEMPTS) throw failure
            }
        }
        throw lastExpiry ?: CloudDriveException(CloudFailureKind.UPLOAD_EXPIRED, "UPLOAD_SESSION_EXPIRED", true)
    }

    override suspend fun publishDrive(
        remoteDriveId: String,
        expectedVersion: Int,
        clientRequestId: String,
    ): RemotePublishAck = translate {
        val published = client.publishDrive(remoteDriveId, expectedVersion)
        RemotePublishAck(published.version)
    }

    private suspend fun <T> translate(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CloudDriveException) {
        throw RemoteDriveException(
            code = when (failure.kind) {
                CloudFailureKind.AUTHENTICATION -> RemoteFailureCode.AUTHENTICATION
                CloudFailureKind.FORBIDDEN -> RemoteFailureCode.REJECTED
                CloudFailureKind.NOT_FOUND -> RemoteFailureCode.NOT_FOUND
                CloudFailureKind.VERSION_CONFLICT -> RemoteFailureCode.VERSION_CONFLICT
                CloudFailureKind.UPLOAD_INCOMPLETE -> RemoteFailureCode.UPLOAD_INCOMPLETE
                CloudFailureKind.UPLOAD_EXPIRED -> RemoteFailureCode.UPLOAD_EXPIRED
                CloudFailureKind.CONSENT_RENEWAL_REQUIRED -> RemoteFailureCode.CONSENT_RENEWAL_REQUIRED
                CloudFailureKind.IDEMPOTENCY_CONFLICT -> RemoteFailureCode.REJECTED
                CloudFailureKind.VALIDATION, CloudFailureKind.CONTRACT -> RemoteFailureCode.REJECTED
                CloudFailureKind.RETRYABLE -> RemoteFailureCode.SERVER_BUSY
                CloudFailureKind.UNKNOWN -> RemoteFailureCode.UNKNOWN
            },
            retryable = failure.retryable,
        )
    }
}

private const val MAX_UPLOAD_SESSION_ATTEMPTS = 2
private fun AssetUploadCommand.manifest() = AssetManifest(
    kind = kind,
    contentType = contentType,
    sizeBytes = content.size.toLong(),
    sha256Hex = checksumSha256Hex,
)
