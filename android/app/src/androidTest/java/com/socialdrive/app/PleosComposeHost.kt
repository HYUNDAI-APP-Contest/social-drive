package com.socialdrive.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale

/** Manually owns the app Activity after the PLEOS task organizer reaches a stable generation. */
internal class PleosComposeHost {
    private var scenario: ActivityScenario<MainActivity>? = null
    private var readiness: PleosReadinessSession? = null

    fun launch() {
        check(scenario == null)
        val stableReadiness = PleosTaskReadiness.awaitStableGeneration()
        stableReadiness.assertPrelaunchStable()
        stableReadiness.enterChildActive()
        var launched: ActivityScenario<MainActivity>? = null
        try {
            launched = ActivityScenario.launch(MainActivity::class.java)
            requireNotNull(launched).moveToState(Lifecycle.State.RESUMED)
            check(requireNotNull(launched).state == Lifecycle.State.RESUMED) { "Compose host did not reach RESUMED" }
            requireNotNull(launched).onActivity { check(it.lifecycle.currentState == Lifecycle.State.RESUMED) }
            stableReadiness.assertChildLogHealthy()
            readiness = stableReadiness
            scenario = requireNotNull(launched)
        } catch (failure: Throwable) {
            val closeFailure = runCatching {
                launched?.let { active ->
                    active.close()
                    check(active.state == Lifecycle.State.DESTROYED) { "Compose host did not reach DESTROYED" }
                }
            }.exceptionOrNull()
            val postFailure = runCatching(stableReadiness::assertChildLogHealthy).exceptionOrNull()
            PleosFailureAudit.throwIfAny(failure, closeFailure, postFailure)
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        requireNotNull(readiness).withChildLogCheck {
            requireNotNull(scenario).onActivity { activity ->
                check(activity.lifecycle.currentState == Lifecycle.State.RESUMED) { "Compose host is not RESUMED" }
                activity.setContent(content = content)
                check(activity.lifecycle.currentState == Lifecycle.State.RESUMED) { "Compose host left RESUMED" }
            }
        }
    }

    fun close() {
        val active = scenario ?: return
        val stableReadiness = requireNotNull(readiness)
        try {
            PleosCloseAudit.run(
                pre = stableReadiness::assertChildLogHealthy,
                close = {
                    active.close()
                    check(active.state == Lifecycle.State.DESTROYED) { "Compose host did not reach DESTROYED" }
                },
                post = stableReadiness::assertChildLogHealthy,
            )
        } finally {
            scenario = null
            readiness = null
        }
    }
}

internal class PleosReadinessSession(
    private val guard: PleosFatalLogGuard,
    private val prelaunchHomePid: String,
    private val prelaunchCheck: (PleosFatalLogGuard, String) -> Unit = PleosTaskReadiness::assertStableGenerationHealthy,
    private val currentHomePid: () -> String? = PleosTaskReadiness::currentHomePidIfNumeric,
    private val logCheck: (PleosFatalLogGuard, Set<String>) -> Unit = PleosTaskReadiness::assertLogHealthy,
) {
    private var phase = PleosReadinessPhase.PrelaunchOrganizer
    private val relevantPids = mutableSetOf(prelaunchHomePid, android.os.Process.myPid().toString())

    fun assertPrelaunchStable() {
        check(phase == PleosReadinessPhase.PrelaunchOrganizer)
        prelaunchCheck(guard, prelaunchHomePid)
    }

    fun enterChildActive() {
        check(phase == PleosReadinessPhase.PrelaunchOrganizer)
        phase = PleosReadinessPhase.ChildActive
    }

    fun assertChildLogHealthy() {
        check(phase == PleosReadinessPhase.ChildActive)
        currentHomePid()?.let(relevantPids::add)
        logCheck(guard, relevantPids)
    }

    fun <T> withChildLogCheck(block: () -> T): T {
        val preFailure = runCatching(::assertChildLogHealthy).exceptionOrNull()
        val result = runCatching(block)
        val postFailure = runCatching(::assertChildLogHealthy).exceptionOrNull()
        PleosFailureAudit.throwIfAny(result.exceptionOrNull(), postFailure, preFailure)
        return result.getOrThrow()
    }
}

internal enum class PleosReadinessPhase { PrelaunchOrganizer, ChildActive }

internal object PleosCloseAudit {
    fun run(pre: () -> Unit, close: () -> Unit, post: () -> Unit) {
        val preFailure = runCatching(pre).exceptionOrNull()
        val closeFailure = runCatching(close).exceptionOrNull()
        val postFailure = runCatching(post).exceptionOrNull()
        PleosFailureAudit.throwIfAny(closeFailure, postFailure, preFailure)
    }
}

internal object PleosFailureAudit {
    fun throwIfAny(vararg failures: Throwable?) {
        val present = failures.filterNotNull()
        val primary = present.firstOrNull { it.isFatalOrDuplicate() } ?: present.firstOrNull() ?: return
        present.filterNot { it === primary }.forEach(primary::addSuppressed)
        throw primary
    }

