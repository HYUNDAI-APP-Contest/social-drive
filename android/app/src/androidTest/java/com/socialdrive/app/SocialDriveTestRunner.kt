package com.socialdrive.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class SocialDriveTestApplication : SocialDriveApplication() {
    override fun recoverQueuedWork() = Unit
}

class SocialDriveTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, SocialDriveTestApplication::class.java.name, context)
}
