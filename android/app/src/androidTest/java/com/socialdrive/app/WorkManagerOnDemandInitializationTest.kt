package com.socialdrive.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import com.socialdrive.app.data.work.PublishWorkerFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerOnDemandInitializationTest {
    @Test
    fun instrumentationApplicationProvidesTheCustomWorkerFactory() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(application is SocialDriveTestApplication)
        assertTrue(application is Configuration.Provider)
        val provider = application as Configuration.Provider
        assertTrue(provider.workManagerConfiguration.workerFactory is PublishWorkerFactory)
    }
}
