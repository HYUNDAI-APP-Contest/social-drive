package com.socialdrive.app.data

import com.socialdrive.app.data.local.RecordingEntity

internal fun RecordingEntity?.recordedDurationSeconds(): Int {
    val recording = this ?: return 0
    val endedAt = recording.endedAtEpochMs ?: return 0
    return ((endedAt - recording.startedAtEpochMs).coerceAtLeast(0L) / 1_000L)
        .coerceAtMost(259_200L)
        .toInt()
}
