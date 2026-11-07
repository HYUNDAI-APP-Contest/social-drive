package com.socialdrive.app.data

import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.data.local.SocialDriveDatabase

object FlavorDriveFeedRepositoryFactory {
    fun create(database: SocialDriveDatabase): DriveFeedRepository =
        StaticDriveFeedRepository(FakeDriveRepository().drives())
}
