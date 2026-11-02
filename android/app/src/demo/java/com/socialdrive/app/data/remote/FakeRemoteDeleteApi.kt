package com.socialdrive.app.data.remote

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Demo-only deterministic delete simulator. It never falls back from the production flavor. */
class FakeRemoteDeleteApi : RemoteDeleteApi {
    private val keys = ConcurrentHashMap<String, String>()

    override suspend fun deletePublishedDrive(remoteDriveId: String, stableKeyHash: String): RemoteDeleteConfirmed {
        currentCoroutineContext().ensureActive()
        yield()
        require(stableKeyHash.matches(Regex("[a-f0-9]{64}")))
        val existing = keys.putIfAbsent(remoteDriveId, stableKeyHash)
        if (existing != null && existing != stableKeyHash) {
            throw RemoteDeleteException(com.socialdrive.app.domain.DeleteFailureCode.VERSION_CONFLICT)
        }
        return RemoteDeleteConfirmed
    }
}
