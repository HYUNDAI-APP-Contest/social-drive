package com.socialdrive.app.domain

const val MAX_CONFLICT_REPLAYS = 2
const val MAX_CONFLICT_TITLE_CODE_POINTS = 40
const val MAX_CONFLICT_SUMMARY_CODE_POINTS = 160

enum class ConflictUiPhase {
    Hidden,
    Detected,
    Fetching,
    SnapshotReady,
    FetchFailed,
    KeptLocal,
    ReplayQueued,
    Resolved,
    Unreviewable,
}

data class ConflictReviewUiState(
    val phase: ConflictUiPhase,
    val latestVersion: Int? = null,
    val latestTitle: String? = null,
    val latestSummary: String? = null,
    val replayCount: Int = 0,
) {
    val isBusy: Boolean get() = phase == ConflictUiPhase.Fetching
    val canFetch: Boolean get() = phase == ConflictUiPhase.Detected || phase == ConflictUiPhase.FetchFailed
    val canAdopt: Boolean get() = phase == ConflictUiPhase.SnapshotReady && replayCount < MAX_CONFLICT_REPLAYS
}

data class OwnedDriveConflictSnapshot(
    val remoteDriveId: String,
    val version: Int,
    val status: String,
    val title: String,
    val summary: String,
)

sealed interface ConflictReviewDecision {
    data object Applied : ConflictReviewDecision
    data object Coalesced : ConflictReviewDecision
    data object NeedsReview : ConflictReviewDecision
    data object NotParked : ConflictReviewDecision
    data object IdentityMismatch : ConflictReviewDecision
    data object ReplayLimitReached : ConflictReviewDecision
    data object SnapshotNotReplayable : ConflictReviewDecision
    data object Missing : ConflictReviewDecision
}

object ConflictReviewPolicy {
    const val SAFE_FETCH_FAILURE = "최신 버전을 확인하지 못했어요. 로컬 초안은 안전하게 보관 중입니다."

    fun mayReplay(attemptedVersion: Int, snapshot: OwnedDriveConflictSnapshot, replayCount: Int): Boolean =
        snapshot.status == "DRAFT" && snapshot.version > attemptedVersion && replayCount < MAX_CONFLICT_REPLAYS

    fun bounded(snapshot: OwnedDriveConflictSnapshot): OwnedDriveConflictSnapshot? {
        if (!snapshot.remoteDriveId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) return null
        if (snapshot.version !in 1..1_000_000 || snapshot.status !in setOf("DRAFT", "PUBLISHED")) return null
        if (snapshot.title.codePointCount(0, snapshot.title.length) > MAX_CONFLICT_TITLE_CODE_POINTS) return null
        if (snapshot.summary.codePointCount(0, snapshot.summary.length) > MAX_CONFLICT_SUMMARY_CODE_POINTS) return null
        return snapshot
    }
}

data class ConflictLeaseSnapshot(
    val draftId: String,
    val clientRequestId: String,
    val remoteDriveId: String,
    val attemptedVersion: Int,
    val state: String,
    val fetchGeneration: Int,
    val replayCount: Int,
)

data class ConflictFetchLease(
    val draftId: String,
    val clientRequestId: String,
    val remoteDriveId: String,
    val attemptedVersion: Int,
    val fetchGeneration: Int,
)

/** Pure mirror of the CAS predicates enforced by Room; used by JVM tests and fake stores. */
object ConflictReviewTransitionContract {
    fun claimFetch(current: ConflictLeaseSnapshot): ConflictFetchLease? = current
        .takeIf { it.state == "DETECTED" || it.state == "FETCH_FAILED" }
        ?.let {
            ConflictFetchLease(
                it.draftId,
                it.clientRequestId,
                it.remoteDriveId,
                it.attemptedVersion,
                it.fetchGeneration + 1,
            )
        }

    fun acceptsFetchResult(current: ConflictLeaseSnapshot, lease: ConflictFetchLease): Boolean =
        current.state == "FETCHING" && current.draftId == lease.draftId &&
            current.clientRequestId == lease.clientRequestId && current.remoteDriveId == lease.remoteDriveId &&
            current.attemptedVersion == lease.attemptedVersion && current.fetchGeneration == lease.fetchGeneration

    fun acceptsInterruptedNormalization(current: ConflictLeaseSnapshot, lease: ConflictFetchLease): Boolean =
        acceptsFetchResult(current, lease)

    fun acceptsReplay(
        current: ConflictLeaseSnapshot,
        localClientRequestId: String,
        localRemoteDriveId: String,
        localVersion: Int,
        latest: OwnedDriveConflictSnapshot,
    ): Boolean = current.state == "SNAPSHOT_READY" &&
        current.clientRequestId == localClientRequestId && current.remoteDriveId == localRemoteDriveId &&
        current.attemptedVersion == localVersion &&
        ConflictReviewPolicy.mayReplay(current.attemptedVersion, latest, current.replayCount)
}
