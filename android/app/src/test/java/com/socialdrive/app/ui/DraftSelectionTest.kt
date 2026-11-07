package com.socialdrive.app.ui

import com.socialdrive.app.data.DraftSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftSelectionTest {
    @Test
    fun `사용자가 고른 이전 초안을 유지한다`() {
        val latest = draft("latest")
        val previous = draft("previous")

        assertEquals(previous, selectVisibleDraft(listOf(latest, previous), previous.id))
    }

    @Test
    fun `선택 기록이 사라지면 최신 초안으로 안전하게 복구한다`() {
        val latest = draft("latest")

        assertEquals(latest, selectVisibleDraft(listOf(latest), "missing"))
        assertNull(selectVisibleDraft(emptyList(), "missing"))
    }

    @Test
    fun `초안 저장은 순차 처리하며 대기 중에는 최신 입력만 유지한다`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val latestPersisted = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val writer = ConflatedDraftCopyWriter(backgroundScope) { update ->
            if (persisted.isEmpty()) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            persisted += update.title
            if (update.title == "최신 입력") latestPersisted.complete(Unit)
        }

        assertTrue(writer.submit(DraftCopyUpdate("draft-1", "첫 입력", "")))
        firstStarted.await()
        assertTrue(writer.submit(DraftCopyUpdate("draft-1", "중간 입력", "")))
        assertTrue(writer.submit(DraftCopyUpdate("draft-1", "최신 입력", "")))
        releaseFirst.complete(Unit)
        latestPersisted.await()

        assertEquals(listOf("첫 입력", "최신 입력"), persisted)
    }

    @Test
    fun `서로 다른 초안의 대기 중 편집은 각각 보존한다`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val allPersisted = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Pair<String, String>>()
        val writer = ConflatedDraftCopyWriter(backgroundScope) { update ->
            if (persisted.isEmpty()) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            persisted += update.draftId to update.title
            if (persisted.size == 3) allPersisted.complete(Unit)
        }

        writer.submit(DraftCopyUpdate("draft-1", "첫 저장", ""))
        firstStarted.await()
        writer.submit(DraftCopyUpdate("draft-1", "첫 초안 최신", ""))
        writer.submit(DraftCopyUpdate("draft-2", "둘째 초안 최신", ""))
        releaseFirst.complete(Unit)
        allPersisted.await()

        assertEquals(
            listOf(
                "draft-1" to "첫 저장",
                "draft-1" to "첫 초안 최신",
                "draft-2" to "둘째 초안 최신",
            ),
            persisted,
        )
    }

    @Test
    fun `개별 저장 실패 뒤에도 다음 최신 편집을 계속 저장한다`() = runTest {
        val latestPersisted = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        val writer = ConflatedDraftCopyWriter(backgroundScope) { update ->
            attempts += update.title
            if (update.title == "실패 입력") error("simulated Room failure")
            latestPersisted.complete(Unit)
        }

        writer.submit(DraftCopyUpdate("draft-1", "실패 입력", ""))
        writer.submit(DraftCopyUpdate("draft-2", "복구 입력", ""))
        latestPersisted.await()

        assertEquals(listOf("실패 입력", "복구 입력"), attempts)
    }

    @Test
    fun `게시 전 저장 장벽은 마지막 편집 완료까지 기다린다`() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writer = ConflatedDraftCopyWriter(backgroundScope) {
            writeStarted.complete(Unit)
            releaseWrite.await()
        }

        writer.submit(DraftCopyUpdate("draft-1", "게시할 최신 제목", ""))
        writeStarted.await()
        val flush = async { writer.awaitIdle() }
        testScheduler.runCurrent()
        assertEquals(false, flush.isCompleted)

        releaseWrite.complete(Unit)
        flush.await()
        assertEquals(true, flush.isCompleted)
    }

    private fun draft(id: String) = DraftSnapshot(
        id = id,
        recordingId = "recording-$id",
        clientRequestId = "request-$id",
        title = id,
        description = "기기 초안",
        coverPreset = "route-memory-demo-coast",
        originalContentRef = "local-recording://$id",
        state = "DRAFT",
    )
}
