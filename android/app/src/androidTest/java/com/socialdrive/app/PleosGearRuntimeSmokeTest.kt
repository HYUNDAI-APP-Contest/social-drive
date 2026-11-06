package com.socialdrive.app

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.MotionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Opt-in evidence that a real Vehicle Gear PARK signal reaches the application boundary.
 * Normal connected suites skip this test because a standard emulator has no Pleos Vehicle Service.
 */
@RunWith(AndroidJUnit4::class)
class PleosGearRuntimeSmokeTest {
    @Test
    fun actualParkSignalReachesApplicationMotionSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Requires an explicitly prepared Pleos Gear runtime",
            InstrumentationRegistry.getArguments().getString(REQUIRE_PLEOS_GEAR_ARGUMENT) == "true",
        )
        Log.i(LOG_TAG, STAGE_START)

        val targetContext = instrumentation.targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            targetContext.checkSelfPermission(CAR_DRIVE_PERMISSION),
        )

        val host = PleosComposeHost()
        try {
            host.launch()
            Log.i(LOG_TAG, STAGE_HOST_LAUNCHED)
            val application = targetContext.applicationContext as SocialDriveApplication
            val source = application.vehicleMotionSource
            Log.i(LOG_TAG, STAGE_SOURCE_ACQUIRED)

            val observed = runBlocking {
                withTimeoutOrNull(PARK_CALLBACK_TIMEOUT_MILLIS) {
                    source.status
                        .filter { status -> status.motion == MotionState.Parked }
                        .first()
                }
            }

            assertNotNull(
                "Pleos Gear PARK was not observed at the application boundary within the deadline",
                observed,
            )
            val status = requireNotNull(observed)
            Log.i(LOG_TAG, STAGE_PARK_OBSERVED)
            assertEquals(MotionState.Parked, status.motion)
            assertEquals(IntegrationSource.Actual, status.integration.source)
            assertEquals(CapabilityState.Available, status.integration.capability)
            Log.i(LOG_TAG, STAGE_ASSERTED)
        } finally {
            Log.i(LOG_TAG, STAGE_CLOSE_START)
            closeWithinDeadline(host)
            Log.i(LOG_TAG, STAGE_CLOSE_DONE)
        }
    }

    private fun closeWithinDeadline(host: PleosComposeHost) {
        val completed = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val cleanup = thread(name = "pleos-gear-smoke-cleanup", isDaemon = true) {
            try {
                host.close()
            } catch (_: Throwable) {
                failed.set(true)
            } finally {
                completed.countDown()
            }
        }
        if (!completed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            cleanup.interrupt()
            throw AssertionError("Pleos Gear smoke cleanup exceeded its deadline")
        }
        if (failed.get()) throw AssertionError("Pleos Gear smoke cleanup failed")
    }

    private companion object {
        const val REQUIRE_PLEOS_GEAR_ARGUMENT = "requirePleosGear"
        const val CAR_DRIVE_PERMISSION = "pleos.car.permission.CAR_DRIVE"
        const val PARK_CALLBACK_TIMEOUT_MILLIS = 10_000L
        const val CLOSE_TIMEOUT_SECONDS = 10L
        const val LOG_TAG = "PleosGearSmoke"
        const val STAGE_START = "stage=start"
        const val STAGE_HOST_LAUNCHED = "stage=host_launched"
        const val STAGE_SOURCE_ACQUIRED = "stage=source_acquired"
        const val STAGE_PARK_OBSERVED = "stage=park_observed"
        const val STAGE_ASSERTED = "stage=asserted"
        const val STAGE_CLOSE_START = "stage=close_start"
        const val STAGE_CLOSE_DONE = "stage=close_done"
    }
}