    private fun Throwable.isFatalOrDuplicate(): Boolean = message.orEmpty().let { message ->
        message.contains("PLEOS readiness fatal") || message.contains("PLEOS readiness duplicate_listener")
    }
}

internal object PleosHomePid {
    fun singleOrNull(raw: String): String? = raw.trim().takeIf { it.matches(Regex("[0-9]+")) }
}

internal data class PleosOrganizerSnapshot(val homePid: String, val taskIds: List<String>)
internal data class PleosObservation(val snapshot: PleosOrganizerSnapshot?, val reason: String)

internal object AndroidUserId {
    // Android assigns each user a non-overlapping range of 100,000 app UIDs.
    private const val PER_USER_UID_RANGE = 100_000
    fun fromUid(uid: Int): Int {
        require(uid >= 0)
        return uid / PER_USER_UID_RANGE
    }
    fun current(): Int = fromUid(android.os.Process.myUid())
}

internal object PleosReadinessParser {
    private const val HOME = "ai.umos.homescreen/ai.umos.homescreen.HomeActivity"
    private const val DRIVING = "ai.umos.drivingview/ai.umos.drivingview.DrivingViewActivity"
    private const val NAVI = "ai.umos.maps.android.navigation.app/ai.umos.maps.android.navigation.app.MainActivity"
    private val required = listOf(HOME, DRIVING, NAVI)

    fun observe(
        homePidBefore: String,
        dump: String,
        homePidAfter: String,
        currentUserId: Int? = null,
    ): PleosObservation {
        val before = PleosHomePid.singleOrNull(homePidBefore)
        val after = PleosHomePid.singleOrNull(homePidAfter)
        if (before == null || after == null) return PleosObservation(null, "home_pid_missing_or_multiple")
        if (before != after) return PleosObservation(null, "home_generation_changed")
        if (dump.isBlank() || dump.contains("Permission Denial", ignoreCase = true)) {
            return PleosObservation(null, "activity_dump_unavailable")
        }
        val parsed = parseTasks(dump)
        if (!parsed.consistent) return PleosObservation(null, "task_record_context_mismatch")
        val tasks = canonicalize(parsed.tasks, currentUserId)
            ?: return PleosObservation(null, "task_representation_inconsistent")
        val ids = required.map { component -> tasks.filter { component in it.components }.map { it.id }.toSet() }
        val cardinalities = ids.joinToString(",") { it.size.toString() }
        if (ids.any { it.size != 1 }) return PleosObservation(null, "task_cardinality=$cardinalities")
        val homeTask = tasks.single { HOME in it.components }
        if (!homeTask.visible.contains(HOME) || !homeTask.resumed.contains(HOME)) {
            return PleosObservation(null, "home_not_visible_resumed")
        }
        return PleosObservation(PleosOrganizerSnapshot(after, ids.map { it.single() }), "ready")
    }

    private fun parseTasks(dump: String): ParsedTasks {
        val tasks = mutableListOf<TaskBlock>()
        var task: TaskBlock? = null
        var activity: String? = null
        var activityIndent = -1
        var consistent = true
        dump.lineSequence().forEach { line ->
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }
            if (activity != null && indent <= activityIndent && !HIST_RECORD.containsMatchIn(line)) {
                activity = null
                activityIndent = -1
            }
            TASK_HEADER.find(line)?.groupValues?.get(1)?.let { id ->
                task = TaskBlock(id)
                tasks += requireNotNull(task)
                activity = null
                activityIndent = -1
            }
            ACTIVITY_RECORD.find(line)?.let { match ->
                val recordTaskId = match.groupValues[4]
                val component = normalize(match.groupValues[2], match.groupValues[3])
                val userId = match.groupValues[1].toInt()
                if (HIST_RECORD.containsMatchIn(line)) {
                    if (!HIST_ZERO.containsMatchIn(line)) {
                        activity = null
                        activityIndent = -1
                    } else {
                        if (task?.id != recordTaskId) consistent = false
                        val detailed = task ?: TaskBlock(recordTaskId).also(tasks::add)
                        detailed.addRecord(component, userId)
                        activity = component
                        activityIndent = indent
                    }
                } else {
                    // Global Resumed/mFocusedApp/application-token summaries use their own tNNN.
                    TaskBlock(recordTaskId).also { summary ->
                        summary.addRecord(component, userId)
                        tasks += summary
                    }
                    activity = null
                    activityIndent = -1
                }
            }
            val active = activity
            if (active != null) {
                VISIBILITY.find(line)?.groupValues?.get(1)?.toBooleanStrictOrNull()?.let { visible ->
                    task?.visibility?.getOrPut(active, ::mutableSetOf)?.add(visible)
                }
                LIFECYCLE.find(line)?.groupValues?.get(1)?.let { state ->
                    task?.lifecycle?.getOrPut(active, ::mutableSetOf)?.add(state)
                }
            }
        }
        return ParsedTasks(tasks, consistent)
    }

