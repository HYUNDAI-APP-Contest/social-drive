package com.socialdrive.app.data.remote

/** Demo is the only flavor allowed to simulate cloud publication. */
object FlavorRemoteDriveApiFactory {
    fun create(): RemoteDriveApi = FakeRemoteDriveApi()
}
