package com.socialdrive.app.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.socialdrive.app.domain.DriveSummary

internal enum class MemoryStoryPhase { Browsing, Recording, DraftReady }

/**
 * The single presentation identity carried across recording, draft, publish, and detail.
 * Only a demo preset id and a bounded count are saved; route points remain in the fake repository.
 */
internal data class MemoryStoryPresentation(
    val driveId: String,
    val highlightCount: Int,
    val phase: MemoryStoryPhase,
) {
    fun select(drive: DriveSummary) = copy(
        driveId = drive.id,
        highlightCount = drive.highlights,
        phase = MemoryStoryPhase.Browsing,
    )

    fun beginRecording(drive: DriveSummary) = copy(
        driveId = drive.id,
        highlightCount = 0,
        phase = MemoryStoryPhase.Recording,
    )

    fun recordingProgress(count: Int) = copy(
        highlightCount = count.coerceIn(0, MAX_PRESENTATION_HIGHLIGHTS),
        phase = MemoryStoryPhase.Recording,
    )

    fun finishDraft(count: Int) = copy(
        highlightCount = count.coerceIn(0, MAX_PRESENTATION_HIGHLIGHTS),
        phase = MemoryStoryPhase.DraftReady,
    )

    fun present(base: DriveSummary): DriveSummary {
        // Room and Compose state flows can publish on adjacent frames after a recording is saved.
        // Never project one story's transient state onto another story or crash during that frame.
        if (base.id != driveId) return base
        if (phase == MemoryStoryPhase.Browsing) return base
        val count = highlightCount.coerceIn(0, MAX_PRESENTATION_HIGHLIGHTS)
        return base.copy(
            highlights = count,
            moments = base.moments.take(count),
        )
    }

    companion object {
        private const val MAX_PRESENTATION_HIGHLIGHTS = 100

        fun browsing(drive: DriveSummary) = MemoryStoryPresentation(
            driveId = drive.id,
            highlightCount = drive.highlights,
            phase = MemoryStoryPhase.Browsing,
        )

        val Saver: Saver<MemoryStoryPresentation, Any> = listSaver(
            save = { listOf(it.driveId, it.highlightCount, it.phase.name) },
            restore = {
                val id = it.getOrNull(0) as? String ?: return@listSaver null
                val count = it.getOrNull(1) as? Int ?: return@listSaver null
                val phase = (it.getOrNull(2) as? String)
                    ?.let { value -> runCatching { MemoryStoryPhase.valueOf(value) }.getOrNull() }
                    ?: return@listSaver null
                MemoryStoryPresentation(id, count.coerceIn(0, MAX_PRESENTATION_HIGHLIGHTS), phase)
            },
        )
    }
}
