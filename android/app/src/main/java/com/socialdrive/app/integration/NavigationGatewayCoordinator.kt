package com.socialdrive.app.integration

import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.NavigationState

internal interface NavigationVendorPort {
    fun initialize(callback: NavigationInitializationCallback)
    fun addListener(listener: NavigationVendorListener)
    fun requestRoute(request: NavigationRequest)
    fun removeListener(listener: NavigationVendorListener)
    fun release()
}

internal interface NavigationInitializationCallback {
    fun onConnected()
    fun onFailed()
}

internal interface NavigationVendorListener {
    fun onRouteStarted()
    fun onRoutingCompleted()
    fun onError(userMessage: String)
}

internal fun interface NavigationScheduledTask {
    fun cancel()
}

internal interface NavigationScheduleScope {
    fun schedule(delayMillis: Long, task: () -> Unit): NavigationScheduledTask
    fun cancelAll()
}

internal data class NavigationVendorSession(
    val port: NavigationVendorPort,
    val scheduleScope: NavigationScheduleScope,
)

internal interface NavigationCoordinatorObserver {
    fun onCapabilityChanged(available: Boolean)
    fun onStateChanged(state: NavigationState)
}

/**
 * Owns request/lifecycle correlation while keeping all vendor calls outside [lock].
 * Vendor calls are counted so a synchronous callback can request disposal without recursively
 * calling remove/release from inside initialize/add/request.
 */
