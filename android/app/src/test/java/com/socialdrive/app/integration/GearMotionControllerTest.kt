package com.socialdrive.app.integration

import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.safety.ParkedExecutionGate
import com.socialdrive.app.safety.ParkedExecutionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GearMotionControllerTest {
    @Test
    fun `only park maps to parked and every movable gear maps to driving`() {
        assertEquals(MotionState.Parked, GearSignal.Park.toMotionState())
        assertEquals(MotionState.Unknown, GearSignal.Unknown.toMotionState())

        val driving = GearSignal.entries - setOf(GearSignal.Park, GearSignal.Unknown)
        assertEquals(12, driving.size)
        driving.forEach { signal -> assertEquals(MotionState.Driving, signal.toMotionState()) }
    }

    @Test
    fun `permission denial stays unknown without touching the vendor service`() {
        val harness = Harness(permissionGranted = false)

        harness.controller.initialize()

        assertEquals(listOf(MotionState.Unknown), harness.motions)
        assertEquals(0, harness.port.initializeCalls)
        assertEquals(0, harness.port.registerCalls)
        assertEquals(0, harness.port.readCalls)
    }

    @Test
    fun `partial initialization failures release vendor resources and stay unknown`() {
        val initializationFailure = Harness()
        initializationFailure.port.throwOnInitialize = true
        initializationFailure.controller.initialize()
        assertEquals(1, initializationFailure.port.releaseCalls)
        assertEquals(MotionState.Unknown, initializationFailure.motions.last())

        val registrationFailure = Harness()
        registrationFailure.port.throwOnRegister = true
        registrationFailure.controller.initialize()
        assertEquals(1, registrationFailure.port.releaseCalls)
        assertEquals(MotionState.Unknown, registrationFailure.motions.last())
        assertEquals(0, registrationFailure.scheduler.activeTasks())
    }

    @Test
    fun `current value and listener transitions publish fail closed motion`() {
        val harness = Harness()
        harness.controller.initialize()
        assertEquals(1, harness.port.initializeCalls)
        assertEquals(1, harness.port.registerCalls)
        assertEquals(1, harness.port.readCalls)

        harness.port.completeCurrent(GearSignal.Park)
        harness.port.emit(GearSignal.Drive)
        harness.port.emit(GearSignal.Reverse)
        harness.port.emit(GearSignal.Neutral)
        harness.port.emit(GearSignal.Gear9)
        harness.port.emit(GearSignal.Unknown)

        assertEquals(
            listOf(
                MotionState.Unknown,
                MotionState.Parked,
                MotionState.Driving,
                MotionState.Driving,
                MotionState.Driving,
                MotionState.Driving,
                MotionState.Unknown,
            ),
            harness.motions,
        )
    }

    @Test
    fun `a stale refresh fails closed and does not queue more reads`() {
        val harness = Harness()
        harness.controller.initialize()
        harness.port.completeCurrent(GearSignal.Park)

        harness.scheduler.runNext(2_000L)
        assertEquals(2, harness.port.readCalls)
        harness.scheduler.runNext(3_000L)

        assertEquals(MotionState.Unknown, harness.motions.last())
        assertEquals(2, harness.port.readCalls)
        assertEquals(0, harness.scheduler.activeTasks())
    }

    @Test
    fun `read and listener failures discard parked evidence`() {
        val readFailure = Harness()
        readFailure.controller.initialize()
        readFailure.port.completeCurrent(GearSignal.Park)
        readFailure.scheduler.runNext(2_000L)
        readFailure.port.failCurrent()
        assertEquals(MotionState.Unknown, readFailure.motions.last())

        val listenerFailure = Harness()
        listenerFailure.controller.initialize()
        listenerFailure.port.completeCurrent(GearSignal.Park)
        listenerFailure.port.failListener()
        assertEquals(MotionState.Unknown, listenerFailure.motions.last())
        assertEquals(0, listenerFailure.scheduler.activeTasks())
    }

    @Test
    fun `permission loss invalidates a later callback`() {
        val harness = Harness()
        harness.controller.initialize()
        harness.port.completeCurrent(GearSignal.Park)

        harness.port.permissionGranted = false
        harness.port.emit(GearSignal.Park)

        assertEquals(MotionState.Unknown, harness.motions.last())
        assertEquals(0, harness.scheduler.activeTasks())
    }

    @Test
    fun `duplicate initialization owns one listener and a new generation rejects old callbacks`() {
        val harness = Harness()
        harness.controller.initialize()
        harness.controller.initialize()
        val staleListener = harness.port.listener
        val staleRead = harness.port.currentCallback
        harness.port.completeCurrent(GearSignal.Park)

        harness.controller.release()
        harness.controller.initialize()
        staleListener?.onSignal(GearSignal.Park)
        staleRead?.onSignal(GearSignal.Park)
        assertEquals(MotionState.Unknown, harness.motions.last())
        harness.port.completeCurrent(GearSignal.Reverse)
        assertEquals(MotionState.Driving, harness.motions.last())
        harness.controller.release()

        assertEquals(2, harness.port.initializeCalls)
        assertEquals(2, harness.port.registerCalls)
        assertEquals(2, harness.port.unregisterCalls)
        assertEquals(2, harness.port.releaseCalls)
        assertEquals(MotionState.Unknown, harness.motions.last())
        assertEquals(0, harness.scheduler.activeTasks())
    }

    @Test
    fun `observed gear transitions drive the existing parked execution gate`() {
        val port = FakeGearSignalPort()
        val scheduler = ManualGearScheduleScope()
        val gate = ParkedExecutionGate(MotionState.Unknown)
        val controller = GearMotionController(
            port = port,
            scheduleScope = scheduler,
            onMotionChanged = { motion -> runBlocking { gate.transitionTo(motion) } },
        )
        controller.initialize()

        port.completeCurrent(GearSignal.Park)
        val allowed = runBlocking { gate.execute(onPaused = {}) { "published" } }
        assertEquals("published", assertIs<ParkedExecutionResult.Completed<String>>(allowed).value)

        port.emit(GearSignal.Reverse)
        val blocked = runBlocking { gate.execute(onPaused = {}) { "must-not-run" } }
        assertIs<ParkedExecutionResult.Paused>(blocked)
    }
}

