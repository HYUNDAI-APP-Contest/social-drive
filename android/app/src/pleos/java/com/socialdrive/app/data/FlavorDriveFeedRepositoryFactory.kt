package com.socialdrive.app.data

import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.PleosCloudRuntime

object FlavorDriveFeedRepositoryFactory {
    fun create(database: SocialDriveDatabase): DriveFeedRepository =
        PersistentCloudDriveFeedRepository(database, PleosCloudRuntime::clientOrNull)
}
