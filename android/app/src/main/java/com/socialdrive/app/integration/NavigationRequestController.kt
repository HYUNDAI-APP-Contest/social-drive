package com.socialdrive.app.integration

import com.socialdrive.app.domain.NavigationState

/** SDK-independent request correlation for callback APIs that do not return a request id. */
internal class NavigationRequestController(
    private val disconnectedIdleState: NavigationState,
    private val connectedIdleState: NavigationState = disconnectedIdleState,
) {
    private var lifecycleEpoch = 0L
    private var requestGeneration = 0L
    private var initializingEpoch: Long? = null
    private var connectedEpoch: Long? = null
    private var active: ActiveRequest? = null
    private var quarantineToken: RequestToken? = null
    private var currentState: NavigationState = disconnectedIdleState

    @Synchronized
    fun beginInitialization(): LifecycleToken {
        lifecycleEpoch += 1
        initializingEpoch = lifecycleEpoch
        connectedEpoch = null
        active = null
        quarantineToken = null
        currentState = disconnectedIdleState
        return LifecycleToken(lifecycleEpoch)
    }

    @Synchronized
    fun connected(token: LifecycleToken): ControllerUpdate {
        if (token.epoch != lifecycleEpoch || initializingEpoch != token.epoch) return ControllerUpdate.Ignored
        initializingEpoch = null
        connectedEpoch = token.epoch
        currentState = connectedIdleState
        return ControllerUpdate.Applied(currentState, terminal = false)
    }

    @Synchronized
    fun initializationFailed(token: LifecycleToken, userMessage: String): ControllerUpdate {
        if (token.epoch != lifecycleEpoch || initializingEpoch != token.epoch) return ControllerUpdate.Ignored
        initializingEpoch = null
        connectedEpoch = null
        active = null
        currentState = NavigationState.Failed(userMessage)
        return ControllerUpdate.Applied(currentState, terminal = true)
    }

    @Synchronized
    fun beginRequest(requestId: String, label: String): RequestStart {
        if (connectedEpoch != lifecycleEpoch) return RequestStart.Unavailable
        if (active != null || quarantineToken != null) return RequestStart.Busy
        requestGeneration += 1
        val token = RequestToken(lifecycleEpoch, requestGeneration)
        currentState = NavigationState.Requesting(requestId, label)
        active = ActiveRequest(token, label)
        return RequestStart.Accepted(token, currentState)
    }

    @Synchronized
    fun localRejection(userMessage: String): ControllerUpdate {
        if (active != null) return ControllerUpdate.Ignored
        currentState = NavigationState.Failed(userMessage)
        return ControllerUpdate.Applied(currentState, terminal = false)
    }

    @Synchronized
    fun routeStarted(token: RequestToken): ControllerUpdate =
        if (matchesActive(token) && active?.pendingTerminal == null) {
            ControllerUpdate.Awaiting
        } else {
            ControllerUpdate.Ignored
        }

    @Synchronized
    fun routingCompleted(token: RequestToken): ControllerUpdate =
        markTerminalPending(token, NavigationState.Active(active?.label.orEmpty()))

    @Synchronized
    fun requestError(token: RequestToken, userMessage: String): ControllerUpdate =
        markTerminalPending(token, NavigationState.Failed(userMessage))

    @Synchronized
    fun settle(token: RequestToken): ControllerUpdate {
        if (!matchesActive(token)) return ControllerUpdate.Ignored
        val terminal = active?.pendingTerminal ?: return ControllerUpdate.Ignored
        currentState = terminal
        active = null
        quarantineToken = token
        return ControllerUpdate.Applied(currentState, terminal = true)
    }

    @Synchronized
    fun invocationFailed(token: RequestToken, userMessage: String): ControllerUpdate =
        failImmediately(token, userMessage)

    @Synchronized
    fun timeout(token: RequestToken, userMessage: String): ControllerUpdate =
        failImmediately(token, userMessage)

    @Synchronized
    fun endQuarantine(token: RequestToken): ControllerUpdate {
        if (quarantineToken != token) return ControllerUpdate.Ignored
        quarantineToken = null
        return ControllerUpdate.Awaiting
    }

    @Synchronized
    fun release(): ControllerUpdate {
        lifecycleEpoch += 1
        initializingEpoch = null
        connectedEpoch = null
        active = null
        quarantineToken = null
        currentState = disconnectedIdleState
        return ControllerUpdate.Applied(currentState, terminal = true)
    }

    @Synchronized
    fun isCurrent(token: RequestToken): Boolean = matchesActive(token)

    @Synchronized
    fun state(): NavigationState = currentState

    private fun markTerminalPending(token: RequestToken, state: NavigationState): ControllerUpdate {
        if (!matchesActive(token) || active?.pendingTerminal != null) return ControllerUpdate.Ignored
        active = active?.copy(pendingTerminal = state)
        return ControllerUpdate.TerminalPending
    }

    private fun failImmediately(token: RequestToken, userMessage: String): ControllerUpdate {
        if (!matchesActive(token)) return ControllerUpdate.Ignored
        currentState = NavigationState.Failed(userMessage)
        active = null
        quarantineToken = token
        return ControllerUpdate.Applied(currentState, terminal = true)
    }

    private fun matchesActive(token: RequestToken): Boolean =
        connectedEpoch == lifecycleEpoch && active?.token == token

    private data class ActiveRequest(
        val token: RequestToken,
        val label: String,
        val pendingTerminal: NavigationState? = null,
    )
}

internal data class LifecycleToken(val epoch: Long)
internal data class RequestToken(val lifecycleEpoch: Long, val generation: Long)

internal sealed interface RequestStart {
    data class Accepted(val token: RequestToken, val state: NavigationState) : RequestStart
    data object Busy : RequestStart
    data object Unavailable : RequestStart
}

internal sealed interface ControllerUpdate {
    data class Applied(val state: NavigationState, val terminal: Boolean) : ControllerUpdate
    data object Awaiting : ControllerUpdate
    data object TerminalPending : ControllerUpdate
    data object Ignored : ControllerUpdate
}
