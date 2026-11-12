package com.socialdrive.app.ui

import com.socialdrive.app.data.fake.FakeDriveRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoryStoryPresentationTest {
    private val repository = FakeDriveRepository()
    private val coast = requireNotNull(repository.drive("demo-coast"))

    @Test
    fun recordingDraftPublishAndDetailKeepOneStoryIdentityRouteAndCount() {
        val recording = MemoryStoryPresentation.browsing(coast)
            .beginRecording(coast)
            .recordingProgress(2)
        val recordingView = recording.present(coast)

        assertEquals("demo-coast", recordingView.id)
        assertEquals(coast.route, recordingView.route)
        assertEquals(2, recordingView.highlights)
        assertNotEquals(coast.highlights, recordingView.highlights)

        val draftReady = recording.finishDraft(recordingView.highlights)
        val publishView = draftReady.present(coast)
        val detailAfterBack = draftReady.present(coast)

        assertEquals(recordingView.id, publishView.id)
        assertEquals(recordingView.route, publishView.route)
        assertEquals(recordingView.highlights, publishView.highlights)
        assertEquals(publishView.id, detailAfterBack.id)
        assertEquals(publishView.route, detailAfterBack.route)
        assertEquals(publishView.highlights, detailAfterBack.highlights)
    }

    @Test
    fun mismatchedRepositoryIdentityReturnsUnmodifiedBaseDuringStateFlowRace() {
        val mountain = requireNotNull(repository.drive("demo-mountain"))
        val presented = MemoryStoryPresentation.browsing(coast)
            .recordingProgress(99)
            .present(mountain)

        assertEquals(mountain, presented)
    }
}
