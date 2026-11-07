package com.socialdrive.app.data.remote

/** Initializes the flavor-specific authentication SDK and restores a secure SDK session, if any. */
fun interface CloudAuthBootstrap {
    suspend fun initialize()
}