internal class NavigationGatewayCoordinator(
    private val vendorSessionFactory: () -> NavigationVendorSession,
    private val observer: NavigationCoordinatorObserver,
    disconnectedState: NavigationState,
    connectedState: NavigationState,
    private val evidence: NavigationRuntimeEvidenceSink = NavigationRuntimeEvidenceSink {},
    private val initializationTimeoutMillis: Long = 10_000L,
    private val minimumFeedbackMillis: Long = 250L,
    private val requestTimeoutMillis: Long = 15_000L,
    private val staleEventQuarantineMillis: Long = 750L,
) {
    private val lock = Any()
    private val controller = NavigationRequestController(disconnectedState, connectedState)
    private var current: Session? = null
    private var cleanupFailed = false

    fun initialize(permissionGranted: Boolean = true) {
        val transition = synchronized(lock) {
            val old = current?.let(::detachLocked)
            val token = controller.beginInitialization()
            record(token, NavigationRuntimeStage.INITIALIZE_STARTED)
            observer.onCapabilityChanged(false)
            observer.onStateChanged(controller.state())
            InitializationTransition(token, old)
        }
        cancelTasks(transition.old?.tasks.orEmpty())
        if (!runMaintenance(transition.old?.maintenance)) return
        synchronized(lock) { cleanupFailed = false }

        if (!permissionGranted) {
            synchronized(lock) {
                record(transition.token, NavigationRuntimeStage.PERMISSION_DENIED)
                val update = controller.initializationFailed(transition.token, PERMISSION_DENIED)
                if (update is ControllerUpdate.Applied) observer.onStateChanged(update.state)
            }
            return
        }

        val resources = runCatching(vendorSessionFactory).getOrElse {
            synchronized(lock) {
                val update = controller.initializationFailed(transition.token, INITIALIZATION_FAILED)
                if (update is ControllerUpdate.Applied) {
                    record(transition.token, NavigationRuntimeStage.SERVICE_UNAVAILABLE)
                    observer.onCapabilityChanged(false)
                    observer.onStateChanged(update.state)
                }
            }
            return
        }
        val session = Session(transition.token, resources)
        val attached = synchronized(lock) {
            if (!controller.isInitializing(transition.token)) false else {
                current = session
                true
            }
        }
        if (!attached) {
            releaseOrphan(resources, transition.token)
            return
        }

        val initializationTimeout = runCatching {
            resources.scheduleScope.schedule(initializationTimeoutMillis) {
                handleInitializationTimeout(session)
            }
        }.getOrElse {
            handleInitializationFailure(session)
            return
        }
        val timeoutKept = synchronized(lock) {
            if (current === session && controller.isInitializing(session.lifecycleToken)) {
                session.initializationTimeoutTask = initializationTimeout
                true
            } else false
        }
        if (!timeoutKept) initializationTimeout.cancel()

        val invoked = invokeVendor(session) { port ->
            port.initialize(object : NavigationInitializationCallback {
                override fun onConnected() = handleConnected(session)
                override fun onFailed() = handleInitializationFailure(session)
            })
        }
        if (!invoked.success) handleInitializationFailure(session)
    }

    fun requestRoute(request: NavigationRequest): NavigationRequestResult {
        val accepted = synchronized(lock) {
            if (cleanupFailed) return NavigationRequestResult.Rejected(CLEANUP_FAILED)
            val session = current ?: return NavigationRequestResult.Rejected(CONNECTION_UNAVAILABLE)
            when (val start = controller.beginRequest(request.requestId, request.destination.name)) {
                RequestStart.Busy -> return NavigationRequestResult.Busy
                RequestStart.Unavailable -> return NavigationRequestResult.Rejected(CONNECTION_UNAVAILABLE)
                is RequestStart.Accepted -> {
                    record(start.token, NavigationRuntimeStage.REQUEST_VALIDATED)
                    val listener = requestListener(session, start.token)
                    val requestContext = RequestContext(start.token, listener)
                    session.request = requestContext
                    observer.onStateChanged(start.state)
                    AcceptedRequest(session, requestContext)
                }
            }
        }
        val timeout = runCatching {
            accepted.session.resources.scheduleScope.schedule(requestTimeoutMillis) {
                handleTimeout(accepted.session, accepted.request.token)
            }
        }.getOrElse {
            handleInvocationFailure(accepted.session, accepted.request.token)
            return NavigationRequestResult.Rejected(REQUEST_FAILED_SHORT)
        }
        val timeoutKept = synchronized(lock) {
            if (isCurrentRequestLocked(accepted.session, accepted.request.token)) {
                accepted.request.timeoutTask = timeout
                true
            } else false
        }
        if (!timeoutKept) timeout.cancel()

        var dispatched = false
        val invoked = invokeVendor(accepted.session) { port ->
            port.addListener(accepted.request.listener)
            val listenerKept = synchronized(lock) {
                if (isCurrentRequestLocked(accepted.session, accepted.request.token)) {
                    accepted.request.listenerAdded = true
                    true
                } else {
                    accepted.session.pendingRemovals += ListenerRemoval(
                        accepted.request.listener,
                        accepted.request.token,
                    )
                    false
                }
            }
            if (!listenerKept) return@invokeVendor
            record(accepted.request.token, NavigationRuntimeStage.REQUEST_DISPATCHED)
            dispatched = true
            port.requestRoute(request)
        }
        if (!invoked.success) {
            handleInvocationFailure(accepted.session, accepted.request.token)
            return NavigationRequestResult.Rejected(REQUEST_FAILED_SHORT)
        }
        return if (invoked.started && dispatched) {
            NavigationRequestResult.Accepted
        } else {
            NavigationRequestResult.Rejected(CONNECTION_UNAVAILABLE)
        }
    }

    fun rejectLocally(userMessage: String) {
        synchronized(lock) {
            val update = controller.localRejection(userMessage)
            if (update is ControllerUpdate.Applied) observer.onStateChanged(update.state)
        }
    }

    fun release() {
        val detached = synchronized(lock) {
            val session = current?.let(::detachLocked)
            val update = controller.release()
            observer.onCapabilityChanged(false)
            if (update is ControllerUpdate.Applied) {
                observer.onStateChanged(
                    if (cleanupFailed && session == null) NavigationState.Failed(CLEANUP_FAILED) else update.state,
                )
            }
            ReleaseTransition(session)
        }
        cancelTasks(detached.detached?.tasks.orEmpty())
        runMaintenance(detached.detached?.maintenance)
    }

    private fun handleConnected(session: Session) {
        val timeout = synchronized(lock) {
            if (current !== session) {
                record(session.lifecycleToken, NavigationRuntimeStage.STALE_IGNORED)
                return
            }
            controller.connected(session.lifecycleToken).also { update ->
                if (update is ControllerUpdate.Applied) {
                    record(session.lifecycleToken, NavigationRuntimeStage.INITIALIZE_CONNECTED)
                    observer.onCapabilityChanged(true)
                    observer.onStateChanged(update.state)
                }
            }
            session.initializationTimeoutTask.also { session.initializationTimeoutTask = null }
        }
        timeout?.cancel()
    }

    private fun handleInitializationFailure(session: Session) {
        val transition = synchronized(lock) {
            if (current !== session) {
                record(session.lifecycleToken, NavigationRuntimeStage.STALE_IGNORED)
                return
            }
            val update = controller.initializationFailed(session.lifecycleToken, INITIALIZATION_FAILED)
            if (update !is ControllerUpdate.Applied) {
                record(session.lifecycleToken, NavigationRuntimeStage.STALE_IGNORED)
                return
            }
            record(session.lifecycleToken, NavigationRuntimeStage.SERVICE_UNAVAILABLE)
            observer.onCapabilityChanged(false)
            observer.onStateChanged(update.state)
            InitializationFailureTransition(detachLocked(session))
        }
        cancelTasks(transition.detached.tasks)
        runMaintenance(transition.detached.maintenance)
    }

    private fun handleInitializationTimeout(session: Session) {
        val transition = synchronized(lock) {
            if (current !== session || !controller.isInitializing(session.lifecycleToken)) {
                record(session.lifecycleToken, NavigationRuntimeStage.STALE_IGNORED)
                return
            }
            session.initializationTimeoutTask = null
            val update = controller.initializationFailed(session.lifecycleToken, INITIALIZATION_TIMED_OUT)
            if (update !is ControllerUpdate.Applied) return
            record(session.lifecycleToken, NavigationRuntimeStage.TIMED_OUT)
            observer.onCapabilityChanged(false)
            observer.onStateChanged(update.state)
            InitializationFailureTransition(detachLocked(session))
        }
        cancelTasks(transition.detached.tasks)
        runMaintenance(transition.detached.maintenance)
    }

    private fun requestListener(session: Session, token: RequestToken) = object : NavigationVendorListener {
        override fun onRouteStarted() = handleEvent(session, token, VendorEvent.RouteStarted)
        override fun onRoutingCompleted() = handleEvent(session, token, VendorEvent.RoutingCompleted)
        override fun onError(userMessage: String) = handleEvent(session, token, VendorEvent.Error(userMessage))
    }

    private fun handleEvent(session: Session, token: RequestToken, event: VendorEvent) {
        var cancelTimeout: NavigationScheduledTask? = null
        val shouldScheduleSettle = synchronized(lock) {
            if (!isCurrentRequestLocked(session, token)) {
                record(token, NavigationRuntimeStage.STALE_IGNORED)
                return
            }
            val update = when (event) {
                VendorEvent.RouteStarted -> controller.routeStarted(token)
                VendorEvent.RoutingCompleted -> controller.routingCompleted(token)
                is VendorEvent.Error -> controller.requestError(token, event.userMessage)
            }
            if (update == ControllerUpdate.TerminalPending) {
                record(
                    token,
                    if (event is VendorEvent.RoutingCompleted) NavigationRuntimeStage.TERMINAL_COMPLETED
                    else NavigationRuntimeStage.TERMINAL_ERROR,
                )
                cancelTimeout = session.request?.timeoutTask
                session.request?.timeoutTask = null
                session.request?.settleTask == null
            } else false
        }
        cancelTimeout?.cancel()
        if (!shouldScheduleSettle) return
        val task = runCatching {
            session.resources.scheduleScope.schedule(minimumFeedbackMillis) { handleSettle(session, token) }
        }.getOrElse {
            handleInvocationFailure(session, token)
            return
        }
        val kept = synchronized(lock) {
            val request = session.request
            if (request != null && isCurrentRequestLocked(session, token) && request.settleTask == null) {
                request.settleTask = task
                true
            } else false
        }
        if (!kept) task.cancel()
    }

    private fun handleSettle(session: Session, token: RequestToken) {
        val transition = synchronized(lock) {
            if (!isCurrentRequestLocked(session, token)) return
            session.request?.settleTask = null
            val update = controller.settle(token)
            if (update !is ControllerUpdate.Applied || !update.terminal) return
            finishRequestLocked(session, token, update)
        }
        completeRequest(transition)
    }

    private fun handleTimeout(session: Session, token: RequestToken) {
        val transition = synchronized(lock) {
            if (!isCurrentRequestLocked(session, token)) return
            session.request?.timeoutTask = null
            val update = controller.timeout(token, REQUEST_TIMED_OUT)
            if (update !is ControllerUpdate.Applied || !update.terminal) return
            record(token, NavigationRuntimeStage.TIMED_OUT)
            finishRequestLocked(session, token, update)
        }
        completeRequest(transition)
    }

    private fun handleInvocationFailure(session: Session, token: RequestToken) {
        val transition = synchronized(lock) {
            if (!isCurrentRequestLocked(session, token)) return
            val update = controller.invocationFailed(token, REQUEST_FAILED)
            if (update !is ControllerUpdate.Applied || !update.terminal) return
            finishRequestLocked(session, token, update)
        }
        completeRequest(transition)
    }

    private fun finishRequestLocked(
        session: Session,
        token: RequestToken,
        update: ControllerUpdate.Applied,
    ): RequestCompletion {
        val request = requireNotNull(session.request)
        session.request = null
        val tasks = listOfNotNull(request.timeoutTask, request.settleTask)
        if (request.listenerAdded) session.pendingRemovals += ListenerRemoval(request.listener, request.token)
        val maintenance = claimMaintenanceLocked(session)
        return RequestCompletion(session, token, tasks, maintenance, update.state)
    }

    private fun completeRequest(completion: RequestCompletion) {
        cancelTasks(completion.tasks)
        val cleaned = runMaintenance(completion.maintenance)
        val stillCurrent = synchronized(lock) {
            if (current !== completion.session || completion.session.detached) {
                record(completion.token, NavigationRuntimeStage.STALE_IGNORED)
                false
            } else {
                observer.onStateChanged(
                    if (cleaned) completion.terminalState else NavigationState.Failed(CLEANUP_FAILED),
                )
                true
            }
        }
        if (stillCurrent) scheduleQuarantine(completion.session, completion.token)
    }

    private fun scheduleQuarantine(session: Session, token: RequestToken) {
        val canSchedule = synchronized(lock) { current === session && !session.detached }
        if (!canSchedule) return
        val task = runCatching {
            session.resources.scheduleScope.schedule(staleEventQuarantineMillis) {
                synchronized(lock) {
                    if (current !== session) return@synchronized
                    controller.endQuarantine(token)
                    session.quarantineTask = null
                }
            }
        }.getOrElse {
            synchronized(lock) { if (current === session) controller.endQuarantine(token) }
            return
        }
        val kept = synchronized(lock) {
            if (current === session && !session.detached) {
                session.quarantineTask?.cancel()
                session.quarantineTask = task
                true
            } else false
        }
        if (!kept) task.cancel()
    }

    private fun invokeVendor(session: Session, operation: (NavigationVendorPort) -> Unit): VendorInvocation {
        val started = synchronized(lock) {
            if (session.detached || session.disposed) false else {
                session.vendorCalls += 1
                true
            }
        }
        if (!started) return VendorInvocation(started = false, success = false)
        val result = runCatching {
            synchronized(session.vendorLock) { operation(session.resources.port) }
        }
        val maintenance = synchronized(lock) {
            session.vendorCalls -= 1
            claimMaintenanceLocked(session)
        }
        runMaintenance(maintenance)
        return VendorInvocation(started = true, success = result.isSuccess)
    }

    private fun detachLocked(session: Session): DetachedSession {
        if (current === session) current = null
        session.detached = true
        session.disposeRequested = true
        val request = session.request
        session.request = null
        val tasks = listOfNotNull(
            request?.timeoutTask,
            request?.settleTask,
            session.initializationTimeoutTask,
            session.quarantineTask,
        )
        session.initializationTimeoutTask = null
        session.quarantineTask = null
        if (request?.listenerAdded == true) {
            session.pendingRemovals += ListenerRemoval(request.listener, request.token)
        }
        return DetachedSession(tasks, claimMaintenanceLocked(session))
    }

    private fun claimMaintenanceLocked(session: Session): VendorMaintenance? {
        if (session.vendorCalls != 0) return null
        val listeners = session.pendingRemovals.toList()
        session.pendingRemovals.clear()
        val shouldRelease = session.disposeRequested && !session.disposed
        if (shouldRelease) session.disposed = true
        if (listeners.isEmpty() && !shouldRelease) return null
        return VendorMaintenance(session, listeners, shouldRelease)
    }

    private fun runMaintenance(maintenance: VendorMaintenance?): Boolean {
        if (maintenance == null) return true
        var successful = true
        synchronized(maintenance.session.vendorLock) {
            maintenance.listeners.forEach { listener ->
                runCatching { maintenance.session.resources.port.removeListener(listener.listener) }
                    .onSuccess { record(listener.token, NavigationRuntimeStage.LISTENER_REMOVED) }
                    .onFailure { successful = false }
            }
            if (maintenance.release) {
                runCatching { maintenance.session.resources.port.release() }
                    .onSuccess { record(maintenance.session.lifecycleToken, NavigationRuntimeStage.RELEASED) }
                    .onFailure { successful = false }
            }
        }
        if (maintenance.release) maintenance.session.resources.scheduleScope.cancelAll()
        if (!successful) {
            synchronized(lock) { cleanupFailed = true }
            record(maintenance.session.lifecycleToken, NavigationRuntimeStage.CLEANUP_FAILED)
            observer.onCapabilityChanged(false)
            observer.onStateChanged(NavigationState.Failed(CLEANUP_FAILED))
        }
        return successful
    }

    private fun releaseOrphan(resources: NavigationVendorSession, token: LifecycleToken) {
        runCatching { resources.port.release() }
            .onSuccess { record(token, NavigationRuntimeStage.RELEASED) }
            .onFailure {
                synchronized(lock) { cleanupFailed = true }
                record(token, NavigationRuntimeStage.CLEANUP_FAILED)
                observer.onCapabilityChanged(false)
                observer.onStateChanged(NavigationState.Failed(CLEANUP_FAILED))
            }
        resources.scheduleScope.cancelAll()
    }

    private fun isCurrentRequestLocked(session: Session, token: RequestToken): Boolean =
        current === session && !session.detached && session.request?.token == token

    private fun cancelTasks(tasks: List<NavigationScheduledTask>) = tasks.forEach { it.cancel() }

    private data class Session(
        val lifecycleToken: LifecycleToken,
        val resources: NavigationVendorSession,
        val vendorLock: Any = Any(),
        val pendingRemovals: MutableSet<ListenerRemoval> = linkedSetOf(),
        var request: RequestContext? = null,
        var initializationTimeoutTask: NavigationScheduledTask? = null,
        var quarantineTask: NavigationScheduledTask? = null,
        var vendorCalls: Int = 0,
        var detached: Boolean = false,
        var disposeRequested: Boolean = false,
        var disposed: Boolean = false,
    )

    private data class RequestContext(
        val token: RequestToken,
        val listener: NavigationVendorListener,
        var listenerAdded: Boolean = false,
        var timeoutTask: NavigationScheduledTask? = null,
        var settleTask: NavigationScheduledTask? = null,
    )

    private data class InitializationTransition(val token: LifecycleToken, val old: DetachedSession?)
    private data class InitializationFailureTransition(val detached: DetachedSession)
    private data class ReleaseTransition(val detached: DetachedSession?)
    private data class AcceptedRequest(val session: Session, val request: RequestContext)
    private data class RequestCompletion(
        val session: Session,
        val token: RequestToken,
        val tasks: List<NavigationScheduledTask>,
        val maintenance: VendorMaintenance?,
        val terminalState: NavigationState,
    )
    private data class DetachedSession(val tasks: List<NavigationScheduledTask>, val maintenance: VendorMaintenance?)
    private data class VendorMaintenance(
        val session: Session,
        val listeners: List<ListenerRemoval>,
        val release: Boolean,
    )
    private data class ListenerRemoval(val listener: NavigationVendorListener, val token: RequestToken)
    private data class VendorInvocation(val started: Boolean, val success: Boolean)

    private sealed interface VendorEvent {
        data object RouteStarted : VendorEvent
        data object RoutingCompleted : VendorEvent
        data class Error(val userMessage: String) : VendorEvent
    }

    private fun record(token: LifecycleToken, stage: NavigationRuntimeStage) =
        evidence.record(NavigationRuntimeEvidence(token.epoch, 0L, stage))

    private fun record(token: RequestToken, stage: NavigationRuntimeStage) =
        evidence.record(NavigationRuntimeEvidence(token.lifecycleEpoch, token.generation, stage))

    private companion object {
        const val CONNECTION_UNAVAILABLE = "차량 내비게이션 연결을 확인해 주세요."
        const val INITIALIZATION_FAILED = "차량 내비게이션에 연결할 수 없어요."
        const val INITIALIZATION_TIMED_OUT = "차량 내비게이션 연결이 지연되고 있어요."
        const val PERMISSION_DENIED = "차량 내비게이션 권한을 확인해 주세요."
        const val CLEANUP_FAILED = "차량 내비게이션 연결을 안전하게 정리하지 못했어요."
        const val REQUEST_FAILED = "경로 요청을 전달하지 못했어요. 잠시 후 다시 시도해 주세요."
        const val REQUEST_FAILED_SHORT = "경로 요청을 전달하지 못했어요."
        const val REQUEST_TIMED_OUT = "차량 내비게이션 응답이 지연되고 있어요. 다시 시도해 주세요."
    }
}
