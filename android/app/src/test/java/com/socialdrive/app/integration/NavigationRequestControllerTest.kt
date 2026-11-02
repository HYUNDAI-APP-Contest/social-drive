package com.socialdrive.app.integration

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.NavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NavigationRequestControllerTest {
    private val unavailable = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "unavailable")
    private val idle = NavigationState.Idle(unavailable)

    @Test
    fun `route started then completed settles once and double callbacks are ignored`() {
        val controller = connectedController()
        val request = accepted(controller, "one", "첫 경로")

        assertEquals(ControllerUpdate.Awaiting, controller.routeStarted(request))
        assertEquals(ControllerUpdate.Awaiting, controller.routeStarted(request))
        assertEquals(ControllerUpdate.TerminalPending, controller.routingCompleted(request))
        assertEquals(ControllerUpdate.Ignored, controller.timeout(request, "late timeout"))
        assertEquals(ControllerUpdate.Ignored, controller.requestError(request, "late error"))
        assertEquals(
            ControllerUpdate.Applied(NavigationState.Active("첫 경로"), terminal = true),
            controller.settle(request),
        )
        assertEquals(ControllerUpdate.Ignored, controller.routingCompleted(request))
    }

    @Test
    fun `timeout releases busy state and quarantines late callback from previous generation`() {
        val controller = connectedController()
        val old = accepted(controller, "old", "이전 경로")

        assertIs<RequestStart.Busy>(controller.beginRequest("blocked", "대기"))
        assertIs<ControllerUpdate.Applied>(controller.timeout(old, "시간 초과"))
        assertIs<RequestStart.Busy>(controller.beginRequest("quarantined", "격리 중"))
        assertEquals(ControllerUpdate.Awaiting, controller.endQuarantine(old))
        val current = accepted(controller, "new", "새 경로")

        assertEquals(ControllerUpdate.Ignored, controller.routingCompleted(old))
        assertEquals(ControllerUpdate.Awaiting, controller.routeStarted(current))
        assertEquals(ControllerUpdate.TerminalPending, controller.routingCompleted(current))
        assertEquals(
            ControllerUpdate.Applied(NavigationState.Active("새 경로"), terminal = true),
            controller.settle(current),
        )
    }

    @Test
    fun `release invalidates request callbacks and old initialize callback`() {
        val controller = NavigationRequestController(idle)
        val oldLifecycle = controller.beginInitialization()
        controller.connected(oldLifecycle)
        val oldRequest = accepted(controller, "old", "이전 경로")

        assertEquals(ControllerUpdate.Ignored, controller.connected(oldLifecycle))
        assertEquals(ControllerUpdate.Ignored, controller.initializationFailed(oldLifecycle, "late init failure"))
        assertEquals(ControllerUpdate.TerminalPending, controller.routingCompleted(oldRequest))

        assertIs<ControllerUpdate.Applied>(controller.release())
        assertEquals(ControllerUpdate.Ignored, controller.settle(oldRequest))
        val newLifecycle = controller.beginInitialization()
        assertEquals(ControllerUpdate.Ignored, controller.connected(oldLifecycle))
        assertEquals(ControllerUpdate.Ignored, controller.requestError(oldRequest, "stale"))
        assertIs<ControllerUpdate.Applied>(controller.connected(newLifecycle))
        assertEquals(idle, controller.state())
    }

    @Test
    fun `helper invocation exception clears active request and replays after quarantine`() {
        val controller = connectedController()
        val failed = accepted(controller, "failed", "실패 경로")

        assertEquals(
            ControllerUpdate.Applied(NavigationState.Failed("호출 실패"), terminal = true),
            controller.invocationFailed(failed, "호출 실패"),
        )
        assertEquals(ControllerUpdate.Awaiting, controller.endQuarantine(failed))
        val replay = accepted(controller, "replay", "재시도 경로")
        assertEquals(ControllerUpdate.Awaiting, controller.routeStarted(replay))
    }

    @Test
    fun `initialization failure is epoch scoped and a new lifecycle can recover`() {
        val controller = NavigationRequestController(idle)
        val failed = controller.beginInitialization()
        assertIs<ControllerUpdate.Applied>(controller.initializationFailed(failed, "연결 실패"))
        assertIs<RequestStart.Unavailable>(controller.beginRequest("no", "연결 전"))

        val recovered = controller.beginInitialization()
        assertIs<ControllerUpdate.Applied>(controller.connected(recovered))
        assertIs<RequestStart.Accepted>(controller.beginRequest("yes", "복구 경로"))
    }

    private fun connectedController(): NavigationRequestController =
        NavigationRequestController(idle).also { controller ->
            val lifecycle = controller.beginInitialization()
            assertIs<ControllerUpdate.Applied>(controller.connected(lifecycle))
        }

    private fun accepted(controller: NavigationRequestController, requestId: String, label: String): RequestToken =
        assertIs<RequestStart.Accepted>(controller.beginRequest(requestId, label)).token
}
