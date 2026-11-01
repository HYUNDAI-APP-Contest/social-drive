package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.util.concurrent.ConcurrentHashMap

enum class RemoteFailureCode { OFFLINE, TIMEOUT, SERVER_BUSY, REJECTED, UNKNOWN }

class RemoteDriveException(
    val code: RemoteFailureCode,
    val retryable: Boolean,
) : RuntimeException(code.name)

/** Presigned upload URLs and credentials stay inside this boundary and never enter Room. */
interface RemoteDriveApi {
    suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String
    suspend fun uploadAssets(remoteDriveId: String, draft: DraftSnapshot)
    suspend fun publishDrive(remoteDriveId: String, clientRequestId: String)
}

class FakeRemoteDriveApi : RemoteDriveApi {
    private val drives = ConcurrentHashMap<String, String>()
    private val uploaded = ConcurrentHashMap.newKeySet<String>()
    private val published = ConcurrentHashMap.newKeySet<String>()

    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String =
        drives.computeIfAbsent(clientRequestId) { "demo-${draft.id}" }

    override suspend fun uploadAssets(remoteDriveId: String, draft: DraftSnapshot) {
        uploaded += remoteDriveId
    }

    override suspend fun publishDrive(remoteDriveId: String, clientRequestId: String) {
        published += remoteDriveId
    }
}
