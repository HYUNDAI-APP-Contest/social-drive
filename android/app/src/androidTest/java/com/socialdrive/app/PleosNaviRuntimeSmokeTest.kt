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
                    gateway.state.filter { it is NavigationState.Active || it is NavigationState.Failed }.first()
                }
                assertTrue(terminal is NavigationState.Active)
                Log.i(LOG_TAG, STAGE_TERMINAL)
            }

            val stages = evidence.navigationRuntimeEvidence().map { it.stage }
            assertOrdered(
                stages,
                NavigationRuntimeStage.INITIALIZE_STARTED,
                NavigationRuntimeStage.INITIALIZE_CONNECTED,
                NavigationRuntimeStage.REQUEST_VALIDATED,
                NavigationRuntimeStage.REQUEST_DISPATCHED,
                NavigationRuntimeStage.TERMINAL_COMPLETED,
                NavigationRuntimeStage.LISTENER_REMOVED,
            )
            assertFalse(stages.contains(NavigationRuntimeStage.CLEANUP_FAILED))
        } finally {
            releaseWithinDeadline(gateway::release)
            val stages = evidence.navigationRuntimeEvidence().map { it.stage }
            assertTrue(stages.contains(NavigationRuntimeStage.RELEASED))
            assertFalse(stages.contains(NavigationRuntimeStage.CLEANUP_FAILED))
            Log.i(LOG_TAG, STAGE_RELEASED)
        }
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
