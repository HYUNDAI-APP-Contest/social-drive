package com.socialdrive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PleosReadinessParserTest {
    @Test fun shortAndFullComponentsMapToOneVisibleGeneration() {
        val short = PleosReadinessParser.observe("42\n", fixture(), "42\n")
        val full = PleosReadinessParser.observe("42", fixture(fullComponents = true), "42")
        assertEquals(listOf("10", "20", "30"), requireNotNull(short.snapshot).taskIds)
        assertEquals(short.snapshot, full.snapshot)
    }

    @Test fun duplicateInvisibleMissingAndPidFlipFailClosed() {
        val duplicate = fixture() + task("21", "ai.umos.drivingview/.DrivingViewActivity")
        assertTrue(PleosReadinessParser.observe("42", duplicate, "42").reason.startsWith("task_cardinality="))
        assertEquals("home_not_visible_resumed", PleosReadinessParser.observe("42", fixture(homeVisible = false), "42").reason)
        assertEquals(
            "default_task_not_visible_active",
            PleosReadinessParser.observe("42", fixture(drivingVisible = false), "42").reason,
        )
        assertEquals(
            "default_task_not_visible_active",
            PleosReadinessParser.observe("42", fixture(naviState = "STOPPED"), "42").reason,
        )
        assertTrue(PleosReadinessParser.observe("42", fixture(includeNavi = false), "42").reason.startsWith("task_cardinality="))
        assertEquals("home_generation_changed", PleosReadinessParser.observe("42", fixture(), "43").reason)
        assertEquals("home_pid_missing_or_multiple", PleosReadinessParser.observe("42 43", fixture(), "43").reason)
    }

    @Test fun stableTrackerRequiresTwoCoherentObservationsAndResetsOnNullOrChange() {
        val a = PleosReadinessParser.observe("42", fixture(), "42")
        val b = PleosReadinessParser.observe("43", fixture(), "43")
        val tracker = StableOrganizerGeneration()
        assertFalse(tracker.record(a))
        assertTrue(tracker.record(a))
        assertFalse(tracker.record(b))
        assertFalse(tracker.record(PleosObservation(null, "missing")))
        assertFalse(tracker.record(a))
        assertTrue(tracker.record(a))
    }

    @Test fun fatalGuardIgnoresOldBaselineAndRejectsNewRelevantFatalOrDuplicate() {
        val oldGuard = PleosFatalLogGuard(100.0, setOf("ai.umos.homescreen"))
        assertNull(oldGuard.inspect(fatalLog("99.000"), setOf("42")))

        val fatalGuard = PleosFatalLogGuard(100.0, setOf("ai.umos.homescreen"))
        val fatal = fatalGuard.inspect(fatalLog("101.000"), setOf("42"))
        assertNotNull(fatal)
        assertEquals("fatal", fatal?.kind)

        val duplicateGuard = PleosFatalLogGuard(100.0, setOf("ai.umos.homescreen"))
        val duplicate = "101.000 42 42 E HOME_SCREEN: Listener for taskId=20 already exists\n"
        val duplicateEvidence = duplicateGuard.inspect(duplicate, setOf("42"))
        assertNotNull(duplicateEvidence)
        assertEquals("duplicate_listener", duplicateEvidence?.kind)
        assertNull(duplicateGuard.inspect(duplicate, setOf("42")))

        val benign = "101.000 42 42 D HOME_SCREEN: Task with ID 20 not found\n"
        assertFalse(PleosShellDiagnostics.logcatCommandFailed(benign))
        assertNull(PleosFatalLogGuard(100.0, setOf("ai.umos.homescreen")).inspect(benign, setOf("42")))
        assertTrue(PleosShellDiagnostics.logcatCommandFailed("logcat: unknown option -Z"))
    }

    private fun fixture(
        fullComponents: Boolean = false,
        homeVisible: Boolean = true,
        drivingVisible: Boolean = true,
        naviState: String = "RESUMED",
        includeNavi: Boolean = true,
    ): String = buildString {
        append(task("10", if (fullComponents) "ai.umos.homescreen/ai.umos.homescreen.HomeActivity" else "ai.umos.homescreen/.HomeActivity", homeVisible))
        append(task("20", if (fullComponents) "ai.umos.drivingview/ai.umos.drivingview.DrivingViewActivity" else "ai.umos.drivingview/.DrivingViewActivity", drivingVisible))
        if (includeNavi) append(task("30", if (fullComponents) "ai.umos.maps.android.navigation.app/ai.umos.maps.android.navigation.app.MainActivity" else "ai.umos.maps.android.navigation.app/.MainActivity", state = naviState))
    }

    private fun task(
        id: String,
        component: String,
        visible: Boolean = true,
        state: String = if (visible) "RESUMED" else "STOPPED",
    ): String = """
        * Task{abc #$id type=standard}
          * Hist #0: ActivityRecord{def u10 $component t$id}
            mVisibleRequested=$visible mVisible=$visible mClientVisible=$visible
            state=$state stopped=${state == "STOPPED"}
    """.trimIndent() + "\n"

    private fun fatalLog(epoch: String): String = """
        $epoch 42 42 E AndroidRuntime: FATAL EXCEPTION: main
        $epoch 42 42 E AndroidRuntime: Process: ai.umos.homescreen, PID: 42
        $epoch 42 42 E AndroidRuntime: java.lang.IllegalArgumentException: boom
    """.trimIndent()
}
