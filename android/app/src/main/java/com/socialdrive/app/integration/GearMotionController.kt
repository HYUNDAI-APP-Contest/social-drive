package com.socialdrive.app.integration

import com.socialdrive.app.domain.MotionState

internal enum class GearSignal {
    Unknown,
    Neutral,
    Reverse,
    Park,
    Drive,
    Gear1,
    Gear2,
    Gear3,
    Gear4,
    Gear5,
    Gear6,
    Gear7,
    Gear8,
    Gear9,
}

internal fun GearSignal.toMotionState(): MotionState = when (this) {
    GearSignal.Park -> MotionState.Parked
    GearSignal.Unknown -> MotionState.Unknown
    GearSignal.Neutral,
    GearSignal.Reverse,
    GearSignal.Drive,
    GearSignal.Gear1,
    GearSignal.Gear2,
    GearSignal.Gear3,
    GearSignal.Gear4,
    GearSignal.Gear5,
    GearSignal.Gear6,
    GearSignal.Gear7,
    GearSignal.Gear8,
    GearSignal.Gear9,
    -> MotionState.Driving
}

internal interface GearSignalCallback {
    fun onSignal(signal: GearSignal)
    fun onFailure()
}

/** Narrow read-only boundary around the permissioned Vehicle gear API. */
internal interface GearSignalPort {
    fun hasReadPermission(): Boolean
    fun initialize()
    fun readCurrent(callback: GearSignalCallback)
    fun register(callback: GearSignalCallback)
    fun unregister(callback: GearSignalCallback)
    fun release()
}

internal fun interface GearScheduledTask {
    fun cancel()
}

internal interface GearScheduleScope {
    fun schedule(delayMillis: Long, task: () -> Unit): GearScheduledTask
    fun cancelAll()
}

/**
 * Keeps a gear observation fresh without ever treating initialization as parked evidence.
 * A listener provides immediate transitions while a bounded current-value read detects a stale
 * service. After a read timeout the controller remains Unknown until a new listener event; it does
 * not create an unbounded queue of vendor calls while the service is unavailable.
 */
