package com.socialdrive.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class RoutePrivacyTest {
    @Test
    fun `unavailable source never persists or displays granted consent`() = runBlocking {
        LiveUnavailableReason.entries.forEach { reason ->
            val decision = RouteConsentReducer.reduce(
                RouteConsentState.NotAsked,
                RouteSource.LiveUnavailable(reason),
                RouteConsentAction.Grant,
            )
            assertEquals(RouteConsentState.NotAsked, decision.state)
            assertEquals(false, decision.shouldPersist)
            assertEquals(reason, decision.unavailableReason)
        }
        val harness = Harness()
        val decision = harness.coordinator.grant(RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON))
        assertEquals(false, decision.shouldPersist)
        assertEquals(RouteConsentState.NotAsked, harness.coordinator.consentState)
        assertEquals(emptyList(), harness.events)
    }

    @Test
    fun `consent reducer follows not asked granted withdrawn lifecycle`() {
        val granted = RouteConsentReducer.reduce(RouteConsentState.NotAsked, RouteSource.DemoPreset, RouteConsentAction.Grant)
        val withdrawn = RouteConsentReducer.reduce(granted.state, RouteSource.DemoPreset, RouteConsentAction.Withdraw)
        assertEquals(RouteConsentState.Granted, granted.state)
        assertEquals(RouteConsentState.Withdrawn, withdrawn.state)
    }

    @Test
    fun `normalized points reject non finite and out of range values`() {
        assertFailsWith<IllegalArgumentException> { NormalizedRoutePoint(0, Float.NaN, .5f) }
        assertFailsWith<IllegalArgumentException> { NormalizedRoutePoint(0, -.1f, .5f) }
        assertFailsWith<IllegalArgumentException> { NormalizedRoutePoint(0, .5f, 1.1f) }
    }

    @Test
    fun `raw coordinates become bounded normalized artwork points`() {
        val normalized = RouteNormalizer.normalize(
            listOf(
                RawRouteCoordinate(126.9, 37.4),
                RawRouteCoordinate(127.0, 37.5),
                RawRouteCoordinate(127.1, 37.6),
            ),
        )
        assertEquals(listOf(0f, .5f, 1f), normalized.map { it.horizontal })
        assertEquals(listOf(1f, .5f, 0f), normalized.map { it.vertical })
        assertEquals(listOf(0, 1, 2), normalized.map { it.sequence })
    }

    @Test
    fun `연속 중복 위치 콜백은 경로점 하나로 축약한다`() {
        val start = RawRouteCoordinate(126.9, 37.4)
        val end = RawRouteCoordinate(127.1, 37.6)

        val normalized = RouteNormalizer.normalize(listOf(start, start, start, end, end))

        assertEquals(2, normalized.size)
        assertEquals(listOf(0, 1), normalized.map { it.sequence })
        assertEquals(listOf(0f, 1f), normalized.map { it.horizontal })
    }

    @Test
    fun `원시 위치는 저장하지 않고 실제 이동 거리만 미터로 집계한다`() {
        val distance = RouteDistance.meters(
            listOf(
                RawRouteCoordinate(126.9780, 37.5665),
                RawRouteCoordinate(126.9790, 37.5665),
            ),
        )

        assertEquals(true, distance in 87..90)
        assertEquals(0, RouteDistance.meters(emptyList()))
    }

    @Test
    fun `위경도 범위를 벗어난 원시 위치는 거부한다`() {
        assertFailsWith<IllegalArgumentException> { RawRouteCoordinate(181.0, 37.0) }
        assertFailsWith<IllegalArgumentException> { RawRouteCoordinate(127.0, 91.0) }
        assertEquals(null, RawRouteCoordinate.fromOrNull(Double.NaN, 37.0))
        assertEquals(null, RawRouteCoordinate.fromOrNull(181.0, 37.0))
        assertEquals(null, RawRouteCoordinate.fromOrNull(127.0, -91.0))
        assertEquals(RawRouteCoordinate(127.0, 37.0), RawRouteCoordinate.fromOrNull(127.0, 37.0))
    }

    @Test
    fun `오래된 첫 위치의 비현실적인 점프는 실제 연속 경로에서 제외한다`() {
        val stale = RawRouteCoordinate(127.20, 37.40)
        val current = listOf(
            RawRouteCoordinate(126.9780, 37.5665),
            RawRouteCoordinate(126.9790, 37.5665),
            RawRouteCoordinate(126.9800, 37.5665),
        )

        val track = RouteDistance.plausibleTrack(listOf(stale) + current)

        assertEquals(current, track)
        assertEquals(true, RouteDistance.meters(track) in 175..178)
    }

    @Test
    fun `장시간 위치 버퍼는 상한 안에서 출발점과 최신점을 보존한다`() {
        val buffer = BoundedRouteBuffer(5)
        val points = (0..12).map { index -> RawRouteCoordinate(126.0 + index / 100.0, 37.0) }

        points.forEach(buffer::offer)

        val retained = buffer.snapshot()
        assertEquals(true, retained.size <= 5)
        assertEquals(points.first(), retained.first())
        assertEquals(points.last(), retained.last())
        assertEquals(retained, retained.sortedBy(RawRouteCoordinate::longitude))
        assertEquals(retained, buffer.snapshotAndClear())
        assertEquals(emptyList(), buffer.snapshot())
    }

    @Test
    fun `위치 버퍼는 의미 있는 최소 상한을 요구한다`() {
        assertFailsWith<IllegalArgumentException> { BoundedRouteBuffer(2) }
    }

    @Test
    fun `future fake collector receives zero points before explicit consent`() = runBlocking {
        val harness = Harness()
        harness.collector.offer(NormalizedRoutePoint(0, .2f, .3f))
        assertEquals(0, harness.collector.points.size)

        val source = RouteSource.LiveAvailable
        harness.coordinator.grant(source)
        harness.collector.offer(NormalizedRoutePoint(0, .2f, .3f))
        assertEquals(1, harness.collector.points.size)
    }

    @Test
    fun `withdrawal stops collector then atomically purges and tombstones before cancel`() = runBlocking {
        val harness = Harness()
        harness.coordinator.grant(RouteSource.LiveAvailable)
        harness.collector.offer(NormalizedRoutePoint(0, .2f, .3f))
        val receipt = harness.coordinator.withdraw()

        assertEquals(listOf("consent:Granted", "collector:start", "collector:stop", "store:withdraw", "cancel:draft-1"), harness.events)
        assertEquals(1, receipt.purgedUnpublishedPointCount)
        assertEquals(0, harness.collector.points.size)
        assertEquals(RouteConsentState.Withdrawn, harness.coordinator.consentState)
    }

    @Test
    fun `late fake point after withdrawal is ignored`() = runBlocking {
        val harness = Harness()
        harness.coordinator.grant(RouteSource.LiveAvailable)
        harness.coordinator.withdraw()
        harness.collector.offer(NormalizedRoutePoint(1, .4f, .6f))
        assertEquals(0, harness.collector.points.size)
    }

    @Test
    fun `parked published delete requires confirmation and writes local pending state`() = runBlocking {
        val harness = Harness()
        assertEquals(
            PublishedDeleteDecision.NotPublished,
            harness.coordinator.requestPublishedDelete("draft-1", false, true) { MotionState.Parked },
        )
        assertEquals(
            PublishedDeleteDecision.ConfirmationRequired,
            harness.coordinator.requestPublishedDelete("draft-1", true, false) { MotionState.Parked },
        )
        assertEquals(
            PublishedDeleteDecision.DeletePending,
            harness.coordinator.requestPublishedDelete("draft-1", true, true) { MotionState.Parked },
        )
        assertEquals(listOf("delete-pending:draft-1"), harness.store.deleteEvents)
    }

    @Test
    fun `driving direct delete call is rejected without persistence`() = runBlocking {
        val harness = Harness()
        assertEquals(
            PublishedDeleteDecision.MotionRejected(MotionState.Driving),
            harness.coordinator.requestPublishedDelete("draft-1", true, true) { MotionState.Driving },
        )
        assertEquals(emptyList(), harness.store.deleteEvents)
    }

    @Test
    fun `unknown direct delete call is rejected without persistence`() = runBlocking {
        val harness = Harness()
        assertEquals(
            PublishedDeleteDecision.MotionRejected(MotionState.Unknown),
            harness.coordinator.requestPublishedDelete("draft-1", true, true) { MotionState.Unknown },
        )
        assertEquals(emptyList(), harness.store.deleteEvents)
    }

    @Test
    fun `parked dialog changing to driving before confirm is revalidated and rejected`() = runBlocking {
        val harness = Harness()
        var motion = MotionState.Parked
        assertEquals(
            PublishedDeleteDecision.ConfirmationRequired,
            harness.coordinator.requestPublishedDelete("draft-1", true, false) { motion },
        )
        motion = MotionState.Driving
        assertEquals(
            PublishedDeleteDecision.MotionRejected(MotionState.Driving),
            harness.coordinator.requestPublishedDelete("draft-1", true, true) { motion },
        )
        assertEquals(emptyList(), harness.store.deleteEvents)
    }

    private class Harness {
        val events = mutableListOf<String>()
        val collector = FakeCollector(events)
        val store = FakeStore(events, collector)
        val coordinator = RoutePrivacyCoordinator(
            RouteConsentState.NotAsked,
            collector,
            store,
            PendingRouteUploadCanceller { ids -> events += "cancel:${ids.sorted().joinToString()}" },
        )
    }

    private class FakeCollector(private val events: MutableList<String>) : FutureRouteCollector {
        var active = false
        val points = mutableListOf<NormalizedRoutePoint>()
        override suspend fun start() { active = true; events += "collector:start" }
        override suspend fun beginRecording() { points.clear() }
        override suspend fun snapshotAndClear(): CollectedRoute =
            CollectedRoute(points.toList().also { points.clear() }, 0)
        override suspend fun stopAndAwait() { active = false; events += "collector:stop" }
        fun offer(point: NormalizedRoutePoint) { if (active) points += point }
    }

    private class FakeStore(
        private val events: MutableList<String>,
        private val collector: FakeCollector,
    ) : RoutePrivacyMutationStore {
        val deleteEvents = mutableListOf<String>()
        override suspend fun persistConsent(state: RouteConsentState) { events += "consent:$state" }
        override suspend fun withdrawAndPurge(): WithdrawalReceipt {
            check(!collector.active)
            val count = collector.points.size
            collector.points.clear()
            events += "store:withdraw"
            return WithdrawalReceipt(count, setOf("draft-1"))
        }
        override suspend fun markPublishedDeletePending(draftId: String): Boolean {
            deleteEvents += "delete-pending:$draftId"
            return true
        }
    }
}