    private fun TaskBlock.addRecord(component: String, userId: Int) {
        components += component
        users.getOrPut(component, ::mutableSetOf) += userId
    }

    private fun canonicalize(raw: List<TaskBlock>, currentUserId: Int?): List<CanonicalTask>? {
        val scoped = raw.mapNotNull { block ->
            val components = block.components.filterTo(mutableSetOf()) { component ->
                val users = block.users[component].orEmpty()
                currentUserId == null || users == setOf(currentUserId)
            }
            block.takeIf { components.isNotEmpty() }?.copy(components = components)
        }
        return scoped.groupBy(TaskBlock::id).map { (id, representations) ->
            val componentSets = representations.map(TaskBlock::components).distinct()
            if (componentSets.size != 1) return null
            val components = componentSets.single()
            val visibility = components.associateWith { component ->
                representations.flatMap { it.visibility[component].orEmpty() }.toSet()
            }
            val lifecycle = components.associateWith { component ->
                representations.flatMap { it.lifecycle[component].orEmpty() }.toSet()
            }
            // Never OR stale/inconsistent representations into an apparently ready task.
            if (visibility.values.any { it.size > 1 } || lifecycle.values.any { it.size > 1 }) return null
            CanonicalTask(
                id = id,
                components = components,
                visible = visibility.filterValues { it == setOf(true) }.keys,
                resumed = lifecycle.filterValues { it == setOf("RESUMED") }.keys,
                active = lifecycle.filterValues { it.singleOrNull() in setOf("STARTED", "RESUMED") }.keys,
            )
        }
    }

    private fun normalize(packageName: String, className: String): String {
        val fullClass = if (className.startsWith('.')) packageName + className else className
        return "$packageName/$fullClass"
    }

    private data class TaskBlock(
        val id: String,
        val components: MutableSet<String> = mutableSetOf(),
        val users: MutableMap<String, MutableSet<Int>> = mutableMapOf(),
        val visibility: MutableMap<String, MutableSet<Boolean>> = mutableMapOf(),
        val lifecycle: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    )

    private data class ParsedTasks(val tasks: List<TaskBlock>, val consistent: Boolean)

    private data class CanonicalTask(
        val id: String,
        val components: Set<String>,
        val visible: Set<String>,
        val resumed: Set<String>,
        val active: Set<String>,
    )

    private val TASK_HEADER = Regex("^\\s*\\*\\s+Task\\{[^\\n]*#([0-9]+)\\b")
    private val ACTIVITY_RECORD = Regex("ActivityRecord\\{[^\\n]*\\su([0-9]+)\\s+([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\\s+t([0-9]+)")
    private val HIST_RECORD = Regex("\\bHist\\s+#[0-9]+:")
    private val HIST_ZERO = Regex("\\bHist\\s+#0:")
    private val VISIBILITY = Regex("(?:^|\\s)mVisible=(true|false)(?:\\s|$)")
    private val LIFECYCLE = Regex("(?:^|\\s)state=(CREATED|STARTED|RESUMED|PAUSED|STOPPED|DESTROYED)(?:\\s|$)")
}

internal class StableOrganizerGeneration {
    private var previous: PleosOrganizerSnapshot? = null
    fun record(observation: PleosObservation): Boolean {
        val current = observation.snapshot
        if (current == null) {
            previous = null
            return false
        }
        if (current == previous) return true
        previous = current
        return false
    }
}

internal data class FatalEvidence(val kind: String, val tag: String, val pid: String, val hash: String)

internal object PleosShellDiagnostics {
    private val logcatDiagnostic = Regex(
        "(?im)^(?:logcat:|/system/bin/sh:.*logcat).*(?:permission denied|unknown option|not found|invalid|error)",
    )
    fun logcatCommandFailed(output: String): Boolean = logcatDiagnostic.containsMatchIn(output)
}