internal class GearMotionController(
    private val port: GearSignalPort,
    private val scheduleScope: GearScheduleScope,
    private val onMotionChanged: (MotionState) -> Unit,
    private val refreshIntervalMillis: Long = 2_000L,
    private val readTimeoutMillis: Long = 3_000L,
) {
    private val lock = Any()
    private val lifecycleOperationLock = Any()
    private val vendorOperationLock = Any()
    private var lifecycleGeneration = 0L
    private var readGeneration = 0L
    private var active = false
    private var initialized = false
    private var registeredCallback: GearSignalCallback? = null
    private var inFlightRead: Long? = null
    private var refreshTask: GearScheduledTask? = null
    private var readTimeoutTask: GearScheduledTask? = null

    init {
        require(refreshIntervalMillis > 0)
        require(readTimeoutMillis > 0)
    }

    fun initialize() = synchronized(lifecycleOperationLock) { initializeSerially() }

    private fun initializeSerially() {
        val lifecycle = synchronized(lock) {
            if (active) return
            active = true
            lifecycleGeneration += 1
            lifecycleGeneration
        }
        publish(MotionState.Unknown)

        val permitted = runCatching { port.hasReadPermission() }.getOrDefault(false)
        if (!permitted || !isActive(lifecycle)) return

        if (runCatching { synchronized(vendorOperationLock) { port.initialize() } }.isFailure) {
            runCatching { synchronized(vendorOperationLock) { port.release() } }
            failClosed(lifecycle)
            return
        }
        val retained = synchronized(lock) {
            if (matches(lifecycle)) {
                initialized = true
                true
            } else {
                false
            }
        }
        if (!retained) {
            runCatching { synchronized(vendorOperationLock) { port.release() } }
            return
        }

        val callback = callbackFor(lifecycle)
        synchronized(lock) {
            if (matches(lifecycle)) registeredCallback = callback
        }
        if (runCatching { synchronized(vendorOperationLock) { port.register(callback) } }.isFailure) {
            synchronized(lock) {
                if (registeredCallback === callback) registeredCallback = null
            }
            release()
            return
        }
        if (!isActive(lifecycle)) {
            runCatching { synchronized(vendorOperationLock) { port.unregister(callback) } }
            return
        }
        requestCurrent(lifecycle)
    }

    fun release() = synchronized(lifecycleOperationLock) { releaseSerially() }

    private fun releaseSerially() {
        val resources = synchronized(lock) {
            lifecycleGeneration += 1
            active = false
            readGeneration += 1
            inFlightRead = null
            val captured = ReleaseResources(
                callback = registeredCallback,
                initialized = initialized,
                refreshTask = refreshTask,
                timeoutTask = readTimeoutTask,
            )
            registeredCallback = null
            initialized = false
            refreshTask = null
            readTimeoutTask = null
            captured
        }
        resources.refreshTask?.cancel()
        resources.timeoutTask?.cancel()
        synchronized(vendorOperationLock) {
            resources.callback?.let { callback -> runCatching { port.unregister(callback) } }
            if (resources.initialized) runCatching { port.release() }
        }
        runCatching { scheduleScope.cancelAll() }
        publish(MotionState.Unknown)
    }

    private fun callbackFor(lifecycle: Long): GearSignalCallback = object : GearSignalCallback {
        override fun onSignal(signal: GearSignal) {
            if (!permissionStillGranted(lifecycle)) return
            val motion = synchronized(lock) {
                if (!matches(lifecycle)) return
                signal.toMotionState()
            }
            publish(motion)
            scheduleRefreshWhenIdle(lifecycle)
        }

        override fun onFailure() {
            failClosed(lifecycle)
        }
    }

    private fun requestCurrent(lifecycle: Long) {
        if (!permissionStillGranted(lifecycle)) return
        val read = synchronized(lock) {
            if (!matches(lifecycle) || inFlightRead != null) return
            readGeneration += 1
            readGeneration.also { inFlightRead = it }
        }
        val timeout = runCatching {
            scheduleScope.schedule(readTimeoutMillis) { onReadTimeout(lifecycle, read) }
        }.getOrElse {
            failRead(lifecycle, read)
            return
        }
        val keepTimeout = synchronized(lock) {
            if (matchesRead(lifecycle, read)) {
                readTimeoutTask?.cancel()
                readTimeoutTask = timeout
                true
            } else {
                false
            }
        }
        if (!keepTimeout) {
            timeout.cancel()
            return
        }
        val callback = object : GearSignalCallback {
            override fun onSignal(signal: GearSignal) = completeRead(lifecycle, read, signal)
            override fun onFailure() = failRead(lifecycle, read)
        }
        val readStarted = runCatching {
            synchronized(vendorOperationLock) {
                if (!isActive(lifecycle)) return@synchronized
                port.readCurrent(callback)
            }
        }
        if (readStarted.isFailure) failRead(lifecycle, read)
    }

    private fun completeRead(lifecycle: Long, read: Long, signal: GearSignal) {
        if (!permissionStillGranted(lifecycle)) return
        val motion = synchronized(lock) {
            if (!matchesRead(lifecycle, read)) return
            inFlightRead = null
            readTimeoutTask?.cancel()
            readTimeoutTask = null
            signal.toMotionState()
        }
        publish(motion)
        scheduleRefreshWhenIdle(lifecycle)
    }

    private fun failRead(lifecycle: Long, read: Long) {
        val applied = synchronized(lock) {
            if (!matchesRead(lifecycle, read)) return
            inFlightRead = null
            readTimeoutTask?.cancel()
            readTimeoutTask = null
            refreshTask?.cancel()
            refreshTask = null
            true
        }
        if (applied) publish(MotionState.Unknown)
    }

    private fun onReadTimeout(lifecycle: Long, read: Long) = failRead(lifecycle, read)

    private fun scheduleRefreshWhenIdle(lifecycle: Long) {
        val shouldSchedule = synchronized(lock) {
            matches(lifecycle) && inFlightRead == null
        }
        if (!shouldSchedule) return
        val scheduled = runCatching {
            scheduleScope.schedule(refreshIntervalMillis) { requestCurrent(lifecycle) }
        }.getOrElse {
            failClosed(lifecycle)
            return
        }
        val accepted = synchronized(lock) {
            if (matches(lifecycle) && inFlightRead == null) {
                refreshTask?.cancel()
                refreshTask = scheduled
                true
            } else {
                false
            }
        }
        if (!accepted) scheduled.cancel()
    }

    private fun permissionStillGranted(lifecycle: Long): Boolean {
        if (!isActive(lifecycle)) return false
        val granted = runCatching { port.hasReadPermission() }.getOrDefault(false)
        if (!granted) failClosed(lifecycle)
        return granted
    }

    private fun failClosed(lifecycle: Long) {
        val applied = synchronized(lock) {
            if (!matches(lifecycle)) return
            readGeneration += 1
            inFlightRead = null
            refreshTask?.cancel()
            refreshTask = null
            readTimeoutTask?.cancel()
            readTimeoutTask = null
            true
        }
        if (applied) publish(MotionState.Unknown)
    }

    private fun isActive(lifecycle: Long): Boolean = synchronized(lock) { matches(lifecycle) }

    private fun matches(lifecycle: Long): Boolean = active && lifecycleGeneration == lifecycle

    private fun matchesRead(lifecycle: Long, read: Long): Boolean =
        matches(lifecycle) && inFlightRead == read && readGeneration == read

    private fun publish(motion: MotionState) = onMotionChanged(motion)

    private data class ReleaseResources(
        val callback: GearSignalCallback?,
        val initialized: Boolean,
        val refreshTask: GearScheduledTask?,
        val timeoutTask: GearScheduledTask?,
    )
}