private class Harness(permissionGranted: Boolean = true) {
    val port = FakeGearSignalPort(permissionGranted)
    val scheduler = ManualGearScheduleScope()
    val motions = mutableListOf<MotionState>()
    val controller = GearMotionController(port, scheduler, motions::add)
}

private class FakeGearSignalPort(
    var permissionGranted: Boolean = true,
) : GearSignalPort {
    var initializeCalls = 0
    var registerCalls = 0
    var unregisterCalls = 0
    var releaseCalls = 0
    var readCalls = 0
    var throwOnInitialize = false
    var throwOnRegister = false
    var listener: GearSignalCallback? = null
    var currentCallback: GearSignalCallback? = null

    override fun hasReadPermission(): Boolean = permissionGranted

    override fun initialize() {
        initializeCalls += 1
        if (throwOnInitialize) error("sanitized fake initialization failure")
    }

    override fun readCurrent(callback: GearSignalCallback) {
        readCalls += 1
        currentCallback = callback
    }

    override fun register(callback: GearSignalCallback) {
        registerCalls += 1
        if (throwOnRegister) error("sanitized fake registration failure")
        check(listener == null)
        listener = callback
    }

    override fun unregister(callback: GearSignalCallback) {
        unregisterCalls += 1
        if (listener === callback) listener = null
    }

    override fun release() {
        releaseCalls += 1
    }

    fun completeCurrent(signal: GearSignal) {
        val callback = currentCallback ?: error("No current-value request")
        currentCallback = null
        callback.onSignal(signal)
    }

    fun failCurrent() {
        val callback = currentCallback ?: error("No current-value request")
        currentCallback = null
        callback.onFailure()
    }

    fun emit(signal: GearSignal) = requireNotNull(listener).onSignal(signal)

    fun failListener() = requireNotNull(listener).onFailure()
}

private class ManualGearScheduleScope : GearScheduleScope {
    private data class Entry(
        val delayMillis: Long,
        val block: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val entries = mutableListOf<Entry>()

    override fun schedule(delayMillis: Long, task: () -> Unit): GearScheduledTask {
        val entry = Entry(delayMillis, task)
        entries += entry
        return GearScheduledTask { entry.cancelled = true }
    }

    override fun cancelAll() {
        entries.forEach { it.cancelled = true }
    }

    fun runNext(delayMillis: Long) {
        val entry = entries.firstOrNull { !it.cancelled && it.delayMillis == delayMillis }
            ?: error("No active task for $delayMillis ms")
        entry.cancelled = true
        entry.block()
    }

    fun activeTasks(): Int = entries.count { !it.cancelled }
}