internal class PleosFatalLogGuard(
    val baselineEpochSeconds: Double,
    private val relevantPackages: Set<String>,
) {
    private val seen = mutableSetOf<Int>()

    fun inspect(log: String, relevantPids: Set<String>): FatalEvidence? {
        val lines = log.lineSequence().filter(String::isNotBlank).toList()
        lines.forEachIndexed { index, line ->
            val timestamp = line.substringBefore(' ').toDoubleOrNull() ?: return@forEachIndexed
            if (timestamp < baselineEpochSeconds) return@forEachIndexed
            val window = lines.subList(index, minOf(index + 12, lines.size)).joinToString("\n")
            val hash = window.hashCode()
            if (!seen.add(hash)) return@forEachIndexed
            val pid = EPOCH_LINE.find(line)?.groupValues?.get(1).orEmpty()
            val relevant = pid in relevantPids || relevantPackages.any(window::contains)
            if (!relevant) return@forEachIndexed
            val fatal = line.contains("AndroidRuntime") && line.contains("FATAL EXCEPTION")
            val duplicate = DUPLICATE_LISTENER.containsMatchIn(window)
            if (fatal || duplicate) {
                return FatalEvidence(
                    kind = if (duplicate) "duplicate_listener" else "fatal",
                    tag = if (line.contains("AndroidRuntime")) "AndroidRuntime" else "task_organizer",
                    pid = pid.ifBlank { "unknown" },
                    hash = hash.toUInt().toString(16),
                )
            }
        }
        return null
    }

    private val EPOCH_LINE = Regex("^[0-9.]+\\s+([0-9]+)\\s+")
    private val DUPLICATE_LISTENER = Regex(
        "(?i)(duplicate|already (?:registered|exists)).{0,80}(listener|task.?organizer)|" +
            "(listener|task.?organizer).{0,80}(duplicate|already (?:registered|exists))",
    )
}

private object PleosTaskReadiness {
    private const val HOME_PACKAGE = "ai.umos.homescreen"
    private val relevantPackages = setOf(
        HOME_PACKAGE,
        "ai.umos.drivingview",
        "ai.umos.maps.android.navigation.app",
        "com.socialdrive.app",
    )

    fun awaitStableGeneration(): PleosReadinessSession {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (runCatching { instrumentation.targetContext.packageManager.getApplicationInfo(HOME_PACKAGE, 0) }.isFailure) {
            error("PLEOS readiness home_package_missing")
        }
        val logGuard = PleosFatalLogGuard(System.currentTimeMillis() / 1_000.0, relevantPackages)
        val generation = StableOrganizerGeneration()
        val currentUserId = AndroidUserId.current()
        val deadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT_MILLIS
        var lastReason = "not_observed"
        while (SystemClock.elapsedRealtime() < deadline) {
            val pidBefore = shell(PID_COMMAND)
            assertLogHealthy(logGuard, listOfNotNull(PleosHomePid.singleOrNull(pidBefore)).toSet())
            val dump = shell(ACTIVITY_COMMAND)
            val pidAfter = shell(PID_COMMAND)
            val observation = PleosReadinessParser.observe(pidBefore, dump, pidAfter, currentUserId)
            lastReason = observation.reason
            assertLogHealthy(logGuard, listOfNotNull(PleosHomePid.singleOrNull(pidAfter)).toSet())
            if (generation.record(observation)) {
                assertLogHealthy(logGuard, listOfNotNull(PleosHomePid.singleOrNull(pidAfter)).toSet())
                return PleosReadinessSession(logGuard, requireNotNull(observation.snapshot).homePid)
            }
        }
        error("PLEOS readiness deadline: $lastReason")
    }

    fun assertStableGenerationHealthy(guard: PleosFatalLogGuard, expectedHomePid: String) {
        val currentHomePid = currentHomePidIfNumeric()
        assertLogHealthy(guard, listOfNotNull(expectedHomePid, currentHomePid).toSet())
        if (currentHomePid != expectedHomePid) error("PLEOS readiness home_generation_changed")
    }

    fun assertLogHealthy(guard: PleosFatalLogGuard, relevantPids: Set<String>) {
        val cursor = String.format(Locale.US, "%.3f", guard.baselineEpochSeconds)
        val log = shell("logcat -d -v epoch -T $cursor")
        if (PleosShellDiagnostics.logcatCommandFailed(log)) error("PLEOS readiness logcat_unavailable")
        guard.inspect(log, relevantPids)
            ?.let { evidence -> error("PLEOS readiness ${evidence.kind} ${evidence.tag} pid=${evidence.pid} hash=${evidence.hash}") }
    }

    fun currentHomePidIfNumeric(): String? = runCatching { shell(PID_COMMAND) }
        .getOrNull()
        ?.let(PleosHomePid::singleOrNull)

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private const val PID_COMMAND = "pidof ai.umos.homescreen"
    private const val ACTIVITY_COMMAND = "dumpsys activity activities"
    private const val READINESS_TIMEOUT_MILLIS = 45_000L
}
