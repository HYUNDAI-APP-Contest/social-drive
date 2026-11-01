package com.socialdrive.app

import android.os.SystemClock
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit

internal object TestWorkManagerLifecycle {
    /** Cancel pending work, observe an empty active set, prune, then close the test DB. */
    fun close(manager: WorkManager) {
        manager.cancelAllWork().result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val active = WorkQuery.Builder.fromStates(
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
        ).build()
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        var remaining = manager.getWorkInfos(active).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        while (remaining.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MILLIS)
            remaining = manager.getWorkInfos(active).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        check(remaining.isEmpty()) { "WorkManager still has active work during test teardown" }
        manager.pruneWork().result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private const val TIMEOUT_SECONDS = 5L
    private const val TIMEOUT_MILLIS = 5_000L
    private const val POLL_MILLIS = 10L
}
