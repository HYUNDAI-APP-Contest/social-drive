package com.socialdrive.app

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.integration.NavigationRuntimeEvidence
import com.socialdrive.app.integration.NavigationRuntimeEvidenceSource
import com.socialdrive.app.integration.NavigationRuntimeStage
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Opt-in proof that the production Pleos boundary observes and cleans up one NaviHelper route. */
@RunWith(AndroidJUnit4::class)
class PleosNaviRuntimeSmokeTest {
    @Test
    fun productionGatewayObservesCompletedVendorRouteAndCleanup() {
        assumeTrue(
            "Requires an explicitly prepared Pleos Navi runtime",
            InstrumentationRegistry.getArguments().getString(REQUIRE_PLEOS_NAVI_ARGUMENT) == "true",
        )
        assertEquals("pleos", BuildConfig.INTEGRATION_MODE)
        Log.i(LOG_TAG, STAGE_START)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            targetContext.checkSelfPermission(NAVI_ROUTE_SEARCH_PERMISSION),
        )
        val application = targetContext.applicationContext as SocialDriveApplication
        val gateway = application.navigationGateway
        val evidence = gateway as? NavigationRuntimeEvidenceSource
            ?: throw AssertionError("Production navigation evidence boundary is unavailable")

        var primaryFailure: Throwable? = null
        try {
            runBlocking {
                val capability = withTimeout(INITIALIZE_DEADLINE_MILLIS) {
                    gateway.capability
                        .filter { it.capability == CapabilityState.Available || gateway.state.value is NavigationState.Failed }
                        .first()
                }
                assertEquals(CapabilityState.Available, capability.capability)
                Log.i(LOG_TAG, STAGE_INITIALIZED)

                val result = gateway.requestRoute(PRIVACY_REVIEWED_PUBLIC_FIXTURE)
                assertEquals(NavigationRequestResult.Accepted, result)
                Log.i(LOG_TAG, STAGE_REQUESTED)

                val terminal = withTimeout(REQUEST_DEADLINE_MILLIS) {
                    gateway.state.filter { state ->
                        state is NavigationState.Failed ||
                            evidence.navigationRuntimeEvidence().any {
                                it.stage == NavigationRuntimeStage.TERMINAL_COMPLETED
                            }
                    }.first()
                }
                assertTrue(terminal is NavigationState.Active)
                Log.i(LOG_TAG, STAGE_TERMINAL)
            }

            assertSuccessfulRequestEvidence(evidence.navigationRuntimeEvidence())
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = runCatching {
            releaseWithinDeadline(gateway::release)
            assertReleaseEvidence(
                evidence.navigationRuntimeEvidence(),
                requireCompletedRequest = primaryFailure == null,
            )
            Log.i(LOG_TAG, STAGE_RELEASED)
        }.exceptionOrNull()

        primaryFailure?.let { failure ->
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
        cleanupFailure?.let { throw it }
    }

    private fun releaseWithinDeadline(release: () -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val cleanup = thread(name = "pleos-navi-smoke-cleanup", isDaemon = true) {
            try {
                release()
            } catch (cause: Throwable) {
                failure.set(cause)
            } finally {
                completed.countDown()
            }
        }
        if (!completed.await(CLEANUP_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            cleanup.interrupt()
            throw AssertionError("Pleos Navi cleanup exceeded its deadline")
        }
        failure.get()?.let { cause ->
            throw AssertionError("Pleos Navi cleanup failed").apply { initCause(cause) }
        }
    }

    private fun assertSuccessfulRequestEvidence(actual: List<NavigationRuntimeEvidence>) {
        val terminal = actual.single { it.stage == NavigationRuntimeStage.TERMINAL_COMPLETED }
        assertTrue(terminal.lifecycleGeneration > 0L)
        assertTrue(terminal.requestGeneration > 0L)
        val sameLifecycle = actual.filter { it.lifecycleGeneration == terminal.lifecycleGeneration }
        val requestStages = sameLifecycle
            .filter { it.requestGeneration == terminal.requestGeneration }
            .map { it.stage }
        val lifecycleStages = sameLifecycle
            .filter { it.requestGeneration == 0L }
            .map { it.stage }

        assertOrdered(
            lifecycleStages,
            NavigationRuntimeStage.INITIALIZE_STARTED,
            NavigationRuntimeStage.INITIALIZE_CONNECTED,
        )
        assertOrdered(
            requestStages,
            NavigationRuntimeStage.REQUEST_VALIDATED,
            NavigationRuntimeStage.REQUEST_DISPATCHED,
            NavigationRuntimeStage.TERMINAL_COMPLETED,
            NavigationRuntimeStage.LISTENER_REMOVED,
        )
        assertFalse(sameLifecycle.any { it.stage in FAIL_CLOSED_STAGES })
    }

    private fun assertReleaseEvidence(
        actual: List<NavigationRuntimeEvidence>,
        requireCompletedRequest: Boolean,
    ) {
        val terminal = actual.singleOrNull { it.stage == NavigationRuntimeStage.TERMINAL_COMPLETED }
        if (requireCompletedRequest) assertTrue(terminal != null)
        val lifecycleGeneration = terminal?.lifecycleGeneration
            ?: actual.lastOrNull { it.stage == NavigationRuntimeStage.INITIALIZE_STARTED }?.lifecycleGeneration
            ?: throw AssertionError("Pleos Navi lifecycle evidence is unavailable")
        assertTrue(
            actual.any {
                it.lifecycleGeneration == lifecycleGeneration &&
                    it.requestGeneration == 0L &&
                    it.stage == NavigationRuntimeStage.RELEASED
            },
        )
        assertFalse(actual.any { it.stage == NavigationRuntimeStage.CLEANUP_FAILED })
    }

    private fun assertOrdered(actual: List<NavigationRuntimeStage>, vararg required: NavigationRuntimeStage) {
        var cursor = -1
        required.forEach { stage ->
            cursor = ((cursor + 1)..actual.lastIndex).firstOrNull { actual[it] == stage } ?: -1
            assertTrue("Required privacy-safe stage was not observed in order", cursor >= 0)
        }
    }

    private companion object {
        const val REQUIRE_PLEOS_NAVI_ARGUMENT = "requirePleosNavi"
        const val NAVI_ROUTE_SEARCH_PERMISSION = "pleos.car.permission.NAVI_ROUTE_SEARCH"
        const val INITIALIZE_DEADLINE_MILLIS = 20_000L
        const val REQUEST_DEADLINE_MILLIS = 20_000L
        const val CLEANUP_DEADLINE_SECONDS = 20L
        const val LOG_TAG = "PleosNaviSmoke"
        const val STAGE_START = "stage=start"
        const val STAGE_INITIALIZED = "stage=initialized"
        const val STAGE_REQUESTED = "stage=requested"
        const val STAGE_TERMINAL = "stage=terminal_completed"
        const val STAGE_RELEASED = "stage=released"

        val FAIL_CLOSED_STAGES = setOf(
            NavigationRuntimeStage.PERMISSION_DENIED,
            NavigationRuntimeStage.SERVICE_UNAVAILABLE,
            NavigationRuntimeStage.TERMINAL_ERROR,
            NavigationRuntimeStage.TIMED_OUT,
            NavigationRuntimeStage.STALE_IGNORED,
            NavigationRuntimeStage.CLEANUP_FAILED,
        )

        val PRIVACY_REVIEWED_PUBLIC_FIXTURE = NavigationRequest(
            requestId = "pleos-runtime-smoke",
            destination = NavigationPlace(
                name = "공개 검증 목적지",
                latitude = 37.5665,
                longitude = 126.9780,
            ),
        )
    }
}
