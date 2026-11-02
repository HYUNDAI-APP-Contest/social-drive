package com.socialdrive.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictReviewTest {
    private val detected = ConflictLeaseSnapshot("draft-1", "request-1", "drive-remote-1", 4, "DETECTED", 0, 0)
    private val latest = OwnedDriveConflictSnapshot("drive-remote-1", 5, "DRAFT", "새 제목", "공개 요약")

    @Test fun explicitTapClaimsExactlyOneGeneration() {
        val lease = requireNotNull(ConflictReviewTransitionContract.claimFetch(detected))
        assertEquals(1, lease.fetchGeneration)
        assertNull(ConflictReviewTransitionContract.claimFetch(detected.copy(state = "FETCHING")))
    }

    @Test fun staleEarlierFetchCannotStoreAfterNextTap() {
        val first = requireNotNull(ConflictReviewTransitionContract.claimFetch(detected))
        val second = requireNotNull(ConflictReviewTransitionContract.claimFetch(detected.copy(state = "FETCH_FAILED", fetchGeneration = 1)))
        val current = detected.copy(state = "FETCHING", fetchGeneration = 2)
        assertFalse(ConflictReviewTransitionContract.acceptsFetchResult(current, first))
        assertTrue(ConflictReviewTransitionContract.acceptsFetchResult(current, second))
    }

    @Test fun cancellationNormalizesOnlyItsOwnFetchingGeneration() {
        val first = requireNotNull(ConflictReviewTransitionContract.claimFetch(detected))
        val fetching = detected.copy(state = "FETCHING", fetchGeneration = 1)
        assertTrue(ConflictReviewTransitionContract.acceptsInterruptedNormalization(fetching, first))
        assertFalse(ConflictReviewTransitionContract.acceptsInterruptedNormalization(fetching.copy(fetchGeneration = 2), first))
    }

    @Test fun localLifetimeIdentityMustMatchForReplay() {
        val ready = detected.copy(state = "SNAPSHOT_READY")
        assertTrue(ConflictReviewTransitionContract.acceptsReplay(ready, "request-1", "drive-remote-1", 4, latest))
        assertFalse(ConflictReviewTransitionContract.acceptsReplay(ready, "request-other", "drive-remote-1", 4, latest))
        assertFalse(ConflictReviewTransitionContract.acceptsReplay(ready, "request-1", "drive-other-1", 4, latest))
    }

    @Test fun replayRequiresNewerDraftSnapshot() {
        val ready = detected.copy(state = "SNAPSHOT_READY")
        assertFalse(ConflictReviewTransitionContract.acceptsReplay(ready, "request-1", "drive-remote-1", 4, latest.copy(version = 4)))
        assertFalse(ConflictReviewTransitionContract.acceptsReplay(ready, "request-1", "drive-remote-1", 4, latest.copy(status = "PUBLISHED")))
    }

    @Test fun replayLimitIsLifetimeDurable() {
        val ready = detected.copy(state = "SNAPSHOT_READY", replayCount = MAX_CONFLICT_REPLAYS)
        assertFalse(ConflictReviewTransitionContract.acceptsReplay(ready, "request-1", "drive-remote-1", 4, latest))
        assertEquals(2, ready.copy(state = "DETECTED").replayCount)
        assertEquals(2, ready.copy(state = "KEPT_LOCAL").replayCount)
    }

    @Test fun boundedSnapshotRejectsOversizedOrInvalidValues() {
        assertNotNull(ConflictReviewPolicy.bounded(latest))
        assertNull(ConflictReviewPolicy.bounded(latest.copy(title = "가".repeat(41))))
        assertNull(ConflictReviewPolicy.bounded(latest.copy(summary = "나".repeat(161))))
        assertNull(ConflictReviewPolicy.bounded(latest.copy(remoteDriveId = "bad")))
    }

    @Test fun fetchFailureCopyDoesNotRevealExistence() {
        assertEquals(
            "최신 버전을 확인하지 못했어요. 로컬 초안은 안전하게 보관 중입니다.",
            ConflictReviewPolicy.SAFE_FETCH_FAILURE,
        )
    }

    @Test fun fetchingUiCoalescesActions() {
        val state = ConflictReviewUiState(ConflictUiPhase.Fetching)
        assertTrue(state.isBusy)
        assertFalse(state.canFetch)
        assertFalse(state.canAdopt)
    }

    @Test fun onlyReadyUiMayAdopt() {
        assertTrue(ConflictReviewUiState(ConflictUiPhase.SnapshotReady, replayCount = 1).canAdopt)
        assertFalse(ConflictReviewUiState(ConflictUiPhase.Detected).canAdopt)
        assertFalse(ConflictReviewUiState(ConflictUiPhase.SnapshotReady, replayCount = 2).canAdopt)
    }
}
