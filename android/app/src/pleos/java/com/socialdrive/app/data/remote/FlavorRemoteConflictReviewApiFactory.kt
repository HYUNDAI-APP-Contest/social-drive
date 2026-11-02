package com.socialdrive.app.data.remote

/** Actual cloud/mobile auth configuration is intentionally absent and fails closed. */
object FlavorRemoteConflictReviewApiFactory {
    fun create(): RemoteConflictReviewApi = UnavailableRemoteConflictReviewApi()
}
