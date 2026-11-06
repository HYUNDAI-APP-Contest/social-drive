package com.socialdrive.app.integration

import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.NavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationGatewayCoordinatorTest {
    @Test
    fun `permission denial records fail closed evidence without creating helper`() {
        val harness = Harness()

        harness.coordinator.initialize(permissionGranted = false)

        assertTrue(harness.ports.isEmpty())
        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertEquals(
            listOf(NavigationRuntimeStage.INITIALIZE_STARTED, NavigationRuntimeStage.PERMISSION_DENIED),
            harness.evidence.events.map { it.stage },
        )
    }

    @Test
    fun `missing initialization callback times out releases and ignores late callback`() {
        lateinit var callback: NavigationInitializationCallback
        val harness = Harness { port -> port.initializeAction = { callback = it } }

        harness.coordinator.initialize()
        harness.scheduler.advanceBy(10_000)

        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertEquals(listOf("release"), harness.ports.single().events)
        assertTrue(NavigationRuntimeStage.TIMED_OUT in harness.evidence.stages())
        assertTrue(NavigationRuntimeStage.RELEASED in harness.evidence.stages())

        callback.onConnected()
        assertIs<NavigationState.Failed>(harness.observer.states.last())
    }

    @Test
    fun `connected then late initialization failure is ignored and release is not recursive`() {
        val harness = Harness { port ->
            port.initializeAction = { callback ->
                port.events += "initialize:start"
                callback.onConnected()
                callback.onFailed()
                port.events += "initialize:end"
            }
        }

        harness.coordinator.initialize()
        val port = harness.ports.single()

        assertEquals(listOf("initialize:start", "initialize:end"), port.events)
        assertEquals(listOf(false, true), harness.observer.capabilities)
        assertIs<NavigationState.Idle>(harness.observer.states.last())

        harness.coordinator.release()
        assertEquals("release", port.events.last())
    }

    @Test
    fun `synchronous initialization failure releases only after callback returns`() {
        val harness = Harness { port ->
            port.initializeAction = { callback ->
                port.events += "initialize:start"
                callback.onFailed()
                port.events += "initialize:end"
            }
        }

        harness.coordinator.initialize()

        assertEquals(listOf("initialize:start", "initialize:end", "release"), harness.ports.single().events)
        assertIs<NavigationState.Failed>(harness.observer.states.last())
    }

    @Test
    fun `success at fourteen point nine seconds cancels timeout before settle`() {
        val harness = Harness()
        harness.coordinator.initialize()
        val port = harness.ports.single()
        assertEquals(NavigationRequestResult.Accepted, harness.coordinator.requestRoute(request("one", "늦은 성공")))

        harness.scheduler.advanceBy(14_900)
        port.listener!!.onRouteStarted()
        port.listener!!.onRoutingCompleted()
        harness.scheduler.advanceBy(100)

        assertIs<NavigationState.Requesting>(harness.observer.states.last())
        harness.scheduler.advanceBy(150)
        assertEquals(NavigationState.Active("늦은 성공"), harness.observer.states.last())
        assertTrue("removeListener" in port.events)
    }

    @Test
    fun `synchronous request callbacks settle once after vendor call returns`() {
        val harness = Harness { port ->
            port.requestAction = { listener ->
                port.events += "callback:start"
                listener.onRouteStarted()
                listener.onRoutingCompleted()
                listener.onError("late duplicate")
                port.events += "callback:end"
            }
        }
        harness.coordinator.initialize()
        val port = harness.ports.single()

        assertEquals(NavigationRequestResult.Accepted, harness.coordinator.requestRoute(request("sync", "동기 경로")))
        assertIs<NavigationState.Requesting>(harness.observer.states.last())
        assertTrue("callback:end" in port.events)
        assertTrue("removeListener" !in port.events)

        harness.scheduler.advanceBy(250)
        assertEquals(NavigationState.Active("동기 경로"), harness.observer.states.last())
        assertEquals(1, port.events.count { it == "removeListener" })
        assertEquals(
            listOf(
                NavigationRuntimeStage.INITIALIZE_STARTED,
                NavigationRuntimeStage.INITIALIZE_CONNECTED,
                NavigationRuntimeStage.REQUEST_VALIDATED,
                NavigationRuntimeStage.REQUEST_DISPATCHED,
                NavigationRuntimeStage.TERMINAL_COMPLETED,
                NavigationRuntimeStage.LISTENER_REMOVED,
            ),
            harness.evidence.stages(),
        )
    }

    @Test
    fun `listener removal failure prevents active success and release failure is visible`() {
        val harness = Harness { port ->
            port.removeFailure = true
            port.releaseFailure = true
        }
        harness.coordinator.initialize()
        val port = harness.ports.single()
        harness.coordinator.requestRoute(request("cleanup", "정리 검증"))

        requireNotNull(port.listener).onRoutingCompleted()
        harness.scheduler.advanceBy(250)

        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertTrue(NavigationRuntimeStage.CLEANUP_FAILED in harness.evidence.stages())
        assertTrue(NavigationRuntimeStage.LISTENER_REMOVED !in harness.evidence.stages())

        harness.coordinator.release()
        harness.coordinator.release()
        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertEquals(2, harness.evidence.stages().count { it == NavigationRuntimeStage.CLEANUP_FAILED })
        assertTrue(NavigationRuntimeStage.RELEASED !in harness.evidence.stages())
    }

    @Test
    fun `error is terminal once and stale completion cannot become active`() {
        val harness = Harness()
        harness.coordinator.initialize()
        val port = harness.ports.single()
        harness.coordinator.requestRoute(request("error", "오류 경로"))
        val listener = requireNotNull(port.listener)

        listener.onError("안전한 오류")
        listener.onRoutingCompleted()
        harness.scheduler.advanceBy(250)

        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertEquals(1, harness.evidence.stages().count { it == NavigationRuntimeStage.TERMINAL_ERROR })
        assertEquals(0, harness.evidence.stages().count { it == NavigationRuntimeStage.TERMINAL_COMPLETED })
        assertNull(port.listener)
    }

    @Test
    fun `missing callback times out quarantines stale listener and then permits replay`() {
        val harness = Harness()
        harness.coordinator.initialize()
        val port = harness.ports.single()
        assertEquals(NavigationRequestResult.Accepted, harness.coordinator.requestRoute(request("old", "이전 경로")))
        val stale = requireNotNull(port.listener)

        harness.scheduler.advanceBy(15_000)
        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertEquals(NavigationRequestResult.Busy, harness.coordinator.requestRoute(request("blocked", "격리 중")))
        harness.scheduler.advanceBy(750)
        assertEquals(NavigationRequestResult.Accepted, harness.coordinator.requestRoute(request("new", "새 경로")))

        stale.onRoutingCompleted()
        assertIs<NavigationState.Requesting>(harness.observer.states.last())
        requireNotNull(port.listener).onRoutingCompleted()
        harness.scheduler.advanceBy(250)
        assertEquals(NavigationState.Active("새 경로"), harness.observer.states.last())
    }

    @Test
    fun `request exception removes listener cancels timeout and recovers after quarantine`() {
        val harness = Harness { port -> port.requestAction = { throw IllegalStateException("sdk failure") } }
        harness.coordinator.initialize()
        val port = harness.ports.single()

        assertIs<NavigationRequestResult.Rejected>(harness.coordinator.requestRoute(request("failed", "실패 경로")))
        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertTrue("removeListener" in port.events)
        harness.scheduler.advanceBy(15_000)
        assertIs<NavigationState.Failed>(harness.observer.states.last())
    }

    @Test
    fun `listener registration exception fails closed without dispatching request`() {
        val harness = Harness { port -> port.addFailure = true }
        harness.coordinator.initialize()
        val port = harness.ports.single()

        assertIs<NavigationRequestResult.Rejected>(
            harness.coordinator.requestRoute(request("listener", "등록 실패")),
        )

        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertEquals(0, port.events.count { it == "requestRoute" })
        assertTrue(NavigationRuntimeStage.REQUEST_DISPATCHED !in harness.evidence.stages())
    }

    @Test
    fun `route started without terminal callback times out and removes listener`() {
        val harness = Harness()
        harness.coordinator.initialize()
        val port = harness.ports.single()
        harness.coordinator.requestRoute(request("started", "시작만 수신"))

        requireNotNull(port.listener).onRouteStarted()
        harness.scheduler.advanceBy(15_000)

        assertIs<NavigationState.Failed>(harness.observer.states.last())
        assertTrue(NavigationRuntimeStage.TIMED_OUT in harness.evidence.stages())
        assertEquals(1, port.events.count { it == "removeListener" })
        assertNull(port.listener)
    }

    @Test
    fun `reinitialize creates a new helper and active release removes listener before release`() {
        val harness = Harness()
        harness.coordinator.initialize()
        val first = harness.ports.single()
        harness.coordinator.requestRoute(request("active", "진행 중"))

        harness.coordinator.initialize()
        val second = harness.ports.last()

        assertNotSame(first, second)
        assertTrue(first.events.indexOf("removeListener") < first.events.indexOf("release"))
        assertEquals("initialize", second.events.first())
        assertTrue(harness.schedulers.first().cancelledAll)
    }

    private fun request(id: String, label: String) = NavigationRequest(
        requestId = id,
        destination = NavigationPlace(label, 0.0, 0.0),
    )

    private class Harness(configure: (FakePort) -> Unit = {}) {
        val observer = RecordingObserver()
        val evidence = RecordingEvidence()
        val ports = mutableListOf<FakePort>()
        val schedulers = mutableListOf<ManualScheduleScope>()
        val coordinator = NavigationGatewayCoordinator(
            vendorSessionFactory = {
                val port = FakePort().also(configure).also(ports::add)
                val scheduler = ManualScheduleScope().also(schedulers::add)
                NavigationVendorSession(port, scheduler)
            },
            observer = observer,
            disconnectedState = NavigationState.Idle(
                CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Actual, "연결 안 됨"),
            ),
            connectedState = NavigationState.Idle(
                CapabilityStatus(CapabilityState.Available, IntegrationSource.Actual, "실차"),
            ),
            evidence = evidence,
        )

        val scheduler: ManualScheduleScope get() = schedulers.last()
    }

    private class RecordingEvidence : NavigationRuntimeEvidenceSink {
        val events = mutableListOf<NavigationRuntimeEvidence>()
        override fun record(evidence: NavigationRuntimeEvidence) { events += evidence }
        fun stages() = events.map { it.stage }
    }

    private class RecordingObserver : NavigationCoordinatorObserver {
        val capabilities = mutableListOf<Boolean>()
        val states = mutableListOf<NavigationState>()
        override fun onCapabilityChanged(available: Boolean) { capabilities += available }
        override fun onStateChanged(state: NavigationState) { states += state }
    }

    private class FakePort : NavigationVendorPort {
        val events = mutableListOf<String>()
        var listener: NavigationVendorListener? = null
        var initializeAction: (NavigationInitializationCallback) -> Unit = { callback ->
            events += "initialize"
            callback.onConnected()
        }
        var requestAction: (NavigationVendorListener) -> Unit = {}
        var addFailure = false
        var removeFailure = false
        var releaseFailure = false

        override fun initialize(callback: NavigationInitializationCallback) = initializeAction(callback)
        override fun addListener(listener: NavigationVendorListener) {
            events += "addListener"
            if (addFailure) throw IllegalStateException("add failed")
            this.listener = listener
        }
        override fun requestRoute(request: NavigationRequest) {
            events += "requestRoute"
            requestAction(requireNotNull(listener))
        }
        override fun removeListener(listener: NavigationVendorListener) {
            events += "removeListener"
            if (removeFailure) throw IllegalStateException("remove failed")
            if (this.listener === listener) this.listener = null
        }
        override fun release() {
            events += "release"
            if (releaseFailure) throw IllegalStateException("release failed")
        }
    }

    private class ManualScheduleScope : NavigationScheduleScope {
        private var now = 0L
        private val tasks = mutableListOf<ManualTask>()
        var cancelledAll = false

        override fun schedule(delayMillis: Long, task: () -> Unit): NavigationScheduledTask {
            val scheduled = ManualTask(now + delayMillis, task)
            tasks += scheduled
            return NavigationScheduledTask { scheduled.cancelled = true }
        }

        override fun cancelAll() {
            cancelledAll = true
            tasks.forEach { it.cancelled = true }
        }

        fun advanceBy(deltaMillis: Long) {
            val target = now + deltaMillis
            while (true) {
                val next = tasks.filter { !it.cancelled && !it.ran && it.at <= target }.minByOrNull { it.at } ?: break
                now = next.at
                next.ran = true
                next.task()
            }
            now = target
        }

        private data class ManualTask(
            val at: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
            var ran: Boolean = false,
        )
    }
}
