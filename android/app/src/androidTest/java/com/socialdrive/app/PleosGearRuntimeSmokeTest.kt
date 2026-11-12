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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Opt-in evidence that a real Vehicle Gear signal reaches the application boundary.
 * Normal connected suites skip this test because a standard emulator has no Pleos Vehicle Service.
 */
@RunWith(AndroidJUnit4::class)
class PleosGearRuntimeSmokeTest {
    @Test
    fun actualGearSignalReachesExpectedApplicationMotionSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires an explicitly prepared Pleos Gear runtime",
            arguments.getString(REQUIRE_PLEOS_GEAR_ARGUMENT) == "true",
        )
        val expectedMotion = expectedMotion(arguments.getString(EXPECTED_MOTION_ARGUMENT))
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
                        .filter { status -> status.motion == expectedMotion }
                        .first()
                }
            }

            assertNotNull(
                "Expected Pleos motion was not observed at the application boundary within the deadline",
                observed,
            )
            val status = requireNotNull(observed)
            Log.i(LOG_TAG, STAGE_MOTION_OBSERVED)
            assertEquals(expectedMotion, status.motion)
            assertEquals(IntegrationSource.Actual, status.integration.source)
            assertEquals(CapabilityState.Available, status.integration.capability)
            Log.i(LOG_TAG, STAGE_ASSERTED)
        } finally {
            Log.i(LOG_TAG, STAGE_CLOSE_START)
            closeWithinDeadline(host)
            Log.i(LOG_TAG, STAGE_CLOSE_DONE)
        }
    }

    private fun expectedMotion(argument: String?): MotionState = when (argument?.lowercase()) {
        null,
        "parked",
        -> MotionState.Parked
        "driving" -> MotionState.Driving
        else -> throw AssertionError("expectedPleosMotion must be parked or driving")
    }

    private fun closeWithinDeadline(host: PleosComposeHost) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val cleanup = thread(name = "pleos-gear-smoke-cleanup", isDaemon = true) {
            try {
                host.close()
            } catch (cause: Throwable) {
                failure.set(cause)
            } finally {
                completed.countDown()
            }
        }
        if (!completed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            cleanup.interrupt()
            throw AssertionError("Pleos Gear smoke cleanup exceeded its deadline")
        }
        failure.get()?.let { cause ->
            throw AssertionError("Pleos Gear smoke cleanup failed").apply { initCause(cause) }
        }
    }

    private companion object {
        const val REQUIRE_PLEOS_GEAR_ARGUMENT = "requirePleosGear"
        const val EXPECTED_MOTION_ARGUMENT = "expectedPleosMotion"
        const val CAR_DRIVE_PERMISSION = "pleos.car.permission.CAR_DRIVE"
        const val PARK_CALLBACK_TIMEOUT_MILLIS = 10_000L
        // Host cleanup includes lifecycle destruction plus pre/post PLEOS process and log audits.
        const val CLOSE_TIMEOUT_SECONDS = 30L
        const val LOG_TAG = "PleosGearSmoke"
        const val STAGE_START = "stage=start"
        const val STAGE_HOST_LAUNCHED = "stage=host_launched"
        const val STAGE_SOURCE_ACQUIRED = "stage=source_acquired"
        const val STAGE_MOTION_OBSERVED = "stage=motion_observed"
        const val STAGE_ASSERTED = "stage=asserted"
        const val STAGE_CLOSE_START = "stage=close_start"
        const val STAGE_CLOSE_DONE = "stage=close_done"
    }
}
