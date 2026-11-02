package com.socialdrive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PleosReadinessParserTest {
    @Test fun currentUserDerivesFromPublicProcessUidRange() {
        assertEquals(0, AndroidUserId.fromUid(10_142))
        assertEquals(10, AndroidUserId.fromUid(1_010_142))
    }

    @Test fun shortAndFullComponentsMapToOneVisibleGeneration() {
        val short = PleosReadinessParser.observe("42\n", fixture(), "42\n", 10)
        val full = PleosReadinessParser.observe("42", fixture(fullComponents = true), "42", 10)
        assertEquals(listOf("10", "20", "30"), requireNotNull(short.snapshot).taskIds)
        assertEquals(short.snapshot, full.snapshot)
        val singleSpaceHist = PleosReadinessParser.observe("42", fixture().replace("Hist  #", "Hist #"), "42", 10)
        assertEquals(short.snapshot, singleSpaceHist.snapshot)
        val repeatedSections = PleosReadinessParser.observe("42", fixture() + fixture(), "42", 10)
        assertEquals(listOf("10", "20", "30"), requireNotNull(repeatedSections.snapshot).taskIds)
        val capturedStyle = PleosReadinessParser.observe("42", capturedStyleDump(), "42", 10)
        assertEquals(listOf("1000299", "1000318", "1000304"), requireNotNull(capturedStyle.snapshot).taskIds)
    }

    @Test fun duplicateMissingWrongUserHomeStateAndPidFlipFailClosed() {
        val duplicate = fixture() + task("21", "ai.umos.drivingview/.DrivingViewActivity")
        assertTrue(PleosReadinessParser.observe("42", duplicate, "42").reason.startsWith("task_cardinality="))
        assertEquals("home_not_visible_resumed", PleosReadinessParser.observe("42", fixture(homeVisible = false), "42").reason)
        val homeNotResumed = fixture().replaceFirst("state=RESUMED", "state=STARTED")
        assertEquals("home_not_visible_resumed", PleosReadinessParser.observe("42", homeNotResumed, "42").reason)
        assertTrue(PleosReadinessParser.observe("42", fixture(includeNavi = false), "42").reason.startsWith("task_cardinality="))
        assertTrue(PleosReadinessParser.observe("42", fixture(drivingUserId = 0), "42", 10).reason.startsWith("task_cardinality="))
        assertEquals("home_generation_changed", PleosReadinessParser.observe("42", fixture(), "43").reason)
        assertEquals("home_pid_missing_or_multiple", PleosReadinessParser.observe("42 43", fixture(), "43").reason)
        val inconsistent = fixture() + task("20", "ai.umos.drivingview/.DrivingViewActivity", state = "STOPPED")
        assertEquals("task_representation_inconsistent", PleosReadinessParser.observe("42", inconsistent, "42", 10).reason)
        val contextBleed = fixture().replace(
            "ai.umos.drivingview/.DrivingViewActivity t20",
            "ai.umos.drivingview/.DrivingViewActivity t99",
        )
        assertEquals("task_record_context_mismatch", PleosReadinessParser.observe("42", contextBleed, "42", 10).reason)
        val otherUserDuplicate = fixture() + task("21", "ai.umos.drivingview/.DrivingViewActivity", userId = 0)
        assertNotNull(PleosReadinessParser.observe("42", otherUserDuplicate, "42", 10).snapshot)
    }

    @Test fun initializingDefaultTasksAreAcceptedOnlyAfterStableTaskIds() {
        val initializing = PleosReadinessParser.observe(
            "42",
            fixture(drivingVisible = false, drivingState = "INITIALIZING", naviState = "STOPPED"),
            "42",
            10,
        )
        assertNotNull(initializing.snapshot)
        val lifecycleMissing = PleosReadinessParser.observe(
            "42",
            fixture(drivingVisible = false).replace("state=STOPPED stopped=true", "initializationPending=true"),
            "42",
            10,
        )
        assertNotNull(lifecycleMissing.snapshot)

        val tracker = StableOrganizerGeneration()
        assertFalse(tracker.record(initializing))
        assertTrue(tracker.record(initializing))
        val changedDrivingId = PleosReadinessParser.observe(
            "42",
            fixture(drivingVisible = false, drivingState = "INITIALIZING", drivingId = "21"),
            "42",
            10,
        )
        assertFalse(tracker.record(changedDrivingId))
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

    @Test fun closeAuditAlwaysCleansAndPrioritizesPostFatalEvidence() {
        var cleanCloseCalled = false
        var cleanPostCalled = false
        PleosCloseAudit.run(
            pre = {},
            close = { cleanCloseCalled = true },
            post = { cleanPostCalled = true },
        )
        assertTrue(cleanCloseCalled)
        assertTrue(cleanPostCalled)
        assertEquals("99", PleosHomePid.singleOrNull("99\n"))
        assertNull(PleosHomePid.singleOrNull(""))

        val preLogFailure = IllegalStateException("PLEOS readiness logcat_unavailable")
        var closeAfterPreFailure = false
        var postAfterPreFailure = false
        val preFailure = captureFailure {
            PleosCloseAudit.run(
                pre = { throw preLogFailure },
                close = { closeAfterPreFailure = true },
                post = { postAfterPreFailure = true },
            )
        }
        assertSame(preLogFailure, preFailure)
        assertTrue(closeAfterPreFailure)
        assertTrue(postAfterPreFailure)

        val notDestroyed = IllegalStateException("Compose host did not reach DESTROYED")
        var postAfterCloseFailure = false
        val closeFailure = captureFailure {
            PleosCloseAudit.run(
                pre = {},
                close = { throw notDestroyed },
                post = { postAfterCloseFailure = true },
            )
        }
        assertSame(notDestroyed, closeFailure)
        assertTrue(postAfterCloseFailure)

        val fatal = IllegalStateException("PLEOS readiness fatal AndroidRuntime pid=99 hash=abc")
        val fatalPrimary = captureFailure {
            PleosCloseAudit.run(
                pre = {},
                close = { throw notDestroyed },
                post = { throw fatal },
            )
        }
        assertSame(fatal, fatalPrimary)
        assertTrue(fatalPrimary.suppressed.contains(notDestroyed))

        val duplicate = IllegalStateException("PLEOS readiness duplicate_listener task_organizer pid=99 hash=def")
        val duplicatePrimary = captureFailure {
            PleosCloseAudit.run(
                pre = { throw preLogFailure },
                close = { throw notDestroyed },
                post = { throw duplicate },
            )
        }
        assertSame(duplicate, duplicatePrimary)
        assertTrue(duplicatePrimary.suppressed.contains(notDestroyed))
        assertTrue(duplicatePrimary.suppressed.contains(preLogFailure))

        val logcatFailure = IllegalStateException("PLEOS readiness logcat_unavailable")
        assertSame(
            logcatFailure,
            captureFailure { PleosCloseAudit.run(pre = {}, close = {}, post = { throw logcatFailure }) },
        )

        // A temporarily empty post-close pid is not itself a failure; package-scoped logs remain audited.
        PleosCloseAudit.run(pre = {}, close = {}, post = {})
        val postGuard = PleosFatalLogGuard(100.0, setOf("ai.umos.homescreen"))
        assertNotNull(postGuard.inspect(fatalLog("101.000", pid = "99"), setOf("42", "99")))
    }

    @Test fun readinessSessionUsesOrganizerChecksOnlyBeforeChildLaunch() {
        val guard = PleosFatalLogGuard(100.0, setOf("ai.umos.homescreen", "com.socialdrive.app"))
        val generationChanged = IllegalStateException("PLEOS readiness home_generation_changed")
        val changedBeforeLaunch = PleosReadinessSession(
            guard = guard,
            prelaunchHomePid = "42",
            prelaunchCheck = { _, _ -> throw generationChanged },
            currentHomePid = { "99" },
            logCheck = { _, _ -> },
        )
        assertSame(generationChanged, captureFailure(changedBeforeLaunch::assertPrelaunchStable))

        var currentPid: String? = "99"
        var prelaunchCalls = 0
        val auditedPidSets = mutableListOf<Set<String>>()
        val session = PleosReadinessSession(
            guard = guard,
            prelaunchHomePid = "42",
            prelaunchCheck = { _, expected ->
                prelaunchCalls += 1
                assertEquals("42", expected)
            },
            currentHomePid = { currentPid },
            logCheck = { _, pids -> auditedPidSets += pids.toSet() },
        )
        session.assertPrelaunchStable()
        assertEquals(1, prelaunchCalls)
        session.enterChildActive()
        session.assertChildLogHealthy()
        assertTrue(auditedPidSets.single().containsAll(setOf("42", "99")))

        // Rehome/task removal is not an organizer failure after launch; an empty pid still audits logs.
        currentPid = null
        session.assertChildLogHealthy()
        assertEquals(2, auditedPidSets.size)
        assertEquals(1, prelaunchCalls)
        assertTrue(runCatching(session::assertPrelaunchStable).isFailure)

        val bodyFailure = AssertionError("UI assertion")
        val cleanBodyFailure = captureFailure { session.withChildLogCheck { throw bodyFailure } }
        assertSame(bodyFailure, cleanBodyFailure)

        var logCalls = 0
        val fatal = IllegalStateException("PLEOS readiness fatal AndroidRuntime pid=99 hash=abc")
        val fatalSession = PleosReadinessSession(
            guard = guard,
            prelaunchHomePid = "42",
            prelaunchCheck = { _, _ -> },
            currentHomePid = { "99" },
            logCheck = { _, _ ->
                logCalls += 1
                if (logCalls == 2) throw fatal
            },
        )
        fatalSession.assertPrelaunchStable()
        fatalSession.enterChildActive()
        val fatalPrimary = captureFailure { fatalSession.withChildLogCheck { throw bodyFailure } }
        assertSame(fatal, fatalPrimary)
        assertTrue(fatalPrimary.suppressed.contains(bodyFailure))
    }

    private fun fixture(
        fullComponents: Boolean = false,
        homeVisible: Boolean = true,
        drivingVisible: Boolean = true,
        drivingState: String? = null,
        drivingId: String = "20",
        drivingUserId: Int = 10,
        naviState: String = "RESUMED",
        includeNavi: Boolean = true,
    ): String = buildString {
        append(task("10", if (fullComponents) "ai.umos.homescreen/ai.umos.homescreen.HomeActivity" else "ai.umos.homescreen/.HomeActivity", homeVisible))
        append(
            task(
                drivingId,
                if (fullComponents) "ai.umos.drivingview/ai.umos.drivingview.DrivingViewActivity" else "ai.umos.drivingview/.DrivingViewActivity",
                drivingVisible,
                state = drivingState ?: if (drivingVisible) "RESUMED" else "STOPPED",
                userId = drivingUserId,
            ),
        )
        if (includeNavi) append(task("30", if (fullComponents) "ai.umos.maps.android.navigation.app/ai.umos.maps.android.navigation.app.MainActivity" else "ai.umos.maps.android.navigation.app/.MainActivity", state = naviState))
    }

    private fun task(
        id: String,
        component: String,
        visible: Boolean = true,
        state: String = if (visible) "RESUMED" else "STOPPED",
        userId: Int = 10,
    ): String = """
        * Task{abc #$id type=standard}
          * Hist  #0: ActivityRecord{def u$userId $component t$id}
            rootOfTask=true task=Task{c9 #$id type=standard}
            topDisplayFocusedRootTask=Task{focus #$id type=standard}
            mVisibleRequested=$visible mVisible=$visible mClientVisible=$visible
            state=$state stopped=${state == "STOPPED"}
    """.trimIndent() + "\n"

    private fun fatalLog(epoch: String, pid: String = "42"): String = """
        $epoch $pid $pid E AndroidRuntime: FATAL EXCEPTION: main
        $epoch $pid $pid E AndroidRuntime: Process: ai.umos.homescreen, PID: $pid
        $epoch $pid $pid E AndroidRuntime: java.lang.IllegalArgumentException: boom
    """.trimIndent()

    private fun captureFailure(block: () -> Unit): Throwable =
        runCatching(block).exceptionOrNull() ?: error("Expected failure")

    private fun capturedStyleDump(): String = buildString {
        append(task("1000299", "ai.umos.homescreen/.HomeActivity"))
        append(task("1000318", "ai.umos.drivingview/.DrivingViewActivity"))
        append(task("1000304", "ai.umos.maps.android.navigation.app/.MainActivity"))
        appendLine("* Task{empty #1000300 type=organizer}")
        appendLine("Resumed: ActivityRecord{one u10 ai.umos.homescreen/.HomeActivity t1000299}")
        appendLine("mFocusedApp=ActivityRecord{two u10 ai.umos.drivingview/.DrivingViewActivity t1000318}")
        appendLine("topResumedActivity=ActivityRecord{three u10 ai.umos.maps.android.navigation.app/.MainActivity t1000304}")
    }
}
