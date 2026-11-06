package com.socialdrive.app.integration

import ai.pleos.playground.navi.constants.NaviErrorCode
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PleosNavigationGatewayTest {
    @Test
    fun `invalid production request never registers listener or calls vendor request`() = runBlocking {
        val port = RecordingPort()
        val gateway = PleosNavigationGateway(
            permissionGranted = true,
            vendorSessionFactory = { NavigationVendorSession(port, NoOpScheduleScope) },
        )
        gateway.initialize()

        val result = gateway.requestRoute(
            NavigationRequest(
                requestId = "invalid",
                destination = NavigationPlace("", Double.NaN, Double.POSITIVE_INFINITY),
            ),
        )

        assertIs<NavigationRequestResult.Rejected>(result)
        assertEquals(0, port.addListenerCalls)
        assertEquals(0, port.requestCalls)
        assertTrue(
            gateway.navigationRuntimeEvidence().none {
                it.stage == NavigationRuntimeStage.REQUEST_VALIDATED ||
                    it.stage == NavigationRuntimeStage.REQUEST_DISPATCHED
            },
        )
    }

    @Test
    fun `every unknown vendor error maps to the generic privacy safe message`() {
        val specificallyMapped = setOf(
            NaviErrorCode.CAUSE_LOW_BATTERY,
            NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE,
            NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE,
            NaviErrorCode.CAUSE_SEARCH_ROUTE,
        )
        val unknownCodes = NaviErrorCode.values().filterNot(specificallyMapped::contains)

        assertTrue(unknownCodes.isNotEmpty(), "SDK must expose an unknown-code test fixture")
        unknownCodes.forEach { code ->
            assertEquals("차량 내비게이션에서 경로를 처리하지 못했어요.", code.safeUserMessage())
        }
    }

    private class RecordingPort : NavigationVendorPort {
        var addListenerCalls = 0
        var requestCalls = 0

        override fun initialize(callback: NavigationInitializationCallback) = callback.onConnected()
        override fun addListener(listener: NavigationVendorListener) { addListenerCalls += 1 }
        override fun requestRoute(request: NavigationRequest) { requestCalls += 1 }
        override fun removeListener(listener: NavigationVendorListener) = Unit
        override fun release() = Unit
    }

    private object NoOpScheduleScope : NavigationScheduleScope {
        override fun schedule(delayMillis: Long, task: () -> Unit) = NavigationScheduledTask {}
        override fun cancelAll() = Unit
    }
}
