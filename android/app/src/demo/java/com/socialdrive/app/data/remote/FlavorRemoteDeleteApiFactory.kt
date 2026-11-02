package com.socialdrive.app.data.remote

object FlavorRemoteDeleteApiFactory {
    fun create(): RemoteDeleteApi = FakeRemoteDeleteApi()
}
